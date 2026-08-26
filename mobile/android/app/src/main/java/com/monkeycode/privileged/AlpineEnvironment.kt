package com.monkeycode.privileged

import android.content.Context
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AlpineCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

class AlpineEnvironment(private val context: Context) {
    companion object {
        private const val MINIROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
        private const val MINIROOTFS_SHA256 = "CHANGE_ME_TO_ACTUAL_HASH"
        private const val MINIROOTFS_SIZE = 3_000_000L // approximate
    }

    private val alpineDir: File
        get() = File(context.filesDir, "alpine")
    private val rootfsDir: File
        get() = File(alpineDir, "rootfs")
    private val workDir: File
        get() = File("/data/local/tmp/monkeycode")

    fun isInstalled(): Boolean {
        return File(rootfsDir, "etc/alpine-release").exists()
    }

    fun install(onProgress: (Float) -> Unit) {
        alpineDir.mkdirs()

        val tarball = File(alpineDir, "minirootfs.tar.gz")
        downloadMinirootfs(tarball, onProgress)
        verifyTarball(tarball)

        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        extractTarball(tarball, rootfsDir)
        tarball.delete()

        installBasicPackages()
        createWorkspaceBind()
    }

    fun execCommand(command: String): AlpineCommandResult {
        if (!isInstalled()) throw IllegalStateException("Alpine Linux not installed")

        val script = buildString {
            appendLine("#!/bin/sh")
            // 创建独立 mount namespace
            appendLine("unshare -m sh -c '")
            // 挂载必要目录
            appendLine("mount -t proc proc $rootfsDir/proc")
            appendLine("mount -t devtmpfs devtmpfs $rootfsDir/dev")
            appendLine("mount --bind /dev/pts $rootfsDir/dev/pts")
            appendLine("mount --bind /sdcard $rootfsDir/sdcard")
            // 挂载工作目录
            workDir.mkdirs()
            appendLine("mount --bind $workDir $rootfsDir/workspace")
            // chroot 并执行命令
            appendLine("chroot $rootfsDir /bin/sh -c 'cd /workspace && $command'")
            // 清理
            appendLine("umount $rootfsDir/proc")
            appendLine("umount $rootfsDir/dev/pts")
            appendLine("umount $rootfsDir/dev")
            appendLine("umount $rootfsDir/sdcard")
            appendLine("umount $rootfsDir/workspace")
            appendLine("'")
        }

        val scriptFile = File(alpineDir, "exec_${System.currentTimeMillis()}.sh")
        scriptFile.writeText(script)
        scriptFile.setExecutable(true)

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", scriptFile.absolutePath))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return AlpineCommandResult(stdout, stderr, exitCode)
        } finally {
            scriptFile.delete()
        }
    }

    private fun downloadMinirootfs(tarball: File, onProgress: (Float) -> Unit) {
        val url = URL(MINIROOTFS_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 300000

        val totalSize = connection.contentLength
        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(tarball)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytesRead: Long = 0

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            if (totalSize > 0) {
                onProgress(totalBytesRead.toFloat() / totalSize * 0.8f)
            }
        }

        outputStream.close()
        inputStream.close()
        connection.disconnect()
    }

    private fun verifyTarball(tarball: File) {
        if (!tarball.exists()) throw IllegalStateException("Download failed: tarball not found")

        val digest = MessageDigest.getInstance("SHA-256")
        val inputStream = FileInputStream(tarball)
        val buffer = ByteArray(8192)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        inputStream.close()

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != MINIROOTFS_SHA256) {
            tarball.delete()
            throw IllegalStateException("SHA-256 verification failed")
        }
    }

    private fun extractTarball(tarball: File, destDir: File) {
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "tar -xzf ${tarball.absolutePath} -C ${destDir.absolutePath}")
        )
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText()
            throw IllegalStateException("Extraction failed: $stderr")
        }
    }

    private fun installBasicPackages() {
        val packages = listOf(
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

        val pkgList = packages.joinToString(" ")
        val result = execCommand("apk update && apk add --no-cache $pkgList")
        if (result.exitCode != 0) {
            throw IllegalStateException("Package installation failed: ${result.stderr}")
        }
    }

    private fun createWorkspaceBind() {
        workDir.mkdirs()
        val workspaceDir = File(rootfsDir, "workspace")
        workspaceDir.mkdirs()
    }
}