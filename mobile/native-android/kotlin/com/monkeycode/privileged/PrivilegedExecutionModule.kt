package com.monkeycode.privileged

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PrivilegedExecutionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val rootShellManager = RootShellManager()
    private val fileSystemOps = FileSystemOps()
    private val deviceTools = DeviceTools(reactContext)
    private val personalDataProvider = PersonalDataProvider(reactContext)
    private val guiAgent = GUIAgent(reactContext)
    private val alpineEnvironment = AlpineEnvironment(reactContext)
    private val ubuntuEnvironment = UbuntuEnvironment(reactContext)

    private val preferences = reactContext.getSharedPreferences("privileged_execution", 0)
    private var sandboxType: String = preferences.getString("sandbox_type", AlpineEnvironment.SANDBOX_TYPE)
        ?: AlpineEnvironment.SANDBOX_TYPE

    private val browserServiceDelegate = lazy { BrowserService(reactContext.applicationContext) }
    private val browserService: BrowserService by browserServiceDelegate
    private val browserMcpServerDelegate = lazy { BrowserMcpServer(browserService) }
    private val browserMcpServer: BrowserMcpServer by browserMcpServerDelegate

    private val sessionDataCallbacks = ConcurrentHashMap<String, (String) -> Unit>()
    private val sessionExitCallbacks = ConcurrentHashMap<String, (Int) -> Unit>()

    override fun getName(): String = "PrivilegedExecution"

    override fun invalidate() {
        if (browserMcpServerDelegate.isInitialized()) browserMcpServer.stop()
        if (browserServiceDelegate.isInitialized()) browserService.destroy()
        sessionDataCallbacks.clear()
        sessionExitCallbacks.clear()
        super.invalidate()
    }

    init {
        applyActiveBackend(sandboxType)
        // 接线 RootShellManager 会话输出/退出回调 → 全局事件（配合 JS 端 DeviceEventEmitter）
        rootShellManager.onSessionData = { sessionId: String, data: String ->
            sessionDataCallbacks[sessionId]?.invoke(data)
            safeSendEvent("shellData", Arguments.createMap().apply {
                putString("sessionId", sessionId)
                putString("data", data)
            })
        }
        rootShellManager.onSessionExit = { sessionId: String, exitCode: Int ->
            sessionExitCallbacks[sessionId]?.invoke(exitCode)
            safeSendEvent("shellExit", Arguments.createMap().apply {
                putString("sessionId", sessionId)
                putInt("exitCode", exitCode)
            })
        }
    }

    private fun applyActiveBackend(type: String) {
        sandboxType = type
        alpineEnvironment.isActive = type == AlpineEnvironment.SANDBOX_TYPE
        ubuntuEnvironment.isActive = type == UbuntuEnvironment.SANDBOX_TYPE
    }

    private fun execActiveBackend(command: String): LinuxCommandResult = when (sandboxType) {
        UbuntuEnvironment.SANDBOX_TYPE -> ubuntuEnvironment.execCommand(command)
        else -> alpineEnvironment.execCommand(command)
    }

    // ==================== Permission Detection ====================

    @ReactMethod
    fun detectRoot(promise: Promise) {
        try {
            val result = Arguments.createMap()
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                result.putBoolean("available", true)
                val output = process.inputStream.bufferedReader().readText()
                result.putString("uid", output.trim())

                // 检测 Root 管理器类型
                val manager = detectRootManager()
                result.putString("manager", manager)

                val version = detectRootManagerVersion(manager)
                result.putString("version", version)
            } else {
                result.putBoolean("available", false)
                result.putNull("manager")
                result.putNull("version")
            }
            promise.resolve(result)
        } catch (e: Exception) {
            val result = Arguments.createMap()
            result.putBoolean("available", false)
            result.putNull("manager")
            result.putNull("version")
            promise.resolve(result)
        }
    }

    @ReactMethod
    fun detectLSPosed(promise: Promise) {
        try {
            val result = Arguments.createMap()
            val lspdPath = File("/data/adb/lspd")
            val modulesPath = File("/data/adb/modules")

            if (lspdPath.exists() || modulesPath.exists()) {
                result.putBoolean("available", true)
                // 尝试读取 LSPosed 版本
                val versionFile = File("/data/adb/lspd/version")
                if (versionFile.exists()) {
                    result.putString("version", versionFile.readText().trim())
                } else {
                    result.putString("version", "unknown")
                }
                result.putInt("apiVersion", 102) // libxposed API 102
            } else {
                result.putBoolean("available", false)
                result.putNull("version")
                result.putInt("apiVersion", 0)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            val result = Arguments.createMap()
            result.putBoolean("available", false)
            result.putNull("version")
            result.putInt("apiVersion", 0)
            promise.resolve(result)
        }
    }

    // ==================== Root Shell ====================

    @ReactMethod
    fun execCommand(command: String, identity: String, promise: Promise) {
        try {
            val result = rootShellManager.execSync(command, identity)
            val map = Arguments.createMap()
            map.putString("stdout", result.stdout)
            map.putString("stderr", result.stderr)
            map.putInt("exitCode", result.exitCode)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("SHELL_ERROR", e.message)
        }
    }

    @ReactMethod
    fun createShellSession(workDir: String, identity: String, promise: Promise) {
        try {
            val sessionId = rootShellManager.createSession(workDir, identity)
            promise.resolve(sessionId)
        } catch (e: Exception) {
            promise.reject("SESSION_ERROR", e.message)
        }
    }

    @ReactMethod
    fun writeToSession(sessionId: String, data: String, promise: Promise) {
        try {
            rootShellManager.writeToSession(sessionId, data)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("WRITE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun destroySession(sessionId: String, promise: Promise) {
        try {
            rootShellManager.destroySession(sessionId)
            sessionDataCallbacks.remove(sessionId)
            sessionExitCallbacks.remove(sessionId)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("DESTROY_ERROR", e.message)
        }
    }

    // ==================== File System ====================

    @ReactMethod
    fun listDirectory(path: String, promise: Promise) {
        try {
            val entries = fileSystemOps.listDirectory(path)
            val array = Arguments.createArray()
            entries.forEach { entry ->
                val map = Arguments.createMap()
                map.putString("name", entry.name)
                map.putString("path", entry.path)
                map.putBoolean("isDirectory", entry.isDirectory)
                map.putDouble("size", entry.size.toDouble())
                map.putDouble("modificationTime", entry.modificationTime.toDouble())
                array.pushMap(map)
            }
            promise.resolve(array)
        } catch (e: Exception) {
            promise.reject("FS_ERROR", e.message)
        }
    }

    @ReactMethod
    fun readFile(path: String, encoding: String, promise: Promise) {
        try {
            val content = fileSystemOps.readFile(path, encoding)
            promise.resolve(content)
        } catch (e: Exception) {
            promise.reject("READ_ERROR", e.message)
        }
    }

    @ReactMethod
    fun writeFile(path: String, content: String, promise: Promise) {
        try {
            fileSystemOps.writeFile(path, content)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("WRITE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun createDirectory(path: String, promise: Promise) {
        try {
            fileSystemOps.createDirectory(path)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("MKDIR_ERROR", e.message)
        }
    }

    @ReactMethod
    fun deleteEntry(path: String, promise: Promise) {
        try {
            fileSystemOps.deleteEntry(path)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("DELETE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getFileInfo(path: String, promise: Promise) {
        try {
            val info = fileSystemOps.getInfo(path)
            val map = Arguments.createMap()
            map.putBoolean("exists", info.exists)
            map.putBoolean("isDirectory", info.isDirectory)
            map.putDouble("size", info.size.toDouble())
            map.putDouble("modificationTime", info.modificationTime.toDouble())
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("INFO_ERROR", e.message)
        }
    }

    // ==================== Device Tools ====================

    @ReactMethod
    fun setAlarm(hour: Int, minute: Int, label: String, promise: Promise) {
        try {
            deviceTools.setAlarm(hour, minute, label)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ALARM_ERROR", e.message)
        }
    }

    @ReactMethod
    fun mediaControl(action: String, promise: Promise) {
        try {
            deviceTools.mediaControl(action)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("MEDIA_ERROR", e.message)
        }
    }

    @ReactMethod
    fun setVolume(stream: String, level: Int, promise: Promise) {
        try {
            deviceTools.setVolume(stream, level)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("VOLUME_ERROR", e.message)
        }
    }

    @ReactMethod
    fun toggleWifi(enable: Boolean, promise: Promise) {
        try {
            deviceTools.toggleWifi(enable)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("WIFI_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getDeviceStatus(promise: Promise) {
        try {
            val status = deviceTools.getDeviceStatus()
            promise.resolve(status)
        } catch (e: Exception) {
            promise.reject("STATUS_ERROR", e.message)
        }
    }

    // ==================== Personal Data ====================

    @ReactMethod
    fun queryGallery(limit: Int, promise: Promise) {
        try {
            val results = personalDataProvider.queryGallery(limit)
            promise.resolve(results)
        } catch (e: Exception) {
            promise.reject("GALLERY_ERROR", e.message)
        }
    }

    @ReactMethod
    fun queryCalendar(startTime: Double, endTime: Double, promise: Promise) {
        try {
            val results = personalDataProvider.queryCalendar(startTime.toLong(), endTime.toLong())
            promise.resolve(results)
        } catch (e: Exception) {
            promise.reject("CALENDAR_ERROR", e.message)
        }
    }

    @ReactMethod
    fun querySMS(limit: Int, promise: Promise) {
        try {
            val results = personalDataProvider.querySMS(limit)
            promise.resolve(results)
        } catch (e: Exception) {
            promise.reject("SMS_ERROR", e.message)
        }
    }

    @ReactMethod
    fun queryNotifications(limit: Int, promise: Promise) {
        try {
            val results = personalDataProvider.queryNotifications(limit)
            promise.resolve(results)
        } catch (e: Exception) {
            promise.reject("NOTIF_ERROR", e.message)
        }
    }

    // ==================== GUI Agent ====================

    @ReactMethod
    fun takeScreenshot(promise: Promise) {
        try {
            val base64 = guiAgent.takeScreenshot()
            promise.resolve(base64)
        } catch (e: Exception) {
            promise.reject("SCREENSHOT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getAccessibilityTree(promise: Promise) {
        try {
            val tree = guiAgent.getAccessibilityTree()
            promise.resolve(tree)
        } catch (e: Exception) {
            promise.reject("A11Y_ERROR", e.message)
        }
    }

    @ReactMethod
    fun performClick(x: Double, y: Double, promise: Promise) {
        try {
            guiAgent.performClick(x.toFloat(), y.toFloat())
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CLICK_ERROR", e.message)
        }
    }

    @ReactMethod
    fun performSwipe(x1: Double, y1: Double, x2: Double, y2: Double, promise: Promise) {
        try {
            guiAgent.performSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SWIPE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun performInput(text: String, promise: Promise) {
        try {
            guiAgent.performInput(text)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("INPUT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopGUIOperation(promise: Promise) {
        try {
            guiAgent.stopOperation()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STOP_ERROR", e.message)
        }
    }

    // ==================== Alpine Linux ====================

    @ReactMethod
    fun installAlpineEnvironment(promise: Promise) {
        // 后台线程安装：任何 Throwable（含 Error）都要转成 promise.reject，避免未捕获崩溃闪退
        val t = Thread {
            try {
                alpineEnvironment.install { progress ->
                    safeSendEvent("alpineInstallProgress", Arguments.createMap().apply {
                        putDouble("progress", progress.toDouble())
                    })
                }
                val result = Arguments.createMap()
                result.putBoolean("success", true)
                promise.resolve(result)
            } catch (e: Throwable) {
                try { promise.reject("ALPINE_INSTALL_ERROR", safeMessage(e)) }
                catch (e2: Throwable) { android.util.Log.e("MonkeyCode", "install reject failed", e2) }
            }
        }
        t.setUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MonkeyCode", "Alpine install crashed", throwable)
        }
        t.start()
    }

    /** 把任意 Throwable 转成可安全写入 promise 的错误消息。 */
    private fun safeMessage(e: Throwable): String {
        return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    @ReactMethod
    fun isAlpineInstalled(promise: Promise) {
        try {
            promise.resolve(alpineEnvironment.isInstalled())
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun execAlpineCommand(command: String, promise: Promise) {
        try {
            val result = alpineEnvironment.execCommand(command)
            val map = Arguments.createMap()
            map.putString("stdout", result.stdout)
            map.putString("stderr", result.stderr)
            map.putInt("exitCode", result.exitCode)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("ALPINE_EXEC_ERROR", e.message)
        }
    }

    // ==================== Ubuntu Linux ====================

    @ReactMethod
    fun installUbuntu(promise: Promise) {
        val t = Thread {
            try {
                ubuntuEnvironment.install { progress ->
                    safeSendEvent("ubuntuInstallProgress", Arguments.createMap().apply {
                        putDouble("progress", progress.toDouble())
                    })
                }
                val result = Arguments.createMap()
                result.putBoolean("success", true)
                promise.resolve(result)
            } catch (e: Throwable) {
                try { promise.reject("UBUNTU_INSTALL_ERROR", safeMessage(e)) }
                catch (e2: Throwable) { android.util.Log.e("MonkeyCode", "ubuntu install reject failed", e2) }
            }
        }
        t.setUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MonkeyCode", "Ubuntu install crashed", throwable)
        }
        t.start()
    }

    @ReactMethod
    fun getUbuntuStatus(promise: Promise) {
        try {
            val map = Arguments.createMap()
            map.putBoolean("installed", ubuntuEnvironment.isInstalled())
            map.putBoolean("installing", ubuntuEnvironment.isInstalling)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun execUbuntuCommand(command: String, promise: Promise) {
        try {
            val result = ubuntuEnvironment.execCommand(command)
            val map = Arguments.createMap()
            map.putString("stdout", result.stdout)
            map.putString("stderr", result.stderr)
            map.putInt("exitCode", result.exitCode)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("UBUNTU_EXEC_ERROR", e.message)
        }
    }

    @ReactMethod
    fun execSandboxCommand(command: String, promise: Promise) {
        try {
            val result = execActiveBackend(command)
            promise.resolve(Arguments.createMap().apply {
                putString("stdout", result.stdout)
                putString("stderr", result.stderr)
                putInt("exitCode", result.exitCode)
            })
        } catch (e: Exception) {
            promise.reject("SANDBOX_EXEC_ERROR", e.message)
        }
    }

    @ReactMethod
    fun setSandboxType(type: String, promise: Promise) {
        try {
            if (type != "alpine" && type != "ubuntu") {
                promise.reject("SANDBOX_ERROR", "无效的沙箱类型: $type，仅支持 alpine 或 ubuntu")
                return
            }
            applyActiveBackend(type)
            preferences.edit().putString("sandbox_type", type).apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SANDBOX_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getSandboxType(promise: Promise) {
        try {
            promise.resolve(sandboxType)
        } catch (e: Exception) {
            promise.resolve("alpine")
        }
    }

    // ==================== Agent Runtime（自研引擎，替代上游 ohmyagent） ====================

    private var agentSessionId: String? = null
    private val agentRuntime: AgentRuntime by lazy {
        AgentRuntime(reactContext).apply {
            // 注入统一执行层：Root 提权优先，PRoot 沙箱兜底
            this.shell = rootShellManager
            this.fs = fileSystemOps
            this.gui = guiAgent
            this.alpine = alpineEnvironment
            this.ubuntu = ubuntuEnvironment
            this.sandboxMode = !isRootAvailable()
            this.sessionManager = SessionManager(reactContext)
            this.skillManager = SkillManager(reactContext)
            this.mcpClient = McpClient(reactContext)
            this.subagentManager = SubagentManager(this)
        }
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val ok = p.waitFor() == 0
            ok
        } catch (e: Exception) { false }
    }

    @ReactMethod
    fun startAgent(configJson: String, promise: Promise) {
        try {
            val cfg = org.json.JSONObject(configJson)
            val model = cfg.optJSONObject("modelConfig") ?: JSONObject()
            val agentConfig = AgentRuntime.AgentConfig(
                model = model.optString("model", ""),
                baseUrl = model.optString("baseUrl", ""),
                apiKey = model.optString("apiKey", ""),
                contextWindow = model.optInt("contextWindow", 128000),
                maxOutput = model.optInt("maxOutput", 32768),
                thinking = model.optJSONObject("thinking")?.optBoolean("enabled", false) ?: false,
                interfaceType = model.optString("interfaceType")
                    .ifBlank { cfg.optString("interfaceType") }.ifBlank { "openai_chat" },
                systemPrompt = cfg.optString("systemPrompt", ""),
                initialInput = cfg.optString("initialInput", ""),
                skills = cfg.optJSONArray("skills")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                tools = cfg.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                maxTurns = cfg.optInt("maxTurns", 64),
                maxToolCalls = cfg.optInt("maxToolCalls", 256),
                workDir = cfg.optString("workDir", ""),
                streamEnabled = cfg.optBoolean("streamEnabled", true),
                compactThreshold = cfg.optDouble("compactThreshold", 0.8)
            )

            if (model.optString("baseUrl").isBlank() || model.optString("apiKey").isBlank()) {
                promise.reject("AGENT_CONFIG_ERROR", "缺少模型 baseUrl 或 apiKey")
                return
            }

            val sid = agentRuntime.startSession(
                agentConfig,
                onFrame = { frame ->
                    sendEvent("engineFrame", jsonObjectToMap(frame))
                },
                onError = { msg ->
                    sendEvent("engineStatus", Arguments.createMap().apply {
                        putString("status", "crashed")
                        putString("phase", "crashed")
                        putString("message", msg)
                    })
                }
            )
            agentSessionId = sid
            promise.resolve(sid)
        } catch (e: Exception) {
            promise.reject("AGENT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun approvePermission(permissionId: String, remember: Boolean, promise: Promise) {
        val sid = agentSessionId
        if (sid == null) promise.reject("AGENT_PERMISSION_ERROR", "Agent 未启动")
        else {
            agentRuntime.approvePermission(sid, permissionId, remember)
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun denyPermission(permissionId: String, promise: Promise) {
        val sid = agentSessionId
        if (sid == null) promise.reject("AGENT_PERMISSION_ERROR", "Agent 未启动")
        else {
            agentRuntime.denyPermission(sid, permissionId)
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun spawnAgent(configJson: String, parentSessionId: String, promise: Promise) {
        try {
            val json = JSONObject(configJson)
            val config = SubagentManager.SpawnConfig(
                type = SubagentManager.SubagentType.fromString(json.optString("type")),
                name = json.optString("name", "subagent"),
                description = json.optString("description", ""),
                systemPrompt = json.optString("systemPrompt", ""),
                task = json.getString("task"),
                maxTurns = json.optInt("maxTurns", 16),
                writePaths = json.optJSONArray("writePaths")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                model = json.optString("model").ifBlank { null },
                baseUrl = json.optString("baseUrl").ifBlank { null },
                apiKey = json.optString("apiKey").ifBlank { null },
                interfaceType = json.optString("interfaceType").ifBlank { null }
            )
            val id = agentRuntime.spawnAgent(config, parentSessionId, { frame ->
                sendEvent("engineFrame", jsonObjectToMap(frame))
            }, { message ->
                sendEvent("engineStatus", Arguments.createMap().apply {
                    putString("status", "crashed")
                    putString("phase", "subagent_error")
                    putString("message", message)
                })
            })
            promise.resolve(id)
        } catch (e: Exception) {
            promise.reject("SUBAGENT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun cancelSubagent(childId: String, promise: Promise) {
        agentRuntime.subagentManager?.cancelSubagent(childId)
        promise.resolve(true)
    }

    @ReactMethod
    fun listSessions(promise: Promise) {
        val result = Arguments.createArray()
        agentRuntime.sessionManager?.listSessions()?.forEach { meta ->
            result.pushMap(Arguments.createMap().apply {
                putString("id", meta.id); putString("title", meta.title); putString("summary", meta.summary)
                putString("status", meta.status.asString()); putString("engineId", meta.engineId)
                putDouble("createdAt", meta.createdAt.toDouble()); putDouble("updatedAt", meta.updatedAt.toDouble())
            })
        }
        promise.resolve(result)
    }

    @ReactMethod
    fun getSessionHistory(sessionId: String, cursor: String, limit: Int, promise: Promise) {
        val offset = cursor.toLongOrNull() ?: 0L
        val requested = limit.coerceAtLeast(1)
        val frames = agentRuntime.sessionManager?.sessionHistory(sessionId, offset, requested + 1) ?: emptyList()
        val page = frames.take(requested)
        promise.resolve(Arguments.createMap().apply {
            putArray("frames", jsonObjectsToArray(page))
            putString("cursor", (offset + page.size).toString())
            putBoolean("has_more", frames.size > requested)
        })
    }

    @ReactMethod
    fun openSession(sessionId: String, promise: Promise) = getSessionHistory(sessionId, "0", 50, promise)

    @ReactMethod
    fun getSessionFrame(sessionId: String, seq: Double, promise: Promise) {
        promise.resolve(agentRuntime.sessionManager?.sessionFrame(sessionId, seq.toLong())?.let(::jsonObjectToMap))
    }

    @ReactMethod
    fun deleteSession(sessionId: String, promise: Promise) {
        agentRuntime.sessionManager?.sessionDestroy(sessionId)
        promise.resolve(true)
    }

    @ReactMethod
    fun sendAgentInput(content: String, promise: Promise) {
        try {
            val sid = agentSessionId ?: throw IllegalStateException("Agent 未启动")
            // 通过 steering 队列注入下一条用户输入
            agentRuntime.sendSteering(sid, content)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("AGENT_INPUT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun cancelAgent(promise: Promise) {
        try {
            agentSessionId?.let { agentRuntime.cancelSession(it) }
            agentSessionId = null
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("AGENT_CANCEL_ERROR", e.message)
        }
    }

    @ReactMethod
    fun pauseAgent(promise: Promise) {
        try {
            agentSessionId?.let { agentRuntime.pauseSession(it) }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("AGENT_PAUSE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopAgent(promise: Promise) {
        try {
            agentSessionId?.let { agentRuntime.cancelSession(it) }
            agentSessionId = null
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("AGENT_ERROR", e.message)
        }
    }

    // ==================== Browser Automation ====================

    @ReactMethod
    fun browserNavigate(url: String, promise: Promise) {
        try {
            val result = browserService.browserNavigate(url)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("BROWSER_NAVIGATE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserScreenshot(elementRef: String, promise: Promise) {
        try {
            val ref = elementRef.takeIf { it.isNotEmpty() }
            val result = browserService.browserScreenshot(ref)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("BROWSER_SCREENSHOT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserSnapshot(promise: Promise) {
        try {
            val result = browserService.browserSnapshot()
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("BROWSER_SNAPSHOT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserClick(ref: String, promise: Promise) {
        try {
            browserService.browserClick(ref)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("BROWSER_CLICK_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserType(ref: String, text: String, promise: Promise) {
        try {
            browserService.browserType(ref, text)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("BROWSER_TYPE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserScroll(ref: String, promise: Promise) {
        try {
            val r = ref.takeIf { it.isNotEmpty() }
            browserService.browserScroll(r)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("BROWSER_SCROLL_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserEvaluate(expression: String, promise: Promise) {
        try {
            val result = browserService.browserEvaluate(expression)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("BROWSER_EVALUATE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserTabs(action: String, tabId: String, promise: Promise) {
        try {
            val tid = tabId.takeIf { it.isNotEmpty() }
            val result = browserService.browserTabs(action, tid)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("BROWSER_TABS_ERROR", e.message)
        }
    }

    @ReactMethod
    fun browserDialog(action: String, promise: Promise) {
        try {
            val result = browserService.browserDialog(action)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("BROWSER_DIALOG_ERROR", e.message)
        }
    }

    // ==================== MCP Server ====================

    @ReactMethod
    fun startMcpServer(port: Int, promise: Promise) {
        try {
            val result = browserMcpServer.start(port)
            val map = Arguments.createMap()
            map.putString("url", result["url"] ?: "")
            map.putString("token", result["token"] ?: "")
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("MCP_START_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopMcpServer(promise: Promise) {
        try {
            browserMcpServer.stop()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("MCP_STOP_ERROR", e.message)
        }
    }

    // ==================== Event Helpers ====================

    fun sendEvent(eventName: String, params: WritableMap) {
        // RN 要求 JS 事件模块在主线程发射；后台线程调用会崩溃致白屏。
        safeSendEvent(eventName, params)
    }

    fun safeSendEvent(eventName: String, params: WritableMap) {
        try {
            reactApplicationContext.runOnUiQueueThread {
                try {
                    reactApplicationContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                        .emit(eventName, params)
                } catch (e: Throwable) {
                    android.util.Log.e("MonkeyCode", "emit $eventName failed", e)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("MonkeyCode", "queue emit $eventName failed", e)
        }
    }

    private fun jsonObjectsToArray(values: List<JSONObject>): WritableArray = Arguments.createArray().apply {
        values.forEach { pushMap(jsonObjectToMap(it)) }
    }

    private fun jsonObjectToMap(json: JSONObject): WritableMap = Arguments.createMap().apply {
        json.keys().forEach { key ->
            when (val value = json.opt(key)) {
                null, JSONObject.NULL -> putNull(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Number -> putDouble(key, value.toDouble())
                is JSONObject -> putMap(key, jsonObjectToMap(value))
                is org.json.JSONArray -> putArray(key, jsonArrayToArray(value))
                else -> putString(key, value.toString())
            }
        }
    }

    private fun jsonArrayToArray(json: org.json.JSONArray): WritableArray = Arguments.createArray().apply {
        for (i in 0 until json.length()) {
            when (val value = json.opt(i)) {
                null, JSONObject.NULL -> pushNull()
                is String -> pushString(value)
                is Boolean -> pushBoolean(value)
                is Int -> pushInt(value)
                is Number -> pushDouble(value.toDouble())
                is JSONObject -> pushMap(jsonObjectToMap(value))
                is org.json.JSONArray -> pushArray(jsonArrayToArray(value))
                else -> pushString(value.toString())
            }
        }
    }

    // ==================== Private Helpers ====================

    private fun detectRootManager(): String? {
        return when {
            File("/data/adb/magisk").exists() -> "magisk"
            File("/data/adb/ksu").exists() -> "kernelsu"
            File("/data/adb/ap").exists() -> "apatch"
            else -> "unknown"
        }
    }

    private fun detectRootManagerVersion(manager: String?): String? {
        return try {
            when (manager) {
                "magisk" -> {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "magisk -v"))
                    process.waitFor()
                    process.inputStream.bufferedReader().readText().trim()
                }
                "kernelsu" -> {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ksud -v"))
                    process.waitFor()
                    process.inputStream.bufferedReader().readText().trim()
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
