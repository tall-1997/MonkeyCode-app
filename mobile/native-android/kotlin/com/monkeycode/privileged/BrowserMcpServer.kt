package com.monkeycode.privileged

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BrowserMcpServer(private val browserService: BrowserService) {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val bearerToken: String = generateToken()
    private val sessions = ConcurrentHashMap<String, McpProtocolSession>()
    private val inflightCount = AtomicInteger(0)
    private var port: Int = 8899

    companion object {
        private const val MAX_INFLIGHT = 32
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }

    private class McpProtocolSession(
        val sessionId: String,
        val contexts: ConcurrentHashMap<String, McpCallContext> = ConcurrentHashMap()
    ) {
        val closed = AtomicBoolean(false)
    }

    private class McpCallContext(
        val callMu: Any = Any()
    )

    fun getToken(): String = bearerToken

    fun start(port: Int): Map<String, String> {
        this.port = port
        if (running.get()) stop()
        serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
        running.set(true)
        serverThread = Thread {
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    if (inflightCount.get() >= MAX_INFLIGHT) {
                        sendHttp(socket, 503, "text/plain", "too many connections", null)
                        socket.close()
                        continue
                    }
                    inflightCount.incrementAndGet()
                    Thread {
                        try {
                            handleConnection(socket)
                        } finally {
                            inflightCount.decrementAndGet()
                        }
                    }.start()
                } catch (e: Exception) {
                    if (running.get()) {
                        android.util.Log.w("MonkeyCode.MCP", "Accept error", e)
                    }
                }
            }
        }
        serverThread?.start()
        return mapOf(
            "url" to "http://127.0.0.1:$port/mcp",
            "token" to bearerToken
        )
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        sessions.clear()
    }

    private fun handleConnection(socket: Socket) {
        try {
            val req = readHttpRequest(socket) ?: run {
                sendHttp(socket, 400, "text/plain", "bad request", null)
                socket.close()
                return
            }

            if (!authenticate(req)) {
                sendHttp(socket, 401, "application/json", """{"error":"unauthorized"}""", null)
                socket.close()
                return
            }

            if (req.method != "POST") {
                sendHttp(socket, 405, "text/plain", "method not allowed", null)
                socket.close()
                return
            }

            val rpc = try { JSONObject(req.body) } catch (e: Exception) {
                sendHttp(socket, 400, "text/plain", "bad json", null)
                socket.close()
                return
            }

            val method = rpc.optString("method", "")
            val protocolId = if (method == "initialize") {
                req.mcpSessionId?.takeIf { sessions.containsKey(it) }
                    ?: createSession()
            } else {
                req.mcpSessionId?.takeIf { sessions.containsKey(it) }
                    ?: run {
                        sendHttp(socket, 404, "text/plain", "unknown MCP session", null)
                        socket.close()
                        return
                    }
            }

            if (!rpc.has("id")) {
                sendHttp(socket, 202, "application/json", "", protocolId)
                socket.close()
                return
            }

            val id = rpc.opt("id")
            val params = rpc.optJSONObject("params") ?: JSONObject()

            val result = try {
                dispatch(method, params, protocolId)
            } catch (e: Exception) {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", JSONObject().apply {
                        put("code", -32000)
                        put("message", e.message ?: "未知错误")
                    })
                }
            }

            sendHttp(socket, 200, "application/json", result.toString(), protocolId)
        } catch (e: Exception) {
            android.util.Log.e("MonkeyCode.MCP", "Connection error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun dispatch(method: String, params: JSONObject, protocolId: String): JSONObject {
        return when (method) {
            "initialize" -> {
                val ver = params.optString("protocolVersion", "2025-03-26")
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", params.opt("id"))
                    put("result", JSONObject().apply {
                        put("protocolVersion", ver)
                        put("capabilities", JSONObject().apply { put("tools", JSONObject()) })
                        put("serverInfo", JSONObject().apply {
                            put("name", "mc-browser-android")
                            put("version", "1.0.0")
                        })
                    })
                }
            }
            "ping" -> {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", params.opt("id"))
                    put("result", JSONObject())
                }
            }
            "tools/list" -> {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", params.opt("id"))
                    put("result", JSONObject().apply {
                        put("tools", buildToolList())
                    })
                }
            }
            "tools/call" -> {
                val name = params.optString("name", "")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val sessionId = getSessionId(params)
                val ctx = getOrCreateContext(protocolId, sessionId)
                synchronized(ctx.callMu) {
                    val resultContent = callTool(name, arguments)
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", params.opt("id"))
                        put("result", JSONObject().apply {
                            put("content", resultContent)
                            put("isError", false)
                        })
                    }
                }
            }
            else -> {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", params.opt("id"))
                    put("error", JSONObject().apply {
                        put("code", -32601)
                        put("message", "method not found: $method")
                    })
                }
            }
        }
    }

    private fun callTool(name: String, args: JSONObject): JSONArray {
        val result = JSONArray()
        try {
            when (name) {
                "browser_navigate" -> {
                    val url = args.optString("url", "")
                    val text = browserService.browserNavigate(url)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                }
                "browser_screenshot" -> {
                    val elementRef = args.optString("elementRef", "").takeIf { it.isNotEmpty() }
                    val data = browserService.browserScreenshot(elementRef)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", data)
                    })
                }
                "browser_snapshot" -> {
                    val text = browserService.browserSnapshot()
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                }
                "browser_click" -> {
                    val ref = args.optString("ref", "")
                    browserService.browserClick(ref)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", "已点击 $ref")
                    })
                }
                "browser_type" -> {
                    val ref = args.optString("ref", "")
                    val text = args.optString("text", "")
                    browserService.browserType(ref, text)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", "已在 $ref 输入 \"${text.take(60)}\"")
                    })
                }
                "browser_scroll" -> {
                    val ref = args.optString("ref", "").takeIf { it.isNotEmpty() }
                    browserService.browserScroll(ref)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (ref != null) "已滚动到 $ref" else "已滚动一屏")
                    })
                }
                "browser_evaluate" -> {
                    val expression = args.optString("expression", "")
                    val evalResult = browserService.browserEvaluate(expression)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", evalResult)
                    })
                }
                "browser_tabs" -> {
                    val action = args.optString("action", "list")
                    val tabId = args.optString("tabId", "").takeIf { it.isNotEmpty() }
                    val text = browserService.browserTabs(action, tabId)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                }
                "browser_dialog" -> {
                    val action = args.optString("action", "accept")
                    val text = browserService.browserDialog(action)
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                }
                else -> {
                    result.put(JSONObject().apply {
                        put("type", "text")
                        put("text", "未知工具: $name")
                    })
                }
            }
        } catch (e: Exception) {
            result.put(JSONObject().apply {
                put("type", "text")
                put("text", "错误: ${e.message}")
            })
        }
        return result
    }

    private fun buildToolList(): JSONArray {
        val tools = JSONArray()

        tools.put(JSONObject().apply {
            put("name", "browser_navigate")
            put("description", "在 WebView 中打开网页。仅支持 http/https。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "目标 URL")
                    })
                })
                put("required", JSONArray().apply { put("url") })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_screenshot")
            put("description", "截取当前页面图片。可指定元素引用截取元素区域。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("elementRef", JSONObject().apply {
                        put("type", "string")
                        put("description", "元素引用编号,如 e3;不填则全页截图")
                    })
                })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_snapshot")
            put("description", "获取当前页面快照:标题/URL + 带编号的可交互元素列表。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_click")
            put("description", "点击页面元素(browser_snapshot 返回的编号,如 e3)。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("ref", JSONObject().apply {
                        put("type", "string")
                        put("description", "元素编号,如 e3")
                    })
                })
                put("required", JSONArray().apply { put("ref") })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_type")
            put("description", "在输入框中输入文本(按元素编号定位)。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("ref", JSONObject().apply {
                        put("type", "string")
                        put("description", "元素编号,如 e3")
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "要输入的文本")
                    })
                })
                put("required", JSONArray().apply { put("ref"); put("text") })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_scroll")
            put("description", "滚动页面:无 ref 翻一屏,有 ref 滚动到元素。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("ref", JSONObject().apply {
                        put("type", "string")
                        put("description", "滚动到该元素(可选)")
                    })
                })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_evaluate")
            put("description", "执行任意 JavaScript 表达式并返回结果。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("expression", JSONObject().apply {
                        put("type", "string")
                        put("description", "JS 表达式")
                    })
                })
                put("required", JSONArray().apply { put("expression") })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_tabs")
            put("description", "标签页管理:list 列出,create 新建,close 关闭,switch 切换。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply { put("list"); put("create"); put("close"); put("switch") })
                        put("description", "操作类型")
                    })
                    put("tabId", JSONObject().apply {
                        put("type", "string")
                        put("description", "目标标签页ID(close/switch 必填)")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            })
        })

        tools.put(JSONObject().apply {
            put("name", "browser_dialog")
            put("description", "对话框处理:accept 接受,dismiss 关闭。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply { put("accept"); put("dismiss") })
                        put("description", "操作类型")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            })
        })

        return tools
    }

    private fun createSession(): String {
        val id = UUID.randomUUID().toString().take(8)
        sessions[id] = McpProtocolSession(id)
        return id
    }

    private fun getOrCreateContext(protocolId: String, agentId: String?): McpCallContext {
        val session = sessions[protocolId] ?: throw IllegalStateException("MCP 会话已关闭")
        val key = agentId?.takeIf { it.isNotEmpty() } ?: "root"
        return session.contexts.getOrPut(key) { McpCallContext() }
    }

    private fun getSessionId(params: JSONObject): String? {
        val meta = params.optJSONObject("_meta") ?: return null
        return meta.optString("session_id", "").takeIf { it.isNotEmpty() }
    }

    private fun authenticate(req: HttpRequest): Boolean {
        return req.bearerToken == bearerToken
    }

    data class HttpRequest(
        val method: String,
        val bearerToken: String?,
        val mcpSessionId: String?,
        val body: String
    )

    private fun readHttpRequest(socket: Socket): HttpRequest? {
        try {
            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            var line = reader.readLine() ?: return null
            val parts = line.split(" ")
            if (parts.size < 2) return null
            val method = parts[0]
            var bearerToken: String? = null
            var mcpSessionId: String? = null
            var contentLength = 0
            var headerCount = 0
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (++headerCount > 100) return null
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (lower.startsWith("authorization:")) {
                    val value = line.substringAfter(":").trim()
                    bearerToken = value.removePrefix("Bearer ")
                }
                if (lower.startsWith("mcp-session-id:")) {
                    mcpSessionId = line.substringAfter(":").trim().takeIf { it.isNotEmpty() }
                }
            }
            if (contentLength > MAX_BODY_BYTES) return null
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                String(buf)
            } else ""
            return HttpRequest(method, bearerToken, mcpSessionId, body)
        } catch (e: Exception) {
            return null
        }
    }

    private fun sendHttp(socket: Socket, status: Int, contentType: String, body: String, sessionId: String?) {
        try {
            val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
            val reason = when (status) {
                200 -> "OK"
                202 -> "Accepted"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                500 -> "Internal Server Error"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            val sessionHeader = sessionId?.let { "Mcp-Session-Id: $it\r\n" } ?: ""
            writer.write("HTTP/1.1 $status $reason\r\n")
            writer.write("Content-Type: $contentType\r\n")
            writer.write(sessionHeader)
            writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(body)
            writer.flush()
        } catch (e: Exception) {
            android.util.Log.w("MonkeyCode.MCP", "Write error", e)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}