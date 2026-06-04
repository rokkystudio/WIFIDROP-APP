package com.rokkystudio.wifidrop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Предоставляет сведения о Wi‑Fi сети, через которую выполняются
 * локальные подключения WiFiDrop.
 */
class WifiNetworkProvider(
    context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Содержит привязку к Wi‑Fi сети и IPv4-адрес устройства в этой сети.
     */
    data class WifiNetworkInfo(
        val network: Network,
        val ipv4Address: Inet4Address,
        val prefixLength: Int,
    )

    /**
     * Возвращает активную Wi‑Fi сеть и IPv4-адрес устройства.
     */
    fun getWifiNetworkInfo(): WifiNetworkInfo {
        val wifiNetwork = findWifiNetwork() ?: throw WiFiDropError.NoWifiNetwork.asException()
        val linkProperties = connectivityManager.getLinkProperties(wifiNetwork)
            ?: throw WiFiDropError.NoWifiNetwork.asException()
        val ipv4Address = findIpv4Address(linkProperties)
            ?: throw WiFiDropError.NoWifiNetwork.asException()
        val prefixLength = findPrefixLength(linkProperties, ipv4Address)
            ?: throw WiFiDropError.NoWifiNetwork.asException()
        Log.d(LOG_TAG, "Wi-Fi network: ${ipv4Address.hostAddress}/$prefixLength")
        return WifiNetworkInfo(
            network = wifiNetwork,
            ipv4Address = ipv4Address,
            prefixLength = prefixLength,
        )
    }

    /**
     * Ищет Wi‑Fi сеть, которую можно использовать для локальных подключений.
     */
    private fun findWifiNetwork(): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (capabilities?.isUsableWifiNetwork() == true) {
                return activeNetwork
            }
        }
        return awaitWifiNetwork()
    }

    /**
     * Ожидает доступную Wi‑Fi сеть через актуальный callback API ConnectivityManager.
     */
    private fun awaitWifiNetwork(): Network? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val selectedNetwork = AtomicReference<Network?>()
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
                if (!capabilities.isUsableWifiNetwork()) {
                    return
                }
                selectedNetwork.compareAndSet(null, network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }

        registerWifiCallback(request, callback)
        return try {
            latch.await(WIFI_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            selectedNetwork.get()?.takeIf {
                val capabilities = connectivityManager.getNetworkCapabilities(it)
                capabilities?.isUsableWifiNetwork() == true
            }
        } finally {
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
    }

    /**
     * Находит IPv4-адрес устройства в свойствах Wi‑Fi сети.
     */
    private fun findIpv4Address(linkProperties: LinkProperties): Inet4Address? {
        linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.let { return it }

        val interfaceName = linkProperties.interfaceName ?: return null
        val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
        return networkInterface.inetAddresses
            .toList()
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
    }

    /**
     * Находит длину префикса для IPv4-адреса Wi‑Fi сети.
     */
    private fun findPrefixLength(linkProperties: LinkProperties, ipv4Address: Inet4Address): Int? {
        return linkProperties.linkAddresses
            .firstOrNull { it.address == ipv4Address }
            ?.prefixLength
    }

    /**
     * Проверяет наличие Wi‑Fi транспорта у сети Android.
     */
    private fun NetworkCapabilities.isUsableWifiNetwork(): Boolean {
        return hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Регистрирует callback поиска Wi‑Fi сети через доступный API уровня SDK.
     */
    private fun registerWifiCallback(
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivityManager.registerBestMatchingNetworkCallback(
                request,
                callback,
                Handler(Looper.getMainLooper()),
            )
        } else {
            connectivityManager.registerNetworkCallback(request, callback)
        }
    }

    private companion object {
        const val LOG_TAG = "WiFiDrop"
        const val WIFI_CALLBACK_TIMEOUT_MS = 750L
    }
}
