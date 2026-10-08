package dev.waterctl.app

import android.app.Application
import dev.waterctl.app.data.db.AppDatabase
import dev.waterctl.app.data.prefs.SettingsRepository

class WaterCtlApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
}
