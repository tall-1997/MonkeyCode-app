package com.monkeycode.privileged.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 三协议 LLM 客户端（openai_chat / openai_responses / anthropic），
 * 接口分派对齐桌面版 config.rs route_of；无论何种协议，输出均归一化为
 * OpenAI chat 形状 {choices:[{message:{content,tool_calls},finish_reason}]}，
 * 由调用方直接驱动主循环。
 */
class LlmClient(
    private val interfaceType: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxOutput: Int = 8192,
) {
    /** 流式回调：onText→agent_message_chunk；onThought→agent_thought_chunk。 */
    class StreamHandlers(
        val onText: (String) -> Unit = {},
        val onThought: (String) -> Unit = {},
    )

    fun isStreamingCapable(): Boolean =
        interfaceType != "openai_responses"

    fun call(
        messages: JSONArray,
        tools: JSONArray,
        handlers: StreamHandlers? = null,
    ): JSONObject? {
        return when (interfaceType) {
            "anthropic" -> if (handlers != null) anthropicStream(messages, tools, handlers) else anthropicCall(messages, tools)
            "openai_responses" -> responsesCall(messages, tools, handlers)
            else -> if (handlers != null) openAiChatStream(messages, tools, handlers) else openAiChatCall(messages, tools)
        }
    }

    // ── OpenAI Chat Completions ─────────────────────────────

    private fun openAiChatCall(messages: JSONArray, tools: JSONArray): JSONObject? {
        val conn = jsonConn(chatEndpointOf(baseUrl), extraHeaders())
        writeClose(conn, chatBody(messages, tools, stream = false))
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(resp)
    }

    private fun openAiChatStream(messages: JSONArray, tools: JSONArray, h: StreamHandlers): JSONObject? {
        val conn = jsonConn(chatEndpointOf(baseUrl), extraHeaders())
        writeClose(conn, chatBody(messages, tools, stream = true))
        if (conn.responseCode != 200) { conn.disconnect(); return null }

        val contentSb = StringBuilder()
        val toolCalls = JSONArray()
        var finish = "stop"

        try {
            readSse(conn.inputStream.bufferedReader()) { data ->
                if (data == "[DONE]") return@readSse
                val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return@readSse
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@readSse
                val delta = choice.optJSONObject("delta") ?: return@readSse
                val textPiece = delta.optString("content", "")
                if (textPiece.isNotEmpty()) { contentSb.append(textPiece); h.onText(textPiece) }
                val reasoning = delta.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) h.onThought(reasoning)
                collectChatToolDeltas(delta.optJSONArray("tool_calls"), toolCalls)
                val fr = delta.optString("finish_reason", "")
                if (fr.isNotEmpty()) finish = fr
            }
            val message = JSONObject().put("role", "assistant").put("content", contentSb.toString())
            if (toolCalls.length() > 0) {
                finalizePartialToolArgs(toolCalls)
                message.put("tool_calls", toolCalls)
                finish = "tool_calls"
            }
            return normalizedChoice(message, finish)
        } finally {
            conn.disconnect()
        }
    }

    private fun chatBody(messages: JSONArray, tools: JSONArray, stream: Boolean): JSONObject =
        JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxOutput)
            put("stream", stream)
            put("temperature", 0.2)
            if (tools.length() > 0) put("tools", tools)
        }

    // ── OpenAI Responses ────────────────────────────────────

    /**
     * Responses API：走非流式整包响应保证正确性，随后把全文经一次
     * 回调下发（调用方帧化接口不变）。
     */
    private fun responsesCall(messages: JSONArray, tools: JSONArray, h: StreamHandlers?): JSONObject? {
        val base = baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/v1")) "$base/responses" else "$base/v1/responses"
        val conn = jsonConn(endpoint, extraHeaders())
        writeClose(conn, responsesBody(messages, tools))
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val chatShaped = responsesToChat(JSONObject(resp))
        if (h != null) {
            val text = chatShaped.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
            if (text.isNotEmpty()) h.onText(text)
        }
        return chatShaped
    }

    private fun responsesBody(messages: JSONArray, tools: JSONArray): JSONObject {
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            when (val role = m.optString("role", "user")) {
                "assistant" -> input.put(JSONObject().apply {
                    put("type", "message"); put("role", "assistant")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "output_text"); put("text", m.optString("content", ""))
                    }))
                })
                "tool" -> input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", m.optString("tool_call_id", ""))
                    put("output", m.optString("content", ""))
                })
                else -> input.put(JSONObject().apply {
                    put("type", "message"); put("role", role)
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_text"); put("text", m.optString("content", ""))
                    }))
                })
            }
        }
        return JSONObject().apply {
            put("model", model)
            put("input", input)
            put("max_output_tokens", maxOutput)
            put("stream", false)
            if (tools.length() > 0) put("tools", tools)
        }
    }

    // ── Anthropic Messages ──────────────────────────────────

    private fun anthropicCall(messages: JSONArray, tools: JSONArray): JSONObject? {
        val conn = jsonConn(anthropicEndpoint(), extraHeaders(includeAnthropicVersion = true))
        writeClose(conn, anthropicBody(messages, tools, stream = false))
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return anthropicToChat(JSONObject(resp))
    }

    private fun anthropicStream(messages: JSONArray, tools: JSONArray, h: StreamHandlers): JSONObject? {
        val conn = jsonConn(anthropicEndpoint(), extraHeaders(includeAnthropicVersion = true))
        writeClose(conn, anthropicBody(messages, tools, stream = true))
        if (conn.responseCode != 200) { conn.disconnect(); return null }

        val contentSb = StringBuilder()
        val toolCalls = JSONArray()
        var stopReason = "end_turn"

        try {
            readSse(conn.inputStream.bufferedReader()) { data ->
                val ev = runCatching { JSONObject(data) }.getOrNull() ?: return@readSse
                when (ev.optString("type")) {
                    "content_block_start" -> {}
                    "content_block_delta" -> {
                        val d = ev.optJSONObject("delta") ?: return@readSse
                        when (d.optString("type")) {
                            "text_delta" -> {
                                val piece = d.optString("text", "")
                                if (piece.isNotEmpty()) { contentSb.append(piece); h.onText(piece) }
                            }
                            "thinking_delta" -> {
                                val piece = d.optString("thinking", "")
                                if (piece.isNotEmpty()) h.onThought(piece)
                            }
                        }
                    }
                    "message_delta" -> {
                        stopReason = ev.optJSONObject("delta")?.optString("stop_reason", "") ?: ""
                    }
                }
            }
            val finish = if (stopReason == "tool_use") "tool_calls" else "stop"
            val message = JSONObject().put("role", "assistant").put("content", contentSb.toString())
            if (finish == "tool_calls" && toolCalls.length() > 0) message.put("tool_calls", toolCalls)
            return normalizedChoice(message, finish)
        } finally {
            conn.disconnect()
        }
    }

    private fun anthropicBody(messages: JSONArray, tools: JSONArray, stream: Boolean): JSONObject {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxOutput)
            put("messages", toAnthropicMessages(messages))
            put("stream", stream)
            if (tools.length() > 0) {
                // OpenAI function 结构投影为 Anthropic input_schema 形状
                val arr = JSONArray()
                for (i in 0 until tools.length()) {
                    val t = tools.getJSONObject(i).optJSONObject("function") ?: continue
                    arr.put(JSONObject().apply {
                        put("name", t.optString("name"))
                        put("description", t.optString("description", ""))
                        put("input_schema", t.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                    })
                }
                put("tools", arr)
            }
        }
        return body
    }

    private fun anthropicEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    }

    private fun extraHeaders(includeAnthropicVersion: Boolean = false): Map<String, String> =
        if (interfaceType == "anthropic" || includeAnthropicVersion) {
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Authorization" to "",
            )
        } else emptyMap()

    // ── 协议工具 ─────────────────────────────────────────────

    private fun jsonConn(endpoint: String, extraHeaders: Map<String, String> = emptyMap()): HttpURLConnection {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        val authHeader = extraHeaders["Authorization"]
        if (authHeader.isNullOrEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        for ((k, v) in extraHeaders) {
            if (k == "Authorization" && v.isEmpty()) continue
            conn.setRequestProperty(k, v)
        }
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = if (extraHeaders.containsKey("anthropic-version") || extraHeaders.containsKey("x-api-key")) 600000 else 180000
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

    private fun readSse(reader: BufferedReader, onData: (String) -> Unit) {
        reader.useLines { lines ->
            var dataLine: String? = null
            for (line in lines) {
                when {
                    line.startsWith("data:") -> {
                        dataLine = line.removePrefix("data:").trim()
                    }
                    line.isEmpty() -> {
                        dataLine?.let(onData)
                        dataLine = null
                    }
                }
            }
            dataLine?.let(onData)
        }
    }

    /** 聚合 OpenAI 流式 tool_calls 增量片段。 */
    private fun collectChatToolDeltas(arr: JSONArray?, toolCalls: JSONArray) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val idx = item.optInt("index", i)
            while (idx >= toolCalls.length()) toolCalls.put(JSONObject())
            val acc = toolCalls.getJSONObject(idx)
            if (!acc.has("id")) acc.put("id", "").put("type", "function")
                .put("function", JSONObject().put("name", "").put("arguments", ""))
            if (item.has("id") && item.optString("id").isNotEmpty()) acc.put("id", item.optString("id"))
            val fn = item.optJSONObject("function")
            if (fn != null) {
                val accFn = acc.getJSONObject("function")
                if (fn.has("name") && fn.optString("name").isNotEmpty()) accFn.put("name", accFn.optString("name") + fn.optString("name"))
                if (fn.has("arguments")) accFn.put("arguments", accFn.optString("arguments") + fn.optString("arguments"))
            }
        }
    }

    private fun finalizePartialToolArgs(toolCalls: JSONArray) {
        for (i in 0 until toolCalls.length()) {
            val acc = toolCalls.getJSONObject(i)
            val fn = acc.optJSONObject("function") ?: continue
            if (!fn.has("arguments") || fn.optString("arguments").isEmpty()) fn.put("arguments", "{}")
        }
    }

    private fun normalizedChoice(message: JSONObject, finish: String): JSONObject = JSONObject().apply {
        put("choices", JSONArray().put(JSONObject().apply {
            put("index", 0); put("message", message); put("finish_reason", finish)
        }))
    }

    /** [system -> user/assistant 交替；tool] 折叠为 user 段落（Anthropic 不允许 system 在 messages 中）。 */
    private fun toAnthropicMessages(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role", "user")
            when (role) {
                "system" -> {}
                "tool" -> {
                    val prev = if (out.length() > 0) out.getJSONObject(out.length() - 1) else null
                    if (prev != null && prev.optString("role") == "user") {
                        prev.put("content", "${prev.optString("content", "")}\n\n[Tool Result] ${m.optString("content", "")}")
                    } else {
                        out.put(JSONObject().apply {
                            put("role", "user"); put("content", "[Tool Result] ${m.optString("content", "")}")
                        })
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

    private fun chatEndpointOf(baseIn: String): String {
        var b = baseIn.trim()
        if (b.endsWith("/")) b = b.dropLast(1)
        if (b.endsWith("/chat/completions")) return b
        if (b.endsWith("/v1")) return "$b/chat/completions"
        return "$b/v1/chat/completions"
    }

    /** Responses -> OpenAI chat 形状。 */
    private fun responsesToChat(r: JSONObject): JSONObject {
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
                    put("id", it.optString("call_id", "call_$i"))
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", it.optString("name", ""))
                        put("arguments", it.optJSONObject("arguments")?.toString() ?: it.optString("arguments", "{}"))
                    })
                })
            }
        }
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", sb.toString())
            if (toolCalls.length() > 0) put("tool_calls", toolCalls)
        }
        return normalizedChoice(message, if (toolCalls.length() > 0) "tool_calls" else "stop")
    }

    /** Anthropic -> OpenAI chat 形状。 */
    private fun anthropicToChat(r: JSONObject): JSONObject {
        val sb = StringBuilder()
        val toolCalls = JSONArray()
        val content = r.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            when (block.optString("type")) {
                "text" -> sb.append(block.optString("text", ""))
                "tool_use" -> toolCalls.put(JSONObject().apply {
                    put("id", block.optString("id", "call_$i"))
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", block.optString("name", ""))
                        put("arguments", block.optJSONObject("input")?.toString() ?: "{}")
                    })
                })
            }
        }
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", sb.toString())
            if (toolCalls.length() > 0) put("tool_calls", toolCalls)
        }
        val stopReason = r.optString("stop_reason", "end_turn")
        return normalizedChoice(message, if (stopReason == "tool_use") "tool_calls" else "stop")
    }
}
