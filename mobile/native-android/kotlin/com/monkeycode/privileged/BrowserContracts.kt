package com.monkeycode.privileged

internal data class BrowserElementRef(
    val tabId: String,
    val generation: Long,
    val ref: String,
    val selector: String
)

internal class BrowserRefRegistry {
    private val entries = LinkedHashMap<String, BrowserElementRef>()

    fun replace(tabId: String, generation: Long, refs: Map<String, String>) {
        entries.entries.removeAll { it.value.tabId == tabId }
        refs.forEach { (ref, selector) ->
            require(REF_PATTERN.matches(ref)) { "非法元素引用: $ref" }
            entries[key(tabId, generation, ref)] = BrowserElementRef(tabId, generation, ref, selector)
        }
    }

    fun resolve(tabId: String, generation: Long, ref: String): BrowserElementRef =
        entries[key(tabId, generation, ref)]
            ?: throw IllegalArgumentException("元素引用 $ref 已失效，请重新获取 snapshot")

    fun removeTab(tabId: String) {
        entries.entries.removeAll { it.value.tabId == tabId }
    }

    fun clear() = entries.clear()

    private fun key(tabId: String, generation: Long, ref: String) = "$tabId\u0000$generation\u0000$ref"

    private companion object {
        val REF_PATTERN = Regex("e[1-9][0-9]*")
    }
}

internal object BrowserMcpContract {
    const val API_VERSION = "1.0"
    const val LATEST_PROTOCOL_VERSION = "2025-03-26"
    val SUPPORTED_PROTOCOL_VERSIONS = setOf(LATEST_PROTOCOL_VERSION, "2024-11-05")

    fun negotiateProtocol(requested: String): String {
        require(requested in SUPPORTED_PROTOCOL_VERSIONS) {
            "不支持 MCP 协议版本 $requested，支持: ${SUPPORTED_PROTOCOL_VERSIONS.joinToString()}"
        }
        return requested
    }

    fun parseBearer(value: String?): String? {
        if (value == null) return null
        val parts = value.trim().split(Regex("\\s+"), limit = 2)
        return parts.takeIf { it.size == 2 && it[0].equals("Bearer", ignoreCase = true) }
            ?.get(1)?.takeIf { it.isNotEmpty() }
    }
}

/** Pure JVM test entry point; Android instrumentation can invoke the same checks. */
internal object BrowserContractStructuredTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val refs = BrowserRefRegistry()
        refs.replace("tab-a", 1, linkedMapOf("e1" to "[data-monkeycode-ref=\"e1\"]"))
        check(refs.resolve("tab-a", 1, "e1").selector.contains("e1"))
        check(runCatching { refs.resolve("tab-a", 2, "e1") }.isFailure)
        refs.replace("tab-a", 2, mapOf("e1" to "#new"))
        check(runCatching { refs.resolve("tab-a", 1, "e1") }.isFailure)
        check(BrowserMcpContract.negotiateProtocol("2025-03-26") == "2025-03-26")
        check(runCatching { BrowserMcpContract.negotiateProtocol("invalid") }.isFailure)
        check(BrowserMcpContract.parseBearer("bearer token") == "token")
        check(BrowserMcpContract.parseBearer("Basic token") == null)
    }
}
