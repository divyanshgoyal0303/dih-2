package com.voicecommander.managers

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class PinManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_commander_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SETUP_COMPLETE = "pin_setup_complete"
        private const val KEY_LOCKED_APPS = "locked_apps"
        private const val KEY_CUSTOM_COMMANDS = "custom_commands"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_ROOT_MODE = "root_mode"
    }

    fun isPinSet(): Boolean {
        return prefs.getBoolean(KEY_PIN_SETUP_COMPLETE, false)
    }

    fun setPin(pin: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .putBoolean(KEY_PIN_SETUP_COMPLETE, true)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return storedHash == hashPin(pin)
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verifyPin(oldPin)) return false
        setPin(newPin)
        return true
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getLockedApps(): MutableSet<String> {
        return prefs.getStringSet(KEY_LOCKED_APPS, mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
    }

    fun setLockedApps(apps: Set<String>) {
        prefs.edit().putStringSet(KEY_LOCKED_APPS, apps).apply()
    }

    fun addLockedApp(packageName: String) {
        val locked = getLockedApps()
        locked.add(packageName)
        setLockedApps(locked)
    }

    fun removeLockedApp(packageName: String) {
        val locked = getLockedApps()
        locked.remove(packageName)
        setLockedApps(locked)
    }

    fun isAppLocked(packageName: String): Boolean {
        return getLockedApps().contains(packageName)
    }

    fun isVoiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_VOICE_ENABLED, true)
    }

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    fun isRootMode(): Boolean {
        return prefs.getBoolean(KEY_ROOT_MODE, false)
    }

    fun setRootMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT_MODE, enabled).apply()
    }

    fun getCustomCommands(): MutableMap<String, String> {
        val json = prefs.getString(KEY_CUSTOM_COMMANDS, null) ?: return mutableMapOf()
        return try {
            val entries = json.split("|||")
            val map = mutableMapOf<String, String>()
            for (entry in entries) {
                val parts = entry.split(":::")
                if (parts.size == 2) {
                    map[parts[0]] = parts[1]
                }
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun setCustomCommands(commands: Map<String, String>) {
        val json = commands.entries.joinToString("|||") { "${it.key}:::${it.value}" }
        prefs.edit().putString(KEY_CUSTOM_COMMANDS, json).apply()
    }

    fun addCustomCommand(trigger: String, action: String) {
        val commands = getCustomCommands()
        commands[trigger.lowercase()] = action
        setCustomCommands(commands)
    }

    fun removeCustomCommand(trigger: String) {
        val commands = getCustomCommands()
        commands.remove(trigger.lowercase())
        setCustomCommands(commands)
    }
}
