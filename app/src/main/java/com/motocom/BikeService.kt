package com.motocom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the bike's screen going with the phone locked or the app behind another one.
 *
 * The work is all in [Moto]; this only says it was asked for, so Android neither stops
 * the app nor lets the processor sleep while the bike is waiting for pictures. It lasts as
 * long as [Moto] is running, and its notification can stop it. Closing the app for good,
 * swiping it out of the recent ones, stops it too: locking the phone is how it goes on alone.
 */
class BikeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_DISCONNECT -> Moto.get(this).stop()
            else -> end()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            // Without it the bike still works while this app is open and in front.
            e.printStackTrace()
            stopSelf()
            return
        }
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Motocom::moto").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // a long day's ride, and not a minute more if forgotten
            }
        }
    }

    private fun end() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Moto.get(this).stop()
        end()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pantalla de la moto", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mantiene la pantalla de la moto con el móvil bloqueado"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, BikeService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pantalla de la moto")
            .setContentText("Sigue aunque bloquees el móvil")
            .setSmallIcon(R.drawable.ic_moto)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Desconectar", disconnect)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.motocom.START"
        private const val ACTION_DISCONNECT = "com.motocom.DISCONNECT"
        private const val CHANNEL_ID = "motocom_moto"
        private const val NOTIF_ID = 44

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BikeService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                // Asked from the background, Android can refuse; the bike works without it while the app is open.
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BikeService::class.java)) }
        }
    }
}
