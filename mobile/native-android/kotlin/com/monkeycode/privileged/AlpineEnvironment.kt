package com.monkeycode.privileged

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
 *  - PRoot 二进制与 Alpine minirootfs 由构建期脚本固化进 APK assets
 *    （native-android/assets/alpine/），assets 缺失时在线兜底下载。
 *  - 沙箱模式（无 root）也能跑完整 Linux 工具链。
 */
class AlpineEnvironment(private val context: Context) {

    companion object {
        // assets 内嵌资源名（构建期由 prepare_android_sandbox.sh 注入）
        private const val ASSET_PROOT = "alpine/proot"
        private const val ASSET_ROOTFS = "alpine/minirootfs.tar.gz"

        // 在线兜底（assets 缺失时）
        private const val FALLBACK_ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
        private const val FALLBACK_PROOT_URL = "https://github.com/termux/proot/releases/download/v0.8.3/proot-v0.8.3-android-aarch64.tar.gz"

        // 常用开发工具档案
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

    private val alpineDir: File get() = File(context.filesDir, "alpine")
    private val rootfsDir: File get() = File(alpineDir, "rootfs")
    private val prootBin: File get() = File(alpineDir, "proot")
    private val libsDir: File get() = File(alpineDir, "libs")
    private val workspaceDir: File get() = File(context.filesDir, "workspace")

    fun isInstalled(): Boolean {
        return File(rootfsDir, "etc/alpine-release").exists() && prootBin.exists()
    }

    /**
     * 安装顺序：
     *  1. PRoot（assets 优先，在线兜底）
     *  2. 依赖动态库（proot 运行需要）
     *  3. rootfs tarball（assets 优先，在线兜底）
     *  4. 解压 rootfs（用系统 tar）
     *  5. 工作区挂载点
     */
    fun install(onProgress: (Float) -> Unit) {
        alpineDir.mkdirs()
        workspaceDir.mkdirs()

        // 1) PRoot
        var progress = 0f
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

        // 2) 依赖库（libtalloc / libandroid-shmem）
        if (libsDir.listFiles()?.isEmpty() != false) {
            extractAssetDir("alpine/libs", libsDir)
        }

        // 3) rootfs tarball
        val tarball = File(alpineDir, "minirootfs.tar.gz")
        if (!tarball.exists()) {
            val fromAssets = extractAsset(ASSET_ROOTFS, tarball)
            if (!fromAssets) {
                onProgress(0.35f)
                downloadMinirootfs(tarball, { p -> onProgress(0.35f + p * 0.4f) })
            }
        }
        onProgress(0.75f)

        // 4) 解压（系统 tar，不依赖 rootfs 内 tar）
        if (File(rootfsDir, "etc/alpine-release").exists().not() || rootfsDir.listFiles()?.isEmpty() == true) {
            rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()
            extractTarball(tarball, rootfsDir)
        }
        onProgress(0.9f)

        // 5) 工作区挂载点
        File(rootfsDir, "workspace").mkdirs()
        File(rootfsDir, "sdcard").mkdirs()
        onProgress(1f)
    }

    /** 执行命令：PRoot 用户态 chroot -> rootfs 内 /bin/sh。免 root。 */
    fun execCommand(command: String): LinuxCommandResult {
        if (!isInstalled()) throw IllegalStateException("Linux 环境未安装")
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
        // proot 动态链接 libtalloc/libandroid-shmem，需设置加载路径
        val env = arrayOf("LD_LIBRARY_PATH=${libsDir.absolutePath}")
        return runProcess(cmd, env)
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

    /** 解出 assets 子目录到本地（如 alpine/libs 下的 so 动态库）。 */
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
        val tmp = File(alpineDir, "proot-dl.tar.gz")
        downloadTo(FALLBACK_PROOT_URL, tmp, onProgress)
        try {
            val extract = runProcess(arrayOf("sh", "-c", "tar -xzf '${tmp.absolutePath}' -C '${alpineDir.absolutePath}'"))
            if (extract.exitCode != 0) return
            val found = searchExecutable(File(alpineDir, "proot"))
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

    private fun downloadMinirootfs(tarball: File, onProgress: (Float) -> Unit) {
        downloadTo(FALLBACK_ROOTFS_URL, tarball, onProgress)
    }

    private fun downloadTo(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 300000
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
        // 直接用系统 tar 解压（不依赖 proot / rootfs 内 tar，避免循环依赖）
        val result = runProcess(arrayOf("sh", "-c", "tar -xzf '${tarball.absolutePath}' -C '${dest.absolutePath}'"))
        if (result.exitCode != 0) {
            throw IllegalStateException("解压失败: ${result.stderr}")
        }
    }
}