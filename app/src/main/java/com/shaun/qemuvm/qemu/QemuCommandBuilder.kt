package com.shaun.qemuvm.qemu

import com.shaun.qemuvm.data.VmConfig
import java.io.File

class QemuCommandBuilder {
    fun build(
        executable: File,
        config: VmConfig,
        firmwareCodePath: String? = null,
        firmwareVarsPath: String? = null
    ): List<String> {
        require(config.diskImagePath.isNotBlank() || config.installMediaPath.isNotBlank()) {
            "Disk image path or install media path is required"
        }
        require(config.firmwarePath.isNotBlank()) { "Firmware path is required" }

        val diskPath = config.diskImagePath.trim()
        val installMediaPath = config.installMediaPath.trim()
        val hasInstallMedia = installMediaPath.isNotBlank()

        val args = mutableListOf(
            executable.absolutePath,
            "-machine", "virt",
            "-cpu", "cortex-a72",
            "-m", config.memoryMb.toString(),
            "-smp", config.cpuCores.toString(),
            "-netdev", "user,id=net0,hostfwd=tcp::${config.sshHostPort}-:22",
            "-device", "virtio-net-device,netdev=net0",
            "-nographic",
            "-serial", "telnet:127.0.0.1:${config.serialPort},server,nowait",
            "-monitor", "telnet:127.0.0.1:${config.monitorPort},server,nowait"
        )

        if (firmwareCodePath != null && firmwareVarsPath != null) {
            args += listOf(
                "-drive", "if=pflash,format=raw,readonly=on,file=$firmwareCodePath",
                "-drive", "if=pflash,format=raw,file=$firmwareVarsPath"
            )
        } else {
            args += listOf("-bios", config.firmwarePath)
        }

        if (hasInstallMedia) {
            args += listOf(
                "-device", "virtio-scsi-pci,id=scsi0",
                "-drive", "if=none,file=$installMediaPath,format=raw,media=cdrom,id=cd0",
                "-device", "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=0"
            )
        }

        if (diskPath.isNotBlank()) {
            val diskLower = diskPath.lowercase()
            val diskFormat = when {
                diskLower.endsWith(".qcow2") -> "qcow2"
                else -> "raw"
            }
            args += listOf(
                "-drive", "if=none,file=$diskPath,format=$diskFormat,id=hd0",
                "-device", "virtio-blk-pci,drive=hd0,bootindex=${if (hasInstallMedia) 1 else 0}"
            )
        }

        if (config.extraArgs.isNotBlank()) {
            args += config.extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        return args
    }
}
