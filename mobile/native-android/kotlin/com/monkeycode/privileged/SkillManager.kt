package com.monkeycode.privileged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 技能管理器 —— 参照 OpenMinis 的 SKILL.md 规范。
 *
 * 技能目录: assets/skills/ (内置) + app_dir/skills/ (用户)
 * SKILL.md 格式: frontmatter { name, description, paths } + Markdown 正文
 * 同名覆盖: 用户技能覆盖内置同名
 * 按会话物化: session/create 时扫描启用集，将技能内容注入 system prompt
 * 默认启用: 出厂规则 + 用户显式开关 (skills-defaults.json)
 */
class SkillManager(private val context: Context) {

    data class SkillDef(
        val name: String,
        val description: String,
        val paths: List<String> = emptyList(),
        val content: String,
        val source: String = "builtin" // builtin / user
    )

    private val builtinSkills = mutableListOf<SkillDef>()
    private val userSkills = mutableListOf<SkillDef>()
    private val defaultEnabled = mutableSetOf<String>()

    init {
        loadBuiltinSkills()
        loadUserSkills()
        loadDefaults()
    }

    private fun loadBuiltinSkills() {
        val builtinDirs = listOf(
            "feature-design",
            "project-wiki",
            "feature-implementer",
            "implementation-planner",
            "publish-website"
        )

        for (dir in builtinDirs) {
            try {
                val path = "skills/$dir/SKILL.md"
                val content = readAsset(path)
                if (content != null) {
                    val parsed = parseSkillMd(content)
                    if (parsed != null) {
                        builtinSkills.add(parsed.copy(source = "builtin"))
                    }
                }
            } catch (_: Exception) {
                // 内置技能加载失败，跳过
            }
        }
    }

    private fun loadUserSkills() {
        val skillsDir = File(context.filesDir, "skills")
        if (!skillsDir.exists()) return

        skillsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val skillMd = File(dir, "SKILL.md")
            if (skillMd.exists()) {
                try {
                    val content = skillMd.readText()
                    val parsed = parseSkillMd(content)
                    if (parsed != null) {
                        userSkills.add(parsed.copy(source = "user"))
                    }
                } catch (_: Exception) {
                    // 用户技能加载失败，跳过
                }
            }
        }
    }

    private fun loadDefaults() {
        // 出厂规则: 默认启用所有内置技能
        builtinSkills.forEach { defaultEnabled.add(it.name) }

        // 读取用户显式开关
        val defaultsFile = File(context.filesDir, "skills-defaults.json")
        if (defaultsFile.exists()) {
            try {
                val json = JSONObject(defaultsFile.readText())
                val enabled = json.optJSONArray("enabled")
                val disabled = json.optJSONArray("disabled")
                if (enabled != null) {
                    for (i in 0 until enabled.length()) {
                        defaultEnabled.add(enabled.getString(i))
                    }
                }
                if (disabled != null) {
                    for (i in 0 until disabled.length()) {
                        defaultEnabled.remove(disabled.getString(i))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun getEnabledSkills(): List<SkillDef> {
        val all = mutableListOf<SkillDef>()
        val seen = mutableSetOf<String>()

        // 用户技能优先（同名覆盖内置）
        for (skill in userSkills) {
            if (defaultEnabled.contains(skill.name)) {
                all.add(skill)
                seen.add(skill.name)
            }
        }
        for (skill in builtinSkills) {
            if (defaultEnabled.contains(skill.name) && !seen.contains(skill.name)) {
                all.add(skill)
            }
        }
        return all
    }

    fun getSkill(name: String): SkillDef? {
        userSkills.find { it.name == name }?.let { return it }
        return builtinSkills.find { it.name == name }
    }

    fun isEnabled(name: String): Boolean = defaultEnabled.contains(name)

    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) defaultEnabled.add(name)
        else defaultEnabled.remove(name)
        saveDefaults()
    }

    private fun saveDefaults() {
        val defaultsFile = File(context.filesDir, "skills-defaults.json")
        try {
            val json = JSONObject().apply {
                put("enabled", JSONArray(defaultEnabled.toList()))
            }
            defaultsFile.writeText(json.toString(2))
        } catch (_: Exception) {
        }
    }

    fun generateSkillSystemPrompt(requested: List<String> = emptyList()): String {
        val enabled = if (requested.isEmpty()) getEnabledSkills() else requested.mapNotNull(::getSkill)
        if (enabled.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n## 可用技能 (Skills)")
        sb.appendLine("你拥有以下技能，可以在适当的时机使用它们来完成任务。")
        sb.appendLine()

        for (skill in enabled) {
            sb.appendLine("### ${skill.name}")
            sb.appendLine("${skill.description}")
            sb.appendLine()
            sb.appendLine(skill.content)
            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun reloadUserSkills() {
        userSkills.clear()
        loadUserSkills()
    }

    fun reloadDefaults() {
        defaultEnabled.clear()
        loadDefaults()
    }

    private fun readAsset(path: String): String? {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    /** 解析 SKILL.md 格式: frontmatter { name, description, paths } + Markdown 正文 */
    private fun parseSkillMd(content: String): SkillDef? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return null

        val endIdx = trimmed.indexOf("}\n")
        if (endIdx < 0) return null

        val frontmatter = trimmed.substring(0, endIdx + 1)
        val body = trimmed.substring(endIdx + 2).trim()

        try {
            val fm = JSONObject(frontmatter)
            val name = fm.optString("name", "")
            val description = fm.optString("description", "")
            val paths = mutableListOf<String>()
            val pathsArr = fm.optJSONArray("paths")
            if (pathsArr != null) {
                for (i in 0 until pathsArr.length()) {
                    paths.add(pathsArr.getString(i))
                }
            }

            if (name.isEmpty()) return null
            return SkillDef(name, description, paths, body)
        } catch (_: Exception) {
            return null
        }
    }
}
