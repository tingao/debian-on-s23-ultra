package com.dsh.autoroot

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Start Shizuku from the phone itself, using the phone's own wireless debugging.
 *
 * Shizuku's server has to run as the *shell* user, and the usual way to arrange that
 * is `adb` from a computer. Android 11 and later can pair with itself over Wireless
 * debugging, so the computer is not needed: the same `adb` client, run on the phone,
 * pairs with the phone's own adbd and starts Shizuku over that shell.
 *
 * Neither the port nor the address is ever asked for. Android advertises both services
 * over mDNS, and `adb mdns services` prints them:
 *
 *     adb-<serial>-xxxxxx   _adb-tls-pairing._tcp.   192.0.2.10:37039
 *     adb-<serial>-xxxxxx   _adb-tls-connect._tcp.   192.0.2.10:46413
 *
 * The pairing one only exists while the "Pair device with pairing code" screen is
 * open, which is why finding it is retried rather than done once. The ports are
 * random and change constantly, so discovering them is the only thing that works:
 * the obvious alternative, reading /proc/net/tcp, is refused for an app uid, which
 * was measured on this device rather than assumed.
 *
 * **The pairing code itself is asked for once, ever.** Pairing is a key exchange. Our
 * key is kept in this app's private storage and the phone remembers it, so every later
 * run connects with that key and no code is involved. Nothing in the ordinary boot
 * path touches wireless debugging at all.
 *
 * The client is AOSP's `adb` (Apache-2.0), shipped as jniLibs/arm64-v8a/libadb.so.
 * That name is deliberate: the native library directory is the only place Android
 * gives an app a file it is allowed to execute.
 */
object ShizukuBootstrap {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val STARTER = "moe.shizuku.starter.ServiceStarter"

    data class Endpoint(val name: String, val host: String, val port: Int) {
        val address get() = "$host:$port"
    }

    fun adbBinary(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, "libadb.so")

    fun available(ctx: Context): Boolean =
        adbBinary(ctx).let { it.exists() && it.canExecute() }

    /** True once a pairing key exists, which is what makes the code one-time. */
    fun hasKey(ctx: Context): Boolean = File(home(ctx), ".android/adbkey").exists()

    /** Whether Shizuku is answering, i.e. whether the code is still needed at all. */
    fun serverRunning(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun forgetKey(ctx: Context) {
        File(home(ctx), ".android").deleteRecursively()
    }

    private fun home(ctx: Context): File =
        File(ctx.filesDir, "adbtools").apply { mkdirs() }

    /** Run the bundled adb. Blocking; call from a worker. */
    fun adb(ctx: Context, vararg args: String, timeoutSec: Long = 45): String {
        val bin = adbBinary(ctx)
        if (!bin.exists()) return "ERR: no adb binary at ${bin.absolutePath}"
        if (!bin.canExecute()) return "ERR: adb binary is not executable"
        val dir = home(ctx)
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            val env = pb.environment()
            env["HOME"] = dir.absolutePath
            // adb keeps its server log and socket in TMPDIR, and defaults to
            // /data/local/tmp, which an app uid is refused. Without this the server
            // never starts and every command fails with "ADB server didn't ACK".
            env["TMPDIR"] = dir.absolutePath
            // Keep our server off the usual 5037 so it cannot collide with a real one.
            env["ADB_SERVER_PORT"] = "5137"
            env["ADB_VENDOR_KEYS"] = File(dir, ".android").absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                "$text\nERR: adb timed out after ${timeoutSec}s"
            } else text
        } catch (t: Throwable) {
            "ERR: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** Everything adb can see over mDNS, as name / service type / address. */
    private fun mdns(ctx: Context): List<Triple<String, String, Endpoint>> {
        val out = adb(ctx, "mdns", "services", timeoutSec = 25)
        return out.lineSequence().mapNotNull { raw ->
            val f = raw.trim().split(Regex("\\s+"))
            if (f.size < 3 || !f[1].startsWith("_adb")) return@mapNotNull null
            val addr = f[2]
            val host = addr.substringBeforeLast(':', "")
            val port = addr.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            if (host.isEmpty()) return@mapNotNull null
            Triple(f[0], f[1], Endpoint(f[0], host, port))
        }.toList()
    }

    /**
     * The pairing endpoint. Only advertised while that screen is open, so this keeps
     * looking for a while instead of giving up on the first quiet answer.
     */
    fun findPairing(ctx: Context, onStep: (String) -> Unit): Endpoint? {
        repeat(10) { attempt ->
            adb(ctx, "start-server", timeoutSec = 20)
            val hit = mdns(ctx).firstOrNull { it.second.contains("_adb-tls-pairing") }?.third
            if (hit != null) return hit
            if (attempt == 0) {
                onStep("waiting for the pairing screen to show up over mDNS")
            }
            Thread.sleep(2000)
        }
        return null
    }

    /** The shell endpoint, preferred over any address we were told before. */
    fun findConnect(ctx: Context): Endpoint? =
        mdns(ctx).firstOrNull { it.second.contains("_adb-tls-connect") }?.third

    /** A serial we can shell into, or null. */
    fun ready(ctx: Context): String? {
        repeat(4) { attempt ->
            val devices = adb(ctx, "devices", timeoutSec = 25)
            devices.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it[1] == "device" }
                ?.let { return it[0] }
            if (attempt < 3) {
                findConnect(ctx)?.let { adb(ctx, "connect", it.address, timeoutSec = 15) }
                Thread.sleep(2500)
            }
        }
        return null
    }

    /**
     * Bring Shizuku up with the key we already have. No code, no ports, no prompts:
     * this is the path used on every ordinary run.
     */
    fun startServer(ctx: Context, onStep: (String) -> Unit): Boolean {
        if (rikka.shizuku.Shizuku.pingBinder()) {
            onStep("Shizuku is already running")
            return true
        }
        if (!available(ctx)) {
            onStep("ERR: the bundled adb is missing from this build")
            return false
        }
        if (!hasKey(ctx)) {
            onStep("no pairing key yet, so this needs the one-time code")
            return false
        }

        onStep("looking for this phone's adb over mDNS")
        val serial = ready(ctx)
        if (serial == null) {
            onStep("could not reach adb. Wireless debugging has to be on for this first start,")
            onStep("in Developer options.")
            return false
        }
        onStep("found $serial")

        val apk = adb(ctx, "-s", serial, "shell", "pm path $SHIZUKU_PKG", timeoutSec = 30)
            .substringAfter("package:", "").substringBefore('\n').trim()
        if (apk.isEmpty()) {
            onStep("could not find the Shizuku APK for app_process")
            return false
        }

        onStep("starting Shizuku")
        val out = adb(
            ctx, "-s", serial, "shell",
            "CLASSPATH=$apk app_process /system/bin --nice-name=shizuku_server $STARTER --debug=false",
            timeoutSec = 60
        ).trim()
        out.lineSequence().filter { it.isNotBlank() }.take(6).forEach { onStep("  $it") }

        repeat(10) {
            Thread.sleep(700)
            if (rikka.shizuku.Shizuku.pingBinder()) return true
        }
        onStep("started, but the binder is not answering yet")
        return false
    }

    /**
     * Pair against a port that has already been discovered.
     *
     * The notification flow finds the endpoint first and then hands the port to the
     * reply action, so that the pairing does not depend on the pairing screen still
     * being open by the time the user has finished typing.
     */
    fun pairAt(ctx: Context, code: String, port: Int, onStep: (String) -> Unit): Boolean {
        if (!available(ctx)) {
            onStep("ERR: the bundled adb is missing from this build")
            return false
        }
        val address = "127.0.0.1:$port"
        onStep("pairing with $address")
        val out = adb(ctx, "pair", address, code, timeoutSec = 60).trim()
        out.lineSequence().filter { it.isNotBlank() }.forEach { onStep("  $it") }
        return out.contains("Successfully paired", ignoreCase = true)
    }

    /**
     * One-time pairing. Takes only the code: the address and port are discovered.
     */
    fun pair(ctx: Context, code: String, onStep: (String) -> Unit): Boolean {
        if (!available(ctx)) {
            onStep("ERR: the bundled adb is missing from this build")
            return false
        }

        onStep("looking for the pairing service over mDNS")
        val endpoint = findPairing(ctx, onStep)
        if (endpoint == null) {
            onStep("could not find the pairing service. Open Developer options >")
            onStep("Wireless debugging, tap \"Pair device with pairing code\", and tap")
            onStep("this button while that screen is still showing.")
            return false
        }
        onStep("pairing with ${endpoint.address}")

        val out = adb(ctx, "pair", endpoint.address, code, timeoutSec = 60).trim()
        out.lineSequence().filter { it.isNotBlank() }.forEach { onStep("  $it") }
        if (!out.contains("Successfully paired", ignoreCase = true)) {
            onStep("that did not pair. The code is only valid while the screen is open.")
            return false
        }
        onStep("paired. The key is stored, so this is the last time you need a code.")
        return true
    }

    /**
     * Straight to Wireless debugging, scrolled into view.
     *
     * Taken from Shizuku's own source, which solves exactly this problem
     * (`home/AdbPairingTutorialActivity.kt`): the public
     * ACTION_APPLICATION_DEVELOPMENT_SETTINGS intent, plus Settings' internal
     * `:settings:fragment_args_key` extra set to `toggle_adb_wireless`, which makes
     * Settings scroll to and highlight that preference.
     *
     * Measured on this phone rather than assumed. With the extra, Developer options
     * opens already scrolled down and "Wireless debugging" is on screen. Without it,
     * it opens at the top showing Memory, Bug report and Desktop backup, and Wireless
     * debugging is nowhere to be seen.
     *
     * The same flags Shizuku uses are set: NEW_TASK plus CLEAR_TASK, so it always
     * lands on a fresh copy of the list rather than whatever Settings screen was
     * already in the back stack.
     */
    fun settingsIntent(ctx: Context): android.content.Intent {
        val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        i.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                  android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        return i
    }
}
