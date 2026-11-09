package app.lightpanda.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keeps the Lightpanda server alive while the screen is off. */
class LightpandaService : Service() {
    companion object {
        private const val CHANNEL = "lightpanda_server"
        private const val ID = 1
        private const val ACTION_STOP = "app.lightpanda.droid.STOP"
        const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LightpandaService::class.java).putExtra(EXTRA_PORT, port),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LightpandaService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Runner.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, Runner.DEFAULT_PORT) ?: Runner.DEFAULT_PORT
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch {
            val error = Runner.startServer(applicationContext, port)
            if (error != null) {
                Runner.append(error)
                stopSelf()
                return@launch
            }
            nm.notify(ID, notification("Running on ws://127.0.0.1:$port"))
            // Stop the service when the server process ends.
            Runner.running.first { !it }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Runner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LightpandaService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Lightpanda")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
