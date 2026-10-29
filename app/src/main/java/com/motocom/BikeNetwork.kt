package com.motocom

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.motocom.bike.BikeQr
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The other way into the bike: its wifi direct joined as an ordinary wifi, with the QR's name and
 * password, as a phone would join any other network. The address then comes from the ordinary
 * wifi's DHCP, not wifi direct's, which is the one some phones never get an address from (a
 * Galaxy A15 got into the bike's wifi direct and waited 36 s for nothing, while Carbit got in).
 *
 * The network is the app's alone, asked for without internet, so mobile data stays the way out
 * and the calls to the bike have to be made on it ([Network.bindSocket]). The first time,
 * Android asks the user to let the app join it.
 */
class BikeNetwork(
    context: Context,
    private val log: (String) -> Unit,
    private val bike: BikeQr,
    /** Whether Android has let this app join this bike's network before, so it will not ask. */
    private val approved: Boolean,
    private val onJoined: (bike: InetAddress, phone: InetAddress?, network: Network) -> Unit,
    /** Android could not join it, or the user said no: try the other way. */
    private val onFailed: (why: String) -> Unit,
    /** The bike's wifi is gone: switched off, most likely. */
    private val onLeft: () -> Unit
) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var joined: Network? = null

    fun start() {
        val manager = connectivity ?: return fail("este móvil no deja pedir redes")
        val wrong = when {
            bike.password.length !in 8..63 -> "la contraseña del QR no tiene entre 8 y 63 caracteres"
            bike.ssid.toByteArray(Charsets.UTF_8).size > 32 -> "el nombre ${bike.ssid} es demasiado largo"
            else -> null
        }
        if (wrong != null) return fail(wrong)
        val request = runCatching {
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // Not the way to the internet: mobile data stays it.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(bike.ssid).setWpa2Passphrase(bike.password).build())
                .build()
        }.getOrElse { return fail("Android no acepta la red ${bike.ssid} (${it.message ?: it})") }
        val made = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { if (callback === this) log("wifi normal de la moto: dentro, esperando su dirección") }
            }

            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                main.post { if (callback === this) looked(network, link) }
            }

            override fun onUnavailable() {
                main.post { if (callback === this) fail("Android no ha podido entrar o no se le ha dejado (¿se rechazó el aviso?)") }
            }

            override fun onLost(network: Network) {
                main.post {
                    if (callback !== this) return@post
                    if (joined == null) return@post fail("la red se ha perdido antes de tener dirección")
                    log("wifi normal de la moto: perdido")
                    stop()
                    onLeft()
                }
            }
        }
        callback = made
        val wait = if (approved) LATER_MS else FIRST_MS
        log("entrando a ${bike.ssid} como wifi normal con la contraseña del QR" +
            if (approved) "" else " (Android pedirá permiso la primera vez: hay que aceptar)")
        runCatching { manager.requestNetwork(request, made, wait.toInt()) }
            .onFailure { fail("no se pudo pedir la red (${it.message ?: it})") }
    }

    fun stop() {
        val made = callback ?: return
        callback = null
        joined = null
        runCatching { connectivity?.unregisterNetworkCallback(made) }
    }

    /** Once the network has an IPv4 address of the phone's: then the bike's is known too. */
    private fun looked(network: Network, link: LinkProperties) {
        if (joined != null) return
        val phone = link.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address } ?: return
        // The bike hands out the addresses, so it is the way out and the DHCP server; failing
        // both, the .1 of the phone's network, as the bikes seen so far are.
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway
        val server = if (Build.VERSION.SDK_INT >= 30) link.dhcpServerAddress?.takeIf { !it.isAnyLocalAddress } else null
        val bikeAddress = gateway ?: server ?: InetAddress.getByAddress(phone.address.copyOf().also { it[3] = 1 })
        val how = when (bikeAddress) {
            gateway -> "la puerta de enlace"
            server -> "el servidor DHCP"
            else -> "la .1 de la red, a falta de otra"
        }
        joined = network
        log("dentro del wifi de la moto como wifi normal (${link.interfaceName ?: "?"}): ella ${bikeAddress.hostAddress} ($how), el móvil ${phone.hostAddress}")
        onJoined(bikeAddress, phone, network)
    }

    private fun fail(why: String) {
        stop()
        log("wifi normal de la moto: no se pudo ($why)")
        onFailed(why)
    }

    private companion object {
        /** Time for the user to read Android's question and say yes. */
        const val FIRST_MS = 90_000L
        /** Once allowed, Android joins it without asking; past this, it is not there. */
        const val LATER_MS = 30_000L
    }
}
