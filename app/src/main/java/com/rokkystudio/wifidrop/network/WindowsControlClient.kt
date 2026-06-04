package com.rokkystudio.wifidrop.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import com.rokkystudio.wifidrop.toWiFiDropError
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Поднимает control session Android -> Windows, чтобы Windows-приложение
 * увидело устройство как подключённое.
 */
class WindowsControlClient(
    private val context: Context,
) {
    data class WebDavEndpoint(
        val host: String,
        val port: Int,
        val basePath: String,
        val mountReady: Boolean,
    )

    data class ConnectedSession(
        val clientId: String,
        val driveName: String,
        val mountReady: Boolean,
    )

    private val activeSessionCall = AtomicReference<Call?>()

    /**
     * Регистрирует Android-устройство на Windows-сервере.
     */
    fun connect(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        server: WindowsServer,
        webDavEndpoint: WebDavEndpoint,
    ): ConnectedSession {
        Log.d(
            LOG_TAG,
            "Registering Android client on ${server.host}:${server.tcpPort} with WebDAV " +
                "${webDavEndpoint.host}:${webDavEndpoint.port}${webDavEndpoint.basePath} " +
                "mountReady=${webDavEndpoint.mountReady}",
        )
        val requestBody = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("deviceName", buildDeviceName())
            .put("deviceNumber", buildDeviceNumber())
            .put("webDavHost", webDavEndpoint.host)
            .put("webDavPort", webDavEndpoint.port)
            .put("webDavBasePath", webDavEndpoint.basePath)
            .put("mountReady", webDavEndpoint.mountReady)
            .put("readOnly", true)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("http://${server.host}:${server.tcpPort}/wifidrop/client/connect")
            .post(requestBody)
            .build()

        val client = buildClient(
            wifiInfo = wifiInfo,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw WiFiDropError.WindowsRejectedConnection(
                        "Windows server rejected connection: HTTP ${response.code}",
                    ).asException()
                }

                val json = JSONObject(body)
                if (!json.optBoolean("accepted")) {
                    val reason = json.optString("error").ifBlank { "Windows server rejected connection" }
                    throw WiFiDropError.WindowsRejectedConnection(reason).asException()
                }

                val clientId = json.optString("clientId")
                if (clientId.isBlank()) {
                    throw WiFiDropError.WindowsRejectedConnection("Windows server did not return clientId").asException()
                }

                return ConnectedSession(
                    clientId = clientId,
                    driveName = json.optString("driveName").ifBlank { buildDeviceName() },
                    mountReady = json.optBoolean("mountReady", false),
                ).also {
                    Log.d(
                        LOG_TAG,
                        "Windows accepted clientId=${it.clientId} driveName=${it.driveName} mountReady=${it.mountReady}",
                    )
                }
            }
        } catch (throwable: Throwable) {
            val error = throwable.toWiFiDropError(
                WiFiDropError.WindowsRejectedConnection("Could not register Android device on Windows"),
            )
            throw error.asException(throwable)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * Удерживает активную control session, пока пользователь не отключится
     * или Windows не закроет соединение.
     */
    fun runSession(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        server: WindowsServer,
        clientId: String,
    ) {
        val client = buildClient(
            wifiInfo = wifiInfo,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = 0L,
        )
        val request = Request.Builder()
            .url("http://${server.host}:${server.tcpPort}/wifidrop/client/session/$clientId")
            .get()
            .build()

        val call = client.newCall(request)
        activeSessionCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = response.body?.string().orEmpty().ifBlank { "HTTP ${response.code}" }
                    throw WiFiDropError.WindowsRejectedConnection(reason).asException()
                }

                val source = response.body?.source()
                    ?: throw WiFiDropError.WindowsRejectedConnection("Windows session stream is empty").asException()
                while (true) {
                    source.readUtf8Line() ?: break
                }
            }
        } catch (throwable: Throwable) {
            if (call.isCanceled()) {
                return
            }
            val error = throwable.toWiFiDropError(
                WiFiDropError.WindowsRejectedConnection("Windows control session ended unexpectedly"),
            )
            throw error.asException(throwable)
        } finally {
            activeSessionCall.compareAndSet(call, null)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * Прерывает текущую активную session.
     */
    fun disconnect() {
        activeSessionCall.getAndSet(null)?.cancel()
    }

    private fun buildClient(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .socketFactory(WifiBoundSocketFactory(wifiInfo.network))
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)

        if (readTimeoutMs == 0L) {
            builder.readTimeout(0L, TimeUnit.MILLISECONDS)
        } else {
            builder.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        }

        return builder.build()
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android Device" }
    }

    private fun buildDeviceNumber(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
            .takeLast(6)
            .uppercase(Locale.US)
        return androidId.ifBlank { "ANDROID" }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val PROTOCOL_VERSION = 1
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 3_000L
        const val LOG_TAG = "WiFiDrop"
    }
}
