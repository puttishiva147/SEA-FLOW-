package com.seaflow.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class TvCandidate(val name: String, val host: String, val port: Int, val service: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SeaflowScreen(this) }
    }
}

@Composable
private fun SeaflowScreen(context: Context) {
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to scan your local Wi-Fi network") }
    var devices by remember { mutableStateOf(listOf<TvCandidate>()) }
    val nsd = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    val serviceTypes = listOf("_androidtvremote2._tcp.")

    fun scan() {
        devices = emptyList()
        status = "Searching for advertised TV services…"
        scanning = true
        serviceTypes.forEach { type ->
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD,
                    object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    val host = info.host?.hostAddress ?: return
                                    val candidate = TvCandidate(info.serviceName, host, info.port, info.serviceType)
                                    devices = (devices + candidate).distinctBy { it.host + ":" + it.port }
                                    status = "Found ${devices.size} advertised service(s)."
                                }
                            })
                        }
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            scanning = false
                            status = "Discovery unavailable for $serviceType (error $errorCode)"
                        }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    })
            } catch (e: Exception) {
                status = "Scan could not start: ${e.localizedMessage ?: "unknown error"}"
                scanning = false
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SEAFLOW", style = MaterialTheme.typography.headlineLarge)
                Text("Local TV discovery", style = MaterialTheme.typography.titleMedium)
                Text("Phone and TV should be connected to the same Wi-Fi network.")
                Button(onClick = { scan() }, enabled = !scanning) {
                    Text(if (scanning) "Scanning…" else "Find TVs on Wi-Fi")
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (devices.isEmpty()) {
                    Text("No compatible service found yet. Some TVs do not advertise remote services; discovery alone cannot confirm control compatibility.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices) { device ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${device.host}:${device.port}")
                                    Text(device.service, style = MaterialTheme.typography.bodySmall)
                                    Text("Android TV Remote service found. Next: secure PIN pairing on port 6467.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
