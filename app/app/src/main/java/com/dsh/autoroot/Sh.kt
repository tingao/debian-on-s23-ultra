package com.dsh.autoroot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes commands as the **shell** user (uid 2000) through a Shizuku
 * UserService.
 *
 * Why this indirection: the CVE-2026-43499 exploit must write to tracing_on
 * under sys/kernel/tracing and create files under data/local/tmp. An app
 * SELinux domain may do neither (measured: even *reading* tracefs is denied),
 * so no app can run the exploit in its own context. Shizuku's user service is
 * the supported way to obtain the shell context that adb would otherwise give.
 */
object Sh {

    private const val TAG = "AutoRoot"
    const val TMP = "/data/local/tmp"

    /** Guards every read and write of [logFile] across all threads in this process. */
    private val LOG_LOCK = Any()

    /** Had reached ~300MB with nothing rotating it. Trim well below that. */
    private const val MAX_LOG_BYTES = 512L * 1024
    private const val KEEP_CHARS = 120_000

    /**
     * NOTE: the log must live in the app's OWN files dir. The app cannot write
     * /data/local/tmp (that is precisely why the exploit needs shell context),
     * so logging there silently failed.
     */
    private val logFile: File
        get() = File(appCtx?.filesDir ?: File("/data/local/tmp"), "autoroot.log")

    @Volatile private var svc: IUserService? = null
    @Volatile private var appCtx: Context? = null
    private var bound = false
    private val latch = CountDownLatch(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = IUserService.Stub.asInterface(binder)
            val uid = try { svc?.uid() } catch (t: Throwable) { -1 }
            Log.i(TAG, "user service connected, uid=$uid")
            try { logFile.appendText("user service connected uid=$uid\n") } catch (_: Throwable) {}
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            Log.w(TAG, "user service disconnected")
        }
    }

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    /**
     * One writer at a time.
     *
     * This file is the only surviving record of a run that failed, and it was being
     * appended to from the service thread, the boot receiver and the UI thread at once
     * with nothing serialising them. The result on the phone was interleaved and
     * truncated lines - a message reading "23:39:26 the app)", which is the middle of a
     * longer sentence with its beginning lost. Losing the beginning of the only
     * diagnostic record is worse than the corruption being cosmetic.
     *
     * The lock is on the class, so every thread in this process shares it. A second
     * process (the user service) keeps its own copy, which is fine: those lines land in
     * their own log, not this one.
     */
    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            "$ts $msg\n"
        } catch (_: Throwable) {
            "$msg\n"
        }
        synchronized(LOG_LOCK) {
            try {
                // Trim before the file becomes unmanageable. It had reached ~300MB with
                // nothing ever rotating it, which matters because this is meant to be
                // readable on a phone.
                if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                    val keep = logFile.readText().takeLast(KEEP_CHARS)
                    logFile.writeText("[log trimmed]\n" + keep)
                }
                java.io.FileOutputStream(logFile, true).bufferedWriter().use { it.write(line) }
            } catch (_: Throwable) {
            }
        }
    }

    fun clearLog() = synchronized(LOG_LOCK) {
        try { logFile.writeText("") } catch (_: Throwable) {}
    }

    fun readLog(): String = synchronized(LOG_LOCK) {
        try { logFile.readText() } catch (_: Throwable) { "" }
    }

    fun shizukuReady(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    fun permissionGranted(): Boolean = try {
        shizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    private fun args(ctx: Context): Shizuku.UserServiceArgs {
        // Must match the APK's debuggable flag or Shizuku rejects the service.
        val dbg = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Shizuku.UserServiceArgs(ComponentName(ctx.packageName, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(dbg)
            .version(1)
    }

    /** Bind the shell-context user service. Safe to call repeatedly. */
    fun ensureService(timeoutMs: Long = 30_000): Boolean {
        svc?.let { return true }
        val ctx = appCtx ?: return false
        if (!permissionGranted()) return false
        try {
            if (!bound) {
                bound = true
                Shizuku.bindUserService(args(ctx), conn)
            }
        } catch (t: Throwable) {
            log("bindUserService failed: ${t.message}")
            return false
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && svc != null
    }

    /** Run a command as shell and return merged output. */
    fun exec(cmd: String): String {
        val s = svc ?: return "ERR: user service not bound"
        return try {
            s.run(cmd) ?: ""
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    /** Fire-and-forget (the command itself backgrounds the work). */
    fun execBg(cmd: String) = exec(cmd)

    fun isRooted(): Boolean = exec("su -c id 2>&1").contains("uid=0")

    /** uid of the bound user service (2000 = shell, 0 = root). */
    fun serviceUid(): Int = try { svc?.uid() ?: -1 } catch (t: Throwable) { -1 }

    /**
     * Run a command INSIDE the Debian chroot.
     * chroot needs CAP_SYS_CHROOT, so it must go through su. The command is
     * base64-encoded to avoid any shell-quoting problems.
     */
    fun runInDebian(cmd: String): String {
        val b64 = android.util.Base64.encodeToString(cmd.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        exec("echo '$b64' | base64 -d > $TMP/dexec.cmd 2>/dev/null")
        return exec(
            "su -c 'cp $TMP/dexec.cmd /data/local/chroot/debian/root/.dexec' 2>/dev/null; " +
            "su -c '/data/local/tmp/debian -c \"sh /root/.dexec\"' 2>&1"
        ).trim()
    }

    /** Unzip our bundled payloads into /data/local/tmp via the shell context. */
    fun extractPayloads(): String {
        val s = svc ?: return "ERR: user service not bound"
        val ctx = appCtx ?: return "ERR: no context"
        return try {
            val r = s.extractAll(ctx.packageCodePath) ?: ""
            log("extract:\n$r")
            r
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }
}
