package com.dsh.autoroot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything needed to get a stock phone to a running Debian server, and a way to
 * say precisely what is still missing.
 *
 * The order matters and is not negotiable:
 *
 *   1. Shizuku installed   - the app cannot root the phone without a shell context
 *   2. Shizuku running     - it survives reboots once it holds WRITE_SECURE_SETTINGS
 *   3. Shizuku permission  - the user has to allow this app
 *   4. root                - the exploit, then ksu-helper --late-load
 *   5. Debian rootfs       - nothing to serve SSH from otherwise
 *   6. server up           - sshd on 1304
 *
 * Steps 1 to 3 are the only ones this app cannot do alone: installing another app
 * needs a tap, and starting Shizuku needs one adb command. Everything is reported
 * rather than assumed, so a failure says which step and why.
 */
object Setup {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /**
     * A plain Debian 13 (trixie) arm64 rootfs, published by the Linux Containers
     * project. Roughly 86 MB compressed.
     *
     * It is a dated build, so it will eventually be pruned from the server. When
     * that happens, list https://images.linuxcontainers.org/images/debian/trixie/arm64/default/
     * and drop the newest date in here.
     */
    const val ROOTFS_URL =
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261007_05:24/rootfs.tar.xz"

    const val ROOTFS_NAME = "debian13-arm64.tar.xz"
    private const val CHROOT = "/data/local/chroot/debian"

    enum class State { OK, NEEDED, BLOCKED, UNKNOWN }

    data class Step(
        val title: String,
        val state: State,
        val detail: String,
        /** True when this app can perform the step itself with a tap. */
        val autoFixable: Boolean = false,
        /** Shown when the step needs something only the user can do. */
        val manual: String? = null
    )

    fun shizukuInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun shizukuRunning(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuAllowed(): Boolean {
        return try {
            if (!shizukuRunning()) false
            else rikka.shizuku.Shizuku.checkSelfPermission() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** Where the downloaded rootfs lives before it is unpacked. */
    fun rootfsFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ROOTFS_NAME)

    /**
     * Read the state of each step. All shell checks go through the Shizuku user
     * service, so anything before step 3 will simply read as unknown rather than
     * throwing.
     */
    fun steps(ctx: Context): List<Step> {
        val out = mutableListOf<Step>()

        val installed = shizukuInstalled(ctx)
        out += Step(
            "Shizuku installed", if (installed) State.OK else State.NEEDED,
            if (installed) "yes" else "not installed",
            autoFixable = !installed,
            manual = if (installed) null else "Shizuku is bundled with this app. " +
                "Tap Install and accept the prompt."
        )

        val running = shizukuRunning()
        out += Step(
            "Shizuku running", if (running) State.OK else State.NEEDED,
            if (running) "binder alive" else "not running",
            manual = if (running) null else
                "Start it once from a computer with USB debugging:\n" +
                "adb shell sh /storage/emulated/0/Android/data/$SHIZUKU_PKG/start.sh\n" +
                "and let it start itself at boot:\n" +
                "adb shell pm grant $SHIZUKU_PKG android.permission.WRITE_SECURE_SETTINGS"
        )

        val allowed = shizukuAllowed()
        out += Step(
            "Shizuku permission for this app",
            if (allowed) State.OK else State.NEEDED,
            if (allowed) "granted" else "not granted",
            manual = if (allowed) null else
                "Open Shizuku and allow this app, or add its uid to\n" +
                "/data/user_de/0/com.android.shell/shizuku.json and restart Shizuku."
        )

        // The pairing code can only be entered in a notification, so with notifications
        // off the whole no-computer route is dead. The permission is requested once in
        // onCreate and, after two denials, Android stops asking, so this has to be
        // checked rather than assumed. Only shown when it is actually a problem.
        val notifyOk = try {
            androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        } catch (t: Throwable) {
            true
        }
        if (!notifyOk) {
            out += Step(
                "Notifications allowed",
                State.BLOCKED,
                "off - the pairing code would have nowhere to be typed",
                manual = "Android > Apps > AutoRoot > Notifications, turn them on. " +
                    "Without this, starting Shizuku with no computer cannot work."
            )
        }

        // Sh.exec runs in the Shizuku user service, which is uid 2000 (shell), so
        // asking it for `id -u` reports 2000 and not the phone's root state. Root
        // only shows up through an explicit `su`.
        val uid = if (allowed) Sh.exec("su -c id -u 2>/dev/null").trim() else ""
        val rooted = uid == "0"
        out += Step(
            "root", if (rooted) State.OK else State.NEEDED,
            if (rooted) "uid 0" else if (allowed) "not rooted yet" else "unknown until Shizuku works",
            autoFixable = allowed && !rooted,
            manual = if (allowed && !rooted) "Tap Set up to run the exploit and late-load KernelSU." else null
        )

        // Accept either bash path, and fall back to the os-release file. The
        // single "test -x .../usr/bin/bash" this replaced reported a perfectly
        // good rootfs as missing on a device where it plainly exists, and a false
        // negative here is expensive: it makes the app offer to download 86 MB
        // the user does not need.
        val probe = if (allowed) Sh.exec(
            "if [ -x $CHROOT/usr/bin/bash ] || [ -x $CHROOT/bin/bash ]; then echo ROOTFS_OK; " +
            "elif [ -f $CHROOT/etc/os-release ]; then echo ROOTFS_OK; else echo ROOTFS_NO; fi"
        ).trim() else ""
        val hasRootfs = probe.contains("ROOTFS_OK")
        val dl = rootfsFile(ctx)
        out += Step(
            "Debian rootfs",
            if (hasRootfs) State.OK else State.NEEDED,
            when {
                hasRootfs -> "unpacked at $CHROOT"
                dl.exists() -> "downloaded (${dl.length() / 1048576} MB), not unpacked yet"
                else -> "missing, and not downloaded"
            },
            autoFixable = allowed && !hasRootfs,
            manual = if (!hasRootfs) "Downloads ${ROOTFS_URL.substringAfterLast('/')} (about 86 MB)." else null
        )

        // Counting listeners rather than testing for exactly one: a dual-stack sshd
        // reports two, and testing == 1 made the app announce the server was down while
        // it was plainly listening.
        val sshd = if (allowed) {
            Sh.exec("(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -c ':1304'")
                .trim().toIntOrNull() ?: 0
        } else 0
        val up = sshd >= 1
        out += Step(
            "Debian server",
            if (up) State.OK else if (hasRootfs) State.NEEDED else State.BLOCKED,
            if (up) "sshd listening on 1304" else if (hasRootfs) "not running" else "needs a rootfs first",
            autoFixable = hasRootfs && !up
        )

        return out
    }

    /**
     * Fire the system installer for the Shizuku APK we ship in assets. The user
     * has to accept the prompt; there is no way around that without root, and
     * without Shizuku there is no root.
     */
    fun installShizuku(ctx: Context) {
        val out = File(ctx.cacheDir, "shizuku.apk")
        ctx.assets.open("shizuku.apk").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    /**
     * Download the rootfs. Runs on the caller's thread, so call it from a worker.
     * Returns null on success, or a message explaining the failure.
     */
    fun downloadRootfs(ctx: Context, onProgress: (Int) -> Unit): String? {
        val dest = rootfsFile(ctx)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ROOTFS_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "autoroot")
            }
            val code = conn.responseCode
            if (code !in 200..299) return "HTTP $code from the rootfs mirror"
            val total = conn.contentLength
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct && pct % 2 == 0) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && part.length() < total) return "download stopped early (${part.length()} of $total bytes)"
            if (!part.renameTo(dest)) { part.copyTo(dest, true); part.delete() }
            onProgress(100)
            return null
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
            try { if (part.exists() && part.length() == 0L) part.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Unpack the rootfs and configure sshd, by running the bundled bootstrap as
     * root. Slow (a few minutes of extraction), so run it on a worker.
     */
    fun bootstrap(ctx: Context): String {
        val f = rootfsFile(ctx)
        if (!f.exists()) return "no rootfs downloaded yet"
        // Make sure the payloads are staged before the script needs them.
        val staged = Sh.exec("test -f /data/local/tmp/00-bootstrap.sh && echo yes || echo no").trim()
        if (staged != "yes") return "00-bootstrap.sh is not staged in /data/local/tmp yet"
        val out = Sh.exec("su -c 'sh /data/local/tmp/00-bootstrap.sh \"${f.absolutePath}\" 2>&1 | tail -25'")
        val ok = Sh.exec("test -x $CHROOT/usr/bin/bash && echo yes || echo no").trim() == "yes"
        return if (ok) "unpacked" else (out.ifBlank { "bootstrap failed, see /data/local/tmp/bootstrap.log" })
    }

    fun deviceSummary(): String = try {
        val model = Build.MODEL
        val build = Build.DISPLAY
        "$model / $build / android ${Build.VERSION.RELEASE}"
    } catch (_: Throwable) { "unknown" }
}
