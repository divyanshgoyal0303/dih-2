package com.voicecommander.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.voicecommander.managers.AppLockManager
import com.voicecommander.managers.PinManager
import com.voicecommander.ui.PinUnlockActivity

class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppLockService"
        var instance: AppLockAccessibilityService? = null
            private set
    }

    // Instance callback for foreground app changes (avoids static leak)
    private var foregroundAppCallback: ((String?) -> Unit)? = null

    private lateinit var pinManager: PinManager
    private lateinit var appLockManager: AppLockManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockedPackage: String? = null
    private var lastBlockTime: Long = 0
    private var currentForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pinManager = PinManager(this)
        appLockManager = AppLockManager(this)
        Log.d(TAG, "Accessibility service connected")
    }

    /**
     * Set callback for foreground app changes (instance-based, no leak)
     */
    fun setForegroundAppCallback(callback: (String?) -> Unit) {
        foregroundAppCallback = callback
    }

    fun clearForegroundAppCallback() {
        foregroundAppCallback = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return

                // Update current foreground app
                if (currentForegroundPackage != packageName) {
                    currentForegroundPackage = packageName
                    foregroundAppCallback?.invoke(packageName)
                    Log.d(TAG, "Foreground app changed: $packageName")
                }

                // Don't block our own app or the system
                if (packageName == this.packageName) return
                if (packageName == "com.android.systemui") return
                if (packageName == "com.android.settings") return
                if (packageName == "com.android.launcher" ||
                    packageName.contains("launcher")) return

                // Prevent rapid re-blocking
                val now = System.currentTimeMillis()
                if (packageName == lastBlockedPackage && now - lastBlockTime < 2000) {
                    return
                }

                if (pinManager.isAppLocked(packageName)) {
                    Log.d(TAG, "Blocked locked app: $packageName")
                    lastBlockedPackage = packageName
                    lastBlockTime = now

                    // Launch our unlock screen
                    val intent = Intent(this, PinUnlockActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("locked_package", packageName)
                    }
                    startActivity(intent)

                    // Try to force stop the locked app in background
                    try {
                        appLockManager.forceStopApp(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error force-stopping app", e)
                    }
                }
            }
        }
    }

    /**
     * Get the current foreground package name
     */
    fun getCurrentForegroundPackage(): String? = currentForegroundPackage

    /**
     * Close/kill the specified app using non-blocking approach
     */
    fun closeApp(packageName: String): Boolean {
        Log.d(TAG, "Closing app: $packageName")

        // First, try to force stop the app via shell
        val forceStopped = appLockManager.closeApp(packageName)

        // If the app is currently in foreground, navigate away using handler (non-blocking)
        if (currentForegroundPackage == packageName) {
            // Go to home screen immediately (no Thread.sleep needed)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Also schedule back actions in case home doesn't work immediately
            handler.postDelayed({
                if (currentForegroundPackage == packageName) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, 150)

            handler.postDelayed({
                if (currentForegroundPackage == packageName) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, 300)
        }

        return forceStopped
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundAppCallback = null
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }
}
