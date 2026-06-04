package com.rokkystudio.wifidrop.network

import android.util.Log
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import com.rokkystudio.wifidrop.toWiFiDropError
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Выполняет HTTP-поиск Windows WiFiDrop Server в Wi‑Fi подсети устройства.
 */
class WifiDropScanner {
    /**
     * Сообщает о прогрессе IP-сканирования подсети.
     */
    data class ScanProgress(
        val currentHost: String,
        val scannedHosts: Int,
        val totalHosts: Int,
        val foundServers: Int,
    )

    /**
     * Сканирует подсеть и возвращает найденные Windows-серверы.
     */
    fun scan(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        onProgress: ((ScanProgress) -> Unit)? = null,
    ): List<WindowsServer> {
        val candidateHosts = buildCandidateHosts(wifiInfo.ipv4Address, wifiInfo.prefixLength)
        if (candidateHosts.size > MAX_SCAN_HOSTS) {
            throw WiFiDropError.UnknownError("Подсеть Wi‑Fi слишком большая для быстрого поиска сервера").asException()
        }

        Log.d(LOG_TAG, "Старт scan по ${candidateHosts.size} адресам")
        val client = OkHttpClient.Builder()
            .socketFactory(WifiBoundSocketFactory(wifiInfo.network))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val results = ConcurrentHashMap<String, WindowsServer>()
        val scannedHosts = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_REQUESTS, candidateHosts.size.coerceAtLeast(1)))
        try {
            val futures = ArrayList<Future<*>>(candidateHosts.size)
            for (host in candidateHosts) {
                futures += executor.submit {
                    fetchServer(client, host)?.let { server ->
                        results.putIfAbsent(server.host, server)
                    }
                    onProgress?.invoke(
                        ScanProgress(
                            currentHost = host,
                            scannedHosts = scannedHosts.incrementAndGet(),
                            totalHosts = candidateHosts.size,
                            foundServers = results.size,
                        ),
                    )
                }
            }
            futures.forEach { it.get() }
        } catch (throwable: Throwable) {
            throw throwable.toWiFiDropError(
                WiFiDropError.UnknownError("Не удалось завершить поиск серверов WiFiDrop"),
            ).asException(throwable)
        } finally {
            executor.shutdownNow()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }

        val servers = results.values.sortedWith(compareBy({ it.deviceName.lowercase() }, { it.host }))
        Log.d(LOG_TAG, "Найдено серверов: ${servers.size}")
        servers.forEach { server ->
            Log.d(LOG_TAG, "Сервер ${server.deviceName} ${server.host}:${server.tcpPort}")
        }
        return servers
    }

    /**
     * Выполняет HTTP-запрос к одному IP-адресу и проверяет протокольный ответ.
     */
    private fun fetchServer(client: OkHttpClient, host: String): WindowsServer? {
        val request = Request.Builder()
            .url("http://$host:$DEFAULT_HTTP_PORT/wifidrop/info")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val body = response.body?.string().orEmpty()
                parseServerInfo(host, body)
            }
        } catch (throwable: Throwable) {
            val error = throwable.toWiFiDropError(WiFiDropError.UnknownError())
            if (error is WiFiDropError.LocalNetworkBlocked) {
                throw error.asException(throwable)
            }
            Log.d(LOG_TAG, "HTTP scan request failed for $host:$DEFAULT_HTTP_PORT", throwable)
            null
        }
    }

    /**
     * Преобразует JSON-ответ сервера в модель WindowsServer.
     */
    private fun parseServerInfo(host: String, body: String): WindowsServer? {
        return runCatching {
            val json = JSONObject(body)
            if (json.optString("app") != "WiFiDrop") {
                return null
            }
            if (json.optString("role") != "windows-server") {
                return null
            }
            val protocolVersion = json.optInt("protocolVersion", -1)
            if (protocolVersion != 1) {
                return null
            }

            WindowsServer(
                host = host,
                tcpPort = json.optInt("tcpPort", DEFAULT_HTTP_PORT),
                udpPort = json.optInt("udpPort").takeIf { json.has("udpPort") },
                deviceName = json.optString("deviceName").ifBlank { host },
                protocolVersion = protocolVersion,
            )
        }.getOrNull()
    }

    /**
     * Строит список IPv4-адресов в подсети, кроме адреса текущего устройства.
     */
    private fun buildCandidateHosts(address: Inet4Address, prefixLength: Int): List<String> {
        if (prefixLength !in 1..30) {
            throw WiFiDropError.UnknownError("Не удалось определить подсеть Wi‑Fi для поиска серверов").asException()
        }

        val ipValue = address.toUnsignedLong()
        val mask = (-1L shl (32 - prefixLength)) and IPV4_MASK
        val network = ipValue and mask
        val broadcast = network or (IPV4_MASK xor mask)
        val firstHost = network + 1
        val lastHost = broadcast - 1
        val result = ArrayList<String>((lastHost - firstHost + 1).toInt().coerceAtLeast(0))
        var current = firstHost
        while (current <= lastHost) {
            if (current != ipValue) {
                result += current.toIpv4String()
            }
            current++
        }
        return result
    }

    /**
     * Преобразует IPv4-адрес в беззнаковое число.
     */
    private fun Inet4Address.toUnsignedLong(): Long {
        val bytes = address
        return ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
    }

    /**
     * Преобразует беззнаковое число в строку IPv4-адреса.
     */
    private fun Long.toIpv4String(): String {
        return listOf(
            (this shr 24) and 0xFF,
            (this shr 16) and 0xFF,
            (this shr 8) and 0xFF,
            this and 0xFF,
        ).joinToString(".")
    }

    private companion object {
        const val LOG_TAG = "WiFiDrop"
        const val DEFAULT_HTTP_PORT = 49231
        const val CONNECT_TIMEOUT_MS = 300L
        const val READ_TIMEOUT_MS = 500L
        const val MAX_PARALLEL_REQUESTS = 24
        const val MAX_SCAN_HOSTS = 4096
        const val IPV4_MASK = 0xFFFFFFFFL
    }
}
