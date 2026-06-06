package com.shaun.qemuvm.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "qemu_vm_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            vmConfig = VmConfig(
                qemuBinaryName = prefs[KEY_QEMU_BINARY] ?: "libqemu.so",
                memoryMb = prefs[KEY_MEMORY_MB] ?: 2048,
                cpuCores = prefs[KEY_CPU_CORES] ?: 2,
                diskImagePath = prefs[KEY_DISK_PATH] ?: "",
                installMediaPath = prefs[KEY_INSTALL_MEDIA_PATH] ?: "",
                firmwarePath = prefs[KEY_FIRMWARE_PATH] ?: "",
                extraArgs = prefs[KEY_EXTRA_ARGS] ?: "",
                sshHostPort = prefs[KEY_SSH_PORT] ?: 2222,
                serialPort = prefs[KEY_SERIAL_PORT] ?: 5555,
                monitorPort = prefs[KEY_MONITOR_PORT] ?: 5556,
                autoStartOnBoot = prefs[KEY_AUTO_START] ?: false,
                keepScreenAwake = prefs[KEY_KEEP_SCREEN_AWAKE] ?: false,
                enableEdgeOverlay = prefs[KEY_EDGE_OVERLAY] ?: false,
                hideFromRecents = prefs[KEY_HIDE_FROM_RECENTS] ?: false
            ),
            runtimeState = VmRuntimeState(
                isRunning = prefs[KEY_IS_RUNNING] ?: false,
                lastExitCode = prefs[KEY_LAST_EXIT_CODE],
                lastError = prefs[KEY_LAST_ERROR] ?: "",
                lastCommandLine = prefs[KEY_LAST_COMMAND] ?: "",
                lastTaskOutput = prefs[KEY_LAST_TASK_OUTPUT] ?: "",
                taskInProgress = prefs[KEY_TASK_IN_PROGRESS] ?: false
            )
        )
    }

    suspend fun updateVmConfig(transform: (VmConfig) -> VmConfig) {
        val current = snapshot().vmConfig
        val updated = transform(current)
        context.dataStore.edit { prefs ->
            prefs[KEY_QEMU_BINARY] = updated.qemuBinaryName
            prefs[KEY_MEMORY_MB] = updated.memoryMb
            prefs[KEY_CPU_CORES] = updated.cpuCores
            prefs[KEY_DISK_PATH] = updated.diskImagePath
            prefs[KEY_INSTALL_MEDIA_PATH] = updated.installMediaPath
            prefs[KEY_FIRMWARE_PATH] = updated.firmwarePath
            prefs[KEY_EXTRA_ARGS] = updated.extraArgs
            prefs[KEY_SSH_PORT] = updated.sshHostPort
            prefs[KEY_SERIAL_PORT] = updated.serialPort
            prefs[KEY_MONITOR_PORT] = updated.monitorPort
            prefs[KEY_AUTO_START] = updated.autoStartOnBoot
            prefs[KEY_KEEP_SCREEN_AWAKE] = updated.keepScreenAwake
            prefs[KEY_EDGE_OVERLAY] = updated.enableEdgeOverlay
            prefs[KEY_HIDE_FROM_RECENTS] = updated.hideFromRecents
        }
    }

    suspend fun updateRuntimeState(transform: (VmRuntimeState) -> VmRuntimeState) {
        val current = snapshot().runtimeState
        val updated = transform(current)
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_RUNNING] = updated.isRunning
            if (updated.lastExitCode == null) {
                prefs.remove(KEY_LAST_EXIT_CODE)
            } else {
                prefs[KEY_LAST_EXIT_CODE] = updated.lastExitCode
            }
            prefs[KEY_LAST_ERROR] = updated.lastError
            prefs[KEY_LAST_COMMAND] = updated.lastCommandLine
            prefs[KEY_LAST_TASK_OUTPUT] = updated.lastTaskOutput
            prefs[KEY_TASK_IN_PROGRESS] = updated.taskInProgress
        }
    }

    suspend fun snapshot(): AppSettings {
        return settings.first()
    }

    companion object {
        private val KEY_QEMU_BINARY = stringPreferencesKey("qemu_binary")
        private val KEY_MEMORY_MB = intPreferencesKey("memory_mb")
        private val KEY_CPU_CORES = intPreferencesKey("cpu_cores")
        private val KEY_DISK_PATH = stringPreferencesKey("disk_path")
        private val KEY_INSTALL_MEDIA_PATH = stringPreferencesKey("install_media_path")
        private val KEY_FIRMWARE_PATH = stringPreferencesKey("firmware_path")
        private val KEY_EXTRA_ARGS = stringPreferencesKey("extra_args")
        private val KEY_SSH_PORT = intPreferencesKey("ssh_port")
        private val KEY_SERIAL_PORT = intPreferencesKey("serial_port")
        private val KEY_MONITOR_PORT = intPreferencesKey("monitor_port")
        private val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        private val KEY_KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        private val KEY_EDGE_OVERLAY = booleanPreferencesKey("edge_overlay")
        private val KEY_HIDE_FROM_RECENTS = booleanPreferencesKey("hide_from_recents")
        private val KEY_IS_RUNNING = booleanPreferencesKey("is_running")
        private val KEY_LAST_EXIT_CODE = intPreferencesKey("last_exit_code")
        private val KEY_LAST_ERROR = stringPreferencesKey("last_error")
        private val KEY_LAST_COMMAND = stringPreferencesKey("last_command")
        private val KEY_LAST_TASK_OUTPUT = stringPreferencesKey("last_task_output")
        private val KEY_TASK_IN_PROGRESS = booleanPreferencesKey("task_in_progress")
    }
}
