package com.motocom.shell

import android.content.Context
import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Entry point executed by adbd as uid 2000, independent of the ADB connection. It outlives the
 * app: when the app dies it waits for it to come back and attaches again, so ADB, and with it a
 * wifi, is only needed again after a reboot.
 */
object ShellMain {
    private const val TAG = "MotocomADB"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(android.os.Process.myUid() == 2000 || android.os.Process.myUid() == 0)
            Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val context = system.createPackageContext("com.android.shell", 0)
            val service = DisplayService(context)
            attach(service, args[0], args.getOrNull(1)?.toInt() ?: 0)
            Looper.loop()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "No se pudo arrancar", e)
            exitProcess(1)
        }
    }

    private fun attach(service: DisplayService, nonce: String, userId: Int) {
        val result = publish(service, nonce, userId)
        val lifetime = requireNotNull(result?.getBinder("lifetime"))
        lifetime.linkToDeath({
            // Without the app the bike's screen paints nowhere: give its apps back to the phone.
            service.release()
            thread(name = "motocom-espera") { waitForApp(service, nonce, userId) }
        }, 0)
        check(lifetime.isBinderAlive)
    }

    /**
     * Looks for the app's process, without starting it, and attaches again once it is there:
     * every second for the first five minutes, then every five seconds, until the phone
     * restarts. Ends if the app was reinstalled (this code is then stale) or if the app keeps
     * refusing, which happens when it has started a worker of its own.
     */
    private fun waitForApp(service: DisplayService, nonce: String, userId: Int) {
        val apk = System.getenv("CLASSPATH")
        var refused = 0
        val since = SystemClock.elapsedRealtime()
        while (true) {
            Thread.sleep(if (SystemClock.elapsedRealtime() - since < 5 * 60_000) 1000 else 5000)
            if (apk != null && !File(apk).exists()) {
                android.util.Log.i(TAG, "La app se ha reinstalado o borrado: termina")
                // Updated, the next worker may be about to use the pairing; uninstalled, nobody will.
                if (!installed()) service.forgetPairing()
                exitProcess(0)
            }
            if (!appRunning(service.owner)) continue
            try {
                attach(service, nonce, userId)
                return
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "La app no acepta el proceso", e)
                if (++refused >= 5) exitProcess(0)
            }
        }
    }

    /** Not knowing counts as installed: forgetting the pairing of an installed app costs more. */
    private fun installed(): Boolean = exec(5, "pm", "path", "com.motocom")?.contains("package:") ?: true

    /** /proc/<pid> belongs to the uid that runs it; the shell may see every process. */
    private fun appRunning(uid: Int): Boolean =
        File("/proc").list()?.any { name ->
            name.all(Char::isDigit) && runCatching { Os.stat("/proc/$name").st_uid == uid }.getOrDefault(false)
        } ?: false

    /** Same external-provider route as Android's `content` shell command. A shell process
     * has no registered app ActivityThread, so the ordinary ContentResolver cannot be used. */
    private fun publish(service: DisplayService, nonce: String, userId: Int): Bundle? {
        val authority = "com.motocom.shell"
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val managerApi = Class.forName("android.app.IActivityManager")
        val token = Binder()
        val holder = managerApi.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, authority, userId, token, "Motocom")
            ?: error("No se encontró el proveedor de Motocom")
        try {
            val info = holder.javaClass.getField("info").get(holder) as android.content.pm.ProviderInfo
            // Only the app that owns the provider may use the screen; set before handing it over.
            service.owner = info.applicationInfo.uid
            val provider = holder.javaClass.getField("provider").get(holder)
            val api = Class.forName("android.content.IContentProvider")
            val extras = Bundle().apply { putBinder("display", service) }
            return if (Build.VERSION.SDK_INT >= 31) {
                val source = AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell").build()
                api.getMethod("call", AttributionSource::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, source, authority, "attach", nonce, extras) as Bundle?
            } else if (Build.VERSION.SDK_INT == 30) {
                api.getMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, "com.android.shell", null, authority, "attach", nonce, extras) as Bundle?
            } else {
                api.getMethod("call", String::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, "com.android.shell", authority, "attach", nonce, extras) as Bundle?
            }
        } finally {
            managerApi.getMethod("removeContentProviderExternalAsUser", String::class.java,
                IBinder::class.java, Int::class.javaPrimitiveType).invoke(manager, authority, token, userId)
        }
    }

}
