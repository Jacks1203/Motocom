package com.motocom.shell

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/** Persistent ADB identity, private to the app and excluded from backups. */
class AdbIdentity(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate
    /** True when this identity was just made, so no phone has accepted it yet. */
    val isNew: Boolean
    /** True when the saved identity could not be read and was replaced. */
    val wasBroken: Boolean
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        val identity = File(context.noBackupFilesDir, "adb-identity.p12")
        val password = CharArray(0) // File is protected by Android's app sandbox.
        val saved = if (identity.exists()) runCatching {
            val store = java.security.KeyStore.getInstance("PKCS12")
            identity.inputStream().use { store.load(it, password) }
            val savedKey = store.getKey("adb", password) as PrivateKey
            savedKey to checkNotNull(store.getCertificate("adb"))
        }.getOrNull() else null
        // A damaged file would fail every start: drop it and pair again with a new one.
        wasBroken = identity.exists() && saved == null
        if (wasBroken) identity.delete()
        isNew = saved == null
        if (saved != null) {
            key = saved.first
            cert = saved.second
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            key = pair.private
            val subject = X500Name("CN=Motocom")
            val provider = BouncyCastleProvider()
            val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(System.currentTimeMillis()),
                Date(System.currentTimeMillis() - 86400000), Date(System.currentTimeMillis() + 10L * 365 * 86400000), subject, pair.public)
            cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(key)))
            val store = java.security.KeyStore.getInstance("PKCS12")
            store.load(null, password)
            store.setKeyEntry("adb", key, password, arrayOf(cert))
            val temp = File(context.noBackupFilesDir, "adb-identity.tmp")
            temp.outputStream().use { store.store(it, password) }
            check(temp.renameTo(identity)) { "No se pudo guardar la identidad ADB" }
        }
    }
    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "Motocom"
}
