package com.offnavigator.bike

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.offnavigator.core.bike.BikePicture
import com.offnavigator.core.bike.PictureRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Paints a picture of the size the bike asks for ([BikeView]) and turns it into H.264.
 *
 * The picture is painted straight into the encoder, about 15 times a second, from a timer of
 * its own. No window and no screen are involved, so it goes on with the phone locked: the
 * phone's own screen can be off, and the picture keeps coming.
 */
class BikeEncoder(
    private val context: Context,
    private val log: (String) -> Unit,
    private val layout: BikeLayout
) : BikePicture {

    private val main = Handler(Looper.getMainLooper())
    private val chunks = LinkedBlockingQueue<ByteArray>()

    private var codec: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var surface: Surface? = null
    /** Main thread only. */
    private var view: BikeView? = null
    /** Tells the painting of one [start] from that of the next. */
    private var round = 0

    /** SPS and PPS: what a decoder needs before the first frame. */
    @Volatile private var header: ByteArray? = null

    /** Until a whole frame comes out, the ones that build on earlier frames are no use. */
    @Volatile private var waitingForWholeFrame = true

    @Synchronized
    override fun start(request: PictureRequest): Boolean {
        stop()
        val width = request.encodedWidth
        val height = request.encodedHeight
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, if (request.fps > 0) request.fps else FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
                // A still screen gives the encoder nothing to do; this keeps frames coming.
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

            val thisRound = ++round
            main.post { paint(thisRound, width, height) }
            log("codificador ${encoder.name}: ${width}x$height a ${request.bitRate / 1000} kbit/s")
            true
        } catch (e: Throwable) {
            log("no se pudo preparar la imagen: $e")
            stop()
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

    @Synchronized
    override fun stop() {
        round++
        main.post { dropView() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        codecThread?.quitSafely()
        codecThread = null
        chunks.clear()
    }

    /** One picture into the encoder, and the next one booked. Main thread. */
    private fun paint(thisRound: Int, width: Int, height: Int) {
        synchronized(this) {
            if (thisRound != round) return
            val target = surface ?: return
            val shown = view ?: try {
                BikeView(context, width, height, layout).also { view = it }
            } catch (e: Throwable) {
                log("no se pudo preparar la vista de la moto: $e")
                return
            }
            try {
                val canvas = target.lockHardwareCanvas()
                try {
                    shown.draw(canvas)
                } finally {
                    target.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                // Once in a while the encoder is not ready for a picture; the next will do.
                if (!warned) log("no se pudo pintar la imagen: $e")
                warned = true
            }
        }
        main.postDelayed({ paint(thisRound, width, height) }, FRAME_MS)
    }

    private var warned = false

    private fun dropView() {
        view?.release()
        view = null
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
        /** About 15 pictures a second: plenty for a map, and light on the phone. */
        const val FRAME_MS = 66L
        /** About two seconds of frames nobody came for. */
        const val BACKLOG = 60
    }
}
