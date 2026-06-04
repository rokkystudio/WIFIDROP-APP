package com.rokkystudio.wifidrop.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import java.io.InputStream

/**
 * Читает URI файлов из Android Share Intent через ContentResolver.
 */
class SharedFileReader(
    context: Context,
) {
    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Содержит сведения о файле, полученном через Share Intent.
     */
    data class SharedFile(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long?,
    )

    /**
     * Извлекает список файлов из ACTION_SEND или ACTION_SEND_MULTIPLE.
     */
    fun readFromIntent(intent: Intent): List<SharedFile> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.readSingleStreamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.readMultipleStreamUris()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            throw WiFiDropError.UploadFailed(reason = "Файлы для передачи не найдены").asException()
        }

        return uris.map { uri -> readSharedFile(uri) }
    }

    /**
     * Открывает входной поток для чтения содержимого файла.
     */
    fun openInputStream(file: SharedFile): InputStream {
        return contentResolver.openInputStream(file.uri)
            ?: throw WiFiDropError.StoragePermissionMissing.asException()
    }

    /**
     * Читает имя и размер файла по URI.
     */
    private fun readSharedFile(uri: Uri): SharedFile {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty().trim()
                if (name.isBlank()) {
                    Log.e(LOG_TAG, "Пустое имя файла для URI: $uri")
                    throw WiFiDropError.UploadFailed(reason = "Не удалось определить имя файла для передачи").asException()
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                return SharedFile(uri = uri, displayName = name, sizeBytes = size)
            }
        }

        Log.e(LOG_TAG, "Не удалось прочитать URI: $uri")
        throw WiFiDropError.UploadFailed(reason = "Не удалось прочитать файл через ContentResolver").asException()
    }

    /**
     * Читает одиночный URI из EXTRA_STREAM.
     */
    private fun Intent.readSingleStreamUri(): Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    /**
     * Читает список URI из EXTRA_STREAM.
     */
    private fun Intent.readMultipleStreamUris(): List<Uri> {
        val items = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return items?.filterNotNull().orEmpty()
    }

    private companion object {
        const val LOG_TAG = "WiFiDrop"
    }
}
