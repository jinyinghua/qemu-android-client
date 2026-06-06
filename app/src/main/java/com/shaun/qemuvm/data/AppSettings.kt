package com.shaun.qemuvm.data

data class VmConfig(
    val qemuBinaryName: String = "libqemu.so",
    val memoryMb: Int = 2048,
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
    val hideFromRecents: Boolean = false
)

data class VmRuntimeState(
    val isRunning: Boolean = false,
    val lastExitCode: Int? = null,
    val lastError: String = "",
    val lastCommandLine: String = "",
    val lastTaskOutput: String = "",
    val taskInProgress: Boolean = false
)

data class AppSettings(
    val vmConfig: VmConfig = VmConfig(),
    val runtimeState: VmRuntimeState = VmRuntimeState()
)
