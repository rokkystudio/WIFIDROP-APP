package com.rokkystudio.wifidrop.network

import android.util.Log
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import com.rokkystudio.wifidrop.storage.SharedFileReader
import com.rokkystudio.wifidrop.toWiFiDropError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Загружает файлы в Windows WiFiDrop Server через upload endpoint.
 */
class WindowsUploadClient(
    private val sharedFileReader: SharedFileReader,
) {
    /**
     * Отправляет набор файлов на выбранный Windows-сервер.
     */
    fun uploadFiles(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        server: WindowsServer,
        files: List<SharedFileReader.SharedFile>,
    ): Int {
        val client = OkHttpClient.Builder()
            .socketFactory(WifiBoundSocketFactory(wifiInfo.network))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        try {
            files.forEach { file ->
                uploadSingleFile(client, server, file)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }

        return files.size
    }

    /**
     * Отправляет один файл на upload endpoint Windows-сервера.
     */
    private fun uploadSingleFile(
        client: OkHttpClient,
        server: WindowsServer,
        file: SharedFileReader.SharedFile,
    ) {
        Log.d(LOG_TAG, "Share upload: ${file.displayName} -> ${server.host}:${server.tcpPort}")
        val encodedName = URLEncoder.encode(file.displayName, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("http://${server.host}:${server.tcpPort}/wifidrop/upload?name=$encodedName")
            .put(SharedFileRequestBody(sharedFileReader, file))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = response.body?.string().orEmpty().trim()
                    val message = buildString {
                        append("Ошибка загрузки файла ${file.displayName}: HTTP ${response.code}")
                        if (reason.isNotBlank()) {
                            append(". ")
                            append(reason)
                        }
                    }
                    throw WiFiDropError.UploadFailed(file.displayName, message).asException()
                }
            }
        } catch (throwable: Throwable) {
            val error = throwable.toWiFiDropError(
                WiFiDropError.UploadFailed(file.displayName, "Не удалось отправить файл ${file.displayName}"),
            )
            throw error.asException(throwable)
        }
    }

    /**
     * Формирует HTTP body, читающий содержимое файла через ContentResolver.
     */
    private class SharedFileRequestBody(
        private val sharedFileReader: SharedFileReader,
        private val file: SharedFileReader.SharedFile,
    ) : RequestBody() {
        /**
         * Возвращает MIME-тип upload body.
         */
        override fun contentType() = OCTET_STREAM

        /**
         * Возвращает длину содержимого, если она известна.
         */
        override fun contentLength(): Long = file.sizeBytes ?: -1L

        /**
         * Записывает содержимое файла в HTTP поток.
         */
        override fun writeTo(sink: BufferedSink) {
            sharedFileReader.openInputStream(file).use { inputStream ->
                sink.writeAll(inputStream.source())
            }
        }
    }

    private companion object {
        const val LOG_TAG = "WiFiDrop"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 60L
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
