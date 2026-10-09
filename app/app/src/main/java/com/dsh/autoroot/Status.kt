package com.dsh.autoroot

/** Collects one snapshot of every moving part, for the status screen. */
object Status {

    /** state: 1 = good, 0 = attention, -1 = broken */
    data class Item(val label: String, val value: String, val state: Int)

    fun snapshot(): List<Item> {
        val out = ArrayList<Item>()
        fun add(l: String, v: String, s: Int) = out.add(Item(l, v, s))

        val sh = Sh.shizukuReady()
        add("Shizuku service", if (sh) "running" else "not running", if (sh) 1 else -1)

        val perm = Sh.permissionGranted()
        add("Shizuku permission", if (perm) "granted" else "denied", if (perm) 1 else -1)

        val svc = if (sh && perm) Sh.ensureService(12_000) else false
        add(
            "Shell context",
            if (svc) "user service bound (uid ${Sh.serviceUid()})" else "not bound",
            if (svc) 1 else -1
        )
        if (!svc) return out

        val rooted = Sh.isRooted()
        val ctx = if (rooted) {
            Sh.exec("su -c id 2>&1").trim().substringAfter("context=", "").trim()
        } else ""
        add("Root", if (rooted) "uid=0  $ctx" else "NOT ROOTED", if (rooted) 1 else -1)

        val ksu = Sh.exec("grep -ic kernelsu /proc/modules 2>/dev/null").trim()
        add("KernelSU driver", if (ksu == "1") "loaded" else "not loaded", if (ksu == "1") 1 else -1)

        if (rooted) {
            val st = Sh.exec("su -c 'sh /data/local/tmp/ota_lockdown/status.sh' 2>&1")
            fun v(k: String) = Regex("$k=(\\S+)").find(st)?.groupValues?.get(1) ?: "?"

            val fb = v("fota_blocked")
            add("FOTA clients blocked", fb, if (fb == "3/3") 1 else 0)
            val hb = v("hosts_blocked")
            add("Hosts blackholed", "$hb domains", if ((hb.toIntOrNull() ?: 0) >= 51) 1 else 0)
            val sf = v("staging_files")
            add("Staged OTA files", sf, if (sf == "0") 1 else -1)
            val wd = v("watchdog")
            add("Watchdog", wd, if (wd == "yes") 1 else -1)
        }

        val deb = Sh.exec("(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -c ':1304'").trim().toIntOrNull() ?: 0
        add(
            "Debian SSH server",
            if (deb >= 1) "listening on 1304" else "not listening",
            if (deb >= 1) 1 else -1
        )

        // Only when a connector is actually installed. A phone without one should not
        // carry a permanent "not running" row that reads like a fault.
        if (rooted && Sh.exec("test -x /data/local/chroot/debian/usr/bin/cloudflared && echo yes").trim() == "yes") {
            val cf = Sh.exec("su -c 'pgrep -x cloudflared' 2>/dev/null").trim()
            add(
                "Cloudflare tunnel",
                if (cf.isNotEmpty()) "running (pid $cf)" else "not running",
                if (cf.isNotEmpty()) 1 else 0
            )
        }

        val ip = Sh.exec("ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | head -1")
            .trim().removePrefix("inet ").trim()
        add("Wi-Fi address", if (ip.isNotEmpty()) ip else "none", if (ip.isNotEmpty()) 1 else 0)

        val t = Sh.exec("for z in /sys/class/thermal/thermal_zone*/temp; do cat \$z 2>/dev/null; done | sort -n | tail -1").trim()
        val tc = (t.toIntOrNull() ?: 0) / 1000
        add("Temperature", "$tc C (gate needs <= 48 C)", if (tc in 1..48000 / 1000) 1 else 0)

        return out
    }

    fun wifiIp(): String = Sh.exec("ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | head -1")
        .trim().removePrefix("inet ").trim()
}
