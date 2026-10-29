package com.motocom.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.TaskInfo
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
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
 *
 * Android 17 on a Pixel puts every such screen with the phone's own, so the power button turns
 * the bike's off too. There it is made as the screen of a connected device instead, the way
 * screens streamed to a car or a computer are, and that kind keeps its own power.
 */
/** [context] is the shell package's, from ShellMain. */
class DisplayService(private val context: Context) : IBikeDisplay.Stub() {

    private val thread = HandlerThread("pantalla-moto").also { it.start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    /** The connected device the screen belongs to, when it needed one; closing it ends both. */
    private var device: Any? = null
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
        forgetPairing()
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
            val made = create(surface, width, height, dpi)
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

    /** Starts with "fallo: " when the app could neither be moved nor started there. */
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
            val started = output(
                "am", "start", "--display", displayId.toString(), "--windowingMode", "1",
                "-f", "0x10200000", // new task, reset if needed
                "-n", component
            )
            val text = listOfNotNull(moved, "am start: ${tidy(started)}").joinToString("; ")
            return (if (startFailed(started)) "fallo: $text" else text).also(::say)
        }
        return moved.also(::say)
    }

    /** An existing task brought forward, or the intent handed to it, still counts as there. */
    private fun startFailed(output: String): Boolean {
        if ("Error" in output || "Exception" in output) return true
        if ("Warning: Activity not started" !in output) return false
        return "brought to the front" !in output && "delivered to currently running" !in output
    }

    @Synchronized
    override fun release() {
        allow()
        display?.let {
            // A device's screen closes what is on it when it goes, so it is brought back first.
            if (device != null) runCatching { returnTasks(it.display.displayId) }
            runCatching { it.release() }
            say("pantalla para la moto deshecha: lo que había vuelve al móvil")
        }
        display = null
        closeDevice()
    }

    private fun closeDevice() {
        device?.let { runCatching { it.javaClass.getMethod("close").invoke(it) } }
        device = null
    }

    /**
     * Undoes the shell's pairing with the bike, which nobody else ever would. Only once Motocom
     * is gone for good: undone and redone on every connection is a path Android 17 was never
     * seen to take, and kept, the next connection after a restart finds it made.
     */
    fun forgetPairing() {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            // Versions that could not find their pairing made a new one on every connection.
            repeat(MAX_PAIRINGS) {
                if (findPairing() == null) return
                android.util.Log.i("MotocomADB", "quitando el emparejamiento de la moto: " +
                    run("cmd", "companiondevice", "disassociate", user(), SHELL, PAIRING))
            }
        }
    }

    override fun displayId(): Int {
        allow()
        return display?.display?.displayId ?: -1
    }

    // Not synchronized: the screen's calls should not wait the seconds the dumps take.
    override fun wifiReport(sinceMillis: Long): String {
        allow()
        return WifiReport.make(sinceMillis)
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

    private fun create(surface: Surface, width: Int, height: Int, dpi: Int): VirtualDisplay {
        fun plainOne(events: Events) = displayManager.createVirtualDisplay(
            "Pantalla de la moto", width, height, dpi, surface, flags(), events, handler
        )
        val first = Events()
        val plain = plainOne(first)
        // Before 14 there is no screen of its own to be had, and none was asked for.
        if (Build.VERSION.SDK_INT < 34 || !withThePhone(plain.display.displayId)) {
            return plain.also { first.of = it }
        }
        say("este Android apagaría la pantalla de la moto con la del móvil: se hace como dispositivo conectado")
        plain.release() // Its own events stay unbound, so its stop is ignored.
        val events = Events()
        val made = try {
            onOwnDevice(surface, width, height, dpi, events)
        } catch (e: Throwable) {
            say("no se pudo hacer como dispositivo conectado (${e.cause ?: e}): se apagará al bloquear")
            closeDevice()
            plainOne(events)
        }
        events.of = made
        return made
    }

    /** Whether Android put [displayId] with the phone's screen, to sleep and wake as one. */
    @SuppressLint("PrivateApi")
    private fun withThePhone(displayId: Int): Boolean = try {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val info = global.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(global.getMethod("getInstance").invoke(null), displayId)
        info!!.javaClass.getField("displayGroupId").getInt(info) == 0
    } catch (e: Throwable) {
        false
    }

    /**
     * Makes the screen on a connected device of its own, which Android powers apart from the
     * phone. Such a device hangs on a pairing, made once like a watch's and kept.
     */
    @SuppressLint("PrivateApi")
    @RequiresApi(34)
    private fun onOwnDevice(surface: Surface, width: Int, height: Int, dpi: Int, events: Events): VirtualDisplay {
        val context = ShellContext(context)
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "virtualdevice")
        val api = Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val service = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val managerClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
        val manager = managerClass.getDeclaredConstructor(api, Context::class.java)
            .apply { isAccessible = true }.newInstance(service, context)
        val builder = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val params = builder.getConstructor().newInstance().let {
            builder.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(it, LOCK_STATE_ALWAYS_UNLOCKED)
            builder.getMethod("setName", String::class.java).invoke(it, "Moto")
            builder.getMethod("build").invoke(it)
        }
        val made = managerClass.getMethod(
            "createVirtualDevice", Int::class.javaPrimitiveType,
            Class.forName("android.companion.virtual.VirtualDeviceParams")
        ).invoke(manager, association(), params)!!
        device = made
        val config = VirtualDisplayConfig.Builder("Pantalla de la moto", width, height, dpi)
            .setSurface(surface)
            .setFlags(DEVICE_FLAGS)
            .build()
        return made.javaClass.getMethod(
            "createVirtualDisplay", VirtualDisplayConfig::class.java, Executor::class.java,
            VirtualDisplay.Callback::class.java
        ).invoke(made, config, Executor { handler.post(it) }, events) as VirtualDisplay
    }

    /** The shell's pairing with the bike, made the first time and undone by [forgetPairing]. */
    private fun association(): Int {
        findPairing()?.let { return it }
        say("emparejando la moto como dispositivo conectado: " +
            run("cmd", "companiondevice", "associate", user(), SHELL, PAIRING, APP_STREAMING))
        // Samsung lists it a moment after saying it is made.
        repeat(PAIRING_TRIES) {
            findPairing()?.let { return it }
            Thread.sleep(PAIRING_WAIT_MS)
        }
        return findPairing() ?: error("no se pudo emparejar; la lista dice: " +
            run("cmd", "companiondevice", "list", user()))
    }

    private fun user() = (Process.myUid() / 100_000).toString()

    /**
     * Android 17 on a Pixel lists each pairing whole ("mId=5, mPackageName='…'"); Android 15
     * and 16 as "5 | package | address", and 14 as "package address 5".
     */
    private fun findPairing(): Int? = output("cmd", "companiondevice", "list", user()).lineSequence()
        .firstNotNullOfOrNull { line ->
            val words = line.split(Regex("[\\s|,]+"))
            when {
                "mPackageName='$SHELL'" in line && "mDeviceMacAddress=$PAIRING" in line ->
                    Regex("mId=(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
                SHELL in words && words.any { it.equals(PAIRING, ignoreCase = true) } ->
                    words.firstNotNullOfOrNull { it.toIntOrNull() }
                else -> null
            }
        }

    /** Takes whatever is on [displayId] back to the phone. */
    @SuppressLint("PrivateApi")
    private fun returnTasks(displayId: Int) {
        val on = TaskInfo::class.java.getField("displayId")
        tasks().filter { on.getInt(it) == displayId }.forEach { runCatching { moveToDisplay(it, 0) } }
    }

    /** One per screen, so a late stop of an old one cannot drop the current one. */
    private inner class Events : VirtualDisplay.Callback() {
        /** Set while [create] still holds the lock, before any event can be handled. */
        @Volatile var of: VirtualDisplay? = null

        // Whether it pauses with the phone locked is the thing to find out on the road.
        override fun onPaused() = say("la pantalla de la moto se pausa (¿móvil bloqueado?)")
        override fun onResumed() = say("la pantalla de la moto sigue")
        override fun onStopped() {
            say("Android ha quitado la pantalla de la moto")
            // Comes on [handler]; the next show() then makes a new one instead of reusing it.
            synchronized(this@DisplayService) {
                if (of != null && display === of) release()
            }
        }
    }

    /**
     * Moves the app's task if it is open somewhere, so a route already going in Waze keeps
     * going. Null if it is not open.
     */
    @SuppressLint("PrivateApi")
    private fun moveTask(packageName: String, displayId: Int): String? {
        val open = tasks().filter {
            it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName
        }
        // Shrinking can leave the app in two tasks; the route is in the floating one.
        val task = open.firstOrNull(::pinned) ?: open.firstOrNull() ?: return null
        // Left while navigating, Maps shrinks to a floating window, and moved like that it stays
        // a small box in the middle of the bike's screen. It is made whole before it goes.
        val floating = displayId != 0 && pinned(task)
        // From 12 there is no making it whole by hand. Not moved, and not "movida", it is
        // started on the bike's screen at full screen instead, which takes it out of the window.
        if (floating && Build.VERSION.SDK_INT >= 31) return "tarea ${task.taskId} de $packageName en ventana flotante"
        if (floating) {
            try {
                toFullScreen(task)
            } catch (e: Throwable) {
                say("no se pudo poner a pantalla completa la tarea ${task.taskId} (${e.cause ?: e})")
            }
        }
        try {
            moveToDisplay(task, displayId)
        } catch (e: Throwable) {
            say("no se pudo mover la tarea ${task.taskId} de $packageName (${e.cause ?: e}): se abre de nuevo")
            return null
        }
        val moved = "la tarea ${task.taskId} de $packageName a la pantalla $displayId"
        if (!floating) return "movida $moved"
        val still = tasks().firstOrNull { it.taskId == task.taskId }?.let(::pinned) ?: false
        // Not "movida", so bring() starts it there at full screen, which takes it out of the window.
        return if (still) "sigue en ventana flotante $moved" else "movida $moved, que estaba en ventana flotante"
    }

    /** Whether [task] is in picture-in-picture, the floating window an app shrinks to. */
    private fun pinned(task: TaskInfo): Boolean = try {
        val config = TaskInfo::class.java.getField("configuration").get(task) as Configuration
        val window = Configuration::class.java.getField("windowConfiguration").get(config)!!
        window.javaClass.getMethod("getWindowingMode").invoke(window) == WINDOWING_MODE_PINNED
    } catch (e: Throwable) {
        false
    }

    @SuppressLint("PrivateApi")
    private fun toFullScreen(task: TaskInfo) {
        val service = taskService()
        service.javaClass.methods.first { it.name == "setTaskWindowingMode" && it.parameterCount == 3 }
            .invoke(service, task.taskId, WINDOWING_MODE_FULLSCREEN, false)
    }

    /**
     * The tasks open now. ActivityTaskManager.getInstance() only came with Android 11; on 10
     * they are asked of its service, or of the activity manager's.
     */
    @SuppressLint("PrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun tasks(): List<ActivityManager.RunningTaskInfo> {
        val atm = Class.forName("android.app.ActivityTaskManager")
        val int = Int::class.javaPrimitiveType
        if (Build.VERSION.SDK_INT >= 30) {
            val instance = atm.getMethod("getInstance").invoke(null)
            return atm.getMethod("getTasks", int).invoke(instance, 100) as List<ActivityManager.RunningTaskInfo>
        }
        return runCatching {
            val service = taskService()
            service.javaClass.getMethod("getTasks", int).invoke(service, 100) as List<ActivityManager.RunningTaskInfo>
        }.getOrElse {
            val service = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            service.javaClass.getMethod("getTasks", int).invoke(service, 100) as List<ActivityManager.RunningTaskInfo>
        }
    }

    @SuppressLint("PrivateApi")
    private fun moveToDisplay(task: TaskInfo, displayId: Int) {
        val service = taskService()
        // Android 12 calls it moveRootTaskToDisplay; 10 and 11, moveStackToDisplay.
        val move = service.javaClass.methods.first {
            (it.name == "moveRootTaskToDisplay" || it.name == "moveStackToDisplay") && it.parameterCount == 2
        }
        // Android 10 moves a stack, which has an id of its own; from 11 a root task's id is the task's.
        val id = if (Build.VERSION.SDK_INT == 29) {
            runCatching { TaskInfo::class.java.getField("stackId").getInt(task) }.getOrDefault(task.taskId)
        } else task.taskId
        move.invoke(service, id, displayId)
    }

    /**
     * DisplayManager checks the package against the caller, which here is the shell's, so it
     * gets a context that says so.
     */
    private val displayManager: DisplayManager by lazy {
        val make = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        make.isAccessible = true
        make.newInstance(ShellContext(context))
    }

    /** ActivityTaskManager's service, where tasks are listed and moved. */
    @SuppressLint("PrivateApi")
    private fun taskService(): Any =
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!

    private fun run(vararg command: String): String = tidy(output(*command))
    private fun tidy(text: String) = text.replace(Regex("\\s+"), " ").take(300)

    /** What [command] printed; never held more than [COMMAND_S], since callers hold the lock. */
    private fun output(vararg command: String): String =
        // "Error" in it, so whoever looks for one in the answer sees it failed.
        exec(COMMAND_S, *command)?.trim() ?: "Error: ${command.joinToString(" ")} sin respuesta en $COMMAND_S s"

    private fun say(text: String) {
        runCatching { log?.line(text) }
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL
        // The connected device asks it of the application, which a shell process has none of.
        override fun getApplicationContext() = this

        @RequiresApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
    }

    private companion object {
        const val SHELL = "com.android.shell"
        /** Longest a system command is waited for: am start and cmd answer in well under that. */
        const val COMMAND_S = 20L

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

        /**
         * On a connected device: its own group (not OWN_DISPLAY_GROUP, which Android 17 does
         * not honour and which would keep it out of the device's), and no lock screen.
         */
        const val DEVICE_FLAGS = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
            TRUSTED or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP

        /** VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED. */
        const val LOCK_STATE_ALWAYS_UNLOCKED = 1

        // WindowConfiguration.WINDOWING_MODE_*.
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_PINNED = 2

        /** A made-up, locally administered address that only names the bike's pairing. */
        const val PAIRING = "02:4d:4f:54:4f:01"
        const val PAIRING_TRIES = 10
        const val PAIRING_WAIT_MS = 300L
        const val APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
        /** More than earlier versions could have piled up on any phone. */
        const val MAX_PAIRINGS = 50
    }
}
