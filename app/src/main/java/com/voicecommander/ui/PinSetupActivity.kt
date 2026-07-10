package com.voicecommander.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.voicecommander.databinding.ActivityPinSetupBinding
import com.voicecommander.managers.PinManager

class PinSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinSetupBinding
    private lateinit var pinManager: PinManager
    private var isFirstStep = true
    private var firstPin = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pinManager = PinManager(this)

        if (pinManager.isPinSet()) {
            binding.tvTitle.text = "Change PIN"
            binding.tvSubtitle.text = "Enter your current PIN first"
        }

        setupUI()
    }

    private fun setupUI() {
        binding.btnConfirm.setOnClickListener {
            val pin = binding.etPin.text.toString()

            if (pin.length < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pinManager.isPinSet() && isFirstStep) {
                // Verify current PIN
                if (pinManager.verifyPin(pin)) {
                    isFirstStep = false
                    firstPin = ""
                    binding.etPin.text?.clear()
                    binding.tvTitle.text = "Enter New PIN"
                    binding.tvSubtitle.text = "Choose a new PIN (4-8 digits)"
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            } else if (isFirstStep) {
                // First time setup
                firstPin = pin
                isFirstStep = false
                binding.etPin.text?.clear()
                binding.tvTitle.text = "Confirm PIN"
                binding.tvSubtitle.text = "Re-enter your PIN"
            } else {
                // Confirm PIN
                if (pin == firstPin || !pinManager.isPinSet()) {
                    pinManager.setPin(pin)
                    Toast.makeText(this, "PIN set successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "PINs don't match", Toast.LENGTH_SHORT).show()
                    isFirstStep = true
                    firstPin = ""
                    binding.etPin.text?.clear()
                    binding.tvTitle.text = if (pinManager.isPinSet()) "Enter Current PIN" else "Set PIN"
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
}
