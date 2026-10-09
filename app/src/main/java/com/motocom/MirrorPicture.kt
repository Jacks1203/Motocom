package com.motocom

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.motocom.bike.BikePicture
import com.motocom.bike.PictureRequest
import com.motocom.shell.ShellLink
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The picture for the bike is another app: Waze, Google Maps or whichever was chosen.
 *
 * A screen of the bike's size is made through ADB ([com.motocom.shell.DisplayService]),
 * painting straight into the encoder, and the app is taken to it. Android draws the app there
 * as it would on a second monitor, so it fills the whole of the bike's screen, and none of this
 * needs the phone's own screen: it can be off and locked.
 *
 * The screen outlives the encoder. When the bike stops asking for a while (it went, or it
 * started over) the app stays on that screen and the next encoder is simply handed to it; only
 * [release] gives the app back to the phone.
 */
class MirrorPicture(
    private val log: (String) -> Unit,
    /** Which app goes to the bike: its package and launcher activity. */
    private val app: () -> Pair<String, String>?,
    private val dpi: () -> Int
) : BikePicture {

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var surface: Surface? = null

    /** The screen the chosen app was last taken to; another screen needs it taken again. */
    @Volatile private var appOn = -1

    /** The bike's screen, or -1. */
    @Volatile var displayId = -1
        private set

    /** SPS and PPS: what a decoder needs before the first frame. */
    @Volatile private var header: ByteArray? = null

    /** Until a whole frame comes out, the ones that build on earlier frames are no use. */
    @Volatile private var waitingForWholeFrame = true

    @Synchronized
    override fun start(request: PictureRequest): Boolean {
        stopEncoder()
        val shell = ShellLink.await()
        if (shell == null) {
            log("ADB no está listo: no se puede hacer la pantalla para la moto")
            return false
        }
        val width = request.encodedWidth
        val height = request.encodedHeight
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, if (request.fps > 0) request.fps else FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
                // A still map gives the encoder nothing to do; this keeps frames coming.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
            val thread = HandlerThread("moto-codec").also { it.start() }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.setCallback(output, Handler(thread.looper))
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface()
            header = null
            waitingForWholeFrame = true
            chunks.clear()
            encoder.start()
            codec = encoder
            codecThread = thread
            surface = input
            log("codificador ${encoder.name}: ${width}x$height a ${request.bitRate / 1000} kbit/s")

            val id = shell.show(input, width, height, dpi())
            if (id < 0) {
                stopEncoder()
                return false
            }
            displayId = id
            if (appOn != id) {
                bringApp(id)
                appOn = id
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
            return false
        }
        val shell = ShellLink.display ?: return false
        return try {
            log("llevando ${chosen.first} a la pantalla $display")
            shell.bring(chosen.first, chosen.second, display)
            true
        } catch (e: Exception) {
            log("no se pudo llevar la app: $e")
            false
        }
    }

    override fun fresh() {
        chunks.clear()
        waitingForWholeFrame = true
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    override fun next(waitMs: Long): ByteArray? = chunks.poll(waitMs, TimeUnit.MILLISECONDS)

    /** The bike stopped asking: the encoder goes, the screen and the app on it stay. */
    @Synchronized
    override fun stop() = stopEncoder()

    /** Done with the bike: the screen goes and the app comes back to the phone. */
    @Synchronized
    fun release() {
        stopEncoder()
        runCatching { ShellLink.display?.release() }
        displayId = -1
        appOn = -1
    }

    private fun stopEncoder() {
        // The screen lets go of the surface before the surface goes.
        if (surface != null) runCatching { ShellLink.display?.detachSurface() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        codecThread?.quitSafely()
        codecThread = null
        chunks.clear()
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
            if (bytes.isEmpty()) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                header = bytes
                return
            }
            val whole = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
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
                chunks += (header ?: ByteArray(0)) + bytes
            } else {
                chunks += bytes
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            log("el codificador falla: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (header != null) return
            val sps = format.getByteBuffer("csd-0") ?: return
            val pps = format.getByteBuffer("csd-1") ?: return
            header = ByteArray(sps.remaining() + pps.remaining()).also {
                sps.duplicate().get(it, 0, sps.remaining())
                pps.duplicate().get(it, sps.remaining(), pps.remaining())
            }
        }
    }

    private companion object {
        /** The bike asks frame by frame and leaves the pace to the phone. */
        const val FPS = 30
        /** About two seconds of frames nobody came for. */
        const val BACKLOG = 60
    }
}
