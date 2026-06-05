package com.rokkystudio.wifidrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rokkystudio.wifidrop.MainActivity
import com.rokkystudio.wifidrop.R
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import com.rokkystudio.wifidrop.network.AndroidWebDavServer
import com.rokkystudio.wifidrop.network.WifiNetworkProvider
import com.rokkystudio.wifidrop.network.WindowsControlClient
import com.rokkystudio.wifidrop.network.WindowsServer
import com.rokkystudio.wifidrop.storage.StorageRootsRepository
import com.rokkystudio.wifidrop.toWiFiDropError
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AndroidConnectionService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var windowsControlClient: WindowsControlClient
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var webDavServer: AndroidWebDavServer
    private lateinit var stateStore: ConnectionServiceStateStore

    @Volatile
    private var disconnectRequested = false

    override fun onCreate() {
        super.onCreate()
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        windowsControlClient = WindowsControlClient(applicationContext)
        storageRootsRepository = StorageRootsRepository(applicationContext)
        webDavServer = AndroidWebDavServer(applicationContext)
        stateStore = ConnectionServiceStateStore(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                generation.incrementAndGet()
                disconnectRequested = true
                stopActiveConnection()
                publishState(
                    ConnectionServiceSnapshot(
                        phase = ConnectionServicePhase.IDLE,
                        detailMessage = getString(R.string.main_status_disconnected_detail),
                    ),
                )
                stopForegroundCompat()
                stopSelf()
            }

            else -> {
                val server = ServerSpec.fromIntent(intent) ?: run {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val currentGeneration = generation.incrementAndGet()
                disconnectRequested = false
                stopActiveConnection()
                val connectingSnapshot = ConnectionServiceSnapshot(
                    phase = ConnectionServicePhase.CONNECTING,
                    serverName = server.deviceName,
                    serverHost = server.host,
                    serverPort = server.tcpPort,
                    detailMessage = getString(R.string.main_status_connecting_detail, server.host, server.tcpPort),
                )
                startForegroundWithSnapshot(connectingSnapshot)
                publishState(connectingSnapshot)
                executor.execute {
                    runConnection(server, currentGeneration)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        disconnectRequested = true
        stopActiveConnection()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runConnection(server: ServerSpec, generationId: Int) {
        try {
            val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
            ensureCurrent(generationId)

            val publishedRoots = storageRootsRepository.listPublishedRoots()
            if (publishedRoots.isEmpty()) {
                throw WiFiDropError.UnsupportedStorageOperation(
                    getString(R.string.main_error_no_storage_roots_ready),
                ).asException()
            }

            val runningWebDav = webDavServer.start(
                wifiInfo = wifiInfo,
                roots = publishedRoots,
            )
            ensureCurrent(generationId)

            val session = windowsControlClient.connect(
                wifiInfo = wifiInfo,
                server = server.toWindowsServer(),
                webDavEndpoint = WindowsControlClient.WebDavEndpoint(
                    host = runningWebDav.host,
                    port = runningWebDav.port,
                    basePath = runningWebDav.basePath,
                    mountReady = runningWebDav.host.isNotBlank() && runningWebDav.port > 0,
                ),
            )
            ensureCurrent(generationId)

            val connectedSnapshot = ConnectionServiceSnapshot(
                phase = ConnectionServicePhase.CONNECTED,
                serverName = server.deviceName,
                serverHost = server.host,
                serverPort = server.tcpPort,
                driveName = session.driveName,
                webDavPort = runningWebDav.port,
                rootDisplayNames = runningWebDav.rootDisplayNames,
                detailMessage = getString(
                    R.string.main_status_connected_detail,
                    session.driveName,
                    server.host,
                    server.tcpPort,
                ),
            )
            publishState(connectedSnapshot)
            updateForegroundNotification(connectedSnapshot)

            windowsControlClient.runSession(wifiInfo, server.toWindowsServer(), session.clientId)
            webDavServer.stop()

            if (!isCurrent(generationId)) {
                return
            }
            if (disconnectRequested) {
                finishWithSnapshot(
                    ConnectionServiceSnapshot(
                        phase = ConnectionServicePhase.IDLE,
                        detailMessage = getString(R.string.main_status_disconnected_detail),
                    ),
                )
            } else {
                finishWithSnapshot(
                    ConnectionServiceSnapshot(
                        phase = ConnectionServicePhase.IDLE,
                        serverName = server.deviceName,
                        detailMessage = getString(R.string.main_status_session_closed, server.deviceName),
                    ),
                )
            }
        } catch (throwable: Throwable) {
            webDavServer.stop()
            if (!isCurrent(generationId)) {
                return
            }
            if (disconnectRequested) {
                finishWithSnapshot(
                    ConnectionServiceSnapshot(
                        phase = ConnectionServicePhase.IDLE,
                        detailMessage = getString(R.string.main_status_disconnected_detail),
                    ),
                )
                return
            }

            val error = throwable.toWiFiDropError(
                WiFiDropError.WindowsRejectedConnection(getString(R.string.main_status_error_title)),
            )
            finishWithSnapshot(
                ConnectionServiceSnapshot(
                    phase = ConnectionServicePhase.ERROR,
                    serverName = server.deviceName,
                    errorMessage = error.toUserMessage(this),
                ),
            )
        }
    }

    private fun finishWithSnapshot(snapshot: ConnectionServiceSnapshot) {
        publishState(snapshot)
        stopForegroundCompat()
        stopSelf()
    }

    private fun publishState(snapshot: ConnectionServiceSnapshot) {
        stateStore.write(snapshot)
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName),
        )
    }

    private fun startForegroundWithSnapshot(snapshot: ConnectionServiceSnapshot) {
        val notification = buildNotification(snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(snapshot: ConnectionServiceSnapshot) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: ConnectionServiceSnapshot): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, AndroidConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (snapshot.phase) {
            ConnectionServicePhase.CONNECTING -> getString(R.string.service_notification_connecting_title)
            ConnectionServicePhase.CONNECTED -> getString(R.string.service_notification_connected_title)
            ConnectionServicePhase.ERROR -> getString(R.string.main_status_error_title)
            ConnectionServicePhase.IDLE -> getString(R.string.main_status_disconnected)
        }
        val text = when (snapshot.phase) {
            ConnectionServicePhase.CONNECTING -> getString(
                R.string.service_notification_connecting_text,
                snapshot.serverName.orEmpty(),
            )

            ConnectionServicePhase.CONNECTED -> getString(
                R.string.service_notification_connected_text,
                snapshot.serverName.orEmpty(),
            )

            ConnectionServicePhase.ERROR -> snapshot.errorMessage.orEmpty()
            ConnectionServicePhase.IDLE -> getString(R.string.main_status_disconnected_detail)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setOngoing(snapshot.isActive)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.service_action_disconnect),
                disconnectIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopActiveConnection() {
        windowsControlClient.disconnect()
        webDavServer.stop()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun isCurrent(generationId: Int): Boolean = generationId == generation.get()

    private fun ensureCurrent(generationId: Int) {
        if (!isCurrent(generationId)) {
            throw CancellationException()
        }
    }

    private class CancellationException : RuntimeException()

    private data class ServerSpec(
        val host: String,
        val tcpPort: Int,
        val deviceName: String,
    ) {
        fun toWindowsServer(): WindowsServer = WindowsServer(
            host = host,
            tcpPort = tcpPort,
            udpPort = null,
            deviceName = deviceName,
            protocolVersion = 1,
        )

        companion object {
            fun fromIntent(intent: Intent?): ServerSpec? {
                val host = intent?.getStringExtra(EXTRA_SERVER_HOST).orEmpty()
                val port = intent?.getIntExtra(EXTRA_SERVER_PORT, 0) ?: 0
                val deviceName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                if (host.isBlank() || port <= 0 || deviceName.isBlank()) {
                    return null
                }
                return ServerSpec(host = host, tcpPort = port, deviceName = deviceName)
            }
        }
    }

    companion object {
        const val ACTION_STATE_CHANGED = "com.rokkystudio.wifidrop.ACTION_CONNECTION_STATE_CHANGED"

        private const val ACTION_START = "com.rokkystudio.wifidrop.action.START_CONNECTION"
        private const val ACTION_STOP = "com.rokkystudio.wifidrop.action.STOP_CONNECTION"
        private const val EXTRA_SERVER_HOST = "server_host"
        private const val EXTRA_SERVER_PORT = "server_port"
        private const val EXTRA_SERVER_NAME = "server_name"
        private const val NOTIFICATION_CHANNEL_ID = "wifidrop_connection"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_OPEN_APP = 4202
        private const val REQUEST_DISCONNECT = 4203

        fun start(
            context: Context,
            server: WindowsServer,
        ) {
            val intent = Intent(context, AndroidConnectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SERVER_HOST, server.host)
                .putExtra(EXTRA_SERVER_PORT, server.tcpPort)
                .putExtra(EXTRA_SERVER_NAME, server.deviceName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AndroidConnectionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
