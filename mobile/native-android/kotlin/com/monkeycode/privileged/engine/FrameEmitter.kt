package com.monkeycode.privileged.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地引擎产帧器：词汇与上游桌面版 desktop/src/driver/frame.rs 一一对应，
 * 作为移动端本地产帧的唯一出口。
 *
 * 帧结构 { type, kind?, data?(内联 JSON 对象), timestamp(ms), seq }；
 * task-running + acp_event 的 data 为 { update: { sessionUpdate, ... } }。
 */
class FrameEmitter {

    private val seqCounter = AtomicLong(0)

    private fun nextSeq(): Long = seqCounter.incrementAndGet()

    private fun build(ftype: String, kind: String?, payload: JSONObject?): JSONObject {
        val f = JSONObject()
        f.put("type", ftype)
        if (kind != null) f.put("kind", kind)
        if (payload != null) f.put("data", payload)
        f.put("timestamp", System.currentTimeMillis())
        f.put("seq", nextSeq())
        return f
    }

    private fun acp(update: JSONObject): JSONObject =
        build("task-running", "acp_event", JSONObject().put("update", update))

    fun base64Text(text: String): String =
        android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    // ==================== 顶层帧 ====================

    fun taskStarted(): JSONObject = build("task-started", null, null)

    fun taskEnded(): JSONObject = build("task-ended", null, null)

    /** 引擎报错但轮次未收尾：terminal=false 保持 UI running 语义 */
    fun taskErrorPending(msg: String): JSONObject =
        build(
            "task-error", null,
            JSONObject().put("error", msg).put("terminal", false),
        )

    fun userInput(text: String): JSONObject =
        build("user-input", null, JSONObject().put("content", base64Text(text)))

    fun steerInput(text: String, clientId: String): JSONObject =
        build(
            "user-input", null,
            JSONObject()
                .put("content", base64Text(text))
                .put("source", "steer")
                .put("client_id", clientId),
        )

    fun steerConfirmed(clientId: String): JSONObject =
        build("steer-confirmed", null, JSONObject().put("client_id", clientId))

    fun permissionReq(id: String, tool: String, title: String, toolCallId: String): JSONObject {
        val d = JSONObject().put("id", id).put("tool", tool).put("title", title)
        if (toolCallId.isNotEmpty()) d.put("tool_call_id", toolCallId)
        return build("permission-req", null, d)
    }

    fun permissionResolved(id: String, outcome: String): JSONObject =
        build("permission-resolved", null, JSONObject().put("id", id).put("outcome", outcome))

    fun replyQuestion(requestId: String, answersJson: String, cancelled: Boolean): JSONObject =
        build(
            "reply-question", null,
            JSONObject()
                .put("request_id", requestId)
                .put("answers_json", answersJson)
                .put("cancelled", cancelled),
        )

    fun askUserQuestion(requestId: String, questions: Any?): JSONObject = build(
        "task-running", "acp_ask_user_question",
        JSONObject().put(
            "toolCall",
            JSONObject()
                .put("toolCallId", requestId)
                .put("title", "Ask User Question")
                .put("kind", "ask-user-question")
                .put("status", "in_progress")
                .put("rawInput", JSONObject().put("questions", questions ?: JSONObject())),
        ),
    )

    // ==================== ACP session update 帧 ====================

    fun agentText(delta: String): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "agent_message_chunk")
            .put("content", JSONObject().put("type", "text").put("text", delta)),
    )

    fun agentThought(delta: String): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "agent_thought_chunk")
            .put("content", JSONObject().put("type", "text").put("text", delta)),
    )

    fun plan(entries: JSONArray): JSONObject = acp(
        JSONObject().put("sessionUpdate", "plan").put("entries", entries),
    )

    fun toolCall(tcId: String, title: String, rawInput: JSONObject?): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "tool_call")
            .put("toolCallId", tcId)
            .put("title", title)
            .put("status", "in_progress")
            .put("rawInput", rawInput ?: JSONObject()),
    )

    fun toolCallCompleted(tcId: String, rawOutput: String, images: List<String>): JSONObject {
        val u = JSONObject()
            .put("sessionUpdate", "tool_call_update")
            .put("toolCallId", tcId)
            .put("status", "completed")
            .put("rawOutput", rawOutput)
        if (images.isNotEmpty()) u.put("images", JSONArray(images))
        return acp(u)
    }

    fun toolCallProgress(tcId: String, progress: JSONObject): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "tool_call_update")
            .put("toolCallId", tcId)
            .put("status", "in_progress")
            .put("progress", progress),
    )

    fun toolCallFailed(tcId: String, rawOutput: String): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "tool_call_update")
            .put("toolCallId", tcId)
            .put("status", "failed")
            .put("rawOutput", rawOutput),
    )

    fun usageUpdate(used: Long, size: Long): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "usage_update")
            .put("used", used)
            .put("size", size),
    )

    fun compactStatus(status: String): JSONObject = acp(
        JSONObject().put("sessionUpdate", "compact_status").put("status", status),
    )

    fun backgroundResult(
        agentId: String,
        agentName: String,
        description: String,
        status: String,
        result: String,
        text: String,
    ): JSONObject = acp(
        JSONObject()
            .put("sessionUpdate", "task_notification")
            .put("agentId", agentId)
            .put("agentName", agentName)
            .put("description", description)
            .put("status", status)
            .put("result", result)
            .put("text", text),
    )

    fun modelUpdate(model: String): JSONObject = acp(
        JSONObject().put("sessionUpdate", "model_update").put("model", model),
    )

    fun thinkUpdate(think: String): JSONObject = acp(
        JSONObject().put("sessionUpdate", "think_update").put("think", think),
    )

    fun permissionModeUpdate(mode: String): JSONObject = acp(
        JSONObject().put("sessionUpdate", "permission_mode_update").put("mode", mode),
    )
}
