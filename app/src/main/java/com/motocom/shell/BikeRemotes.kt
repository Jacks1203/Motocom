package com.motocom.shell

import android.annotation.SuppressLint
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import java.io.File
import java.io.FileInputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bluetooth remotes, such as a roadbook's, while an app shows on the bike's screen.
 *
 * Android gives a remote's keys to the screen in focus, which is the phone's, and with the phone
 * locked to no app at all: its arrows then turn the volume. So, for as long as [target] names
 * the bike's screen, each Bluetooth device with keys is taken for this process alone, straight
 * from the kernel, and its keys are handed to the bike's screen. Android sees none of them, the
 * phone's own buttons stay its own, and the remote goes back to the phone once the bike's
 * screen is empty or gone.
 */
internal class BikeRemotes(
    private val say: (String) -> Unit,
    /** The bike's screen while some app is on it, -1 otherwise. Asked every [POLL_MS]. */
    private val target: () -> Int,
) {
    private class Remote(val path: String, val name: String, val stream: FileInputStream) {
        @Volatile var done = false
    }

    private val held = mutableMapOf<String, Remote>()
    /** Where the keys go now; read by every remote's thread. */
    @Volatile private var to = -1
    /** Devices that could not be taken, said once each. */
    private val refused = mutableSetOf<String>()
    /** /dev/input as last looked at: "getevent -i" runs again only when it changes. */
    private var lastNodes: List<String>? = null
    private var targetFailed = false
    /** Whether the last "getevent -i" could open every device: one just made may not be readable yet. */
    private var listedAll = true
    private val wake = Object()
    /** Counts [letGo]s, so a [target] asked before one cannot undo it. */
    @Volatile private var generation = 0
    /** A [letGo] not yet seen by the round, so its wake-up is never lost. */
    private var pending = false
    /**
     * Why Android refused a key sent here, null while it takes them. Xiaomi refuses them until
     * "USB debugging (Security settings)" is on: a remote taken then would reach nobody, so the
     * remotes stay with the phone until the bike's screen is made again.
     */
    @Volatile private var refusal: Throwable? = null

    init {
        thread(isDaemon = true, name = "mandos-moto") {
            while (true) {
                runCatching { round() }.onFailure { say("mandos: $it") }
                synchronized(wake) {
                    if (!pending) wake.wait(POLL_MS)
                    pending = false
                }
            }
        }
    }

    /** The bike's screen is gone: keys stop going there now, and the remotes go back soon after. */
    fun letGo() {
        synchronized(wake) {
            generation++
            to = -1
            refusal = null
        }
        wakeRound()
    }

    private fun wakeRound() {
        synchronized(wake) {
            pending = true
            wake.notifyAll()
        }
    }

    private fun round() {
        held.values.filter { it.done }.forEach { held.remove(it.path); lastNodes = null }
        val asked = generation
        val now = try {
            target().also { targetFailed = false }
        } catch (e: Throwable) {
            // Better the remote back with the phone than sending keys to a screen not known to be there.
            if (!targetFailed) say("mandos: no se sabe qué hay en la pantalla de la moto (${e.cause ?: e}), se devuelven al móvil")
            targetFailed = true
            -1
        }
        to = if (generation == asked && refusal == null) now else -1
        if (to < 0) {
            letGoAll()
            return
        }
        val nodes = File("/dev/input").list()?.sorted()
        if (nodes != null && nodes == lastNodes) return
        val devices = bluetoothKeyDevices() ?: return
        var all = true
        for (device in devices) {
            if (device.path in held) continue
            if (!take(device)) all = false
        }
        // Looked at again only once all were taken: one that failed is tried on the next round.
        lastNodes = if (all && listedAll) nodes else null
    }

    private fun letGoAll() {
        held.values.forEach {
            it.done = true
            runCatching { it.stream.close() }
            say("mando «${it.name}» devuelto al móvil")
        }
        held.clear()
        lastNodes = null
    }

    private fun take(device: Device): Boolean {
        val keys = layout(device.vendor, device.product)
        if (keys.isEmpty()) {
            // Taken, it would reach neither the bike nor the phone.
            if (refused.add(device.path + device.name)) say("mando «${device.name}»: no se encuentra su distribución de teclas, se queda con el móvil")
            return true
        }
        val stream = try {
            FileInputStream(device.path)
        } catch (e: Exception) {
            if (refused.add(device.path + device.name)) say("mando «${device.name}»: no se puede leer ($e)")
            return false
        }
        try {
            grab(stream.fd)
        } catch (e: Throwable) {
            // Without it Android would get each key too: better left to the phone than doubled.
            runCatching { stream.close() }
            if (refused.add(device.path + device.name)) say("mando «${device.name}»: no se deja tomar (${e.cause ?: e})")
            return false
        }
        val remote = Remote(device.path, device.name, stream)
        held[device.path] = remote
        say("mando «${device.name}» (bluetooth ${device.vendor}:${device.product}, ${device.kinds}) tomado: " +
            "sus teclas van a la pantalla de la moto")
        thread(isDaemon = true, name = "mando-moto") { forward(remote, keys) }
        return true
    }

    /**
     * Reads the kernel's events until the remote goes or is let go, and passes its keys on, and
     * a game pad's cross as the arrows Android would make of it. A key still down then is let
     * up, cancelled, where it went down, so the app is not left with it held.
     */
    private fun forward(remote: Remote, keys: Map<Int, Int>) {
        val size = if (Process.is64Bit()) 24 else 16 // struct input_event: timeval, type, code, value
        val bytes = ByteArray(size)
        val event = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        /** Android's key, by the kernel's (or by a cross's axis), while down: when, and where to. */
        class Down(val key: Int, val at: Long, val display: Int, val gen: Int) {
            var repeats = 0
            var repeating: ScheduledFuture<*>? = null
        }
        val down = mutableMapOf<Int, Down>()
        val told = mutableSetOf<Int>()
        // Android repeats a held key itself only when it comes from the device, not when sent:
        // this repeats it the same way, on the same timings.
        val repeater = Executors.newSingleThreadScheduledExecutor { Thread(it, "mando-repite").apply { isDaemon = true } }

        fun scanCode(source: Int) = if (source >= HAT_SOURCE) 0 else source
        fun press(source: Int, key: Int) {
            val now = SystemClock.uptimeMillis()
            // In this order: a [letGo] in between leaves gen behind, so a refusal then is not kept.
            val gen = generation
            val display = to
            val sent = send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                scanCode(source), 0, InputDevice.SOURCE_KEYBOARD), display, gen)
            if (sent) {
                val held = Down(key, now, display, gen)
                synchronized(down) { down[source] = held }
                held.repeating = repeater.scheduleWithFixedDelay({
                    synchronized(down) {
                        if (down[source] !== held) return@synchronized
                        held.repeats++
                        // As Android marks it, so an app waiting for a long press sees one.
                        val long = if (held.repeats == 1) KeyEvent.FLAG_LONG_PRESS else 0
                        send(KeyEvent(held.at, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, key, held.repeats, 0,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode(source), long, InputDevice.SOURCE_KEYBOARD), display, held.gen)
                    }
                }, ViewConfiguration.getKeyRepeatTimeout().toLong(), ViewConfiguration.getKeyRepeatDelay().toLong(),
                    TimeUnit.MILLISECONDS)
            }
            val denied = refusal
            if (denied != null) {
                if (told.add(REFUSED)) say("mando «${remote.name}»: Android no deja a Motocom mandar sus teclas a la moto " +
                    "($denied); se devuelve al móvil. En Xiaomi activa «Depuración USB (ajustes de seguridad)»")
                wakeRound()
            } else if (told.add(source)) {
                val where = if (sent) "a la pantalla de la moto ($display)" else "y no llegó a la moto (${if (display < 0) "sin pantalla" else lastError})"
                say("mando «${remote.name}»: ${KeyEvent.keyCodeToString(key)} $where")
            }
        }
        fun release(source: Int, cancel: Boolean = false) {
            val was = synchronized(down) { down.remove(source) } ?: return
            was.repeating?.cancel(false)
            send(KeyEvent(was.at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, was.key, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode(source), if (cancel) KeyEvent.FLAG_CANCELED else 0,
                InputDevice.SOURCE_KEYBOARD), was.display, was.gen)
        }

        try {
            while (!remote.done) {
                var read = 0
                while (read < size) {
                    val n = remote.stream.read(bytes, read, size - read)
                    if (n < 0) return
                    read += n
                }
                val type = event.getShort(size - 8).toInt() and 0xffff
                val code = event.getShort(size - 6).toInt() and 0xffff
                val value = event.getInt(size - 4)
                if (type == EV_ABS && (code == ABS_HAT0X || code == ABS_HAT0Y)) {
                    val axis = HAT_SOURCE + code
                    release(axis)
                    val key = when {
                        value == 0 -> null
                        code == ABS_HAT0X -> if (value < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                        else -> if (value < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                    }
                    if (key != null) press(axis, key)
                    continue
                }
                if (type != EV_KEY) continue
                val key = keys[code]
                if (key == null) {
                    if (told.add(code)) say("mando «${remote.name}»: tecla $code sin equivalente en Android, se ignora")
                    continue
                }
                // The kernel's own repeats are left out: the repeater above makes them.
                when (value) {
                    1 -> { release(code, cancel = true); press(code, key) }
                    0 -> release(code)
                }
            }
        } catch (e: Throwable) {
            // Thrown on out of a thread, it would end the whole process.
            if (!remote.done) say("mando «${remote.name}» desconectado (${e.message})")
        } finally {
            remote.done = true
            runCatching { remote.stream.close() }
            synchronized(down) { down.keys.toList() }.forEach { release(it, cancel = true) }
            repeater.shutdownNow()
        }
    }

    /** Hands [event] to [display], as "adb shell input" would. */
    private fun send(event: KeyEvent, display: Int, gen: Int): Boolean {
        if (display < 0) return false
        return try {
            setDisplayId.invoke(event, display)
            inject(event).also { if (!it) lastError = IllegalStateException("Android no la aceptó") }
        } catch (e: Throwable) {
            var cause: Throwable = e
            while (cause is java.lang.reflect.InvocationTargetException && cause.cause != null) cause = cause.cause!!
            lastError = cause
            // One refused for a screen already gone must not keep the remotes from the next.
            if (cause is SecurityException) synchronized(wake) { if (gen == generation) refusal = cause }
            false
        }
    }

    /** The last reason a key could not be sent, for the record. */
    @Volatile private var lastError: Throwable? = null

    private class Device(val path: String, val name: String, val vendor: String, val product: String, val kinds: String)

    /**
     * The Bluetooth devices with keys, as "getevent -i" lists them: a remote, a keyboard or a
     * game pad. Not AVRCP, which is the headset's play and pause and stays with the music: the
     * Bluetooth stack names it so and gives it no vendor or product.
     */
    private fun bluetoothKeyDevices(): List<Device>? {
        val text = exec(5, "getevent", "-i") ?: return null
        listedAll = "could not open" !in text
        return text.split("add device ").drop(1).mapNotNull { block ->
            val path = Regex("""^\d+:\s*(\S+)""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            fun field(name: String) = Regex("""\n\s*$name:?\s+(\S+)""").find(block)?.groupValues?.get(1)
            val name = Regex("""\n\s*name:\s*"(.*)"""").find(block)?.groupValues?.get(1) ?: ""
            val vendor = field("vendor") ?: "0000"
            val product = field("product") ?: "0000"
            val bluetooth = field("bus") == BUS_BLUETOOTH
            val keys = Regex("""\n\s*KEY \(0001\):""").containsMatchIn(block)
            val avrcp = name.contains("avrcp", ignoreCase = true) || (vendor == "0000" && product == "0000")
            if (!bluetooth || !keys || avrcp) return@mapNotNull null
            // What it sends, for the record: KEY, ABS for a pad's sticks and cross, REL for a mouse.
            val kinds = Regex("""\n\s*([A-Z]{2,4})\s+\(00[0-9a-f]{2}\):""").findAll(block)
                .map { it.groupValues[1] }.distinct().joinToString("+")
            Device(path, name, vendor, product, kinds)
        }
    }

    /**
     * The kernel's key numbers and Android's, from the key layout Android itself would use for
     * this device: its own file if the phone has one, the generic one if not.
     */
    private fun layout(vendor: String, product: String): Map<Int, Int> {
        val names = listOf("Vendor_${vendor}_Product_${product}.kl", "Generic.kl")
        val file = names.firstNotNullOfOrNull { name ->
            LAYOUT_DIRS.map { File(it, name) }.firstOrNull { it.canRead() }
        } ?: return emptyMap()
        val keys = mutableMapOf<Int, Int>()
        runCatching {
            file.forEachLine { line ->
                val m = Regex("""^\s*key\s+(\d+)\s+(\w+)""").find(line) ?: return@forEachLine
                val code = KeyEvent.keyCodeFromString("KEYCODE_" + m.groupValues[2])
                if (code != KeyEvent.KEYCODE_UNKNOWN) keys[m.groupValues[1].toInt()] = code
            }
        }
        return keys
    }

    private companion object {
        const val POLL_MS = 2000L
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val ABS_HAT0X = 0x10
        const val ABS_HAT0Y = 0x11
        /** Kept apart from the kernel's key numbers, which stop well short of it. */
        const val HAT_SOURCE = 0x10000
        /** In a remote's "told" set: its keys were refused, said once. */
        const val REFUSED = -1
        const val BUS_BLUETOOTH = "0005"
        /** _IOW('E', 0x90, int): only this process reads the device until it is closed. */
        const val EVIOCGRAB = 0x40044590
        val LAYOUT_DIRS = listOf(
            "/odm/usr/keylayout", "/vendor/usr/keylayout", "/product/usr/keylayout", "/system_ext/usr/keylayout",
            "/system/usr/keylayout", "/data/system/devices/keylayout"
        )

        // Hidden, so found by name.
        val setDisplayId by lazy { KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

        /** InputManagerGlobal from Android 14, InputManager before; both hidden. */
        val inject: (InputEvent) -> Boolean by lazy {
            val manager = runCatching {
                Class.forName("android.hardware.input.InputManagerGlobal").let { it.getMethod("getInstance").invoke(null) }
            }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
            val method = manager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            val f: (InputEvent) -> Boolean = { method.invoke(manager, it, 0) as Boolean }
            f
        }

        /**
         * The grab is an int passed by value, any but 0 to take; the hidden ioctlInt passes a
         * pointer instead, never 0, so it always takes. Closing the device lets it go.
         */
        @SuppressLint("DiscouragedPrivateApi")
        fun grab(fd: FileDescriptor) {
            val ioctl = Class.forName("android.system.Os").declaredMethods.first { it.name == "ioctlInt" }
            if (ioctl.parameterCount == 2) {
                ioctl.invoke(null, fd, EVIOCGRAB)
            } else {
                val ref = Class.forName("android.system.Int32Ref").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                ioctl.invoke(null, fd, EVIOCGRAB, ref)
            }
        }
    }
}
