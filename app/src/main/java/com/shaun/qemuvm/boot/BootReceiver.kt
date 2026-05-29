package com.shaun.qemuvm.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shaun.qemuvm.app.QemuVmApplication
import com.shaun.qemuvm.keepalive.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as QemuVmApplication
            CoroutineScope(Dispatchers.IO).launch {
                val settings = app.settingsRepository.snapshot()
                if (settings.vmConfig.autoStartOnBoot) {
                    val serviceIntent = Intent(context, KeepAliveService::class.java).apply {
                        action = KeepAliveService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            context.startForegroundService(serviceIntent)
                        } catch (e: Exception) {
                            // On Android 15+, starting an FGS from BOOT_COMPLETED might fail if restricted.
                            // To properly handle Android 15 restrictions, one should defer the launch or use WorkManager.
                            e.printStackTrace()
                        }
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
