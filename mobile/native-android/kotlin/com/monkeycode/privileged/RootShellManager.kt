package com.monkeycode.privileged

import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

class RootShellManager {
    private val sessions = ConcurrentHashMap<String, ShellSession>()
    private val asyncJobs = ConcurrentHashMap<String, AsyncJob>()
    private val jobIdCounter = AtomicInteger(0)

    inner class ShellSession(
        val id: String,
        val identity: String,
        val workDir: String,
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val errorReader: BufferedReader
    ) {
        var isAlive: Boolean = true
        val cwd: String get() = workDir
    }

    inner class AsyncJob(
        val id: String,
        val process: Process,
        val identity: String
    ) {
        var isRunning: Boolean = true
        val pid: Int get() = getProcessPid(process)
    }

    fun execSync(command: String, identity: String = "user"): ShellResult {
        val shellCommand = buildShellCommand(command, identity)
        val process = Runtime.getRuntime().exec(shellCommand)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return ShellResult(stdout, stderr, exitCode)
    }

    fun createSession(workDir: String, identity: String = "user"): String {
        val sessionId = "sh_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        val shellCommand = when (identity) {
            "root" -> {
                val busybox = detectBusyBox()
                arrayOf("su", "-c", "cd '$workDir' && exec $busybox")
            }
            else -> arrayOf("sh", "-c", "cd '$workDir' && exec sh")
        }

        val process = Runtime.getRuntime().exec(shellCommand)
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))

        val session = ShellSession(sessionId, identity, workDir, process, writer, reader, errorReader)
        sessions[sessionId] = session

        Thread {
            try {
                val buf = CharArray(4096)
                while (session.isAlive) {
                    val len = reader.read(buf)
                    if (len == -1) break
                    val data = String(buf, 0, len)
                    // 通过事件回调输出 - 由 PrivilegedExecutionModule 处理
                    onSessionData?.invoke(sessionId, data)
                }
            } catch (_: IOException) {
            } finally {
                session.isAlive = false
                onSessionExit?.invoke(sessionId, process.exitValue())
                sessions.remove(sessionId)
            }
        }.start()

        return sessionId
    }

    fun writeToSession(sessionId: String, data: String) {
        val session = sessions[sessionId] ?: throw IllegalStateException("Session not found: $sessionId")
        if (!session.isAlive) throw IllegalStateException("Session is dead: $sessionId")
        session.writer.write(data)
        session.writer.newLine()
        session.writer.flush()
    }

    fun destroySession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.isAlive = false
        try {
            // 通过进程组终止
            val pid = getProcessPid(session.process)
            Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 -$pid"))
        } catch (_: Exception) {
            session.process.destroyForcibly()
        }
    }

    fun execAsync(command: String, identity: String = "user"): String {
        val jobId = "async_${jobIdCounter.incrementAndGet()}"
        val shellCommand = buildShellCommand(command, identity)
        val process = Runtime.getRuntime().exec(shellCommand)
        val job = AsyncJob(jobId, process, identity)
        asyncJobs[jobId] = job
        return jobId
    }

    fun cancelAsyncJob(jobId: String) {
        val job = asyncJobs.remove(jobId) ?: return
        job.isRunning = false
        try {
            val pid = job.pid
            Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 -$pid"))
        } catch (_: Exception) {
            job.process.destroyForcibly()
        }
    }

    fun readAsyncOutput(jobId: String): String? {
        val job = asyncJobs[jobId] ?: return null
        return try {
            job.process.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildShellCommand(command: String, identity: String): Array<String> {
        return when (identity) {
            "root" -> {
                val busybox = detectBusyBox()
                arrayOf("su", "-c", "exec $busybox -c '$command'")
            }
            else -> arrayOf("sh", "-c", command)
        }
    }

    private fun detectBusyBox(): String {
        val candidates = listOf(
            "/data/adb/magisk/busybox",
            "/data/adb/ksu/bin/busybox",
            "/data/adb/ap/bin/busybox",
            "/system/xbin/busybox",
            "/system/bin/busybox"
        )
        for (path in candidates) {
            if (File(path).exists()) return "$path ash"
        }
        return "sh"
    }

    private fun getProcessPid(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }

    // 回调接口
    var onSessionData: ((String, String) -> Unit)? = null
    var onSessionExit: ((String, Int) -> Unit)? = null
}