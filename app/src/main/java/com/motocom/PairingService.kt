package com.motocom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.motocom.shell.ShellLink
import io.github.muntashirakon.adb.android.AdbMdns

/**
 * Pairing without split screen: while Android's pairing dialog is open, its port is found by
 * mDNS and the code is asked for in a notification, which can be answered over Settings. In
 * the foreground so that a phone short of memory does not close the app meanwhile.
 */
class PairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var mdns: AdbMdns? = null
    /** The pairing port of the dialog open now, or -1 while there is none. */
    private var port = -1
    /** The last one mDNS gave, kept while pairing too: a new dialog may open meanwhile. */
    private var latest = -1
    private var pairing = false
    /** Changes on every finish, so a pairing that ends afterwards leaves the notice alone. */
    private var session = 0
    /** What went wrong last, kept to show once the dialog has closed. */
    private var problem: String? = null
    private val giveUp = object : Runnable {
        override fun run() {
            // A code did come: give the pairing under way the time it needs.
            if (pairing) main.postDelayed(this, 60_000L) else finish(TIMEOUT)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> onCode(intent)
            ACTION_STOP -> finish(null)
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DONE_ID)
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, waiting(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "No se pudo mostrar el aviso para el código. Empareja a mano, con pantalla dividida.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (mdns == null) {
            mdns = AdbMdns(applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, found -> main.post { onPort(found) } }
                .also { it.start() }
        }
        searching.value = true
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, TIME_LIMIT)
    }

    private fun onPort(found: Int) {
        if (mdns == null) return
        latest = if (found in 1..65535) found else -1
        if (pairing || latest == port) return
        port = latest
        if (port > 0) {
            problem = null
            show(notice("Escribe aquí el código de 6 cifras que muestra Android. No cierres su diálogo.", ask = true, alert = true))
        } else {
            show(waiting())
        }
    }

    private fun onCode(intent: Intent) {
        if (pairing) return
        if (mdns == null) {
            // The app was closed meanwhile and Android started this again just for the answer.
            show(notice("Motocom se cerró mientras esperaba. Vuelve a la app y pulsa Emparejar.", ask = false, done = true))
            stopSelf()
            return
        }
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.filter(Char::isDigit)?.toString().orEmpty()
        if (port <= 0) return show(waiting())
        if (code.length != 6) return show(notice("El código tiene 6 cifras. Escríbelo otra vez.", ask = true))
        pairing = true
        show(notice("Emparejando…", ask = false, cancel = false))
        val used = port
        val mine = session
        val accepted = ShellLink.pairAndStart(used, code) { text ->
            if (mine != session) return@pairAndStart
            pairing = false
            when {
                ShellLink.ready -> finish("ADB listo. Ya puedes volver a Motocom.")
                text.startsWith("Error") -> {
                    // The library's reasons are in English; the app's log keeps them.
                    problem = "No se pudo emparejar: el código no era el correcto o el diálogo se cerró."
                    // The dialog has usually closed by now; a new one gives a new port.
                    port = -1
                    if (latest > 0 && latest != used) onPort(latest) else show(waiting())
                }
                else -> finish(text)
            }
        }
        if (!accepted) {
            pairing = false
            show(notice("Motocom está ocupada. Espera un momento y vuelve a escribir el código.", ask = true))
        }
    }

    /** Leaves [text] behind as an ordinary notice, or nothing, and stops. */
    private fun finish(text: String?) {
        session++
        pairing = false
        main.removeCallbacks(giveUp)
        stopSearch()
        searching.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Under an id of its own: HyperOS posts the foreground one again after letting it go,
        // over whatever had replaced it.
        if (text != null) show(notice(text, ask = false, done = true))
        stopSelf()
    }

    override fun onDestroy() {
        session++
        main.removeCallbacks(giveUp)
        stopSearch()
        searching.value = false
        super.onDestroy()
    }

    /**
     * AdbMdns only stops a search Android has already confirmed as started, so stopped too
     * early it would go on for good: asked once more a little later.
     */
    private fun stopSearch() {
        val search = mdns ?: return
        mdns = null
        search.stop()
        main.postDelayed({ search.stop() }, 2_000L)
    }

    private fun waiting(): Notification {
        val ask = "En Depuración inalámbrica, toca «Vincular dispositivo con código» (en Xiaomi, «Emparejar dispositivo con código de sincronización»)."
        return notice(problem?.let { "$it\n$ask" } ?: ask, ask = false)
    }

    private fun show(notification: Notification) {
        val id = if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) NOTIF_ID else DONE_ID
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                // High, so the question drops down over Settings by itself.
                NotificationChannel(CHANNEL_ID, "Emparejar ADB", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pide el código de emparejamiento sin salir de Ajustes"
                    setSound(null, null)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notice(text: String, ask: Boolean, alert: Boolean = false, done: Boolean = false, cancel: Boolean = true): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (done) "Motocom" else "Emparejar Motocom")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_moto)
            .setOnlyAlertOnce(!alert)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Only at the end: opening the app before would leave Settings and its dialog.
        if (done) {
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return builder.setContentIntent(open).setAutoCancel(true).build()
        }
        builder.setOngoing(true)
        if (ask) {
            // Mutable, or Android could not fill in what was typed.
            val send = PendingIntent.getService(
                this, 1,
                Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Código de 6 cifras").build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Escribir código", send)
                    .addRemoteInput(input).setAllowGeneratedReplies(false).build()
            )
        }
        if (!cancel) return builder.build()
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder.addAction(0, "Cancelar", stop).build()
    }

    companion object {
        private const val ACTION_CODE = "com.motocom.PAIR_CODE"
        private const val ACTION_STOP = "com.motocom.PAIR_STOP"
        private const val KEY_CODE = "codigo"
        private const val CHANNEL_ID = "motocom_emparejar"
        private const val NOTIF_ID = 45
        private const val DONE_ID = 46
        private const val TIME_LIMIT = 10 * 60_000L
        private const val TIMEOUT = "No llegó ningún código en 10 minutos. Vuelve a Motocom y pulsa Emparejar otra vez."

        /** True while a notice is waiting for the dialog or the code. */
        val searching = mutableStateOf(false)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, PairingService::class.java))

        /** False when the user has silenced just this channel, so no code could be typed. */
        fun canAsk(context: Context): Boolean {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun stop(context: Context) {
            if (searching.value) context.startService(Intent(context, PairingService::class.java).setAction(ACTION_STOP))
        }
    }
}
