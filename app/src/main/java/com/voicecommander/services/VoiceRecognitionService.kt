package com.voicecommander.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voicecommander.R
import com.voicecommander.VoiceCommanderApp
import com.voicecommander.managers.PinManager
import com.voicecommander.managers.VoiceCommandManager
import com.voicecommander.ui.MainActivity
import kotlinx.coroutines.*

class VoiceRecognitionService : Service() {

    companion object {
        private const val TAG = "VoiceRecognitionService"
        private const val NOTIFICATION_ID = 1001
        private const val LISTENING_PAUSE_MS = 1500L

        private var isRunning = false
        private var listeningEnabled = true

        fun isServiceRunning(): Boolean = isRunning

        fun setListeningEnabled(enabled: Boolean) {
            listeningEnabled = enabled
        }

        fun isListeningEnabled(): Boolean = listeningEnabled
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var pinManager: PinManager
    private lateinit var commandManager: VoiceCommandManager
    private var commandCallback: ((VoiceCommandManager.CommandResult) -> Unit)? = null

    private var lastSpokenText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pinManager = PinManager(this)
        commandManager = VoiceCommandManager(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START" -> {
                startForeground(NOTIFICATION_ID, createNotification("Listening for commands..."))
                startListening()
            }
            "ACTION_STOP" -> {
                stopListening()
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                stopSelf()
            }
            "ACTION_TOGGLE" -> {
                if (listeningEnabled) {
                    listeningEnabled = false
                    stopListening()
                    updateNotification("Voice recognition paused")
                } else {
                    listeningEnabled = true
                    startListening()
                    updateNotification("Listening for commands...")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopListening()
        serviceScope.cancel()
    }

    private fun startListening() {
        if (!listeningEnabled) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognizer", e)
            scheduleRestart()
        }
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
                updateNotification("Listening...")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                isListening = false
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Speech error: $error")
                isListening = false

                if (listeningEnabled) {
                    scheduleRestart()
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    Log.d(TAG, "Recognized: $spokenText")
                    processSpokenText(spokenText)
                }

                if (listeningEnabled) {
                    scheduleRestart()
                }
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    lastSpokenText = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
    }

    private fun processSpokenText(text: String) {
        if (text.isBlank()) return

        val result = commandManager.processCommand(text)

        when (result.action) {
            "stop_listening" -> {
                listeningEnabled = false
                updateNotification("Voice recognition paused — say 'start listening' to resume")
            }
            "start_listening" -> {
                listeningEnabled = true
                updateNotification("Listening for commands...")
            }
        }

        commandCallback?.invoke(result)
    }

    private fun scheduleRestart() {
        serviceScope.launch {
            delay(LISTENING_PAUSE_MS)
            if (listeningEnabled && isRunning) {
                startListening()
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VoiceCommanderApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VoiceCommander")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun setCommandCallback(callback: (VoiceCommandManager.CommandResult) -> Unit) {
        commandCallback = callback
    }

    fun getCommandManager(): VoiceCommandManager = commandManager
}
