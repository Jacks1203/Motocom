package com.motocom

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Puts a few black rows above and below the picture on its way to the encoder.
 *
 * The bike blends the top row of each frame with the bottom one when it scales it, so a light
 * bottom (Waze's panel) showed as a light line across the top. With black at both ends, what
 * gets blended is black. The screen for the bike paints into [surface], [EDGE] rows shorter at
 * each end than [target], and each frame is drawn into [target] in between.
 *
 * The screen paints at 60 Hz and the bike takes about 30 a second, so no more than about 30
 * a second go on; the last of a burst still goes, a little later.
 */
class BlackEdges(
    target: Surface,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit = {}
) {

    /** The height the screen for the bike gets. */
    val inner = height - 2 * EDGE

    private val thread = HandlerThread("moto-bordes").also { it.start() }
    private val handler = Handler(thread.looper)
    private val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var context: android.opengl.EGLContext
    private lateinit var window: android.opengl.EGLSurface
    private var program = 0
    private var texture = 0
    private lateinit var frames: SurfaceTexture
    private val matrix = FloatArray(16)
    @Volatile private var released = false

    /** The time stamped on the last frame that went to [target], in ns; only on [thread]. */
    private var drawnAt = 0L
    /** A frame was held back and [late] will draw it. */
    private var held = false
    private val late = Runnable {
        held = false
        // Stamped after the encoder's 30 a second, or it would drop the very frame that matters.
        if (!released) draw(maxOf(stamp(), drawnAt + LATE_STAMP_NS))
    }

    /** Where the screen for the bike paints. */
    lateinit var surface: Surface
        private set

    init {
        try { start(target) } catch (e: Throwable) {
            thread.quitSafely()
            throw e
        }
    }

    private fun start(target: Surface) {
        onThread {
            val version = IntArray(2)
            check(EGL14.eglInitialize(egl, version, 0, version, 1)) { "EGL no arranca" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                egl, intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
                ), 0, configs, 0, 1, count, 0
            )
            val config = configs[0].takeIf { count[0] > 0 } ?: error("EGL sin configuración para grabar")
            context = EGL14.eglCreateContext(
                egl, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            window = EGL14.eglCreateWindowSurface(egl, config, target, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(egl, window, window, context)) { "EGL no se deja usar" }

            program = program(VERTEX, FRAGMENT)
            texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            frames = SurfaceTexture(texture).apply {
                setDefaultBufferSize(width, inner)
                setOnFrameAvailableListener({ frame() }, handler)
            }
            surface = Surface(frames)
            // Black until the first frame comes.
            clear()
            EGL14.eglSwapBuffers(egl, window)
        }
    }

    /** A frame came: always taken off the screen's queue, so it never stalls; drawn at most 30 a second. */
    private fun frame() {
        if (released) return
        frames.updateTexImage()
        val made = stamp()
        // By the frames' own times, regular at 60 Hz: every other one is due.
        if (drawnAt == 0L || made - drawnAt >= GAP_NS) {
            handler.removeCallbacks(late)
            held = false
            draw(made)
        } else if (!held) {
            // If no other frame comes, this one still has to reach the bike. Later than the
            // next frame of a steady stream would come, so that one goes first and on time.
            held = true
            handler.postDelayed(late, GAP_MS + GAP_MS / 2)
        }
    }

    /** When the frame last taken from the screen was made; now if the screen did not say. */
    private fun stamp() = frames.timestamp.takeIf { it > 0 } ?: System.nanoTime()

    /** Draws the frame last taken from the screen, stamped [at] for the encoder. */
    private fun draw(at: Long) {
        drawnAt = at
        frames.getTransformMatrix(matrix)
        clear()
        GLES20.glViewport(0, EDGE, width, inner)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "matrix"), 1, false, matrix, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(egl, window, at)
        EGL14.eglSwapBuffers(egl, window)
    }

    private fun clear() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /** Once the screen for the bike has let go of [surface]. */
    fun release() {
        released = true
        try {
            onThread {
                handler.removeCallbacks(late)
                frames.setOnFrameAvailableListener(null)
                surface.release()
                frames.release()
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(egl, window)
                EGL14.eglDestroyContext(egl, context)
            }
        } catch (e: Throwable) {
            log("no se pudieron soltar los bordes negros: $e")
        }
        thread.quitSafely()
    }

    /**
     * Runs [work] where the EGL context lives and waits for it, passing on what it throws.
     * A stuck driver must not hold the caller for ever: after [WAIT_MS] it gives up.
     */
    private fun onThread(work: () -> Unit) {
        val done = CountDownLatch(1)
        var failed: Throwable? = null
        handler.post {
            try {
                work()
            } catch (e: Throwable) {
                failed = e
            } finally {
                done.countDown()
            }
        }
        check(done.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "EGL no contesta en $WAIT_MS ms" }
        failed?.let { throw it }
    }

    private companion object {
        /** Black rows at the top and at the bottom: more than the bike's scaling blends. */
        const val EDGE = 4
        /** Most time a frame waits for a newer one before it goes anyway. */
        const val GAP_MS = 33L
        /**
         * Shortest time between frames to the encoder: 30 a second, less the 2 ms the encoder's
         * own limit lets through early.
         */
        const val GAP_NS = 31_500_000L
        /** Far enough after the last frame that the encoder's limit never drops a late one. */
        const val LATE_STAMP_NS = 36_000_000L
        /** How long to wait for the EGL thread. */
        const val WAIT_MS = 2000L
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }

        const val VERTEX = """
            attribute vec2 position;
            uniform mat4 matrix;
            varying vec2 place;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                place = (matrix * vec4((position + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES picture;
            varying vec2 place;
            void main() {
                gl_FragColor = texture2D(picture, place);
            }
        """

        fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "sombreador: ${GLES20.glGetShaderInfoLog(it)}" }
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
            }
        }
    }
}
