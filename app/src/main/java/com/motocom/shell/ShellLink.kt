package com.motocom.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.motocom.R
import com.motocom.Refused
import com.motocom.Said
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** ADB only starts the worker. Surfaces and commands then travel over Binder. */
object ShellLink {
    enum class Status { NOT_RUNNING, READY }
    val status = mutableStateOf(Status.NOT_RUNNING)
    val busy = mutableStateOf(false)
    /** What the last start or pairing said, read in the app's language when shown, while busy or failed. */
    val message = mutableStateOf<Said?>(null)
    /** The last start or pairing failed, and [message] says why. */
    val failed = mutableStateOf(false)
    @Volatile var display: IBikeDisplay? = null
        private set
    /** Right away, unlike [status], which follows on the main thread. */
    val ready: Boolean get() = display != null
    private lateinit var context: Context
    private var log: (String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connected = CountDownLatch(1)
    /** Kept in prefs: a worker started before the app last died still knows it. */
    internal val nonce: String
        get() = prefs.getString(KEY_NONCE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_NONCE, it).commit()
        }
    private var manager: AdbIdentity? = null
    /** Set once this phone has accepted our key, so later starts need no port or code. */
    val paired = mutableStateOf(false)
    /** Paired just now and not started yet, so the next step is Iniciar ADB. */
    val justPaired = mutableStateOf(false)
    private val prefs by lazy { context.getSharedPreferences("adb", Context.MODE_PRIVATE) }
    var onReady: (() -> Unit)? = null
    var onLost: (() -> Unit)? = null

    fun init(context: Context, log: (String) -> Unit) {
        remember(context)
        this.log = log
        paired.value = prefs.getBoolean(KEY_PAIRED, false)
        // The app died while it had wireless debugging on for a start: leave the phone as it was.
        if (prefs.getBoolean(KEY_SWITCHED, false) && !busy.value) {
            if (canWriteSettings() && setWirelessDebugging(false)) log("depuración inalámbrica apagada: la dejó encendida un inicio que no acabó")
            prefs.edit().remove(KEY_SWITCHED).apply()
        }
    }

    /** The provider may be asked to attach before the app has set anything else up. */
    internal fun remember(context: Context) {
        if (!::context.isInitialized) this.context = context.applicationContext
    }

    fun refresh() {
        if (display != null && display?.asBinder()?.isBinderAlive != true) lost()
    }
    fun bind() = refresh()
    fun uid() = if (display != null) 2000 else -1
    fun await(seconds: Long = 8): IBikeDisplay? {
        display?.let { return it }
        connected.await(seconds, TimeUnit.SECONDS)
        return display
    }
    private fun lost() {
        display = null
        connected = CountDownLatch(1)
        main.post {
            status.value = Status.NOT_RUNNING
            failed.value = false
            message.value = Said(R.string.adb_closed)
            onLost?.invoke()
        }
    }
    internal fun attach(binder: IBinder, token: String): IBinder {
        check(token == nonce) { "Sesión ADB incorrecta" }
        check(display == null) { "Ya hay un proceso ADB conectado" }
        val remote = IBikeDisplay.Stub.asInterface(binder)
        val logger = object : IShellLog.Stub() {
            override fun line(text: String) = log("adb: $text")
        }
        binder.linkToDeath({ if (display?.asBinder() == binder) lost() }, 0)
        remote.setLog(logger)
        display = remote
        connected.countDown()
        main.post {
            status.value = Status.READY
            failed.value = false
            message.value = Said(R.string.adb_ready)
            onReady?.invoke()
        }
        return logger // When the app dies the worker waits for it to come back.
    }

    fun pair(port: String, code: String) = work {
        main.post { justPaired.value = false }
        pairOn(validPort(port), code)
        Said(R.string.adb_paired_next)
    }

    /**
     * From the notification, with the pairing port found by mDNS: pairs, then starts as
     * [quickStart] does. False if something else is under way, so nothing was tried.
     */
    fun pairAndStart(port: Int, code: String, done: (said: Said, failed: Boolean) -> Unit) = work(done) {
        pairOn(port, code)
        runCatching { autoStart() }.getOrElse { Said(R.string.adb_paired_not_started, reason(it)) }
    }

    private fun pairOn(number: Int, code: String) {
        main.post { justPaired.value = false }
        if (!code.matches(Regex("[0-9]{6}"))) throw Refused(Said(R.string.adb_code_digits))
        val adb = client(pairing = true)
        // The library sets no time limit on pairing, so a hung socket is left behind on its thread.
        var result: Result<Boolean>? = null
        val done = CountDownLatch(1)
        thread(name = "motocom-pair", isDaemon = true) {
            result = runCatching { adb.pair("127.0.0.1", number, code) }
            done.countDown()
        }
        if (!done.await(30, TimeUnit.SECONDS)) {
            manager = null // The next try gets a client of its own.
            throw Refused(Said(R.string.adb_pair_hung))
        }
        if (!result!!.getOrThrow()) throw Refused(Said(R.string.adb_pair_failed))
        needsPairing = false
        main.post { justPaired.value = true }
    }

    fun start(port: String) = work {
        if (display != null || survivor() != null) return@work Said(R.string.adb_already_ready)
        val number = validPort(port)
        val adb = client()
        adb.disconnect()
        try {
            if (!adb.connect("127.0.0.1", number)) throw Refused(Said(R.string.adb_connect_failed))
            launch(adb)
        } finally {
            adb.disconnect()
        }
    }

    /**
     * One-button start once paired: turns wireless debugging on if allowed, finds its
     * port by mDNS (it changes every time), starts the worker and restores the setting.
     */
    fun quickStart() = work { autoStart() }

    private fun autoStart(): Said {
        if (display != null || survivor() != null) return Said(R.string.adb_already_ready)
        // First, so that a key needing pairing again does not leave debugging switched on.
        val adb = client()
        val switched = enableWirelessDebugging()
        adb.disconnect()
        try {
            if (!adb.autoConnect(context, 20_000)) {
                throw Refused(Said(if (switched || wirelessDebuggingOn()) R.string.adb_debugging_not_found else R.string.adb_debugging_off))
            }
            return launch(adb)
        } finally {
            adb.disconnect()
            // The worker no longer needs it, so leave the phone as it was.
            if (switched) {
                setWirelessDebugging(false)
                prefs.edit().remove(KEY_SWITCHED).commit()
            }
        }
    }

    /** Starts the worker over an open connection, then lets later starts switch debugging on by themselves. */
    private fun launch(adb: AdbIdentity): Said {
        // A worker that was still alive may have come back while connecting: keep its nonce.
        if (display != null) {
            main.post { justPaired.value = false }
            return Said(R.string.adb_already_ready)
        }
        prefs.edit().putString(KEY_NONCE, UUID.randomUUID().toString())
            .putLong(KEY_STARTED, System.currentTimeMillis()).commit()
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        // The worker detaches from ADB so changing wifi does not kill the display. When sh exits,
        // adbd kills its process group, so sh waits until setsid has moved the worker out of it.
        val command = "CLASSPATH='$apk' setsid nohup /system/bin/app_process /system/bin com.motocom.shell.ShellMain $nonce ${context.applicationInfo.uid / 100000} >/dev/null 2>&1 </dev/null & sleep 2"
        adb.openStream("exec:$command").use {
            if (!connected.await(15, TimeUnit.SECONDS) || display == null) {
                // No worker came, so there is none to wait for next time either.
                prefs.edit().remove(KEY_STARTED).apply()
                throw Refused(Said(R.string.adb_no_worker))
            }
        }
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        main.post { paired.value = true; justPaired.value = false }
        if (!canWriteSettings()) {
            val output = runCatching {
                adb.openStream("shell:pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS} 2>&1")
                    .use { it.openInputStream().bufferedReader().readText().trim() }
            }.getOrElse { it.message ?: "" }
            log(if (canWriteSettings()) "permiso concedido: la app ya puede activar sola la depuración inalámbrica"
                else "no se pudo conceder el permiso para activar la depuración inalámbrica: $output " +
                    "(en Xiaomi activa también «Depuración USB (ajustes de seguridad)»)")
        }
        return Said(R.string.adb_ready)
    }

    /**
     * A worker started since the phone last booted may still be alive after the app died; it
     * looks for the app every second at first and every five seconds later, so give it a
     * moment instead of starting a second one.
     */
    private fun survivor(): IBikeDisplay? {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (prefs.getLong(KEY_STARTED, 0) < boot) return null
        main.post { message.value = Said(R.string.adb_survivor) }
        return await(6)
    }

    private fun canWriteSettings() =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    private fun wirelessDebuggingOn() = Settings.Global.getInt(context.contentResolver, ADB_WIFI, 0) == 1
    private fun setWirelessDebugging(on: Boolean) = runCatching {
        Settings.Global.putInt(context.contentResolver, ADB_WIFI, if (on) 1 else 0)
    }.isSuccess

    /** True only if this call switched it on. */
    private fun enableWirelessDebugging(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30 || !canWriteSettings() || wirelessDebuggingOn()) return false
        // Written first, so init() can switch it off if the app dies before the end of the start.
        prefs.edit().putBoolean(KEY_SWITCHED, true).commit()
        if (!setWirelessDebugging(true)) {
            prefs.edit().remove(KEY_SWITCHED).commit()
            return false
        }
        log("depuración inalámbrica activada por la app")
        return true
    }

    /** A new key replaced one the phone had accepted: nothing but pairing can work until it is. */
    @Volatile private var needsPairing = false

    private fun client(pairing: Boolean = false): AdbIdentity {
        val identity = manager ?: AdbIdentity(context).also {
            manager = it
            if (it.wasBroken) log("la identidad ADB guardada estaba dañada: se ha hecho otra y hay que volver a emparejar")
            // A new key is one this phone has never accepted, so ask for the code again.
            if (it.isNew && prefs.getBoolean(KEY_PAIRED, false)) {
                prefs.edit().putBoolean(KEY_PAIRED, false).apply()
                main.post { paired.value = false }
                needsPairing = true
            }
        }
        if (needsPairing && !pairing) throw Refused(Said(R.string.adb_new_identity))
        return identity
    }
    private fun validPort(value: String): Int = value.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw Refused(Said(R.string.adb_port_range))
    /** Runs [action] off the main thread; [done] gets its outcome on the main thread. */
    private fun work(done: (said: Said, failed: Boolean) -> Unit = { _, _ -> }, action: () -> Said): Boolean {
        if (busy.value) return false
        busy.value = true
        failed.value = false
        message.value = Said(R.string.adb_preparing)
        thread(name = "motocom-adb", isDaemon = true) {
            var error = false
            val result = try { action() } catch (e: Throwable) {
                error = true
                Said(R.string.adb_error, reason(e))
            }
            val logged = result.forLog(context)
            log(if (error) "Error ADB: $logged" else logged)
            main.post { message.value = result; failed.value = error; busy.value = false; done(result, error) }
        }
        return true
    }

    /** Ours, to be read in the app's language; the library's and Java's come in English as they are. */
    private fun reason(e: Throwable): Any =
        if (e is Refused) e.said else (e.message ?: e.javaClass.simpleName).removeSuffix(".")

    private const val KEY_PAIRED = "paired"
    private const val KEY_NONCE = "nonce"
    private const val KEY_STARTED = "started"
    /** Set while wireless debugging is on only because a start switched it on. */
    private const val KEY_SWITCHED = "switched_on"
    private const val ADB_WIFI = "adb_wifi_enabled"
}
