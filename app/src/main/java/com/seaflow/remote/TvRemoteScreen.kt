package com.seaflow.remote

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.seaflow.remote.protocol.TvRemoteClient

@Composable
internal fun TvRemoteScreen(
    nickname: String,
    client: TvRemoteClient,
    status: String,
    onDisconnect: () -> Unit
) {
    var commandStatus by remember { mutableStateOf("Ready") }
    var website by remember { mutableStateOf("") }
    var tvText by remember { mutableStateOf("") }
    fun runRemoteAction(success: String, action: () -> Unit) {
        try {
            action()
            commandStatus = success
        } catch (error: Exception) {
            commandStatus = error.localizedMessage ?: "The TV connection was lost. Reconnect and try again."
        }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val phrase = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (phrase.isNotBlank()) {
            val command = phrase.trim()
                .replace(Regex("(?i)^" + Regex.escape(nickname) + "[, ]*"), "")
                .lowercase()
            try {
                when {
                    command.contains("play") && (command.contains("music") || command.contains("song")) -> {
                        client.launchAppLink("https://music.youtube.com/search?q=random%20music")
                        commandStatus = "Opening YouTube Music search"
                    }
                    command.contains("open youtube") -> {
                        client.launchAppLink("https://www.youtube.com")
                        commandStatus = "Opening YouTube"
                    }
                    command.contains("open browser") || command.contains("open website") || command.contains("search") -> {
                        val rawUrl = Regex("(?i)(?:https?://)?(?:www\\.)?[a-z0-9-]+\\.[a-z]{2,}(?:/[^\\s]*)?").find(phrase)?.value
                        if (rawUrl != null) {
                            val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
                            client.launchAppLink(url)
                            commandStatus = "Opening $url"
                        } else commandStatus = "Say the website name or full address, for example example.com"
                    }
                    command.contains("volume up") -> { client.press(24); commandStatus = "Volume up" }
                    command.contains("volume down") -> { client.press(25); commandStatus = "Volume down" }
                    command.contains("mute") -> { client.press(164); commandStatus = "Mute" }
                    command.contains("power") || command.contains("turn off tv") -> { client.press(177); commandStatus = "Sending TV power command (if supported)" }
                    command.contains("go home") || command == "home" -> { client.press(3); commandStatus = "Home" }
                    command.contains("go back") || command == "back" -> { client.press(4); commandStatus = "Back" }
                    command.contains("play pause") || command.contains("pause") -> { client.press(85); commandStatus = "Play / pause" }
                    else -> commandStatus = "I heard: $phrase. That command is not supported yet."
                }
            } catch (error: Exception) {
                commandStatus = error.localizedMessage ?: "Command failed"
            }
        }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { runRemoteAction("Up", { client.press(19) }) }) { Text("▲") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { runRemoteAction("Left", { client.press(21) }) }) { Text("◀") }
                    Button(onClick = { runRemoteAction("Select", { client.press(23) }) }) { Text("OK") }
                    Button(onClick = { runRemoteAction("Right", { client.press(22) }) }) { Text("▶") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { runRemoteAction("Down", { client.press(20) }) }) { Text("▼") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { runRemoteAction("Back", { client.press(4) }) }) { Text("Back") }
            OutlinedButton(onClick = { runRemoteAction("Home", { client.press(3) }) }) { Text("Home") }
            OutlinedButton(onClick = { runRemoteAction("Menu", { client.press(82) }) }) { Text("Menu") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { runRemoteAction("Power command sent (TV support varies)", { client.press(177) }) }) { Text("Power") }
            Button(onClick = { runRemoteAction("Volume up", { client.press(24) }) }) { Text("Vol +") }
            Button(onClick = { runRemoteAction("Volume down", { client.press(25) }) }) { Text("Vol −") }
            Button(onClick = { runRemoteAction("Mute", { client.press(164) }) }) { Text("Mute") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runRemoteAction("Opening YouTube", { client.launchAppLink("https://www.youtube.com") }) }, modifier = Modifier.weight(1f)) { Text("YouTube") }
            Button(onClick = { runRemoteAction("Opening YouTube Music", { client.launchAppLink("https://music.youtube.com") }) }, modifier = Modifier.weight(1f)) { Text("Music") }
        }
        TextField(value = tvText, onValueChange = { tvText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Type into the focused TV field") }, singleLine = true)
        OutlinedButton(onClick = {
            try { client.sendText(tvText); commandStatus = "Text sent to TV"; tvText = "" }
            catch (error: Exception) { commandStatus = error.localizedMessage ?: "Text entry failed" }
        }, enabled = tvText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send text to TV") }
        TextField(value = website, onValueChange = { website = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Website URL") }, singleLine = true)
        OutlinedButton(onClick = {
            val url = website.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            try { client.launchAppLink(url); commandStatus = "Opening $url" } catch (error: Exception) { commandStatus = error.localizedMessage ?: "Could not open URL" }
        }, enabled = website.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Open website on TV") }
        Button(onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command for $nickname")
            }
            try { speech.launch(intent) } catch (_: Exception) { commandStatus = "Speech recognition is not available on this phone." }
        }, modifier = Modifier.fillMaxWidth()) { Text("🎙 Speak to $nickname") }
        Text(commandStatus, style = MaterialTheme.typography.bodySmall)
        Text("Try: “Nexa, open YouTube”, “Nexa, play random music”, “Nexa, open browser https://example.com”, or “Nexa, volume up”.", style = MaterialTheme.typography.bodySmall)
    }
}
