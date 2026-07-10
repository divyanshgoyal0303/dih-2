package com.voicecommander.managers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class VoiceCommandManager(private val context: Context) {

    private val pinManager = PinManager(context)
    private val appLockManager = AppLockManager(context)
    private val gson = Gson()

    data class CommandResult(
        val success: Boolean,
        val message: String,
        val action: String = ""
    )

    data class CommandHistoryEntry(
        val timestamp: Long,
        val spokenText: String,
        val recognizedCommand: String,
        val result: String,
        val success: Boolean
    )

    private val commandHistory = mutableListOf<CommandHistoryEntry>()

    companion object {
        // Close app patterns - force stop and close the app (does NOT lock it)
        val CLOSE_PATTERNS = listOf(
            "close (?:the )?(?:app )?(.+)",
            "shut (?:down )?(?:the )?(?:app )?(.+)",
            "kill (?:the )?(?:app )?(.+)",
            "exit (?:the )?(?:app )?(.+)",
            "quit (?:the )?(?:app )?(.+)"
        )

        // Lock app patterns - close + add to locked list (blocks reopening)
        val LOCK_PATTERNS = listOf(
            "lock (?:the )?(?:app )?(.+)",
            "disable (?:the )?(?:app )?(.+)"
        )
        val UNLOCK_PATTERNS = listOf(
            "unlock (?:the )?(?:app )?(.+)",
            "enable (?:the )?(?:app )?(.+)"
        )
        val STATUS_PATTERNS = listOf(
            "what(?:'s| is) (?:the )?status",
            "list locked",
            "show locked",
            "locked apps"
        )
        val FOREGROUND_PATTERNS = listOf(
            "what(?:'s| is) (?:this|that|the current|the open) (?:app)?",
            "which app",
            "what am i (?:in|using|running)",
            "current app",
            "foreground app"
        )
        val STOP_LISTENING = listOf(
            "stop listening",
            "stop voice",
            "pause listening",
            "quiet",
            "shut up"
        )
        val START_LISTENING = listOf(
            "start listening",
            "start voice",
            "resume listening",
            "wake up",
            "hey commander"
        )
        val HELP_PATTERNS = listOf(
            "help",
            "what can you do",
            "commands",
            "what are the commands"
        )
    }

    /**
     * Process recognized speech text and execute the appropriate command
     */
    fun processCommand(spokenText: String): CommandResult {
        val text = spokenText.lowercase().trim()

        // Check custom commands first
        val customResult = checkCustomCommands(text)
        if (customResult != null) {
            recordHistory(spokenText, "custom", customResult.message, customResult.success)
            return customResult
        }

        // Check CLOSE app commands first (before lock)
        for (pattern in CLOSE_PATTERNS) {
            val match = Regex(pattern).find(text)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                return executeCloseCommand(spokenText, appName)
            }
        }

        // Check LOCK app commands
        for (pattern in LOCK_PATTERNS) {
            val match = Regex(pattern).find(text)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                return executeLockCommand(spokenText, appName)
            }
        }

        for (pattern in UNLOCK_PATTERNS) {
            val match = Regex(pattern).find(text)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                return executeUnlockCommand(spokenText, appName)
            }
        }

        for (pattern in STATUS_PATTERNS) {
            if (Regex(pattern).containsMatchIn(text)) {
                return executeStatusCommand(spokenText)
            }
        }

        for (pattern in FOREGROUND_PATTERNS) {
            if (Regex(pattern).containsMatchIn(text)) {
                return executeForegroundCommand(spokenText)
            }
        }

        for (pattern in STOP_LISTENING) {
            if (text.contains(pattern)) {
                val result = CommandResult(true, "Voice recognition paused", "stop_listening")
                recordHistory(spokenText, "stop", result.message, true)
                return result
            }
        }

        for (pattern in START_LISTENING) {
            if (text.contains(pattern)) {
                val result = CommandResult(true, "Voice recognition resumed", "start_listening")
                recordHistory(spokenText, "start", result.message, true)
                return result
            }
        }

        for (pattern in HELP_PATTERNS) {
            if (text.contains(pattern)) {
                return executeHelpCommand(spokenText)
            }
        }

        val result = CommandResult(false, "Command not recognized: $spokenText")
        recordHistory(spokenText, "unknown", result.message, false)
        return result
    }

    private fun findAppByName(appName: String): AppLockManager.AppInfo? {
        val apps = appLockManager.getInstalledApps()
        return apps.find {
            it.appName.equals(appName, ignoreCase = true) ||
                    it.appName.lowercase().contains(appName.lowercase()) ||
                    it.packageName.lowercase().contains(appName.lowercase())
        }
    }

    /**
     * Execute close command - force stops the app but does NOT add to lock list
     */
    private fun executeCloseCommand(spokenText: String, appName: String): CommandResult {
        // Special case: "close app" without a specific name = close the current foreground app
        if (appName.isBlank() || appName == "app" || appName == "this app" || appName == "it") {
            val accessibilityService = AppLockAccessibilityService.instance
            val foregroundPkg = accessibilityService?.getCurrentForegroundPackage()
            if (foregroundPkg != null) {
                val pm = context.packageManager
                val appLabel = try {
                    val appInfo = pm.getApplicationInfo(foregroundPkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    foregroundPkg
                }
                val success = accessibilityService?.closeApp(foregroundPkg) ?: appLockManager.closeApp(foregroundPkg)
                val message = if (success) {
                    "Closed $appLabel"
                } else {
                    "Attempted to close $appLabel"
                }
                val result = CommandResult(success, message, "close")
                recordHistory(spokenText, "close $appLabel", message, success)
                return result
            } else {
                val result = CommandResult(false, "No foreground app detected. Make sure accessibility service is enabled.")
                recordHistory(spokenText, "close", result.message, false)
                return result
            }
        }

        val matchedApp = findAppByName(appName)
        if (matchedApp == null) {
            val result = CommandResult(false, "App not found: $appName")
            recordHistory(spokenText, "close", result.message, false)
            return result
        }

        // Try accessibility service first (non-blocking), then fall back to shell
        val accessibilityService = AppLockAccessibilityService.instance
        val success = if (accessibilityService != null) {
            accessibilityService.closeApp(matchedApp.packageName)
        } else {
            appLockManager.closeApp(matchedApp.packageName)
        }

        val message = if (success) {
            "Closed ${matchedApp.appName}"
        } else {
            "Attempted to close ${matchedApp.appName}"
        }

        val result = CommandResult(success, message, "close")
        recordHistory(spokenText, "close ${matchedApp.appName}", message, success)
        return result
    }

    /**
     * Execute lock command - force stops AND adds to locked list (blocks reopening)
     */
    private fun executeLockCommand(spokenText: String, appName: String): CommandResult {
        val matchedApp = findAppByName(appName)

        if (matchedApp == null) {
            val result = CommandResult(false, "App not found: $appName")
            recordHistory(spokenText, "lock", result.message, false)
            return result
        }

        // First close the app, then lock it
        val accessibilityService = AppLockAccessibilityService.instance
        accessibilityService?.closeApp(matchedApp.packageName)
        val success = appLockManager.lockApp(matchedApp.packageName)

        val message = if (success) {
            "Locked ${matchedApp.appName} — app stopped and will be blocked"
        } else {
            "Attempted to lock ${matchedApp.appName} (may need root for full effect)"
        }

        val result = CommandResult(success, message, "lock")
        recordHistory(spokenText, "lock ${matchedApp.appName}", message, success)
        return result
    }

    /**
     * Execute foreground command - show what app is currently open
     */
    private fun executeForegroundCommand(spokenText: String): CommandResult {
        val accessibilityService = AppLockAccessibilityService.instance
        val foregroundPkg = accessibilityService?.getCurrentForegroundPackage()

        return if (foregroundPkg != null) {
            val pm = context.packageManager
            val appLabel = try {
                val appInfo = pm.getApplicationInfo(foregroundPkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                foregroundPkg
            }
            val result = CommandResult(true, "Current app: $appLabel ($foregroundPkg)", "foreground")
            recordHistory(spokenText, "foreground", result.message, true)
            result
        } else {
            val result = CommandResult(false, "No foreground app detected. Make sure accessibility service is enabled.")
            recordHistory(spokenText, "foreground", result.message, false)
            result
        }
    }

    private fun executeUnlockCommand(spokenText: String, appName: String): CommandResult {
        val apps = appLockManager.getInstalledApps()
        val matchedApp = apps.find {
            it.appName.equals(appName, ignoreCase = true) ||
                    it.appName.lowercase().contains(appName.lowercase()) ||
                    it.packageName.lowercase().contains(appName.lowercase())
        }

        if (matchedApp == null) {
            val result = CommandResult(false, "App not found: $appName")
            recordHistory(spokenText, "unlock", result.message, false)
            return result
        }

        val success = appLockManager.unlockApp(matchedApp.packageName)
        val message = if (success) {
            "Unlocked ${matchedApp.appName} — re-enabled and removed from lock list"
        } else {
            "Removed ${matchedApp.appName} from lock list"
        }

        val result = CommandResult(success, message, "unlock")
        recordHistory(spokenText, "unlock ${matchedApp.appName}", message, success)
        return result
    }

    private fun executeStatusCommand(spokenText: String): CommandResult {
        val lockedApps = pinManager.getLockedApps()
        val message = if (lockedApps.isEmpty()) {
            "No apps are currently locked"
        } else {
            "Locked apps: ${lockedApps.joinToString(", ")}"
        }

        val result = CommandResult(true, message, "status")
        recordHistory(spokenText, "status", message, true)
        return result
    }

    private fun executeHelpCommand(spokenText: String): CommandResult {
        val helpText = """
            Available commands:
            • "Close [app name]" — Force stop and close an app
            • "Close app" — Close the current foreground app
            • "Lock [app name]" — Close and lock an app (blocks reopening)
            • "Unlock [app name]" — Unlock a locked app
            • "What's this app" — Show the current foreground app
            • "What's status" — Show locked apps
            • "Stop listening" — Pause voice recognition
            • "Start listening" — Resume voice recognition
            • "Help" — Show this message
            • Custom commands you've configured
        """.trimIndent()

        val result = CommandResult(true, helpText, "help")
        recordHistory(spokenText, "help", helpText, true)
        return result
    }

    private fun checkCustomCommands(text: String): CommandResult? {
        val customCommands = pinManager.getCustomCommands()
        for ((trigger, action) in customCommands) {
            if (text.contains(trigger)) {
                val result = executeCustomAction(action)
                recordHistory(text, "custom:$trigger", result.message, result.success)
                return result
            }
        }
        return null
    }

    private fun executeCustomAction(action: String): CommandResult {
        val parts = action.split(":", limit = 2)
        if (parts.size < 2) {
            return CommandResult(false, "Invalid custom action format")
        }

        val actionType = parts[0].trim().lowercase()
        val actionParam = parts[1].trim()

        return when (actionType) {
            "lock" -> {
                val apps = appLockManager.getInstalledApps()
                val matched = apps.find {
                    it.appName.equals(actionParam, ignoreCase = true) ||
                            it.packageName.equals(actionParam, ignoreCase = true)
                }
                if (matched != null) {
                    val success = appLockManager.lockApp(matched.packageName)
                    CommandResult(success, if (success) "Custom: Locked ${matched.appName}" else "Failed to lock ${matched.appName}", "lock")
                } else {
                    CommandResult(false, "App not found for custom command: $actionParam")
                }
            }
            "unlock" -> {
                val apps = appLockManager.getInstalledApps()
                val matched = apps.find {
                    it.appName.equals(actionParam, ignoreCase = true) ||
                            it.packageName.equals(actionParam, ignoreCase = true)
                }
                if (matched != null) {
                    val success = appLockManager.unlockApp(matched.packageName)
                    CommandResult(success, if (success) "Custom: Unlocked ${matched.appName}" else "Failed to unlock ${matched.appName}", "unlock")
                } else {
                    CommandResult(false, "App not found for custom command: $actionParam")
                }
            }
            "disable" -> {
                val apps = appLockManager.getInstalledApps()
                val matched = apps.find {
                    it.appName.equals(actionParam, ignoreCase = true)
                }
                if (matched != null) {
                    val success = appLockManager.disableApp(matched.packageName)
                    CommandResult(success, if (success) "Disabled ${matched.appName}" else "Failed to disable ${matched.appName}", "disable")
                } else {
                    CommandResult(false, "App not found: $actionParam")
                }
            }
            "message" -> CommandResult(true, actionParam, "message")
            else -> CommandResult(false, "Unknown action type: $actionType")
        }
    }

    private fun recordHistory(spokenText: String, command: String, result: String, success: Boolean) {
        commandHistory.add(
            CommandHistoryEntry(
                timestamp = System.currentTimeMillis(),
                spokenText = spokenText,
                recognizedCommand = command,
                result = result,
                success = success
            )
        )
        // Keep only last 100 entries
        if (commandHistory.size > 100) {
            commandHistory.removeAt(0)
        }
    }

    fun getHistory(): List<CommandHistoryEntry> = commandHistory.toList()

    fun addCustomCommand(trigger: String, action: String) {
        pinManager.addCustomCommand(trigger, action)
    }

    fun removeCustomCommand(trigger: String) {
        pinManager.removeCustomCommand(trigger)
    }

    fun getCustomCommands(): Map<String, String> {
        return pinManager.getCustomCommands()
    }
}
