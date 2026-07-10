package com.voicecommander.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.voicecommander.databinding.ActivityPinUnlockBinding
import com.voicecommander.managers.AppLockManager
import com.voicecommander.managers.PinManager

class PinUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinUnlockBinding
    private lateinit var pinManager: PinManager
    private lateinit var appLockManager: AppLockManager
    private var lockedPackage: String? = null
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pinManager = PinManager(this)
        appLockManager = AppLockManager(this)

        lockedPackage = intent.getStringExtra("locked_package")

        // Show which app is locked
        val appName = getAppName(lockedPackage)
        binding.tvTitle.text = "App Locked"
        binding.tvSubtitle.text = "$appName is locked. Enter PIN to unlock."

        setupUI()
    }

    private fun setupUI() {
        binding.btnUnlock.setOnClickListener {
            val pin = binding.etPin.text.toString()

            if (pinManager.verifyPin(pin)) {
                // Correct PIN - unlock the app
                lockedPackage?.let { pkg ->
                    appLockManager.unlockApp(pkg)
                    val appName = getAppName(pkg)
                    Toast.makeText(this, "$appName unlocked!", Toast.LENGTH_SHORT).show()

                    // Try to re-launch the app
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        // App may have been fully disabled
                    }
                }
                finish()
            } else {
                attempts++
                binding.etPin.text?.clear()

                if (attempts >= 5) {
                    Toast.makeText(this, "Too many attempts. Try again later.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Incorrect PIN (${5 - attempts} attempts remaining)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun getAppName(packageName: String?): String {
        if (packageName == null) return "Unknown"
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onBackPressed() {
        // Don't allow going back without entering PIN
        // Just minimize instead
        moveTaskToBack(true)
    }
}
