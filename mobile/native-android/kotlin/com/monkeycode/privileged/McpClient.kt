package com.monkeycode.privileged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Client —— 连接本地 MCP server，将 MCP 工具注册到 AgentRuntime 的工具目录。
 *
 * 读取 mcp.json 配置
 * 连接本地 MCP server (HTTP streamable-http)
 * 工具发现: tools/list
 * 工具注册到 AgentRuntime 的工具目录
 * 工具调用: tools/call
 */
class McpClient(private val context: Context) {

    data class McpServerConfig(
        val name: String,
        val url: String,
        val enabled: Boolean = true
    )

    data class McpTool(
        val serverName: String,
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )

    private val servers = mutableListOf<McpServerConfig>()
    private val tools = ConcurrentHashMap<String, McpTool>()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        val configFile = File(context.filesDir, "mcp.json")
        if (!configFile.exists()) {
            // 也尝试从 assets 读取
            try {
                val content = context.assets.open("mcp.json").bufferedReader().use { it.readText() }
                parseConfig(content)
            } catch (_: Exception) {
            }
            return
        }

        try {
            val content = configFile.readText()
            parseConfig(content)
        } catch (_: Exception) {
        }
    }

    private fun parseConfig(content: String) {
        try {
            val json = JSONObject(content)
            val mcpServers = json.optJSONObject("mcpServers")
            if (mcpServers != null) {
                val keys = mcpServers.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val server = mcpServers.optJSONObject(name)
                    if (server != null) {
                        val url = server.optString("url", "")
                        val enabled = server.optBoolean("enabled", true)
                        if (url.isNotEmpty()) {
                            servers.add(McpServerConfig(name, url, enabled))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun discoverTools(): List<McpTool> {
        tools.clear()
        for (server in servers) {
            if (!server.enabled) continue
            try {
                val response = sendMcpRequest(server.url, "tools/list", JSONObject())
                val result = response?.optJSONObject("result")
                val toolsList = result?.optJSONArray("tools")
                if (toolsList != null) {
                    for (i in 0 until toolsList.length()) {
                        val tool = toolsList.getJSONObject(i)
                        val name = tool.optString("name", "")
                        val description = tool.optString("description", "")
                        val inputSchema = tool.optJSONObject("inputSchema") ?: JSONObject()

                        val mcpTool = McpTool(server.name, name, description, inputSchema)
                        tools["mcp_${server.name}_$name"] = mcpTool
                    }
                }
            } catch (_: Exception) {
                // 服务器不可用，跳过
            }
        }
        return tools.values.toList()
    }

    fun callTool(serverName: String, toolName: String, arguments: JSONObject): String {
        val key = "mcp_${serverName}_$toolName"
        val tool = tools[key] ?: return "MCP 工具未找到: $serverName/$toolName"

        val server = servers.find { it.name == serverName } ?: return "MCP 服务器未找到: $serverName"

        try {
            val params = JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            }
            val response = sendMcpRequest(server.url, "tools/call", params)
            val result = response?.optJSONObject("result")
            val content = result?.optJSONArray("content")
            if (content != null) {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.getJSONObject(i)
                    sb.append(item.optString("text", item.toString()))
                }
                return sb.toString()
            }
            return result?.toString() ?: "无结果"
        } catch (e: Exception) {
            return "MCP 工具调用失败: ${e.message}"
        }
    }

    fun registerToolsToCatalog(catalog: AgentRuntime.ToolCatalog) {
        for ((key, tool) in tools) {
            // 将 MCP 工具 inputSchema 适配为 OpenAI function 格式
            catalog.addTool(key, "[MCP:${tool.serverName}] ${tool.description}", tool.inputSchema)
        }
    }

    private fun sendMcpRequest(serverUrl: String, method: String, params: JSONObject): JSONObject? {
        val url = URL(serverUrl.trimEnd('/'))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 30000

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis().toString())
            put("method", method)
            put("params", params)
        }

        try {
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            return JSONObject(resp)
        } catch (e: Exception) {
            conn.disconnect()
            return null
        }
    }
}