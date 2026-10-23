package com.osmaicoach.collector

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitor em primeiro plano (notificação fixa): mantém o processo vivo mesmo com o app fora dos recentes, confere os
 * alarmes a cada minuto e entrega na hora o que ficou atrasado. É a camada local; a nuvem (Push) é a reserva.
 */
class KeepAliveService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Monitor ativo", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantém os avisos de treino, obras e jogos funcionando com o app fechado"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 7001, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("OSM Coach: avisos ativos")
            .setContentText("Treinos, obras e jogos")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7001, n)
        } catch (e: Exception) {
            Diag.lastError = "Monitor ativo: " + (e.message ?: e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        job = AppScope.scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { Notifier.reschedule(applicationContext) } catch (e: Exception) { Diag.lastError = "Avisos: " + (e.message ?: e.javaClass.simpleName) }
                delay(60_000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CH = "keepalive"

        fun enabled(ctx: Context): Boolean = Settings.get(ctx, "keepalive", "1") == "1"

        fun start(ctx: Context) {
            if (!enabled(ctx)) return
            try { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) {
                Diag.lastError = "Monitor ativo: " + (e.message ?: e.javaClass.simpleName)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }
}
