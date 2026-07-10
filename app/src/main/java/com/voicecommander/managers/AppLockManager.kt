package com.voicecommander.managers

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader

class AppLockManager(private val context: Context) {

    private val pinManager = PinManager(context)

    companion object {
        val GOOGLE_ASSISTANT_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.searchlite",
            "com.google.android.apps.bard"
        )
    }

    /**
     * Detect which Google Assistant variant is installed
     */
    fun detectAssistant(): String? {
        val pm = context.packageManager
        for (pkg in GOOGLE_ASSISTANT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // not installed
            }
        }
        return null
    }

    fun getAssistantName(packageName: String?): String {
        return when (packageName) {
            "com.google.android.googlequicksearchbox" -> "Google Assistant"
            "com.google.android.apps.searchlite" -> "Google Assistant Go"
            "com.google.android.apps.bard" -> "Gemini"
            else -> "None"
        }
    }

    /**
     * Get all installed launchable apps
     */
    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolveInfos
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                AppInfo(
                    packageName = ri.activityInfo.packageName,
                    appName = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm),
                    isSystemApp = (ri.activityInfo.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    /**
     * Force stop an app - works on rooted devices
     */
    fun forceStopApp(packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am", "force-stop", packageName)).waitFor() == 0
        } catch (e: Exception) {
            try {
                Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName)).waitFor() == 0
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Clear app cache - works on rooted devices
     */
    fun clearAppCache(packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pm", "clear", packageName)).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Disable an app - works on rooted or with ADB
     */
    fun disableApp(packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        return try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "pm", "disable-user", "--user", "0", packageName)
            ).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Re-enable a previously disabled app
     */
    fun enableApp(packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        return try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "pm", "enable", packageName)
            ).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lock an app: force stop + clear cache + optionally disable
     */
    fun lockApp(packageName: String): Boolean {
        pinManager.addLockedApp(packageName)

        val forceStopped = forceStopApp(packageName)
        val cacheCleared = clearAppCache(packageName)

        return forceStopped || cacheCleared
    }

    /**
     * Unlock an app: re-enable if disabled
     */
    fun unlockApp(packageName: String): Boolean {
        pinManager.removeLockedApp(packageName)
        return enableApp(packageName)
    }

    /**
     * Close/kill a running app without adding it to the locked list
     */
    fun closeApp(packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        
        // Try to kill the app process
        val killed = forceStopApp(packageName)
        
        // Also try to clear from recents using shell command
        try {
            Runtime.getRuntime().exec(arrayOf("am", "kill", packageName)).waitFor()
        } catch (e: Exception) {
            // Ignore errors
        }
        
        return killed
    }

    /**
     * Get recently used apps (last N apps)
     */
    fun getRecentApps(limit: Int = 5): List<AppInfo> {
        val pm = context.packageManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 1000 * 60 * 60, // last hour
            now
        )
        
        return stats?.sortedByDescending { it.lastTimeUsed }
            ?.take(limit)
            ?.mapNotNull { stat ->
                try {
                    val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(stat.packageName)
                    AppInfo(
                        packageName = stat.packageName,
                        appName = appName,
                        icon = icon,
                        isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
    }

    /**
     * Check if root access is available
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor() == 0 && output == "root"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if usage stats permission is granted
     */
    fun hasUsageStatsPermission(): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1000, now)
        return stats != null && stats.isNotEmpty()
    }

    fun openUsageStatsSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun isValidPackageName(packageName: String): Boolean {
        return packageName.matches(Regex("^[a-zA-Z0-9._]+$"))
    }

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: android.graphics.drawable.Drawable?,
        val isSystemApp: Boolean
    )
}
