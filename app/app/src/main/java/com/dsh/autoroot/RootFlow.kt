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

    /**
     * Why the last run failed, so RootService knows what to offer.
     *
     * Once the payload has mutated the kernel it refuses to try again ("refusing unsafe retry
     * before reboot"), and a manual ksu-helper --late-load against that state answers
     * "su: connect daemon: Permission denied". Nothing is salvageable on that boot, so it is
     * told apart from the failures where a second attempt is genuinely safe. A retry button
     * on the mutated case would invite the one action the exploit will not take.
     */
    enum class Failure { NONE, RETRY_SAFE, REBOOT_REQUIRED }

    @Volatile var lastFailure = Failure.NONE

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
        lastFailure = Failure.NONE

        if (!Sh.shizukuReady()) {
            Sh.log("Shizuku not running - cannot obtain shell context")
            lastFailure = Failure.RETRY_SAFE
            return false
        }
        if (!Sh.ensureService()) {
            Sh.log("could not bind the shell user service (Shizuku permission granted?)")
            lastFailure = Failure.RETRY_SAFE
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
            afterRoot(ctx)
            return true
        }

        Sh.log("=== waiting for gate ===")
        if (!waitForGate()) {
            Sh.log("gate never opened (device too warm / too busy)")
            lastFailure = Failure.RETRY_SAFE
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
        var deadEnd = false
        for (i in 0 until 150) {
            Thread.sleep(2_000)
            // tail -2 was not enough, and that cost ten minutes of every failed boot. The
            // supervisor repeats its refusal every few seconds, so the two lines that matter
            // scrolled straight out of the window and none of the strings below could ever
            // match - the loop ran its full length and then reported "did not report
            // success" without saying why.
            val tail = Sh.exec("tail -30 '$TMP/bope.log' 2>/dev/null").trim()
            if (tail.isNotEmpty() && i % 5 == 0) Sh.log("bope: ${tail.lines().lastOrNull() ?: ""}")
            if (tail.contains("Root achieved")) { reached = true; break }

            // Terminal, and it happens: the futex route lands a kernel mutation, then the
            // physical-write step fails -
            //   [aar_aaw] unsafe write plan rejected ... errno=84(Illegal byte sequence)
            //   [pipe_rw] guard: cache swap failed ... restore=0
            //   [pipe_rw] det terminal failure attempt=1/12 reason=restore
            //   [immediate] pipe physical R/W install failed
            // and the supervisor refuses to retry, correctly, because the kernel is already
            // mutated and a second attempt could corrupt it. Measured: a manual
            // `ksu-helper --late-load` against this state returns "su: connect daemon:
            // Permission denied", so there is nothing to salvage and no point waiting.
            // A reboot clears it and the flow tries again.
            if (tail.contains("kernel-mutation-pending") || tail.contains("refusing unsafe retry")) {
                Sh.log("exploit stopped at kernel-mutation-pending: the kernel is mutated but the")
                Sh.log("physical-write step failed, so there is no root this boot and no safe retry.")
                lastFailure = Failure.REBOOT_REQUIRED
                deadEnd = true
                break
            }
            if (tail.contains("BOPE :: Failure")) { deadEnd = true; break }
            if (tail.contains("invalid root helper")) { Sh.log("helper rejected; aborting"); deadEnd = true; break }
            if (tail.contains("attempt=3/3")) { Thread.sleep(4_000); break }
        }

        if (deadEnd || !reached) {
            Sh.log("exploit did not produce root this boot; tail=${Sh.exec("tail -8 '$TMP/bope.log' 2>/dev/null")}")
            // The mutated case set this already and a second attempt is not safe; everything
            // else that lands here failed before the kernel was touched.
            if (lastFailure != Failure.REBOOT_REQUIRED) lastFailure = Failure.RETRY_SAFE
            return false
        }
        Sh.log("exploit reported success")

        Sh.log("=== ksu-helper --late-load ===")
        val out = Sh.exec("cd '$TMP' && ./ksu-helper --late-load 2>&1")
        Sh.log("late-load: ${out.trim().take(400)}")

        Thread.sleep(3_000)
        val ok = Sh.isRooted()
        Sh.log("root check: ${if (ok) "ROOTED" else "not rooted"}")
        if (ok) {
            afterRoot(ctx)
        } else {
            // The exploit claimed success and the late-load ran, but su is still missing.
            // The kernel was mutated on the way here, so a repeat attempt is not safe.
            lastFailure = Failure.REBOOT_REQUIRED
        }
        return ok
    }

    /** Everything that should happen once root exists. */
    fun afterRoot(ctx: Context) {
        val su = "su -c "

        // Leave adbd listening on TCP for the NEXT boot.
        //
        // This is the one thing that breaks the cycle the handset hit with the cable out:
        // root needs a shell, a shell needs adb, and wireless debugging - the only adb this
        // app had - is a framework feature whose listener is started by the Settings UI and
        // resets on a reboot. With no cable and no wireless debugging there was no way in at
        // all, so nothing downstream could ever start.
        //
        // adbd reads, in this order (packages/modules/adb/daemon/main.cpp):
        //
        //     service.adb.listen_addrs   - if set, it wins outright and the ports below are
        //                                  ignored entirely, which is why this checks it
        //     service.adb.tcp.port       - volatile, gone on reboot
        //     persist.adb.tcp.port       - survives reboot; this is the one that matters
        //
        // persist is a property, not a setting, so it stays set, and adbd binds the port from
        // boot before any app runs. Set here, while root is in hand, so the next boot has it.
        // ShizukuBootstrap.ADB_TCP_PORT is the matching constant it connects to.
        //
        // Setting a property is all this does - no adbd restart - so it is safe to do first.
        // The narrowing below is the part that restarts adbd, and it runs last.
        Sh.log("--- adb over TCP for the next boot ---")
        val listenAddrs = Sh.exec("getprop service.adb.listen_addrs").trim()
        if (listenAddrs.isNotEmpty()) {
            // Deliberately not touching it: overriding a vendor listen_addrs could take the
            // device off the network path it is meant to use. Report it instead.
            Sh.log("service.adb.listen_addrs is set to '$listenAddrs' - leaving adbd alone")
        } else {
            Sh.log(
                Sh.exec(
                    su + "'setprop persist.adb.tcp.port ${ShizukuBootstrap.ADB_TCP_PORT}; " +
                        "echo persist=$(getprop persist.adb.tcp.port) " +
                        "service=$(getprop service.adb.tcp.port)' 2>&1"
                ).trim()
            )
        }

        Sh.log("--- KernelSU service stage ---")
        Sh.log(Sh.exec(su + "'/data/adb/ksud services' 2>&1").trim().take(200))

        Sh.log("--- OTA lockdown ---")
        Sh.log(Sh.exec(su + "'sh $DIR/lockdown.sh' 2>&1").trim().take(400))

        // Narrow adbd to loopback, THEN start the chroot.
        //
        // The narrowing restarts adbd, and restarting adbd kills everything that was
        // started from an adb session - including Shizuku, and including the chroot's sshd,
        // because debian-start.sh is itself run through Sh.exec. Measured on the handset:
        // putting the narrowing last left a stale LISTEN socket on 1304 with nothing behind
        // it, ssh refused on both loopback and the LAN, and the watchdog never put it back
        // because restarting sshd is not one of the things it does. So the order is:
        // everything that needs Shizuku first, then the one disruptive step, then the
        // chroot - whose sshd is then a child of a session nobody is about to tear down.
        if (listenAddrs.isEmpty()) {
            // tcp:<port> means network_inaddr_any_server - every interface, including the
            // cafe wifi. The phone does not need that: the app runs on the phone and talks
            // to 127.0.0.1. socket_spec.cpp only treats the literal string "localhost" as
            // local (tcp_host_is_local), so tcp:127.0.0.1:5555 is REJECTED outright with
            // "listening on specified hostname currently unsupported" and
            // tcp:localhost:5555 is what binds loopback.
            //
            // listen_addrs is volatile - gone after a reboot, which is why
            // persist.adb.tcp.port is set above as the thing that gets adbd listening at
            // all. The boot window is therefore all-interfaces, and this narrows it once
            // root exists.
            Sh.log("--- narrowing adbd to loopback ---")
            Sh.exec(
                su + "'setprop service.adb.listen_addrs tcp:localhost:${ShizukuBootstrap.ADB_TCP_PORT}; " +
                    "setprop ctl.restart adbd' 2>&1"
            )
            Thread.sleep(5_000)

            // adbd's restart took Shizuku with it. Bring it back before anything needs it.
            if (rikka.shizuku.Shizuku.pingBinder() == false) {
                Sh.log("Shizuku went down with adbd - restarting it")
                ShizukuBootstrap.startServer(ctx) { line -> Sh.log("  $line") }
            }

            // And re-bind the user service, which the restart invalidated.
            //
            // Everything below runs through Sh.exec, and that goes through the user service.
            // Without this the chroot step reported "ERR: user service not bound" and the
            // sshd never came up at all - the socket on 1304 lingered with nothing behind it,
            // which looks connected and is not. This is the step that was missing.
            if (!Sh.ensureService(30_000)) {
                Sh.log("could not re-bind the shell user service after restarting Shizuku")
            } else {
                Sh.log("shell user service re-bound")
            }
        }

        // Started last, so its sshd outlives the adbd restart above.
        Sh.log("--- Debian server ---")
        Sh.log(Sh.exec(su + "'sh $TMP/debian-start.sh' 2>&1").trim().take(400))

        // Report the PROCESS, not the socket. A stale LISTEN socket satisfies a port check
        // while nothing is behind it, which is how a dead sshd went unnoticed: the log said
        // "listeners on 1304: 1" and ssh was refused everywhere.
        val sshd = Sh.exec("pgrep -f 'sshd -p 1304' | wc -l").trim()
        val sock = Sh.exec("(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -c ':1304'").trim()
        Sh.log("debian sshd: processes=$sshd sockets=$sock")
        Sh.log("=== DONE: root + lockdown + debian ===")
    }
}
