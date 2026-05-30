package com.shaun.qemuvm.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.shaun.qemuvm.MainActivity
import com.shaun.qemuvm.R
import com.shaun.qemuvm.app.QemuVmApplication
import com.shaun.qemuvm.qemu.QemuCommandBuilder
import com.shaun.qemuvm.util.NativeBinaryLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeepAliveService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var process: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        if (intent?.action == ACTION_STOP) {
            stopVm()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()

        lifecycleScope.launch(Dispatchers.IO) {
            startVm()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val stopIntent = Intent(this, KeepAliveService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val uiIntent = Intent(this, MainActivity::class.java)
        val uiPendingIntent = PendingIntent.getActivity(this, 0, uiIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_running))
            .setContentText("QEMU VM is running in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(uiPendingIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun startVm() {
        val repo = (application as QemuVmApplication).settingsRepository
        val config = repo.snapshot().vmConfig

        if (config.diskImagePath.isBlank() || config.firmwarePath.isBlank()) {
            repo.updateRuntimeState { it.copy(isRunning = false, lastError = "Disk or firmware path missing") }
            stopSelf()
            return
        }

        val qemuBin = NativeBinaryLocator.resolveExecutable(this, config.qemuBinaryName)
        val args = QemuCommandBuilder().build(qemuBin, config)
        
        repo.updateRuntimeState { it.copy(isRunning = true, lastCommandLine = args.joinToString(" "), lastError = "") }

        acquireWakeLock(config.keepScreenAwake)

        try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            process = pb.start()

            // In a real app, you'd read process.inputStream here and log it to UI or file.
            val exitCode = process?.waitFor() ?: -1
            
            repo.updateRuntimeState { it.copy(isRunning = false, lastExitCode = exitCode) }
        } catch (e: Exception) {
            repo.updateRuntimeState { it.copy(isRunning = false, lastError = e.message ?: "Unknown error") }
        } finally {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun stopVm() {
        process?.destroy()
        process = null
        releaseWakeLock()
        lifecycleScope.launch {
            val repo = (application as QemuVmApplication).settingsRepository
            repo.updateRuntimeState { it.copy(isRunning = false) }
        }
    }

    private fun acquireWakeLock(keepScreenAwake: Boolean) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val levelAndFlags = if (keepScreenAwake) {
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
        } else {
            PowerManager.PARTIAL_WAKE_LOCK
        }
        wakeLock = pm.newWakeLock(levelAndFlags, "QemuVm::RuntimeWakeLock").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopVm()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.shaun.qemuvm.action.START"
        const val ACTION_STOP = "com.shaun.qemuvm.action.STOP"
        private const val CHANNEL_ID = "qemu_vm_channel"
        private const val NOTIFICATION_ID = 101
    }
}
