package com.monkeycode.privileged

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ubuntu 24.04 ARM64 用户空间沙箱（PRoot）。
 *
 * 参照 Operit / AnLinux-App 的方案：
 *  - 使用 PRoot 做用户态 chroot（免 root），与 Alpine 共用同一 proot 二进制。
 *  - rootfs 从 Ubuntu Base 官方 CDImage 下载，assets 缺失时在线兜底。
 *  - 工作区 /workspace 挂载到 App 私有目录。
 *  - 预装 apt 开发工具链。
 *  - 沙箱模式（无 root）也能跑完整 Linux 工具链。
 */
class UbuntuEnvironment(private val context: Context) {

    companion object {
        const val SANDBOX_TYPE = "ubuntu"

        private const val ASSET_PROOT = "alpine/proot"
        private const val ASSET_ROOTFS = "ubuntu/rootfs.tar.gz"

        private const val FALLBACK_ROOTFS_URL = "http://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz"
        private const val FALLBACK_PROOT_URL = "https://github.com/termux/proot/releases/download/v0.8.3/proot-v0.8.3-android-aarch64.tar.gz"

        val TOOL_PACKAGES = listOf(
            "git", "python3", "python3-pip", "python3-venv",
            "nodejs", "npm",
            "ripgrep",
            "curl", "wget",
            "jq",
            "build-essential",
            "vim", "tmux"
        )
    }

    private val ubuntuDir: File get() = File(context.filesDir, "ubuntu")
    private val rootfsDir: File get() = File(ubuntuDir, "rootfs")
    private val prootBin: File get() = File(ubuntuDir, "proot")
    private val libsDir: File get() = File(ubuntuDir, "libs")
    private val workspaceDir: File get() = File(context.filesDir, "workspace")

    private var _installing = false
    val isInstalling: Boolean get() = _installing

    fun isInstalled(): Boolean {
        return File(rootfsDir, "etc/lsb-release").exists() && prootBin.exists()
    }

    /**
     * 安装顺序：
     *  1. PRoot（assets 优先，在线兜底）
     *  2. 依赖动态库（proot 运行需要 libtalloc / libandroid-shmem）
     *  3. rootfs tarball（assets 优先，在线兜底）
     *  4. 解压 rootfs（用系统 tar）
     *  5. 工作区挂载点
     */
    fun install(onProgress: (Float) -> Unit) {
        _installing = true
        try {
            ubuntuDir.mkdirs()
            workspaceDir.mkdirs()

            var progress = 0f

            // 1) PRoot
            if (!prootBin.exists()) {
                val fromAssets = extractAsset(ASSET_PROOT, prootBin)
                if (!fromAssets) {
                    progress = 0.05f
                    onProgress(progress)
                    downloadProot(prootBin, { p -> onProgress(progress + p * 0.2f) })
                    if (!prootBin.exists() || prootBin.length() < 1024) {
                        throw IllegalStateException("PRoot 获取失败，请检查网络或重新安装应用")
                    }
                }
                prootBin.setExecutable(true)
            }
            onProgress(0.3f)

            // 2) 依赖库
            if (libsDir.listFiles()?.isEmpty() != false) {
                extractAssetDir("alpine/libs", libsDir)
            }

            // 3) rootfs tarball
            val tarball = File(ubuntuDir, "rootfs.tar.gz")
            if (!tarball.exists()) {
                val fromAssets = extractAsset(ASSET_ROOTFS, tarball)
                if (!fromAssets) {
                    onProgress(0.35f)
                    downloadRootfs(tarball, { p -> onProgress(0.35f + p * 0.4f) })
                }
            }
            onProgress(0.75f)

            // 4) 解压
            if (File(rootfsDir, "etc/lsb-release").exists().not() || rootfsDir.listFiles()?.isEmpty() == true) {
                rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                extractTarball(tarball, rootfsDir)
            }
            onProgress(0.9f)

            // 5) 工作区挂载点
            File(rootfsDir, "workspace").mkdirs()
            File(rootfsDir, "sdcard").mkdirs()
            onProgress(1f)
        } finally {
            _installing = false
        }
    }

    /** 执行命令：PRoot 用户态 chroot -> rootfs 内 /bin/bash。免 root。 */
    fun execCommand(command: String): LinuxCommandResult {
        if (!isInstalled()) throw IllegalStateException("Ubuntu 环境未安装")
        val cmd = arrayOf(
            prootBin.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/proc:/proc",
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/sdcard:/sdcard",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-w", "/workspace",
            "/bin/bash", "-c", command
        )
        val env = arrayOf("LD_LIBRARY_PATH=${libsDir.absolutePath}")
        return runProcess(cmd, env)
    }

    /** 预装默认 apt 工具（可选，安装完成后调用）。 */
    fun installToolProfile(onProgress: (Float) -> Unit): LinuxCommandResult {
        if (!isInstalled()) throw IllegalStateException("Ubuntu 环境未安装")
        val pkgs = TOOL_PACKAGES.joinToString(" ")
        val result = execCommand("apt-get update && apt-get install -y --no-install-recommends $pkgs")
        onProgress(1f)
        return result
    }

    // ── 内部工具 ─────────────────────────────────────────────

    private fun runProcess(cmd: Array<String>, env: Array<String> = emptyArray()): LinuxCommandResult {
        val process = try {
            Runtime.getRuntime().exec(cmd, env)
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

    private fun extractAssetDir(assetPath: String, destDir: File): Boolean {
        return try {
            val names = context.assets.list(assetPath) ?: return false
            destDir.mkdirs()
            for (name in names) {
                val src = "$assetPath/$name"
                val dest = File(destDir, name)
                context.assets.open(src).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadProot(dest: File, onProgress: (Float) -> Unit) {
        val tmp = File(ubuntuDir, "proot-dl.tar.gz")
        downloadTo(FALLBACK_PROOT_URL, tmp, onProgress)
        try {
            val extract = runProcess(arrayOf("sh", "-c", "tar -xzf '${tmp.absolutePath}' -C '${ubuntuDir.absolutePath}'"))
            if (extract.exitCode != 0) return
            val found = searchExecutable(File(ubuntuDir, "proot"))
            if (found != null) {
                found.copyTo(dest, overwrite = true)
                dest.setExecutable(true)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun searchExecutable(dir: File): File? {
        val queue = ArrayDeque<File>()
        queue.add(dir)
        var depth = 0
        while (queue.isNotEmpty() && depth < 6) {
            depth++
            val next = ArrayDeque<File>()
            for (f in queue) {
                val files = f.listFiles() ?: continue
                for (file in files) {
                    if (file.isDirectory) next.add(file)
                    else if (file.name == "proot" && file.canExecute()) return file
                }
            }
            queue.clear()
            queue.addAll(next)
        }
        return null
    }

    private fun downloadRootfs(tarball: File, onProgress: (Float) -> Unit) {
        downloadTo(FALLBACK_ROOTFS_URL, tarball, onProgress)
    }

    private fun downloadTo(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 600000
        val total = conn.contentLength
        FileOutputStream(dest).use { out ->
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
        val result = runProcess(arrayOf("sh", "-c", "tar -xzf '${tarball.absolutePath}' -C '${dest.absolutePath}'"))
        if (result.exitCode != 0) {
            throw IllegalStateException("解压失败: ${result.stderr}")
        }
    }
}