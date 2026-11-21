package com.example.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class CallForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "talkback_active_call"
        const val NOTIFICATION_ID = 4102
        const val ACTION_START_CALL = "com.example.ACTION_START_CALL"
        const val ACTION_START_SCREEN_SHARE = "com.example.ACTION_START_SCREEN_SHARE"
        const val ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE"
        const val EXTRA_CALL_TITLE = "extra_call_title"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        var activeMediaProjection: MediaProjection? = null
            private set

        fun startForCall(context: Context, title: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CALL_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startForScreenShare(
            context: Context,
            title: String,
            resultCode: Int,
            data: Intent
        ) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_SCREEN_SHARE
                putExtra(EXTRA_CALL_TITLE, title)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            activeMediaProjection?.stop()
            activeMediaProjection = null
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val title = intent?.getStringExtra(EXTRA_CALL_TITLE) ?: "Chamada em andamento"
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                activeMediaProjection?.stop()
                activeMediaProjection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SCREEN_SHARE -> {
                val notification = buildNotification(
                    title = title,
                    subtitle = "Compartilhando tela no Talkback"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode == Activity.RESULT_OK && projectionData != null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    activeMediaProjection?.stop()
                    activeMediaProjection = mpm.getMediaProjection(resultCode, projectionData)
                }
            }
            else -> {
                val notification = buildNotification(
                    title = title,
                    subtitle = "Chamada de voz/vídeo ativa"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Chamadas e Compartilhamento de Tela",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        activeMediaProjection?.stop()
        activeMediaProjection = null
        super.onDestroy()
    }
}
