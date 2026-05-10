package com.horsenma.yourtv

import java.net.Inet4Address
import java.net.NetworkInterface

object PortUtil {

    fun lan(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val networkInterfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return null

        while (networkInterfaces.hasMoreElements()) {
            val networkInterface = networkInterfaces.nextElement()
            if (!runCatching { networkInterface.isUp }.getOrDefault(false)) continue
            if (runCatching { networkInterface.isLoopback || networkInterface.isVirtual }.getOrDefault(true)) continue

            val interfaceName = networkInterface.name.lowercase()
            val interfacePriority = when {
                interfaceName.startsWith("wlan") || interfaceName.startsWith("wifi") -> 0
                interfaceName.startsWith("eth") || interfaceName.startsWith("en") -> 1
                else -> 2
            }

            val inetAddresses = networkInterface.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val inetAddress = inetAddresses.nextElement()
                if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                    val addressPriority = if (inetAddress.isSiteLocalAddress) 0 else 10
                    val hostAddress = inetAddress.hostAddress ?: continue
                    candidates.add((interfacePriority + addressPriority) to hostAddress)
                }
            }
        }

        return candidates.minByOrNull { it.first }?.second
    }
}
