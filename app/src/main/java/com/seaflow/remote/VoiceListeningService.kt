package com.seaflow.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceListeningService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var restarting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SEAFLOW voice control is active")
                .setContentText("Listening for your TV nickname and commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SEAFLOW voice control is active")
                .setContentText("Listening for your TV nickname and commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit(ACTION_ERROR, "Speech recognition is not available on this device.")
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        startRecognition()
    }

    private fun startRecognition() {
        if (listening || !SpeechRecognizer.isRecognitionAvailable(this)) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (error: Exception) {
            listening = false
            emit(ACTION_ERROR, error.localizedMessage ?: "Could not start listening.")
            scheduleRestart()
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!phrase.isNullOrBlank()) emit(ACTION_HEARD, phrase)
        scheduleRestart()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!phrase.isNullOrBlank()) emit(ACTION_PARTIAL, phrase)
    }

    override fun onError(error: Int) {
        listening = false
        if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            emit(ACTION_ERROR, "Listening paused (speech error $error); retrying.")
        }
        scheduleRestart()
    }

    private fun scheduleRestart() {
        if (restarting) return
        restarting = true
        android.os.Handler(mainLooper).postDelayed({
            restarting = false
            if (listening) {
                try { recognizer?.cancel() } catch (_: Exception) { }
                listening = false
            }
            if (recognizer != null) startRecognition()
        }, 650)
    }

    private fun emit(action: String, value: String) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_TEXT, value))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SEAFLOW voice control", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onReadyForSpeech(params: Bundle?) { emit(ACTION_STATE, "Listening") }
    override fun onBeginningOfSpeech() { emit(ACTION_STATE, "Hearing speech") }
    override fun onEndOfSpeech() { listening = false; emit(ACTION_STATE, "Processing speech") }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        listening = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HEARD = "com.seaflow.remote.VOICE_HEARD"
        const val ACTION_PARTIAL = "com.seaflow.remote.VOICE_PARTIAL"
        const val ACTION_STATE = "com.seaflow.remote.VOICE_STATE"
        const val ACTION_ERROR = "com.seaflow.remote.VOICE_ERROR"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "seaflow_voice"
        private const val NOTIFICATION_ID = 4102
    }
}
