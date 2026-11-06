package de.autoknigge.pdfreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Hält das Vorlesen am Laufen, wenn die App im Hintergrund ist oder der Bildschirm ausgeht.
 * Der Dienst zeigt eine Benachrichtigung mit „Pause/Weiter“ und „Stopp“. Das Vorlesen selbst
 * macht weiter der [Speaker] in der Activity; der Dienst hält nur den Prozess wach.
 */
class ReadService : Service() {

    companion object {
        private const val CHANNEL = "vorlesen"
        private const val ID = 4711
        private const val ACT_TOGGLE = "de.autoknigge.pdfreader.VORLESEN_TOGGLE"
        private const val ACT_STOP = "de.autoknigge.pdfreader.VORLESEN_STOP"

        @Volatile
        var instance: ReadService? = null

        /** Wird von der Activity gesetzt: Pause/Weiter und Stopp aus der Benachrichtigung. */
        var onToggle: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null

        /** Benachrichtigung zeigen oder aktualisieren; startet den Dienst, wenn er noch nicht läuft. */
        fun show(ctx: Context, playing: Boolean, text: String) {
            val running = instance
            if (running != null) {
                running.refresh(playing, text)
                return
            }
            val i = Intent(ctx, ReadService::class.java).putExtra("playing", playing).putExtra("text", text)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Start aus dem Hintergrund nicht erlaubt: dann bleibt es beim Vorlesen ohne Dienst
            }
        }

        fun hide(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, ReadService::class.java))
            } catch (ignored: Exception) {
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var lastPlaying = true
    private var lastText = "Vorlesen"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Vorlesen", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Zeigt, dass ein Dokument vorgelesen wird, und bietet Pause und Stopp."
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> main.post { onToggle?.invoke() }
            ACT_STOP -> main.post { onStop?.invoke() }
            else -> {
                lastPlaying = intent?.getBooleanExtra("playing", true) ?: true
                lastText = intent?.getStringExtra("text") ?: lastText
            }
        }
        startFg()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val n = build(lastPlaying, lastText)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
        setWake(lastPlaying)
    }

    fun refresh(playing: Boolean, text: String) {
        lastPlaying = playing
        lastText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID, build(playing, text))
        setWake(playing)
    }

    private fun setWake(on: Boolean) {
        try {
            if (on) {
                if (wake == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pdfreader:vorlesen")
                }
                if (wake?.isHeld == false) wake?.acquire(6 * 60 * 60 * 1000L)
            } else if (wake?.isHeld == true) {
                wake?.release()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun build(playing: Boolean, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, ReadService::class.java).setAction(ACT_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ReadService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("PDF-Reader")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Weiter", toggle
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stopp", stop)
            .build()
    }

    /** App wurde aus der Übersicht weggewischt: Vorlesen beenden. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        main.post { onStop?.invoke() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        setWake(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (instance === this) instance = null
        super.onDestroy()
    }
}
