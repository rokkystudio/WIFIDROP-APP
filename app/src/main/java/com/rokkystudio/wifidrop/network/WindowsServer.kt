package com.rokkystudio.wifidrop.network

/**
 * Описывает найденный Windows WiFiDrop Server.
 */
data class WindowsServer(
    val host: String,
    val tcpPort: Int,
    val udpPort: Int?,
    val deviceName: String,
    val protocolVersion: Int,
)
