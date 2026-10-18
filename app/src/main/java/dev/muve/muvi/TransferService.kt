package dev.muve.muvi

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.muve.muvi.data.Transfer
import dev.muve.muvi.data.humanSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Mantiene viva la app mientras sube o baja videos (pueden ser varios GB): Android no la cierra
 * aunque salgas de la app o se apague la pantalla. Se detiene sola cuando no queda nada en curso.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var watching = false
    private var lastNotify = 0L
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Subidas y descargas", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build("Preparando…", null, 0, 0), type)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "muvi:transfer")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val repo = (application as MuviApp).repo
        if (intent?.action == ACTION_CANCEL) {
            repo.cancelUpload()
            repo.cancelDownload()
        }
        if (!watching) {
            watching = true
            scope.launch {
                combine(repo.upload, repo.download) { u, d -> u to d }.collect { (u, d) ->
                    val up = u?.takeIf { it.active }
                    val down = d?.takeIf { it.active }
                    if (up == null && down == null) {
                        stopSelfResult(lastStartId) // si entretanto llego otra tanda, no se detiene
                    } else {
                        show(up, down)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: los servicios de transferencia tienen tope de horas al día. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val repo = (application as MuviApp).repo
        repo.cancelUpload()
        repo.cancelDownload()
        stopSelf()
    }

    private fun show(up: Transfer?, down: Transfer?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1000) return // el avance llega muchas veces por segundo
        lastNotify = now
        val main = up ?: down!!
        val title = buildString {
            append(if (main.upload) "Subiendo" else "Descargando")
            append(" ${main.index} de ${main.total}")
            if (up != null && down != null) append(" · también descargando")
        }
        val text = main.name + if (main.size > 0) " · ${humanSize(main.bytes)} de ${humanSize(main.size)}" else ""
        val pct = if (main.size > 0) (main.bytes * 100 / main.size).toInt().coerceIn(0, 100) else 0
        getSystemService(NotificationManager::class.java).notify(ID, build(title, text, pct, if (main.size > 0) 100 else 0))
    }

    private fun build(title: String, text: String?, progress: Int, max: Int): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancelar", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val ID = 7
        private const val ACTION_CANCEL = "dev.muve.muvi.CANCEL"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
            } catch (e: Exception) {
                // sin permiso para arrancarlo desde segundo plano: la transferencia sigue mientras la app esté abierta
            }
        }
    }
}
