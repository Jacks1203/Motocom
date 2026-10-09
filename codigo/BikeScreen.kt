package com.offnavigator.bike

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.offnavigator.core.bike.BikeEvents
import com.offnavigator.core.bike.BikeLink
import com.offnavigator.core.bike.BikeQr
import com.offnavigator.core.bike.BikeState
import com.offnavigator.core.bike.PhoneCard
import com.offnavigator.ui.main.AppMessages
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Puts a picture on the bike's screen without Carbit Ride: joins the bike's wifi direct, lets
 * it know the phone is there and serves it what it comes asking for.
 *
 * While running it keeps at it: if the bike goes (switched off at a stop), it looks for it
 * again. Not for ever: with no bike in [SEARCH_MS] of starting, or [AFTER_LEFT_MS] of losing it,
 * it gives up, since looking keeps the wifi and the GPS busy with the phone in a pocket.
 *
 * The picture is a view of its own made to the bike's size ([BikeView]). It all lives outside
 * the Activity, one for the whole app, and [BikeService] keeps it going with the phone locked
 * in a pocket or another app in front. The bike lives in a garage with no computer and no internet near it, so every
 * attempt writes what happened to `moto_log.txt`, one attempt after another, and [share] sends
 * that file from the phone later: it is all there will be to go on.
 */
class BikeScreen private constructor(private val context: Context) {
    val lines = mutableStateListOf<String>()
    val state = mutableStateOf(BikeState.CLOSED)
    val running = mutableStateOf(false)

    private val prefs = context.getSharedPreferences("offnav_moto", Context.MODE_PRIVATE)

    /** The bike whose QR code was last read, which is the one [start] looks for. */
    val bike = mutableStateOf(prefs.getString("qr", null)?.let { BikeQr.parse(it) })

    /** What the strip along the bottom of the bike's screen shows. */
    val layout = BikeLayout(prefs)

    /**
     * Whether the phone tells the bike it is OffNavigator. The bike was only ever seen with
     * Carbit Ride, so the first time it is tried and, if the bike shows nothing with it,
     * Carbit's name is used from then on. The record says which.
     */
    private val ownName: Boolean get() = prefs.getString(KEY_NAME, null) != NAME_CARBIT

    /** This attempt got into a bike's wifi. Main thread. */
    private var inBikeWifi = false

    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val file = File(context.getExternalFilesDir(null), "moto_log.txt")
    private val day = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US)

    init {
        // Back upstairs, the last attempt is still there to read.
        runCatching { if (file.exists()) lines += file.readLines().takeLast(200) }
    }
    private var link: BikeLink? = null
    private var wifi: BikeWifi? = null

    /** What has to be granted before [start]. */
    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * Connects by itself when the app opens in moto mode, if everything is already in place:
     * the bike's QR read and the permissions granted. Nothing is asked from here.
     */
    fun startIfReady() {
        if (running.value || bike.value == null) return
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) return
        start()
    }

    fun start() {
        if (running.value) return
        lines.clear()
        running.value = true
        BikeService.start(context)
        giveUpIn(SEARCH_MS)
        attempt()
    }

    /** No bike for [ms]: stop looking. Showing on the bike, it is called off. */
    private val giveUp = Runnable {
        if (!running.value || state.value == BikeState.SHOWING) return@Runnable
        log("sin moto en ${lookedForMs / 60_000} min: se deja de buscar")
        AppMessages.show("No aparece la moto: se deja de buscar. Para volver a probar, Ajustes → Pantalla de la moto → Conectar.", long = true)
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
        inBikeWifi = false
        // Attempts pile up rather than replace each other; only a very long file loses its start.
        runCatching { if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(MAX_BYTES / 2)) }
        header()

        val keys = BikeKeys(context)
        val metrics = context.resources.displayMetrics
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
        ).let { if (ownName) ownCard(it) else it }
        log(if (ownName) "se presenta como OffNavigator (${context.packageName})" else "se presenta como Carbit Ride 2.4 (con el nombre de OffNavigator no enseñó nada)")
        val events = object : BikeEvents {
            override fun log(line: String) = this@BikeScreen.log(line)
            override fun state(state: BikeState) {
                main.post {
                    if (state == BikeState.SHOWING && this@BikeScreen.state.value != BikeState.SHOWING) {
                        main.removeCallbacks(giveUp)
                        AppMessages.show("Conectado: la moto enseña OffNavigator.")
                        // Only a bike reached through its wifi says anything: the one played over the
                        // cable for tests takes any name.
                        if (ownName && inBikeWifi && prefs.getString(KEY_NAME, null) != NAME_OWN) {
                            prefs.edit().putString(KEY_NAME, NAME_OWN).apply()
                            log("la moto acepta el nombre de OffNavigator: se queda así")
                        }
                    }
                    this@BikeScreen.state.value = state
                }
            }
        }
        val picture = BikeEncoder(context, ::log, layout)
        val opened = BikeLink(card, picture, events)
        try {
            opened.open()
        } catch (e: IOException) {
            // Carbit listens on the very same ports, and only one of the two can have them.
            log("no se pueden abrir los puertos (${e.message}). Si Carbit está abierto, ciérralo del todo y vuelve a probar.")
            opened.close()
            running.value = false
            BikeService.stop(context)
            return
        }
        link = opened
        log("escuchando en 10920, 10921 y 10922")
        wifi = BikeWifi(
            context, ::log, bike.value,
            onJoined = { bike, phone ->
                main.post { inBikeWifi = true }
                knockUntilHeard(opened, bike, phone)
                if (ownName && prefs.getString(KEY_NAME, null) == null) main.postDelayed({ nameNotTaken(opened) }, NAME_TRIAL_MS)
            },
            onLeft = { bikeLeft(opened) }
        ).also { it.start() }
    }

    /**
     * The bike's wifi went away, as when it is switched off at a stop. Start over and keep
     * looking, so switching it on again is all it takes.
     */
    private fun bikeLeft(from: BikeLink) {
        if (link !== from) return
        log("la moto se ha ido (¿apagada?): se la vuelve a buscar")
        AppMessages.show("La moto se ha desconectado. Si vuelve en ${AFTER_LEFT_MS / 60_000} min, se conecta sola.")
        giveUpIn(AFTER_LEFT_MS)
        closeAttempt()
        state.value = BikeState.LISTENING
        main.postDelayed({ if (running.value && link == null) attempt() }, RETRY_MS)
    }

    /**
     * In the bike's wifi for a good while, with OffNavigator's name, and nothing on its screen:
     * the name is the likely reason. Carbit's from now on, starting over at once.
     */
    private fun nameNotTaken(from: BikeLink) {
        if (link !== from || state.value == BikeState.SHOWING || prefs.getString(KEY_NAME, null) != null) return
        prefs.edit().putString(KEY_NAME, NAME_CARBIT).apply()
        log("con el nombre de OffNavigator la moto no enseña nada en ${NAME_TRIAL_MS / 1000} s: se vuelve a probar con el de Carbit")
        closeAttempt()
        state.value = BikeState.LISTENING
        main.postDelayed({ if (running.value && link == null) attempt() }, RETRY_MS)
    }

    /** [card] saying it is OffNavigator, in the version installed, instead of Carbit Ride. */
    private fun ownCard(card: PhoneCard): PhoneCard {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val code = info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0
        return PhoneCard(
            uuid = card.uuid, brand = card.brand, model = card.model, androidSdk = card.androidSdk,
            bluetoothName = card.bluetoothName, screenWidth = card.screenWidth, screenHeight = card.screenHeight,
            publicKey = card.publicKey, sign = card.sign,
            packageName = context.packageName,
            versionCode = code,
            fingerprint = "V:${info?.versionName}($code)-offnavigator-ONLINE"
        )
    }

    private fun closeAttempt() {
        wifi?.stop()
        wifi = null
        val closing = link
        link = null
        // Closing sockets is network work, which Android does not allow on the main thread.
        thread(isDaemon = true) { closing?.close() }
    }

    /** Takes what the camera read off the bike's screen. False if it is not a bike's code. */
    fun readQr(text: String): Boolean {
        val read = BikeQr.parse(text)
        if (read == null) {
            log("QR leído, pero no es de una moto: ${text.take(200)}")
            return false
        }
        prefs.edit().putString("qr", text).apply()
        bike.value = read
        log("QR de la moto: $text")
        return true
    }

    fun stop() {
        if (!running.value) return
        main.removeCallbacks(giveUp)
        running.value = false
        closeAttempt()
        BikeService.stop(context)
        log("parado")
    }

    /** Sends the whole record, every attempt in it, through WhatsApp, mail, Drive... */
    fun share(activity: Context) {
        try {
            if (!file.exists() || file.length() == 0L) {
                AppMessages.show("Todavía no hay registro de la moto.", long = true)
                return
            }
            val exports = File(activity.cacheDir, "exports").apply { mkdirs() }
            exports.listFiles()?.forEach { it.delete() }
            val name = "registro-moto-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt"
            val out = File(exports, name).apply { writeBytes(file.readBytes()) }
            // If the app closed by itself halfway, the reason is in the other file.
            File(context.getExternalFilesDir(null), "crash_log.txt").takeIf { it.exists() }?.let {
                out.appendText("\n======== cierres inesperados ========\n" + it.readText().takeLast(20_000))
            }
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
            AppMessages.show("No se pudo enviar el registro: ${e.message}", long = true)
        }
    }

    /**
     * The handlebar buttons are thought to reach the phone as a Bluetooth keyboard. If they
     * do, this is where it shows.
     */
    fun noteKey(event: KeyEvent) {
        if (!running.value || event.action != KeyEvent.ACTION_DOWN) return
        log("tecla: ${KeyEvent.keyCodeToString(event.keyCode)} desde «${event.device?.name ?: "?"}»")
    }

    /** What an attempt starts from, for whoever reads the record without having been there. */
    private fun header() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        log("======== intento del ${day.format(Date())} ========")
        log("OffNavigator $version en ${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        log("wifi ${if (wifi?.isWifiEnabled == true) "encendido" else "APAGADO"}, " +
            "ubicación ${if (location != null && LocationManagerCompat.isLocationEnabled(location)) "encendida" else "APAGADA"}, " +
            "Carbit ${if (carbitInstalled()) "instalado" else "no instalado"}")
    }

    private fun carbitInstalled() =
        runCatching { context.packageManager.getPackageInfo("net.easyconn.easyride.wws", 0) }.isSuccess

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

    private fun log(line: String) {
        val stamped = "${stamp.format(Date())}  $line"
        runCatching { file.appendText(stamped + "\n") }
        main.post {
            lines += stamped
            if (lines.size > 400) lines.removeRange(0, 100)
        }
    }

    companion object {
        private const val MAX_BYTES = 1_000_000
        /** How long to look for the bike after starting before giving up. */
        const val SEARCH_MS = 3 * 60 * 1000L
        /**
         * How long to look for it again once it has gone: enough for a stop to fill up or for a
         * coffee, with the bike switched off; not all night in the garage.
         */
        const val AFTER_LEFT_MS = 10 * 60 * 1000L
        /** Time for the last attempt to let go of the ports. */
        private const val RETRY_MS = 3000L

        private const val KEY_NAME = "nombre"
        private const val NAME_OWN = "offnavigator"
        private const val NAME_CARBIT = "carbit"
        /** Carbit had the picture on the bike in about ten seconds from joining its wifi. */
        private const val NAME_TRIAL_MS = 45_000L

        @Volatile private var instance: BikeScreen? = null

        /** The one for the whole app: the Activity comes and goes, the bike stays. */
        fun get(context: Context): BikeScreen =
            instance ?: synchronized(this) {
                instance ?: BikeScreen(context.applicationContext).also { instance = it }
            }
    }
}
