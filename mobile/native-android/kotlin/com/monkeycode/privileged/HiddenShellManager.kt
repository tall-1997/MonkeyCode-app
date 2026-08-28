package com.monkeycode.privileged

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 隐藏执行结果状态（对齐 OmniBot EmbeddedTerminalRuntime.HiddenExecResult 词汇） */
data class HiddenExecResult(
    val output: String,
    val exitCode: Int,
    val state: State,
    val error: String? = null,
    val rawOutputPreview: String? = null
) {
    enum class State {
        OK, TIMEOUT, PROCESS_EXITED, SHELL_START_FAILED,
        MISSING_BEGIN_MARKER, MISSING_END_MARKER, INVALID_EXIT_CODE, EXECUTION_ERROR
    }

    val isOk: Boolean get() = state == State.OK
}

/**
 * 常驻隐藏 shell + marker 信封执行器。
 *
 * 复用方案来自 OperitTerminalCore LocalTerminalProvider.executeHiddenCommand：
 *  - 每个 executorKey 一个常驻隐藏 shell（proot 内 bash --noprofile --norc）
 *  - 每条命令包装为 BEGIN/PID/END marker 信封；heredoc 写临时脚本，setsid 后台运行，
 *    wait 收集退出码；PID marker 用于超时杀进程组
 *  - 同 key 串行（Mutex），不同 key 并行；超时关闭该 key 的 shell 防串流
 */
class HiddenShellManager(
    private val buildShellCommand: () -> Array<String>,
    private val buildEnv: (ProcessBuilder) -> Unit
) {

    companion object {
        private const val BEGIN_MARKER_PREFIX = "__MC_HIDDEN_BEGIN__:"
        private const val END_MARKER_PREFIX = "__MC_HIDDEN_END__:"
        private const val PID_MARKER_PREFIX = "__MC_HIDDEN_PID__:"
        private const val READY_PROMPT_PREFIX = "__MC_SHELL_READY__"
        private const val IDLE_RECYCLE_MS = 10 * 60 * 1000L
    }

    /** 单个常驻隐藏 shell 实例 */
    private class ShellHandle(
        val process: Process,
        val writer: java.io.BufferedWriter,
        val outputChannel: Channel<String>,
        @Volatile var lastUsedAt: Long = System.currentTimeMillis()
    )

    private val shells = ConcurrentHashMap<String, ShellHandle>()
    private val perKeyMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * 执行隐藏命令。
     * @param command 在常驻 shell 内执行的命令文本（可多行）
     * @param executorKey 会话键：同 key 串行复用同一 shell，不同 key 各自独立 shell
     * @param timeoutMs 总超时（毫秒）
     * @param onOutputChunk 可选实时输出回调（行级 chunk，供 UI 流式展示）
     */
    fun executeCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long,
        onOutputChunk: ((String) -> Unit)? = null
    ): HiddenExecResult {
        val mutex = perKeyMutexes.computeIfAbsent(executorKey) { Mutex() }

        // 获取或启动 shell（锁外做，失败不影响其他 key）
        val shell = try {
            runBlocking { ensureShell(executorKey) }
        } catch (e: Exception) {
            return HiddenExecResult(
                output = "", exitCode = -1,
                state = HiddenExecResult.State.SHELL_START_FAILED,
                error = e.message ?: "Failed to start hidden shell"
            )
        }

        return try {
            runBlocking {
                withTimeout(timeoutMs) {
                    mutex.withLock {
                        shell.lastUsedAt = System.currentTimeMillis()
                        val token = UUID.randomUUID().toString()
                        val wrapped = buildEnvelope(command, token)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            shell.writer.write(wrapped)
                            shell.writer.flush()
                        }
                        collectResult(shell, token, timeoutMs, onOutputChunk)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            recycle(executorKey)
            HiddenExecResult(
                output = "", exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Hidden exec command timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            recycle(executorKey)
            HiddenExecResult(
                output = "", exitCode = -1,
                state = HiddenExecResult.State.EXECUTION_ERROR,
                error = e.message ?: "Hidden exec failed"
            )
        }
    }

    fun recycleIdle(now: Long = System.currentTimeMillis()) {
        shells.entries.removeIf { (_, shell) ->
            val idle = now - shell.lastUsedAt > IDLE_RECYCLE_MS
            if (idle && !shell.process.isAlive) {
                closeQuietly(shell); true
            } else if (idle && now - shell.lastUsedAt > IDLE_RECYCLE_MS * 2) {
                closeQuietly(shell); true
            } else {
                false
            }
        }
    }

    fun recycle(executorKey: String) {
        shells.remove(executorKey)?.let { closeQuietly(it) }
    }

    fun recycleAll() {
        shells.keys.toList().forEach { recycle(it) }
    }

    // ── 内部 ─────────────────────────────────────────────

    private suspend fun ensureShell(executorKey: String): ShellHandle =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            shells[executorKey]?.takeIf { it.process.isAlive } ?: run {
                shells.remove(executorKey)?.let { closeQuietly(it) }
                createShell(executorKey)
            }
        }

    private suspend fun createShell(executorKey: String): ShellHandle =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pb = ProcessBuilder(*buildShellCommand())
            pb.redirectErrorStream(true)
            buildEnv(pb)
            val process = pb.start()

            val channel = Channel<String>(Channel.UNLIMITED)
            val readerJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> channel.trySend("$line\n") }
                }
                channel.close()
            }

            val handle = ShellHandle(
                process = process,
                writer = process.outputStream.bufferedWriter(),
                outputChannel = channel,
            )

            // 启动探测：独立写一行 printf，等 ready 标记回来再交付业务。
            // proot 启动可能数秒；进程死掉立即失败。
            handle.writer.write("printf '%s\\n' '$READY_PROMPT_PREFIX'\n")
            handle.writer.flush()

            val deadline = System.currentTimeMillis() + 30_000L
            var readySeen = false
            while (System.currentTimeMillis() < deadline) {
                val chunk = kotlinx.coroutines.withTimeoutOrNull(500) {
                    channel.receiveCatching().getOrNull()
                } ?: continue
                if (chunk.contains(READY_PROMPT_PREFIX)) { readySeen = true; break }
                channel.trySend(chunk)
                if (!process.isAlive) break
            }

            if (!readySeen || !process.isAlive) {
                readerJob.cancel()
                runCatching { process.destroy() }
                throw IOException("Hidden shell failed to become ready (key=$executorKey)")
            }

            shells[executorKey] = handle
            handle
        }

    /**
     * 构造 marker 信封——结构与 OperitTerminalCore buildHiddenExecEnvelope 一致：
     * BEGIN → heredoc 写脚本 → setsid 后台 + PID marker → wait → 清理 → END:rc
     */
    private fun buildEnvelope(command: String, token: String): String {
        val normalized = command.replace("\r\n", "\n").replace("\r", "\n")
        val tokenSuffix = token.replace("-", "")
        val heredocTag = "__MC_HIDDEN_CMD_${tokenSuffix}__"
        return buildString {
            append("printf '%s\\n' '$BEGIN_MARKER_PREFIX$token'\n")
            append("__mc_hidden_script=\"\${TMPDIR:-/tmp}/mc_hidden_$tokenSuffix.sh\"\n")
            append("cat >\"\$__mc_hidden_script\" <<'$heredocTag'\n")
            append(normalized)
            if (!normalized.endsWith("\n")) append('\n')
            append("$heredocTag\n")
            append("setsid /bin/sh \"\$__mc_hidden_script\" </dev/null &\n")
            append("__mc_hidden_pid=\$!\n")
            append("printf '%s:%s\\n' '$PID_MARKER_PREFIX$token' \"\$__mc_hidden_pid\"\n")
            append("wait \"\$__mc_hidden_pid\"\n")
            append("__mc_hidden_rc=\$?\n")
            append("rm -f \"\$__mc_hidden_script\"\n")
            append("printf '%s:%s\\n' '$END_MARKER_PREFIX$token' \"\$__mc_hidden_rc\"\n")
        }
    }

    private suspend fun collectResult(
        shell: ShellHandle,
        token: String,
        timeoutMs: Long,
        onOutputChunk: ((String) -> Unit)?
    ): HiddenExecResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val deadline = System.currentTimeMillis() + timeoutMs
        val builder = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val chunk = kotlinx.coroutines.withTimeoutOrNull(remaining) {
                shell.outputChannel.receiveCatching().getOrNull()
            } ?: break

            builder.append(chunk)
            onOutputChunk?.invoke(chunk)

            // 收集以 END marker 为准；marker 自身在解析时剔除
            if (builder.indexOf(endMarkerPrefix) >= 0) break
        }

        val raw = builder.toString()
        parse(raw, token).also { result ->
            if (!result.isOk && shell.process.isAlive) {
                cancelByPid(raw, token)
            }
        }
    }

    private fun parse(raw: String, token: String): HiddenExecResult {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"

        val endIndex = raw.indexOf(endMarkerPrefix)
        if (endIndex < 0) {
            val preview = raw.takeLast(1200)
            val state =
                if (raw.indexOf(beginMarker) < 0) HiddenExecResult.State.MISSING_BEGIN_MARKER
                else HiddenExecResult.State.MISSING_END_MARKER
            return HiddenExecResult(
                output = extractPayload(raw, token), exitCode = -1,
                state = state, error = "Hidden exec output in state $state",
                rawOutputPreview = preview
            )
        }

        val payload = extractPayload(raw, token)

        val exitText = raw.substring(endIndex + endMarkerPrefix.length)
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: return HiddenExecResult(
                output = payload, exitCode = -1,
                state = HiddenExecResult.State.INVALID_EXIT_CODE,
                error = "Missing exit code line",
                rawOutputPreview = raw.takeLast(1200)
            )

        val exitCode = exitText.toIntOrNull()
            ?: return HiddenExecResult(
                output = payload, exitCode = -1,
                state = HiddenExecResult.State.INVALID_EXIT_CODE,
                error = "Invalid exit code: $exitText",
                rawOutputPreview = raw.takeLast(1200)
            )

        return HiddenExecResult(output = payload, exitCode = exitCode, state = HiddenExecResult.State.OK)
    }

    private fun extractPayload(raw: String, token: String): String {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val beginIndex = raw.indexOf(beginMarker)
        if (beginIndex < 0) return ""
        val payloadStart = beginIndex + beginMarker.length
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val endIndex = raw.indexOf(endMarkerPrefix, payloadStart).let { if (it >= 0) it else raw.length }
        return raw.substring(payloadStart, endIndex)
            .lineSequence()
            .filterNot { it.startsWith("$PID_MARKER_PREFIX$token:") }
            .joinToString("\n")
            .trimStart('\n', '\r')
            .trimEnd('\n', '\r')
    }

    /** 超时收尾：从输出里捞 PID marker，SIGTERM→SIGKILL 杀整个进程组 */
    private fun cancelByPid(raw: String, token: String) {
        val pid = extractPid(raw, token) ?: return
        try {
            killGroup(pid, OsConstants.SIGTERM)
            Thread.sleep(100)
            killGroup(pid, OsConstants.SIGKILL)
        } catch (_: Throwable) {
        }
    }

    private fun extractPid(raw: String, token: String): Long? {
        val prefix = "$PID_MARKER_PREFIX$token:"
        val idx = raw.indexOf(prefix)
        if (idx < 0) return null
        val text = raw.substring(idx + prefix.length)
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return text?.toLongOrNull()
    }

    private fun killGroup(pid: Long, signal: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Os.kill((-pid).toInt(), signal)
        }
    }

    private fun closeQuietly(shell: ShellHandle) {
        runCatching { shell.writer.close() }
        runCatching { shell.outputChannel.close() }
        runCatching { shell.process.destroy() }
        runCatching { shell.process.destroyForcibly() }
    }
}
