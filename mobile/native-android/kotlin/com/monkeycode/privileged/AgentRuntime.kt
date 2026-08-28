package com.monkeycode.privileged

import android.content.Context
import com.monkeycode.privileged.engine.FrameEmitter
import com.monkeycode.privileged.engine.LlmClient
import com.monkeycode.privileged.engine.SubagentCatalog
import com.monkeycode.privileged.engine.SubagentRunner
import com.monkeycode.privileged.engine.ToolDeps
import com.monkeycode.privileged.engine.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 Agent 引擎 —— 自研 Kotlin 原生实现，不依赖上游 ohmyagent（私有仓库）。
 *
 * 结构对齐参考仓：
 *  - OmniBot AgentOrchestrator：主循环骨架（steering → LLM 流式 → 工具批执行）
 *  - desktop/src/driver/frame.rs：帧词汇唯一来源（engine/FrameEmitter 投影）
 *  - shiyi subagent.dart / app_state.dart：子代理白名单与工具裁剪
 *
 * 执行层注入由 PrivilegedExecutionModule 完成：Root 提权优先，
 * 无 Root 走 PRoot Alpine 沙箱；run_terminal 经 HiddenShellManager
 * 常驻隐藏 shell 复用（OperitTerminalCore marker 信封协议）。
 */
class AgentRuntime(private val context: Context) {

    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val sessionCounter = AtomicInteger(0)

    /** 当前线程正在执行的 (sessionId, toolCallId)，供 question/spawn 工具回传帧使用 */
    private val execContext = ThreadLocal<Pair<String, String>?>()

    // 统一执行层（Root 优先 / PRoot 沙箱兜底），由 PrivilegedExecutionModule 注入
    var shell: RootShellManager? = null
    var fs: FileSystemOps? = null
    var gui: GUIAgent? = null
    var alpine: AlpineEnvironment? = null
    var sandboxMode: Boolean = false

    enum class SessionState { RUNNING, COMMITTING, TERMINAL }

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

        /** 组装请求消息数组（当前规模直接全量透出）。 */
        fun build(): JSONArray {
            val out = JSONArray()
            for (m in messages) out.put(m)
            return out
        }
    }

    inner class AgentSession(
        val id: String,
        val config: AgentConfig,
        val transcript: TranscriptBuilder,
        val registry: ToolRegistry,
        val emitter: FrameEmitter,
        val onFrame: (JSONObject) -> Unit,
    ) {
        var state: SessionState = SessionState.RUNNING
        var turnCount: Int = 0
        var toolCallCount: Int = 0
        var isCancelled: Boolean = false
        var isPaused: Boolean = false
        val steering: MutableList<String> = mutableListOf()

        private val pendingQuestions = LinkedHashMap<String, CompletableFuture<String>>()
        private val qCounter = AtomicInteger(0)

        fun emit(frame: JSONObject) {
            onFrame(frame)
            if (!frame.isNull("seq")) frame.optLong("seq", -1)
        }

        fun usage(totalChars: Int) {
            val used = (totalChars / 4).coerceAtLeast(1).toLong()
            emitter.usageUpdate(used, config.contextWindow.toLong()).let(::emit)
        }

        fun nextQuestionId(): String = "q_${qCounter.incrementAndGet()}_$id"

        fun registerQuestion(qid: String, future: CompletableFuture<String>) {
            synchronized(pendingQuestions) { pendingQuestions[qid] = future }
        }

        fun unregisterQuestion(qid: String) {
            synchronized(pendingQuestions) { pendingQuestions.remove(qid) }
        }

        /** JS 答复唤醒等待中的提问卡；返回是否存在等待者。 */
        fun answer(answersJson: String): Boolean {
            synchronized(pendingQuestions) {
                val entry = pendingQuestions.entries.firstOrNull { !it.value.isDone } ?: return false
                entry.value.complete(answersJson)
                return true
            }
        }
    }

    // ==================== 会话生命周期 ====================

    fun startSession(config: AgentConfig, onFrame: (JSONObject) -> Unit, onError: (String) -> Unit): String {
        val sessionId = "agent_${sessionCounter.incrementAndGet()}"
        val emitter = FrameEmitter()
        val sessionHolder = arrayOfNulls<AgentSession>(1)
        val deps = buildDeps(sessionId)
        val registry = ToolRegistry(deps).apply {
            registerDefaults()
            restrictTo(config.tools.toSet())
        }
        // spawn_agent 需要会话级 emitter/tcId 联动，按 session 定制注册
        sessionHolder[0] = AgentSession(sessionId, config, TranscriptBuilder(), registry, emitter, onFrame)
        registerSpawnAgent(registry, config, sessionId)

        val session = sessionHolder[0]!!
        sessions[sessionId] = session

        Thread {
            try {
                executeAgentLoop(session)
            } catch (e: Exception) {
                session.emitter.taskErrorPending(e.message ?: "Agent loop error").let(session::emit)
                onError(e.message ?: "Agent loop error")
            } finally {
                session.emitter.taskEnded().let(session::emit)
                session.state = SessionState.TERMINAL
                sessions.remove(sessionId)
            }
        }.start()

        return sessionId
    }

    fun cancelSession(sessionId: String) {
        sessions[sessionId]?.isCancelled = true
    }

    fun pauseSession(sessionId: String) {
        sessions[sessionId]?.isPaused = true
    }

    /** 运行中注入用户输入（桌面版 steer 语义）。 */
    fun sendSteering(sessionId: String, message: String) {
        val s = sessions[sessionId] ?: return
        synchronized(s.steering) { s.steering.add(message) }
        s.emitter.steerConfirmed(message.hashCode().toString()).let(s::emit)
    }

    /** JS 侧答复提问卡（bridge: resolveLocalQuestion）。 */
    fun answerQuestion(sessionId: String, answersJson: String): Boolean =
        sessions[sessionId]?.answer(answersJson) ?: false

    // ==================== 主循环 ====================

    private fun executeAgentLoop(session: AgentSession) {
        val config = session.config
        val transcript = session.transcript

        session.emitter.taskStarted().let(session::emit)

        if (config.systemPrompt.isNotEmpty()) transcript.addSystem(config.systemPrompt)
        if (config.workDir.isNotEmpty()) {
            transcript.addSystem("当前工作目录: ${config.workDir}\n所有文件操作默认在此目录内进行。")
        }
        if (config.initialInput.isNotEmpty()) {
            transcript.addUser(config.initialInput)
            session.emitter.userInput(config.initialInput).let(session::emit)
        }

        val client = LlmClient(config.interfaceType, config.baseUrl, config.apiKey, config.model, config.maxOutput)
        val toolsJson = session.registry.catalogJson()
        var totalChars = estimateContext(transcript)

        while (session.state == SessionState.RUNNING &&
            session.turnCount < config.maxTurns &&
            session.toolCallCount < config.maxToolCalls &&
            !session.isCancelled
        ) {
            while (session.isPaused && !session.isCancelled) Thread.sleep(100)
            if (session.isCancelled) break

            // steering：运行中追加的用户指令，每轮前并入历史
            val injected = mutableListOf<String>()
            synchronized(session.steering) {
                while (session.steering.isNotEmpty()) {
                    val next = session.steering.removeAt(0)
                    if (next.isNotBlank()) injected.add(next)
                }
            }
            for (msg in injected) {
                transcript.addUser(msg)
                session.emitter.userInput(msg).let(session::emit)
            }

            session.turnCount++

            val response = client.call(
                transcript.build(),
                toolsJson,
                LlmClient.StreamHandlers(
                    onText = { piece ->
                        totalChars += piece.length
                        session.emitter.agentText(piece).let(session::emit)
                    },
                    onThought = { piece ->
                        session.emitter.agentThought(piece).let(session::emit)
                    },
                ),
            )

            if (session.isCancelled) break
            if (response == null) {
                session.emitter.taskErrorPending("LLM API call failed").let(session::emit)
                onErrorSession(session, "LLM API call failed")
                break
            }

            val choice = response.optJSONArray("choices")?.optJSONObject(0)
            if (choice == null) {
                session.emitter.taskErrorPending("Empty response from LLM").let(session::emit)
                break
            }
            val message = choice.optJSONObject("message") ?: break
            val content = message.optString("content", "")
            val toolCalls = message.optJSONArray("tool_calls")
            val finishReason = choice.optString("finish_reason", "stop")

            transcript.addAssistant(content, toolCalls)
            totalChars += content.length
            session.usage(totalChars)

            if (finishReason != "tool_calls" || toolCalls == null || toolCalls.length() == 0) break

            for (i in 0 until toolCalls.length()) {
                if (session.isCancelled) return
                if (session.toolCallCount >= config.maxToolCalls) return
                session.toolCallCount++

                val toolCall = toolCalls.getJSONObject(i)
                val tcId = toolCall.optString("id", "").ifBlank { "tc_${session.toolCallCount}" }
                val function = toolCall.optJSONObject("function") ?: continue
                val name = function.optString("name", "")
                val args = function.optString("arguments", "{}")

                session.emitter.toolCall(tcId, session.registry.titleOf(name), parseArgs(args)).let(session::emit)

                execContext.set(session.id to tcId)
                val resultObj = try {
                    session.registry.execute(name, args)
                } finally {
                    execContext.set(null)
                }
                transcript.addToolResult(tcId, name, resultObj.output)
                session.emitter.toolCallCompleted(tcId, resultObj.output, resultObj.images).let(session::emit)
                totalChars += resultObj.output.length.coerceAtMost(8_000)
            }
        }
    }

    private fun onErrorSession(session: AgentSession, msg: String) {
        // task-error(terminal=false) 已发；引擎状态帧由 JS 层 engineStatus 承接
    }

    private fun parseArgs(args: String): JSONObject =
        runCatching { JSONObject(args) }.getOrElse { JSONObject() }

    private fun estimateContext(t: TranscriptBuilder): Int =
        t.messages.sumOf { m -> m.optString("content").length + 24 }

    // ==================== 子代理 ====================

    private fun registerSpawnAgent(registry: ToolRegistry, config: AgentConfig, sessionId: String) {
        registry.register(
            "spawn_agent", "Spawn Sub-Agent",
            "派生子代理执行独立任务（explore=只读侦查 / plan=产出计划 / worker=授权执行），并返回其报告",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("agent_type", JSONObject().put("type", "string")
                        .put("description", "子代理类型：explore|plan|worker"))
                    put("task", JSONObject().put("type", "string").put("description", "任务描述"))
                })
                put("required", JSONArray().put("agent_type").put("task"))
            },
        ) { p ->
            val type = p.optString("agent_type", "worker")
            val task = p.optString("task", "")
            val spec = SubagentCatalog.of(type)

            val report = try {
                runSubagentLoop(spec, task, { onProgress ->
                    // 进度挂到父工具卡（frame.rs ToolProgress.subagent_tool/subagent_text/child_session 词汇）
                    val ctx = execContext.get()
                    if (ctx != null) sessions[ctx.first]?.let { parent ->
                        parent.emitter.toolCallProgress(ctx.second, onProgress).let(parent::emit)
                    }
                }, config)
            } catch (e: Exception) {
                "error: ${e.message ?: e.javaClass.simpleName}"
            }
            ToolRegistry.ToolResult(report.clipReport())
        }
    }

    /**
     * 子代理小循环：独立 transcript + 白名单注册表 + 只读共享实现；
     * 白名单遵循 shiyi 三型语义（explore 只读、plan 规划、worker 全权）。
     */
    private fun runSubagentLoop(
        spec: SubagentCatalog.Spec,
        task: String,
        onProgress: (JSONObject) -> Unit,
        config: AgentConfig,
    ): String {
        val client = LlmClient(config.interfaceType, config.baseUrl, config.apiKey, config.model, config.maxOutput)
        val transcript = TranscriptBuilder()
        transcript.addSystem(listOf(
            config.systemPrompt,
            spec.promptAppendix,
            if (config.workDir.isNotBlank()) "当前工作目录: ${config.workDir}" else "",
        ).filter { it.isNotBlank() }.joinToString("\n\n"))
        transcript.addUser(task)
        onProgress(JSONObject().put("kind", "child_session").put("child_session", spec.type).put("text", "${spec.title} 启动"))

        val subRegistry = readOnlyRegistry().also { it.restrictTo(spec.allowedTools) }
        val toolsJson = subRegistry.catalogJson()
        var turns = 0
        while (turns < spec.maxTurns) {
            turns++
            val response = client.call(transcript.build(), toolsJson, null)
                ?: throw IllegalStateException("subagent LLM call failed")
            val choice = response.optJSONArray("choices")?.optJSONObject(0) ?: break
            val msg = choice.optJSONObject("message") ?: break
            val finish = choice.optString("finish_reason", "stop")
            transcript.addAssistant(msg.optString("content"), msg.optJSONArray("tool_calls"))

            val tcs = msg.optJSONArray("tool_calls")
            if (finish != "tool_calls" || tcs == null || tcs.length() == 0) {
                val text = msg.optString("content").ifBlank { "(无文本输出)" }
                onProgress(JSONObject().put("kind", "subagent_text").put("text", text.take(2000)))
                return text
            }

            for (i in 0 until tcs.length()) {
                val tc = tcs.getJSONObject(i)
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                onProgress(JSONObject().put("kind", "subagent_tool").put("text", name))
                val output = subRegistry.execute(name, fn.optString("arguments", "{}")).output
                transcript.addToolResult(tc.optString("id"), name, output)
            }
        }
        return "已达子代理最大轮数($turns/${spec.maxTurns})，停止。"
    }

    /** 只读白名单注册表（terminal/file_read/list_directory/web_fetch），供全部子代理复用。 */
    private val sharedReadOnly by lazy {
        ToolRegistry(ToolDeps(
            terminal = { command, _ -> execTerminal(command) },
            readFile = ::readFileText,
            listDir = ::listDirectoryImpl,
            webFetch = ::fetchUrlImpl,
        )).apply { registerDefaults() }
    }
    private fun readOnlyRegistry(): ToolRegistry = sharedReadOnly

    private fun String.clipReport(): String =
        if (length <= MAX_REPORT_CHARS) this else take(MAX_REPORT_CHARS) + "\n...[report truncated]"

    companion object {
        const val MAX_REPORT_CHARS = 16_000
    }

    // ==================== 默认依赖组装 ====================

    private fun buildDeps(sessionId: String): ToolDeps {
        val alpineEnv = alpine
        return ToolDeps(
            terminal = { command, timeoutMs -> execTerminal(command) },
            readFile = ::readFileText,
            writeFile = ::writeFileText,
            listDir = ::listDirectoryImpl,
            installPackage = { pkg ->
                when {
                    alpineEnv != null -> formatResult(alpineEnv.execCommand("apk add $pkg"))
                    else -> "error: Linux 环境未初始化"
                }
            },
            webFetch = ::fetchUrlImpl,
            askUser = { questionsJson ->
                askUserBlocking(questionsJson)
            },
            spawnAgent = { _, _ -> "" }, // spawn_agent 在 registerSpawnAgent 单独覆盖注册
            screenshot = {
                val g = gui
                if (g == null) "error: 无障碍/Root 能力不可用"
                else runCatching { g.takeScreenshot() }
                    .map { "ok: screenshot captured (${it.length} bytes)" }
                    .getOrElse { "error: ${it.message ?: "截屏失败"}" }
            },
            guiClick = { x, y ->
                runCatching { gui?.performClick(x.toFloat(), y.toFloat()) }
                    .map { "ok" }
                    .getOrElse { "error: ${it.message ?: "点击失败"}" }
            },
            guiType = { text ->
                runCatching { gui?.performInput(text) }
                    .map { "ok" }
                    .getOrElse { "error: ${it.message ?: "输入失败"}" }
            },
            accessibilityTree = {
                val g = gui
                if (g == null) "error: 无障碍服务未连接"
                else runCatching { g.getAccessibilityTree() }
                    .getOrElse { "error: ${it.message ?: "获取失败"}" }
            },
        )
    }

    // ==================== 执行通道 ====================

    private fun execTerminal(command: String): String = when {
        !sandboxMode && shell != null -> {
            val r = shell!!.execSync(command)
            buildString {
                append(r.stdout)
                if (r.stderr.isNotBlank()) append("\n[stderr] ").append(r.stderr.trim())
                append("\nexit=").append(r.exitCode)
            }
        }
        alpine != null -> formatResult(alpine!!.execCommand(command))
        else -> "error: 未配置任何可用执行通道"
    }

    private fun formatResult(r: LinuxCommandResult): String = buildString {
        append(r.stdout)
        if (r.stderr.isNotBlank()) append("\n[stderr] ").append(r.stderr.trim())
        if (r.exitCode != 0) append("\nexit=").append(r.exitCode)
    }.ifBlank { "(no output)" }

    private fun readFileText(path: String): String = when {
        alpine != null && sandboxMode -> formatResult(alpine!!.execCommand("cat '${path.sq()}'"))
        fs != null -> try {
            fs!!.readFile(path)
        } catch (e: Exception) { "error: ${e.message}" }
        else -> "error: 文件系统未配置"
    }

    private fun writeFileText(path: String, content: String): String = when {
        fs != null -> try {
            fs!!.writeFile(path, content); "ok"
        } catch (e: Exception) { "error: ${e.message}" }
        else -> "error: 文件系统未配置"
    }

    private fun listDirectoryImpl(pathIn: String): String {
        if (fs != null) {
            return try {
                val entries = fs!!.listDirectory(pathIn)
                entries.joinToString("\n") { e -> "${e.name}${if (e.isDirectory) "/" else ""}\t${e.size}" }
            } catch (e: Exception) { "error: ${e.message}" }
        }
        if (alpine != null) {
            return formatResult(alpine!!.execCommand("ls -la '${pathIn.sq()}'"))
        }
        return "error: 文件系统未配置"
    }

    private fun fetchUrlImpl(url: String, maxBytes: Long): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "error: 仅支持 http(s)"
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MonkeyCode-Mobile-Agent/1.0")
            if (conn.responseCode / 100 != 2) {
                return "error: HTTP ${conn.responseCode}"
            }
            val bytes = conn.inputStream.use { ins -> ins.readNBytes(maxBytes.coerceIn(1024L, 512_000L).toInt()) }
            conn.disconnect()
            String(bytes, Charsets.UTF_8) + if (bytes.size >= maxBytes) "\n...[truncated at $maxBytes bytes]" else ""
        } catch (e: IOException) {
            "error: ${e.message}"
        }
    }

    /** 提问卡阻塞等待 JS 答复（bridge resolveLocalQuestion 唤醒）；超时返回空。 */
    private fun askUserBlocking(questionsJson: String): String {
        val ctx = execContext.get()
        val session = sessions[ctx?.first]
        if (session == null) {
            // 无主会话上下文（例如子代理内）：降级为文本占位回答
            return "[user answered] (无父会话上下文)"
        }
        val qid = session.nextQuestionId()
        val questions = runCatching { JSONArray(questionsJson) }
            .getOrElse { JSONArray().put(JSONObject().put("question", questionsJson)) }

        val future = CompletableFuture<String>()
        session.registerQuestion(qid, future)
        session.emitter.askUserQuestion(qid, questions).let(session::emit)
        val answer = try {
            future.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) { "" }
        session.unregisterQuestion(qid)
        session.emitter.replyQuestion(qid, answer, cancelled = answer.isBlank()).let(session::emit)
        return "[user answered] $answer"
    }
}

private fun String.sq(): String = replace("'", "'\\''")

