package com.example.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TalkbackFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_MESSAGES = "talkback_messages"
        const val CHANNEL_CALLS = "talkback_calls"
        const val CHANNEL_SOCIAL = "talkback_social"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channels = listOf(
                    NotificationChannel(CHANNEL_MESSAGES, "Mensagens e Menções", NotificationManager.IMPORTANCE_DEFAULT),
                    NotificationChannel(CHANNEL_CALLS, "Chamadas Recebidas", NotificationManager.IMPORTANCE_HIGH),
                    NotificationChannel(CHANNEL_SOCIAL, "Amizades e Convites", NotificationManager.IMPORTANCE_DEFAULT)
                )
                channels.forEach { nm.createNotificationChannel(it) }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = Firebase.auth.currentUser?.uid ?: return
        try {
            val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
            db.collection("users").document(uid).update(
                mapOf(
                    "fcmToken" to token.take(512),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        } catch (e: Exception) {
            Log.w("TalkbackFCM", "Unable to sync FCM token: ${e.message}")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        createChannels(applicationContext)

        val category = message.data["category"] ?: "message"
        val channelId = when (category) {
            "call" -> CHANNEL_CALLS
            "friend_request", "invite" -> CHANNEL_SOCIAL
            else -> CHANNEL_MESSAGES
        }

        val title = message.notification?.title ?: message.data["title"] ?: "Talkback"
        val body = message.notification?.body ?: message.data["body"] ?: "Nova atualização"

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(
                if (category == "call") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
