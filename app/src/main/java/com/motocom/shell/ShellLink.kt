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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** ADB only starts the worker. Surfaces and commands then travel over Binder. */
object ShellLink {
    enum class Status { NOT_RUNNING, READY }
    val status = mutableStateOf(Status.NOT_RUNNING)
    val busy = mutableStateOf(false)
    val message = mutableStateOf("Activa la depuración inalámbrica para empezar.")
    @Volatile var display: IBikeDisplay? = null
        private set
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
    private val prefs by lazy { context.getSharedPreferences("adb", Context.MODE_PRIVATE) }
    var onReady: (() -> Unit)? = null
    var onLost: (() -> Unit)? = null

    fun init(context: Context, log: (String) -> Unit) {
        remember(context)
        this.log = log
        paired.value = prefs.getBoolean(KEY_PAIRED, false)
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
            message.value = "Se ha cerrado el proceso ADB. Pulsa Iniciar ADB."
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
            message.value = "ADB listo. Ya puedes conectar la moto."
            onReady?.invoke()
        }
        return logger // When the app dies the worker waits for it to come back.
    }

    fun pair(port: String, code: String) = work {
        val number = validPort(port)
        require(code.matches(Regex("[0-9]{6}"))) { "El código debe tener 6 cifras." }
        check(client().pair("127.0.0.1", number, code)) { "No se pudo emparejar." }
        "Emparejado. Introduce el puerto de conexión de la pantalla principal y pulsa Iniciar ADB."
    }

    fun start(port: String) = work {
        if (display != null || survivor() != null) return@work "ADB ya está listo."
        val number = validPort(port)
        val adb = client()
        adb.disconnect()
        try {
            check(adb.connect("127.0.0.1", number)) { "No se pudo conectar. Comprueba el puerto y el emparejamiento." }
            launch(adb)
        } finally {
            adb.disconnect()
        }
    }

    /**
     * One-button start once paired: turns wireless debugging on if allowed, finds its
     * port by mDNS (it changes every time), starts the worker and restores the setting.
     */
    fun quickStart() = work {
        if (display != null || survivor() != null) return@work "ADB ya está listo."
        val switched = enableWirelessDebugging()
        val adb = client()
        adb.disconnect()
        try {
            if (!adb.autoConnect(context, 20_000)) {
                throw IllegalStateException(
                    if (switched || wirelessDebuggingOn()) "No se encontró la depuración inalámbrica. Comprueba que el móvil esté en un wifi"
                    else "La depuración inalámbrica está apagada. Actívala en Opciones de desarrollador y vuelve a pulsar"
                )
            }
            launch(adb)
        } finally {
            adb.disconnect()
            // The worker no longer needs it, so leave the phone as it was.
            if (switched) setWirelessDebugging(false)
        }
    }

    /** Starts the worker over an open connection, then lets later starts switch debugging on by themselves. */
    private fun launch(adb: AdbIdentity): String {
        prefs.edit().putString(KEY_NONCE, UUID.randomUUID().toString())
            .putLong(KEY_STARTED, System.currentTimeMillis()).commit()
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        // The worker detaches from ADB so changing wifi does not kill the display. When sh exits,
        // adbd kills its process group, so sh waits until setsid has moved the worker out of it.
        val command = "CLASSPATH='$apk' setsid nohup /system/bin/app_process /system/bin com.motocom.shell.ShellMain $nonce ${context.applicationInfo.uid / 100000} >/dev/null 2>&1 </dev/null & sleep 2"
        adb.openStream("exec:$command").use {
            check(connected.await(15, TimeUnit.SECONDS) && display != null) {
                "ADB conectó, pero no arrancó la pantalla. Reintenta; comprueba que la app siga abierta."
            }
        }
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        main.post { paired.value = true }
        if (!canWriteSettings()) {
            val output = runCatching {
                adb.openStream("shell:pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS} 2>&1")
                    .use { it.openInputStream().bufferedReader().readText().trim() }
            }.getOrElse { it.message ?: "" }
            log(if (canWriteSettings()) "permiso concedido: la app ya puede activar sola la depuración inalámbrica"
                else "no se pudo conceder el permiso para activar la depuración inalámbrica: $output " +
                    "(en Xiaomi activa también «Depuración USB (ajustes de seguridad)»)")
        }
        return "ADB listo. Ya puedes conectar la moto."
    }

    /**
     * A worker started since the phone last booted may still be alive after the app died; it
     * looks for the app every second, so give it a moment instead of starting a second one.
     */
    private fun survivor(): IBikeDisplay? {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (prefs.getLong(KEY_STARTED, 0) < boot) return null
        main.post { message.value = "Buscando el proceso ADB que ya estaba en marcha…" }
        return await(3)
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
        if (!setWirelessDebugging(true)) return false
        log("depuración inalámbrica activada por la app")
        return true
    }

    private fun client() = manager ?: AdbIdentity(context).also { manager = it }
    private fun validPort(value: String): Int = value.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("Introduce un puerto entre 1 y 65535.")
    private fun work(action: () -> String) {
        if (busy.value) return
        busy.value = true
        message.value = "Preparando ADB…"
        thread(name = "motocom-adb", isDaemon = true) {
            val result = try { action() } catch (e: Throwable) {
                "Error ADB: ${e.message ?: e.javaClass.simpleName}. Comprueba la depuración y los puertos."
            }
            log(result)
            main.post { message.value = result; busy.value = false }
        }
    }

    private const val KEY_PAIRED = "paired"
    private const val KEY_NONCE = "nonce"
    private const val KEY_STARTED = "started"
    private const val ADB_WIFI = "adb_wifi_enabled"
}
