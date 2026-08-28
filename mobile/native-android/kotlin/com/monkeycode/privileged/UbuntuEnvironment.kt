package com.monkeycode.privileged

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    @Volatile var isActive: Boolean = false

    companion object {
        const val SANDBOX_TYPE = "ubuntu"

        private const val ASSET_PROOT = "alpine/proot"
        private const val ASSET_ROOTFS = "ubuntu/rootfs.tar.gz"

        private const val ROOTFS_FILE = "ubuntu-base-24.04.4-base-arm64.tar.gz"
        private const val ROOTFS_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
        private const val FALLBACK_PROOT_URL = "https://github.com/termux/proot/releases/download/v0.8.3/proot-v0.8.3-android-aarch64.tar.gz"

        data class Mirror(val id: String, val name: String, val baseUrl: String) {
            val url: String get() = "$baseUrl/$ROOTFS_FILE"
        }

        val ROOTFS_MIRRORS = listOf(
            Mirror("official", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"),
            Mirror("tuna", "清华大学 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"),
            Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release")
        )

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
    private val installMarker: File get() = File(ubuntuDir, ".installed-$ROOTFS_FILE")
    private val preferences = context.getSharedPreferences("privileged_execution", 0)

    private var _installing = false
    val isInstalling: Boolean get() = _installing

    fun isInstalled(): Boolean {
        val systemReady = File(rootfsDir, "etc/os-release").exists() && prootBin.exists()
        if (systemReady && !installMarker.exists() && File(rootfsDir, "etc/lsb-release").exists()) {
            installMarker.writeText("legacy")
        }
        return systemReady && installMarker.exists()
    }

    fun mirrors(): List<Mirror> = ROOTFS_MIRRORS

    fun selectedMirror(): Mirror {
        val id = preferences.getString("ubuntu_mirror", ROOTFS_MIRRORS.first().id)
        return ROOTFS_MIRRORS.find { it.id == id } ?: ROOTFS_MIRRORS.first()
    }

    fun selectMirror(id: String): Mirror {
        val mirror = ROOTFS_MIRRORS.find { it.id == id }
            ?: throw IllegalArgumentException("无效的 Ubuntu 镜像源")
        preferences.edit().putString("ubuntu_mirror", mirror.id).apply()
        return mirror
    }

    /**
     * 安装顺序：
     *  1. PRoot（assets 优先，在线兜底）
     *  2. 依赖动态库（proot 运行需要 libtalloc / libandroid-shmem）
     *  3. rootfs tarball（assets 优先，在线兜底）
     *  4. 解压 rootfs（用系统 tar）
     *  5. 工作区挂载点
     */
    @Synchronized
    fun install(onProgress: (Float, String) -> Unit) {
        if (_installing) throw IllegalStateException("Ubuntu 正在安装")
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
                    onProgress(progress, "正在下载 PRoot")
                    downloadProot(prootBin, { p -> onProgress(progress + p * 0.2f, "正在下载 PRoot") })
                    if (!prootBin.exists() || prootBin.length() < 1024) {
                        throw IllegalStateException("PRoot 获取失败，请检查网络或重新安装应用")
                    }
                }
                prootBin.setExecutable(true)
            }
            onProgress(0.3f, "正在准备运行库")

            // 2) 依赖库
            if (libsDir.listFiles()?.isEmpty() != false) {
                extractAssetDir("alpine/libs", libsDir)
            }

            // 3) rootfs tarball
            val tarball = File(ubuntuDir, ROOTFS_FILE)
            if (!tarball.exists() || !verifySha256(tarball, ROOTFS_SHA256)) {
                if (tarball.exists()) tarball.delete()
                val fromAssets = extractAsset(ASSET_ROOTFS, tarball)
                if (!fromAssets || !verifySha256(tarball, ROOTFS_SHA256)) {
                    if (tarball.exists()) tarball.delete()
                    onProgress(0.35f, "正在下载 Ubuntu RootFS")
                    downloadRootfs(tarball, { p -> onProgress(0.35f + p * 0.4f, "正在下载 Ubuntu RootFS") })
                }
            }
            if (!verifySha256(tarball, ROOTFS_SHA256)) {
                tarball.delete()
                throw IllegalStateException("Ubuntu RootFS 完整性校验失败")
            }
            onProgress(0.75f, "正在校验 Ubuntu RootFS")

            // 4) 解压
            if (!isInstalled()) {
                rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                extractTarball(tarball, rootfsDir)
            }
            onProgress(0.9f, "正在配置 Ubuntu")

            // 5) 工作区挂载点
            File(rootfsDir, "workspace").mkdirs()
            File(rootfsDir, "sdcard").mkdirs()
            installMarker.writeText(ROOTFS_SHA256)
            onProgress(1f, "Ubuntu 已就绪")
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
        return collectProcess(process)
    }

    private fun collectProcess(process: Process): LinuxCommandResult {
        val pool = Executors.newFixedThreadPool(2)
        val stdout = pool.submit<String> { readBounded(process.inputStream) }
        val stderr = pool.submit<String> { readBounded(process.errorStream) }
        val exit = try { process.waitFor() } catch (_: InterruptedException) {
            process.destroy()
            Thread.currentThread().interrupt()
            -1
        }
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        return LinuxCommandResult(stdout.get(), stderr.get(), exit)
    }

    private fun readBounded(input: InputStream, limit: Int = 4 * 1024 * 1024): String = input.use {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        var truncated = false
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            val retained = minOf(count, limit - total)
            if (retained > 0) output.write(buffer, 0, retained)
            total += count
            if (total > limit) truncated = true
        }
        output.toString(Charsets.UTF_8.name()) + if (truncated) "\n[output truncated]" else ""
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
        downloadTo(selectedMirror().url, tarball, onProgress)
    }

    private fun downloadTo(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val part = File(dest.parentFile, "${dest.name}.part")
        if (part.exists()) part.delete()
        var conn: HttpURLConnection? = null
        try {
            var current = URL(urlStr)
            for (redirect in 0..5) {
                conn = current.openConnection() as HttpURLConnection
                conn!!.instanceFollowRedirects = false
                conn!!.connectTimeout = 30000
                conn!!.readTimeout = 600000
                conn!!.setRequestProperty("User-Agent", "MonkeyCode-Mobile/1.0")
                val code = conn!!.responseCode
                if (code in 300..399) {
                    val location = conn!!.getHeaderField("Location")
                        ?: throw IOException("镜像源重定向缺少目标地址")
                    current = URL(current, location)
                    conn!!.disconnect()
                    conn = null
                    if (redirect == 5) throw IOException("镜像源重定向次数过多")
                } else {
                    if (code !in 200..299) throw IOException("镜像源返回 HTTP $code")
                    break
                }
            }
            val active = conn ?: throw IOException("无法连接镜像源")
            val total = active.contentLengthLong
            FileOutputStream(part).use { out ->
                active.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
                out.fd.sync()
            }
            if (total > 0 && part.length() != total) throw IOException("下载文件大小不完整")
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    private fun extractTarball(tarball: File, dest: File) {
        val result = runProcess(arrayOf("sh", "-c", "tar -xzf '${tarball.absolutePath}' -C '${dest.absolutePath}'"))
        if (result.exitCode != 0) {
            throw IllegalStateException("解压失败: ${result.stderr}")
        }
    }
}
