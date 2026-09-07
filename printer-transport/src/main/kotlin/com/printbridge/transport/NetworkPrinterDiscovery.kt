package com.printbridge.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class NetworkPrinterCandidate(
    val host: String,
    val port: Int,
    val protocolHint: String
)

class NetworkPrinterDiscovery(
    private val ports: List<Int> = DEFAULT_PRINTER_PORTS,
    private val connectTimeoutMs: Int = 250
) {
    suspend fun discoverLocalSubnets(limitPerSubnet: Int = 254): List<NetworkPrinterCandidate> = withContext(Dispatchers.IO) {
        val hosts = localIpv4Subnets(limitPerSubnet)
        discoverHosts(hosts)
    }

    suspend fun discoverSubnet(prefix: String, limit: Int = 254): List<NetworkPrinterCandidate> = withContext(Dispatchers.IO) {
        require(prefix.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\."""))) { "Subnet prefix must look like 192.168.1." }
        discoverHosts((1..limit.coerceIn(1, 254)).map { "$prefix$it" })
    }

    private suspend fun discoverHosts(hosts: List<String>): List<NetworkPrinterCandidate> = coroutineScope {
        hosts.distinct().flatMap { host ->
            ports.map { port ->
                async(Dispatchers.IO) {
                    if (isOpen(host, port)) NetworkPrinterCandidate(host, port, port.protocolHint()) else null
                }
            }
        }.awaitAll().filterNotNull().sortedWith(compareBy({ it.hostAddressSortKey() }, { it.port }))
    }

    private fun isOpen(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                true
            }
        }.getOrDefault(false)

    private fun localIpv4Subnets(limitPerSubnet: Int): List<String> =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .flatMap { address ->
                val bytes = address.address.map { it.toInt() and 0xff }
                val prefix = "${bytes[0]}.${bytes[1]}.${bytes[2]}."
                (1..limitPerSubnet.coerceIn(1, 254)).asSequence().map { "$prefix$it" }
            }
            .toList()

    private fun Int.protocolHint(): String = when (this) {
        9191 -> "PrintBridge Windows Bridge"
        9100 -> "RAW/JetDirect"
        631 -> "IPP"
        515 -> "LPR"
        else -> "TCP"
    }

    private fun NetworkPrinterCandidate.hostAddressSortKey(): Long =
        host.split('.').fold(0L) { acc, part -> (acc shl 8) + (part.toLongOrNull() ?: 0L) }

    companion object {
        val DEFAULT_PRINTER_PORTS = listOf(9191, 9100, 631, 515)
    }
}
