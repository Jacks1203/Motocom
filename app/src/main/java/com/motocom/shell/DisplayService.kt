package com.motocom.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

/**
 * The part of the app that ADB runs as "adb shell", in a process of its own. Only from there
 * can a screen be made that other apps may open on, which is what scrcpy does with
 * `--new-display`; the app itself could only paint on it.
 *
 * The screen is the bike's size and paints straight into the app's encoder, so whatever app is
 * moved onto it, Waze or Google Maps, fills the whole of the bike's screen and nothing of it
 * shows on the phone. Asked for on Android 13 and up, it is one that the lock screen does not
 * cover, so the phone can be locked in a pocket.
 */
class DisplayService() : IBikeDisplay.Stub() {
    private var context: Context? = null

    /** ShellMain supplies the shell package context. */
    constructor(context: Context) : this() {
        this.context = context
    }

    private val thread = HandlerThread("pantalla-moto").also { it.start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    @Volatile private var log: IShellLog? = null

    /** Motocom's uid. The worker lives on after the app, so it answers to nobody else. */
    @Volatile var owner = -1

    private fun allow() {
        val caller = Binder.getCallingUid()
        check(caller == owner || caller == Process.myUid()) { "Solo Motocom" }
    }

    override fun destroy() {
        allow()
        release()
        exitProcess(0)
    }

    override fun setLog(log: IShellLog?) {
        allow()
        this.log = log
        say("dentro de ADB como uid ${Process.myUid()}, Android ${Build.VERSION.SDK_INT}")
    }

    @Synchronized
    override fun show(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        allow()
        display?.let { shown ->
            shown.resize(width, height, dpi)
            shown.surface = surface
            return shown.display.displayId
        }
        return try {
            val made = displayManager().createVirtualDisplay(
                "Pantalla de la moto", width, height, dpi, surface, flags(), events, handler
            )
            display = made
            say("pantalla para la moto hecha: ${width}x$height a $dpi ppp, número ${made.display.displayId}")
            made.display.displayId
        } catch (e: Throwable) {
            say("no se pudo hacer la pantalla para la moto: $e")
            -1
        }
    }

    @Synchronized
    override fun detachSurface() {
        allow()
        display?.surface = null
    }

    @Synchronized
    override fun bring(packageName: String, component: String, displayId: Int): String {
        allow()
        val moved = try {
            moveTask(packageName, displayId)
        } catch (e: Throwable) {
            "no se pudo mover (${e.cause ?: e})"
        }
        if (moved == null || !moved.startsWith("movida")) {
            // Not open yet, or this Android does not let it be moved: start it there instead.
            val started = run(
                "am", "start", "--display", displayId.toString(), "--windowingMode", "1",
                "-f", "0x10200000", // new task, reset if needed
                "-n", component
            )
            return listOfNotNull(moved, "am start: $started").joinToString("; ").also(::say)
        }
        return moved.also(::say)
    }

    @Synchronized
    override fun release() {
        allow()
        display?.let {
            runCatching { it.release() }
            say("pantalla para la moto deshecha: lo que había vuelve al móvil")
        }
        display = null
    }

    override fun displayId(): Int {
        allow()
        return display?.display?.displayId ?: -1
    }

    /**
     * Its own screen, with no bars and nothing of the phone's on it. TRUSTED and OWN_FOCUS let
     * it take touches and keys of its own; ALWAYS_UNLOCKED keeps the lock screen off it;
     * OWN_DISPLAY_GROUP is what ALWAYS_UNLOCKED needs. The same as scrcpy asks for.
     */
    private fun flags(): Int {
        var flags = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP
        return flags
    }

    private val events = object : VirtualDisplay.Callback() {
        // Whether it pauses with the phone locked is the thing to find out on the road.
        override fun onPaused() = say("la pantalla de la moto se pausa (¿móvil bloqueado?)")
        override fun onResumed() = say("la pantalla de la moto sigue")
        override fun onStopped() = say("Android ha quitado la pantalla de la moto")
    }

    /**
     * Moves the app's task if it is open somewhere, so a route already going in Waze keeps
     * going. Null if it is not open.
     */
    @SuppressLint("PrivateApi")
    private fun moveTask(packageName: String, displayId: Int): String? {
        val atm = Class.forName("android.app.ActivityTaskManager")
        val instance = atm.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val tasks = atm.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(instance, 100)
            as List<ActivityManager.RunningTaskInfo>
        val task = tasks.firstOrNull {
            it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName
        } ?: return null
        val service = atm.getMethod("getService").invoke(null)!!
        // Android 12 calls it moveRootTaskToDisplay; 10 and 11, moveStackToDisplay.
        val move = service.javaClass.methods.first {
            (it.name == "moveRootTaskToDisplay" || it.name == "moveStackToDisplay") && it.parameterCount == 2
        }
        move.invoke(service, task.taskId, displayId)
        return "movida la tarea ${task.taskId} de $packageName a la pantalla $displayId"
    }

    /**
     * DisplayManager checks the package against the caller, which here is the shell's, so it
     * gets a context that says so.
     */
    private fun displayManager(): DisplayManager {
        val shell = ShellContext(context ?: systemContext())
        val make = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        make.isAccessible = true
        return make.newInstance(shell)
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null)
            ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(current) as Context
    }

    private fun run(vararg command: String): String = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        out.replace(Regex("\\s+"), " ").take(300)
    } catch (e: Exception) {
        e.toString()
    }

    private fun say(text: String) {
        runCatching { log?.line(text) }
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL

        @RequiresApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
    }

    private companion object {
        const val SHELL = "com.android.shell"

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*, several of them hidden.
        const val PUBLIC = 1
        const val PRESENTATION = 1 shl 1
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
