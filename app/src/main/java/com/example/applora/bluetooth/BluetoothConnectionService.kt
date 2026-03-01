package com.example.applora.bluetooth

import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.applora.MainActivity
import com.example.applora.NotificationHelper

/**
 * Service Android foreground qui maintient la connexion Bluetooth active,
 * même lorsque l'application est en arrière-plan.
 *
 * Architecture inspirée de [BluetoothConnectionService] du projet bluetoothchat :
 * — Le service expose un [Binder] pour que les activités puissent y accéder.
 * — Toute la logique de connexion est déléguée au [ConnectionController].
 * — La notification foreground informe l'utilisateur de l'état courant.
 */
class BluetoothConnectionService : Service() {

    private val binder = ConnectionBinder()

    val controller: ConnectionController by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        ConnectionController(manager.adapter)
    }

    inner class ConnectionBinder : Binder() {
        fun getService(): BluetoothConnectionService = this@BluetoothConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        isRunning = true

        controller.onForegroundMessage = { text -> updateNotification(text) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received")
            controller.stop()
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        showForegroundNotification(getString(com.example.applora.R.string.listening))
        return START_STICKY
    }

    fun updateNotification(text: String) {
        showForegroundNotification(text)
    }

    private fun showForegroundNotification(text: String) {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_CONNECTION_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(com.example.applora.R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        controller.stop()
        isRunning = false
    }

    companion object {
        private const val TAG = "BTConnectionService"
        private const val FOREGROUND_ID = 101
        const val ACTION_STOP = "com.example.applora.bluetooth.STOP"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BluetoothConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BluetoothConnectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun bind(context: Context, connection: android.content.ServiceConnection) {
            val intent = Intent(context, BluetoothConnectionService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
}
