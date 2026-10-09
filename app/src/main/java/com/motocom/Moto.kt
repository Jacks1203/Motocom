package com.motocom

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val prefs = context.getSharedPreferences("motocom_moto", Context.MODE_PRIVATE)

    /** Package of the app that goes to the bike. */
    val chosenApp = mutableStateOf(prefs.getString(KEY_APP, null))

    /** How big things look on the bike: higher, bigger letters and less map. */
    val dpi = mutableIntStateOf(prefs.getInt(KEY_DPI, DEFAULT_DPI))

    /** The bike whose QR code was last read off its screen; null until one is read. */
    val bike = mutableStateOf(prefs.getString(KEY_QR, null)?.let(BikeQr::parse))

    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US)
    private val file = File(context.getExternalFilesDir(null), "moto_log.txt")

    /** Connect was pressed before ADB was up: it connects by itself as soon as it is. */
    val waitingForAdb = mutableStateOf(false)

    init {
        runCatching { if (file.exists()) lines += file.readLines().takeLast(200) }
        ShellLink.onReady = {
            if (waitingForAdb.value) {
                waitingForAdb.value = false
                log("ADB ya está en marcha: conectando")
                start()
            }
        }
        ShellLink.onLost = {
            if (running.value) {
                toast("ADB se ha parado: la moto se queda sin imagen. Vuelve a pulsar Conectar.")
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
        if (running.value) return
        ShellLink.refresh()
        if (ShellLink.status.value == ShellLink.Status.READY) {
            start()
            return
        }
        waitingForAdb.value = true
        BikeService.start(context)
        log("esperando a que ADB esté en marcha para conectar")
        if (ShellLink.paired.value) ShellLink.quickStart()
    }

    fun cancelWaiting() {
        if (!waitingForAdb.value) return
        waitingForAdb.value = false
        if (!running.value) BikeService.stop(context)
    }

    private var link: BikeLink? = null
    private var wifi: BikeWifi? = null

    /** One for the whole run, so the app stays on the bike's screen between attempts. */
    private var picture: MirrorPicture? = null

    /** What has to be granted before [start]. */
    val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    fun missingPermissions() =
        permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun choose(packageName: String) {
        prefs.edit().putString(KEY_APP, packageName).apply()
        chosenApp.value = packageName
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
        log("QR de la moto: $text")
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
        lines.clear()
        running.value = true
        ShellLink.bind()
        picture = MirrorPicture(::log, ::chosenComponent) { dpi.intValue }
        BikeService.start(context)
        giveUpIn(SEARCH_MS)
        attempt()
    }

    /** No bike for a while: stop looking. Showing on the bike, it is called off. */
    private val giveUp = Runnable {
        if (!running.value || state.value == BikeState.SHOWING) return@Runnable
        log("sin moto en ${lookedForMs / 60_000} min: se deja de buscar")
        toast("No aparece la moto: se deja de buscar.")
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
        runCatching { if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(MAX_BYTES / 2)) }
        header()

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
        val events = object : BikeEvents {
            override fun log(line: String) = this@Moto.log(line)
            override fun state(state: BikeState) {
                main.post {
                    if (state == BikeState.SHOWING && this@Moto.state.value != BikeState.SHOWING) {
                        main.removeCallbacks(giveUp)
                        toast("Conectado: la app está en la moto.")
                    }
                    this@Moto.state.value = state
                }
            }
        }
        val opened = BikeLink(card, picture ?: return, events)
        try {
            opened.open()
        } catch (e: IOException) {
            // Carbit and OffNavigator listen on the very same ports, and only one can have them.
            log("no se pueden abrir los puertos (${e.message}). Si Carbit u OffNavigator están abiertos, ciérralos del todo y vuelve a probar.")
            toast("Puertos ocupados: cierra Carbit u OffNavigator del todo.")
            opened.close()
            stop()
            return
        }
        link = opened
        log("escuchando en 10920, 10921 y 10922")
        wifi = BikeWifi(
            context, ::log, bike.value,
            onJoined = { bike, phone -> knockUntilHeard(opened, bike, phone) },
            onLeft = { bikeLeft(opened) }
        ).also { it.start() }
    }

    /**
     * The bike's wifi went away, as when it is switched off at a stop. Start over and keep
     * looking, so switching it on again is all it takes. The app stays on the bike's screen.
     */
    private fun bikeLeft(from: BikeLink) {
        if (link !== from) return
        log("la moto se ha ido (¿apagada?): se la vuelve a buscar")
        toast("La moto se ha desconectado. Si vuelve en ${AFTER_LEFT_MS / 60_000} min, se conecta sola.")
        giveUpIn(AFTER_LEFT_MS)
        closeAttempt()
        state.value = BikeState.LISTENING
        main.postDelayed({ if (running.value && link == null) attempt() }, RETRY_MS)
    }

    private fun closeAttempt() {
        wifi?.stop()
        wifi = null
        val closing = link
        link = null
        // Closing sockets is network work, which Android does not allow on the main thread.
        thread(isDaemon = true) { closing?.close() }
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
        log("parado")
    }

    /** The bike's screen is up, so the app can be taken back and forth. */
    val canMove: Boolean get() = (picture?.displayId ?: -1) >= 0

    /** The app to the phone, to type a destination; [toBike] sends it back. */
    fun toPhone() = thread(isDaemon = true) { picture?.bringApp(0) }

    fun toBike() = thread(isDaemon = true) {
        val shown = picture ?: return@thread
        if (shown.displayId >= 0) shown.bringApp(shown.displayId)
    }

    /** Sends the whole record, every attempt in it, through WhatsApp, mail, Drive... */
    fun share(activity: Context) {
        try {
            if (!file.exists() || file.length() == 0L) {
                toast("Todavía no hay registro de la moto.")
                return
            }
            val exports = File(activity.cacheDir, "exports").apply { mkdirs() }
            exports.listFiles()?.forEach { it.delete() }
            val name = "registro-moto-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt"
            val out = File(exports, name).apply { writeBytes(file.readBytes()) }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name.removeSuffix(".txt"))
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Enviar registro de la moto"))
        } catch (e: Exception) {
            toast("No se pudo enviar el registro: ${e.message}")
        }
    }

    /** What an attempt starts from, for whoever reads the record without having been there. */
    private fun header() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        log("======== intento del ${day.format(Date())} ========")
        log("Motocom ${BuildConfig.VERSION_NAME} en ${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        log("wifi ${if (wifi?.isWifiEnabled == true) "encendido" else "APAGADO"}, " +
            "ubicación ${if (location != null && LocationManagerCompat.isLocationEnabled(location)) "encendida" else "APAGADA"}, " +
            "ADB ${ShellLink.status.value} (uid ${ShellLink.uid()}), app ${chosenApp.value ?: "ninguna"} a ${dpi.intValue} ppp")
    }

    /** The bike only comes once it has been called, and the first call can be too early. */
    private fun knockUntilHeard(link: BikeLink, bike: InetAddress, phone: InetAddress?) {
        thread(name = "moto-llamar", isDaemon = true) {
            repeat(10) {
                if (this.link !== link || link.bikeInfo != null) return@thread
                link.knock(bike, phone)
                Thread.sleep(3000)
            }
            if (this.link === link && link.bikeInfo == null) log("la moto no ha venido tras 10 llamadas")
        }
    }

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    private fun log(line: String) {
        val stamped = "${stamp.format(Date())}  $line"
        synchronized(file) { runCatching { file.appendText(stamped + "\n") } }
        main.post {
            lines += stamped
            if (lines.size > 400) lines.removeRange(0, 100)
        }
    }

    companion object {
        private const val MAX_BYTES = 1_000_000
        const val SEARCH_MS = 3 * 60 * 1000L
        const val AFTER_LEFT_MS = 10 * 60 * 1000L
        /** Time for the last attempt to let go of the ports. */
        private const val RETRY_MS = 3000L

        private const val KEY_APP = "app"
        private const val KEY_DPI = "ppp"
        private const val KEY_QR = "qr"
        /** 592 pixels across at this density is about a phone's width of map. */
        const val DEFAULT_DPI = 240

        @Volatile private var instance: Moto? = null

        fun get(context: Context): Moto =
            instance ?: synchronized(this) {
                instance ?: Moto(context.applicationContext).also { instance = it }
            }
    }
}
