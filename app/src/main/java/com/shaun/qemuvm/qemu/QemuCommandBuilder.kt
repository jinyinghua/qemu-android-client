package com.shaun.qemuvm.qemu

import com.shaun.qemuvm.data.VmConfig
import java.io.File

class QemuCommandBuilder {
    fun build(executable: File, config: VmConfig): List<String> {
        require(config.diskImagePath.isNotBlank()) { "Disk image path is required" }
        require(config.firmwarePath.isNotBlank()) { "Firmware path is required" }

        val args = mutableListOf(
            executable.absolutePath,
            "-machine", "virt",
            "-cpu", "cortex-a72",
            "-m", config.memoryMb.toString(),
            "-smp", config.cpuCores.toString(),
            "-bios", config.firmwarePath,
            "-drive", "if=none,file=${config.diskImagePath},format=qcow2,id=hd0",
            "-device", "virtio-blk-device,drive=hd0",
            "-netdev", "user,id=net0,hostfwd=tcp::${config.sshHostPort}-:22",
            "-device", "virtio-net-device,netdev=net0",
            "-display", "none",
            "-serial", "telnet:127.0.0.1:${config.serialPort},server,nowait",
            "-monitor", "telnet:127.0.0.1:${config.monitorPort},server,nowait"
        )

        if (config.extraArgs.isNotBlank()) {
            args += config.extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        return args
    }
}
