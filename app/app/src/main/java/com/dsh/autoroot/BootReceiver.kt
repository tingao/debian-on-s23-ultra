package com.dsh.autoroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fires after reboot and drives the whole flow with no user interaction.
 *
 * Shizuku self-starts at boot (its BootCompleteReceiver runs now that
 * WRITE_SECURE_SETTINGS is granted, and its adb key "shizuku" is already in
 * /data/misc/adb/adb_keys), so by the time this runs the shell context exists.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED &&
            a != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            a != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Sh.init(context)

        if (!RootService.autoStartEnabled(context)) {
            Sh.log("boot: auto-start disabled, skipping")
            return
        }
        Sh.log("boot received ($a) - scheduling root restore")

        // Shizuku needs a moment after boot; the service polls for it.
        val i = Intent(context, RootService::class.java).setAction(RootService.ACTION_RUN)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }
}
