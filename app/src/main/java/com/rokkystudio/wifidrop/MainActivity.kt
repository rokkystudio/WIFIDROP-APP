package com.rokkystudio.wifidrop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rokkystudio.wifidrop.network.AndroidWebDavServer
import com.rokkystudio.wifidrop.network.WifiDropScanner
import com.rokkystudio.wifidrop.network.WifiNetworkProvider
import com.rokkystudio.wifidrop.network.WindowsControlClient
import com.rokkystudio.wifidrop.network.WindowsServer
import com.rokkystudio.wifidrop.storage.StorageAccessState
import com.rokkystudio.wifidrop.storage.StorageRootEntry
import com.rokkystudio.wifidrop.storage.StorageRootsRepository
import com.rokkystudio.wifidrop.ui.ShareServerPickerScreen
import com.rokkystudio.wifidrop.ui.StorageRootsScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Выполняет авто-поиск Windows-серверов и удерживает активное Android -> Windows
 * подключение, чтобы устройство отображалось в Windows-приложении.
 */
class MainActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var storageRootsListView: ListView
    private lateinit var serverListView: ListView
    private lateinit var retryButton: android.widget.Button
    private lateinit var disconnectButton: android.widget.Button

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var wifiDropScanner: WifiDropScanner
    private lateinit var windowsControlClient: WindowsControlClient
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var webDavServer: AndroidWebDavServer
    private lateinit var serverPickerScreen: ShareServerPickerScreen
    private lateinit var storageRootsScreen: StorageRootsScreen

    private var lastWifiInfo: WifiNetworkProvider.WifiNetworkInfo? = null
    private var lastStorageRoots: List<StorageRootEntry> = emptyList()
    @Volatile
    private var disconnectRequested = false
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: run {
                refreshStorageRootsState()
                return@registerForActivityResult
            }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            storageRootsRepository.saveTreeUri(uri)
            refreshStorageRootsState()
        }
    private val manageAllFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStorageRootsState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        bindDependencies()
        bindActions()
        refreshStorageRootsState()
        startDiscoveryFlow()
    }

    override fun onDestroy() {
        disconnectRequested = true
        windowsControlClient.disconnect()
        webDavServer.stop()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.mainStatusText)
        statsText = findViewById(R.id.mainStatsText)
        detailText = findViewById(R.id.mainDetailText)
        progressBar = findViewById(R.id.mainProgressBar)
        storageRootsListView = findViewById(R.id.mainStorageRootsListView)
        serverListView = findViewById(R.id.mainServerListView)
        retryButton = findViewById(R.id.mainRetryButton)
        disconnectButton = findViewById(R.id.mainDisconnectButton)
    }

    private fun bindDependencies() {
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        wifiDropScanner = WifiDropScanner()
        windowsControlClient = WindowsControlClient(applicationContext)
        storageRootsRepository = StorageRootsRepository(applicationContext)
        webDavServer = AndroidWebDavServer(applicationContext)
        serverPickerScreen = ShareServerPickerScreen(
            context = this,
            listView = serverListView,
            onServerSelected = ::connectToServer,
        )
        storageRootsScreen = StorageRootsScreen(
            context = this,
            listView = storageRootsListView,
            onRootSelected = ::handleStorageRootSelected,
        )
    }

    private fun bindActions() {
        retryButton.setOnClickListener {
            startDiscoveryFlow()
        }
        disconnectButton.setOnClickListener {
            disconnectRequested = true
            windowsControlClient.disconnect()
            webDavServer.stop()
            showDisconnected()
        }
    }

    private fun startDiscoveryFlow() {
        disconnectRequested = false
        windowsControlClient.disconnect()
        webDavServer.stop()
        refreshStorageRootsState()
        showLoading(getString(R.string.main_status_preparing), null, null)
        executor.execute {
            try {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                lastWifiInfo = wifiInfo
                runOnUiThread {
                    showLoading(
                        getString(R.string.main_status_scanning),
                        buildNetworkStats(
                            wifiInfo = wifiInfo,
                            currentHost = null,
                            scannedHosts = 0,
                            totalHosts = 0,
                            foundServers = 0,
                        ),
                        getString(R.string.main_status_scanning_detail),
                    )
                }
                val servers = wifiDropScanner.scan(wifiInfo) { progress ->
                    runOnUiThread {
                        statusText.text = getString(R.string.main_status_scanning)
                        statsText.text = buildNetworkStats(
                            wifiInfo = wifiInfo,
                            currentHost = progress.currentHost,
                            scannedHosts = progress.scannedHosts,
                            totalHosts = progress.totalHosts,
                            foundServers = progress.foundServers,
                        )
                        detailText.text = getString(R.string.main_status_scanning_host, progress.currentHost)
                    }
                }
                runOnUiThread {
                    handleDiscoveredServers(wifiInfo, servers)
                }
            } catch (throwable: Throwable) {
                val error = throwable.toWiFiDropError(
                    WiFiDropError.UnknownError(getString(R.string.main_status_error_title)),
                )
                runOnUiThread {
                    handleError(error)
                }
            }
        }
    }

    private fun handleDiscoveredServers(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        servers: List<WindowsServer>,
    ) {
        if (servers.isEmpty()) {
            handleError(WiFiDropError.ServerNotFound)
            return
        }

        statusText.text = getString(R.string.main_status_select_server)
        statsText.text = buildNetworkStats(
            wifiInfo = wifiInfo,
            currentHost = null,
            scannedHosts = null,
            totalHosts = null,
            foundServers = servers.size,
        )
        detailText.text = getString(R.string.main_status_select_server_detail, servers.size)
        progressBar.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        disconnectButton.visibility = View.GONE
        serverListView.visibility = View.VISIBLE
        serverPickerScreen.show(servers)
    }

    private fun connectToServer(server: WindowsServer) {
        val wifiInfo = lastWifiInfo
        if (wifiInfo == null) {
            handleError(WiFiDropError.NoWifiNetwork)
            return
        }
        val publishedRoots = storageRootsRepository.listPublishedRoots()
        if (publishedRoots.isEmpty()) {
            handleError(WiFiDropError.UnsupportedStorageOperation(getString(R.string.main_error_no_storage_roots_ready)))
            return
        }

        disconnectRequested = false
        showLoading(
            getString(R.string.main_status_connecting, server.deviceName),
            buildNetworkStats(
                wifiInfo = wifiInfo,
                currentHost = server.host,
                scannedHosts = null,
                totalHosts = null,
                foundServers = null,
            ),
            getString(R.string.main_status_connecting_detail, server.host, server.tcpPort),
        )
        executor.execute {
            try {
                val runningWebDav = webDavServer.start(wifiInfo, publishedRoots)
                val mountReady = runningWebDav.host.isNotBlank() && runningWebDav.port > 0
                val session = windowsControlClient.connect(
                    wifiInfo = wifiInfo,
                    server = server,
                    webDavEndpoint = WindowsControlClient.WebDavEndpoint(
                        host = runningWebDav.host,
                        port = runningWebDav.port,
                        basePath = runningWebDav.basePath,
                        mountReady = mountReady,
                    ),
                )
                runOnUiThread {
                    showConnected(server, session.driveName, runningWebDav)
                }
                windowsControlClient.runSession(wifiInfo, server, session.clientId)
                webDavServer.stop()
                runOnUiThread {
                    if (disconnectRequested) {
                        showDisconnected()
                    } else {
                        handleError(
                            WiFiDropError.WindowsRejectedConnection(
                                getString(R.string.main_status_session_closed, server.deviceName),
                            ),
                        )
                    }
                }
            } catch (throwable: Throwable) {
                webDavServer.stop()
                if (disconnectRequested) {
                    runOnUiThread { showDisconnected() }
                    return@execute
                }

                val error = throwable.toWiFiDropError(
                    WiFiDropError.WindowsRejectedConnection(getString(R.string.main_status_error_title)),
                )
                runOnUiThread {
                    handleError(error)
                }
            }
        }
    }

    private fun showConnected(
        server: WindowsServer,
        driveName: String,
        runningWebDav: AndroidWebDavServer.RunningServer,
    ) {
        statusText.text = getString(R.string.main_status_connected, server.deviceName)
        statsText.text = buildConnectedStats(server, runningWebDav)
        detailText.text = getString(
            R.string.main_status_connected_detail,
            driveName,
            server.host,
            server.tcpPort,
        )
        progressBar.visibility = View.GONE
        serverListView.visibility = View.GONE
        retryButton.visibility = View.GONE
        disconnectButton.visibility = View.VISIBLE
    }

    private fun showDisconnected() {
        statusText.text = getString(R.string.main_status_disconnected)
        statsText.text = buildIdleStats()
        detailText.text = getString(R.string.main_status_disconnected_detail)
        progressBar.visibility = View.GONE
        serverListView.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        disconnectButton.visibility = View.GONE
    }

    private fun handleError(error: WiFiDropError) {
        statusText.text = getString(R.string.main_status_error_title)
        statsText.text = buildErrorStats()
        detailText.text = error.toUserMessage(this)
        progressBar.visibility = View.GONE
        serverListView.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        disconnectButton.visibility = View.GONE
    }

    private fun showLoading(status: String, stats: String?, detail: String?) {
        statusText.text = status
        statsText.text = stats
        detailText.text = detail
        progressBar.visibility = View.VISIBLE
        serverListView.visibility = View.GONE
        retryButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
    }

    private fun buildNetworkStats(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        currentHost: String?,
        scannedHosts: Int?,
        totalHosts: Int?,
        foundServers: Int?,
    ): String {
        val lines = mutableListOf(
            getString(R.string.main_stats_wifi_ip, wifiInfo.ipv4Address.hostAddress.orEmpty()),
            getString(R.string.main_stats_prefix, wifiInfo.prefixLength),
            getString(R.string.main_stats_port, SERVER_SCAN_PORT),
        )
        lines += buildStorageStatsLines()

        if (scannedHosts != null && totalHosts != null && totalHosts > 0) {
            lines += getString(R.string.main_stats_progress, scannedHosts, totalHosts)
        }
        if (currentHost != null) {
            lines += getString(R.string.main_stats_current_host, currentHost)
        }
        if (foundServers != null) {
            lines += getString(R.string.main_stats_found_servers, foundServers)
        }

        return lines.joinToString("\n")
    }

    private fun buildConnectedStats(
        server: WindowsServer,
        runningWebDav: AndroidWebDavServer.RunningServer,
    ): String {
        val wifiInfo = lastWifiInfo
        val lines = mutableListOf(
            getString(R.string.main_stats_server_host, server.host),
            getString(R.string.main_stats_server_port, server.tcpPort),
            getString(R.string.main_stats_webdav_port, runningWebDav.port),
        )
        lines += runningWebDav.rootDisplayNames.map { rootName ->
            getString(R.string.main_stats_storage_root_item, rootName)
        }
        if (wifiInfo != null) {
            lines.add(0, getString(R.string.main_stats_wifi_ip, wifiInfo.ipv4Address.hostAddress.orEmpty()))
            lines.add(1, getString(R.string.main_stats_prefix, wifiInfo.prefixLength))
        }
        return lines.joinToString("\n")
    }

    private fun buildErrorStats(): String? {
        val wifiInfo = lastWifiInfo ?: return null
        return buildNetworkStats(
            wifiInfo = wifiInfo,
            currentHost = null,
            scannedHosts = null,
            totalHosts = null,
            foundServers = null,
        )
    }

    private fun buildIdleStats(): String? {
        val lines = buildStorageStatsLines()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun refreshStorageRootsState() {
        lastStorageRoots = storageRootsRepository.listRoots()
        storageRootsScreen.show(lastStorageRoots)
    }

    private fun handleStorageRootSelected(root: StorageRootEntry) {
        when (root.accessState) {
            StorageAccessState.READY -> Unit
            StorageAccessState.NEEDS_ALL_FILES_ACCESS -> {
                manageAllFilesAccessLauncher.launch(storageRootsRepository.buildManageAllFilesAccessIntent())
            }

            StorageAccessState.NEEDS_TREE_GRANT -> {
                openDocumentTreeLauncher.launch(storageRootsRepository.buildGrantTreeIntent(root))
            }

            StorageAccessState.UNAVAILABLE -> {
                handleError(
                    WiFiDropError.UnsupportedStorageOperation(getString(R.string.storage_state_unavailable)),
                )
            }
        }
    }

    private fun buildStorageStatsLines(): List<String> {
        if (lastStorageRoots.isEmpty()) {
            return emptyList()
        }

        val readyRoots = lastStorageRoots.filter { it.accessState == StorageAccessState.READY }
        val pendingRoots = lastStorageRoots.count { it.accessState != StorageAccessState.READY }
        val lines = mutableListOf(
            getString(R.string.main_stats_storage_ready, readyRoots.size, lastStorageRoots.size),
        )
        if (pendingRoots > 0) {
            lines += getString(R.string.main_stats_storage_pending, pendingRoots)
        }
        lines += readyRoots.map { root ->
            getString(R.string.main_stats_storage_root_item, root.displayName)
        }
        return lines
    }

    private companion object {
        const val SERVER_SCAN_PORT = 49231
    }
}
