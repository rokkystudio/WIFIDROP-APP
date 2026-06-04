package com.rokkystudio.wifidrop

import android.content.Context
import java.io.IOException

/**
 * Описывает ошибки сценариев WiFiDrop, которые показываются пользователю.
 */
sealed class WiFiDropError {
    data object NoWifiNetwork : WiFiDropError()

    data object LocalNetworkBlocked : WiFiDropError()

    data object ServerNotFound : WiFiDropError()

    data object MultipleServersNeedSelection : WiFiDropError()

    data class UploadFailed(
        val fileName: String? = null,
        val reason: String? = null,
    ) : WiFiDropError()

    data class WebDavStartFailed(
        val reason: String? = null,
    ) : WiFiDropError()

    data object StoragePermissionMissing : WiFiDropError()

    data class UnsupportedStorageOperation(
        val reason: String? = null,
    ) : WiFiDropError()

    data class WindowsRejectedConnection(
        val reason: String? = null,
    ) : WiFiDropError()

    data class UnknownError(
        val reason: String? = null,
    ) : WiFiDropError()

    /**
     * Возвращает текст ошибки для отображения в UI.
     */
    fun toUserMessage(context: Context): String {
        return when (this) {
            NoWifiNetwork -> context.getString(R.string.share_error_wifi_only)
            LocalNetworkBlocked -> context.getString(R.string.share_error_local_network_blocked)
            ServerNotFound -> context.getString(R.string.share_error_server_not_found)
            MultipleServersNeedSelection -> context.getString(R.string.share_status_select_server)
            is UploadFailed -> reason ?: context.getString(R.string.share_status_error_title)
            is WebDavStartFailed -> reason ?: context.getString(R.string.share_status_error_title)
            StoragePermissionMissing -> context.getString(R.string.share_status_error_title)
            is UnsupportedStorageOperation -> reason ?: context.getString(R.string.share_status_error_title)
            is WindowsRejectedConnection -> reason ?: context.getString(R.string.share_status_error_title)
            is UnknownError -> reason ?: context.getString(R.string.share_status_error_title)
        }
    }
}

/**
 * Переносит типизированную ошибку WiFiDrop через исключения.
 */
class WiFiDropException(
    val error: WiFiDropError,
    cause: Throwable? = null,
) : IOException(error.toString(), cause)

/**
 * Создаёт исключение из ошибки WiFiDrop.
 */
fun WiFiDropError.asException(cause: Throwable? = null): WiFiDropException {
    return WiFiDropException(this, cause)
}

/**
 * Ищет типизированную ошибку WiFiDrop в цепочке причин.
 */
fun Throwable.findWiFiDropError(): WiFiDropError? {
    if (this is WiFiDropException) {
        return error
    }
    return cause?.findWiFiDropError()
}

/**
 * Преобразует системную ошибку в пользовательскую ошибку WiFiDrop.
 */
fun Throwable.toWiFiDropError(default: WiFiDropError): WiFiDropError {
    return findWiFiDropError()
        ?: if (isLocalNetworkBlockedFailure()) {
            WiFiDropError.LocalNetworkBlocked
        } else {
            default
        }
}

/**
 * Определяет блокировку локального подключения со стороны VPN или сетевой политики.
 */
fun Throwable.isLocalNetworkBlockedFailure(): Boolean {
    if (this is SecurityException) {
        return true
    }
    val messageText = buildString {
        append(message.orEmpty())
        val causeMessage = cause?.message.orEmpty()
        if (causeMessage.isNotBlank()) {
            append(' ')
            append(causeMessage)
        }
    }.lowercase()
    return "eacces" in messageText ||
        "eperm" in messageText ||
        "permission denied" in messageText ||
        "operation not permitted" in messageText ||
        "socket access denied" in messageText
}
