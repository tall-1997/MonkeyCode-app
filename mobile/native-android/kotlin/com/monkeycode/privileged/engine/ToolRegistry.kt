package com.monkeycode.privileged.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地引擎工具注册表：词汇裁剪自参考仓 shiyi 16 工具表
 * （lib/core/app_state.dart），本地能力经由 ToolDeps 回调接入，
 * 由宿主（AgentRuntime/PrivilegedExecutionModule）组装真实实现。
 */
class ToolRegistry(private val deps: ToolDeps) {

    class ToolResult(val output: String, val images: List<String> = emptyList()) {
        fun toJson(): JSONObject = JSONObject()
            .put("output", output)
            .put("images", JSONArray(images))
    }

    private class ToolDef(
        val name: String,
        val title: String,
        val description: String,
        val parameters: JSONObject,
        val executor: (JSONObject) -> ToolResult,
    )

    private val defs = LinkedHashMap<String, ToolDef>()

    /** 白名单裁剪视图（shiyi 子代理模式）：null=不限制；execute 同步生效。 */
    @Volatile
    private var whitelist: Set<String>? = null

    fun restrictTo(allowed: Set<String>) {
        whitelist = if (allowed.isEmpty()) null else allowed
    }

    fun register(name: String, title: String, description: String, parameters: JSONObject, executor: (JSONObject) -> ToolResult) {
        defs[name] = ToolDef(name, title, description, parameters, executor)
    }

    fun names(): List<String> = defs.keys.toList()

    /** OpenAI function 工具数组；allowed 为空集合时不限制。 */
    fun catalogJson(allowed: Set<String> = emptySet()): JSONArray {
        val filter = allowed.ifEmpty { whitelist ?: emptySet() }
        val arr = JSONArray()
        for (d in defs.values) {
            if (filter.isNotEmpty() && d.name !in filter) continue
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", d.name)
                    put("description", d.description)
                    put("parameters", d.parameters)
                })
            })
        }
        return arr
    }

    fun titleOf(name: String): String = defs[name]?.title ?: name

    fun execute(name: String, argsJson: String): ToolResult {
        val def = defs[name] ?: return ToolResult("error: unknown tool $name")
        whitelist?.let { wl ->
            if (name !in wl) return ToolResult("blocked: '$name' 在当前代理白名单之外")
        }
        val params = runCatching {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        }.getOrElse { return ToolResult("error: invalid tool arguments (expect JSON object)") }
        return runCatching { def.executor(params) }
            .getOrElse { e -> ToolResult("error: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ── 参数 schema 小助手 ───────────────────────────────────

    private fun strParam(desc: String, required: Boolean = false): JSONObject =
        JSONObject().put("type", "string").put("description", desc).put("_required", required)

    private fun numParam(desc: String): JSONObject =
        JSONObject().put("type", "number").put("description", desc)

    private fun boolParam(desc: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", desc)

    private fun objParams(defsMap: Map<String, JSONObject>): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for ((k, v) in defsMap) {
            props.put(k, v)
            if (v.optBoolean("_required")) req.put(k)
        }
        for (k in props.keys()) props.getJSONObject(k).remove("_required")
        return JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (req.length() > 0) put("required", req)
        }
    }

    // ── 默认工具集 ───────────────────────────────────────────

    /** 注册移动端本地默认工具集（对应旧 buildToolCatalog 的超集+新增）。 */
    fun registerDefaults() {
        register(
            "run_terminal", "Run Command",
            "执行 shell 命令（Linux 环境/沙箱），返回 stdout/stderr 与退出码",
            objParams(mapOf(
                "command" to strParam("Shell 命令", required = true),
                "timeout_ms" to numParam("超时毫秒数（默认 600000）"),
            )),
        ) { p ->
            val cmd = p.optString("command", "")
            if (cmd.isBlank()) return@register ToolResult("error: command is required")
            ToolResult(deps.terminal(cmd, p.optLong("timeout_ms", 600_000L)))
        }

        register(
            "file_read", "Read File",
            "读取文件内容",
            objParams(mapOf("path" to strParam("文件绝对路径", required = true))),
        ) { p -> ToolResult(deps.readFile(p.optString("path", ""))) }

        register(
            "file_write", "Write File",
            "写入或覆盖文件",
            objParams(mapOf(
                "path" to strParam("文件绝对路径", required = true),
                "content" to strParam("文件内容", required = true),
            )),
        ) { p -> ToolResult(deps.writeFile(p.optString("path", ""), p.optString("content", ""))) }

        register(
            "list_directory", "List Directory",
            "列出目录内容",
            objParams(mapOf("path" to strParam("目录路径（可选）"))),
        ) { p -> ToolResult(deps.listDir(p.optString("path", ""))) }

        register(
            "install_package", "Install Package",
            "在 Linux 环境中安装包（apk add）",
            objParams(mapOf("package" to strParam("包名（如 nodejs/npm/git/python3）", required = true))),
        ) { p -> ToolResult(deps.installPackage(p.optString("package", ""))) }

        register(
            "web_fetch", "Fetch URL",
            "请求 HTTP(S) 地址并返回响应文本（限 GET）",
            objParams(mapOf(
                "url" to strParam("完整 http(s) 地址", required = true),
                "max_bytes" to numParam("返回内容上限字节数（默认 65536）"),
            )),
        ) { p -> ToolResult(deps.webFetch(p.optString("url", ""), p.optLong("max_bytes", 65_536L))) }

        register(
            "question", "Ask User Question",
            "向用户提问并等待回答（提问卡）",
            objParams(mapOf(
                "questions" to strParam("问题列表 JSON 数组", required = true),
            )),
        ) { p -> ToolResult(deps.askUser(p.optString("questions", p.toString()))) }

        register(
            "spawn_agent", "Spawn Sub-Agent",
            "派生子代理执行独立任务（explore/plan/worker）并返回其报告",
            objParams(mapOf(
                "agent_type" to strParam("子代理类型：explore|plan|worker", required = true),
                "task" to strParam("任务描述", required = true),
            )),
        ) { p ->
            ToolResult(deps.spawnAgent(p.optString("agent_type", "worker"), p.optString("task", "")))
        }

        register(
            "screenshot", "Screenshot", "截取当前屏幕", objParams(emptyMap()),
        ) { _ -> ToolResult(deps.screenshot()) }

        register(
            "gui_click", "Click Screen", "在屏幕坐标点击",
            objParams(mapOf("x" to numParam("x"), "y" to numParam("y"))),
        ) { p -> ToolResult(deps.guiClick(p.optInt("x"), p.optInt("y"))) }

        register(
            "gui_type", "Type Text", "向当前输入框输入文本",
            objParams(mapOf("text" to strParam("要输入的文本", required = true))),
        ) { p -> ToolResult(deps.guiType(p.optString("text", ""))) }

        register(
            "get_accessibility_tree", "Accessibility Tree", "获取当前界面无障碍节点树",
            objParams(emptyMap()),
        ) { _ -> ToolResult(deps.accessibilityTree()) }
    }
}

/** 宿主侧工具实现回调集合；默认全部返回未配置错误，组装时按需覆盖。 */
data class ToolDeps(
    val terminal: (command: String, timeoutMs: Long) -> String = { _, _ -> err("run_terminal") },
    val readFile: (path: String) -> String = { _ -> err("file_read") },
    val writeFile: (path: String, content: String) -> String = { _, _ -> err("file_write") },
    val listDir: (path: String) -> String = { _ -> err("list_directory") },
    val installPackage: (pkg: String) -> String = { _ -> err("install_package") },
    val webFetch: (url: String, maxBytes: Long) -> String = { _, _ -> err("web_fetch") },
    val askUser: (questionsJson: String) -> String = { _ -> err("question") },
    val spawnAgent: (agentType: String, task: String) -> String = { _, _ -> err("spawn_agent") },
    val screenshot: () -> String = { err("screenshot") },
    val guiClick: (x: Int, y: Int) -> String = { _, _ -> err("gui_click") },
    val guiType: (text: String) -> String = { _ -> err("gui_type") },
    val accessibilityTree: () -> String = { err("get_accessibility_tree") },
) {
    companion object {
        private fun err(tool: String): String = "error: tool '$tool' is not wired to host"
    }
}
