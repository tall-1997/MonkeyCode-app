package com.monkeycode.privileged

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 Agent 引擎 —— 自研实现，对齐桌面端 MonkeyCode 协议规格。
 *
 * 参考 Eta-HyperOS Agent Runtime / Operit / shiyi-agent：
 *  - Agent Loop: pending steering → provider response → assistant history → tool batch → next turn
 *  - 工具执行走统一执行层: 有 Root 走 RootShellManager/FileSystemOps, 无 Root 走 PRoot Linux 沙箱
 *  - 工具参数执行前按 Schema 校验; 结果边界是"模型不是可信输入"
 *  - 帧词汇与桌面端 frame.rs 对齐: 完整帧类型
 *  - 流式 SSE 输出, 审批流, 子代理, 技能系统, 会话持久化, 上下文管理, MCP 集成
 */
class AgentRuntime(private val context: Context) {
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val sessionCounter = AtomicInteger(0)
    private val frameSeq = AtomicLong(0)

    var sessionManager: SessionManager? = null
    var skillManager: SkillManager? = null
    var subagentManager: SubagentManager? = null
    var mcpClient: McpClient? = null

    var shell: RootShellManager? = null
    var fs: FileSystemOps? = null
    var gui: GUIAgent? = null
    var alpine: AlpineEnvironment? = null
    var executor: ((String, JSONObject) -> String)? = null
    var sandboxMode: Boolean = false

    enum class SessionStatus {
        CREATED, RUNNING, IDLE, FINISHED, INTERRUPTED, ERROR;

        fun asString(): String = when (this) {
            CREATED -> "created"
            RUNNING -> "running"
            IDLE -> "idle"
            FINISHED -> "finished"
            INTERRUPTED -> "interrupted"
            ERROR -> "error"
        }
    }

    enum class PermOutcome {
        APPROVED, DENIED, TIMEOUT, CANCELLED;

        fun asString(): String = when (this) {
            APPROVED -> "approved"
            DENIED -> "denied"
            TIMEOUT -> "timeout"
            CANCELLED -> "cancelled"
        }
    }

    inner class AgentSession(
        val id: String,
        val config: AgentConfig,
        val transcript: TranscriptBuilder,
        val toolCatalog: ToolCatalog
    ) {
        var status: SessionStatus = SessionStatus.CREATED
        var turnCount: Int = 0
        var toolCallCount: Int = 0
        var isCancelled: Boolean = false
        var isPaused: Boolean = false
        val steering: MutableList<String> = mutableListOf()
        var engineId: String = ""
        var parentSessionId: String? = null
        var promptTokens: Long = 0
        var completionTokens: Long = 0
        val permissionDecisions = ConcurrentHashMap<String, PermOutcome>()
        val permissionRemember = ConcurrentHashMap<String, Boolean>()
        val pendingPermissions = ConcurrentHashMap<String, JSONObject>()
    }

    data class AgentConfig(
        val model: String = "deepseek-chat",
        val baseUrl: String = "https://api.deepseek.com/v1",
        val apiKey: String = "",
        val interfaceType: String = "openai_chat",
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
        val workDir: String = "",
        val streamEnabled: Boolean = true,
        val compactThreshold: Double = 0.8
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
        fun size(): Int = tools.size
    }

    fun startSession(config: AgentConfig, onFrame: (JSONObject) -> Unit, onError: (String) -> Unit): String {
        val sessionId = "agent_${sessionCounter.incrementAndGet()}"
        val transcript = TranscriptBuilder()
        val toolCatalog = buildToolCatalog(config.tools)
        val session = AgentSession(sessionId, config, transcript, toolCatalog)
        session.status = SessionStatus.CREATED
        sessions[sessionId] = session

        sessionManager?.let { sm ->
            val window = sm.sessionOpen(sessionId, "", sessionId)
            session.engineId = sessionId
        }

        subagentManager = subagentManager ?: SubagentManager(this)

        emitFrame(session, onFrame, "task-started", null, null)

        Thread {
            try {
                session.status = SessionStatus.RUNNING
                sessionManager?.updateStatus(sessionId, SessionManager.SessionStatus.RUNNING)
                executeAgentLoop(session, onFrame, onError)
            } catch (e: Exception) {
                session.status = SessionStatus.ERROR
                emitFrame(session, onFrame, "task-error", null, JSONObject().apply {
                    put("error", e.message ?: "Agent loop error")
                    put("terminal", true)
                })
                sessionManager?.reconcile(sessionId, e.message ?: "Agent loop error")
                onError(e.message ?: "Agent loop error")
            } finally {
                if (session.status == SessionStatus.RUNNING) {
                    session.status = SessionStatus.FINISHED
                }
                sessionManager?.updateStatus(sessionId, SessionManager.SessionStatus.fromString(session.status.asString()))
                emitFrame(session, onFrame, "task-ended", null, JSONObject().apply {
                    put("status", if (session.isCancelled) "cancelled" else "finished")
                    put("turns", session.turnCount)
                    put("toolCalls", session.toolCallCount)
                })
                sessions.remove(sessionId)
            }
        }.start()

        return sessionId
    }

    fun cancelSession(sessionId: String) { sessions[sessionId]?.isCancelled = true }
    fun pauseSession(sessionId: String) { sessions[sessionId]?.isPaused = true }
    fun sendSteering(sessionId: String, message: String) { sessions[sessionId]?.steering?.add(message) }

    fun approvePermission(sessionId: String, permId: String, remember: Boolean = false) {
        val session = sessions[sessionId] ?: return
        session.permissionDecisions[permId] = PermOutcome.APPROVED
        if (remember) session.permissionRemember[permId] = true
        emitFrame(session, { sessionManager?.appendFrame(sessionId, it) }, "permission-resolved", null, JSONObject().apply {
            put("id", permId)
            put("outcome", "approved")
        })
    }

    fun denyPermission(sessionId: String, permId: String) {
        val session = sessions[sessionId] ?: return
        session.permissionDecisions[permId] = PermOutcome.DENIED
        emitFrame(session, { sessionManager?.appendFrame(sessionId, it) }, "permission-resolved", null, JSONObject().apply {
            put("id", permId)
            put("outcome", "denied")
        })
    }

    fun spawnAgent(config: SubagentManager.SpawnConfig, parentSessionId: String, onFrame: (JSONObject) -> Unit, onError: (String) -> Unit): String? {
        return subagentManager?.spawn(config, parentSessionId, onFrame, onError)
    }

    private fun executeAgentLoop(
        session: AgentSession,
        onFrame: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        val config = session.config
        val transcript = session.transcript

        if (config.systemPrompt.isNotEmpty()) {
            transcript.addSystem(config.systemPrompt)
        }

        if (config.workDir.isNotEmpty()) {
            transcript.addSystem("当前工作目录: ${config.workDir}\n所有文件操作默认在此目录内进行。")
        }

        skillManager?.let { sm ->
            val skillPrompt = sm.generateSkillSystemPrompt()
            if (skillPrompt.isNotEmpty()) {
                transcript.addSystem(skillPrompt)
            }
        }

        if (config.initialInput.isNotEmpty()) {
            emitFrame(session, onFrame, "user-input", null, JSONObject().apply {
                put("content", b64Text(config.initialInput))
            })
            transcript.addUser(config.initialInput)
        }

        while (session.status == SessionStatus.RUNNING &&
            session.turnCount < config.maxTurns &&
            session.toolCallCount < config.maxToolCalls &&
            !session.isCancelled) {

            while (session.isPaused && !session.isCancelled) Thread.sleep(100)
            if (session.isCancelled) break

            while (session.steering.isNotEmpty() && !session.isCancelled) {
                val next = session.steering.removeAt(0)
                if (next.isNotBlank()) {
                    emitFrame(session, onFrame, "user-input", null, JSONObject().apply {
                        put("content", b64Text(next))
                        put("source", "steer")
                    })
                    transcript.addUser(next)
                }
            }

            session.turnCount++
            session.status = SessionStatus.RUNNING

            checkContextCompact(session, onFrame)

            val response = if (config.streamEnabled && config.interfaceType == "openai_chat") {
                callOpenAIChatStreaming(config, transcript.build(), session.toolCatalog.build(), session, onFrame, onError)
            } else {
                callLLM(config, transcript.build(), session.toolCatalog.build())
            }

            if (response == null) {
                onError("LLM API call failed")
                emitFrame(session, onFrame, "task-error", null, JSONObject().apply {
                    put("error", "LLM API call failed")
                    put("terminal", false)
                })
                if (session.turnCount >= config.maxTurns) break
                Thread.sleep(1000)
                continue
            }

            val choice = response.optJSONArray("choices")?.optJSONObject(0)
            if (choice == null) {
                onError("Empty response from LLM")
                break
            }

            val message = choice.optJSONObject("message") ?: break
            val content = message.optString("content", "")
            val toolCalls = message.optJSONArray("tool_calls")
            val finishReason = choice.optString("finish_reason", "stop")

            if (finishReason == "stop") {
                session.status = SessionStatus.IDLE
                sessionManager?.updateStatus(session.id, SessionManager.SessionStatus.IDLE)
                break
            }

            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    if (session.isCancelled) break
                    session.toolCallCount++
                    val toolCall = toolCalls.getJSONObject(i)
                    val toolCallId = toolCall.optString("id", "")
                    val function = toolCall.optJSONObject("function") ?: continue
                    val name = function.optString("name", "")
                    val args = function.optString("arguments", "{}")

                    val approved = requestPermission(session, name, args, toolCallId, onFrame)
                    if (!approved) {
                        transcript.addToolResult(toolCallId, name, "权限被拒绝")
                        emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                            put("sessionUpdate", "tool_call_update")
                            put("toolCallId", toolCallId)
                            put("status", "failed")
                            put("rawOutput", "权限被拒绝")
                        })
                        continue
                    }

                    emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                        put("sessionUpdate", "tool_call")
                        put("toolCallId", toolCallId)
                        put("title", name)
                        put("status", "in_progress")
                        put("rawInput", JSONObject(args))
                    })

                    val result = executeTool(name, args)
                    transcript.addToolResult(toolCallId, name, result)

                    val resultSuccess = !result.startsWith("错误:")
                    emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                        put("sessionUpdate", "tool_call_update")
                        put("toolCallId", toolCallId)
                        put("status", if (resultSuccess) "completed" else "failed")
                        put("rawOutput", result)
                    })
                }
            } else break
        }

        if (session.status == SessionStatus.RUNNING) {
            session.status = SessionStatus.FINISHED
        }
        sessionManager?.updateStatus(session.id, SessionManager.SessionStatus.fromString(session.status.asString()))
    }

    private fun callOpenAIChatStreaming(
        config: AgentConfig,
        messages: JSONArray,
        tools: JSONArray,
        session: AgentSession,
        onFrame: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ): JSONObject? {
        val endpoint = chatEndpointOf(config.baseUrl)
        val conn = openJsonConn(endpoint, config.apiKey)
        conn.readTimeout = 300000
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("max_tokens", config.maxOutput)
            put("stream", true)
            put("temperature", 0.2)
            if (tools.length() > 0) put("tools", tools)
        }
        writeClose(conn, body)

        if (conn.responseCode != 200) {
            val errorBody = try { conn.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
            conn.disconnect()
            return callOpenAIChat(config, messages, tools)
        }

        val contentBuilder = StringBuilder()
        val toolCallsMap = mutableMapOf<Int, JSONObject>()
        val toolCallArgsMap = mutableMapOf<Int, StringBuilder>()
        var finishReason = "stop"
        var hasContent = false

        try {
            conn.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank() || !line!!.startsWith("data: ")) continue
                    val data = line!!.removePrefix("data: ")
                    if (data == "[DONE]") break

                    try {
                        val chunk = JSONObject(data)
                        val choices = chunk.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue
                        val textDelta = delta.optString("content", "")

                        if (textDelta.isNotEmpty()) {
                            contentBuilder.append(textDelta)
                            hasContent = true
                            emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                                put("sessionUpdate", "agent_message_chunk")
                                put("content", JSONObject().apply {
                                    put("type", "text")
                                    put("text", textDelta)
                                })
                            })
                        }

                        val reasoning = delta.optString("reasoning_content", "")
                        if (reasoning.isEmpty()) {
                            val reasoningObj = delta.optJSONObject("reasoning_content")
                            val reasoningStr = reasoningObj?.optString("text", "") ?: ""
                            if (reasoningStr.isNotEmpty()) {
                                emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                                    put("sessionUpdate", "agent_thought_chunk")
                                    put("content", JSONObject().apply {
                                        put("type", "text")
                                        put("text", reasoningStr)
                                    })
                                })
                            }
                        } else if (reasoning.isNotEmpty()) {
                            emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                                put("sessionUpdate", "agent_thought_chunk")
                                put("content", JSONObject().apply {
                                    put("type", "text")
                                    put("text", reasoning)
                                })
                            })
                        }

                        val toolCallsDelta = delta.optJSONArray("tool_calls")
                        if (toolCallsDelta != null) {
                            for (j in 0 until toolCallsDelta.length()) {
                                val tc = toolCallsDelta.getJSONObject(j)
                                val idx = tc.optInt("index", 0)
                                val tcId = tc.optString("id", "")
                                val func = tc.optJSONObject("function")
                                if (tcId.isNotEmpty()) {
                                    toolCallsMap[idx] = JSONObject().apply {
                                        put("id", tcId)
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", func?.optString("name", "") ?: "")
                                            put("arguments", "")
                                        })
                                    }
                                    toolCallArgsMap[idx] = StringBuilder()
                                }
                                val argsDelta = func?.optString("arguments", "") ?: ""
                                if (argsDelta.isNotEmpty()) {
                                    toolCallArgsMap.getOrPut(idx) { StringBuilder() }.append(argsDelta)
                                }
                            }
                        }

                        val usage = chunk.optJSONObject("usage")
                        if (usage != null) {
                            session.promptTokens = usage.optLong("prompt_tokens", session.promptTokens)
                            session.completionTokens = usage.optLong("completion_tokens", session.completionTokens)
                            emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                                put("sessionUpdate", "usage_update")
                                put("used", session.promptTokens + session.completionTokens)
                                put("size", config.contextWindow)
                            })
                        }

                        finishReason = choice.optString("finish_reason", finishReason)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            return callOpenAIChat(config, messages, tools)
        }
        conn.disconnect()

        for (idx in toolCallsMap.keys) {
            val args = toolCallArgsMap[idx]?.toString() ?: "{}"
            toolCallsMap[idx]?.getJSONObject("function")?.put("arguments", args)
        }

        val toolCalls = JSONArray()
        for (idx in toolCallsMap.keys.sorted()) {
            toolCalls.put(toolCallsMap[idx])
        }

        if (hasContent) {
            session.transcript.addAssistant(contentBuilder.toString(), if (toolCalls.length() > 0) toolCalls else null)
        }

        if (toolCalls.length() > 0) {
            finishReason = "tool_calls"
        }

        return JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", contentBuilder.toString())
                    if (toolCalls.length() > 0) put("tool_calls", toolCalls)
                })
                put("finish_reason", finishReason)
            }))
        }
    }

    private fun callLLM(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        return when (config.interfaceType) {
            "anthropic" -> callAnthropic(config, messages, tools)
            "openai_responses" -> callOpenAIResponses(config, messages, tools)
            else -> callOpenAIChat(config, messages, tools)
        }
    }

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

    fun callLLMInternal(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        return callLLM(config, messages, tools)
    }

    private fun callOpenAIResponses(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/v1")) "$base/responses" else "$base/v1/responses"
        val conn = openJsonConn(endpoint, config.apiKey)
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
        return responsesToChat(JSONObject(resp), config.model)
    }

    private fun callAnthropic(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        val conn = openJsonConn(endpoint, config.apiKey)
        conn.setRequestProperty("x-api-key", config.apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxOutput)
            put("messages", toAnthropicMessages(messages))
        }
        writeClose(conn, body)
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return anthropicToChat(JSONObject(resp), config.model)
    }

    fun chatEndpointOf(baseUrl: String): String {
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
                    put("content", m.optString("content", ""))
                })
            }
        }
        return out
    }

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
            "read_file", "读取本地文件",
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
            "exec_command", "执行 shell 命令",
            objParams(mapOf(
                "command" to strParam("Shell 命令", required = true),
                "identity" to strParam("user 或 root", required = false)
            )))
        if (all || toolNames.contains("install_package")) catalog.addTool(
            "install_package", "在 Linux 环境中安装 APK 包",
            objParams(mapOf("package" to strParam("包名", required = true))))
        if (all || toolNames.contains("screenshot")) catalog.addTool(
            "screenshot", "截取当前屏幕", objParams(emptyMap()))
        if (all || toolNames.contains("gui_click")) catalog.addTool(
            "gui_click", "在屏幕坐标点击", objParams(mapOf(
                "x" to numParam("x"), "y" to numParam("y"))))
        if (all || toolNames.contains("gui_type")) catalog.addTool(
            "gui_type", "向当前输入框输入文本", objParams(mapOf("text" to strParam("要输入的文本", required = true))))
        if (all || toolNames.contains("get_accessibility_tree")) catalog.addTool(
            "get_accessibility_tree", "获取当前界面无障碍节点树", objParams(emptyMap()))
        if (all || toolNames.contains("query_sms")) catalog.addTool(
            "query_sms", "查询短信记录",
            objParams(mapOf("query" to strParam("查询关键词", required = false), "limit" to numParam("返回条数"))))
        if (all || toolNames.contains("query_contacts")) catalog.addTool(
            "query_contacts", "查询通讯录",
            objParams(mapOf("query" to strParam("查询关键词", required = false), "limit" to numParam("返回条数"))))
        if (all || toolNames.contains("query_calendar")) catalog.addTool(
            "query_calendar", "查询日历事件",
            objParams(mapOf("start" to strParam("开始日期", required = false), "end" to strParam("结束日期", required = false))))
        if (all || toolNames.contains("set_alarm")) catalog.addTool(
            "set_alarm", "设置闹钟",
            objParams(mapOf("time" to strParam("闹钟时间", required = true), "label" to strParam("标签", required = false))))
        if (all || toolNames.contains("toggle_wifi")) catalog.addTool(
            "toggle_wifi", "开关 WiFi", objParams(mapOf("enable" to JSONObject().apply { put("type", "boolean"); put("description", "开启或关闭") })))
        if (all || toolNames.contains("toggle_bluetooth")) catalog.addTool(
            "toggle_bluetooth", "开关蓝牙", objParams(mapOf("enable" to JSONObject().apply { put("type", "boolean"); put("description", "开启或关闭") })))

        if (all || toolNames.contains("spawn_agent")) {
            catalog.tools.add(SubagentManager.buildSpawnToolSchema())
        }

        mcpClient?.let { mc ->
            mc.discoverTools()
            mc.registerToolsToCatalog(catalog)
        }

        return catalog
    }

    private fun isSensitiveTool(name: String): Boolean {
        return name in setOf(
            "exec_command", "install_package", "query_sms", "query_contacts",
            "query_calendar", "set_alarm", "toggle_wifi", "toggle_bluetooth",
            "write_file"
        )
    }

    private fun isWriteFileOutsideWorkdir(args: String, workDir: String): Boolean {
        try {
            val params = JSONObject(if (args.isBlank()) "{}" else args)
            val path = params.optString("path", "")
            if (path.isEmpty() || workDir.isEmpty()) return false
            return !path.startsWith(workDir)
        } catch (_: Exception) {
            return false
        }
    }

    private fun requestPermission(
        session: AgentSession,
        toolName: String,
        args: String,
        toolCallId: String,
        onFrame: (JSONObject) -> Unit
    ): Boolean {
        if (!isSensitiveTool(toolName)) return true

        if (toolName == "write_file" && !isWriteFileOutsideWorkdir(args, session.config.workDir)) {
            return true
        }

        val permId = "perm_${toolName}_${session.toolCallCount}"

        if (session.permissionRemember[toolName] == true) {
            return session.permissionDecisions[permId] != PermOutcome.DENIED
        }

        emitFrame(session, onFrame, "permission-req", null, JSONObject().apply {
            put("id", permId)
            put("tool", toolName)
            put("title", "请求执行: $toolName")
            put("tool_call_id", toolCallId)
        })

        sessionManager?.appendFrame(session.id, JSONObject().apply {
            put("type", "permission-req")
            put("data", JSONObject().apply {
                put("id", permId)
                put("tool", toolName)
                put("title", "请求执行: $toolName")
                put("tool_call_id", toolCallId)
            })
            put("timestamp", System.currentTimeMillis())
            put("seq", frameSeq.get())
        })

        val startTime = System.currentTimeMillis()
        val timeoutMs = 30000L

        while (System.currentTimeMillis() - startTime < timeoutMs && !session.isCancelled) {
            val decision = session.permissionDecisions[permId]
            if (decision != null) {
                session.permissionDecisions.remove(permId)
                return decision == PermOutcome.APPROVED
            }
            Thread.sleep(200)
        }

        session.permissionDecisions[permId] = PermOutcome.TIMEOUT
        emitFrame(session, onFrame, "permission-resolved", null, JSONObject().apply {
            put("id", permId)
            put("outcome", "timeout")
        })
        return false
    }

    private fun checkContextCompact(session: AgentSession, onFrame: (JSONObject) -> Unit) {
        val estimatedTokens = estimateTokenCount(session.transcript)
        val threshold = (session.config.contextWindow * session.config.compactThreshold).toInt()
        if (estimatedTokens > threshold) {
            emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                put("sessionUpdate", "compact_status")
                put("status", "started")
            })
            compactTranscript(session)
            emitFrame(session, onFrame, "task-running", "acp_event", JSONObject().apply {
                put("sessionUpdate", "compact_status")
                put("status", "completed")
            })
        }
    }

    private fun estimateTokenCount(transcript: TranscriptBuilder): Int {
        var total = 0
        for (msg in transcript.messages) {
            total += msg.optString("content", "").length / 4
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (i in 0 until toolCalls.length()) {
                    total += toolCalls.getJSONObject(i).toString().length / 4
                }
            }
        }
        return total
    }

    private fun compactTranscript(session: AgentSession) {
        val transcript = session.transcript
        if (transcript.messages.size <= 6) return

        val systemMessages = mutableListOf<JSONObject>()
        val recentMessages = mutableListOf<JSONObject>()
        val middleMessages = mutableListOf<JSONObject>()

        for (i in 0 until transcript.messages.size) {
            val msg = transcript.messages[i]
            if (msg.optString("role") == "system") {
                systemMessages.add(msg)
            } else if (i >= transcript.messages.size - 6) {
                recentMessages.add(msg)
            } else {
                middleMessages.add(msg)
            }
        }

        if (middleMessages.isEmpty()) return

        try {
            val summaryPrompt = buildCompactSummaryPrompt(middleMessages)
            val compactConfig = AgentConfig(
                model = session.config.model,
                baseUrl = session.config.baseUrl,
                apiKey = session.config.apiKey,
                contextWindow = 16000,
                maxOutput = 2048,
                streamEnabled = false
            )
            val summaryMessages = JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", summaryPrompt) })
            }
            val resp = callLLM(compactConfig, summaryMessages, JSONArray())
            val summary = resp?.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""

            if (summary.isNotEmpty()) {
                transcript.messages.clear()
                systemMessages.forEach { transcript.messages.add(it) }
                transcript.messages.add(JSONObject().apply {
                    put("role", "system")
                    put("content", "以下是之前对话的摘要:\n$summary")
                })
                recentMessages.forEach { transcript.messages.add(it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildCompactSummaryPrompt(messages: List<JSONObject>): String {
        val sb = StringBuilder()
        sb.appendLine("请用中文简要总结以下对话历史，保留关键信息和重要决策:")
        for (msg in messages) {
            val role = msg.optString("role", "")
            val content = msg.optString("content", "")
            if (content.isNotEmpty() && content.length > 500) {
                sb.appendLine("[$role]: ${content.take(500)}...")
            } else if (content.isNotEmpty()) {
                sb.appendLine("[$role]: $content")
            }
        }
        return sb.toString()
    }

    private fun handleTool(name: String, params: JSONObject): String {
        if (executor != null) return executor!!.invoke(name, params)

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
            "query_sms" -> "短信查询需要用户授权"
            "query_contacts" -> "通讯录查询需要用户授权"
            "query_calendar" -> "日历查询需要用户授权"
            "set_alarm" -> "闹钟设置需要用户授权"
            "toggle_wifi" -> "WiFi 控制需要用户授权"
            "toggle_bluetooth" -> "蓝牙控制需要用户授权"
            "spawn_agent" -> handleSpawnAgent(params)
            else -> {
                if (name.startsWith("mcp_")) {
                    handleMcpTool(name, params)
                } else {
                    "未知工具: $name"
                }
            }
        }
    }

    private fun handleSpawnAgent(params: JSONObject): String {
        val subagentMgr = subagentManager
        if (subagentMgr == null) return "子代理管理器未初始化"
        if (!subagentMgr.canSpawn()) return "子代理并行数已达上限"

        val config = SubagentManager.SpawnConfig(
            type = SubagentManager.SubagentType.fromString(params.optString("type", "general-purpose")),
            name = params.optString("name", "subagent"),
            task = params.optString("task", ""),
            description = params.optString("description", ""),
            maxTurns = params.optInt("max_turns", 16),
            writePaths = params.optJSONArray("write_paths")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            model = params.optString("model", null),
            baseUrl = params.optString("base_url", null),
            apiKey = params.optString("api_key", null)
        )

        val parentId = ""
        val result = subagentMgr.spawn(config, parentId, { _ -> }, { _ -> })
        return if (result != null) "子代理已启动: $result" else "子代理启动失败"
    }

    private fun handleMcpTool(fullName: String, params: JSONObject): String {
        val mcp = mcpClient ?: return "MCP 客户端未初始化"
        val parts = fullName.removePrefix("mcp_").split("_", limit = 2)
        if (parts.size < 2) return "MCP 工具名格式错误: $fullName"
        return mcp.callTool(parts[0], parts[1], params)
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

    fun emitFrame(session: AgentSession, onFrame: (JSONObject) -> Unit, type: String, kind: String?, data: JSONObject?) {
        val seq = frameSeq.incrementAndGet()
        val frame = JSONObject().apply {
            put("type", type)
            if (kind != null) put("kind", kind)
            if (data != null) put("data", data)
            put("timestamp", System.currentTimeMillis())
            put("seq", seq)
        }
        onFrame(frame)
        sessionManager?.appendFrame(session.id, frame)
    }

    private fun b64Text(s: String): String {
        return Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun strParam(desc: String, required: Boolean): JSONObject {
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