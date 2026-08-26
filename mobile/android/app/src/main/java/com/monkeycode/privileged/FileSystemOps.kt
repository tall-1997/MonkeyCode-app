package com.monkeycode.privileged

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modificationTime: Long
)

data class FileInfo(
    val exists: Boolean,
    val isDirectory: Boolean,
    val size: Long,
    val modificationTime: Long
)

class FileSystemOps {

    fun listDirectory(path: String): List<FileEntry> {
        val resolvedPath = resolveSymlink(path)
        val dir = File(resolvedPath)
        if (!dir.exists()) throw IllegalStateException("Directory not found: $resolvedPath")
        if (!dir.isDirectory) throw IllegalStateException("Not a directory: $resolvedPath")

        return dir.listFiles()?.map { file ->
            FileEntry(
                name = file.name,
                path = resolveSymlink(file.absolutePath),
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                modificationTime = file.lastModified()
            )
        }?.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    fun readFile(path: String, encoding: String = "utf8"): String {
        val resolvedPath = resolveSymlink(path)
        val file = File(resolvedPath)
        if (!file.exists()) throw IllegalStateException("File not found: $resolvedPath")
        if (file.isDirectory) throw IllegalStateException("Cannot read directory: $resolvedPath")

        // 限制 5MB
        if (file.length() > 5 * 1024 * 1024) {
            throw IllegalStateException("File too large (max 5MB): ${file.length()} bytes")
        }

        return if (encoding == "base64") {
            val bytes = file.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } else {
            file.readText(Charsets.UTF_8)
        }
    }

    fun writeFile(path: String, content: String) {
        val resolvedPath = resolveSymlink(path)
        val file = File(resolvedPath)
        file.parentFile?.mkdirs()

        // 原子写入：先写临时文件，再重命名
        val tempFile = File("${resolvedPath}.tmp.${System.currentTimeMillis()}")
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            tempFile.setReadable(true, false)
            tempFile.setWritable(true, true)
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tempFile.delete()
        }
    }

    fun createDirectory(path: String) {
        val resolvedPath = resolveSymlink(path)
        val dir = File(resolvedPath)
        if (!dir.mkdirs() && !dir.exists()) {
            throw IllegalStateException("Failed to create directory: $resolvedPath")
        }
    }

    fun deleteEntry(path: String) {
        val resolvedPath = resolveSymlink(path)
        val file = File(resolvedPath)
        if (!file.exists()) throw IllegalStateException("Path not found: $resolvedPath")
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            if (!file.delete()) throw IllegalStateException("Failed to delete: $resolvedPath")
        }
    }

    fun moveEntry(from: String, to: String) {
        val fromPath = resolveSymlink(from)
        val toPath = resolveSymlink(to)
        val fromFile = File(fromPath)
        if (!fromFile.exists()) throw IllegalStateException("Source not found: $fromPath")
        File(toPath).parentFile?.mkdirs()
        if (!fromFile.renameTo(File(toPath))) {
            throw IllegalStateException("Failed to move: $fromPath -> $toPath")
        }
    }

    fun copyEntry(from: String, to: String) {
        val fromPath = resolveSymlink(from)
        val toPath = resolveSymlink(to)
        val fromFile = File(fromPath)
        if (!fromFile.exists()) throw IllegalStateException("Source not found: $fromPath")
        File(toPath).parentFile?.mkdirs()
        if (fromFile.isDirectory) {
            fromFile.copyRecursively(File(toPath), true)
        } else {
            fromFile.copyTo(File(toPath), true)
        }
    }

    fun getInfo(path: String): FileInfo {
        val resolvedPath = resolveSymlink(path)
        val file = File(resolvedPath)
        return FileInfo(
            exists = file.exists(),
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            modificationTime = if (file.exists()) file.lastModified() else 0
        )
    }

    private fun resolveSymlink(path: String): String {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.canonicalPath
            } else {
                path
            }
        } catch (_: Exception) {
            path
        }
    }
}