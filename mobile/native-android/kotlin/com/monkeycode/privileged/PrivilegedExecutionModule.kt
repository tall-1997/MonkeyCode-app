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

    private val sessionDataCallbacks = ConcurrentHashMap<String, (String) -> Unit>()
    private val sessionExitCallbacks = ConcurrentHashMap<String, (Int) -> Unit>()

    override fun getName(): String = "PrivilegedExecution"

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
        Thread {
            try {
                alpineEnvironment.install { progress ->
                    sendEvent("alpineInstallProgress", Arguments.createMap().apply {
                        putDouble("progress", progress.toDouble())
                    })
                }
                val result = Arguments.createMap()
                result.putBoolean("success", true)
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("ALPINE_INSTALL_ERROR", e.message)
            }
        }.start()
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

    // ==================== Agent Runtime（自研引擎，替代上游 ohmyagent） ====================

    private var agentSessionId: String? = null
    private val agentRuntime: AgentRuntime by lazy {
        AgentRuntime(reactContext).apply {
            // 注入统一执行层：Root 提权优先，PRoot 沙箱兜底
            this.shell = rootShellManager
            this.fs = fileSystemOps
            this.gui = guiAgent
            this.alpine = alpineEnvironment
            this.sandboxMode = !isRootAvailable()
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
                systemPrompt = cfg.optString("systemPrompt", ""),
                initialInput = cfg.optString("initialInput", ""),
                skills = emptyList(),
                tools = cfg.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                maxTurns = cfg.optInt("maxTurns", 64),
                maxToolCalls = cfg.optInt("maxToolCalls", 256),
                workDir = cfg.optString("workDir", "")
            )

            if (model.optString("baseUrl").isBlank() || model.optString("apiKey").isBlank()) {
                promise.reject("AGENT_CONFIG_ERROR", "缺少模型 baseUrl 或 apiKey")
                return
            }

            val sid = agentRuntime.startSession(
                agentConfig,
                onFrame = { frame ->
                    sendEvent("engineFrame", Arguments.createMap().apply {
                        putString("type", frame.optString("type", "task-running"))
                        putString("kind", frame.optString("kind"))
                        putString("data", frame.optJSONObject("data")?.toString() ?: "{}")
                        putDouble("timestamp", System.currentTimeMillis().toDouble())
                        putInt("seq", frame.optInt("seq", 0))
                    })
                },
                onError = { msg ->
                    sendEvent("engineStatus", Arguments.createMap().apply {
                        putString("status", "crashed")
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

    // ==================== Event Helpers ====================

    fun sendEvent(eventName: String, params: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
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