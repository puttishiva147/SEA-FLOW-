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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Handler\nimport android.os.Looper\nimport com.seaflow.remote.protocol.TvPairingClient\nimport java.util.concurrent.Executors

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

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    nsd.stopServiceDiscovery(this)
                }
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
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to scan your local Wi-Fi network.") }
    var devices by remember { mutableStateOf(emptyList<TvCandidate>()) }\n    var pairingStatus by remember { mutableStateOf("Not paired") }\n    var pairingPin by remember { mutableStateOf("") }\n    var pairingBusy by remember { mutableStateOf(false) }\n    var awaitingPin by remember { mutableStateOf(false) }\n    var pairedName by remember { mutableStateOf<String?>(null) }\n    var pairingSession by remember { mutableStateOf<TvPairingClient.PairingSession?>(null) }\n    val executor = remember { Executors.newSingleThreadExecutor() }\n    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val controller = remember(context) {
        TvDiscoveryController(context) { message, results, active ->
            status = message
            devices = results
            scanning = active
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.close(); pairingSession?.close(); executor.shutdownNow() }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("SEAFLOW", style = MaterialTheme.typography.headlineLarge)
                Text("TV discovery", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Connect your phone and TV to the same Wi-Fi network. SEAFLOW searches for the Android TV Remote service; discovery does not yet mean the TV is paired.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { controller.start() }, enabled = !scanning) {
                        Text(if (scanning) "Scanning…" else "Find TVs")
                    }
                    OutlinedButton(onClick = { controller.stop(); scanning = false; status = "Scan stopped." }, enabled = scanning) {
                        Text("Stop")
                    }
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)\n                Text(pairingStatus, style = MaterialTheme.typography.bodyMedium)\n                if (awaitingPin) {\n                    Text("Enter the six-character hexadecimal code displayed on your TV.", style = MaterialTheme.typography.bodySmall)\n                    TextField(value = pairingPin, onValueChange = { pairingPin = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6) }, label = { Text("TV pairing code") }, singleLine = true)\n                    Button(onClick = {\n                        val session = pairingSession\n                        if (session != null && pairingPin.length == 6 && !pairingBusy) {\n                            pairingBusy = true\n                            pairingStatus = "Verifying the TV code and completing secure pairing…"\n                            executor.execute {\n                                try {\n                                    session.finish(pairingPin)\n                                    mainHandler.post { pairingBusy = false; awaitingPin = false; pairingSession = null; pairedName = "TV"; pairingStatus = "Secure pairing completed successfully."; pairingPin = "" }\n                                } catch (error: Exception) {\n                                    mainHandler.post { pairingBusy = false; pairingStatus = error.localizedMessage ?: "Pairing failed." }\n                                }\n                            }\n                        }\n                    }, enabled = !pairingBusy && pairingPin.length == 6) { Text(if (pairingBusy) "Pairing…" else "Confirm code") }\n                    OutlinedButton(onClick = { pairingSession?.close(); pairingSession = null; awaitingPin = false; pairingStatus = "Pairing cancelled."; pairingPin = "" }, enabled = !pairingBusy) { Text("Cancel pairing") }\n                }\n                pairedName?.let { Text("Paired: $it", style = MaterialTheme.typography.titleMedium) }
                Text("Discovered devices: ${devices.size}", style = MaterialTheme.typography.titleMedium)
                if (devices.isEmpty()) {
                    Text(
                        "No compatible service found yet. Some TV models disable or do not advertise the Android TV Remote service.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(devices, key = { "${it.host}:${it.port}" }) { device ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${device.host}:${device.port}")
                                    Text(device.service, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Service discovered. Secure PIN pairing and remote commands are still pending.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
