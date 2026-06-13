package com.shaun.qemuvm.util

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * 磁盘镜像导入与预分配工具。
 *
 * 作用：
 * 1. 将用户指定的 raw/qcow2 从公共存储（如 /storage/emulated/0/）复制到 App 私有目录
 *    （绕过 FUSE/SAF 层，QEMU 直接走 ext4/f2fs，IO 性能明显提升）
 * 2. 复制时预分配完整空间（posix_fallocate 语义），避免运行时扩容与碎片
 */
class DiskPreparer(private val context: Context) {

    data class PrepareResult(
        /** 最终使用的文件（在私有目录或原地） */
        val preparedFile: File,
        /** 是否发生了复制 */
        val wasCopied: Boolean,
        /** 源路径（记录用） */
        val sourcePath: String,
        /** 详细日志 */
        val log: String
    )

    /**
     * 准备磁盘镜像。
     *
     * @param configDiskPath 用户配置的磁盘路径
     * @param copyToPrivate  是否复制到私有目录（UI 开关）
     * @return PrepareResult，包含最终文件路径等信息
     */
    fun prepare(configDiskPath: String, copyToPrivate: Boolean): PrepareResult {
        if (configDiskPath.isBlank()) {
            return PrepareResult(File(""), false, "", "skip: no disk path")
        }

        val sourceFile = File(configDiskPath)
        if (!sourceFile.exists()) {
            return PrepareResult(sourceFile, false, configDiskPath, "skip: source not exists")
        }

        if (!copyToPrivate) {
            return PrepareResult(sourceFile, false, configDiskPath, "skip: copy disabled")
        }

        // 如果源文件已经在 App 私有目录下，直接使用（避免重复复制）
        val filesDir = context.filesDir.absolutePath
        if (sourceFile.absolutePath.startsWith(filesDir)) {
            return PrepareResult(sourceFile, false, configDiskPath, "skip: already in private dir")
        }

        val vmDir = File(context.filesDir, "vm")
        vmDir.mkdirs()

        val extension = sourceFile.extension.ifBlank { "raw" }
        // 统一命名为 system.raw / system.qcow2，便于管理
        val destFile = File(vmDir, "system.${extension.lowercase()}")

        // 标记文件：记录上次复制的源路径
        val sourceMarker = File(vmDir, ".source_path")
        val previousSource = if (sourceMarker.exists()) sourceMarker.readText().trim() else ""

        // 如果目标文件已存在且源路径未变且目标不比源旧，跳过复制
        if (destFile.exists() &&
            previousSource == sourceFile.absolutePath &&
            destFile.lastModified() >= sourceFile.lastModified()
        ) {
            return PrepareResult(destFile, false, configDiskPath, "skip: up-to-date")
        }

        // 执行复制 + 预分配
        val log = copyWithPreallocation(sourceFile, destFile)

        // 记录源路径
        sourceMarker.writeText(sourceFile.absolutePath)

        return PrepareResult(destFile, true, configDiskPath, log)
    }

    /**
     * 执行带预分配的复制。
     *
     * 策略：
     * 1. 先用 RandomAccessFile.setLength() 预分配完整大小
     *    （在 ext4/f2fs 上会触发真正的块分配，提前发现空间不足）
     * 2. 再用 1MB 缓冲逐块写入数据
     */
    private fun copyWithPreallocation(source: File, dest: File): String {
        dest.parentFile?.mkdirs()

        val sourceSize = source.length()
        val destPath = dest.absolutePath
        val sourcePath = source.absolutePath

        // 如果目标已存在，先删除（避免新旧格式混淆）
        if (dest.exists()) {
            dest.delete()
        }

        // Step 1: 预分配完整空间
        var preallocMs = 0L
        val preallocStart = System.currentTimeMillis()
        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(sourceSize)
        }
        preallocMs = System.currentTimeMillis() - preallocStart

        // Step 2: 逐块复制数据
        var copyMs = 0L
        val copyStart = System.currentTimeMillis()
        var totalBytes: Long = 0

        source.inputStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024) // 1MB 缓冲
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalBytes += read
                }
                output.flush()
            }
        }
        copyMs = System.currentTimeMillis() - copyStart

        // 确保目标文件的修改时间不早于源文件
        dest.setLastModified(source.lastModified())

        val sizeGiB = "%.1f".format(sourceSize / (1024.0 * 1024.0 * 1024.0))
        return buildString {
            appendLine("Imported: $sourcePath → $destPath")
            appendLine("Size: ${sourceSize / (1024*1024)} MB ($sizeGiB GiB)")
            appendLine("Prealloc: ${preallocMs}ms")
            appendLine("Copy: ${copyMs}ms")
            appendLine("Speed: ${if (copyMs > 0) "%.0f".format(totalBytes.toDouble() / copyMs / 1024.0) else "?"} MB/s")
        }
    }

    companion object {
        /** 检查当前运行的磁盘是否来自私有目录的副本 */
        fun isUsingPrivateCopy(context: Context, configDiskPath: String): Boolean {
            if (configDiskPath.isBlank()) return false
            return File(configDiskPath).absolutePath.startsWith(
                File(context.filesDir, "vm").absolutePath
            )
        }

        /** 获取私有目录中的副本路径（如果存在） */
        fun getPrivateCopyPath(context: Context): File? {
            val vmDir = File(context.filesDir, "vm")
            if (!vmDir.exists()) return null
            val candidates = vmDir.listFiles { file ->
                file.isFile && (file.name.startsWith("system."))
            } ?: return null
            return candidates.firstOrNull()
        }

        /** 删除私有副本及标记文件 */
        fun deletePrivateCopy(context: Context): Boolean {
            val vmDir = File(context.filesDir, "vm")
            if (!vmDir.exists()) return true
            var success = true
            vmDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.startsWith("system.") || file.name == ".source_path")) {
                    if (!file.delete()) success = false
                }
            }
            return success
        }
    }
}
