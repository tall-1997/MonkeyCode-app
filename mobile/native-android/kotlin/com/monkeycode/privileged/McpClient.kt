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
        val enabled: Boolean = true,
        val headers: Map<String, String> = emptyMap()
    )

    data class McpTool(
        val serverName: String,
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )

    private val servers = mutableListOf<McpServerConfig>()
    private val tools = ConcurrentHashMap<String, McpTool>()
    private val sessionIds = ConcurrentHashMap<String, String>()
    private val protocolVersions = ConcurrentHashMap<String, String>()
    private val initialized = ConcurrentHashMap.newKeySet<String>()

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
                        val headers = mutableMapOf<String, String>()
                        server.optJSONObject("headers")?.let { configured ->
                            configured.keys().forEach { key -> headers[key] = configured.optString(key) }
                        }
                        server.optString("token").takeIf { it.isNotBlank() }?.let {
                            headers.putIfAbsent("Authorization", "Bearer $it")
                        }
                        if (url.isNotEmpty()) {
                            servers.add(McpServerConfig(name, url, enabled, headers))
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
                ensureInitialized(server)
                val response = sendMcpRequest(server, "tools/list", JSONObject())
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
            ensureInitialized(server)
            val response = sendMcpRequest(server, "tools/call", params)
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

    fun callRegisteredTool(registeredName: String, arguments: JSONObject): String {
        val tool = tools[registeredName] ?: return "MCP 工具未找到: $registeredName"
        return callTool(tool.serverName, tool.name, arguments)
    }

    fun registerToolsToCatalog(catalog: AgentRuntime.ToolCatalog) {
        for ((key, tool) in tools) {
            // 将 MCP 工具 inputSchema 适配为 OpenAI function 格式
            catalog.addTool(key, "[MCP:${tool.serverName}] ${tool.description}", tool.inputSchema)
        }
    }

    private fun ensureInitialized(server: McpServerConfig) {
        if (initialized.contains(server.name)) return
        synchronized(initialized) {
            if (initialized.contains(server.name)) return
            val response = sendMcpRequest(server, "initialize", JSONObject().apply {
                put("protocolVersion", "2025-03-26")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "monkeycode-mobile")
                    put("version", "1.0")
                })
            }) ?: throw IllegalStateException("MCP initialize 失败: ${server.name}")
            if (response.has("error")) throw IllegalStateException(response.getJSONObject("error").optString("message"))
            response.optJSONObject("result")?.optString("protocolVersion")
                ?.takeIf { it.isNotBlank() }
                ?.let { protocolVersions[server.name] = it }
            sendMcpNotification(server, "notifications/initialized")
            initialized.add(server.name)
        }
    }

    private fun configureConnection(conn: HttpURLConnection, server: McpServerConfig) {
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        server.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        sessionIds[server.name]?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        protocolVersions[server.name]?.let { conn.setRequestProperty("MCP-Protocol-Version", it) }
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
    }

    private fun sendMcpNotification(server: McpServerConfig, method: String) {
        val conn = URL(server.url.trimEnd('/')).openConnection() as HttpURLConnection
        configureConnection(conn, server)
        try {
            conn.outputStream.use { it.write(JSONObject().apply {
                put("jsonrpc", "2.0"); put("method", method)
            }.toString().toByteArray()) }
            conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionIds[server.name] = it }
        } finally {
            conn.disconnect()
        }
    }

    private fun sendMcpRequest(server: McpServerConfig, method: String, params: JSONObject): JSONObject? {
        val url = URL(server.url.trimEnd('/'))
        val conn = url.openConnection() as HttpURLConnection
        configureConnection(conn, server)

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

            conn.getHeaderField("Mcp-Session-Id")?.let { sessionIds[server.name] = it }
            val raw = conn.inputStream.bufferedReader().readText()
            val resp = if (conn.contentType?.startsWith("text/event-stream") == true) {
                raw.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: return null
            } else raw
            conn.disconnect()
            return JSONObject(resp)
        } catch (e: Exception) {
            conn.disconnect()
            return null
        }
    }
}
