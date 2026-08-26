package com.monkeycode.privileged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class AgentRuntime(private val context: Context) {
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val sessionCounter = AtomicInteger(0)

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
        val contextWindow: Int = 128000,
        val maxOutput: Int = 32768,
        val thinking: Boolean = false,
        val thinkingEffort: String? = null,
        val systemPrompt: String = "",
        val skills: List<String> = emptyList(),
        val tools: List<String> = emptyList(),
        val memoryEnabled: Boolean = false,
        val maxTurns: Int = 64,
        val maxToolCalls: Int = 256
    )

    class TranscriptBuilder {
        val messages = mutableListOf<JSONObject>()

        fun addSystem(content: String) {
            messages.add(JSONObject().apply {
                put("role", "system")
                put("content", content)
            })
        }

        fun addUser(content: String) {
            messages.add(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
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

        fun build(): JSONArray {
            return JSONArray(messages)
        }
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

    fun cancelSession(sessionId: String) {
        sessions[sessionId]?.isCancelled = true
    }

    fun pauseSession(sessionId: String) {
        sessions[sessionId]?.isPaused = true
    }

    fun sendSteering(sessionId: String, message: String) {
        sessions[sessionId]?.steering?.add(message)
    }

    private fun executeAgentLoop(session: AgentSession, onFrame: (JSONObject) -> Unit, onError: (String) -> Unit) {
        val config = session.config
        val transcript = session.transcript

        if (config.systemPrompt.isNotEmpty()) {
            transcript.addSystem(config.systemPrompt)
        }

        while (session.state == SessionState.RUNNING &&
            session.turnCount < config.maxTurns &&
            session.toolCallCount < config.maxToolCalls &&
            !session.isCancelled) {

            // 检查暂停
            while (session.isPaused && !session.isCancelled) {
                Thread.sleep(100)
            }
            if (session.isCancelled) break

            session.turnCount++

            // 发送 API 请求
            val response = callLLM(config, transcript.build(), session.toolCatalog.build())
            if (response == null) {
                onError("LLM API call failed")
                break
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

            // 发送 assistant 帧
            val assistantFrame = JSONObject().apply {
                put("type", "task-running")
                put("kind", "acp_event")
                put("data", JSONObject().apply {
                    put("type", "agent_message_chunk")
                    put("content", content)
                })
                put("timestamp", System.currentTimeMillis())
                put("seq", session.turnCount)
            }
            onFrame(assistantFrame)

            transcript.addAssistant(content, toolCalls)

            if (finishReason == "stop") {
                break
            }

            // 执行工具调用
            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    if (session.isCancelled) break
                    session.toolCallCount++

                    val toolCall = toolCalls.getJSONObject(i)
                    val toolCallId = toolCall.optString("id", "")
                    val function = toolCall.optJSONObject("function") ?: continue
                    val name = function.optString("name", "")
                    val args = function.optString("arguments", "{}")

                    // 工具调用帧
                    val toolFrame = JSONObject().apply {
                        put("type", "task-running")
                        put("kind", "acp_event")
                        put("data", JSONObject().apply {
                            put("type", "tool_call")
                            put("name", name)
                            put("arguments", args)
                            put("status", "running")
                        })
                        put("timestamp", System.currentTimeMillis())
                        put("seq", session.turnCount)
                    }
                    onFrame(toolFrame)

                    // 执行工具
                    val result = executeTool(name, args)
                    transcript.addToolResult(toolCallId, name, result)

                    // 工具结果帧
                    val resultFrame = JSONObject().apply {
                        put("type", "task-running")
                        put("kind", "acp_event")
                        put("data", JSONObject().apply {
                            put("type", "tool_call_update")
                            put("name", name)
                            put("status", "completed")
                            put("result", result)
                        })
                        put("timestamp", System.currentTimeMillis())
                        put("seq", session.turnCount)
                    }
                    onFrame(resultFrame)
                }
            } else {
                break
            }
        }

        // 发送结束帧
        val endFrame = JSONObject().apply {
            put("type", "task-ended")
            put("data", JSONObject().apply {
                put("status", if (session.isCancelled) "cancelled" else "finished")
                put("turns", session.turnCount)
                put("toolCalls", session.toolCallCount)
            })
            put("timestamp", System.currentTimeMillis())
            put("seq", session.turnCount + 1)
        }
        onFrame(endFrame)
    }

    private fun callLLM(config: AgentConfig, messages: JSONArray, tools: JSONArray): JSONObject? {
        val url = URL("${config.baseUrl}/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 120000

        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("max_tokens", config.maxOutput)
            put("stream", false)
            if (tools.length() > 0) put("tools", tools)
        }

        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray())
        }

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            return null
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        return JSONObject(responseBody)
    }

    private fun buildToolCatalog(toolNames: List<String>): ToolCatalog {
        val catalog = ToolCatalog()

        if (toolNames.contains("read_file") || toolNames.isEmpty()) {
            catalog.addTool("read_file", "Read a file from the filesystem", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file")
                    })
                })
                put("required", JSONArray().put("path"))
            })
        }

        if (toolNames.contains("write_file") || toolNames.isEmpty()) {
            catalog.addTool("write_file", "Write content to a file", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "Content to write")
                    })
                })
                put("required", JSONArray().put("path").put("content"))
            })
        }

        if (toolNames.contains("list_directory") || toolNames.isEmpty()) {
            catalog.addTool("list_directory", "List files in a directory", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Directory path")
                    })
                })
                put("required", JSONArray().put("path"))
            })
        }

        if (toolNames.contains("exec_command") || toolNames.isEmpty()) {
            catalog.addTool("exec_command", "Execute a shell command", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Shell command to execute")
                    })
                    put("identity", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("user").put("root"))
                    })
                })
                put("required", JSONArray().put("command"))
            })
        }

        if (toolNames.contains("screenshot") || toolNames.isEmpty()) {
            catalog.addTool("screenshot", "Take a screenshot of the device", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }

        if (toolNames.contains("gui_click") || toolNames.isEmpty()) {
            catalog.addTool("gui_click", "Click at screen coordinates", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply { put("type", "number") })
                    put("y", JSONObject().apply { put("type", "number") })
                })
                put("required", JSONArray().put("x").put("y"))
            })
        }

        return catalog
    }

    private fun executeTool(name: String, args: String): String {
        return try {
            val params = JSONObject(args)
            when (name) {
                "read_file" -> {
                    val path = params.optString("path", "")
                    File(path).readText()
                }
                "write_file" -> {
                    val path = params.optString("path", "")
                    val content = params.optString("content", "")
                    File(path).apply { parentFile?.mkdirs() }.writeText(content)
                    "File written successfully"
                }
                "list_directory" -> {
                    val path = params.optString("path", ".")
                    File(path).listFiles()?.joinToString("\n") { it.name } ?: "Empty directory"
                }
                "exec_command" -> {
                    val command = params.optString("command", "")
                    val identity = params.optString("identity", "user")
                    val shellCmd = if (identity == "root") {
                        arrayOf("su", "-c", command)
                    } else {
                        arrayOf("sh", "-c", command)
                    }
                    val process = Runtime.getRuntime().exec(shellCmd)
                    process.inputStream.bufferedReader().readText()
                }
                "screenshot" -> {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
                    val bytes = process.inputStream.readBytes()
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
                "gui_click" -> {
                    val x = params.optDouble("x", 0.0).toFloat()
                    val y = params.optDouble("y", 0.0).toFloat()
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
                    "Clicked at ($x, $y)"
                }
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}