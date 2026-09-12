package com.sellsan.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sellsan_followup"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Follow-ups SellSan", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val client = intent.getStringExtra("client") ?: "cliente"
        val text = intent.getStringExtra("text") ?: "Você tem um follow-up para fazer hoje."
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.sellsan.app.R.drawable.ic_sellsan)
            .setContentTitle("SellSan • $client")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
