package com.motocom

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.motocom.bike.BikePicture
import java.io.File
import java.io.OutputStream
import com.motocom.bike.PictureRequest
import com.motocom.shell.ShellLink
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Where the chosen app is, as far as this app knows. */
enum class AppPlace { NOWHERE, BIKE, PHONE }

/**
 * The picture for the bike is another app: Waze, Google Maps or whichever was chosen.
 *
 * A screen of the bike's size is made through ADB ([com.motocom.shell.DisplayService]),
 * painting into the encoder through [BlackEdges], and the app is taken to it. Android draws the app there
 * as it would on a second monitor, so it fills the whole of the bike's screen, and none of this
 * needs the phone's own screen: it can be off and locked.
 *
 * The screen outlives the encoder. When the bike stops asking for a while (it went, or it
 * started over) the app stays on that screen and the next encoder is simply handed to it; only
 * [release] gives the app back to the phone. Brought to the phone by the user ([toPhone]), it
 * stays there until [toBike].
 */
class MirrorPicture(
    private val log: (String) -> Unit,
    /** Which app goes to the bike: its package and launcher activity. */
    private val app: () -> Pair<String, String>?,
    private val dpi: () -> Int,
    /** Each time the app moves, or failed to; from any thread. */
    private val onApp: (AppPlace) -> Unit = {},
    /** Where the first seconds of what the bike gets are kept, to play them on a computer. */
    private val recording: File? = null
) : BikePicture {

    private val chunks = LinkedBlockingQueue<ByteArray>()
    /** Guards [chunks] together with [waitingForWholeFrame]. */
    private val queue = Any()
    @Volatile private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var surface: Surface? = null
    private var edges: BlackEdges? = null

    /** What the encoder was last started with, to start it again if it fails. */
    @Volatile private var lastRequest: PictureRequest? = null

    /**
     * The screen the chosen app was last taken to, -1 if none, or [BY_USER] if the user took it
     * to the phone; another screen needs it taken again.
     */
    @Volatile private var appOn = -1

    /** One move of the app after another, in the order they were asked for. */
    private val moving = Any()
    /** Made when first needed, gone with [release]; guarded by [moverLock], never held long. */
    private var mover: ExecutorService? = null
    private val moverLock = Any()

    /** The bike's screen, or -1. */
    @Volatile var displayId = -1
        private set

    /** SPS and PPS: what a decoder needs before the first frame. */
    @Volatile private var header: ByteArray? = null

    /** Until a whole frame comes out, the ones that build on earlier frames are no use. */
    private var waitingForWholeFrame = true

    /** Let go of for good: a START from the bike racing Disconnect must not bring it all back. */
    @Volatile private var released = false

    /** When the encoder was last started over after failing; a few a minute at most. */
    private val restarts = ArrayDeque<Long>()

    /** The first whole frames of each encoder, told in the record; only on the encoder's thread. */
    private var keyframesTold = 0

    /** Open while [recording] is being filled: once per connection, from the first frame. */
    private val recordLock = Any()
    private var recorder: OutputStream? = null
    private var recorded = 0L
    private var recordingDone = recording == null

    @Synchronized
    override fun start(request: PictureRequest): Boolean {
        if (released) return false
        stopEncoder()
        lastRequest = request
        val shell = ShellLink.await()
        if (shell == null) {
            log("ADB no está listo: no se puede hacer la pantalla para la moto")
            return false
        }
        val width = request.encodedWidth
        val height = request.encodedHeight
        return try {
            fun format(baseline: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, if (request.fps > 0) request.fps else FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
                // A still map gives the encoder nothing to do; this keeps frames coming.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                // The screen paints at 60 Hz and the bike takes about 30.
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FPS.toFloat())
                // The bike gets frames without times, in the order they come: none may be
                // reordered. Some encoders (MediaTek's) chose a profile the bike shows as black.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                if (baseline) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    // 3.1 holds up to 1280x720; bigger screens need 4.
                    val blocks = ((width + 15) / 16) * ((height + 15) / 16)
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        if (blocks <= 3600) MediaCodecInfo.CodecProfileLevel.AVCLevel31 else MediaCodecInfo.CodecProfileLevel.AVCLevel4
                    )
                }
            }
            // Each piece goes into its field as soon as it exists, so stopEncoder lets go of
            // whatever was made if a later step throws.
            val thread = HandlerThread("moto-codec").also { it.start() }
            codecThread = thread
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            keyframesTold = 0
            header = null
            synchronized(queue) {
                waitingForWholeFrame = true
                chunks.clear()
            }
            fun prepare(baseline: Boolean): Surface {
                encoder.setCallback(output, Handler(thread.looper))
                encoder.configure(format(baseline), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val made = encoder.createInputSurface()
                try {
                    encoder.start()
                } catch (e: Exception) {
                    made.release()
                    throw e
                }
                return made
            }
            // Some encoders take the profile and only fail when started.
            val input = try {
                prepare(true)
            } catch (e: Exception) {
                log("el codificador no acepta el perfil básico ($e): se usa el suyo")
                encoder.reset()
                prepare(false)
            }
            surface = input
            log("codificador ${encoder.name}: ${width}x$height a ${request.bitRate / 1000} kbit/s")
            val framed = BlackEdges(input, width, height, log)
            edges = framed

            val id = shell.show(framed.surface, width, framed.inner, dpi())
            if (id < 0) {
                stopEncoder()
                displayId = -1
                if (appOn != BY_USER) appOn = -1
                onApp(AppPlace.NOWHERE)
                return false
            }
            displayId = id
            synchronized(moving) {
                when (appOn) {
                    id -> onApp(AppPlace.BIKE)
                    // The user wants it on the phone: it goes back with "to the bike".
                    BY_USER -> {
                        log("la app está en el móvil: no se lleva a la moto")
                        onApp(AppPlace.PHONE)
                    }
                    // Failed, it is tried again on the next start.
                    else -> if (bringApp(id)) appOn = id
                }
            }
            true
        } catch (e: Throwable) {
            log("no se pudo preparar la imagen: $e")
            stopEncoder()
            false
        }
    }

    /** Takes the chosen app to [display]: the bike's, or 0 for the phone's. */
    fun bringApp(display: Int): Boolean {
        val chosen = app() ?: run {
            log("no hay app elegida para la moto")
            onApp(AppPlace.NOWHERE)
            return false
        }
        val shell = ShellLink.display ?: run {
            log("ADB no está listo: no se puede llevar la app")
            onApp(AppPlace.NOWHERE)
            return false
        }
        val done = try {
            log("llevando ${chosen.first} a la pantalla $display")
            val said = shell.bring(chosen.first, chosen.second, display)
            if (said.startsWith("fallo: ")) {
                log("no se pudo llevar la app: ${said.removePrefix("fallo: ")}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            log("no se pudo llevar la app: $e")
            false
        }
        // Failing to bring it to the phone leaves it where it was, which may well be the bike.
        val stayed = when {
            displayId >= 0 && appOn == displayId -> AppPlace.BIKE
            appOn == BY_USER -> AppPlace.PHONE
            else -> AppPlace.NOWHERE
        }
        onApp(if (!done) stayed else if (display == 0) AppPlace.PHONE else AppPlace.BIKE)
        return done
    }

    /** The app to the phone, to type a destination; it stays there until [toBike]. */
    fun toPhone() = move {
        if (bringApp(0)) appOn = BY_USER
    }

    /** The app back to the bike's screen, if there is one. */
    fun toBike() = move {
        val id = displayId
        if (id >= 0 && bringApp(id)) appOn = id
    }

    /** Runs [work] on the one thread that moves the app, after whatever was asked before. */
    private fun move(work: () -> Unit) {
        if (released) return
        val runner = synchronized(moverLock) {
            mover ?: Executors.newSingleThreadExecutor { job -> Thread(job, "moto-app").apply { isDaemon = true } }
                .also { mover = it }
        }
        try {
            runner.execute { synchronized(moving) { work() } }
        } catch (e: RejectedExecutionException) {
            log("no se puede mover la app: ya se ha parado")
        }
    }

    override fun fresh() {
        synchronized(queue) {
            chunks.clear()
            waitingForWholeFrame = true
        }
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    override fun next(waitMs: Long): ByteArray? =
        chunks.poll(waitMs, TimeUnit.MILLISECONDS)?.also { record(it) }

    /**
     * Keeps what goes to the bike, as it goes, until [RECORDING_BYTES]: a player on a computer
     * then shows whether the picture itself is right. Called only from the one reader.
     */
    private fun record(chunk: ByteArray): Unit = synchronized(recordLock) {
        if (recordingDone) return
        try {
            val out = recorder ?: recording!!.outputStream().buffered(256 * 1024).also {
                recorder = it
                log("grabando lo que se envía a la moto, para el registro")
            }
            out.write(chunk)
            recorded += chunk.size
            if (recorded >= RECORDING_BYTES) endRecording()
        } catch (e: Exception) {
            log("no se pudo grabar lo que se envía a la moto: $e")
            endRecording()
        }
    }

    private fun endRecording(): Unit = synchronized(recordLock) {
        // Also before anything came: a late frame must not open a file nobody closes.
        recordingDone = true
        val out = recorder ?: return
        recorder = null
        runCatching { out.close() }
        log("grabados ${recorded / 1024} KB de lo enviado a la moto (salen al enviar el registro)")
    }

    /** The bike stopped asking: the encoder goes, the screen and the app on it stay. */
    @Synchronized
    override fun stop() = stopEncoder()

    /** Done with the bike: the screen goes and the app comes back to the phone. */
    @Synchronized
    fun release() {
        released = true
        stopEncoder()
        endRecording()
        lastRequest = null
        val runner = synchronized(moverLock) { mover.also { mover = null } }
        // Moves still waiting no longer matter: the app is coming back to the phone.
        runner?.shutdownNow()
        synchronized(moving) {
            runCatching { ShellLink.display?.release() }
            displayId = -1
            appOn = -1
        }
        onApp(AppPlace.NOWHERE)
    }

    private fun stopEncoder() {
        // The screen lets go of the surface before the surface goes.
        if (surface != null) runCatching { ShellLink.display?.detachSurface() }
        edges?.release()
        edges = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        codecThread?.quitSafely()
        codecThread = null
        synchronized(queue) { chunks.clear() }
    }

    /**
     * The encoder failed for good and the picture would stay frozen: another one, from a thread
     * of its own, since stopping this one ends the thread its callbacks come on.
     */
    private fun restart(failed: MediaCodec) {
        thread(name = "moto-reinicio", isDaemon = true) {
            synchronized(this@MirrorPicture) {
                // Already stopped, released or started again since.
                if (codec !== failed) return@thread
                val request = lastRequest ?: return@thread
                val now = System.currentTimeMillis()
                while (restarts.isNotEmpty() && now - restarts.first() > 60_000) restarts.removeFirst()
                if (restarts.size >= 3) {
                    log("el codificador sigue fallando: no se reinicia más. Desconecta y vuelve a conectar.")
                    return@thread
                }
                restarts.addLast(now)
                log("se reinicia el codificador")
                if (!start(request)) log("no se pudo reiniciar el codificador: la moto se queda sin imagen")
            }
        }
    }

    private val output = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val bytes = try {
                val buffer = codec.getOutputBuffer(index) ?: return
                ByteArray(info.size).also {
                    buffer.position(info.offset)
                    buffer.get(it)
                }
            } catch (e: IllegalStateException) {
                return
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            if (bytes.isEmpty() || codec !== this@MirrorPicture.codec) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                header = bytes
                log("cabecera del vídeo: ${describe(bytes)}")
                return
            }
            val whole = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            // Each whole frame carries what is needed to decode it, as the bike's own app sends
            // them: a bike that missed the first one, or started over, still gets a picture.
            val h = header
            val frame = if (whole && h != null) withHeader(h, bytes) else bytes
            if (whole && keyframesTold < KEYFRAMES_TOLD) {
                keyframesTold++
                log("imagen completa ${keyframesTold}: ${frame.size} bytes, partes ${nalTypes(frame).joinToString(",")}")
            }
            // Checking and adding in one go, so fresh() cannot empty the queue in between.
            synchronized(queue) {
                // The bike is slower than the encoder, or has stopped asking: start over from a
                // whole frame rather than hand it a gap.
                if (chunks.size > BACKLOG) {
                    chunks.clear()
                    waitingForWholeFrame = true
                    runCatching {
                        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                }
                if (waitingForWholeFrame) {
                    if (!whole) return
                    waitingForWholeFrame = false
                }
                chunks += frame
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            log("el codificador falla: ${e.diagnosticInfo}")
            if (!e.isTransient) restart(codec)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (header != null) return
            val sps = format.getByteBuffer("csd-0") ?: return
            val pps = format.getByteBuffer("csd-1") ?: return
            val made = ByteArray(sps.remaining() + pps.remaining()).also {
                sps.duplicate().get(it, 0, sps.remaining())
                pps.duplicate().get(it, sps.remaining(), pps.remaining())
            }
            header = made
            log("cabecera del vídeo (del formato): ${describe(made)}")
        }
    }

    /**
     * [frame] with [header] in it unless it has its own: after an access unit delimiter if it
     * starts with one, since nothing may come before that.
     */
    private fun withHeader(header: ByteArray, frame: ByteArray): ByteArray {
        val types = nalTypes(frame)
        if (SPS in types) return frame
        if (types.firstOrNull() != AUD) return header + frame
        // Where the unit after the delimiter starts, with its start code (3 or 4 bytes).
        val next = (4 until frame.size - 2).firstOrNull {
            frame[it].toInt() == 0 && frame[it + 1].toInt() == 0 && frame[it + 2].toInt() == 1
        }?.let { if (frame[it - 1].toInt() == 0) it - 1 else it } ?: return header + frame
        return frame.copyOfRange(0, next) + header + frame.copyOfRange(next, frame.size)
    }

    /** The kinds of the NAL units in [bytes], by their Annex-B start codes. */
    private fun nalTypes(bytes: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var i = 0
        while (i + 3 < bytes.size && types.size < 12) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                types += bytes[i + 3].toInt() and 0x1F
                i += 3
            }
            i++
        }
        return types
    }

    /** Profile and level from the SPS in [header], which is what decides if the bike can play it. */
    private fun describe(header: ByteArray): String {
        val types = nalTypes(header)
        val at = (0 until header.size - 6).firstOrNull {
            header[it].toInt() == 0 && header[it + 1].toInt() == 0 && header[it + 2].toInt() == 1 &&
                header[it + 3].toInt() and 0x1F == SPS
        }
        val profile = at?.let {
            when (val idc = header[it + 4].toInt() and 0xFF) {
                66 -> if (header[it + 5].toInt() and 0x40 != 0) "básico restringido" else "básico"
                77 -> "principal"
                100 -> "alto"
                else -> "perfil $idc"
            }
        } ?: "sin SPS"
        val level = at?.let { (header[it + 6].toInt() and 0xFF) / 10.0 }
        return "$profile, nivel ${level ?: "?"}, ${header.size} bytes, partes ${types.joinToString(",")}"
    }

    private companion object {
        /** The bike asks frame by frame and leaves the pace to the phone. */
        const val FPS = 30
        /** About two seconds of frames nobody came for. */
        const val BACKLOG = 60
        /** [appOn] when the user took the app to the phone. */
        const val BY_USER = -2
        /** NAL kind of an SPS. */
        const val SPS = 7
        /** NAL kind of an access unit delimiter. */
        const val AUD = 9
        /** The first whole frames of each encoder that get a line in the record. */
        const val KEYFRAMES_TOLD = 3
        /** About half a minute of what goes to the bike. */
        const val RECORDING_BYTES = 12L * 1024 * 1024
    }
}
