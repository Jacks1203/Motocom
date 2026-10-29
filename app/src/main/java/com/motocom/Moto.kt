package com.motocom

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import com.motocom.bike.BikeEvents
import com.motocom.bike.BikeLink
import com.motocom.bike.BikeQr
import com.motocom.bike.BikeState
import com.motocom.bike.PhoneCard
import com.motocom.shell.ShellLink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Puts the chosen app on the bike's screen: joins the bike's wifi direct, lets it know the
 * phone is there, and serves it the app as the bike asks for it ([MirrorPicture]).
 *
 * The same as OffNavigator's BikeScreen, with the picture being another app instead of a view
 * of its own. While running it keeps at it: if the bike goes (switched off at a stop), it looks
 * for it again. Not for ever: with no bike in [SEARCH_MS] of starting, or [AFTER_LEFT_MS] of
 * losing it, it gives up, since looking keeps the wifi busy with the phone in a pocket.
 *
 * The bike lives in a garage with no computer near it, so every attempt writes what happened
 * to `moto_log.txt`, and [share] sends that file from the phone later.
 */
class Moto private constructor(private val context: Context) {
    val lines = mutableStateListOf<String>()
    val state = mutableStateOf(BikeState.CLOSED)
    val running = mutableStateOf(false)

    /** Where the chosen app is while running: on the bike, brought to the phone, or neither yet. */
    val appPlace = mutableStateOf(AppPlace.NOWHERE)

    private val prefs = context.getSharedPreferences("motocom_moto", Context.MODE_PRIVATE)

    /** Package of the app that goes to the bike. */
    val chosenApp = mutableStateOf(prefs.getString(KEY_APP, null))

    /** How big things look on the bike: higher, bigger letters and less map. */
    val dpi = mutableIntStateOf(prefs.getInt(KEY_DPI, DEFAULT_DPI))

    /** The bike whose QR code was last read off its screen; null until one is read. */
    val bike = mutableStateOf(prefs.getString(KEY_QR, null)?.let(BikeQr::parse))

    private val main = Handler(Looper.getMainLooper())
    private val file = File(context.getExternalFilesDir(null), "moto_log.txt")
    /** The one thread that touches [file], in order, so no line waits on the disk. */
    private val disk = Executors.newSingleThreadExecutor { job -> Thread(job, "moto-registro").apply { isDaemon = true } }

    /** Connect was pressed before ADB was up: it connects by itself as soon as it is. */
    val waitingForAdb = mutableStateOf(false)

    init {
        disk.execute {
            // The recording of what went to the bike, from 0.18, is no longer made: its space back.
            File(context.getExternalFilesDir(null), "moto_video.h264").delete()
            trimLog()
            // Only the end, without the system's reports, which are for whoever reads the file.
            val old = runCatching { if (file.exists()) tail(TAIL_BYTES) else null }.getOrNull()
                ?.lines()?.drop(1)?.filter { it.isNotEmpty() && !it.startsWith(REPORT_INDENT) }?.takeLast(200)
            if (old != null) main.post { lines.addAll(0, old) }
        }
        ShellLink.onReady = {
            if (waitingForAdb.value) {
                waitingForAdb.value = false
                main.removeCallbacks(adbTooSlow)
                log("ADB ya está en marcha: conectando")
                start()
            }
        }
        ShellLink.onLost = {
            if (running.value) {
                toast(text(R.string.adb_lost))
                stop()
            }
        }
        ShellLink.init(context, ::log)
    }

    /**
     * Connects now if ADB is up, or as soon as it comes up. The service starts at once
     * either way: Android lets it start only while the app is in front, and by the time
     * ADB is up the user is in ADB.
     */
    fun connect() {
        if (running.value || waitingForAdb.value) return
        whyNot()?.let {
            log("no se conecta: ${Said(it).forLog(context)}")
            toast(text(it))
            return
        }
        // Not a reason to stop: maybe it is enough on this phone, and the record will tell.
        // The home screen already says so.
        if (!ShellLink.usbDebuggingOn()) log("aviso: la depuración USB está apagada; sin ella ADB se para al cambiar de wifi")
        ShellLink.refresh()
        if (ShellLink.ready) {
            start()
            return
        }
        waitingForAdb.value = true
        BikeService.start(context)
        main.postDelayed(adbTooSlow, WAIT_ADB_MS)
        log("esperando a que ADB esté en marcha para conectar")
        if (ShellLink.paired.value) ShellLink.quickStart()
    }

    /** What keeps the bike from being found before even trying, said so it can be put right. */
    private fun whyNot(): Int? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        if (wifi?.isWifiEnabled != true) return R.string.wifi_off
        // Before Android 13, looking for wifi devices needs the location on.
        if (Build.VERSION.SDK_INT < 33) {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            if (location == null || !LocationManagerCompat.isLocationEnabled(location)) {
                return R.string.location_off
            }
        }
        if (chosenComponent() == null) return R.string.app_gone_choose
        return null
    }

    /** ADB never came up: the phone is not kept awake for nothing. */
    private val adbTooSlow = Runnable {
        if (!waitingForAdb.value) return@Runnable
        log("ADB no se ha puesto en marcha en ${WAIT_ADB_MS / 60_000} min: se deja de esperar")
        toast(text(R.string.adb_too_slow))
        cancelWaiting()
    }

    fun cancelWaiting() {
        if (!waitingForAdb.value) return
        waitingForAdb.value = false
        main.removeCallbacks(adbTooSlow)
        if (!running.value) BikeService.stop(context)
    }

    @Volatile private var link: BikeLink? = null
    private var linkEvents: LinkEvents? = null
    private var wifi: BikeWifi? = null
    /** The other way in: the bike's wifi joined as an ordinary one ([BikeNetwork]). */
    private var network: BikeNetwork? = null
    /** That ordinary wifi once in it, which the calls to the bike have to go on. */
    @Volatile private var bikeNetwork: Network? = null
    /** Which way this run is trying, or got in by. */
    private var route = Route.WIFI_DIRECT
    /** The ordinary wifi got in once in this run: when it goes, it is the bike switched off. */
    private var wifiWorked = false
    /** The ordinary wifi failed before Android ever let the app in: not tried again this run. */
    private var wifiRefused = false

    /** Where the bike and the phone are on the bike's wifi, once in it; kept to call again. */
    @Volatile private var bikeAddress: InetAddress? = null
    @Volatile private var phoneAddress: InetAddress? = null

    /** One for the whole run, so the app stays on the bike's screen between attempts. */
    private var picture: MirrorPicture? = null

    /** Since when the system's report has things not yet in the record. */
    private var reportSince = 0L
    /** Whether this run already has a report on getting in without an address. */
    private var reportedNoAddress = false

    /** What is asked for before [connect]: the wifi-direct one, and notifications, which are optional. */
    val permissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Asked together, as Android 12 wants; only the fine one finds wifi devices.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.toTypedArray()

    /**
     * What has to be granted for the bike to be found. Since Android 13 that is only nearby
     * devices, declared as not for location; notifications only hide the service's notice.
     */
    private val required: List<String>
        get() = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingPermissions() =
        required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun choose(packageName: String) {
        prefs.edit().putString(KEY_APP, packageName).apply()
        chosenApp.value = packageName
    }

    /** The chosen app is gone (uninstalled or disabled): forget it rather than show its package. */
    fun forgetMissingApp(): Boolean {
        val pkg = chosenApp.value ?: return false
        if (context.packageManager.getLaunchIntentForPackage(pkg) != null) return false
        log("la app elegida ($pkg) ya no se puede abrir: se olvida")
        prefs.edit().remove(KEY_APP).apply()
        chosenApp.value = null
        return true
    }

    /** Takes what the camera read off the bike's screen. False if it is not a bike's code. */
    fun readQr(text: String): Boolean {
        val read = BikeQr.parse(text)
        if (read == null) {
            log("QR leído, pero no es de una moto: ${text.take(200)}")
            return false
        }
        prefs.edit().putString(KEY_QR, text).apply()
        bike.value = read
        log("QR de la moto: ${text.replace(Regex("(?i)(pwd=)[^&]*"), "$1***")}")
        return true
    }

    fun setDpi(value: Int) {
        prefs.edit().putInt(KEY_DPI, value).apply()
        dpi.intValue = value
    }

    /** The chosen app's package and the activity it opens with. */
    private fun chosenComponent(): Pair<String, String>? {
        val pkg = chosenApp.value ?: return null
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return null
        return pkg to launch.flattenToShortString()
    }

    fun start() {
        if (running.value) return
        running.value = true
        appPlace.value = AppPlace.NOWHERE
        ShellLink.refresh()
        var made: MirrorPicture? = null
        made = MirrorPicture(::log, ::chosenComponent, { dpi.intValue }) { place ->
            // A picture let go of says so late; only the current one speaks for the run.
            main.post { if (running.value && picture === made) appPlace.value = place }
        }
        picture = made
        reportSince = System.currentTimeMillis() - REPORT_BEFORE_MS
        reportedNoAddress = false
        wifiWorked = false
        wifiRefused = false
        // The way that worked last time with this bike goes first.
        route = if (bike.value?.ssid?.let { it == prefs.getString(KEY_ROUTE_WIFI, null) } == true) Route.WIFI else Route.WIFI_DIRECT
        BikeService.start(context)
        giveUpIn(SEARCH_MS)
        attempt()
    }

    /** No bike for a while: stop looking. Showing on the bike, it is called off. */
    private val giveUp = Runnable {
        if (!running.value || state.value == BikeState.SHOWING) return@Runnable
        log("sin moto en ${lookedForMs / 60_000} min: se deja de buscar")
        toast(text(R.string.bike_not_found))
        stop()
    }
    private var lookedForMs = 0L

    private fun giveUpIn(ms: Long) {
        main.removeCallbacks(giveUp)
        lookedForMs = ms
        main.postDelayed(giveUp, ms)
    }

    /** One go at the bike, from finding it to showing it; another when it goes and comes back. */
    private fun attempt() {
        header()
        bikeAddress = null
        phoneAddress = null
        bikeNetwork = null
        if (!openLink()) return
        log("escuchando en 10920, 10921 y 10922")
        startRoute()
    }

    /** Into the bike's wifi the way [route] says: as wifi direct, or as an ordinary wifi. */
    private fun startRoute() {
        val qr = bike.value
        if (route == Route.WIFI && qr != null) {
            var made: BikeNetwork? = null
            made = BikeNetwork(
                context, ::log, qr,
                approved = prefs.getString(KEY_APPROVED, null) == qr.ssid,
                onJoined = { bike, phone, joinedOn ->
                    if (network === made) {
                        // Android does not ask again for a network it has let this app join.
                        prefs.edit().putString(KEY_APPROVED, qr.ssid).apply()
                        wifiWorked = true
                        bikeNetwork = joinedOn
                        joined(bike, phone)
                    }
                },
                onFailed = { why -> main.post { if (network === made) wifiFailed(why) } },
                onLeft = { if (network === made) bikeLeft() }
            )
            network = made
            made.start()
            return
        }
        var made: BikeWifi? = null
        made = BikeWifi(
            context, ::log, qr,
            onJoined = { bike, phone -> joined(bike, phone) },
            onLeft = { bikeLeft() },
            onNoAddress = {
                // Once a run: later ones would repeat it.
                if (!reportedNoAddress) {
                    reportedNoAddress = true
                    systemReport("el móvil entró en el wifi de la moto pero no recibió dirección")
                }
                // On some phones wifi direct never hands out the address: the same wifi, joined
                // as an ordinary one, gets it the ordinary way.
                if (qr != null && !wifiRefused) main.post { if (wifi === made) switchRoute(Route.WIFI, "el wifi directo no da dirección") }
            }
        )
        wifi = made
        made.start()
    }

    /**
     * The ordinary wifi did not get in. Having got in before in this run, the bike is off or out
     * of reach: it is asked for again, not wifi direct, which would only get in without an
     * address. Never let in, it is not asked for again this run, so Android's question does not
     * come back over and over.
     */
    private fun wifiFailed(why: String) {
        if (wifiWorked) {
            log("se vuelve a pedir el wifi normal de la moto, que ya funcionó")
            network?.stop()
            network = null
            retryRouteLater()
            return
        }
        if (prefs.getString(KEY_APPROVED, null) != bike.value?.ssid) wifiRefused = true
        switchRoute(Route.WIFI_DIRECT, why)
    }

    /** One way did not get in: the other one, after a moment for the first to let go. */
    private fun switchRoute(next: Route, why: String) {
        if (!running.value || bikeAddress != null) return
        log("se prueba a entrar ${next.how} ($why)")
        wifi?.stop()
        wifi = null
        network?.stop()
        network = null
        route = next
        retryRouteLater()
    }

    /** The way in again, after a moment for the last try to let go. */
    private fun retryRouteLater() {
        main.postDelayed({ if (running.value && link != null && !onTheWay()) startRoute() }, RETRY_MS)
    }

    /** Whether one of the ways in is going, or in. */
    private fun onTheWay() = wifi != null || network != null

    /** Opens the phone's ports for the bike. False, and everything stopped, if they are taken. */
    private fun openLink(): Boolean {
        val keys = BikeKeys(context)
        val metrics = context.resources.displayMetrics
        // Carbit Ride's name, which is the one the bike is known to take.
        val card = PhoneCard(
            uuid = keys.uuid,
            brand = Build.BRAND,
            model = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            bluetoothName = Settings.Global.getString(context.contentResolver, "device_name") ?: Build.MODEL,
            screenWidth = minOf(metrics.widthPixels, metrics.heightPixels),
            screenHeight = maxOf(metrics.widthPixels, metrics.heightPixels),
            publicKey = keys.publicKey,
            sign = keys::sign
        )
        val shown = picture ?: return false
        val events = LinkEvents()
        // Only the bike may talk to these ports, not whoever shares a café's wifi with the
        // phone; a test build also lets in the cable, for codigo/moto_falsa.py.
        val opened = BikeLink(card, shown, events, allow = { from, to ->
            from == bikeAddress || (BuildConfig.CABLE_TESTS && from.isLoopbackAddress) ||
                // The bike's address on an ordinary wifi is a guess: any of that network will do,
                // coming in on the phone's address there and not another network's.
                (bikeNetwork != null && to != null && to == phoneAddress && sameNetwork(from, phoneAddress))
        })
        events.link = opened
        try {
            opened.open()
        } catch (e: IOException) {
            // Carbit and OffNavigator listen on the very same ports, and only one can have them.
            log("no se pueden abrir los puertos (${e.message}). Si Carbit u OffNavigator están abiertos, ciérralos del todo y vuelve a probar.")
            toast(text(R.string.ports_taken))
            events.retired = true
            thread(isDaemon = true) { opened.close() }
            stop()
            return false
        }
        link = opened
        linkEvents = events
        return true
    }

    /** What one link says, heeded only while it is the current one. */
    private inner class LinkEvents : BikeEvents {
        @Volatile var retired = false
        var link: BikeLink? = null

        override fun log(line: String) = this@Moto.log(line)

        override fun state(state: BikeState) {
            main.post {
                if (retired || !running.value) return@post
                if (state == BikeState.SHOWING && this@Moto.state.value != BikeState.SHOWING) {
                    rememberRoute()
                    main.removeCallbacks(giveUp)
                    toast(text(R.string.connected))
                }
                this@Moto.state.value = state
            }
        }

        override fun gone(why: String) {
            main.post { link?.let { if (!retired) relink(it, why) } }
        }
    }

    private fun joined(bike: InetAddress, phone: InetAddress?) {
        bikeAddress = bike
        phoneAddress = phone
        link?.let { knockUntilHeard(it, bike, phone) }
    }

    /** The way that got the app onto the bike, so the next run goes straight that way. */
    private fun rememberRoute() {
        val ssid = bike.value?.ssid ?: return
        if (route == Route.WIFI) prefs.edit().putString(KEY_ROUTE_WIFI, ssid).apply()
        else if (prefs.getString(KEY_ROUTE_WIFI, null) == ssid) prefs.edit().remove(KEY_ROUTE_WIFI).apply()
    }

    private fun sameNetwork(a: InetAddress, b: InetAddress?): Boolean =
        b != null && a.address.size == 4 && b.address.size == 4 && a.address.copyOf(3).contentEquals(b.address.copyOf(3))

    /**
     * The bike's wifi went away, as when it is switched off at a stop. Start over and keep
     * looking, so switching it on again is all it takes. The app stays on the bike's screen.
     */
    private fun bikeLeft() {
        if (!running.value) return
        log("la moto se ha ido (¿apagada?): se la vuelve a buscar")
        toast(text(R.string.bike_left, AFTER_LEFT_MS / 60_000))
        giveUpIn(AFTER_LEFT_MS)
        closeAttempt()
        state.value = BikeState.LISTENING
        main.postDelayed({ if (running.value && link == null && !onTheWay()) attempt() }, RETRY_MS)
    }

    /**
     * The bike stopped talking with its wifi still up, as when its screen starts its session
     * over. The wifi stays; the ports open afresh and the bike is called again.
     */
    private fun relink(from: BikeLink, why: String) {
        if (!running.value || link !== from) return
        log("la moto ha dejado de hablar ($why): se la vuelve a llamar")
        giveUpIn(AFTER_LEFT_MS)
        retireLink()
        state.value = BikeState.LISTENING
        main.postDelayed({
            if (!running.value || link != null || !onTheWay()) return@postDelayed
            if (!openLink()) return@postDelayed
            val bike = bikeAddress ?: return@postDelayed
            link?.let { knockUntilHeard(it, bike, phoneAddress) }
        }, RETRY_MS)
    }

    private fun retireLink() {
        linkEvents?.retired = true
        linkEvents = null
        val closing = link
        link = null
        // Closing sockets is network work, which Android does not allow on the main thread.
        thread(isDaemon = true) { closing?.close() }
    }

    private fun closeAttempt() {
        wifi?.stop()
        wifi = null
        network?.stop()
        network = null
        bikeNetwork = null
        bikeAddress = null
        retireLink()
    }

    fun stop() {
        cancelWaiting()
        if (!running.value) return
        main.removeCallbacks(giveUp)
        running.value = false
        closeAttempt()
        val done = picture
        picture = null
        // The app comes back to the phone.
        thread(isDaemon = true) { done?.release() }
        BikeService.stop(context)
        state.value = BikeState.CLOSED
        appPlace.value = AppPlace.NOWHERE
        log("parado")
    }

    /** The app to the phone, to type a destination; [toBike] sends it back. */
    fun toPhone() {
        picture?.toPhone()
    }

    fun toBike() {
        picture?.toBike()
    }

    /** Sends the whole record, every attempt in it, through WhatsApp, mail, Drive... */
    fun share(activity: Context) {
        // Copied on the record's own thread, after every line already said; sent from the main one.
        disk.execute {
            val sent = runCatching {
                if (!file.exists() || file.length() == 0L) return@runCatching null
                val exports = File(activity.cacheDir, "exports").apply { mkdirs() }
                exports.listFiles()?.forEach { it.delete() }
                val name = "registro-moto-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now())}.txt"
                val out = file.copyTo(File(exports, name), overwrite = true)
                name to FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", out)
            }
            main.post {
                try {
                    val (name, uri) = sent.getOrThrow() ?: run {
                        toast(text(R.string.no_log))
                        return@post
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, name.removeSuffix(".txt"))
                        clipData = ClipData.newRawUri(name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(send, text(R.string.send_log)))
                } catch (e: Exception) {
                    toast(text(R.string.send_log_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** What an attempt starts from, for whoever reads the record without having been there. */
    private fun header() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        log("======== intento del ${DAY.format(LocalDateTime.now())} ========")
        log("Motocom ${BuildConfig.VERSION_NAME} en ${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        log("wifi ${if (wifi?.isWifiEnabled == true) "encendido" else "APAGADO"}, " +
            "ubicación ${if (location != null && LocationManagerCompat.isLocationEnabled(location)) "encendida" else "APAGADA"}, " +
            "ADB ${if (ShellLink.ready) "READY" else "NOT_RUNNING"}, " +
            "depuración USB ${if (ShellLink.usbDebuggingOn()) "encendida" else "APAGADA"}, app ${chosenApp.value ?: "ninguna"} a ${dpi.intValue} ppp")
    }

    /**
     * What the system itself says about the wifi direct links since the last report, written
     * into the record only, for a phone too far away to plug in. Needs the ADB worker.
     */
    private fun systemReport(why: String) {
        val shell = ShellLink.display ?: return log("informe del sistema ($why): no se puede sacar sin ADB")
        val since = reportSince
        reportSince = System.currentTimeMillis()
        log("informe del sistema ($why): sacándolo")
        thread(name = "moto-informe", isDaemon = true) {
            // An ADB worker from before an update would not know the call, and answers nothing.
            val text = try {
                shell.wifiReport(since) ?: "el proceso ADB es de una versión anterior: reinicia el móvil y vuelve a probar"
            } catch (e: Exception) {
                "no se pudo sacar (${e.message ?: e.javaClass.simpleName})"
            }
            // Whatever a phone maker prints of it, the bike's password does not travel in the record.
            val secret = bike.value?.password?.takeIf { it.isNotEmpty() }
            val body = text.let { if (secret != null) it.replace(secret, "***") else it }
                .replace(Regex("(?i)(passphrase|psk|password|pwd)(\"?\\s*[=:]\\s*)(\"[^\"]*\"|(?!<)\\S+)"), "$1$2***")
                .lines()
            disk.execute {
                val now = STAMP.format(LocalDateTime.now())
                runCatching {
                    file.appendText(buildString {
                        append("$now  ======== informe del sistema: $why ========\n")
                        body.forEach { append(REPORT_INDENT).append(it).append('\n') }
                        append("$now  ======== fin del informe ========\n")
                    })
                }
            }
            log("informe del sistema: ${body.size} líneas guardadas en el registro (salen al enviarlo)")
        }
    }

    /** The last [bytes] of the record; the first line may come cut. Only on [disk]. */
    private fun tail(bytes: Long): String = RandomAccessFile(file, "r").use { raf ->
        val from = maxOf(0L, raf.length() - bytes)
        raf.seek(from)
        val bytes = ByteArray((raf.length() - from).toInt())
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }

    /** Keeps the record from growing for ever, reading only the half it keeps. Only on [disk]. */
    private fun trimLog() {
        runCatching {
            if (file.length() > MAX_BYTES) file.writeText(tail(MAX_BYTES / 2L).substringAfter('\n'))
        }
    }

    /**
     * The bike only comes once it has been called, and the first call can be too early: keep
     * calling until it comes, for as long as this link is the one (giving up is [giveUp]'s).
     */
    private fun knockUntilHeard(link: BikeLink, bike: InetAddress, phone: InetAddress?) {
        thread(name = "moto-llamar", isDaemon = true) {
            var calls = 0
            while (this.link === link && link.bikeInfo == null) {
                link.knock(bike, phone, bikeNetwork)
                if (++calls == 10) log("la moto no ha venido tras 10 llamadas: se sigue llamando")
                Thread.sleep(3000)
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = Language.text(context, id, *args)

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    private fun log(line: String) {
        val stamped = "${STAMP.format(LocalDateTime.now())}  $line"
        disk.execute { runCatching { file.appendText(stamped + "\n") } }
        main.post {
            lines += stamped
            if (lines.size > 400) lines.removeRange(0, 100)
        }
    }

    companion object {
        /** Room for a few runs with the system's reports in them. */
        private const val MAX_BYTES = 4_000_000
        /** How the report's lines start in the record, which is also how they are kept off the screen. */
        private const val REPORT_INDENT = "    | "
        /** The report starts a little before Connect, to catch what led up to it. */
        private const val REPORT_BEFORE_MS = 10_000L
        const val SEARCH_MS = 3 * 60 * 1000L
        const val AFTER_LEFT_MS = 10 * 60 * 1000L
        /** How long Connect waits for ADB before letting the phone sleep again. */
        const val WAIT_ADB_MS = 3 * 60 * 1000L
        /** Time for the last attempt to let go of the ports. */
        private const val RETRY_MS = 3000L

        private const val KEY_APP = "app"
        private const val KEY_DPI = "ppp"
        private const val KEY_QR = "qr"
        /** The bike (its network's name) last got onto as an ordinary wifi, so it goes that way first. */
        private const val KEY_ROUTE_WIFI = "camino_wifi"
        /** The bike's network Android has already let this app join. */
        private const val KEY_APPROVED = "wifi_aceptada"
        /** About the last 200 lines of the record. */
        private const val TAIL_BYTES = 200_000L
        private val STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
        /** 592 pixels across at this density is about a phone's width of map. */
        const val DEFAULT_DPI = 240

        @Volatile private var instance: Moto? = null

        fun get(context: Context): Moto =
            instance ?: synchronized(this) {
                instance ?: Moto(context.applicationContext).also { instance = it }
            }
    }
}

/** The two ways into the bike's wifi. */
private enum class Route(val how: String) {
    WIFI_DIRECT("por wifi directo"),
    WIFI("como wifi normal")
}
