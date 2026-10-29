package com.motocom

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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.motocom.bike.BikeQr
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
    private val onLeft: () -> Unit,
    /** The phone got into the bike's wifi but left it with no address: the system's report is wanted. */
    private val onNoAddress: () -> Unit
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private var listening = false
    private var asked = false
    private var joined = false
    private var triedPassword = false
    /** Whether a half-made link of the phone's with the bike has been undone in this start. */
    private var cleaned = false
    /** Whether the system has said, since [start], what group the phone is in. */
    private var groupChecked = false
    /** Times the formed group has been asked about again for want of the owner's address. */
    private var ownerRetries = 0
    /** Times the bike has been looked at again waiting for [groupChecked]. */
    private var groupWaits = 0
    private val retryPeers = Runnable { lookAtPeers() }
    /** Once the phone has asked to get in, a group it ends up running is one it made by asking. */
    private var askedOnce = false
    private var lastSeen = ""
    private var otherGroup = ""
    /** How the phone itself stands in wifi direct, as the system last said. */
    private var selfStatus = -1
    /** The password's turn came while the phone was in, waiting for an address: it goes next. */
    private var passwordPending = false
    /**
     * When the phone got into what it asked for in this start, still without an address; 0 when
     * not. Only after asking, so a group of somebody else's (Smart View, Quick Share) is not it.
     */
    private var waitingSince = 0L
    /** Given up waiting with the bike's group maybe still up, which [stop] has to undo. */
    private var groupLeft = false
    /** The last failure of each quiet call, so that the same one is said once and not every 30 s. */
    private val failures = mutableMapOf<String, Int>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Carbit gets in by asking, as if a button were pressed on both sides. If the bike does not
     * let the phone in that way, the QR code's password opens its wifi without asking anybody.
     */
    private val usePassword = Runnable {
        val wifi = manager
        val on = channel
        val known = bike
        if (!listening || joined || triedPassword || wifi == null || on == null || known == null) return@Runnable
        if (waitingForAddress()) {
            // Asking now would fail on top of the link (error 0) and cut the wait for the address
            // short: it goes once the system lets this link go.
            if (!passwordPending) log("la contraseña del QR se probará cuando acabe este intento, que espera dirección")
            passwordPending = true
            return@Runnable
        }
        passwordPending = false
        // Android throws rather than refuse when the name or the password are not to its liking.
        val wrong = when {
            !known.ssid.matches(Regex("DIRECT-[a-zA-Z0-9]{2}.*")) -> "el nombre ${known.ssid} no empieza por DIRECT- y dos letras"
            known.ssid.toByteArray(Charsets.UTF_8).size > 32 -> "el nombre ${known.ssid} es demasiado largo"
            known.password.length !in 8..63 -> "la contraseña del QR no tiene entre 8 y 63 caracteres"
            else -> null
        }
        if (wrong != null) {
            log("sigue fuera del wifi de la moto, y no se puede probar la contraseña: $wrong")
            return@Runnable
        }
        triedPassword = true
        log("sigue fuera tras ${PASSWORD_AFTER_MS / 1000} s: entrando a ${known.ssid} con la contraseña del QR")
        runCatching {
            val config = WifiP2pConfig.Builder().setNetworkName(known.ssid).setPassphrase(known.password).build()
            // Connecting before the cancel is done, or with a half group still there, fails with error 0.
            val connect = {
                runCatching {
                    if (listening && !joined && on === channel) wifi.connect(on, config, result("entrar con la contraseña"))
                }.onFailure { log("entrar con la contraseña: no se pudo (${it.message ?: it})") }
            }
            val removeGroup = {
                runCatching {
                    if (!listening || on !== channel) return@runCatching
                    wifi.removeGroup(on, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { main.postDelayed({ connect() }, UNDO_WAIT_MS) }
                        override fun onFailure(reason: Int) { connect() }
                    })
                }.onFailure { connect() }
            }
            wifi.cancelConnect(on, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { removeGroup() }
                override fun onFailure(reason: Int) { removeGroup() }
            })
        }.onFailure { log("entrar con la contraseña: no se pudo (${it.message ?: it})") }
    }

    /**
     * Android gives up looking after a couple of minutes, and a bike switched on later would
     * never be seen: keep looking, and asking again, until the phone is in.
     */
    private val keepLooking = object : Runnable {
        override fun run() {
            if (!listening) return
            val on = channel
            // Looking now would take the radio off the bike's channel while it gives the address.
            if (!joined && on != null && !waitingForAddress()) {
                asked = false
                runCatching { manager?.discoverPeers(on, result("buscar aparatos por wifi directo", quiet = true)) }
            }
            // In, it has nothing to do: losing the bike's group ends this start (onLeft).
            if (!joined) main.postDelayed(this, LOOK_AGAIN_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> lookAtPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> lookAtGroup()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) log("wifi directo: el sistema dice que está apagado")
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    selfChanged(intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE))
                }
            }
        }
    }

    fun start() {
        val wifi = manager ?: return log("este móvil no tiene wifi directo")
        val on = wifi.initialize(context, context.mainLooper) {
            // The system dropped the app's line to wifi direct; calls on it will fail from now on.
            if (listening) log("el sistema ha cortado el wifi directo de la app")
        } ?: return log("no se pudo abrir el wifi directo")
        channel = on
        asked = false
        askedOnce = false
        joined = false
        triedPassword = false
        cleaned = false
        groupChecked = false
        ownerRetries = 0
        groupWaits = 0
        lastSeen = ""
        otherGroup = ""
        selfStatus = -1
        passwordPending = false
        waitingSince = 0L
        groupLeft = false
        failures.clear()
        log(if (bike != null) "buscando la moto del QR: ${bike.name}" else "sin QR leído: buscando un aparato que empiece por $DEFAULT_PREFIX")
        main.postDelayed(usePassword, PASSWORD_AFTER_MS)
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        listening = true
        // Android no longer repeats the phone's own state on registering: ask for it.
        runCatching { wifi.requestDeviceInfo(on) { self -> if (listening && on === channel) selfChanged(self) } }
        // The phone may still be in the bike's group from the last time.
        lookAtGroup()
        wifi.discoverPeers(on, result("buscar aparatos por wifi directo"))
        main.postDelayed(keepLooking, LOOK_AGAIN_MS)
    }

    fun stop() {
        // Everything this handler has waiting: the password, looking again, undoing a half link.
        main.removeCallbacksAndMessages(null)
        if (listening) runCatching { context.unregisterReceiver(receiver) }
        listening = false
        val wifi = manager ?: return
        val on = channel ?: return
        runCatching { wifi.stopPeerDiscovery(on, null) }
        // A request to get in still going on, or half done, would be left behind in the system,
        // and the next start would find the bike "connected" and fail to ask again.
        if (askedOnce && !joined) runCatching { wifi.cancelConnect(on, null) }
        // Only the bike's group: another one the phone is in is none of this app's business.
        // Waiting for an address, it is the one asked for, and left there the next start would wait on it.
        if (joined || waitingSince > 0 || groupLeft) runCatching { wifi.removeGroup(on, null) }
        waitingSince = 0L
        groupLeft = false
        joined = false
        channel = null
        runCatching { on.close() }
    }

    private fun lookAtPeers() {
        if (!listening || asked || joined) return
        val wifi = manager ?: return
        // The answer may come after stop(): it has to use the channel it was asked on, if still open.
        val on = channel ?: return
        wifi.requestPeers(on) { peers ->
            if (!listening || on !== channel || asked || joined) return@requestPeers
            val devices = peers?.deviceList.orEmpty()
            val names = devices.joinToString { it.deviceName.orEmpty() }
            val found = devices.firstOrNull {
                val name = it.deviceName.orEmpty()
                if (bike != null) name == bike.name else name.startsWith(DEFAULT_PREFIX)
            }
            if (found == null) {
                // The same televisions over and over are worth one line.
                if (names.isNotEmpty() && names != lastSeen) log("a la vista, sin moto: $names")
                lastSeen = names
                return@requestPeers
            }
            log("moto a la vista: ${found.deviceName} (${describe(found)})")
            val linked = found.status == WifiP2pDevice.CONNECTED || found.status == WifiP2pDevice.INVITED
            if (linked && !groupChecked) {
                // The phone may still be in the bike's group from the last time, which is good:
                // wait for the system to say so (start() has asked) before taking the link for a
                // broken one. If it never answers, go on as if there were no group.
                if (groupWaits++ < GROUP_WAITS) {
                    main.removeCallbacks(retryPeers)
                    main.postDelayed(retryPeers, GROUP_WAIT_MS)
                    return@requestPeers
                }
                groupChecked = true
            }
            if (linked && waitingForAddress()) return@requestPeers
            asked = true
            if (linked && !cleaned && !askedOnce && !triedPassword) {
                // "Connected" or "invited" is how the bike stands with this phone, not with others.
                // Before asking in this start, it is something left behind by an earlier attempt,
                // and asking again on top of it fails with error 0: undo it and ask afresh.
                cleaned = true
                log("el móvil ya tenía una conexión a medias con la moto: se deshace y se vuelve a pedir")
                undoHalfLink(on)
                return@requestPeers
            }
            askedOnce = true
            val config = WifiP2pConfig().apply {
                deviceAddress = found.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0 // the bike runs the group
            }
            runCatching {
                wifi.connect(on, config, result("pedir entrar en el wifi de la moto", onFailure = { asked = false }))
            }.onFailure {
                asked = false
                log("pedir entrar en el wifi de la moto: no se pudo (${it.message ?: it})")
            }
        }
    }

    /** How the phone itself stands in wifi direct: getting in is where the wait for an address starts. */
    private fun selfChanged(self: WifiP2pDevice?) {
        val now = self?.status ?: return
        val was = selfStatus
        selfStatus = now
        if (!listening || joined) return
        if (now == WifiP2pDevice.CONNECTED && was != WifiP2pDevice.CONNECTED && (askedOnce || triedPassword)) {
            // In the bike's wifi: what is missing now is the address it hands out.
            waitingSince = SystemClock.elapsedRealtime()
            checkGroup()
            main.postDelayed(noAddress, ADDRESS_WAIT_MS)
        } else if (now != WifiP2pDevice.CONNECTED && waitingSince > 0) {
            stopWaiting()
            log("el móvil ha salido del wifi de la moto sin recibir dirección")
            noAddressAfter()
        }
    }

    /**
     * In the bike's wifi, still without an address: Android is waiting for the bike's. Not past
     * [ADDRESS_WAIT_MS], in case the system never says the wait is over.
     */
    private fun waitingForAddress() =
        !joined && waitingSince > 0 && SystemClock.elapsedRealtime() - waitingSince < ADDRESS_WAIT_MS

    private fun stopWaiting() {
        waitingSince = 0L
        main.removeCallbacks(noAddress)
    }

    /** What comes after getting in without an address: telling, then the password if it was put off. */
    private fun noAddressAfter() {
        onNoAddress()
        if (listening && passwordPending) main.postDelayed(usePassword, PASSWORD_AFTER_UNDO_MS)
    }

    /** The group the phone is in while it waits: if it is not the bike's, there is no address to wait for. */
    private fun checkGroup() {
        val wifi = manager ?: return
        val on = channel ?: return
        runCatching {
            wifi.requestGroupInfo(on) { group ->
                if (!listening || on !== channel) return@requestGroupInfo
                if (group == null) return@requestGroupInfo
                val name = group.networkName.orEmpty()
                if (group.isGroupOwner || (name.isNotEmpty() && !isBikes(name, group.owner?.deviceName))) {
                    // Somebody else's group, or one of the phone's own: not a wait for the bike's address.
                    log("el móvil está en otro grupo de wifi directo ($name): no se espera dirección")
                    if (waitingSince > 0) stopWaiting()
                }
            }
        }.onFailure { log("grupo de wifi directo: no se pudo preguntar (${it.message ?: it})") }
    }

    /** Android waits about 36 s for the address; if it neither gets it nor gives up, undo it here. */
    private val noAddress = Runnable {
        val on = channel ?: return@Runnable
        if (!listening || joined || waitingSince == 0L) return@Runnable
        log("${ADDRESS_WAIT_MS / 1000} s en el wifi de la moto sin dirección: se deshace y se vuelve a pedir")
        // The system may never say the phone is out: it is taken as out from here, and the group
        // undone also by stop() if the undoing has not gone through by then.
        groupLeft = true
        stopWaiting()
        undoHalfLink(on)
        noAddressAfter()
    }

    /**
     * How the bike stands, and whether it already says it runs a group: then "connected" means
     * the phone was let in but did not get onto its wifi.
     */
    private fun describe(found: WifiP2pDevice) =
        "${status(found.status)}, jefe de grupo ${if (found.isGroupOwner) "sí" else "no"}, " +
            "botón ${if (found.wpsPbcSupported()) "sí" else "no"}, ${found.deviceAddress}"

    private fun undoHalfLink(on: WifiP2pManager.Channel) {
        val wifi = manager ?: return
        val again = Runnable {
            if (!listening || on !== channel || joined) return@Runnable
            asked = false
            runCatching { wifi.discoverPeers(on, result("buscar aparatos por wifi directo", quiet = true)) }
        }
        val removeGroup = {
            if (listening && on === channel) runCatching {
                wifi.removeGroup(on, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { main.postDelayed(again, UNDO_WAIT_MS) }
                    override fun onFailure(reason: Int) { main.postDelayed(again, UNDO_WAIT_MS) }
                })
            }.onFailure { main.postDelayed(again, UNDO_WAIT_MS) }
        }
        runCatching {
            wifi.cancelConnect(on, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { removeGroup() }
                override fun onFailure(reason: Int) { removeGroup() }
            })
        }.onFailure { removeGroup() }
    }

    private fun lookAtGroup() {
        val wifi = manager ?: return
        val on = channel ?: return
        wifi.requestConnectionInfo(on) { info: WifiP2pInfo? ->
            if (!listening || on !== channel) return@requestConnectionInfo
            if (info == null || !info.groupFormed) {
                groupChecked = true
                ownerRetries = 0
                otherGroup = ""
                if (joined) {
                    log("fuera del wifi de la moto")
                    joined = false
                    onLeft()
                }
                return@requestConnectionInfo
            }
            if (joined) return@requestConnectionInfo
            if (info.isGroupOwner) {
                if (askedOnce) {
                    // Asking to get in made a group of the phone's own instead of joining the
                    // bike's, and there the bike's address is not known: undo it and look again.
                    log("el móvil ha quedado de jefe del grupo: se deshace y se sigue buscando")
                    runCatching { wifi.removeGroup(on, null) }
                    asked = false
                } else {
                    sayOtherGroup("uno en el que el móvil es el jefe")
                }
                groupChecked = true
                return@requestConnectionInfo
            }
            val owner = info.groupOwnerAddress
            if (owner == null) {
                // Seen as silence before: the address may come a moment later, so ask a few times.
                if (ownerRetries == 0) log("hay un grupo de wifi directo formado, pero el sistema no da la dirección del jefe")
                if (ownerRetries++ < OWNER_RETRIES) main.postDelayed({ lookAtGroup() }, GROUP_WAIT_MS)
                else groupChecked = true
                return@requestConnectionInfo
            }
            wifi.requestGroupInfo(on) { group ->
                if (!listening || on !== channel || joined) return@requestGroupInfo
                groupChecked = true
                // Some phones give no group here even with one formed; without a name, it is
                // taken for the bike's as it always was.
                val name = group?.networkName.orEmpty()
                if (name.isNotEmpty() && !isBikes(name, group?.owner?.deviceName)) {
                    return@requestGroupInfo sayOtherGroup(if (bike != null) "$name (se esperaba ${bike.ssid})" else name)
                }
                joined = true
                otherGroup = ""
                passwordPending = false
                main.removeCallbacks(usePassword)
                stopWaiting()
                runCatching { wifi.stopPeerDiscovery(on, null) }
                val phone = ownAddressNear(owner)
                log("dentro del wifi de la moto: ella ${owner.hostAddress}, el móvil ${phone?.hostAddress ?: "?"}")
                onJoined(owner, phone)
            }
        }
    }

    /**
     * The QR's network if there is one; otherwise one named like the bikes seen so far. Loose
     * on purpose: what Android reports for the bike's group has not been seen yet, and being
     * wrong here would keep the phone in the bike's wifi doing nothing.
     */
    private fun isBikes(network: String, ownerName: String?): Boolean {
        if (bike == null) return network.substringAfter("DIRECT-", "").contains(DEFAULT_PREFIX, ignoreCase = true)
        return network.equals(bike.ssid, ignoreCase = true) ||
            network.endsWith(bike.name, ignoreCase = true) ||
            ownerName.equals(bike.name, ignoreCase = true)
    }

    private fun sayOtherGroup(name: String) {
        if (name != otherGroup) log("el móvil está en otro wifi directo: $name")
        otherGroup = name
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
        override fun onSuccess() {
            failures.remove(what)
            if (!quiet) log("$what: en marcha")
        }
        override fun onFailure(reason: Int) {
            val why = when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "este móvil no lo admite"
                WifiP2pManager.BUSY -> "el wifi está ocupado; ¿wifi y ubicación encendidos?"
                else -> "error $reason; ¿wifi y ubicación encendidos?"
            }
            if (!quiet || failures[what] != reason) log("$what: no se pudo ($why)")
            failures[what] = reason
            onFailure()
        }
    }

    private companion object {
        /** The bikes seen so far are named like "HJ-5G-XXXXXX". */
        const val DEFAULT_PREFIX = "HJ-"
        /** Carbit took seven seconds to get in by asking. */
        const val PASSWORD_AFTER_MS = 25_000L
        const val LOOK_AGAIN_MS = 30_000L
        /** Time for the system to let go of the half-made link before asking again. */
        const val UNDO_WAIT_MS = 2_000L
        /** Time for the system to answer about the group before looking at the bike again. */
        const val GROUP_WAIT_MS = 1_000L
        const val OWNER_RETRIES = 5
        const val GROUP_WAITS = 5
        /** Longer than Android's own 36 s, which should be the one to give up. */
        const val ADDRESS_WAIT_MS = 50_000L
        /** After undoing the link: the undo and the looking again that follows it, done first. */
        const val PASSWORD_AFTER_UNDO_MS = 5_000L
    }

    private fun status(status: Int) = when (status) {
        WifiP2pDevice.CONNECTED -> "conectada"
        WifiP2pDevice.INVITED -> "invitada"
        WifiP2pDevice.FAILED -> "falló"
        WifiP2pDevice.AVAILABLE -> "disponible"
        else -> "no disponible"
    }
}
