package com.r4x.rxcontrolzone

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service — keeps the Python bridge alive even when app is in background.
 */
class BridgeService : Service() {

    companion object { private const val CHANNEL_ID = "rxcz_bridge" }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RX CONTROL ZONE")
            .setContentText("AI phone control active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "RX Bridge", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps AI bridge running in background" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
