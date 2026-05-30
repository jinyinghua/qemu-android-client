package com.shaun.qemuvm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shaun.qemuvm.app.QemuVmApplication
import com.shaun.qemuvm.data.AppSettings
import com.shaun.qemuvm.data.VmConfig
import com.shaun.qemuvm.keepalive.KeepAliveService
import com.shaun.qemuvm.util.BatteryOptimizationHelper
import kotlinx.coroutines.launch

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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val appSettings by repo.settings.collectAsState(initial = AppSettings())
                    val scope = rememberCoroutineScope()

                    MainScreen(
                        settings = appSettings,
                        onConfigChanged = { newConfig ->
                            scope.launch { repo.updateVmConfig { newConfig } }
                        },
                        onStartVm = { startVm() },
                        onStopVm = { stopVm() },
                        onRequestBatteryOpt = { requestBatteryOptimization() },
                        isBatteryOptIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: AppSettings,
    onConfigChanged: (VmConfig) -> Unit,
    onStartVm: () -> Unit,
    onStopVm: () -> Unit,
    onRequestBatteryOpt: () -> Unit,
    isBatteryOptIgnored: Boolean
) {
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
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("Running: ${settings.runtimeState.isRunning}")
                    if (settings.runtimeState.lastError.isNotBlank()) {
                        Text("Error: ${settings.runtimeState.lastError}", color = MaterialTheme.colorScheme.error)
                    }
                    if (settings.runtimeState.lastExitCode != null) {
                        Text("Last exit code: ${settings.runtimeState.lastExitCode}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStartVm, enabled = !settings.runtimeState.isRunning) { Text("Start VM") }
                        Button(onClick = onStopVm, enabled = settings.runtimeState.isRunning) { Text("Stop VM") }
                    }
                }
            }

            if (!isBatteryOptIgnored) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Battery Optimization", style = MaterialTheme.typography.titleMedium)
                        Text("Please ignore battery optimization for stable background running.")
                        Button(onClick = onRequestBatteryOpt) { Text("Configure") }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VM Configuration", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = settings.vmConfig.diskImagePath,
                        onValueChange = { onConfigChanged(settings.vmConfig.copy(diskImagePath = it)) },
                        label = { Text("Disk Image Path (ISO/QCOW2/IMG)") },
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
                        Text("Auto Start on Boot")
                        Switch(checked = settings.vmConfig.autoStartOnBoot, onCheckedChange = { onConfigChanged(settings.vmConfig.copy(autoStartOnBoot = it)) })
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Keep Screen Awake (WakeLock)")
                        Switch(checked = settings.vmConfig.keepScreenAwake, onCheckedChange = { onConfigChanged(settings.vmConfig.copy(keepScreenAwake = it)) })
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
