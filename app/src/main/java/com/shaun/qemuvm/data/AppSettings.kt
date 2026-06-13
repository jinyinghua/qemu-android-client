package com.shaun.qemuvm.data

data class VmConfig(
    val qemuBinaryName: String = "libqemu.so",
    // 4GB 宿主机推荐分配 2.5GB 给 Guest，留足 1~1.5GB 给 Android 系统 + 保活 App
    val memoryMb: Int = 2560,
    // 天玑6020 2×A76+6×A55，TCG 下 2 核最优（过多核同步开销反而变慢）
    val cpuCores: Int = 2,
    val diskImagePath: String = "",
    val installMediaPath: String = "",
    val firmwarePath: String = "",
    val extraArgs: String = "",
    val sshHostPort: Int = 2222,
    val serialPort: Int = 5555,
    val monitorPort: Int = 5556,
    val autoStartOnBoot: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val enableEdgeOverlay: Boolean = false,
    val hideFromRecents: Boolean = false,
    // 启动时将磁盘镜像复制到 App 私有目录（避免 FUSE/SAF 层开销）+ 预分配完整空间
    val copyToPrivateDir: Boolean = true
)

data class VmRuntimeState(
    val isRunning: Boolean = false,
    val lastExitCode: Int? = null,
    val lastError: String = "",
    val lastCommandLine: String = "",
    val lastTaskOutput: String = "",
    val taskInProgress: Boolean = false,
    // 实际用于运行的磁盘路径（导入后可能不同于配置中的路径）
    val actualDiskPath: String = ""
)

data class AppSettings(
    val vmConfig: VmConfig = VmConfig(),
    val runtimeState: VmRuntimeState = VmRuntimeState()
)
