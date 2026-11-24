package com.seaflow.remote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class PairingCodeVerifierTest {
    @Test
    fun acceptsCorrectChecksumAndReturnsProtocolSecret() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val client = generator.generateKeyPair().public as RSAPublicKey
        val server = generator.generateKeyPair().public as RSAPublicKey
        val nonce = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val digest = PairingCodeVerifier.hash(client, server, nonce)
        val code = (digest[0].toInt() and 0xff).toString(16).padStart(2, '0').uppercase() + "A1B2"

        assertArrayEquals(digest, PairingCodeVerifier.deriveSecret(client, server, code))
    }

    @Test
    fun rejectsIncorrectChecksum() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val client = generator.generateKeyPair().public as RSAPublicKey
        val server = generator.generateKeyPair().public as RSAPublicKey
        val nonce = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val digest = PairingCodeVerifier.hash(client, server, nonce)
        val actual = digest[0].toInt() and 0xff
        val wrong = (if (actual == 0) 1 else 0).toString(16).padStart(2, '0').uppercase() + "A1B2"

        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeVerifier.deriveSecret(client, server, wrong)
        }
    }
}
