package com.dsh.autoroot

import android.content.Context

/**
 * The exact sequence proven to work over adb:
 *
 *   exploit (stability-launcher) -> wait for "Root achieved"
 *   -> ./ksu-helper --late-load    <-- the step that installs su
 *   -> verify -> re-apply OTA lockdown -> start the Debian server
 *
 * The `--late-load` flag is essential: `ksu-helper <ksud> late-load` silently
 * does nothing, and a typo'd binary name fails with exit 127.
 */
object RootFlow {

    private val TMP = Sh.TMP
    private val DIR = "$TMP/ota_lockdown"

    /**
     * A short wake lock held ONLY around the exploit.
     *
     * The flow can wait ~25 minutes in the thermal gate, and holding a wake lock
     * across that stops the device deep-sleeping the whole time. The gate wants a
     * *quiet* machine anyway, so letting it suspend while it waits is both fine
     * and better for battery.
     */
    private var wake: android.os.PowerManager.WakeLock? = null

    private fun acquireShortWake(ctx: Context, minutes: Long = 5) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wake?.let { if (it.isHeld) it.release() }
            wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AutoRoot:exploit")
            wake?.acquire(minutes * 60_000L)
        } catch (_: Throwable) {
        }
    }

    fun releaseWake() {
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wake = null
    }

    fun hottestTemp(): Int = try {
        Sh.exec("for z in /sys/class/thermal/thermal_zone*/temp; do cat \$z 2>/dev/null; done | sort -n | tail -1")
            .trim().toIntOrNull() ?: 0
    } catch (_: Throwable) { 0 }

    fun uptimeSec(): Long = try {
        Sh.exec("cut -d' ' -f1 /proc/uptime").trim().toDoubleOrNull()?.toLong() ?: 0L
    } catch (_: Throwable) { 0L }

    /** Wait until the device is cool + settled enough for the exploit gate. */
    fun waitForGate(maxMinutes: Int = 25): Boolean {
        repeat(maxMinutes * 4) { i ->
            val up = uptimeSec()
            val temp = hottestTemp()
            if (up >= 60 && temp in 1..48000) {
                Sh.log("gate OK uptime=${up}s temp=${temp / 1000}C")
                return true
            }
            if (i % 4 == 0) Sh.log("gate waiting uptime=${up}s temp=${temp / 1000}C (need <=48C)")
            Thread.sleep(15_000)
        }
        return false
    }

    /**
     * Full flow. Safe to call when already rooted.
     * @return true if root is available at the end.
     */
    fun run(ctx: Context): Boolean {
        Sh.init(ctx)

        if (!Sh.shizukuReady()) {
            Sh.log("Shizuku not running - cannot obtain shell context")
            return false
        }
        if (!Sh.ensureService()) {
            Sh.log("could not bind the shell user service (Shizuku permission granted?)")
            return false
        }

        // Stage the payloads and scripts BEFORE the rooted check.
        //
        // This used to sit after it, which meant an already-rooted phone never
        // refreshed anything: deleting a script left the Debian server unable to
        // start, and a fix shipped in a new APK would not take effect until the
        // phone happened to lose root. Staging is cheap and idempotent, so it
        // belongs first.
        Sh.log("=== extracting payloads and scripts from our own APK ===")
        Sh.extractPayloads()

        if (Sh.isRooted()) {
            Sh.log("already rooted")
            afterRoot()
            return true
        }

        Sh.log("=== waiting for gate ===")
        if (!waitForGate()) {
            Sh.log("gate never opened (device too warm / too busy)")
            return false
        }

        // The gate wants the device cool AND quiet, so let it sleep while it
        // waits; only pin the CPU awake for the exploit itself.
        acquireShortWake(ctx)

        Sh.log("=== launching exploit ===")
        // ABSOLUTE paths are required - the payload's preflight rejects a
        // relative --helper with "[preflight] invalid root helper errno=0".
        val launcher = "$TMP/stability-launcher"
        val payload = "$TMP/ksu-payload"
        val helper = "$TMP/ksu-helper"
        val factory = "$TMP/mm-exec-factory"
        Sh.exec(
            "cd '$TMP' && rm -f bope.log temp_su.sock 2>/dev/null; " +
            "nohup $launcher --payload $payload --helper $helper " +
            "--mm-factory $factory > bope.log 2>&1 &"
        )

        var reached = false
        for (i in 0 until 300) {
            Thread.sleep(2_000)
            val tail = Sh.exec("tail -2 '$TMP/bope.log' 2>/dev/null").trim()
            if (tail.isNotEmpty() && i % 5 == 0) Sh.log("bope: ${tail.replace("\n", " | ")}")
            if (tail.contains("Root achieved")) { reached = true; break }
            if (tail.contains("BOPE :: Failure")) break
            // bail out early instead of polling for 10 minutes
            if (tail.contains("invalid root helper")) { Sh.log("helper rejected; aborting"); break }
            if (tail.contains("attempt=3/3")) { Thread.sleep(4_000); break }
        }

        if (!reached) {
            Sh.log("exploit did not report success; tail=${Sh.exec("tail -5 '$TMP/bope.log' 2>/dev/null")}")
            return false
        }
        Sh.log("exploit reported success")

        Sh.log("=== ksu-helper --late-load ===")
        val out = Sh.exec("cd '$TMP' && ./ksu-helper --late-load 2>&1")
        Sh.log("late-load: ${out.trim().take(400)}")

        Thread.sleep(3_000)
        val ok = Sh.isRooted()
        Sh.log("root check: ${if (ok) "ROOTED" else "not rooted"}")
        if (ok) afterRoot()
        return ok
    }

    /** Everything that should happen once root exists. */
    fun afterRoot() {
        val su = "su -c "
        Sh.log("--- KernelSU service stage ---")
        Sh.log(Sh.exec(su + "'/data/adb/ksud services' 2>&1").trim().take(200))

        Sh.log("--- OTA lockdown ---")
        Sh.log(Sh.exec(su + "'sh $DIR/lockdown.sh' 2>&1").trim().take(400))

        Sh.log("--- Debian server ---")
        Sh.log(Sh.exec(su + "'sh $TMP/debian-start.sh' 2>&1").trim().take(400))

        val listen = Sh.exec("(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -c ':1304'").trim()
        Sh.log("debian sshd listeners on 1304: $listen")
        Sh.log("=== DONE: root + lockdown + debian ===")
    }
}
