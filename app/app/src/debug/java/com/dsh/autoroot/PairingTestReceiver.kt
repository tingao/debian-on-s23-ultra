package com.dsh.autoroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Test-only trigger for [PairingService].
 *
 * Why this exists: the pairing code box can only be exercised when the app has no
 * stored adb key AND Shizuku is not answering, which is the fresh-install state. On a
 * phone that is already set up there is no supported way to reach that branch -
 * [PairingService] is `exported="false"`, so `adb shell am start-foreground-service`
 * is refused with "Requires permission not exported from uid ...". That made the
 * notification path impossible to test after the fact.
 *
 * **This must never exist in a release build.** It is guarded twice, structurally:
 *
 *  1. The class lives only in `src/debug/java`, so it is not compiled into a release
 *     APK at all.
 *  2. The receiver element is declared only in `src/debug/AndroidManifest.xml`, so the
 *     release manifest has no such component. A runtime `FLAG_DEBUGGABLE` check was
 *     rejected on purpose: it still ships an exported component in the release
 *     manifest, and "inert" is not the same as "absent".
 *
 * The runtime check below is kept as a third, belt-and-braces layer.
 *
 * It starts the pairing notification and nothing else. It does not pair, root, reboot
 * or change any device state; the worst a stray trigger can do is post a notification
 * that says it is looking for the wireless debugging screen.
 */
class PairingTestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.dsh.autoroot.TEST_PAIRING"

        /**
         * Posts the two failure notices. That path is otherwise unreachable on demand, because
         * reaching it needs the exploit to fail and the exploit fails when it feels like it.
         *
         *     adb shell am broadcast -a com.dsh.autoroot.TEST_ALERT \
         *         -n com.dsh.autoroot/.PairingTestReceiver
         *     adb shell am broadcast -a com.dsh.autoroot.TEST_ALERT_RETRY \
         *         -n com.dsh.autoroot/.PairingTestReceiver
         */
        const val ACTION_ALERT = "com.dsh.autoroot.TEST_ALERT"
        const val ACTION_ALERT_RETRY = "com.dsh.autoroot.TEST_ALERT_RETRY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isDebuggable(context)) return
        when (intent.action) {
            ACTION -> try {
                ContextCompat.startForegroundService(context, PairingService.startIntent(context))
                Sh.log("test trigger: posting the pairing notification")
            } catch (t: Throwable) {
                // The whole point of this hook is to surface what was previously discarded.
                Sh.log("test trigger: could not start PairingService: $t")
            }
            ACTION_ALERT -> {
                RootAlerts.failure(context, retryable = false)
                Sh.log("test trigger: posted the restart-required failure notice")
            }
            ACTION_ALERT_RETRY -> {
                RootAlerts.failure(context, retryable = true)
                Sh.log("test trigger: posted the retryable failure notice")
            }
        }
    }

    private fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
