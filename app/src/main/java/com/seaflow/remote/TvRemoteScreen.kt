package com.seaflow.remote

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.seaflow.remote.protocol.TvRemoteClient
import java.net.URLEncoder

@Composable
internal fun TvRemoteScreen(
    nickname: String,
    client: TvRemoteClient,
    status: String,
    tvHost: String,
    tvCertificate: String,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    fun voiceServiceIntent() = Intent(context, VoiceListeningService::class.java).apply {
        putExtra(VoiceListeningService.EXTRA_HOST, tvHost)
        putExtra(VoiceListeningService.EXTRA_CERT, tvCertificate)
        putExtra(VoiceListeningService.EXTRA_NICKNAME, nickname)
    }
    var commandStatus by remember { mutableStateOf("Ready") }
    var listening by remember { mutableStateOf(false) }
    var heardText by remember { mutableStateOf("Nothing heard yet") }
    var website by remember { mutableStateOf("") }
    var tvText by remember { mutableStateOf("") }
    val activityLog = remember { mutableStateListOf<String>() }

    fun log(line: String) {
        activityLog.add(0, line)
        while (activityLog.size > 12) activityLog.removeAt(activityLog.lastIndex)
    }

    fun runAction(label: String, action: () -> Unit) {
        try {
            action()
            commandStatus = label
            log("ACTION  ·  $label")
        } catch (error: Exception) {
            commandStatus = error.localizedMessage ?: "The TV connection was lost."
            log("ERROR  ·  $commandStatus")
        }
    }

    fun processVoice(phrase: String) {
        heardText = phrase
        log("HEARD  ·  $phrase")
        val command = phrase.trim()
            .replace(Regex("(?i)^" + Regex.escape(nickname) + "[, ]*"), "")
            .lowercase()
        log("UNDERSTOOD  ·  $command")
        when {
            command.contains("play") && (command.contains("music") || command.contains("song")) ->
                runAction("Opening YouTube Music search") { client.launchAppLink("https://music.youtube.com/search?q=random%20music") }
            command.contains("open youtube") ->
                runAction("Opening YouTube") { client.launchAppLink("https://www.youtube.com") }
            command.contains("open chatgpt") || command.contains("open chat gpt") ->
                runAction("Opening ChatGPT (uses the TV's installed app or browser association)") { client.launchAppLink("https://chatgpt.com/") }
            command.contains("open gemini") ->
                runAction("Opening Gemini (uses the TV's installed app or browser association)") { client.launchAppLink("https://gemini.google.com/") }
            command.contains("ask chatgpt") || command.contains("ask gemini") -> {
                val url = if (command.contains("chatgpt")) "https://chatgpt.com/" else "https://gemini.google.com/"
                runAction("Opening AI assistant; type your question on its screen") { client.launchAppLink(url) }
            }
            command.contains("search") || command.contains("google") ->
                runAction("Searching Google on TV") { client.launchAppLink("https://www.google.com/search?q=" + URLEncoder.encode(command, "UTF-8")) }
            command.contains("open browser") || command.contains("open website") -> {
                val rawUrl = Regex("(?i)(?:https?://)?(?:www\\.)?[a-z0-9-]+\\.[a-z]{2,}(?:/[^\\s]*)?").find(phrase)?.value
                if (rawUrl != null) {
                    val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
                    runAction("Opening $url") { client.launchAppLink(url) }
                } else {
                    commandStatus = "Say the full website address."
                    log("NEEDS INPUT  ·  Website address not detected")
                }
            }
            command.contains("volume up") -> runAction("Volume up") { client.press(24) }
            command.contains("volume down") -> runAction("Volume down") { client.press(25) }
            command.contains("mute") -> runAction("Mute") { client.press(164) }
            command.contains("turn off tv") || command == "power off" -> runAction("Sending TV power key; hardware support varies") { client.press(177) }
            command.contains("go home") || command == "home" -> runAction("Home") { client.press(3) }
            command.contains("go back") || command == "back" -> runAction("Back") { client.press(4) }
            command.contains("play pause") || command == "pause" || command == "play" -> runAction("Play / pause") { client.press(85) }
            else -> {
                commandStatus = "Command not recognized yet"
                log("NOT MAPPED  ·  $phrase")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ContextCompat.startForegroundService(context, voiceServiceIntent())
            listening = true
            commandStatus = "Background voice listening started"
            log("SYSTEM  ·  Foreground listening service started")
        } else {
            commandStatus = "Microphone permission is required for voice control."
            log("PERMISSION  ·  Microphone denied")
        }
    }

    DisposableEffect(context, client) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val value = intent?.getStringExtra(VoiceListeningService.EXTRA_TEXT).orEmpty()
                when (intent?.action) {
                    VoiceListeningService.ACTION_HEARD -> processVoice(value)
                    VoiceListeningService.ACTION_PARTIAL -> heardText = value
                    VoiceListeningService.ACTION_STATE -> commandStatus = value
                    VoiceListeningService.ACTION_ERROR -> {
                        commandStatus = value
                        log("LISTENER  ·  $value")
                    }
                    VoiceListeningService.ACTION_UNDERSTOOD -> log("UNDERSTOOD  ·  $value")
                    VoiceListeningService.ACTION_ACTION -> {
                        commandStatus = value
                        log("ACTION  ·  $value")
                    }
                    VoiceListeningService.ACTION_UNMAPPED -> log("NOT MAPPED  ·  $value")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(VoiceListeningService.ACTION_HEARD)
            addAction(VoiceListeningService.ACTION_PARTIAL)
            addAction(VoiceListeningService.ACTION_STATE)
            addAction(VoiceListeningService.ACTION_ERROR)
            addAction(VoiceListeningService.ACTION_UNDERSTOOD)
            addAction(VoiceListeningService.ACTION_ACTION)
            addAction(VoiceListeningService.ACTION_UNMAPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(nickname, style = MaterialTheme.typography.headlineMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VOICE CONTROL", style = MaterialTheme.typography.titleMedium)
                Text(if (listening) "Background listening is ON" else "Background listening is OFF")
                Text("Heard: $heardText", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            ContextCompat.startForegroundService(context, voiceServiceIntent())
                            listening = true
                            commandStatus = "Background voice listening started"
                            log("SYSTEM  ·  Listening enabled")
                        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = !listening, modifier = Modifier.weight(1f)) { Text("Start listening") }
                    OutlinedButton(onClick = {
                        context.stopService(Intent(context, VoiceListeningService::class.java))
                        listening = false
                        commandStatus = "Listening stopped"
                        log("SYSTEM  ·  Listening stopped")
                    }, enabled = listening, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                Text("Android keeps a microphone foreground-service notification visible while listening. You can stop it here or from system controls.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("REMOTE CONTROL", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { runAction("Up") { client.press(19) } }) { Text("▲") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { runAction("Left") { client.press(21) } }) { Text("◀") }
                    Button(onClick = { runAction("Select") { client.press(23) } }) { Text("OK") }
                    Button(onClick = { runAction("Right") { client.press(22) } }) { Text("▶") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { runAction("Down") { client.press(20) } }) { Text("▼") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { runAction("Back") { client.press(4) } }) { Text("Back") }
            OutlinedButton(onClick = { runAction("Home") { client.press(3) } }) { Text("Home") }
            OutlinedButton(onClick = { runAction("Menu") { client.press(82) } }) { Text("Menu") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { runAction("Power command sent; TV support varies") { client.press(177) } }) { Text("Power") }
            Button(onClick = { runAction("Volume up") { client.press(24) } }) { Text("Vol +") }
            Button(onClick = { runAction("Volume down") { client.press(25) } }) { Text("Vol −") }
            Button(onClick = { runAction("Mute") { client.press(164) } }) { Text("Mute") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runAction("Opening YouTube") { client.launchAppLink("https://www.youtube.com") } }, modifier = Modifier.weight(1f)) { Text("YouTube") }
            Button(onClick = { runAction("Opening YouTube Music") { client.launchAppLink("https://music.youtube.com") } }, modifier = Modifier.weight(1f)) { Text("Music") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runAction("Opening ChatGPT") { client.launchAppLink("https://chatgpt.com/") } }, modifier = Modifier.weight(1f)) { Text("ChatGPT") }
            OutlinedButton(onClick = { runAction("Opening Gemini") { client.launchAppLink("https://gemini.google.com/") } }, modifier = Modifier.weight(1f)) { Text("Gemini") }
        }
        TextField(value = tvText, onValueChange = { tvText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Type into the focused TV field") }, singleLine = true)
        OutlinedButton(onClick = {
            try { client.sendText(tvText); commandStatus = "Text sent to TV"; log("ACTION  ·  Text sent to TV"); tvText = "" }
            catch (error: Exception) { commandStatus = error.localizedMessage ?: "Text entry failed" }
        }, enabled = tvText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send text to TV") }
        TextField(value = website, onValueChange = { website = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Website URL") }, singleLine = true)
        OutlinedButton(onClick = {
            val url = website.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            runAction("Opening $url") { client.launchAppLink(url) }
        }, enabled = website.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Open website on TV") }
        Text("CURRENT STATUS  ·  $commandStatus", style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("LIVE ACTIVITY", style = MaterialTheme.typography.titleMedium)
                if (activityLog.isEmpty()) Text("Your heard words, interpretation and TV actions will appear here.", style = MaterialTheme.typography.bodySmall)
                activityLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Try: “$nickname, open YouTube”, “$nickname, play random music”, “$nickname, open ChatGPT”, “$nickname, search weather tomorrow”, or “$nickname, volume up”.", style = MaterialTheme.typography.bodySmall)
    }
}
