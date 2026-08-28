package com.monkeycode.privileged

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 子代理管理器 —— 参照 shiyi-agent 的 spawn_agent 模式。
 *
 * 四种子代理类型: explore, plan, worker, general-purpose
 * 并行限制: 最多 4 个并行子代理
 * write_paths: 每个子代理只能写指定目录
 * 禁止递归委派: 子代理不能再 spawn
 * 轮数预算: 每个子代理有 maxTurns 限制
 * 帧输出: subagent_tool, subagent_text, subagent_output, child_session, task_notification
 */
class SubagentManager(
    private val agentRuntime: AgentRuntime
) {
    private val subagents = ConcurrentHashMap<String, SubagentState>()
    private val activeCount = AtomicInteger(0)
    val maxParallel = 4

    enum class SubagentType {
        EXPLORE, PLAN, WORKER, GENERAL_PURPOSE;

        companion object {
            fun fromString(s: String): SubagentType {
                return when (s.lowercase()) {
                    "explore" -> EXPLORE
                    "plan" -> PLAN
                    "worker" -> WORKER
                    else -> GENERAL_PURPOSE
                }
            }
        }

        fun asString(): String = when (this) {
            EXPLORE -> "explore"
            PLAN -> "plan"
            WORKER -> "worker"
            GENERAL_PURPOSE -> "general-purpose"
        }
    }

    data class SpawnConfig(
        val type: SubagentType,
        val name: String,
        val description: String = "",
        val systemPrompt: String = "",
        val task: String,
        val maxTurns: Int = 16,
        val writePaths: List<String> = emptyList(),
        val model: String? = null,
        val baseUrl: String? = null,
        val apiKey: String? = null,
        val interfaceType: String? = null
    )

    inner class SubagentState(
        val id: String,
        val parentSessionId: String,
        val config: SpawnConfig
    ) {
        var status: String = "running" // running, completed, failed, cancelled
        var turnCount: Int = 0
        var result: String = ""
        var error: String? = null
        var isCancelled: Boolean = false
    }

    fun canSpawn(): Boolean = activeCount.get() < maxParallel

    fun spawn(
        config: SpawnConfig,
        parentSessionId: String,
        onFrame: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ): String? {
        if (!canSpawn()) {
            onError("子代理并行数已达上限 ($maxParallel)")
            return null
        }

        val id = "child_${parentSessionId}_${activeCount.incrementAndGet()}"
        val state = SubagentState(id, parentSessionId, config)
        subagents[id] = state

        val emit: (JSONObject) -> Unit = { frame ->
            val data = frame.optJSONObject("data")
            if (frame.optString("kind") == "acp_event" && data?.has("sessionUpdate") == true) {
                frame.put("data", JSONObject().put("update", data))
            }
            onFrame(frame)
        }

        emit(JSONObject().apply {
            put("type", "task-running")
            put("kind", "acp_event")
            put("data", JSONObject().apply {
                put("sessionUpdate", "child_session")
                put("childSessionId", id)
                put("parentSessionId", parentSessionId)
                put("agentType", config.type.asString())
                put("agentName", config.name)
                put("status", "started")
            })
            put("timestamp", System.currentTimeMillis())
            put("seq", -1)
        })

        Thread {
            try {
                executeSubagent(state, emit, onError)
            } catch (e: Exception) {
                state.status = "failed"
                state.error = e.message
                emit(JSONObject().apply {
                    put("type", "task-running")
                    put("kind", "acp_event")
                    put("data", JSONObject().apply {
                        put("sessionUpdate", "child_session")
                        put("childSessionId", id)
                        put("status", "failed")
                        put("error", e.message ?: "unknown")
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("seq", -1)
                })
            } finally {
                activeCount.decrementAndGet()
                subagents.remove(id)

                emit(JSONObject().apply {
                    put("type", "task-running")
                    put("kind", "acp_event")
                    put("data", JSONObject().apply {
                        put("sessionUpdate", "task_notification")
                        put("agentId", id)
                        put("agentName", config.name)
                        put("description", config.description)
                        put("status", state.status)
                        put("result", state.result)
                        put("text", if (state.status == "completed") state.result.take(200) else "子代理未完成")
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("seq", -1)
                })
            }
        }.start()

        return id
    }

    fun cancelSubagent(id: String) {
        subagents[id]?.isCancelled = true
    }

    private fun executeSubagent(
        state: SubagentState,
        onFrame: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        val config = state.config
        val transcript = AgentRuntime.TranscriptBuilder()
        val toolCatalog = AgentRuntime.ToolCatalog().apply {
            addTool("read_file", "读取工作区文件", objectSchema("path", "string"))
            addTool("list_directory", "列出工作区目录", objectSchema("path", "string"))
            if (config.type == SubagentType.WORKER && config.writePaths.isNotEmpty()) {
                addTool("write_file", "写入授权目录内文件", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string"))
                        put("content", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray(listOf("path", "content")))
                })
            }
        }

        if (config.systemPrompt.isNotEmpty()) {
            transcript.addSystem(config.systemPrompt)
        }
        transcript.addSystem("你是一个子代理 (${config.type.asString()})，执行父代理分配的任务。")
        transcript.addSystem("你只能在以下目录中写入文件: ${config.writePaths.joinToString(", ")}")
        transcript.addSystem("你不能创建子代理或委派任务。")
        transcript.addUser(config.task)

        val agentConfig = AgentRuntime.AgentConfig(
            model = config.model ?: "deepseek-chat",
            baseUrl = config.baseUrl ?: "https://api.deepseek.com/v1",
            apiKey = config.apiKey ?: "",
            interfaceType = config.interfaceType ?: "openai_chat",
            systemPrompt = "",
            initialInput = "",
            maxTurns = config.maxTurns,
            maxToolCalls = config.maxTurns * 4,
            contextWindow = 64000,
            maxOutput = 8192
        )

        while (state.turnCount < config.maxTurns && !state.isCancelled) {
            state.turnCount++
            val response = agentRuntime.callLLMInternal(agentConfig, transcript.build(), toolCatalog.build())

            if (response == null) {
                state.status = "failed"
                state.error = "LLM API 调用失败"
                return
            }

            val choice = response.optJSONArray("choices")?.optJSONObject(0)
            if (choice == null) {
                state.status = "completed"
                state.result = "子代理完成"
                return
            }

            val message = choice.optJSONObject("message") ?: break
            val content = message.optString("content", "")
            val finishReason = choice.optString("finish_reason", "stop")

            if (content.isNotEmpty()) {
                onFrame(JSONObject().apply {
                    put("type", "task-running")
                    put("kind", "acp_event")
                    put("data", JSONObject().apply {
                        put("sessionUpdate", "subagent_text")
                        put("childSessionId", state.id)
                        put("content", JSONObject().apply {
                            put("type", "text")
                            put("text", content)
                        })
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("seq", state.turnCount)
                })
            }

            val toolCalls = message.optJSONArray("tool_calls")
            transcript.addAssistant(content, toolCalls)

            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name")
                    val args = function.optString("arguments", "{}")
                    val result = if (name == "write_file" && !isAuthorizedWrite(args, config.writePaths)) {
                        "错误: 子代理写入路径超出授权目录"
                    } else agentRuntime.executeSubagentTool(
                            state.parentSessionId, name, args, onFrame, onError
                        )
                    transcript.addToolResult(call.optString("id"), name, result)
                    onFrame(frame("subagent_tool", state).apply {
                        getJSONObject("data").getJSONObject("update").put("toolCallId", call.optString("id"))
                            .put("title", name).put("status", "completed").put("rawOutput", result)
                    })
                }
                continue
            }

            if (finishReason == "stop") {
                state.status = "completed"
                state.result = content
                onFrame(JSONObject().apply {
                    put("type", "task-running")
                    put("kind", "acp_event")
                    put("data", JSONObject().apply {
                        put("sessionUpdate", "subagent_output")
                        put("childSessionId", state.id)
                        put("output", content)
                    })
                    put("timestamp", System.currentTimeMillis())
                    put("seq", state.turnCount)
                })
                return
            }

            break
        }

        state.status = if (state.isCancelled) "cancelled" else "completed"
        state.result = "子代理完成 (${state.turnCount} 轮)"
    }

    private fun isAuthorizedWrite(args: String, writePaths: List<String>): Boolean = try {
        val path = JSONObject(args).optString("path")
        writePaths.any { agentRuntime.isPathInside(path, it) }
    } catch (_: Exception) {
        false
    }

    private fun frame(update: String, state: SubagentState): JSONObject = JSONObject().apply {
        put("type", "task-running")
        put("kind", "acp_event")
        put("data", JSONObject().apply {
            put("update", JSONObject().apply {
                put("sessionUpdate", update)
                put("childSessionId", state.id)
            })
        })
        put("timestamp", System.currentTimeMillis())
        put("seq", state.turnCount)
    }

    companion object {
        private fun objectSchema(name: String, type: String): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().put(name, JSONObject().put("type", type)))
            put("required", JSONArray(listOf(name)))
        }

        fun buildSpawnToolSchema(): JSONObject {
            return JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "spawn_agent")
                    put("description", "创建子代理执行独立任务。子代理并行运行，最多 4 个。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray(listOf("explore", "plan", "worker", "general-purpose")))
                                put("description", "子代理类型")
                            })
                            put("name", JSONObject().apply {
                                put("type", "string")
                                put("description", "子代理名称")
                            })
                            put("task", JSONObject().apply {
                                put("type", "string")
                                put("description", "分配给子代理的任务描述")
                            })
                            put("description", JSONObject().apply {
                                put("type", "string")
                                put("description", "任务简短描述（用于 UI 展示）")
                            })
                            put("max_turns", JSONObject().apply {
                                put("type", "integer")
                                put("description", "最大轮数预算 (默认 16)")
                            })
                            put("write_paths", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply { put("type", "string") })
                                put("description", "允许写入的目录路径列表")
                            })
                        })
                        put("required", JSONArray(listOf("type", "name", "task")))
                    })
                })
            }
        }
    }
}
