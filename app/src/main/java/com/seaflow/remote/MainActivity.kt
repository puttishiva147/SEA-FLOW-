package com.seaflow.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.seaflow.remote.protocol.TvPairingClient
import com.seaflow.remote.protocol.TvRemoteClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64

data class TvCandidate(
    val name: String,
    val host: String,
    val port: Int,
    val service: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SeaflowScreen(this) }
    }
}

private class TvDiscoveryController(
    context: Context,
    private val onUpdate: (String, List<TvCandidate>, Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val found = linkedMapOf<String, TvCandidate>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var scanning = false

    private val scanTimeout = Runnable {
        finishScan(
            if (found.isEmpty()) "Scan complete. No Android TV Remote service was advertised on this Wi-Fi network."
            else "Scan complete. Found ${found.size} Android TV Remote service(s)."
        )
    }

    fun start() {
        stop()
        found.clear()
        publish("Searching for Android TV Remote services on the local network…", true)
        try {
            multicastLock = wifi.createMulticastLock("seaflow-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            scanning = true
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_androidtvremote2._tcp")) return
                    try {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                publish("A TV service was announced but could not be resolved (error $errorCode).", scanning)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val address = info.host?.hostAddress ?: return
                                val item = TvCandidate(
                                    name = info.serviceName,
                                    host = address,
                                    port = info.port,
                                    service = info.serviceType
                                )
                                found["$address:${info.port}"] = item
                                publish("Found ${found.size} Android TV Remote service(s).", scanning)
                            }
                        })
                    } catch (error: Exception) {
                        publish("Could not resolve TV service: ${error.localizedMessage ?: "unknown error"}", scanning)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = found.entries.firstOrNull { it.value.name == serviceInfo.serviceName }?.key
                    if (key != null) found.remove(key)
                    publish("A TV service disappeared from the network.", scanning)
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finishScan("Android TV discovery could not start (error $errorCode).")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            listeners += listener
            nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            main.postDelayed(scanTimeout, 12_000)
        } catch (error: Exception) {
            finishScan("Scan failed: ${error.localizedMessage ?: "unknown error"}")
        }
    }

    fun stop() {
        main.removeCallbacks(scanTimeout)
        listeners.toList().forEach { listener ->
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
        }
        listeners.clear()
        scanning = false
        releaseMulticastLock()
    }

    private fun finishScan(message: String) {
        main.removeCallbacks(scanTimeout)
        listeners.toList().forEach { listener ->
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
        }
        listeners.clear()
        scanning = false
        releaseMulticastLock()
        publish(message, false)
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) { }
        multicastLock = null
    }

    private fun publish(message: String, active: Boolean) {
        val snapshot = found.values.toList()
        main.post { onUpdate(message, snapshot, active) }
    }

    fun close() = stop()
}

@Composable
private fun SeaflowScreen(context: Context) {
    var stage by remember { mutableStateOf("welcome") }
    var remoteClient by remember { mutableStateOf<TvRemoteClient?>(null) }
    var tvNickname by remember { mutableStateOf("Nexa") }
    var remoteStatus by remember { mutableStateOf("Not connected") }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to find your TV") }
    var devices by remember { mutableStateOf(emptyList<TvCandidate>()) }
    var pairingStatus by remember { mutableStateOf("Not connected") }
    var pairingPin by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var pendingTvName by remember { mutableStateOf("") }
    var pendingTvHost by remember { mutableStateOf("") }
    var pairingBusy by remember { mutableStateOf(false) }
    val preferences = remember(context) { context.getSharedPreferences("seaflow_tv_pairing", Context.MODE_PRIVATE) }
    var pairedName by remember { mutableStateOf(preferences.getString("paired_tv_name", null)) }
    var pairedHost by remember { mutableStateOf(preferences.getString("paired_tv_host", null)) }
    var pairingSession by remember { mutableStateOf<TvPairingClient.PairingSession?>(null) }
    val sessionRef = remember { AtomicReference<TvPairingClient.PairingSession?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val controller = remember(context) {
        TvDiscoveryController(context) { message, results, active ->
            status = message
            devices = results
            scanning = active
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.close(); sessionRef.getAndSet(null)?.close(); remoteClient?.close(); executor.shutdownNow() }
    }

    fun beginPairing(hostValue: String, displayName: String) {
        val host = hostValue.trim()
        if (host.isBlank() || host.any { it.isWhitespace() }) {
            pairingStatus = "Enter a valid TV IP address or host name."
            return
        }
        pairingSession?.close()
        pairingSession = null
        sessionRef.set(null)
        pairingBusy = true
        pendingTvName = displayName
        pendingTvHost = host
        pairingStatus = "Opening secure connection to $displayName…"
        executor.execute {
            try {
                val session = TvPairingClient(context.applicationContext).begin(host)
                mainHandler.post {
                    pairingSession = session
                    sessionRef.set(session)
                    pairingBusy = false
                    pairingPin = ""
                    pairingStatus = "Connection established. Enter the code shown on your TV."
                    stage = "pair"
                }
            } catch (error: Exception) {
                mainHandler.post {
                    pairingBusy = false
                    pairingStatus = error.localizedMessage ?: "Could not start pairing."
                }
            }
        }
    }

    val navy = androidx.compose.ui.graphics.Color(0xFF071426)
    val panel = androidx.compose.ui.graphics.Color(0xFF10243D)
    val cyan = androidx.compose.ui.graphics.Color(0xFF25C7E8)
    val muted = androidx.compose.ui.graphics.Color(0xFF9FB2C8)
    val scheme = androidx.compose.material3.darkColorScheme(
        primary = cyan,
        secondary = androidx.compose.ui.graphics.Color(0xFF4D86FF),
        background = navy,
        surface = panel,
        onPrimary = navy,
        onBackground = androidx.compose.ui.graphics.Color(0xFFF3F7FC),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF3F7FC)
    )

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = navy) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painter = painterResource(com.seaflow.remote.R.drawable.ic_seaflow_logo), contentDescription = "SEAFLOW logo", modifier = Modifier.padding(end = 4.dp).fillMaxWidth(0.14f))
                        Column {
                            Text("SEA", style = MaterialTheme.typography.titleLarge)
                            Text("FLOW", color = cyan, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Text("PHONE  ↔  TV", color = muted, style = MaterialTheme.typography.labelMedium)
                }
                when (stage) {
                    "welcome" -> {
                        Spacer(Modifier.weight(0.3f))
                        Text("Your TV.\nOne simple remote.", style = MaterialTheme.typography.headlineLarge)
                        Text("Connect your phone and TV on the same Wi-Fi. SEAFLOW guides you through secure pairing.", color = muted, style = MaterialTheme.typography.bodyLarge)
                        SetupStep("01", "Connect to the same Wi-Fi", "Phone and TV must share a network")
                        SetupStep("02", "Find your television", "Automatic discovery or manual address")
                        SetupStep("03", "Pair securely", "Confirm the code displayed on your TV")
                        Spacer(Modifier.weight(0.7f))
                        Button(onClick = { stage = "discover" }, modifier = Modifier.fillMaxWidth()) { Text("Set up SEAFLOW  →") }
                    }
                    "discover" -> {
                        Text("Connect your TV", style = MaterialTheme.typography.headlineMedium)
                        Text("Make sure your phone and TV are on the same Wi-Fi network.", color = muted)
                        Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = panel)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("STEP 1  ·  FIND YOUR TV", color = cyan, style = MaterialTheme.typography.labelMedium)
                                Text("Search the local network for compatible Android TV devices.")
                                Button(onClick = { controller.start() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Searching…" else "Find TVs on Wi-Fi") }
                                if (scanning) OutlinedButton(onClick = { controller.stop(); scanning = false }, modifier = Modifier.fillMaxWidth()) { Text("Stop search") }
                                Text(status, color = muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("AVAILABLE DEVICES  ·  \${devices.size}", color = cyan, style = MaterialTheme.typography.labelMedium)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(devices, key = { "\${it.host}:\${it.port}" }) { device ->
                                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = panel)) {
                                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                                            Text(device.host, color = muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(onClick = { beginPairing(device.host, device.name) }, enabled = !pairingBusy) { Text("Connect") }
                                    }
                                }
                            }
                        }
                        Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = panel)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Can't find your TV?", style = MaterialTheme.typography.titleSmall)
                                Text("Enter its local IP address to try pairing directly.", color = muted, style = MaterialTheme.typography.bodySmall)
                                TextField(value = manualHost, onValueChange = { manualHost = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text("TV IP address or host name") }, singleLine = true)
                                OutlinedButton(onClick = { beginPairing(manualHost, manualHost) }, enabled = manualHost.isNotBlank() && !pairingBusy, modifier = Modifier.fillMaxWidth()) { Text("Continue with address") }
                            }
                        }
                    }
                    "pair" -> {
                        Text("Verify your TV", style = MaterialTheme.typography.headlineMedium)
                        Text("STEP 2  ·  SECURE PAIRING", color = cyan, style = MaterialTheme.typography.labelMedium)
                        Text("A six-character code should appear on your television. Enter it below to finish pairing.", color = muted)
                        Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = panel)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(pendingTvName, style = MaterialTheme.typography.titleLarge)
                                Text(pendingTvHost, color = muted)
                                TextField(value = pairingPin, onValueChange = { pairingPin = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6) }, modifier = Modifier.fillMaxWidth(), label = { Text("6-character TV code") }, singleLine = true)
                                Button(onClick = {
                                    val session = pairingSession
                                    val pin = pairingPin
                                    if (session != null && pin.length == 6 && !pairingBusy) {
                                        pairingBusy = true
                                        pairingStatus = "Verifying code…"
                                        executor.execute {
                                            try {
                                                session.finish(pin)
                                                mainHandler.post {
                                                    pairingBusy = false
                                                    pairingSession = null
                                                    sessionRef.set(null)
                                                    pairedName = pendingTvName
                                                    pairedHost = pendingTvHost
                                                    preferences.edit()
                                                        .putString("paired_tv_name", pendingTvName)
                                                        .putString("paired_tv_host", pendingTvHost)
                                                        .putString("paired_tv_certificate", Base64.getEncoder().encodeToString(session.serverCertificateDer))
                                                        .apply()
                                                    pairingPin = ""
                                                    pairingStatus = "TV paired successfully."
                                                    stage = "connected"
                                                }
                                            } catch (error: Exception) {
                                                mainHandler.post {
                                                    pairingBusy = false
                                                    pairingStatus = error.localizedMessage ?: "Pairing failed. Check the code and try again."
                                                }
                                            }
                                        }
                                    }
                                }, enabled = !pairingBusy && pairingPin.length == 6, modifier = Modifier.fillMaxWidth()) { Text(if (pairingBusy) "Verifying…" else "Pair my TV") }
                            }
                        }
                        Text(pairingStatus, color = muted)
                        TextButton(onClick = { pairingSession?.close(); pairingSession = null; sessionRef.set(null); pairingPin = ""; stage = "discover"; pairingStatus = "Pairing cancelled." }) { Text("Back to device list") }
                    }
                    "connected" -> {
                        Text("TV paired", style = MaterialTheme.typography.headlineMedium)
                        Text("Give your TV a voice nickname.", color = muted)
                        TextField(value = tvNickname, onValueChange = { tvNickname = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("TV nickname") }, singleLine = true)
                        Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = panel)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(pairedName ?: "Your TV", style = MaterialTheme.typography.titleLarge)
                                Text(pairedHost ?: "", color = muted)
                                Text("Nickname: $tvNickname", color = muted)
                            }
                        }
                        Button(onClick = {
                            val host = pairedHost
                            val cert = preferences.getString("paired_tv_certificate", null)
                            if (host == null || cert == null) remoteStatus = "Pair this TV again to save its certificate."
                            else executor.execute {
                                try {
                                    val client = TvRemoteClient(context, host, Base64.getDecoder().decode(cert)) { message -> mainHandler.post { remoteStatus = message } }
                                    mainHandler.post { remoteClient?.close(); remoteClient = client; remoteStatus = "Remote connected"; stage = "remote" }
                                } catch (error: Exception) { mainHandler.post { remoteStatus = error.localizedMessage ?: "Remote connection failed." } }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open remote control") }
                        Text(remoteStatus, color = muted)
                        OutlinedButton(onClick = { stage = "discover" }, modifier = Modifier.fillMaxWidth()) { Text("Add another TV") }
                    }
                    "remote" -> {
                        val client = remoteClient
                        if (client != null) TvRemoteScreen(tvNickname, client, remoteStatus) { client.close(); remoteClient = null; stage = "connected" }
                        else Text("Remote disconnected. Reconnect from the paired TV screen.")
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(number, color = androidx.compose.ui.graphics.Color(0xFF25C7E8), style = MaterialTheme.typography.titleLarge)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = androidx.compose.ui.graphics.Color(0xFF9FB2C8), style = MaterialTheme.typography.bodySmall)
        }
    }
}
