package com.seaflow.remote.protocol

import android.content.Context
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal class TvRemoteClient(
    context: Context,
    host: String,
    pairedCertificateDer: ByteArray,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(pairedCertificateDer.inputStream()) as X509Certificate
    private val socket: SSLSocket
    private val connected = AtomicBoolean(false)
    private val writeLock = Any()
    private var supportedFeatures = 0L

    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, null)
        }.keyManagers
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty() && chain[0].encoded.contentEquals(certificate.encoded)) {
                    "The TV certificate changed. Pair this TV again to protect the connection."
                }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), null) }
        socket = ssl.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, REMOTE_PORT), 8_000)
        socket.soTimeout = 15_000
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket.startHandshake()
        configure()
        connected.set(true)
        socket.soTimeout = 0
        Thread({ readLoop() }, "seaflow-tv-reader").apply { isDaemon = true; start() }
    }

    private fun configure() {
        val first = ProtoWire.fields(ProtoWire.readDelimited(socket.inputStream))
        val config = first.firstOrNull { it.number == 1 }?.data
            ?: throw IllegalStateException("TV did not send its remote configuration.")
        val configFields = ProtoWire.fields(config)
        supportedFeatures = configFields.firstOrNull { it.number == 1 }?.integer ?: 0L
        val deviceInfo = ProtoWire.string(4, "1") +
            ProtoWire.uint(3, 1) +
            ProtoWire.string(5, "com.seaflow.remote") +
            ProtoWire.string(6, "1.0.0")
        write(ProtoWire.uint(1, supportedFeatures) + ProtoWire.message(2, deviceInfo))

        val activeFrame = ProtoWire.fields(ProtoWire.readDelimited(socket.inputStream))
        if (activeFrame.none { it.number == 2 }) {
            throw IllegalStateException("TV did not activate the remote session.")
        }
        write(ProtoWire.uint(1, supportedFeatures))
        onStatus("Remote channel ready. TV capabilities: " + featureSummary(supportedFeatures))
    }

    fun press(keyCode: Int, longPress: Boolean = false) {
        checkReady()
        val direction = if (longPress) 1L else 3L
        val payload = ProtoWire.uint(1, keyCode.toLong()) + ProtoWire.uint(2, direction)
        write(ProtoWire.message(10, payload))
    }

    fun launchAppLink(uri: String) {
        checkReady()
        require(uri.startsWith("https://") || uri.startsWith("http://") || uri.startsWith("intent://")) {
            "Only web or Android intent links can be launched."
        }
        write(ProtoWire.message(90, ProtoWire.string(1, uri)))
    }

    private fun readLoop() {
        try {
            while (connected.get() && !socket.isClosed) {
                val fields = ProtoWire.fields(ProtoWire.readDelimited(socket.inputStream))
                val ping = fields.firstOrNull { it.number == 8 }?.data
                if (ping != null) {
                    val value = ProtoWire.fields(ping).firstOrNull { it.number == 1 }?.integer ?: 0L
                    write(ProtoWire.message(9, ProtoWire.uint(1, value)))
                }
            }
        } catch (error: Exception) {
            if (connected.get()) onStatus("TV remote disconnected: " + (error.localizedMessage ?: "connection lost"))
            connected.set(false)
        }
    }

    private fun write(message: ByteArray) {
        synchronized(writeLock) {
            check(!socket.isClosed) { "TV connection is closed." }
            ProtoWire.writeDelimited(socket.outputStream, message)
        }
    }

    private fun checkReady() {
        check(connected.get() && !socket.isClosed) { "Connect to your TV first." }
    }

    private fun featureSummary(features: Long): String = buildList {
        if (features and (1L shl 1) != 0L) add("navigation")
        if (features and (1L shl 5) != 0L) add("power")
        if (features and (1L shl 6) != 0L) add("volume")
        if (features and (1L shl 9) != 0L) add("app launch")
    }.joinToString().ifBlank { "basic" }

    override fun close() {
        connected.set(false)
        try { socket.close() } catch (_: Exception) { }
    }

    companion object {
        private const val REMOTE_PORT = 6466
    }
}
