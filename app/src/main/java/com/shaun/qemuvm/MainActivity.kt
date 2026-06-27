package com.shaun.qemuvm

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shaun.qemuvm.app.QemuVmApplication
import com.shaun.qemuvm.data.AppSettings
import com.shaun.qemuvm.data.VmConfig
import com.shaun.qemuvm.data.VmState
import com.shaun.qemuvm.keepalive.KeepAliveService
import com.shaun.qemuvm.util.BatteryOptimizationHelper
import com.shaun.qemuvm.util.DiskPreparer
import com.shaun.qemuvm.util.DiskResizer
import com.shaun.qemuvm.util.NativeBinaryLocator
import com.shaun.qemuvm.util.StorageAccessHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val repo = (application as QemuVmApplication).settingsRepository
        lifecycleScope.launch {
            val snapshot = repo.settings.first()
            applyRecentsVisibility(snapshot.vmConfig.hideFromRecents)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val appSettings by repo.settings.collectAsState(initial = AppSettings())
                    val scope = rememberCoroutineScope()

                    MainScreen(
                        settings = appSettings,
                        onConfigChanged = { newConfig ->
                            scope.launch {
                                repo.updateVmConfig { newConfig }
                                applyRecentsVisibility(newConfig.hideFromRecents)
                            }
                        },
                        onStartVm = { startVm() },
                        onStopVm = { stopVm() },
                        onRequestBatteryOpt = { requestBatteryOptimization() },
                        onRequestStorageAccess = { requestAllFilesAccess() },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onConvertQcowToRaw = { source, dest -> runDiskTask("Convert QCOW2 to RAW") { convertQcow2ToRaw(source, dest) } },
                        onResizeDisk = { path, sizeGiB -> runDiskTask("Resize disk") { resizeDisk(path, sizeGiB) } },
                        onDeletePrivateCopy = { deletePrivateCopy() },
                        onResizePrivateCopy = { newSizeGiB -> resizePrivateCopy(newSizeGiB) },
                        isBatteryOptIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this),
                        hasStorageAccess = StorageAccessHelper.hasRequiredAccessForSharedStorage(),
                        hasOverlayPermission = Settings.canDrawOverlays(this)
                    )
                }
            }
        }
    }

    private fun startVm() {
        val intent = Intent(this, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVm() {
        val intent = Intent(this, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP }
        startService(intent)
    }

    private fun requestBatteryOptimization() {
        try {
            startActivity(BatteryOptimizationHelper.buildRequestIntent(this))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestAllFilesAccess() {
        try {
            startActivity(StorageAccessHelper.buildManageAllFilesIntent(this))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun runDiskTask(name: String, block: () -> String) {
        val repo = (application as QemuVmApplication).settingsRepository
        lifecycleScope.launch(Dispatchers.IO) {
            repo.updateRuntimeState { it.copy(taskInProgress = true, lastTaskOutput = "$name: running...", lastError = "") }
            val result = runCatching { block() }.getOrElse { e -> "ERROR: ${e.message ?: e.javaClass.simpleName}" }
            repo.updateRuntimeState { it.copy(taskInProgress = false, lastTaskOutput = result) }
        }
    }

    private fun deletePrivateCopy() {
        runDiskTask("Delete private copy") {
            val success = DiskPreparer.deletePrivateCopy(this)
            if (success) "Private copy deleted successfully" else "Some files could not be deleted"
        }
    }

    /** 扩容私有目录中的磁盘副本 */
    private fun resizePrivateCopy(newSizeGiB: Int) {
        runDiskTask("Resize private copy") {
            val privateCopy = DiskPreparer.getPrivateCopyPath(this)
                ?: error("No private copy found. Start the VM first to create one.")
            DiskResizer(this).resize(privateCopy.absolutePath, newSizeGiB)
        }
    }

    private fun convertQcow2ToRaw(source: String, destination: String): String {
        require(source.isNotBlank()) { "Source path missing" }
        require(destination.isNotBlank()) { "Destination path missing" }
        File(destination).parentFile?.mkdirs()
        val qemuImg = NativeBinaryLocator.resolveExecutable(this, "libqemu_img.so")
        val pb = ProcessBuilder(qemuImg.absolutePath, "convert", "-p", "-f", "qcow2", "-O", "raw", source, destination)
        NativeBinaryLocator.configureEnvironment(this, pb)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) error(output.ifBlank { "qemu-img convert failed with code $code" })
        return buildString {
            appendLine("Convert completed")
            appendLine("Source: $source")
            appendLine("Destination: $destination")
            if (output.isNotBlank()) append(output.trim())
        }
    }

    private fun resizeDisk(path: String, sizeGiB: Int): String {
        require(path.isNotBlank()) { "Disk path missing" }
        require(sizeGiB > 0) { "Target GiB must be > 0" }
        return DiskResizer(this).resize(path, sizeGiB)
    }

    private fun applyRecentsVisibility(hide: Boolean) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (hide) {
            am.appTasks.forEach { it.setExcludeFromRecents(true) }
        } else {
            am.appTasks.forEach { it.setExcludeFromRecents(false) }
        }
    }
}

private fun com.shaun.qemuvm.data.SettingsRepository.snapshotBlocking(): AppSettings =
    kotlinx.coroutines.runBlocking { snapshot() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: AppSettings,
    onConfigChanged: (VmConfig) -> Unit,
    onStartVm: () -> Unit,
    onStopVm: () -> Unit,
    onRequestBatteryOpt: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onConvertQcowToRaw: (String, String) -> Unit,
    onResizeDisk: (String, Int) -> Unit,
    onDeletePrivateCopy: () -> Unit,
    onResizePrivateCopy: (Int) -> Unit,
    isBatteryOptIgnored: Boolean,
    hasStorageAccess: Boolean,
    hasOverlayPermission: Boolean
) {
    var convertSource by remember { mutableStateOf(settings.vmConfig.diskImagePath) }
    var convertDest by remember {
        mutableStateOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "converted.raw").absolutePath
        )
    }
    var resizePath by remember { mutableStateOf(settings.vmConfig.diskImagePath) }
    var resizeGiB by remember { mutableStateOf("4") }
    var privateResizeGiB by remember { mutableStateOf("8") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("QEMU Android Client") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ============ Status ============
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("State: ${settings.runtimeState.state}")
                    if (settings.runtimeState.lastError.isNotBlank()) {
                        Text("Error: ${settings.runtimeState.lastError}", color = MaterialTheme.colorScheme.error)
                    }
                    if (settings.runtimeState.lastExitCode != null) {
                        Text("Last exit code: ${settings.runtimeState.lastExitCode}")
                    }
                    if (settings.runtimeState.actualDiskPath.isNotBlank()) {
                        Text("Actual disk path:", style = MaterialTheme.typography.bodySmall)
                        Text(settings.runtimeState.actualDiskPath, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartVm,
                            enabled = settings.runtimeState.state == VmState.Idle
                        ) { Text("Start VM") }
                        Button(
                            onClick = onStopVm,
                            enabled = settings.runtimeState.state in setOf(
                                VmState.PreparingDisk,
                                VmState.PreparingFirmware,
                                VmState.Starting,
                                VmState.Running
                            )
                        ) { Text("Stop VM") }
                    }
                }
            }

            // ============ Shared Storage ============
            if (!hasStorageAccess) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shared Storage Access", style = MaterialTheme.typography.titleMedium)
                        Text("The VM image and firmware are under /storage/emulated/0/Download. On modern Android, this app needs all files access to read them directly.")
                        Button(onClick = onRequestStorageAccess) { Text("Grant All Files Access") }
                    }
                }
            }

            // ============ Battery Optimization ============
            if (!isBatteryOptIgnored) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Battery Optimization", style = MaterialTheme.typography.titleMedium)
                        Text("Please ignore battery optimization for stable background running.")
                        Button(onClick = onRequestBatteryOpt) { Text("Configure") }
                    }
                }
            }

            // ============ VM Configuration ============
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VM Configuration", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = settings.vmConfig.diskImagePath,
                        onValueChange = {
                            onConfigChanged(settings.vmConfig.copy(diskImagePath = it))
                            convertSource = it
                            resizePath = it
                        },
                        label = { Text("System Disk Path (RAW/QCOW2)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = settings.vmConfig.installMediaPath,
                        onValueChange = { onConfigChanged(settings.vmConfig.copy(installMediaPath = it)) },
                        label = { Text("Install Media Path (ISO, optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = settings.vmConfig.firmwarePath,
                        onValueChange = { onConfigChanged(settings.vmConfig.copy(firmwarePath = it)) },
                        label = { Text("Firmware Path (QEMU_EFI.fd)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = settings.vmConfig.memoryMb.toString(),
                            onValueChange = { it.toIntOrNull()?.let { mem -> onConfigChanged(settings.vmConfig.copy(memoryMb = mem)) } },
                            label = { Text("RAM (MB)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = settings.vmConfig.cpuCores.toString(),
                            onValueChange = { it.toIntOrNull()?.let { cpu -> onConfigChanged(settings.vmConfig.copy(cpuCores = cpu)) } },
                            label = { Text("CPU Cores") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = settings.vmConfig.extraArgs,
                        onValueChange = { onConfigChanged(settings.vmConfig.copy(extraArgs = it)) },
                        label = { Text("Extra QEMU Args") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Copy to private directory")
                        Switch(
                            checked = settings.vmConfig.copyToPrivateDir,
                            onCheckedChange = { onConfigChanged(settings.vmConfig.copy(copyToPrivateDir = it)) }
                        )
                    }

                    if (settings.vmConfig.copyToPrivateDir) {
                        Text(
                            "On start, the disk image will be copied to app private dir with full preallocation. " +
                                "This bypasses SAF/FUSE for better IO performance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto Start on Boot")
                        Switch(checked = settings.vmConfig.autoStartOnBoot, onCheckedChange = { onConfigChanged(settings.vmConfig.copy(autoStartOnBoot = it)) })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Keep Screen Awake (WakeLock)")
                        Switch(checked = settings.vmConfig.keepScreenAwake, onCheckedChange = { onConfigChanged(settings.vmConfig.copy(keepScreenAwake = it)) })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Transparent Edge Overlay")
                        Switch(
                            checked = settings.vmConfig.enableEdgeOverlay,
                            onCheckedChange = {
                                if (it && !hasOverlayPermission) onRequestOverlayPermission()
                                onConfigChanged(settings.vmConfig.copy(enableEdgeOverlay = it))
                            }
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Hide from Recents")
                        Switch(
                            checked = settings.vmConfig.hideFromRecents,
                            onCheckedChange = { onConfigChanged(settings.vmConfig.copy(hideFromRecents = it)) }
                        )
                    }
                }
            }

            // ============ Private Copy Management ============
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Private Copy Management", style = MaterialTheme.typography.titleMedium)

                    // 私有副本状态（仅显示信息，不自动刷新）
                    Text("Status info available after first VM start.", style = MaterialTheme.typography.bodySmall)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(
                            onClick = onDeletePrivateCopy,
                            enabled = !settings.runtimeState.taskInProgress
                        ) {
                            Text("Delete Private Copy")
                        }
                    }

                    // 扩容
                    Text("Expand disk size:", style = MaterialTheme.typography.bodySmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = privateResizeGiB,
                            onValueChange = { privateResizeGiB = it },
                            label = { Text("New size (GiB)") },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                privateResizeGiB.toIntOrNull()?.let { size ->
                                    if (size > 0) onResizePrivateCopy(size)
                                }
                            },
                            enabled = !settings.runtimeState.taskInProgress
                        ) {
                            Text("Expand")
                        }
                    }

                    Text(
                        "Expands the private copy to the specified size. " +
                            "After expansion, you'll need to partition the new space inside the guest (e.g. fdisk + resize2fs).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ============ Disk Tools ============
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Disk Tools", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = convertSource,
                        onValueChange = { convertSource = it },
                        label = { Text("QCOW2 source path") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = convertDest,
                        onValueChange = { convertDest = it },
                        label = { Text("RAW destination path") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { onConvertQcowToRaw(convertSource, convertDest) }, enabled = !settings.runtimeState.taskInProgress) {
                        Text("Convert QCOW2 to RAW")
                    }

                    OutlinedTextField(
                        value = resizePath,
                        onValueChange = { resizePath = it },
                        label = { Text("Disk path to resize") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = resizeGiB,
                        onValueChange = { resizeGiB = it },
                        label = { Text("Target size (GiB)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { resizeGiB.toIntOrNull()?.let { onResizeDisk(resizePath, it) } }, enabled = !settings.runtimeState.taskInProgress) {
                        Text("Resize RAW/QCOW2")
                    }
                }
            }

            // ============ Task Output ============
            if (settings.runtimeState.lastTaskOutput.isNotBlank()) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Last Task Output", style = MaterialTheme.typography.titleSmall)
                        Text(settings.runtimeState.lastTaskOutput, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (settings.runtimeState.lastCommandLine.isNotBlank()) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Last Command", style = MaterialTheme.typography.titleSmall)
                        Text(settings.runtimeState.lastCommandLine, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
