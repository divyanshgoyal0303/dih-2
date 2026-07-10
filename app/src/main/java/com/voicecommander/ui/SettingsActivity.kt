package com.voicecommander.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.voicecommander.R
import com.voicecommander.databinding.ActivitySettingsBinding
import com.voicecommander.managers.AppLockManager
import com.voicecommander.managers.PinManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var pinManager: PinManager
    private lateinit var appLockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pinManager = PinManager(this)
        appLockManager = AppLockManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupUI()
    }

    private fun setupUI() {
        // Root mode toggle
        binding.switchRootMode.isChecked = pinManager.isRootMode()
        binding.switchRootMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !appLockManager.isRootAvailable()) {
                Toast.makeText(this, "Root access not detected on this device", Toast.LENGTH_LONG).show()
                binding.switchRootMode.isChecked = false
                return@setOnCheckedChangeListener
            }
            pinManager.setRootMode(isChecked)
        }

        // Accessibility Service status
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled) "Enabled ✓" else "Disabled ✗"
        binding.btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Usage Stats permission
        val hasUsageStats = appLockManager.hasUsageStatsPermission()
        binding.tvUsageStatsStatus.text = if (hasUsageStats) "Granted ✓" else "Not Granted ✗"
        binding.btnUsageStats.setOnClickListener {
            appLockManager.openUsageStatsSettings()
        }

        // Change PIN
        binding.btnChangePin.setOnClickListener {
            startActivity(Intent(this, PinSetupActivity::class.java))
        }

        // Add custom command
        binding.btnAddCustomCommand.setOnClickListener {
            showAddCustomCommandDialog()
        }

        // View custom commands
        binding.btnViewCustomCommands.setOnClickListener {
            showCustomCommandsDialog()
        }

        // Clear all locked apps
        binding.btnClearAllLocked.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Unlock All Apps?")
                .setMessage("This will remove all apps from the lock list. They will need to be re-enabled if they were disabled.")
                .setPositiveButton("Unlock All") { _, _ ->
                    val lockedApps = pinManager.getLockedApps().toSet()
                    for (pkg in lockedApps) {
                        appLockManager.unlockApp(pkg)
                    }
                    pinManager.setLockedApps(emptySet())
                    Toast.makeText(this, "All apps unlocked", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showAddCustomCommandDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val triggerInput = EditText(this).apply {
            hint = "Voice trigger (e.g. 'block youtube')"
            setPadding(0, 16, 0, 16)
        }

        val actionInput = EditText(this).apply {
            hint = "Action (e.g. 'lock:YouTube')"
            setPadding(0, 16, 0, 16)
        }

        val helpText = android.widget.TextView(this).apply {
            text = "Actions:\n• lock:AppName — Lock an app\n• unlock:AppName — Unlock an app\n• disable:AppName — Disable an app\n• message:Text — Show a message"
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }

        layout.addView(triggerInput)
        layout.addView(actionInput)
        layout.addView(helpText)

        AlertDialog.Builder(this)
            .setTitle("Add Custom Command")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val trigger = triggerInput.text.toString().trim()
                val action = actionInput.text.toString().trim()

                if (trigger.isNotEmpty() && action.isNotEmpty()) {
                    pinManager.addCustomCommand(trigger, action)
                    Toast.makeText(this, "Custom command added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomCommandsDialog() {
        val commands = pinManager.getCustomCommands()
        if (commands.isEmpty()) {
            Toast.makeText(this, "No custom commands yet", Toast.LENGTH_SHORT).show()
            return
        }

        val items = commands.map { "\"${it.key}\" → ${it.value}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Custom Commands")
            .setItems(items) { _, which ->
                val trigger = commands.keys.elementAt(which)
                AlertDialog.Builder(this)
                    .setTitle("Delete Command?")
                    .setMessage("Remove \"$trigger\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        pinManager.removeCustomCommand(trigger)
                        Toast.makeText(this, "Command deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${com.voicecommander.services.AppLockAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    override fun onResume() {
        super.onResume()
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled) "Enabled ✓" else "Disabled ✗"

        val hasUsageStats = appLockManager.hasUsageStatsPermission()
        binding.tvUsageStatsStatus.text = if (hasUsageStats) "Granted ✓" else "Not Granted ✗"
    }
}
