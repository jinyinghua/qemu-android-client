package com.shaun.qemuvm.app

import android.app.Application
import com.shaun.qemuvm.data.SettingsRepository

class QemuVmApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
    }
}
