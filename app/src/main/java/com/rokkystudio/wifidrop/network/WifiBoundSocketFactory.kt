package com.rokkystudio.wifidrop.network

import android.net.Network
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Создаёт TCP-сокеты, привязанные к выбранной Wi‑Fi сети Android.
 */
class WifiBoundSocketFactory(
    network: Network,
) : SocketFactory() {
    private val delegate: SocketFactory = network.socketFactory

    /**
     * Создаёт неинициализированный сокет.
     */
    override fun createSocket(): Socket = delegate.createSocket()

    /**
     * Создаёт сокет и подключает его к хосту.
     */
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)

    /**
     * Создаёт сокет и подключает его к удалённому адресу.
     */
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return delegate.createSocket(host, port, localHost, localPort)
    }

    /**
     * Создаёт сокет и подключает его к удалённому адресу.
     */
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)

    /**
     * Создаёт сокет с указанным локальным адресом.
     */
    override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        return delegate.createSocket(host, port, localAddress, localPort)
    }
}
