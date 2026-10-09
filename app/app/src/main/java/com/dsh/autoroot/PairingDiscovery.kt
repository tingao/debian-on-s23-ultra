package com.dsh.autoroot

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Finds the wireless debugging pairing port.
 *
 * Uses Android's own mDNS, NsdManager, rather than shelling out to the bundled adb.
 * That is what Shizuku does (`manager/adb/AdbMdns.kt`) and the difference is the whole
 * reason this exists: the subprocess route starts an adb server, takes tens of seconds,
 * and frequently reports nothing at all, so the notification never got as far as
 * offering the code box. NsdManager answers in well under a second, in process.
 *
 * The pairing service only advertises while the "Pair device with pairing code" screen
 * is open, which is exact: there is nothing to find until that screen appears, and it
 * disappears when the screen does.
 */
class PairingDiscovery(
    context: Context,
    private val onPort: (Int) -> Unit,
    private val onLost: () -> Unit = {}
) {

    companion object {
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
    }

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var running = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var current: String? = null

    fun start() {
        if (running || nsd == null) return
        running = true

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
            }

            override fun onDiscoveryStopped(serviceType: String) {
                running = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!running) return
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}

                        override fun onServiceResolved(i: NsdServiceInfo) {
                            if (!running) return
                            if (isThisPhone(i) && somethingIsListening(i.port)) {
                                current = i.serviceName
                                onPort(i.port)
                            }
                        }
                    })
                } catch (_: Throwable) {
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceName == current) {
                    current = null
                    onLost()
                }
            }
        }

        listener = l
        try {
            nsd.discoverServices(TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (t: Throwable) {
            running = false
        }
    }

    fun stop() {
        running = false
        listener?.let { l -> try { nsd?.stopServiceDiscovery(l) } catch (_: Throwable) {} }
        listener = null
        current = null
    }

    /**
     * The service has to be on one of this phone's own addresses: we are pairing with
     * ourselves, and a pairing service elsewhere on the network is not our business.
     */
    private fun isThisPhone(info: NsdServiceInfo): Boolean = try {
        val host = info.host?.hostAddress ?: return false
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            nif.inetAddresses.toList().any { it.hostAddress == host }
        }
    } catch (t: Throwable) {
        true
    }

    /** Confirms something is actually bound there. Same check Shizuku makes. */
    private fun somethingIsListening(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: Exception) {
        true
    }
}
