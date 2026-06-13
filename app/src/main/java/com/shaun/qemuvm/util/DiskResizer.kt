package com.shaun.qemuvm.util

import android.content.Context
import java.io.File

/**
 * 磁盘扩容工具。
 *
 * 针对 App 私有目录中的 raw/qcow2 磁盘镜像，
 * 使用 qemu-img resize 进行扩容，并处理源文件标记同步。
 */
class DiskResizer(private val context: Context) {

    data class DiskInfo(
        val path: String,
        val format: String,          // "raw" 或 "qcow2"
        val virtualSizeGiB: Double,  // 虚拟大小（Guest 可见）
        val virtualSizeBytes: Long,
        val actualSizeBytes: Long,   // 实际文件大小
        val exists: Boolean
    )

    /**
     * 获取磁盘镜像信息。
     * raw: 文件大小 = 虚拟大小
     * qcow2: 需解析 qemu-img info 输出获取虚拟大小
     */
    fun getDiskInfo(diskPath: String): DiskInfo {
        if (diskPath.isBlank()) {
            return DiskInfo("", "", 0.0, 0, 0, false)
        }

        val file = File(diskPath)
        if (!file.exists()) {
            return DiskInfo(diskPath, "", 0.0, 0, 0, false)
        }

        val format = detectFormat(diskPath)
        val actualSizeBytes = file.length()

        if (format == "qcow2") {
            // qcow2: 虚拟大小 ≠ 文件大小，需解析 qemu-img info
            val virtualBytes = try {
                val info = runQemuImgInfo(diskPath)
                parseVirtualSize(info)
            } catch (_: Exception) {
                actualSizeBytes // 回退
            }
            return DiskInfo(diskPath, format, virtualBytes / GiB, virtualBytes, actualSizeBytes, true)
        }

        // raw: 文件大小 = 虚拟大小
        return DiskInfo(diskPath, format, actualSizeBytes / GiB, actualSizeBytes, actualSizeBytes, true)
    }

    /**
     * 扩容磁盘镜像到指定大小。
     * 内部调用 qemu-img resize，支持 raw 和 qcow2。
     *
     * 扩容后自动更新 mtime，防止 DiskPreparer 将旧源文件覆盖回来。
     */
    fun resize(diskPath: String, newSizeGiB: Int): String {
        if (diskPath.isBlank() || !File(diskPath).exists()) {
            error("Disk not found: $diskPath")
        }
        if (newSizeGiB <= 0) {
            error("Target size must be > 0 GiB")
        }

        val format = detectFormat(diskPath)
        val qemuImg = resolveQemuImg()
        val sizeArg = "${newSizeGiB}G"

        val pb = ProcessBuilder(
            qemuImg.absolutePath,
            "resize",
            "-f", format,
            diskPath,
            sizeArg
        )
        NativeBinaryLocator.configureEnvironment(context, pb)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()

        if (code != 0) {
            error("qemu-img resize failed (exit=$code): ${output.ifBlank { "no output" }}")
        }

        // 扩容后更新 mtime，防止 DiskPreparer 下次启动时用旧源覆盖
        File(diskPath).setLastModified(System.currentTimeMillis())

        return buildString {
            appendLine("Resize completed")
            appendLine("Disk: $diskPath")
            appendLine("Format: $format")
            appendLine("Target: $sizeArg")
            if (output.isNotBlank()) append(output.trim())
        }
    }

    // ========== 内部方法 ==========

    private fun detectFormat(path: String): String {
        val lower = path.lowercase()
        if (lower.endsWith(".qcow2")) return "qcow2"
        // 没有扩展名或 .raw，都当作 raw
        return "raw"
    }

    private fun resolveQemuImg(): File {
        val img = NativeBinaryLocator.resolveExecutable(context, "libqemu_img.so")
        if (!img.exists()) error("qemu-img not found at ${img.absolutePath}")
        return img
    }

    private fun runQemuImgInfo(path: String): String {
        val qemuImg = resolveQemuImg()
        val pb = ProcessBuilder(qemuImg.absolutePath, "info", path)
        NativeBinaryLocator.configureEnvironment(context, pb)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) error("qemu-img info failed: ${output.ifBlank { "exit=$code" }}")
        return output
    }

    /** 从 qemu-img info 输出解析虚拟大小（字节数） */
    private fun parseVirtualSize(info: String): Long {
        // 例: "virtual size: 4 GiB (4294967296 bytes)"
        val regex = Regex("virtual size:.*?\\((\\d+)\\s+bytes\\)")
        val match = regex.find(info)
            ?: error("Cannot parse virtual size from:\n$info")
        return match.groupValues[1].toLongOrNull()
            ?: error("Invalid virtual size value in:\n$info")
    }

    companion object {
        private const val GiB = 1024.0 * 1024.0 * 1024.0
    }
}
