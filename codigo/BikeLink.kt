package com.offnavigator.core.bike

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
    private val dataPort: Int = 10920
) : Closeable {

    @Volatile private var closed = false
    private val doors = mutableListOf<ServerSocket>()
    private val open = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val toAsk = LinkedBlockingQueue<PxcMessage>()
    private val saidOnce = Collections.synchronizedSet(mutableSetOf<String>())

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
     * mobile data.
     */
    fun knock(bike: InetAddress, from: InetAddress? = null, port: Int = 10930): Boolean {
        return try {
            Socket().use { socket ->
                if (from != null) socket.bind(InetSocketAddress(from, 0))
                socket.connect(InetSocketAddress(bike, port), 2000)
                socket.soTimeout = 2000
                val hello = buildJsonObject {
                    put("phoneType", "Android")
                    put("packageName", card.packageName)
                }
                Pxc.write(socket.getOutputStream(), Pxc.KNOCK, hello.toString().toByteArray())
                val answer = Pxc.read(socket.getInputStream())
                events.log("llamada a la moto: responde ${answer.text.ifEmpty { answer.toString() }}")
                answer.command == Pxc.KNOCK + 1 &&
                    json(answer)?.get("status")?.jsonPrimitive?.contentOrNull.equals("true", ignoreCase = true)
            }
        } catch (e: IOException) {
            events.log("llamada a la moto: ${e.message}")
            false
        }
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
                socket.tcpNoDelay = true
                socket.keepAlive = true
                open += socket
                thread(name = "moto-$name-${socket.port}", isDaemon = true) {
                    try {
                        serve(socket)
                    } catch (e: IOException) {
                        if (!closed) events.log("$name: se corta (${e.message})")
                    } catch (e: RuntimeException) {
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
                while (!closed) answerBike(Pxc.read(input), output)
            }
            Pxc.OPEN_PHONE_ASKS -> {
                events.log("la moto abre el canal de nuestras preguntas")
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
                    ?.takeIf { it > 0 }?.let { bikeTimeoutMs = it * 1000 }
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

    private fun greeting(bike: JsonObject?): JsonObject {
        val bikeId = bike?.get("HUID")?.jsonPrimitive?.contentOrNull.orEmpty()
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
            put("encryptedHUID", if (bikeId.isEmpty()) "" else card.sign(bikeId))
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
            request = null
            runCatching { picture.stop() }
            if (!closed) events.state(BikeState.GREETED)
        }
    }

    private fun data(socket: Socket) {
        val wanted = request ?: run {
            events.log("datos: la moto viene a por imagen sin haberla pedido")
            return
        }
        val input = socket.getInputStream()
        val output = socket.getOutputStream().buffered(64 * 1024)
        events.log("la moto viene a por la imagen")
        picture.fresh()
        var shown = false
        var sent = 0
        var empty = 0
        var countedFrom = System.currentTimeMillis()
        val length = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        while (!closed) {
            val wait = if (wanted.fps == 0) {
                val asked = Rv.read(input)
                if (asked.command != Rv.NEXT_FRAME) {
                    events.log("datos: orden desconocida $asked")
                    Rv.write(output, Rv.NOT_SUPPORTED)
                    return
                }
                FRAME_WAIT_MS
            } else {
                1000L / wanted.fps
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
    }
}
