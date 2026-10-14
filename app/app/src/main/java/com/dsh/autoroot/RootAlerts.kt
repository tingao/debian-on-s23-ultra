package com.dsh.autoroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Tells the user when root did not come back, and offers the one action that is safe.
 *
 * Kept apart from [RootService] so the debug test hook can post these without running the
 * whole flow. The failure path is otherwise unreachable on demand: it needs the exploit to
 * fail, which is exactly the thing nobody can schedule.
 *
 * The two failures are not interchangeable, and the difference decides the button. Once the
 * payload has mutated the kernel it refuses to try again, because a second attempt against a
 * changed kernel could corrupt it; a manual late-load against that state answers
 * "su: connect daemon: Permission denied", so the phone has to restart. Offering Retry there
 * would point at the action the exploit deliberately will not take. Failures before the
 * kernel is touched are safe to repeat, and those do get Retry.
 */
object RootAlerts {

    /**
     * A channel of its own, and therefore a new id.
     *
     * RootService's progress channel is created IMPORTANCE_LOW and a channel cannot be raised
     * after creation. The old code posted its failure notice there, so the only sign that a
     * phone had failed to root was a silent notification on the same channel as the progress
     * one, with no action attached.
     */
    private const val CH_ID = "root_alert_v1"
    private const val ID = 4712

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "AutoRoot failures", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Says when root did not come back, and what to do about it"
                setShowBadge(true)
            }
        )
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /** Post the failure notice. [retryable] decides whether a Retry button is honest. */
    fun failure(ctx: Context, retryable: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = Notification.Builder(ctx, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(
                if (retryable) "Root did not come back" else "Root failed, and the phone needs a restart"
            )
            .setContentText(
                if (retryable) "The exploit never ran. Retrying is safe."
                else "The exploit changed kernel state and will not run again until a restart."
            )
            .setStyle(
                Notification.BigTextStyle().bigText(
                    if (retryable)
                        "The exploit did not get as far as running, which usually means the " +
                        "phone was busy or too warm at that moment. Nothing was changed, so " +
                        "tapping Retry is safe and no restart is needed."
                    else
                        "The exploit reached the point of changing kernel state, then the write " +
                        "step failed. It will not run again until the phone restarts, because a " +
                        "second attempt against a changed kernel could corrupt it. Restarting is " +
                        "the whole fix: after that the app takes over on its own and there is " +
                        "nothing to press."
                )
            )
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(Notification.PRIORITY_HIGH)

        if (retryable) {
            val retry = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, RootService::class.java).setAction(RootService.ACTION_RETRY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, "Retry", retry).build())
            b.addAction(Notification.Action.Builder(null, "Open app", open).build())
        } else {
            // No Retry here on purpose. See the class comment.
            b.addAction(Notification.Action.Builder(null, "How to fix", open).build())
        }

        nm.notify(ID, b.build())
    }
}
