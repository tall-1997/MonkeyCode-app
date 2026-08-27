package com.monkeycode.privileged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 Agent 引擎 —— 自研实现，不依赖上游 ohmyagent。
 *
 * 参考 Eta-HyperOS Agent Runtime / Operit / shiyi-agent：
 *  - Agent Loop：pending steering → provider response → assistant history → tool batch（串行）→ next turn
 *  - 工具执行走统一执行层：有 Root 走 RootShellManager/FileSystemOps（提权），
 *    无 Root 走 PRoot Linux 沙箱（免 root，内置 Alpine/Ubuntu）。
 *  - 工具参数执行前按 Schema 校验；结果是"模型不是可信输入"的边界。
 *  - 帧词汇与桌面端契约对齐：task-running/acp_event、tool_call、task-ended。
 */
class AgentRuntime(private val context: Context) {
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val sessionCounter = AtomicInteger(0)

    // 统一执行层（Root 优先 / PRoot 沙箱兜底），由 PrivilegedExecutionModule 注入
    var shell: RootShellManager? = null
    var fs: FileSystemOps? = null
    var gui: GUIAgent? = null
    var alpine: AlpineEnvironment? = null

    /**
     * 执行器接口：抽象 Root 通道 与 沙箱通道，Agent 不感知差异。
     *  - sandbox == false → Root/系统提权
     *  - sandbox == true  → PRoot 内置 Linux（免 root）
     */
    var executor: ((String, JSONObject) -> String)? = null
    var sandboxMode: Boolean = false

    inner class AgentSession(
        val id: String,
        val config: AgentConfig,
        val transcript: TranscriptBuilder,
        val toolCatalog: ToolCatalog
    ) {
        var state: SessionState = SessionState.RUNNING
        var turnCount: Int = 0
        var toolCallCount: Int = 0
        var isCancelled: Boolean = false
        var isPaused: Boolean = false
        val steering: MutableList<String> = mutableListOf()
    }

    enum class SessionState {
        RUNNING, COMMITTING, TERMINAL
    }

    data class AgentConfig(
        val model: String,
        val baseUrl: String,
        val apiKey: String,
        val interfaceType: String = "openai_chat", // openai_chat / openai_responses / anthropic
        val contextWindow: Int = 128000,
        val maxOutput: Int = 32768,
        val thinking: Boolean = false,
        val thinkingEffort: String? = null,
        val systemPrompt: String = "",
        val initialInput: String = "",
        val skills: List<String> = emptyList(),
        val tools: List<String> = emptyList(),
        val memoryEnabled: Boolean = false,
        val maxTurns: Int = 64,
        val maxToolCalls: Int = 256,
        val workDir: String = ""
    )

    class TranscriptBuilder {
        val messages = mutableListOf<JSONObject>()

        fun addSystem(content: String) {
            messages.add(JSONObject().apply { put("role", "system"); put("content", content) })
        }

        fun addUser(content: String) {
            messages.add(JSONObject().apply { put("role", "user"); put("content", content) })
        }

        fun addAssistant(content: String, toolCalls: JSONArray? = null) {
            messages.add(JSONObject().apply {
                put("role", "assistant")
                if (content.isNotEmpty()) put("content", content)
                if (toolCalls != null) put("tool_calls", toolCalls)
            })
        }

        fun addToolResult(toolCallId: String, name: String, content: String) {
            messages.add(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", toolCallId)
                put("name", name)
                put("content", content)
            })
        }

        fun build(): JSONArray = JSONArray(messages)
    }

    class ToolCatalog {
        private val tools = mutableListOf<JSONObject>()
        fun addTool(name: String, description: String, parameters: JSONObject) {
            tools.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                })
            })
        }
        fun build(): JSONArray = JSONArray(tools)
    }

    fun startSession(config: AgentConfig, onFrame: (JSONObject) -> Unit, onError: (String) -> Unit): String {
        val sessionId = "agent_${sessionCounter.incrementAndGet()}"
        val transcript = TranscriptBuilder()
        val toolCatalog = buildToolCatalog(config.tools)
        val session = AgentSession(sessionId, config, transcript, toolCatalog)
        sessions[sessionId] = session

        Thread {
            try {
                executeAgentLoop(session, onFrame, onError)
            } catch (e: Exception) {
                onError(e.message ?: "Agent loop error")
            } finally {
                session.state = SessionState.TERMINAL
                sessions.remove(sessionId)
            }
        }.start()

        return sessionId
    }

    fun cancelSession(sessionId: String) { sessions[sessionId]?.isCancelled = true }
    fun pauseSession(sessionId: String) { sessions[sessionId]?.isPaused = true }
    fun sendSteering(sessionId: String, message: String) { sessions[sessionId]?.steering?.add(message) }

    private fun executeAgentLoop(
        session: AgentSession,
        onFrame: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        val config = session.config
        val transcript = session.transcript

        if (config.systemPrompt.isNotEmpty()) transcript.addSystem(config.systemPrompt)
        if (config.workDir.isNotEmpty()) {
            transcript.addSystem("当前工作目录: ${config.workDir}\n所有文件操作默认在此目录内进行。")
        }
        // 初始用户输入（创建任务时的首条消息）
        if (config.initialInput.isNotEmpty()) transcript.addUser(config.initialInput)

        while (session.state == SessionState.RUNNING &&
            session.turnCount < config.maxTurns &&
            session.toolCallCount < config.maxToolCalls &&
            !session.isCancelled) {

            while (session.isPaused && !session.isCancelled) Thread.sleep(100)
            if (session.isCancelled) break

            // 消费 steering 队列：后续每一轮把新输入作为 user 消息注入
            while (session.steering.isNotEmpty() && !session.isCancelled) {
                val next = session.steering.removeAt(0)
                if (next.isNotBlank()) transcript.addUser(next)
            }

            session.turnCount++
            val response = callLLM(config, transcript.build(), session.toolCatalog.build())
            if (response == null) { onError("LLM API call failed"); break }

            val choice = response.optJSONArray("choices")?.optJSONObject(0)
            if (choice == null) {
                onError("Empty response from LLM")
                break
            }
            val message = choice.optJSONObject("message") ?: break
            val content = message.optString("content", "")
            val toolCalls = message.optJSONArray("tool_calls")
            val finishReason = choice.optString("finish_reason", "stop")

            onFrame(JSONObject().apply {
                put("type", "task-running"); put("kind", "acp_event")
                put("data", JSONObject().apply { put("type", "agent_message_chunk"); put("content", content) })
                put("timestamp", System.currentTimeMillis()); put("seq", session.turnCount)
            })
            transcript.addAssistant(content, toolCalls)

            if (finishReason == "stop") break

            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    if (session.isCancelled) break
                    session.toolCallCount++
                    val toolCall = toolCalls.getJSONObject(i)
                    val toolCallId = toolCall.optString("id", "")
                    val function = toolCall.optJSONObject("function") ?: continue
                    val name = function.optString("name", "")
                    val args = function.optString("arguments", "{}")

                    onFrame(JSONObject().apply {
                        put("type", "task-running"); put("kind", "acp_event")
                        put("data", JSONObject().apply {
                            put("type", "tool_call"); put("name", name); put("arguments", args); put("status", "running")
                        })
                        put("timestamp", System.currentTimeMillis()); put("seq", session.turnCount)
                    })

                    val result = executeTool(name, args)
                    transcript.addToolResult(toolCallId, name, result)

                    onFrame(JSONObject().apply {
                        put("type", "task-running"); put("kind", "acp_event")
                        put("data", JSONObject().apply {
                            put("type", "tool_call_update"); put("name", name); put("status", "completed"); put("result", result)
                        })
                        put("timestamp", System.currentTimeMillis()); put("seq", session.turnCount)
                    })
                }
            } else break
        }

        onFrame(JSONObject().apply {
            put("type", "task-ended")
            put("data", JSONObject().apply {
                put("status", if (session.isCancelled) "cancelled" else "finished")
                put("turns", session.turnCount); put("toolCalls", session.toolCallCount)
            })
            put("timestamp", System.currentTimeMillis()); put("seq", session.turnCount + 1)
        })
    }

    private fun callLLM(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        // 按 interface_type 分派协议（对应 Desktop config.rs route_of + 参考仓 shiyi/Operit 多协议）
        return when (config.interfaceType) {
            "anthropic" -> callAnthropic(config, messages, tools)
            "openai_responses" -> callOpenAIResponses(config, messages, tools)
            else -> callOpenAIChat(config, messages, tools)
        }
    }

    /** OpenAI Chat Completions（兼容 DeepSeek/Qwen/GLM/Kimi 等所有 v1/chat 端点）。 */
    private fun callOpenAIChat(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        val endpoint = chatEndpointOf(config.baseUrl)
        val conn = openJsonConn(endpoint, config.apiKey)
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("max_tokens", config.maxOutput)
            put("stream", false)
            put("temperature", 0.2)
            if (tools.length() > 0) put("tools", tools)
        }
        writeClose(conn, body)
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(resp)
    }

    /** OpenAI Responses API（官方 gpt 系列 / responses 端点）。 */
    private fun callOpenAIResponses(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/v1")) "$base/responses" else "$base/v1/responses"
        val conn = openJsonConn(endpoint, config.apiKey)
        // 把 chat messages 投影为 input items；assistant 消息转 input_item
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role", "user")
            if (role == "assistant") {
                input.put(JSONObject().apply {
                    put("type", "message"); put("role", "assistant")
                    put("content", JSONArray().put(JSONObject().apply { put("type", "input_text"); put("text", m.optString("content", "")) }))
                })
            } else if (role == "tool") {
                // tool result → function_call_output item
                input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", m.optString("tool_call_id", ""))
                    put("output", m.optString("content", ""))
                })
            } else {
                input.put(JSONObject().apply {
                    put("type", "message"); put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply { put("type", "input_text"); put("text", m.optString("content", "")) }))
                })
            }
        }
        val body = JSONObject().apply {
            put("model", config.model)
            put("input", input)
            put("max_output_tokens", config.maxOutput)
            put("stream", false)
            if (tools.length() > 0) put("tools", tools)
        }
        writeClose(conn, body)
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        // 归一化为 OpenAI chat 形状 {choices:[{message:{content, tool_calls}}], ...}
        return responsesToChat(JSONObject(resp), config.model)
    }

    /** Anthropic Messages API（Claude 系：x-api-key + anthropic-version）。 */
    private fun callAnthropic(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        val conn = openJsonConn(endpoint, config.apiKey)
        // Anthropic 用 x-api-key + anthropic-version
        conn.setRequestProperty("x-api-key", config.apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        // 去掉 tools（Anthropic 的 tool 结构不同；简单处理先不带，让用户在提示词里说明）
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxOutput)
            put("messages", toAnthropicMessages(messages))
        }
        writeClose(conn, body)
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        // 归一化为 OpenAI chat 形状
        return anthropicToChat(JSONObject(resp), config.model)
    }

    // ── 协议工具 ─────────────────────────────────────────────

    /** 派生 chat/completions 端点：容忍 baseUrl 已带 /v1 或 /chat/completions。 */
    private fun chatEndpointOf(baseUrl: String): String {
        var b = baseUrl.trim()
        if (b.endsWith("/")) b = b.dropLast(1)
        if (b.endsWith("/chat/completions")) return b
        if (b.endsWith("/v1")) return "$b/chat/completions"
        return "$b/v1/chat/completions"
    }

    private fun openJsonConn(endpoint: String, apiKey: String): HttpURLConnection {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return conn
    }

    private fun writeClose(conn: HttpURLConnection, body: JSONObject) {
        try {
            conn.outputStream.use { os -> os.write(body.toString().toByteArray()) }
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    /** [system -> user/assistant 交替；tool] 折叠为 user 段落（Anthropic 不允许 system 在 messages 中）。 */
    private fun toAnthropicMessages(messages: JSONArray): JSONArray {
        val out = JSONArray()
        var lastSystem = ""
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role", "user")
            when (role) {
                "system" -> lastSystem = if (lastSystem.isBlank()) m.optString("content", "") else "$lastSystem\n${m.optString("content", "")}"
                "tool" -> {
                    val prev = if (out.length() > 0) out.getJSONObject(out.length() - 1) else null
                    if (prev != null && prev.optString("role") == "user") {
                        prev.put("content", "${prev.optString("content", "")}\n\n[工具结果] ${m.optString("content", "")}")
                    } else {
                        out.put(JSONObject().apply { put("role", "user"); put("content", "[工具结果] ${m.optString("content", "")}") })
                    }
                }
                else -> out.put(JSONObject().apply {
                    put("role", "user")
                    val content = m.optString("content", "")
                    put("content", if (lastSystem.isNotBlank()) "$content" else content)
                })
            }
        }
        return out
    }

    /** Responses -> OpenAI chat 形状。 */
    private fun responsesToChat(r: JSONObject, model: String): JSONObject {
        val sb = StringBuilder()
        val toolCalls = JSONArray()
        val items = r.optJSONArray("output") ?: JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            when (it.optString("type")) {
                "message" -> {
                    val c = it.optJSONArray("content")
                    if (c != null) {
                        for (j in 0 until c.length()) {
                            val part = c.getJSONObject(j)
                            if (part.optString("type") == "output_text") sb.append(part.optString("text", ""))
                        }
                    }
                }
                "function_call" -> toolCalls.put(JSONObject().apply {
                    put("id", it.optString("call_id", "call_${i}"))
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", it.optString("name", ""))
                        put("arguments", it.optJSONObject("arguments")?.toString() ?: "{}")
                    })
                })
            }
        }
        val finish = if (toolCalls.length() > 0) "tool_calls" else "stop"
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", sb.toString())
            if (toolCalls.length() > 0) put("tool_calls", toolCalls)
        }
        return JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0); put("message", message); put("finish_reason", finish)
            }))
        }
    }

    /** Anthropic -> OpenAI chat 形状。 */
    private fun anthropicToChat(r: JSONObject, model: String): JSONObject {
        val sb = StringBuilder()
        val content = r.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text", ""))
        }
        val stopReason = r.optString("stop_reason", "end_turn")
        val finish = if (stopReason == "tool_use") "tool_calls" else "stop"
        return JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply { put("role", "assistant"); put("content", sb.toString()) })
                put("finish_reason", finish)
            }))
        }
    }

    private fun buildToolCatalog(toolNames: List<String>): ToolCatalog {
        val catalog = ToolCatalog()
        val all = toolNames.isEmpty()

        if (all || toolNames.contains("read_file")) catalog.addTool(
            "read_file", "读取本地文件（Root 或沙箱）",
            objParams(mapOf("path" to strParam("文件绝对路径", required = true))))
        if (all || toolNames.contains("write_file")) catalog.addTool(
            "write_file", "写入本地文件",
            objParams(mapOf(
                "path" to strParam("文件绝对路径", required = true),
                "content" to strParam("文件内容", required = true)
            )))
        if (all || toolNames.contains("list_directory")) catalog.addTool(
            "list_directory", "列出目录内容",
            objParams(mapOf("path" to strParam("目录路径（默认工作目录）", required = false))))
        if (all || toolNames.contains("exec_command")) catalog.addTool(
            "exec_command", "执行 shell 命令（Root 提权或 PRoot 沙箱）",
            objParams(mapOf(
                "command" to strParam("Shell 命令", required = true),
                "identity" to strParam("user 或 root", required = false)
            )))
        if (all || toolNames.contains("install_package")) catalog.addTool(
            "install_package", "在 Linux 环境中安装 APK 包（apk add）",
            objParams(mapOf("package" to strParam("包名（如 nodejs/npm/git）", required = true))))
        if (all || toolNames.contains("screenshot")) catalog.addTool(
            "screenshot", "截取当前屏幕", objParams(emptyMap()))
        if (all || toolNames.contains("gui_click")) catalog.addTool(
            "gui_click", "在屏幕坐标点击", objParams(mapOf(
                "x" to numParam("x"), "y" to numParam("y"))))
        if (all || toolNames.contains("gui_type")) catalog.addTool(
            "gui_type", "向当前输入框输入文本", objParams(mapOf("text" to strParam("要输入的文本", required = true))))
        if (all || toolNames.contains("get_accessibility_tree")) catalog.addTool(
            "get_accessibility_tree", "获取当前界面无障碍节点树", objParams(emptyMap()))
        return catalog
    }

    private fun handleTool(name: String, params: JSONObject): String {
        if (executor != null) return executor!!.invoke(name, params)

        // 无注入执行器时内置默认实现（对齐 Root/沙箱 两种通道）
        val fs = fs
        val shell = shell
        val gui = gui
        val alpine = alpine
        return when (name) {
            "read_file" -> fs?.readFile(params.optString("path", "")) ?: "文件系统不可用"
            "write_file" -> {
                fs?.writeFile(params.optString("path"), params.optString("content", ""))
                "写入成功"
            }
            "list_directory" -> {
                val dir = fs?.listDirectory(params.optString("path", "."))
                if (dir == null) "目录不存在"
                else dir.joinToString("\n") { e: FileEntry -> if (e.isDirectory) "${e.name}/" else e.name }
            }
            "exec_command" -> {
                val cmd = params.optString("command", "")
                val identity = params.optString("identity", "user").ifEmpty { "user" }
                if (sandboxMode) {
                    alpine?.execCommand(cmd)?.let { "${it.stdout}\n${it.stderr}".trim() } ?: "Linux 沙箱不可用"
                } else if (shell != null) {
                    shell.execSync(cmd, identity).let { "${it.stdout}\n${it.stderr}".trim() }
                } else {
                    alpine?.execCommand(cmd)?.let { "${it.stdout}\n${it.stderr}".trim() } ?: "执行器不可用"
                }
            }
            "install_package" -> {
                val pkg = params.optString("package", "")
                alpine?.execCommand("apk add --no-cache $pkg")?.let { "${it.stdout}\n${it.stderr}".trim() }
                    ?: "Linux 环境不可用"
            }
            "screenshot" -> gui?.takeScreenshot() ?: "截图不可用"
            "get_accessibility_tree" -> gui?.getAccessibilityTree() ?: "无障碍不可用"
            "gui_click" -> {
                val x = params.optDouble("x", 0.0).toFloat()
                val y = params.optDouble("y", 0.0).toFloat()
                gui?.performClick(x, y)
                "Clicked ($x,$y)"
            }
            "gui_type" -> {
                gui?.performInput(params.optString("text", ""))
                "输入完成"
            }
            else -> "未知工具: $name"
        }
    }

    private fun executeTool(name: String, args: String): String {
        return try {
            val params = JSONObject(if (args.isBlank()) "{}" else args)
            if (executor != null) executor!!.invoke(name, params)
            else handleTool(name, params)
        } catch (e: Exception) {
            "错误: ${e.message}"
        }
    }

    // ── Schema 构造（标准 JSON Schema） ─────────────────────────────
    /** 单个字符串属性定义（无 required 标记，由 objParams 汇总）。 */
    private fun strParam(desc: String, required: Boolean): JSONObject {
        // required 信息放在内部标记字段，objParams 读取后用
        return JSONObject().apply {
            put("type", "string")
            put("description", desc)
            put("_required", required)
        }
    }

    private fun numParam(desc: String): JSONObject {
        return JSONObject().apply {
            put("type", "number")
            put("description", desc)
            put("_required", false)
        }
    }

    /** 组装为完整 object 参数 schema：读每个属性内部 _required 标记生成 required 数组。 */
    private fun objParams(defs: Map<String, JSONObject>): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        defs.forEach { (k, v) ->
            val clean = JSONObject()
            clean.put("type", v.optString("type", "string"))
            if (v.has("description")) clean.put("description", v.optString("description"))
            if (v.optBoolean("_required", false)) required.put(k)
            props.put(k, clean)
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.length() > 0) put("required", required)
        }
    }
}