package com.offnavigator.bike

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.offnavigator.core.bike.BikeQr
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Gets the phone onto the bike's wifi direct. The bike runs the group and gives out the
 * addresses, so once inside, the bike is the group's owner and the phone is whatever address
 * of its own shares the owner's network.
 *
 * Needs location permission and, from Android 13, the one for nearby wifi devices; whoever
 * calls [start] has asked for them.
 */
@SuppressLint("MissingPermission")
class BikeWifi(
    private val context: Context,
    private val log: (String) -> Unit,
    /** What the bike's QR code said. Without it, anything named like the bikes seen so far will do. */
    private val bike: BikeQr?,
    private val onJoined: (bike: InetAddress, phone: InetAddress?) -> Unit,
    /** The bike's wifi is gone: switched off, most likely. */
    private val onLeft: () -> Unit = {}
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private var listening = false
    private var asked = false
    private var joined = false
    private var triedPassword = false
    private var lastSeen = ""
    private val main = Handler(Looper.getMainLooper())

    /**
     * Carbit gets in by asking, as if a button were pressed on both sides. If the bike does not
     * let the phone in that way, the QR code's password opens its wifi without asking anybody.
     */
    private val usePassword = Runnable {
        val wifi = manager
        val on = channel
        val known = bike
        if (joined || triedPassword || wifi == null || on == null || known == null) return@Runnable
        if (known.password.length < 8 || Build.VERSION.SDK_INT < 29) {
            log("sigue fuera del wifi de la moto, y no hay contraseña que probar en este móvil")
            return@Runnable
        }
        triedPassword = true
        log("sigue fuera tras ${PASSWORD_AFTER_MS / 1000} s: entrando a ${known.ssid} con la contraseña del QR")
        val config = WifiP2pConfig.Builder().setNetworkName(known.ssid).setPassphrase(known.password).build()
        wifi.cancelConnect(on, null)
        wifi.connect(on, config, result("entrar con la contraseña"))
    }

    /**
     * Android gives up looking after a couple of minutes, and a bike switched on later would
     * never be seen: keep looking, and asking again, until the phone is in.
     */
    private val keepLooking = object : Runnable {
        override fun run() {
            if (!listening) return
            if (!joined) {
                asked = false
                manager?.discoverPeers(channel, result("buscar aparatos por wifi directo", quiet = true))
            }
            main.postDelayed(this, LOOK_AGAIN_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> lookAtPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> lookAtGroup()
            }
        }
    }

    fun start() {
        val wifi = manager ?: return log("este móvil no tiene wifi directo")
        channel = wifi.initialize(context, context.mainLooper, null)
        asked = false
        joined = false
        triedPassword = false
        lastSeen = ""
        log(if (bike != null) "buscando la moto del QR: ${bike.name}" else "sin QR leído: buscando un aparato que empiece por $DEFAULT_PREFIX")
        main.postDelayed(usePassword, PASSWORD_AFTER_MS)
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        listening = true
        // The phone may still be in the bike's group from the last time.
        lookAtGroup()
        wifi.discoverPeers(channel, result("buscar aparatos por wifi directo"))
        main.postDelayed(keepLooking, LOOK_AGAIN_MS)
    }

    fun stop() {
        main.removeCallbacks(usePassword)
        main.removeCallbacks(keepLooking)
        if (listening) runCatching { context.unregisterReceiver(receiver) }
        listening = false
        val wifi = manager ?: return
        val on = channel ?: return
        runCatching { wifi.stopPeerDiscovery(on, null) }
        if (joined) runCatching { wifi.removeGroup(on, null) }
        joined = false
        channel = null
    }

    private fun lookAtPeers() {
        if (asked || joined) return
        manager?.requestPeers(channel) { peers ->
            if (asked || joined) return@requestPeers
            val names = peers.deviceList.joinToString { it.deviceName.orEmpty() }
            val found = peers.deviceList.firstOrNull {
                val name = it.deviceName.orEmpty()
                if (bike != null) name == bike.name else name.startsWith(DEFAULT_PREFIX)
            }
            if (found == null) {
                // The same televisions over and over are worth one line.
                if (names.isNotEmpty() && names != lastSeen) log("a la vista, sin moto: $names")
                lastSeen = names
                return@requestPeers
            }
            log("moto a la vista: ${found.deviceName} (${status(found.status)})")
            asked = true
            val config = WifiP2pConfig().apply {
                deviceAddress = found.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0 // the bike runs the group
            }
            manager.connect(channel, config, result("pedir entrar en el wifi de la moto", onFailure = { asked = false }))
        }
    }

    private fun lookAtGroup() {
        manager?.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
            if (info == null || !info.groupFormed) {
                if (joined) {
                    log("fuera del wifi de la moto")
                    joined = false
                    onLeft()
                }
                return@requestConnectionInfo
            }
            if (joined) return@requestConnectionInfo
            if (info.isGroupOwner) {
                log("el móvil ha quedado de jefe del grupo: así no se sabe la dirección de la moto")
                return@requestConnectionInfo
            }
            val owner = info.groupOwnerAddress ?: return@requestConnectionInfo
            joined = true
            main.removeCallbacks(usePassword)
            runCatching { manager?.stopPeerDiscovery(channel, null) }
            val phone = ownAddressNear(owner)
            log("dentro del wifi de la moto: ella ${owner.hostAddress}, el móvil ${phone?.hostAddress ?: "?"}")
            onJoined(owner, phone)
        }
    }

    /** The phone's address on the same /24 as the bike. */
    private fun ownAddressNear(bike: InetAddress): InetAddress? {
        val net = bike.address.copyOf(3)
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && it.address.copyOf(3).contentEquals(net) }
        }.getOrNull()
    }

    private fun result(what: String, quiet: Boolean = false, onFailure: () -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (!quiet) log("$what: en marcha") }
        override fun onFailure(reason: Int) {
            val why = when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "este móvil no lo admite"
                WifiP2pManager.BUSY -> "el wifi está ocupado; ¿wifi y ubicación encendidos?"
                else -> "error $reason; ¿wifi y ubicación encendidos?"
            }
            log("$what: no se pudo ($why)")
            onFailure()
        }
    }

    private companion object {
        /** The bikes seen so far are named like "HJ-5G-XXXXXX". */
        const val DEFAULT_PREFIX = "HJ-"
        /** Carbit took seven seconds to get in by asking. */
        const val PASSWORD_AFTER_MS = 25_000L
        const val LOOK_AGAIN_MS = 30_000L
    }

    private fun status(status: Int) = when (status) {
        WifiP2pDevice.CONNECTED -> "conectada"
        WifiP2pDevice.INVITED -> "invitada"
        WifiP2pDevice.FAILED -> "falló"
        WifiP2pDevice.AVAILABLE -> "disponible"
        else -> "no disponible"
    }
}
