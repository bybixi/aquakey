package dev.waterctl.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "waterctl_settings")

data class AppSettings(
    /** 记住上次使用的设备，下次打开自动填入并支持一键重连 */
    val rememberDevice: Boolean = true,
    /** 打开应用时自动重连上次的设备 */
    val autoReconnect: Boolean = false,
    /** 错误弹窗中展示十六进制收发日志 */
    val showDebugLog: Boolean = true,
    /** 上次连接的水控器名 */
    val lastDeviceName: String? = null,
    /** 上次连接的水控器 MAC */
    val lastDeviceAddress: String? = null,
)

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            rememberDevice = prefs[KEY_REMEMBER_DEVICE] ?: true,
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: false,
            showDebugLog = prefs[KEY_SHOW_DEBUG_LOG] ?: true,
            lastDeviceName = prefs[KEY_LAST_DEVICE_NAME],
            lastDeviceAddress = prefs[KEY_LAST_DEVICE_ADDRESS],
        )
    }

    suspend fun setRememberDevice(value: Boolean) = store.edit { it[KEY_REMEMBER_DEVICE] = value }

    suspend fun setAutoReconnect(value: Boolean) = store.edit { it[KEY_AUTO_RECONNECT] = value }

    suspend fun setShowDebugLog(value: Boolean) = store.edit { it[KEY_SHOW_DEBUG_LOG] = value }

    suspend fun setLastDevice(name: String, address: String) = store.edit {
        it[KEY_LAST_DEVICE_NAME] = name
        it[KEY_LAST_DEVICE_ADDRESS] = address
    }

    suspend fun clearLastDevice() = store.edit {
        it.remove(KEY_LAST_DEVICE_NAME)
        it.remove(KEY_LAST_DEVICE_ADDRESS)
    }

    private companion object {
        val KEY_REMEMBER_DEVICE = booleanPreferencesKey("remember_device")
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEY_SHOW_DEBUG_LOG = booleanPreferencesKey("show_debug_log")
        val KEY_LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val KEY_LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
    }
}
