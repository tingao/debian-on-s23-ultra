package com.dsh.autoroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import rikka.shizuku.Shizuku

class RootService : Service() {

    companion object {
        const val ACTION_RUN = "com.dsh.autoroot.RUN"
        private const val CH_ID = "autoroot"
        private const val CH_NAME = "AutoRoot"
        private const val NOTIF_ID = 4711
        private const val PREFS = "autoroot"
        private const val KEY_AUTO = "auto_start"

        fun autoStartEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

        fun enableAutoStart(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()

        /**
         * How long to wait after a reboot before doing ANYTHING.
         *
         * The whole flow - exploit, late-load, lockdown, Debian - is automatic, so a
         * mistake in it would repeat on every boot with no chance to intervene. This
         * window is the escape hatch: open the app and turn autostart off, create
         * files/autoroot.pause, or simply kill the app.
         */
        const val SETTLE_SECONDS = 60

        /**
         * Process-wide, deliberately not a per-instance field.
         *
         * startForegroundService on a service that is already running only delivers
         * another onStartCommand, and a service Android has restarted gets a fresh
         * instance with fresh fields. With a per-instance flag, LOCKED_BOOT_COMPLETED,
         * BOOT_COMPLETED and MY_PACKAGE_REPLACED each started their own 60 second settle
         * and their own Shizuku poll inside the same minute - six starts logged between
         * 23:38:30 and 23:39:36 on the phone. One flag for the whole process stops that.
         */
        @Volatile private var runInFlight = false
    }

    /** Manual override that works even when the UI cannot be opened. */
    private fun isPaused() = java.io.File(filesDir, "autoroot.pause").exists()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Root restore + Debian server"
                    setShowBadge(false)
                }
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID) else Notification.Builder(this))
            .setContentTitle("AutoRoot")
            .setContentText("Restoring root and starting Debian…")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(RootService::class.java) {
            if (runInFlight) {
                Sh.log("a run is already in flight - ignoring this start request")
                return START_STICKY
            }
            runInFlight = true
        }
        // NOTE: deliberately NO long wake lock here. The flow may sit in the
        // thermal gate for up to 25 minutes, and a wake lock held across that
        // stops the phone deep-sleeping the whole time. RootFlow takes a short
        // one around the exploit, which is the only part that needs the CPU
        // pinned awake.
        Thread {
            try {
                Sh.init(applicationContext)
                Sh.log("--- service start ---")

                // --- manual override -------------------------------------------------
                // A settle delay alone is not enough: this service is START_STICKY,
                // so Android can restart it after it is killed. The pause file is the
                // real override, and the delay exists to give a window in which to
                // create it. Both are checked, so the flow can be stopped at any point
                // during the wait rather than only before it starts.
                if (isPaused()) {
                    Sh.log("PAUSED: files/autoroot.pause exists - touching nothing")
                    return@Thread
                }

                Sh.log("settling ${SETTLE_SECONDS}s before touching anything")
                Sh.log("  (stop it: turn off autostart in the app, create the pause file, or kill the app)")
                var aborted = false
                for (i in 1..SETTLE_SECONDS) {
                    Thread.sleep(1_000)
                    if (isPaused()) { aborted = true; break }
                    // also honour the UI toggle going off mid-wait, so flipping it
                    // during the settle window stops the flow instead of only
                    // affecting the NEXT boot
                    if (!autoStartEnabled(applicationContext)) { aborted = true; break }
                    if (i % 15 == 0) Sh.log("  settle ${i}s/${SETTLE_SECONDS}s")
                }
                if (aborted) {
                    Sh.log("aborted during settle - touching nothing")
                    return@Thread
                }
                Sh.log("settle done - starting the root flow")

                // Poll for Shizuku: it self-starts shortly after boot.
                var ready = false
                for (i in 0 until 60) {
                    if (Sh.shizukuReady()) { ready = true; break }
                    if (i % 4 == 0) Sh.log("waiting for Shizuku… (${i * 5}s)")
                    Thread.sleep(5_000)
                }
                if (!ready) {
                    Sh.log("Shizuku never came up; aborting")
                    return@Thread
                }
                Sh.log("Shizuku is up")

                // Shizuku permission is a one-time grant.
                var granted = false
                for (i in 0 until 12) {
                    granted = Sh.permissionGranted()
                    if (granted) break
                    Thread.sleep(5_000)
                }
                if (!granted) {
                    Sh.log("Shizuku permission not granted - open the app and tap 'Grant Shizuku permission'")
                    return@Thread
                }

                val ok = RootFlow.run(this)
                Sh.log(if (ok) "SUCCESS" else "FAILED")

                val nm = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.notify(
                        NOTIF_ID,
                        Notification.Builder(this, CH_ID)
                            .setContentTitle(if (ok) "Rooted" else "Root failed")
                            .setContentText(if (ok) "Lockdown applied, Debian server up on 1304" else "Check the app log")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .build()
                    )
                }
            } catch (t: Throwable) {
                Sh.log("service error: ${t.message}")
            } finally {
                RootFlow.releaseWake()
                runInFlight = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.start()

        return START_STICKY
    }
}
