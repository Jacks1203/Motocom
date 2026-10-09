package com.motocom.shell

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DirectAdbTest {
    @Test fun unprivilegedCallerCannotPublishWorker() {
        try {
            ShellProvider().call("attach", "invalid", Bundle())
            fail("An app uid must not publish a privileged worker")
        } catch (_: IllegalStateException) { }
    }

    @Test fun adbIdentitySurvivesReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Opening the manager reloads the same PKCS12 without corrupting it.
        AdbIdentity(context)
        val file = java.io.File(context.noBackupFilesDir, "adb-identity.p12")
        val before = file.readBytes()
        AdbIdentity(context)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun shellWorkerCreatesAndReleasesEncoderDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ShellLink.init(context) { android.util.Log.i("MotocomTest", it) }
        val apk = context.applicationInfo.sourceDir
        // UiAutomation uses Runtime.exec, not shell quoting. Keep its output open until exit.
        val command = "env CLASSPATH=$apk /system/bin/app_process /system/bin com.motocom.shell.ShellMain ${ShellLink.nonce} ${context.applicationInfo.uid / 100000}"
        val processOutput = instrumentation.uiAutomation.executeShellCommand(command)
        val remote = ShellLink.await(20)
        assertNotNull("Worker did not publish its Binder; inspect MotocomADB logcat", remote)
        val codec = MediaCodec.createEncoderByType("video/avc")
        var surface: android.view.Surface? = null
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 592, 816).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4000000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            val id = remote!!.show(surface, 592, 816, 240)
            assertTrue("Virtual display could not be created", id > 0)
            assertEquals(id, remote.displayId())
            remote.detachSurface()
            remote.release()
            assertEquals(-1, remote.displayId())
        } finally {
            runCatching { remote?.release() }
            runCatching { codec.stop() }
            codec.release()
            surface?.release()
            val dead = CountDownLatch(1)
            remote?.asBinder()?.linkToDeath({ dead.countDown() }, 0)
            runCatching { remote?.destroy() }
            assertTrue("Worker did not exit", dead.await(5, TimeUnit.SECONDS))
            processOutput.close()
        }
    }
}
