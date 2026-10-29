package com.motocom.bike

import android.net.Network
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** What the phone tells the bike about itself when the bike introduces itself. */
class PhoneCard(
    val uuid: String,
    val brand: String,
    val model: String,
    val androidSdk: Int,
    val bluetoothName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    /** An RSA public key the phone made up for itself, X.509 in base64. */
    val publicKey: String,
    /** The bike's identifier encrypted with the matching private key, in base64. */
    val sign: (String) -> String,
    // The bike was only ever seen talking to Carbit Ride 2.4, so that is who the phone says it
    // is until it is known whether the bike minds another name.
    val packageName: String = "net.easyconn.easyride.wws",
    val versionCode: Int = 47,
    val fingerprint: String = "V:2.4(47)-eee8ab8d1-ONLINE"
)

/** Where the picture comes from. Every call arrives on one of the link's own threads. */
interface BikePicture {
    /** Get ready to produce what the bike asked for. False if it cannot be done. */
    fun start(request: PictureRequest): Boolean

    /** The bike starts reading afresh: the next chunk has to be one it can decode on its own. */
    fun fresh()

    /** The next piece of H.264 with its start codes, or null if none turns up in [waitMs]. */
    fun next(waitMs: Long): ByteArray?

    fun stop()
}

enum class BikeState { LISTENING, GREETED, SHOWING, CLOSED }

interface BikeEvents {
    fun log(line: String)
    fun state(state: BikeState) {}
    fun input(input: BikeInput) {}

    /**
     * The bike's side of the conversation died without the phone closing it. Said once per
     * link; the link does nothing more by itself, so whoever made it closes it and makes another.
     */
    fun gone(why: String) {}
}

/**
 * The phone's end of the conversation with the bike's screen, which is an EasyConn head unit.
 *
 * The phone is the server throughout. Once both are on the same wifi-direct group the phone
 * knocks on the bike's door ([knock]), and the bike answers by opening four connections to it:
 *
 * ```
 *   pxcPort      the bike asks, the phone answers: who it is, the time, "still there?"
 *   pxcPort      the phone asks, the bike answers: mostly "the app is in front", once a second
 *   controlPort  the bike says what picture it wants and tells it to start
 *   dataPort     the bike asks for a frame, the phone sends one; again, thirty times a second
 * ```
 *
 * Learnt by reading how Carbit Ride does it and what it logged during a real session with the
 * bike on 30-09-2026; PANTALLA-MOTO.md has the story. Nothing of Carbit's is in here.
 */
class BikeLink(
    private val card: PhoneCard,
    private val picture: BikePicture,
    private val events: BikeEvents,
    private val pxcPort: Int = 10922,
    private val controlPort: Int = 10921,
    private val dataPort: Int = 10920,
    /**
     * Who may connect, from where and to which of the phone's addresses: anybody else on the
     * same network is shown the door at once.
     */
    private val allow: (from: InetAddress, to: InetAddress?) -> Boolean = { _, _ -> true }
) : Closeable {

    @Volatile private var closed = false
    private val doors = mutableListOf<ServerSocket>()
    private val open = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val toAsk = LinkedBlockingQueue<PxcMessage>()
    private val saidOnce = Collections.synchronizedSet(mutableSetOf<String>())
    private val refused = Collections.synchronizedSet(mutableSetOf<InetAddress>())
    private val goneSaid = AtomicBoolean(false)

    // A newer connection of the same kind takes over, and the older one must not undo its work.
    private val controlTurn = AtomicInteger()
    private val dataTurn = AtomicInteger()
    @Volatile private var dataSocket: Socket? = null

    /** What the bike asked for, kept for the data connection, which arrives separately. */
    @Volatile private var request: PictureRequest? = null
    @Volatile private var bikeTimeoutMs = 9000

    /** The bike's own words about itself, once it has introduced itself. */
    @Volatile var bikeInfo: JsonObject? = null
        private set

    /** The ports really taken, which only differ from the ones asked for in tests. */
    var pxcPortBound = 0; private set
    var controlPortBound = 0; private set
    var dataPortBound = 0; private set

    @Throws(IOException::class)
    fun open() {
        pxcPortBound = listen("pxc", pxcPort, ::pxc)
        controlPortBound = listen("control", controlPort, ::control)
        dataPortBound = listen("data", dataPort, ::data)
        events.state(BikeState.LISTENING)
    }

    /**
     * Tells the bike the phone is there and listening. The bike only comes to the phone's ports
     * after this. [from] ties the call to the wifi-direct side of a phone that is also on
     * mobile data; [network], to the bike's wifi joined as an ordinary one, which only routes
     * what is made on it.
     */
    fun knock(bike: InetAddress, from: InetAddress? = null, network: Network? = null, port: Int = 10930): Boolean {
        return try {
            Socket().use { socket ->
                if (network != null) network.bindSocket(socket)
                else if (from != null) socket.bind(InetSocketAddress(from, 0))
                socket.connect(InetSocketAddress(bike, port), 2000)
                socket.soTimeout = 2000
                val hello = buildJsonObject {
                    put("phoneType", "Android")
                    put("packageName", card.packageName)
                }
                Pxc.write(socket.getOutputStream(), Pxc.KNOCK, hello.toString().toByteArray())
                val answer = Pxc.read(socket.getInputStream())
                sayKnock("responde ${answer.text.ifEmpty { answer.toString() }}")
                answer.command == Pxc.KNOCK + 1 &&
                    json(answer)?.get("status")?.jsonPrimitive?.contentOrNull.equals("true", ignoreCase = true)
            }
        } catch (e: IOException) {
            // The local port in Android's message changes every call; it is not news.
            sayKnock(e.message.orEmpty().replace(Regex("\\(port \\d+\\)"), "").replace(Regex(" from /\\S+"), ""))
            false
        }
    }

    /** Calls go every few seconds until the bike comes: the same outcome is worth one line. */
    @Volatile private var lastKnock = ""
    private fun sayKnock(outcome: String) {
        if (outcome != lastKnock) events.log("llamada a la moto: $outcome")
        lastKnock = outcome
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(doors) { doors.forEach { runCatching { it.close() } } }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        runCatching { picture.stop() }
        events.state(BikeState.CLOSED)
    }

    private fun listen(name: String, port: Int, serve: (Socket) -> Unit): Int {
        val door = ServerSocket(port, 50)
        synchronized(doors) { doors += door }
        thread(name = "moto-$name", isDaemon = true) {
            while (!closed) {
                val socket = try { door.accept() } catch (e: IOException) { break }
                val from: InetAddress? = socket.inetAddress
                if (from == null || !runCatching { allow(from, socket.localAddress) }.getOrDefault(false)) {
                    if (from != null && refused.add(from)) events.log("conexión rechazada de ${from.hostAddress}")
                    runCatching { socket.close() }
                    continue
                }
                // The bike opens four; a pile of them means something is wrong, not more bike.
                val room = synchronized(open) { (open.size < MAX_OPEN).also { if (it) open += socket } }
                if (!room) {
                    once("lleno-$name") { "$name: demasiadas conexiones abiertas, se rechaza la de ${from.hostAddress}" }
                    runCatching { socket.close() }
                    continue
                }
                // close() may have gone through the open ones just before this one was added.
                if (closed) {
                    open -= socket
                    runCatching { socket.close() }
                    break
                }
                thread(name = "moto-$name-${socket.port}", isDaemon = true) {
                    try {
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        serve(socket)
                    } catch (e: IOException) {
                        if (!closed) events.log("$name: se corta (${e.message})")
                    } catch (e: Exception) {
                        // Ours, not the bike's: a thread dying of it would take the app along.
                        events.log("$name: fallo nuestro: $e")
                    } finally {
                        open -= socket
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return door.localPort
    }

    // ---- The conversation ---------------------------------------------------------------

    private fun pxc(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val opening = Pxc.read(input)
        Pxc.write(output, opening.command + 1)
        when (opening.command) {
            Pxc.OPEN_BIKE_ASKS -> {
                events.log("la moto abre el canal de sus preguntas")
                endsSession("el canal de preguntas de la moto") {
                    while (!closed) {
                        // The bike beats once a second, so three of its own timeouts is plenty.
                        // Set every time: its introduction, on this very channel, may change it.
                        socket.soTimeout = 3 * bikeTimeoutMs
                        answerBike(Pxc.read(input), output)
                    }
                }
            }
            Pxc.OPEN_PHONE_ASKS -> {
                events.log("la moto abre el canal de nuestras preguntas")
                // Only the bike's own beat, seen every second with the real bike, ends the
                // session; this one failing is logged, as it always was.
                askBike(socket)
            }
            else -> events.log("pxc: apertura desconocida $opening")
        }
    }

    private fun answerBike(asked: PxcMessage, output: java.io.OutputStream) {
        // Odd commands are replies, and nothing here is waiting for one.
        if (asked.command and 1 != 0) return
        val reply = asked.command + 1
        when (asked.command) {
            Pxc.HEARTBEAT -> {
                once("latido") { "la moto late" }
                Pxc.write(output, reply)
            }
            Pxc.BIKE_INFO -> {
                val info = json(asked)
                bikeInfo = info
                events.log("la moto se presenta: ${asked.text.replace(Regex("\\s+"), "")}")
                info?.get("socketTimeoutPeriodWifi")?.jsonPrimitive?.intOrNull
                    ?.takeIf { it > 0 }?.let { bikeTimeoutMs = it.coerceAtMost(MAX_BIKE_TIMEOUT_S) * 1000 }
                Pxc.write(output, reply, greeting(info).toString().toByteArray())
                events.state(BikeState.GREETED)
            }
            Pxc.WHAT_TIME -> Pxc.write(output, reply, time().toString().toByteArray())
            Pxc.CHECK_SERIAL -> {
                // The bike hands over its serial number for the app to check with EasyConn's
                // server. This says what Carbit says when it cannot reach that server, which is
                // the plain truth here: nobody checked.
                val serial = json(asked)?.get("sn")?.jsonPrimitive?.contentOrNull.orEmpty()
                events.log("la moto da su número de serie")
                Pxc.write(output, reply)
                toAsk += PxcMessage(Pxc.SERIAL_CHECKED, buildJsonObject {
                    put("isOk", true)
                    put("errCode", -100)
                    put("errMsg", "Network Not Available")
                    put("id", serial)
                }.toString().toByteArray())
            }
            Pxc.HUD_ICONS, Pxc.WEATHER, 0x00010740 -> {
                once("c${asked.command}") { "la moto dice $asked ${asked.text.replace(Regex("\\s+"), "")}" }
                Pxc.write(output, reply)
            }
            Pxc.ENCRYPT -> {
                // Not seen with this bike. Carbit would switch the connection to TLS here.
                events.log("¡LA MOTO PIDE CIFRADO! No está hecho; se le dice que no.")
                Pxc.write(output, Pxc.NOT_SUPPORTED)
            }
            else -> {
                events.log("la moto pregunta algo nuevo: $asked ${asked.text.take(300)}")
                Pxc.write(output, Pxc.NOT_SUPPORTED)
            }
        }
    }

    private fun askBike(socket: Socket) {
        socket.soTimeout = bikeTimeoutMs
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        // Carbit repeats this about once a second for as long as it is on the screen, and it
        // doubles as the phone's own "still here".
        val inFront = PxcMessage(Pxc.APP_IN_FRONT, buildJsonObject {
            put("displayRotation", 0)
            put("width", card.screenWidth)
            put("height", card.screenHeight)
            put("enableAccessibility", false)
            put("enableAOAHid", true)
            put("mode", 2) // 2: a screen drawn for the bike, not a copy of the phone's
        }.toString().toByteArray())
        var next = inFront
        while (!closed) {
            Pxc.write(output, next.command, next.body)
            val answer = try {
                Pxc.read(input)
            } catch (e: SocketTimeoutException) {
                throw IOException("la moto no contesta a 0x%08X".format(next.command))
            }
            if (answer.command != next.command + 1) {
                once("r${next.command}") { "a 0x%08X la moto contesta $answer ${answer.text.take(200)}".format(next.command) }
            } else {
                once("ok${next.command}") { "la moto acepta 0x%08X".format(next.command) }
            }
            next = toAsk.poll(1, TimeUnit.SECONDS) ?: inFront
        }
    }

    /**
     * Either channel of the conversation ending, however it ends, means the bike is gone unless
     * the phone closed the link itself.
     */
    private inline fun endsSession(what: String, talk: () -> Unit) {
        var why = "$what se ha cerrado"
        try {
            talk()
        } catch (e: SocketTimeoutException) {
            why = "$what: la moto lleva demasiado sin decir nada"
            throw e
        } catch (e: Exception) {
            why = "$what: ${e.message ?: e}"
            throw e
        } finally {
            lost(why)
        }
    }

    private fun lost(why: String) {
        if (!closed && goneSaid.compareAndSet(false, true)) {
            events.log("se pierde la sesión con la moto: $why")
            events.gone(why)
        }
    }

    private fun greeting(bike: JsonObject?): JsonObject {
        val bikeId = bike?.get("HUID")?.jsonPrimitive?.contentOrNull.orEmpty()
        val signed = if (bikeId.isEmpty()) "" else try {
            card.sign(bikeId)
        } catch (e: Exception) {
            // Without it the bike may refuse the picture later, but the rest still goes on.
            events.log("no se pudo cifrar el identificador de la moto: $e")
            ""
        }
        return buildJsonObject {
            put("pxcVersion", "1.0.2")
            put("phoneUUID", card.uuid)
            put("phoneBrand", card.brand)
            put("phoneModel", card.model)
            put("phoneOsVersion", card.androidSdk.toString())
            put("phoneOs", "Android")
            put("package", card.packageName)
            put("versionCode", card.versionCode)
            put("token", 0)
            put("pubkey", card.publicKey)
            put("encryptedHUID", signed)
            put("bluetoothName", card.bluetoothName)
            put("supportH264IFrame", true)
            put("supportFunction", bike?.get("supportFunction")?.jsonPrimitive?.longOrNull ?: 0L)
            put("appVersionFingerPrint", card.fingerprint)
            put("speechEngineType", 2)
        }
    }

    private fun time(): JsonObject {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        return buildJsonObject {
            put("time", now)
            put("currentTime", now + zone.getOffset(now))
            put("currentTimeZone", zone.id)
            put("dateTime", SimpleDateFormat("dd.MM.yyyy HH:mm:ss:SSS", Locale.US).format(Date(now)))
        }
    }

    // ---- The picture --------------------------------------------------------------------

    private fun control(socket: Socket) {
        // No timeout here: how often the bike beats on this one is not known. It goes down with
        // the rest when the link is closed after the bike is gone.
        val turn = synchronized(controlTurn) { controlTurn.incrementAndGet() }
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            while (!closed) {
                val asked = Rv.read(input)
                when (asked.command) {
                    Rv.CONFIGURE -> {
                        val wanted = PictureRequest.parse(asked.body)
                        request = wanted
                        events.log("la moto pide imagen: $wanted")
                        Rv.write(output, Rv.CONFIGURE + 1, wanted.reply())
                    }
                    Rv.CONFIGURE_MORE -> {
                        val more = runCatching { Json.parseToJsonElement(String(asked.body)).jsonObject }.getOrNull()
                        events.log("la moto detalla la imagen: ${String(asked.body)}")
                        Rv.write(output, Rv.CONFIGURE_MORE + 1, buildJsonObject {
                            if (more?.containsKey("viewAreaConfig") == true) {
                                put("viewAreaConfig", buildJsonObject { put("state", 0) })
                            }
                            put("supportFunction", more?.get("supportFunction")?.jsonPrimitive?.intOrNull ?: 0)
                        }.toString().toByteArray())
                    }
                    Rv.VERSION -> Rv.write(
                        output, Rv.VERSION + 1,
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(5002).putInt(1).array()
                    )
                    Rv.HEARTBEAT -> Rv.write(output, Rv.HEARTBEAT + 1)
                    Rv.START -> {
                        val wanted = request
                        val started = wanted != null && wanted.encoder == 2 && picture.start(wanted)
                        events.log(if (started) "la moto pide empezar: codificador listo" else "la moto pide empezar y no se puede ($wanted)")
                        Rv.write(output, if (started) Rv.START + 1 else Rv.START_FAILED)
                    }
                    Rv.KEY_OR_TOUCH -> BikeInput.parse(asked.body)?.let {
                        events.log("la moto manda una pulsación: $it")
                        events.input(it)
                    }
                    Rv.SHELL -> {
                        val answer = when (String(asked.body).trim('\u0000', ' ')) {
                            "getprop ro.build.version.release" -> card.androidSdk.toString()
                            "getprop ro.product.model" -> card.model
                            else -> "OK"
                        }
                        Rv.write(output, Rv.SHELL + 1, (answer + "\u0000").toByteArray())
                    }
                    else -> {
                        events.log("control: orden desconocida $asked")
                        Rv.write(output, Rv.NOT_SUPPORTED)
                    }
                }
            }
        } finally {
            // An older control connection ending must not undo what a newer one set up.
            synchronized(controlTurn) {
                if (controlTurn.get() == turn) {
                    request = null
                    runCatching { picture.stop() }
                    if (!closed) events.state(BikeState.GREETED)
                }
            }
        }
    }

    private fun data(socket: Socket) {
        val wanted = request ?: run {
            events.log("datos: la moto viene a por imagen sin haberla pedido")
            return
        }
        // A new data connection replaces the old one, which is closed so that it stops at once.
        val turn = synchronized(dataTurn) {
            dataSocket?.let { old -> runCatching { old.close() } }
            dataSocket = socket
            dataTurn.incrementAndGet()
        }
        try {
            sendPicture(socket, wanted, turn)
        } catch (e: IOException) {
            // Closed on purpose because a newer one took over: nothing to tell.
            if (dataTurn.get() == turn) throw e
        } finally {
            synchronized(dataTurn) { if (dataSocket === socket) dataSocket = null }
        }
    }

    private fun sendPicture(socket: Socket, asked: PictureRequest, turn: Int) {
        var wanted = asked
        // No timeout here: the bike may stop asking while it shows a screen of its own, and a
        // dead session is found by its beat, which closes this socket with the rest.
        val input = socket.getInputStream()
        val output = socket.getOutputStream().buffered(64 * 1024)
        events.log("la moto viene a por la imagen")
        picture.fresh()
        var shown = false
        var sent = 0
        var empty = 0
        var countedFrom = System.currentTimeMillis()
        val length = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        while (!closed && dataTurn.get() == turn) {
            val now = request ?: run {
                events.log("datos: la moto ya no pide imagen")
                return
            }
            if (now !== wanted) wanted = now
            val wait = if (wanted.fps <= 0) {
                val next = Rv.read(input)
                if (next.command != Rv.NEXT_FRAME) {
                    events.log("datos: orden desconocida $next")
                    Rv.write(output, Rv.NOT_SUPPORTED)
                    return
                }
                FRAME_WAIT_MS
            } else {
                1000L / wanted.fps.coerceIn(1, MAX_FPS)
            }
            // A frame goes as its length and then its bytes; "nothing new" is a length of zero.
            val chunk = picture.next(wait)
            output.write(length.putInt(0, chunk?.size ?: 0).array())
            if (chunk != null) output.write(chunk)
            output.flush()
            if (chunk != null) sent++ else empty++
            // A line every so often says whether the bike keeps asking and the picture keeps
            // coming, which is all there is to go on afterwards.
            if (System.currentTimeMillis() - countedFrom >= TALLY_MS) {
                events.log("imagen: $sent enviadas y $empty peticiones sin nada nuevo en ${TALLY_MS / 1000} s")
                sent = 0
                empty = 0
                countedFrom = System.currentTimeMillis()
            }
            if (chunk != null && !shown) {
                shown = true
                events.log("primera imagen enviada (${chunk.size} bytes)")
                events.state(BikeState.SHOWING)
            }
        }
    }

    private fun json(message: PxcMessage): JsonObject? =
        runCatching { Json.parseToJsonElement(message.text).jsonObject }.getOrNull()

    /** Things that repeat every second are worth one line, not one a second. */
    private fun once(what: String, line: () -> String) {
        if (saidOnce.add(what)) events.log(line())
    }

    private companion object {
        /** How long a request for a frame waits for one before answering "nothing new". */
        const val FRAME_WAIT_MS = 40L
        const val TALLY_MS = 10_000L
        const val MAX_FPS = 60
        const val MAX_BIKE_TIMEOUT_S = 120
        /** The bike opens four connections; a few more allow for it coming back. */
        const val MAX_OPEN = 16
    }
}
