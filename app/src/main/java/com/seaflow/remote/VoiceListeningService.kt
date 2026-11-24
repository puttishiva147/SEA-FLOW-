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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import com.seaflow.remote.protocol.TvRemoteClient
import java.net.URLEncoder
import java.util.Base64

class VoiceListeningService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var remote: TvRemoteClient? = null
    private var listening = false
    private var restarting = false
    private var speaking = false
    private var nickname = "Nexa"
    private var speaker: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        speaker = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speaker?.language = Locale.getDefault()
                speaker?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { speaking = false; scheduleRestart() }
                    @Deprecated("Deprecated in Android API")
                    override fun onError(id: String?) { speaking = false; scheduleRestart() }
                })
            }
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SEAFLOW voice control is active")
                .setContentText("Listening for your TV nickname and commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("SEAFLOW voice control is active")
                .setContentText("Listening for your TV nickname and commands")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST)
        val cert = intent?.getStringExtra(EXTRA_CERT)
        nickname = intent?.getStringExtra(EXTRA_NICKNAME) ?: nickname
        if (remote == null && !host.isNullOrBlank() && !cert.isNullOrBlank()) {
            Thread {
                try {
                    val connected = TvRemoteClient(applicationContext, host, Base64.getDecoder().decode(cert)) {
                        emit(ACTION_STATE, it)
                    }
                    remote = connected
                    emit(ACTION_STATE, "Background TV connection ready")
                } catch (error: Exception) {
                    emit(ACTION_ERROR, "Background TV connection failed: " + (error.localizedMessage ?: "unknown error"))
                }
            }.start()
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit(ACTION_ERROR, "Speech recognition is not available on this device.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            startRecognition()
        }
        return START_STICKY
    }

    private fun startRecognition() {
        if (listening || recognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { listening = true; recognizer?.startListening(intent) }
        catch (error: Exception) {
            listening = false
            emit(ACTION_ERROR, error.localizedMessage ?: "Could not start listening.")
            scheduleRestart()
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!phrase.isNullOrBlank()) {
            emit(ACTION_HEARD, phrase)
            executeCommand(phrase)
        }
        scheduleRestart()
    }

    private fun executeCommand(phrase: String) {
        val spoken = phrase.trim()
        if (!spoken.startsWith(nickname, ignoreCase = true)) {
            emit(ACTION_UNMAPPED, "Ignored without wake word '$nickname': $phrase")
            return
        }
        val command = spoken.replace(Regex("(?i)^" + Regex.escape(nickname) + "[, ]*"), "").lowercase()
        emit(ACTION_UNDERSTOOD, command)
        val assistantReply = when {
            command == "hello" || command == "hi" || command == "hey" -> "Hello! I'm SEAFLOW. What can I do for you?"
            command.contains("who are you") -> "I'm SEAFLOW, your voice assistant and television controller."
            command.contains("how are you") -> "I'm doing well and ready to help."
            command.contains("what time") || command == "time" -> "It's " + java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
            command.contains("what can you do") || command.contains("help me") -> "I can control supported TV functions, open apps and websites, search Google, and answer a few basic questions."
            else -> null
        }
        if (assistantReply != null) {
            emit(ACTION_ACTION, assistantReply)
            speak(assistantReply)
            return
        }
        val target = remote ?: run {
            val reply = "I heard you, but the television connection is not ready."
            emit(ACTION_ERROR, reply)
            speak(reply)
            return
        }
        try {
            val action = when {
                command.contains("play") && (command.contains("music") || command.contains("song")) -> {
                    target.launchAppLink("https://music.youtube.com/search?q=random%20music"); "Opening YouTube Music"
                }
                command.contains("open youtube") -> { target.launchAppLink("https://www.youtube.com"); "Opening YouTube" }
                command.contains("open google") -> { target.launchAppLink("https://www.google.com"); "Opening Google" }
                command.contains("open browser") || command.contains("open website") -> {
                    val url = Regex("(?i)(?:https?://)?(?:www\\.)?[a-z0-9-]+\\.[a-z]{2,}(?:/[^\\s]*)?").find(command)?.value
                        ?: throw IllegalArgumentException("Say the website address after open browser.")
                    target.launchAppLink(if (url.startsWith("http")) url else "https://$url")
                    "Opening $url"
                }
                command.contains("open chatgpt") || command.contains("open chat gpt") -> {
                    target.launchAppLink("https://chatgpt.com/"); "Opening ChatGPT"
                }
                command.contains("open gemini") -> {
                    target.launchAppLink("https://gemini.google.com/"); "Opening Gemini"
                }
                command.contains("search") || command.contains("google") -> {
                    target.launchAppLink("https://www.google.com/search?q=" + URLEncoder.encode(command, "UTF-8")); "Searching Google on TV"
                }
                command.contains("wake tv") || command.contains("wake screen") || command.contains("exit screen saver") -> { target.press(3); "Sending Home to wake or exit screen saver" }
                command.contains("open settings") -> { target.press(176); "Opening TV settings (if supported)" }
                command.contains("volume up") -> { target.press(24); "Volume up" }
                command.contains("volume down") -> { target.press(25); "Volume down" }
                command.contains("mute") -> { target.press(164); "Mute" }
                command.contains("turn off tv") || command == "power off" -> { target.press(177); "Sending TV power command (support varies)" }
                command.contains("go home") || command == "home" -> { target.press(3); "Home" }
                command.contains("go back") || command == "back" -> { target.press(4); "Back" }
                command.contains("pause") || command == "play" -> { target.press(85); "Play / pause" }
                else -> { emit(ACTION_UNMAPPED, phrase); "Command not mapped yet" }
            }
            emit(ACTION_ACTION, action)
            speak(action)
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "TV command failed"
            emit(ACTION_ERROR, message)
            speak(message)
        }
    }

    private fun speak(message: String) {
        val engine = speaker ?: return
        speaking = true
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "seaflow-" + System.nanoTime())
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!phrase.isNullOrBlank()) emit(ACTION_PARTIAL, phrase)
    }
    override fun onError(error: Int) {
        listening = false
        if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            emit(ACTION_ERROR, "Listening paused (speech error $error); retrying.")
        scheduleRestart()
    }
    private fun scheduleRestart() {
        if (restarting || speaking) return
        restarting = true
        android.os.Handler(mainLooper).postDelayed({
            restarting = false
            if (listening) { try { recognizer?.cancel() } catch (_: Exception) { }; listening = false }
            if (recognizer != null) startRecognition()
        }, 650)
    }
    private fun emit(action: String, value: String) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_TEXT, value))
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "SEAFLOW voice control", NotificationManager.IMPORTANCE_LOW))
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
        speaker?.stop()
        speaker?.shutdown()
        speaker = null
        remote?.close()
        remote = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HEARD = "com.seaflow.remote.VOICE_HEARD"
        const val ACTION_PARTIAL = "com.seaflow.remote.VOICE_PARTIAL"
        const val ACTION_STATE = "com.seaflow.remote.VOICE_STATE"
        const val ACTION_ERROR = "com.seaflow.remote.VOICE_ERROR"
        const val ACTION_UNDERSTOOD = "com.seaflow.remote.VOICE_UNDERSTOOD"
        const val ACTION_ACTION = "com.seaflow.remote.VOICE_ACTION"
        const val ACTION_UNMAPPED = "com.seaflow.remote.VOICE_UNMAPPED"
        const val EXTRA_TEXT = "text"
        const val EXTRA_HOST = "tv_host"
        const val EXTRA_CERT = "tv_certificate"
        const val EXTRA_NICKNAME = "tv_nickname"
        private const val CHANNEL_ID = "seaflow_voice"
        private const val NOTIFICATION_ID = 4102
    }
}
