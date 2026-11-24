package com.seaflow.remote.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Implements the Android TV Remote v2 six-hex-character code proof. */
internal object PairingCodeVerifier {
    fun deriveSecret(clientKey: RSAPublicKey, serverKey: RSAPublicKey, code: String): ByteArray {
        val normalized = code.trim().uppercase()
        require(normalized.length == 6 && normalized.all { it in "0123456789ABCDEF" }) {
            "Enter the six-character hexadecimal code shown on your TV."
        }
        val digest = hash(
            clientKey,
            serverKey,
            hexToBytes(normalized.substring(2))
        )
        val expectedCheck = normalized.substring(0, 2).toInt(16)
        if ((digest[0].toInt() and 0xff) != expectedCheck) {
            throw IllegalArgumentException("That TV code did not pass its security check. Re-enter the code shown on the TV.")
        }
        return digest
    }

    internal fun hash(
        clientKey: RSAPublicKey,
        serverKey: RSAPublicKey,
        nonce: ByteArray
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        unsigned(clientKey.modulus) +
            unsigned(clientKey.publicExponent) +
            unsigned(serverKey.modulus) +
            unsigned(serverKey.publicExponent) +
            nonce
    )

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hexToBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
