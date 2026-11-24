package com.seaflow.remote.protocol

import android.content.Context
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote v2 pairing transport.
 * The TV certificate is self-signed; trust is established by the six-character
 * on-screen code and the protocol's certificate-key hash, not by a public CA.
 */
internal class TvPairingClient(private val context: Context) {
    fun begin(host: String): PairingSession {
        val identity = TvClientIdentity(context.applicationContext)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, null)
        }.keyManagers

        val peerCertificate = AtomicReference<X509Certificate?>()
        val trustManager = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "TV did not provide a TLS certificate." }
                peerCertificate.set(chain[0])
            }
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), SecureRandom())
        }
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, PAIRING_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = RESPONSE_TIMEOUT_MS
            val supported = socket.supportedProtocols.toSet()
            socket.enabledProtocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
            socket.startHandshake()
            val serverCert = peerCertificate.get()
                ?: throw IllegalStateException("TV did not present its pairing certificate.")

            send(socket, outer(10, ProtoWire.string(1, "atvremote") + ProtoWire.string(2, "SEAFLOW")))
            awaitField(socket, 11)

            val encoding = ProtoWire.uint(1, HEXADECIMAL) + ProtoWire.uint(2, PIN_LENGTH.toLong())
            val options = ProtoWire.message(1, encoding) + ProtoWire.uint(3, INPUT_ROLE)
            send(socket, outer(20, options))
            awaitField(socket, 20)

            val configuration = ProtoWire.message(1, encoding) + ProtoWire.uint(2, INPUT_ROLE)
            send(socket, outer(30, configuration))
            awaitField(socket, 31)

            socket.soTimeout = PIN_ENTRY_TIMEOUT_MS
            return PairingSession(socket, identity.certificate, serverCert)
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw error
        }
    }

    internal inner class PairingSession(
        private val socket: SSLSocket,
        private val clientCertificate: X509Certificate,
        private val serverCertificate: X509Certificate
    ) : AutoCloseable {
        fun finish(pin: String) {
            val clientKey = clientCertificate.publicKey as? RSAPublicKey
                ?: throw IllegalStateException("SEAFLOW client certificate is not RSA.")
            val serverKey = serverCertificate.publicKey as? RSAPublicKey
                ?: throw IllegalStateException("TV pairing certificate is not RSA.")
            val digest = PairingCodeVerifier.deriveSecret(clientKey, serverKey, pin)

            send(socket, outer(40, ProtoWire.bytes(1, digest)))
            awaitField(socket, 41)
            close()
        }

        override fun close() {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun awaitField(socket: SSLSocket, expectedField: Int) {
        val frame = ProtoWire.readDelimited(socket.inputStream, MAX_FRAME_BYTES)
        val fields = ProtoWire.fields(frame)
        val status = fields.firstOrNull { it.number == 2 }?.integer ?: STATUS_OK
        if (status != STATUS_OK.toLong()) {
            throw IllegalStateException("TV rejected pairing protocol message (status $status).")
        }
        if (fields.none { it.number == expectedField }) {
            throw IllegalStateException("Unexpected response from TV during pairing (expected field $expectedField).")
        }
    }

    private fun send(socket: SSLSocket, message: ByteArray) {
        ProtoWire.writeDelimited(socket.outputStream, message)
    }

    private fun outer(payloadField: Int, payload: ByteArray): ByteArray =
        ProtoWire.uint(1, PROTOCOL_VERSION) +
            ProtoWire.uint(2, STATUS_OK) +
            ProtoWire.message(payloadField, payload)

    companion object {
        private const val PAIRING_PORT = 6467
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val RESPONSE_TIMEOUT_MS = 10_000
        private const val PIN_ENTRY_TIMEOUT_MS = 180_000
        private const val MAX_FRAME_BYTES = 256 * 1024
        private const val PROTOCOL_VERSION = 2L
        private const val STATUS_OK = 200L
        private const val HEXADECIMAL = 3L
        private const val INPUT_ROLE = 1L
        private const val PIN_LENGTH = 6
    }
}
