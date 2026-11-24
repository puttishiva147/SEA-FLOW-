package com.seaflow.remote.protocol

import android.content.Context
import android.security.KeyPairGeneratorSpec
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** Persistent RSA client identity. The private key stays inside AndroidKeyStore. */
internal class TvClientIdentity(context: Context) {
    private val alias = "seaflow-tv-remote-client"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) createIdentity(context)
    }

    val certificate: X509Certificate
        get() = store.getCertificate(alias) as X509Certificate

    val privateKey: PrivateKey
        get() = store.getKey(alias, null) as PrivateKey

    @Suppress("DEPRECATION")
    private fun createIdentity(context: Context) {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }
        val spec = KeyPairGeneratorSpec.Builder(context)
            .setAlias(alias)
            .setSubject(X500Principal("CN=SEAFLOW Android TV Remote"))
            .setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}
