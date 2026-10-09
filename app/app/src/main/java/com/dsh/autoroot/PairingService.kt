package com.dsh.autoroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Pairing driven from a notification, so the code can be typed while Settings is
 * showing it.
 *
 * This is Shizuku's solution to the same problem (`manager/adb/AdbPairingService.kt`).
 * The pairing code lives in Settings, in another app, so anything this app draws is
 * behind it. A notification with an inline reply floats over whatever is on screen,
 * which means the code can be entered without switching away from the screen
 * displaying it.
 *
 * The port comes from [PairingDiscovery], which is Android's own mDNS. The first
 * version of this shelled out to the bundled adb instead, and that was wrong: it
 * started an adb server, took tens of seconds, and often found nothing, so the reply
 * box never appeared. Shizuku uses NsdManager, and now so does this.
 */
class PairingService : Service() {

    companion object {
        const val CHANNEL = "pairing_code_v2"
        private const val NOTIF_ID = 4201
        /**
         * A group of its own, deliberately with no summary posted.
         *
         * The system was auto-grouping this with RootService's ongoing notification under
         * "AutoRoot", and a collapsed group costs an extra tap before the action row - the
         * one holding Reply - is reachable.
         */
        private const val GROUP = "com.dsh.autoroot.pairing"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_PORT = "pairing_port"
        private const val ACTION_START = "com.dsh.autoroot.PAIR_START"
        private const val ACTION_REPLY = "com.dsh.autoroot.PAIR_REPLY"

        fun startIntent(ctx: Context): Intent =
            Intent(ctx, PairingService::class.java).setAction(ACTION_START)

        private fun replyIntent(ctx: Context, port: Int): Intent =
            Intent(ctx, PairingService::class.java).setAction(ACTION_REPLY).putExtra(KEY_PORT, port)
    }

    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: PairingDiscovery? = null
    private var found = 0

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            // A new channel id deliberately. Channel settings are immutable once created,
            // so the old "pairing" channel keeps the silent, no-vibration, no-badge
            // settings it was made with, and reusing the id would inherit all of them.
            // That silence is part of why this notification could not be found.
            NotificationChannel(CHANNEL, "Pairing code", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks for the Shizuku pairing code"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim() ?: ""
                val port = intent.getIntExtra(KEY_PORT, 0)
                startForegroundSafely(working())
                if (code.isEmpty()) {
                    notify(plain("No code entered", "Tap Reply again and type the six digits."))
                    stopSelf()
                } else {
                    Thread { doPair(code, port) }.start()
                }
            }
            ACTION_START -> {
                // Offer the reply box straight away rather than waiting to find the
                // port first. The pairing port only advertises while the "Pair device
                // with pairing code" screen is open, so waiting for it meant the box
                // was absent exactly when it was wanted. The port is looked up when the
                // code is submitted instead, which is a moment later and just as good.
                found = 0
                startForegroundSafely(askForCode(0))
                beginDiscovery()
            }
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun beginDiscovery() {
        discovery?.stop()
        discovery = PairingDiscovery(
            this,
            onPort = { port ->
                found = port
                // Refresh the box so it can confirm the service is there. Re-posting the
                // same RemoteInput keeps whatever the user had already typed.
                handler.post { notify(askForCode(port)) }
            }
        ).also { it.start() }

        // Keep the box on screen; discovery stops on its own when the screen closes.
        handler.postDelayed({
            if (found == 0) {
                notify(askForCode(0))
            }
        }, 20_000)
    }

    private fun startForegroundSafely(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            // Log rather than swallow. A discarded throwable here is the reason the code
            // box never appeared: no NotificationRecord for this id means neither this
            // call nor the nm.notify fallback ever reached the notification manager, and
            // the only signal explaining why was being thrown away.
            Sh.log("pairing: startForeground(typed) failed: $t")
            try {
                startForeground(NOTIF_ID, n)
            } catch (t2: Throwable) {
                Sh.log("pairing: startForeground(plain) failed: $t2")
                try {
                    nm.notify(NOTIF_ID, n)
                    Sh.log("pairing: fell back to nm.notify")
                } catch (t3: Throwable) {
                    Sh.log("pairing: nm.notify failed too: $t3")
                }
            }
        }
    }

    private fun doPair(code: String, port: Int) {
        var p = port
        if (p <= 0) p = found
        if (p <= 0) {
            // Keep the box on screen: the app still needs the code, and the answer is
            // almost always that the pairing screen is not open yet.
            notify(askForCode(0, "Waiting for the pairing screen. "))
            return
        }
        val ok = try {
            ShizukuBootstrap.pairAt(this, code, p) { }
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            val started = try {
                ShizukuBootstrap.startServer(this) { }
            } catch (t: Throwable) {
                false
            }
            notify(
                plain(
                    if (started) "Shizuku is running" else "Paired",
                    if (started) "Nothing else to do. This is remembered, so it will not ask again."
                    else "Paired. Tap Set up everything in the app to start Shizuku."
                )
            )
            // Done with the code for good.
            stopSelf()
        } else {
            // Still needed, so keep the box rather than making the user find the button
            // again. A wrong or expired code is the common case, and the screen it came
            // from is usually still open.
            notify(askForCode(p, "That code was not accepted. Codes expire, so open the "))
        }
    }

    private fun notify(n: Notification) = nm.notify(NOTIF_ID, n)

    /** Somewhere to go when the notification body is tapped, rather than nothing. */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun base(title: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(0xFF2F6FE0.toInt())
            .setContentTitle(title)
            .setContentIntent(contentIntent())
            // PRIORITY_HIGH and REMINDER are what make it heads-up, so it floats over
            // Settings rather than sitting quietly in the shade waiting to be found.
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(false)
            // Its own group, and deliberately no summary.
            //
            // Without this the system auto-grouped this notification together with
            // RootService's ongoing one under "AutoRoot". A group is collapsed by default,
            // so the first tap on the notification expanded *the group* and the second was
            // needed to reach the action row holding Reply - the one control the whole
            // flow depends on. Measured on the phone: with the group present, opening the
            // shade and tapping once showed the body and no Reply button. A group of one
            // is not collapsed, so the action row is one tap away instead of two.
            .setGroup(GROUP)
    }

    /**
     * Ongoing only for the states where the service is still working. The terminal
     * "paired" notification must be swipeable, or the shade is left holding something
     * that cannot be dismissed.
     */
    private fun ongoing(b: Notification.Builder): Notification = b.setOngoing(true).build()

    private fun plain(title: String, text: String): Notification =
        base(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).build()

    private fun searching() = plain(
        "Looking for Wireless debugging",
        "Open Developer options, turn Wireless debugging on, then tap " +
            "\"Pair device with pairing code\". This will offer a box to type the code."
    )

    private fun working() = ongoing(base("Pairing").setContentText("Talking to this phone's adb."))

    private fun askForCode(port: Int, lead: String = ""): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("6 digit pairing code").build()
        val pi = PendingIntent.getForegroundService(
            this, 1, replyIntent(this, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // The action is called Reply because that is the word the body text uses. Three
        // different names for one gesture ("Enter pairing code" here, "Tap Reply" in the
        // text, "type into the notification" on screen) was the review's complaint.
        val action = Notification.Action.Builder(null, "Reply", pi)
            .addRemoteInput(remoteInput)
            .build()
        // Copy that fits one collapsed line, because the action row only fits on screen
        // when the text does not push it off. The full instruction lives in the expanded
        // BigTextStyle body, so nothing is lost by keeping the collapsed line short.
        val short = if (port > 0) "Tap Reply and type the code."
                    else "Tap Reply and type the six digits."
        val long = if (port > 0) {
            "Wireless debugging found. Tap Reply and type the 6 digit code. Nothing else " +
                "is needed - the key is remembered, so this is the last time a code is asked for."
        } else {
            "Tap Reply and type the six digits from the \"Pair device with pairing code\" " +
                "screen. You do not need to leave that screen to type it."
        }
        val text = lead + long
        return ongoing(
            base(if (port > 0) "Wireless debugging found" else "Enter the pairing code")
                .setContentText(lead + short)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .addAction(action)
        )
    }

    override fun onDestroy() {
        discovery?.stop()
        discovery = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
