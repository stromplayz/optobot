package com.optobot.native

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * OptoWakeWordService — foreground service that keeps the wake word
 * listener alive while the app is in the background.
 *
 * NOTE: This is a stub. The actual wake word detection runs via
 * OptoVoice.startWakeWordListener() which is called from the main
 * activity. This service just provides the foreground notification
 * so Android doesn't kill the app while listening.
 *
 * On a real device build with NDK + whisper.cpp, this service would
 * own the AudioRecord + Whisper inference loop.
 */
class OptoWakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "opto_wake_word"
        private const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OptoBot is listening")
            .setContentText("Say \"Hey Opto!\" to start a conversation")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Listener",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "OptoBot is listening for the wake word \"Hey Opto!\""
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
