package com.shaun.qemuvm.qemu

import com.shaun.qemuvm.data.VmConfig
import java.io.File

class QemuCommandBuilder {
    fun build(executable: File, config: VmConfig): List<String> {
        require(config.diskImagePath.isNotBlank() || config.installMediaPath.isNotBlank()) {
            "Disk image path or install media path is required"
        }
        require(config.firmwarePath.isNotBlank()) { "Firmware path is required" }

        val diskPath = config.diskImagePath.trim()
        val installMediaPath = config.installMediaPath.trim()

        val args = mutableListOf(
            executable.absolutePath,
            "-machine", "virt",
            "-cpu", "cortex-a72",
            "-m", config.memoryMb.toString(),
            "-smp", config.cpuCores.toString(),
            "-bios", config.firmwarePath,
            "-netdev", "user,id=net0,hostfwd=tcp::${config.sshHostPort}-:22",
            "-device", "virtio-net-device,netdev=net0",
            "-display", "none",
            "-serial", "telnet:127.0.0.1:${config.serialPort},server,nowait",
            "-monitor", "telnet:127.0.0.1:${config.monitorPort},server,nowait"
        )

        if (diskPath.isNotBlank()) {
            val diskLower = diskPath.lowercase()
            val diskFormat = when {
                diskLower.endsWith(".qcow2") -> "qcow2"
                else -> "raw"
            }
            args += listOf(
                "-drive", "if=none,file=$diskPath,format=$diskFormat,id=hd0",
                "-device", "virtio-blk-device,drive=hd0"
            )
        }

        if (installMediaPath.isNotBlank()) {
            args += listOf(
                "-boot", "order=d",
                "-device", "virtio-scsi-device",
                "-drive", "if=none,file=$installMediaPath,format=raw,media=cdrom,id=cd0",
                "-device", "scsi-cd,drive=cd0"
            )
        }

        if (config.extraArgs.isNotBlank()) {
            args += config.extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        return args
    }
}
