package com.voicecommander.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voicecommander.R
import com.voicecommander.databinding.ActivityMainBinding
import com.voicecommander.managers.AppLockManager
import com.voicecommander.managers.PinManager
import com.voicecommander.managers.VoiceCommandManager
import com.voicecommander.services.AppLockAccessibilityService
import com.voicecommander.services.VoiceRecognitionService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pinManager: PinManager
    private lateinit var appLockManager: AppLockManager
    private lateinit var commandManager: VoiceCommandManager

    private var currentForegroundPackage: String? = null
    private var currentForegroundAppName: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startVoiceService()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice commands", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pinManager = PinManager(this)
        appLockManager = AppLockManager(this)
        commandManager = VoiceCommandManager(this)

        // Check PIN setup
        if (!pinManager.isPinSet()) {
            showPinSetupDialog()
        }

        setupUI()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        updateAssistantInfo()
        setupForegroundAppTracking()
    }

    override fun onPause() {
        super.onPause()
        // Clear callback to avoid leaks
        AppLockAccessibilityService.instance?.clearForegroundAppCallback()
    }

    private fun setupUI() {
        // Voice toggle button
        binding.btnToggleVoice.setOnClickListener {
            if (VoiceRecognitionService.isServiceRunning()) {
                stopVoiceService()
            } else {
                checkPermissionsAndStart()
            }
            updateUI()
        }

        // Close current app button
        binding.btnCloseCurrentApp.setOnClickListener {
            closeCurrentForegroundApp()
        }

        // Manage locked apps button
        binding.btnManageLockedApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        // Command history button
        binding.btnCommandHistory.setOnClickListener {
            startActivity(Intent(this, CommandHistoryActivity::class.java))
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Change PIN button
        binding.btnChangePin.setOnClickListener {
            startActivity(Intent(this, PinSetupActivity::class.java))
        }

        // Help button
        binding.btnHelp.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun setupForegroundAppTracking() {
        val accessibilityService = AppLockAccessibilityService.instance

        if (accessibilityService != null) {
            // Set callback for foreground app changes
            accessibilityService.setForegroundAppCallback { packageName ->
                runOnUiThread {
                    updateForegroundApp(packageName)
                }
            }

            // Get current foreground app immediately
            val currentPkg = accessibilityService.getCurrentForegroundPackage()
            updateForegroundApp(currentPkg)
        } else {
            updateForegroundApp(null)
            binding.tvCurrentAppName.text = "Accessibility service not enabled"
            binding.tvCurrentAppPackage.text = "Enable it in Settings > Accessibility"
        }
    }

    private fun updateForegroundApp(packageName: String?) {
        currentForegroundPackage = packageName

        if (packageName == null || packageName == this.packageName) {
            binding.tvCurrentAppName.text = "No external app detected"
            binding.tvCurrentAppPackage.text = ""
            binding.btnCloseCurrentApp.isEnabled = false
            return
        }

        // Filter out system apps and launchers
        if (packageName == "com.android.systemui" ||
            packageName == "com.android.settings" ||
            packageName.contains("launcher")) {
            binding.tvCurrentAppName.text = "System / Home screen"
            binding.tvCurrentAppPackage.text = packageName
            binding.btnCloseCurrentApp.isEnabled = false
            return
        }

        // Get app label
        val pm = packageManager
        val appLabel = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        currentForegroundAppName = appLabel
        binding.tvCurrentAppName.text = appLabel
        binding.tvCurrentAppPackage.text = packageName
        binding.btnCloseCurrentApp.isEnabled = true
    }

    private fun closeCurrentForegroundApp() {
        val packageName = currentForegroundPackage ?: return
        val appName = currentForegroundAppName ?: packageName

        val accessibilityService = AppLockAccessibilityService.instance
        val success = if (accessibilityService != null) {
            accessibilityService.closeApp(packageName)
        } else {
            appLockManager.closeApp(packageName)
        }

        val message = if (success) {
            "Closed $appName"
        } else {
            "Attempted to close $appName"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val isRunning = VoiceRecognitionService.isServiceRunning()
        val isListening = VoiceRecognitionService.isListeningEnabled()

        binding.tvVoiceStatus.text = when {
            !isRunning -> "Voice recognition: OFF"
            isListening -> "Voice recognition: LISTENING"
            else -> "Voice recognition: PAUSED"
        }

        binding.btnToggleVoice.text = when {
            !isRunning -> "Start Voice Recognition"
            isListening -> "Stop Listening"
            else -> "Resume Listening"
        }

        // Show locked apps count
        val lockedCount = pinManager.getLockedApps().size
        binding.tvLockedCount.text = "$lockedCount app(s) locked"
    }

    private fun updateAssistantInfo() {
        val assistantPkg = appLockManager.detectAssistant()
        val assistantName = appLockManager.getAssistantName(assistantPkg)
        binding.tvAssistantInfo.text = "Detected: $assistantName"

        if (assistantPkg == null) {
            binding.tvAssistantWarning.visibility = android.view.View.VISIBLE
            binding.tvAssistantWarning.text = "No Google Assistant/Gemini detected. Voice recognition will use built-in Android speech."
        } else {
            binding.tvAssistantWarning.visibility = android.view.View.GONE
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startVoiceService()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceRecognitionService::class.java).apply {
            action = "ACTION_START"
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI()
        Toast.makeText(this, "Voice recognition started", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceRecognitionService::class.java).apply {
            action = "ACTION_STOP"
        }
        startService(intent)
        updateUI()
        Toast.makeText(this, "Voice recognition stopped", Toast.LENGTH_SHORT).show()
    }

    private fun showPinSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Set Up PIN")
            .setMessage("You need to set up a PIN to protect your locked apps. This PIN will be required to unlock any app you lock.")
            .setPositiveButton("Set PIN") { _, _ ->
                startActivity(Intent(this, PinSetupActivity::class.java))
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun showHelpDialog() {
        val helpText = """
            VoiceCommander - Voice Controlled App Closer

            VOICE COMMANDS:
            • "Close [app name]" — Force stop and close an app
            • "Close app" — Close the current foreground app
            • "Kill [app name]" — Same as close
            • "Lock [app name]" — Close and lock an app (blocks reopening)
            • "Unlock [app name]" — Unlock a locked app (requires PIN)
            • "What's this app" — Show the current foreground app
            • "What's status" — List locked apps
            • "Stop listening" — Pause voice recognition
            • "Start listening" — Resume voice recognition
            • "Help" — Show commands

            FEATURES:
            • Detects Google Assistant, Assistant Go, or Gemini
            • Falls back to built-in Android speech recognition
            • Closes running apps via Accessibility Service
            • Locks apps to prevent reopening (requires PIN to unlock)
            • Custom voice commands

            SETUP:
            1. Set your PIN in Settings
            2. Enable Accessibility Service for app closing
            3. Grant microphone permission
            4. Start voice recognition
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Help")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .show()
    }
}
