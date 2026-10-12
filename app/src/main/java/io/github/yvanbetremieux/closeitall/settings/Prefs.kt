package io.github.yvanbetremieux.closeitall.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.yvanbetremieux.closeitall.automation.DEFAULT_GPS_APPS
import io.github.yvanbetremieux.closeitall.automation.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class UpdatePrefs(val autoCheck: Boolean, val autoInstall: Boolean, val lastCheck: Long)

class Prefs(private val context: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val btEnabled = booleanPreferencesKey("bt_enabled")
        val btDelay = intPreferencesKey("bt_delay_min")
        val locEnabled = booleanPreferencesKey("loc_enabled")
        val locDelay = intPreferencesKey("loc_delay_min")
        val gpsApps = stringSetPreferencesKey("gps_apps")
        val autoCheck = booleanPreferencesKey("update_auto_check")
        val autoInstall = booleanPreferencesKey("update_auto_install")
        val lastCheck = longPreferencesKey("update_last_check")
        fun previous(id: String) = stringPreferencesKey("system_previous_$id")
    }

    val rules: Flow<RuleConfig> = context.dataStore.data.map(::rulesFrom)

    val updates: Flow<UpdatePrefs> = context.dataStore.data.map { p ->
        UpdatePrefs(p[K.autoCheck] ?: true, p[K.autoInstall] ?: false, p[K.lastCheck] ?: 0L)
    }

    suspend fun currentRules(): RuleConfig = rules.first()

    suspend fun updateRules(change: (RuleConfig) -> RuleConfig) {
        context.dataStore.edit { p ->
            val c = change(rulesFrom(p))
            p[K.enabled] = c.enabled
            p[K.btEnabled] = c.btEnabled
            p[K.btDelay] = c.btDelayMin.coerceIn(1, 120)
            p[K.locEnabled] = c.locEnabled
            p[K.locDelay] = c.locDelayMin.coerceIn(1, 120)
            p[K.gpsApps] = c.gpsApps
        }
    }

    suspend fun setAutoCheck(value: Boolean) = context.dataStore.edit { it[K.autoCheck] = value }
    suspend fun setAutoInstall(value: Boolean) = context.dataStore.edit { it[K.autoInstall] = value }
    suspend fun setLastCheck(value: Long) = context.dataStore.edit { it[K.lastCheck] = value }

    suspend fun previousValue(id: String): String? = context.dataStore.data.first()[K.previous(id)]
    suspend fun setPreviousValue(id: String, value: String?) = context.dataStore.edit {
        if (value == null) it.remove(K.previous(id)) else it[K.previous(id)] = value
    }

    private fun rulesFrom(p: Preferences) = RuleConfig(
        enabled = p[K.enabled] ?: true,
        btEnabled = p[K.btEnabled] ?: false,
        btDelayMin = p[K.btDelay] ?: 5,
        locEnabled = p[K.locEnabled] ?: false,
        locDelayMin = p[K.locDelay] ?: 10,
        gpsApps = p[K.gpsApps] ?: DEFAULT_GPS_APPS,
    )
}
