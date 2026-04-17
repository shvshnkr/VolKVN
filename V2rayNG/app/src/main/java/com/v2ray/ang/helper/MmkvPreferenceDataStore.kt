package com.v2ray.ang.helper

import androidx.preference.PreferenceDataStore
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.VolkvnAgentDebug

/**
 * PreferenceDataStore implementation that bridges AndroidX Preference framework to MMKV storage.
 * This ensures that all Preference UI operations read/write directly from/to MMKV,
 * avoiding inconsistencies between SharedPreferences and MMKV.
 */
class MmkvPreferenceDataStore : PreferenceDataStore() {

    override fun putString(key: String, value: String?) {
        val before = MmkvManager.decodeSettingsString(key, null)
        val changed = before != value
        MmkvManager.encodeSettings(key, value)
        // #region agent log
        VolkvnAgentDebug.emit(
            AngApplication.application,
            hypothesisId = "H31",
            location = "MmkvPreferenceDataStore.kt:putString",
            message = "preference_write",
            data = mapOf(
                "key" to key,
                "type" to "string",
                "changed" to changed,
                "beforeLen" to (before?.length ?: -1),
                "afterLen" to (value?.length ?: -1),
            ),
        )
        // #endregion
        if (changed) {
            notifySettingChanged(key)
        }
    }

    override fun getString(key: String, defaultValue: String?): String? {
        return MmkvManager.decodeSettingsString(key, defaultValue)
    }

    override fun putInt(key: String, value: Int) {
        val before = MmkvManager.decodeSettingsInt(key, value)
        if (before == value) return
        MmkvManager.encodeSettings(key, value)
        notifySettingChanged(key)
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return MmkvManager.decodeSettingsInt(key, defaultValue)
    }

    override fun putLong(key: String, value: Long) {
        val before = MmkvManager.decodeSettingsLong(key, value)
        if (before == value) return
        MmkvManager.encodeSettings(key, value)
        notifySettingChanged(key)
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return MmkvManager.decodeSettingsLong(key, defaultValue)
    }

    override fun putFloat(key: String, value: Float) {
        val before = MmkvManager.decodeSettingsFloat(key, value)
        if (before == value) return
        MmkvManager.encodeSettings(key, value)
        notifySettingChanged(key)
    }

    override fun getFloat(key: String, defaultValue: Float): Float {
        return MmkvManager.decodeSettingsFloat(key, defaultValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        val before = MmkvManager.decodeSettingsBool(key, value)
        val changed = before != value
        MmkvManager.encodeSettings(key, value)
        // #region agent log
        VolkvnAgentDebug.emit(
            AngApplication.application,
            hypothesisId = "H31",
            location = "MmkvPreferenceDataStore.kt:putBoolean",
            message = "preference_write",
            data = mapOf(
                "key" to key,
                "type" to "boolean",
                "changed" to changed,
                "before" to before,
                "after" to value,
            ),
        )
        // #endregion
        if (changed) {
            notifySettingChanged(key)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return MmkvManager.decodeSettingsBool(key, defaultValue)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) {
        val before = MmkvManager.decodeSettingsStringSet(key)
        val changed = before != values
        if (!changed) return
        if (values == null) {
            MmkvManager.encodeSettings(key, null as String?)
        } else {
            MmkvManager.encodeSettings(key, values)
        }
        notifySettingChanged(key)
    }

    override fun getStringSet(key: String, defaultValues: MutableSet<String>?): MutableSet<String>? {
        return MmkvManager.decodeSettingsStringSet(key) ?: defaultValues
    }

    // Internal helper: notify other modules about setting changes
    private fun notifySettingChanged(key: String) {
        // Call SettingsManager.setNightMode if UI mode changed
        if (key == AppConfig.PREF_UI_MODE_NIGHT) {
            SettingsManager.setNightMode()
        }
        // Notify listeners that require service restart or reinit
        // #region agent log
        VolkvnAgentDebug.emit(
            AngApplication.application,
            hypothesisId = "H32",
            location = "MmkvPreferenceDataStore.kt:notifySettingChanged",
            message = "restart_flag_marked",
            data = mapOf("key" to key),
        )
        // #endregion
        SettingsChangeManager.makeRestartService()
        SettingsChangeManager.makeSetupGroupTab()
    }
}