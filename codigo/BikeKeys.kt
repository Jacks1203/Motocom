package com.offnavigator.bike

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher

/**
 * The key pair the phone shows the bike. It is nobody's secret: the phone makes it up the first
 * time, hands the bike the public half and the bike's identifier encrypted with the private
 * half, and the bike sends that back when it asks for the picture.
 */
class BikeKeys(context: Context) {
    private val prefs = context.getSharedPreferences("offnav_moto", Context.MODE_PRIVATE)

    val uuid: String
        get() = prefs.getString("uuid", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("uuid", it).apply() }

    val publicKey: String
        get() {
            ensure()
            return prefs.getString("publica", "")!!
        }

    fun sign(bikeId: String): String {
        ensure()
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(prefs.getString("privada", ""), Base64.NO_WRAP)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(bikeId.toByteArray()), Base64.NO_WRAP)
    }

    private fun ensure() {
        if (prefs.contains("privada")) return
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        prefs.edit()
            .putString("privada", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString("publica", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
    }
}
