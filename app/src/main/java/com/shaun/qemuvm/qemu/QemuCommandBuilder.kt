package com.shaun.qemuvm.qemu

import com.shaun.qemuvm.data.VmConfig
import java.io.File

class QemuCommandBuilder {
    fun build(
        executable: File,
        config: VmConfig,
        firmwareCodePath: String? = null,
        firmwareVarsPath: String? = null,
        cloudSeedUrl: String? = null,
        dataDirPath: String? = null
    ): List<String> {
        require(config.diskImagePath.isNotBlank() || config.installMediaPath.isNotBlank()) {
            "Disk image path or install media path is required"
        }
        require(config.firmwarePath.isNotBlank()) { "Firmware path is required" }

        val diskPath = config.diskImagePath.trim()
        val installMediaPath = config.installMediaPath.trim()
        val hasInstallMedia = installMediaPath.isNotBlank()
        val extraArgs = config.extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wantsVnc = extraArgs.any { it == "-vnc" || it.startsWith("vnc=") }

        val args = mutableListOf(
            executable.absolutePath,
            "-machine", "virt",
            "-cpu", "cortex-a72",
            "-m", config.memoryMb.toString(),
            "-smp", config.cpuCores.toString(),
            "-device", "virtio-rng-pci"
        )

        if (dataDirPath != null) {
            args += listOf("-L", dataDirPath)
        }

        args += listOf(
            "-netdev", "user,id=net0,hostfwd=tcp::${config.sshHostPort}-:22",
            "-device", "virtio-net-pci,netdev=net0,romfile=",
            "-serial", "telnet:127.0.0.1:${config.serialPort},server,nowait",
            "-monitor", "telnet:127.0.0.1:${config.monitorPort},server,nowait"
        )

        if (wantsVnc) {
            args += listOf(
                "-display", "none",
                "-device", "virtio-gpu-pci"
            )
        } else {
            args += listOf("-nographic")
        }

        if (firmwareCodePath != null && firmwareVarsPath != null) {
            args += listOf(
                "-drive", "if=pflash,format=raw,readonly=on,file=$firmwareCodePath",
                "-drive", "if=pflash,format=raw,file=$firmwareVarsPath"
            )
        } else {
            args += listOf("-bios", config.firmwarePath)
        }

        if (cloudSeedUrl != null) {
            args += listOf(
                "-smbios", "type=1,serial=ds=nocloud-net;s=$cloudSeedUrl"
            )
        }

        if (hasInstallMedia) {
            args += listOf(
                "-device", "virtio-scsi-pci,id=scsi0",
                "-drive", "if=none,file=$installMediaPath,format=raw,media=cdrom,id=cd0",
                "-device", "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=0"
            )
        }

        if (diskPath.isNotBlank()) {
            val diskFormat = detectDiskFormat(File(diskPath))
            args += listOf(
                "-drive", "if=none,file=$diskPath,format=$diskFormat,id=hd0",
                "-device", "virtio-blk-pci,drive=hd0,bootindex=${if (hasInstallMedia) 1 else 0}"
            )
        }

        if (extraArgs.isNotEmpty()) {
            args += extraArgs
        }

        return args
    }

    private fun detectDiskFormat(file: File): String {
        if (!file.exists() || !file.isFile) return "raw"

        if (file.extension.equals("qcow2", ignoreCase = true)) return "qcow2"

        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read == 4 &&
                    header[0] == 'Q'.code.toByte() &&
                    header[1] == 'F'.code.toByte() &&
                    header[2] == 'I'.code.toByte() &&
                    header[3] == 0xfb.toByte()
                ) {
                    "qcow2"
                } else {
                    "raw"
                }
            }
        }.getOrDefault("raw")
    }
}
