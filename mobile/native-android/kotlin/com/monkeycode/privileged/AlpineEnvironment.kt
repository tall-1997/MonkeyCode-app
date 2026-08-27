package com.monkeycode.privileged

import android.content.Context
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class LinuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

/**
 * 内置 Linux 工具环境（Alpine minirootfs）。
 *
 * 参考 OpenMinis / Operit / shiyi-agent 的方案：
 *  - 用 PRoot 做用户态 chroot（免 root），取代需要 root 的 mount namespace + chroot。
 *  - PRoot 二进制与 Alpine minirootfs 通过构建期脚本固化进 APK assets，
 *    (`native-android/assets/alpine/`)，避免运行时在线下载的不确定性。
 *  - 沙箱模式（无 root）也能跑完整 Linux 工具链；有 root 时仍可走原有特权通道。
 *
 * assets 布局（由 scripts/prepare_android_sandbox.sh 生成并打包进 APK）：
 *   assets/alpine/proot            PRoot 可执行文件（Android arm64）
 *   assets/alpine/minirootfs.tar.gz Alpine 3.21 minirootfs（固定版本，构建期校验 SHA-256）
 */
class AlpineEnvironment(private val context: Context) {
    companion object {
        // assets 内嵌资源名（构建期由 prepare_android_sandbox.sh 注入）
        private const val ASSET_PROOT = "alpine/proot"
        private const val ASSET_MINIROOTFS = "alpine/minirootfs.tar.gz"

        // 在线兜底（assets 缺失时）：固定版本 + 官方镜像
        private const val FALLBACK_ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"

        // 常用开发工具档案（与设计文档一致）
        val TOOL_PACKAGES = listOf(
            "git", "git-lfs", "openssh-client",
            "python3", "py3-pip", "py3-venv",
            "ripgrep", "fd",
            "curl", "wget", "rsync",
            "jq", "sqlite",
            "diffutils", "patch",
            "gzip", "bzip2", "xz", "zip", "unzip",
            "procps", "htop",
            "vim", "nano",
            "bash", "tmux"
        )
    }

    private val alpineDir: File
        get() = File(context.filesDir, "alpine")
    private val rootfsDir: File
        get() = File(alpineDir, "rootfs")
    private val prootBin: File
        get() = File(alpineDir, "proot")
    private val workspaceDir: File
        get() = File(context.filesDir, "workspace")

    fun isInstalled(): Boolean {
        return File(rootfsDir, "etc/alpine-release").exists() && prootBin.exists()
    }

    /**
     * 安装顺序：
     *  1. 从 assets 解出 PRoot + minirootfs（如果 assets 存在）
     *  2. assets 缺失 → 在线下载 fitm 兜底
     *  3. 解压 rootfs、可选预装工具
     */
    fun install(onProgress: (Float) -> Unit) {
        alpineDir.mkdirs()
        workspaceDir.mkdirs()

        // 1) PRoot
        var progress = 0f
        if (!prootBin.exists()) {
            val fromAssets = extractAsset(ASSET_PROOT, prootBin)
            if (!fromAssets) {
                throw IllegalStateException("PRoot 未随 APK 构建，请重新 build（scripts/prepare_android_sandbox.sh）")
            }
            prootBin.setExecutable(true)
        }
        onProgress(0.25f)

        // 2) rootfs tarball
        val tarball = File(alpineDir, "minirootfs.tar.gz")
        if (!tarball.exists()) {
            val fromAssets = extractAsset(ASSET_MINIROOTFS, tarball)
            if (!fromAssets) {
                onProgress(0.3f)
                downloadMinirootfs(tarball) { p -> onProgress(0.3f + p * 0.4f) }
                progress = 0.7f
            } else {
                progress = 0.7f
            }
        } else {
            progress = 0.7f
        }
        onProgress(progress)

        // 3) 解压
        if (File(rootfsDir, "etc/alpine-release").exists().not() || rootfsDir.listFiles()?.isEmpty() == true) {
            rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()
            extractTarball(tarball, rootfsDir)
        }
        onProgress(0.9f)

        // 4) 工作区挂载点
        File(rootfsDir, "workspace").mkdirs()
        File(rootfsDir, "sdcard").mkdirs()
        onProgress(1f)
    }

    /** 执行命令：PRoot 用户态 chroot → rootfs 内 /bin/sh。免 root。 */
    fun execCommand(command: String): LinuxCommandResult {
        if (!isInstalled()) throw IllegalStateException("Linux 环境未安装")

        // PRoot 用户态 chroot：无需 root；绑定 workspace 与 sdcard。
        // 参考 OpenMinis 做法：proot -0 -r <rootfs> -b /sdcard -b workspace ...
        val cmd = arrayOf(
            prootBin.absolutePath,
            "-0", // 伪装 root
            "-r", rootfsDir.absolutePath,
            "-b", "/proc:/proc",
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/sdcard:/sdcard",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-w", "/workspace",
            "/bin/sh", "-c", command
        )
        return runProcess(cmd)
    }

    /** 运行单条命令（非 shell 内嵌），用于安装工具档案等一次性任务。 */
    fun execOnce(runAsRoot: Boolean = true): LinuxCommandResult {
        throw UnsupportedOperationException("execOnce 已并入 execCommand")
    }

    /** 预装默认工具（可选，安装完成后调用）。 */
    fun installToolProfile(onProgress: (Float) -> Unit): LinuxCommandResult {
        if (!isInstalled()) throw IllegalStateException("Linux 环境未安装")
        val pkgs = TOOL_PACKAGES.joinToString(" ")
        val result = execCommand("apk update && apk add --no-cache $pkgs")
        onProgress(1f)
        return result
    }

    // ── 内部工具 ─────────────────────────────────────────────

    private fun runProcess(cmd: Array<String>): LinuxCommandResult {
        val process = try {
            Runtime.getRuntime().exec(cmd)
        } catch (e: IOException) {
            return LinuxCommandResult("", "启动进程失败: ${e.message}", -1)
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = try { process.waitFor() } catch (e: InterruptedException) { -1 }
        return LinuxCommandResult(stdout, stderr, exit)
    }

    private fun extractAsset(asset: String, dest: File): Boolean {
        return try {
            context.assets.open(asset).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadMinirootfs(tarball: File, onProgress: (Float) -> Unit) {
        val url = URL(FALLBACK_ROOTFS_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 300000
        val total = conn.contentLength
        FileOutputStream(tarball).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        conn.disconnect()
    }

    private fun extractTarball(tarball: File, dest: File) {
        val result = runProcess(arrayOf(
            prootBin.absolutePath, "-0", "-r", alpineDir.absolutePath,
            "/bin/tar", "-xzf", tarball.absolutePath, "-C", dest.absolutePath
        ))
        if (result.exitCode != 0) {
            // tar 不在 app 私有目录：改用 Android 自带 tar（su 或 shell 均可）
            val fallback = runProcess(arrayOf("sh", "-c", "tar -xzf '${tarball.absolutePath}' -C '${dest.absolutePath}'"))
            if (fallback.exitCode != 0) {
                throw IllegalStateException("解压失败: ${fallback.stderr}")
            }
        }
    }
}