package com.monkeycode.privileged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话持久化管理器 —— 参照桌面端 driver/session.rs 的 sidecar 模式。
 *
 * 会话目录: app_dir/sessions/<sid>/
 *   meta.json: { id, title, summary, status, engine_id, created_at, updated_at }
 *   events.jsonl: 每行一个 Frame JSON (journal)
 *   replay.jsonl: 折叠后的回放帧 (一行一轮)
 *
 * 方法:
 *   session_open: 创建会话，返回尾部回放窗口 { frames, cursor, has_more }
 *   session_resume: 根据 engine_id 恢复会话
 *   session_destroy: 删除会话目录
 *   session_history: 按 cursor 取历史帧
 *   session_outline: 全量提问目录
 *   session_frame: 按 seq 回读工具大字段
 *
 * 状态机: created → running → idle/finished/interrupted/error
 * 和解原则: 引擎崩溃时本地补 task-error + task-ended
 * 冷修复: kill -9 残留 running 状态修复
 */
class SessionManager(private val context: Context) {

    enum class SessionStatus {
        CREATED, RUNNING, IDLE, FINISHED, INTERRUPTED, ERROR;

        fun asString(): String = when (this) {
            CREATED -> "created"
            RUNNING -> "running"
            IDLE -> "idle"
            FINISHED -> "finished"
            INTERRUPTED -> "interrupted"
            ERROR -> "error"
        }

        companion object {
            fun fromString(s: String): SessionStatus = when (s) {
                "created" -> CREATED
                "running" -> RUNNING
                "idle" -> IDLE
                "finished" -> FINISHED
                "interrupted" -> INTERRUPTED
                "error" -> ERROR
                else -> CREATED
            }
        }
    }

    data class SessionMeta(
        val id: String,
        var title: String = "",
        var summary: String = "",
        var status: SessionStatus = SessionStatus.CREATED,
        var engineId: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class ReplayWindow(
        val frames: List<JSONObject>,
        val cursor: Long,
        val hasMore: Boolean
    )

    private val sessionsDir: File = File(context.filesDir, "sessions")
    private val sessions = ConcurrentHashMap<String, SessionMeta>()
    private val journalWriters = ConcurrentHashMap<String, Any>()

    init {
        sessionsDir.mkdirs()
        coldRepair()
    }

    fun sessionOpen(
        id: String,
        title: String = "",
        engineId: String = ""
    ): ReplayWindow {
        val meta = SessionMeta(
            id = id,
            title = title,
            engineId = engineId,
            status = SessionStatus.CREATED
        )
        sessions[id] = meta
        journalWriters[id] = Any()

        val sessionDir = File(sessionsDir, id)
        sessionDir.mkdirs()

        // 写入 meta.json
        writeMeta(meta)

        // 写入空 events.jsonl
        File(sessionDir, "events.jsonl").createNewFile()
        File(sessionDir, "replay.jsonl").createNewFile()

        return ReplayWindow(frames = emptyList(), cursor = 0, hasMore = false)
    }

    fun sessionResume(engineId: String): SessionMeta? {
        // 按 engineId 查找已有会话
        val existing = sessions.values.find { it.engineId == engineId }
        if (existing != null) {
            existing.status = SessionStatus.RUNNING
            existing.updatedAt = System.currentTimeMillis()
            writeMeta(existing)
            return existing
        }

        // 扫描磁盘
        sessionsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val json = JSONObject(metaFile.readText())
                    if (json.optString("engine_id", "") == engineId) {
                        val meta = SessionMeta(
                            id = json.getString("id"),
                            title = json.optString("title", ""),
                            summary = json.optString("summary", ""),
                            status = SessionStatus.fromString(json.optString("status", "created")),
                            engineId = json.optString("engine_id", ""),
                            createdAt = json.optLong("created_at", System.currentTimeMillis()),
                            updatedAt = json.optLong("updated_at", System.currentTimeMillis())
                        )
                        meta.status = SessionStatus.RUNNING
                        meta.updatedAt = System.currentTimeMillis()
                        sessions[meta.id] = meta
                        journalWriters[meta.id] = Any()
                        writeMeta(meta)
                        return meta
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    fun sessionDestroy(id: String) {
        sessions.remove(id)
        journalWriters.remove(id)
        val sessionDir = File(sessionsDir, id)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    fun sessionHistory(id: String, cursor: Long, limit: Int = 50): List<JSONObject> {
        val eventsFile = File(File(sessionsDir, id), "events.jsonl")
        if (!eventsFile.exists()) return emptyList()

        val frames = mutableListOf<JSONObject>()
        try {
            eventsFile.bufferedReader().use { reader ->
                var lineNum = 0L
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (lineNum >= cursor && frames.size < limit) {
                        try {
                            frames.add(JSONObject(line!!))
                        } catch (_: Exception) {
                        }
                    }
                    lineNum++
                    if (frames.size >= limit) break
                }
            }
        } catch (_: Exception) {
        }
        return frames
    }

    fun sessionOutline(id: String): List<JSONObject> {
        val eventsFile = File(File(sessionsDir, id), "events.jsonl")
        if (!eventsFile.exists()) return emptyList()

        val outline = mutableListOf<JSONObject>()
        try {
            eventsFile.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    try {
                        val frame = JSONObject(line!!)
                        if (frame.optString("type") == "user-input") {
                            outline.add(frame)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return outline
    }

    fun sessionFrame(id: String, seq: Long): JSONObject? {
        val eventsFile = File(File(sessionsDir, id), "events.jsonl")
        if (!eventsFile.exists()) return null

        try {
            eventsFile.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    try {
                        val frame = JSONObject(line!!)
                        if (frame.optLong("seq", -1) == seq) {
                            return frame
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun updateStatus(id: String, status: SessionStatus) {
        sessions[id]?.let { meta ->
            meta.status = status
            meta.updatedAt = System.currentTimeMillis()
            writeMeta(meta)
        }
    }

    fun updateTitle(id: String, title: String) {
        sessions[id]?.let { meta ->
            meta.title = title
            meta.updatedAt = System.currentTimeMillis()
            writeMeta(meta)
        }
    }

    fun updateSummary(id: String, summary: String) {
        sessions[id]?.let { meta ->
            meta.summary = summary
            meta.updatedAt = System.currentTimeMillis()
            writeMeta(meta)
        }
    }

    fun appendFrame(id: String, frame: JSONObject) {
        val lock = journalWriters[id] ?: return
        synchronized(lock) {
val eventsFile = File(File(sessionsDir, id), "events.jsonl")
            try {
                FileWriter(eventsFile, true).use { writer ->
                    writer.write(frame.toString())
                    writer.write("\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun appendReplayFrame(id: String, frame: JSONObject) {
        val lock = journalWriters[id] ?: return
        synchronized(lock) {
            val replayFile = File(File(sessionsDir, id), "replay.jsonl")
            try {
                FileWriter(replayFile, true).use { writer ->
                    writer.write(frame.toString())
                    writer.write("\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 引擎崩溃时的和解: 补 task-error + task-ended 帧
     */
    fun reconcile(id: String, errorMsg: String) {
        val meta = sessions[id] ?: return
        if (meta.status == SessionStatus.RUNNING) {
            meta.status = SessionStatus.ERROR
            meta.updatedAt = System.currentTimeMillis()
            writeMeta(meta)

            val seq = getEventCount(id) + 1
            appendFrame(id, JSONObject().apply {
                put("type", "task-error")
                put("data", JSONObject().apply {
                    put("error", errorMsg)
                    put("terminal", true)
                })
                put("timestamp", System.currentTimeMillis())
                put("seq", seq)
            })
            appendFrame(id, JSONObject().apply {
                put("type", "task-ended")
                put("timestamp", System.currentTimeMillis())
                put("seq", seq + 1)
            })
        }
    }

    /**
     * 冷修复: 将所有残留 running 状态修复为 interrupted
     */
    private fun coldRepair() {
        sessionsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val json = JSONObject(metaFile.readText())
                    if (json.optString("status", "") == "running") {
                        json.put("status", "interrupted")
                        json.put("updated_at", System.currentTimeMillis())

                        // 补一帧 task-error 说明原因
                        val eventsFile = File(dir, "events.jsonl")
                        val seq = if (eventsFile.exists()) {
                            eventsFile.readLines().size.toLong() + 1
                        } else 1L

                        FileWriter(eventsFile, true).use { writer ->
                            writer.write(JSONObject().apply {
                                put("type", "task-error")
                                put("data", JSONObject().apply {
                                    put("error", "上次运行未正常结束(应用被强制退出),已按中断收尾")
                                    put("terminal", true)
                                })
                                put("timestamp", System.currentTimeMillis())
                                put("seq", seq)
                            }.toString() + "\n")
                            writer.write(JSONObject().apply {
                                put("type", "task-ended")
                                put("timestamp", System.currentTimeMillis())
                                put("seq", seq + 1)
                            }.toString() + "\n")
                        }
                        metaFile.writeText(json.toString(2))
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun getEventCount(id: String): Long {
        val eventsFile = File(File(sessionsDir, id), "events.jsonl")
        if (!eventsFile.exists()) return 0
        return try {
            eventsFile.readLines().size.toLong()
        } catch (_: Exception) {
            0
        }
    }

    fun getMeta(id: String): SessionMeta? = sessions[id]

    fun listSessions(): List<SessionMeta> {
        val result = mutableListOf<SessionMeta>()
        sessionsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "meta.json")
            if (metaFile.exists()) {
                try {
                    val json = JSONObject(metaFile.readText())
                    result.add(SessionMeta(
                        id = json.getString("id"),
                        title = json.optString("title", ""),
                        summary = json.optString("summary", ""),
                        status = SessionStatus.fromString(json.optString("status", "created")),
                        engineId = json.optString("engine_id", ""),
                        createdAt = json.optLong("created_at", System.currentTimeMillis()),
                        updatedAt = json.optLong("updated_at", System.currentTimeMillis())
                    ))
                } catch (_: Exception) {
                }
            }
        }
        return result.sortedByDescending { it.updatedAt }
    }

    private fun writeMeta(meta: SessionMeta) {
        val metaFile = File(File(sessionsDir, meta.id), "meta.json")
        metaFile.parentFile?.mkdirs()
        try {
            metaFile.writeText(JSONObject().apply {
                put("id", meta.id)
                put("title", meta.title)
                put("summary", meta.summary)
                put("status", meta.status.asString())
                put("engine_id", meta.engineId)
                put("created_at", meta.createdAt)
                put("updated_at", meta.updatedAt)
            }.toString(2))
        } catch (_: Exception) {
        }
    }
}