package com.monkeycode.privileged.engine

import org.json.JSONObject

/**
 * 子代理系统：类型与白名单思路对齐参考仓 shiyi lib/services/subagent.dart，
 * 进度经 frame.rs 定义的 tool_call_progress 载荷（subagent_tool/subagent_text）
 * 挂到父会话对应工具卡。
 */
object SubagentCatalog {

    data class Spec(
        val type: String,
        val title: String,
        /** 允许使用的工具名；空集合 = 不限制 */
        val allowedTools: Set<String>,
        val maxTurns: Int,
        val promptAppendix: String,
    )

    val EXPLORE = Spec(
        type = "explore",
        title = "Explore",
        allowedTools = setOf("run_terminal", "file_read", "list_directory", "web_fetch"),
        maxTurns = 12,
        promptAppendix = "你是只读侦查代理：只收集事实与路径线索，禁止写入或安装；最终以紧凑要点报告结论。",
    )

    val PLAN = Spec(
        type = "plan",
        title = "Plan",
        allowedTools = setOf("run_terminal", "file_read", "list_directory", "web_fetch", "question"),
        maxTurns = 10,
        promptAppendix = "你是规划代理：基于现状产出可执行步骤计划（每步含验证方式），禁止实施改动。",
    )

    val WORKER = Spec(
        type = "worker",
        title = "Worker",
        allowedTools = emptySet(),
        maxTurns = 30,
        promptAppendix = "你是执行代理：直接完成任务并回报结果，重点说明改动内容与验证证据。",
    )

    fun of(type: String): Spec = when (type.lowercase()) {
        "explore" -> EXPLORE
        "plan" -> PLAN
        else -> WORKER
    }
}

/**
 * 子代理执行入口。miniLoop 由宿主（AgentRuntime）注入：按 spec 白名单构建
 * LlmClient + 裁剪版 ToolRegistry 独立小循环，返回最终报告文本；
 * onProgress 产出 progress JSON 载荷（FrameEmitter.toolCallProgress 消费）。
 */
class SubagentRunner(
    private val miniLoop: (
        spec: SubagentCatalog.Spec,
        task: String,
        onProgress: (progress: JSONObject) -> Unit,
    ) -> String,
) {
    fun spawn(
        agentType: String,
        task: String,
        parentToolCallId: String,
        onProgressFrame: (progress: JSONObject) -> Unit,
    ): String {
        val spec = SubagentCatalog.of(agentType)
        if (task.isBlank()) return "error: task is required"
        onProgressFrame(
            JSONObject()
                .put("kind", "subagent")
                .put("child_session", spec.type)
                .put("text", "${spec.title} 已启动"),
        )
        return try {
            miniLoop(spec, task.trim(), onProgressFrame).let(::clipReport)
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 报告裁剪上限（对齐 shiyi 子代理报告投递前的截断策略）。 */
    private fun clipReport(report: String): String =
        if (report.length <= MAX_REPORT_CHARS) report else report.take(MAX_REPORT_CHARS) + "\n...[report truncated]"

    companion object {
        const val MAX_REPORT_CHARS = 16_000
    }
}
