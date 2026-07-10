package com.voicecommander.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.voicecommander.managers.PinManager
import com.voicecommander.services.VoiceRecognitionService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val pinManager = PinManager(context)
            if (pinManager.isVoiceEnabled() && pinManager.getLockedApps().isNotEmpty()) {
                // Auto-restart voice service on boot if there are locked apps
                val serviceIntent = Intent(context, VoiceRecognitionService::class.java).apply {
                    action = "ACTION_START"
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
