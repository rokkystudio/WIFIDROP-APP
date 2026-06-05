package com.rokkystudio.wifidrop.service

import android.content.Context

enum class ConnectionServicePhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class ConnectionServiceSnapshot(
    val phase: ConnectionServicePhase,
    val serverName: String? = null,
    val serverHost: String? = null,
    val serverPort: Int = 0,
    val driveName: String? = null,
    val webDavPort: Int = 0,
    val rootDisplayNames: List<String> = emptyList(),
    val detailMessage: String? = null,
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = phase == ConnectionServicePhase.CONNECTING || phase == ConnectionServicePhase.CONNECTED
}

class ConnectionServiceStateStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): ConnectionServiceSnapshot {
        return ConnectionServiceSnapshot(
            phase = preferences.getString(KEY_PHASE, null)
                ?.let { value -> enumValues<ConnectionServicePhase>().firstOrNull { it.name == value } }
                ?: ConnectionServicePhase.IDLE,
            serverName = preferences.getString(KEY_SERVER_NAME, null),
            serverHost = preferences.getString(KEY_SERVER_HOST, null),
            serverPort = preferences.getInt(KEY_SERVER_PORT, 0),
            driveName = preferences.getString(KEY_DRIVE_NAME, null),
            webDavPort = preferences.getInt(KEY_WEBDAV_PORT, 0),
            rootDisplayNames = preferences.getString(KEY_ROOTS, null)
                ?.split(ROOT_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            detailMessage = preferences.getString(KEY_DETAIL_MESSAGE, null),
            errorMessage = preferences.getString(KEY_ERROR_MESSAGE, null),
        )
    }

    fun write(snapshot: ConnectionServiceSnapshot) {
        preferences.edit()
            .putString(KEY_PHASE, snapshot.phase.name)
            .putString(KEY_SERVER_NAME, snapshot.serverName)
            .putString(KEY_SERVER_HOST, snapshot.serverHost)
            .putInt(KEY_SERVER_PORT, snapshot.serverPort)
            .putString(KEY_DRIVE_NAME, snapshot.driveName)
            .putInt(KEY_WEBDAV_PORT, snapshot.webDavPort)
            .putString(KEY_ROOTS, snapshot.rootDisplayNames.joinToString(ROOT_SEPARATOR))
            .putString(KEY_DETAIL_MESSAGE, snapshot.detailMessage)
            .putString(KEY_ERROR_MESSAGE, snapshot.errorMessage)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "wifidrop_connection_service_state"
        const val KEY_PHASE = "phase"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_SERVER_HOST = "server_host"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_DRIVE_NAME = "drive_name"
        const val KEY_WEBDAV_PORT = "webdav_port"
        const val KEY_ROOTS = "roots"
        const val KEY_DETAIL_MESSAGE = "detail_message"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val ROOT_SEPARATOR = "\u001F"
    }
}
