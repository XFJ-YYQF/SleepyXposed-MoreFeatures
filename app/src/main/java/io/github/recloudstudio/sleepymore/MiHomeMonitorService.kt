package io.github.recloudstudio.sleepymore

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Hosts [MiHomeMonitor]. Plain (non-foreground) service — on most ROMs this will get killed by
 * battery optimization / Doze after a while. For reliable long-running polling, either:
 *  - turn this into a foreground service with a persistent notification, or
 *  - ask the user to disable battery optimization for this app (see RomDetector for existing
 *    ROM-specific helpers this project already has for similar asks), or
 *  - drive polling from AlarmManager/WorkManager instead of a HandlerThread loop.
 * Left as a plain service here to keep the scaffold small; harden per your reliability needs.
 */
class MiHomeMonitorService : Service() {
    private var monitor: MiHomeMonitor? = null

    override fun onCreate() {
        super.onCreate()
        monitor = MiHomeMonitor(applicationContext).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        monitor?.stop()
        monitor = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Starts the service if the saved config has Mi Home reporting enabled. Safe to call
         * repeatedly (e.g. from onCreate and from a boot receiver) — starting an already-running
         * service is a no-op. */
        fun startIfEnabled(context: Context) {
            Thread {
                    val config = runCatching { ConfigManager.loadConfig(context) }.getOrNull()
                    if (config?.miHomeEnabled == true) {
                        runCatching { context.startService(Intent(context, MiHomeMonitorService::class.java)) }
                    }
                }
                .start()
        }
    }
}
