package com.rokkystudio.wifidrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rokkystudio.wifidrop.network.WifiDropScanner
import com.rokkystudio.wifidrop.network.WifiNetworkProvider
import com.rokkystudio.wifidrop.network.WindowsServer
import com.rokkystudio.wifidrop.storage.StorageAccessState
import com.rokkystudio.wifidrop.storage.StorageRootEntry
import com.rokkystudio.wifidrop.storage.StorageRootsRepository
import com.rokkystudio.wifidrop.service.AndroidConnectionService
import com.rokkystudio.wifidrop.service.ConnectionServicePhase
import com.rokkystudio.wifidrop.service.ConnectionServiceSnapshot
import com.rokkystudio.wifidrop.service.ConnectionServiceStateStore
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
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var connectionStateStore: ConnectionServiceStateStore
    private lateinit var serverPickerScreen: ShareServerPickerScreen
    private lateinit var storageRootsScreen: StorageRootsScreen

    private var lastWifiInfo: WifiNetworkProvider.WifiNetworkInfo? = null
    private var lastStorageRoots: List<StorageRootEntry> = emptyList()
    private var discoveredServers: List<WindowsServer> = emptyList()
    private var scanGeneration = 0
    private var waitingForInitialStorageGrant = false
    private var connectionReceiverRegistered = false
    @Volatile
    private var disconnectRequested = false
    private val connectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderConnectionState(connectionStateStore.read())
        }
    }
    private val manageAllFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (storageRootsRepository.hasAllFilesAccess()) {
                waitingForInitialStorageGrant = false
                continueAfterStorageAccess()
            } else {
                Toast.makeText(this, R.string.main_storage_access_denied, Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
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
        if (ensureStorageAccessGranted()) {
            continueAfterStorageAccess()
        }
    }

    override fun onStart() {
        super.onStart()
        registerConnectionStateReceiver()
        val snapshot = connectionStateStore.read()
        if (snapshot.phase != ConnectionServicePhase.IDLE) {
            renderConnectionState(snapshot)
        }
    }

    override fun onStop() {
        if (connectionReceiverRegistered) {
            unregisterReceiver(connectionStateReceiver)
            connectionReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
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
        storageRootsRepository = StorageRootsRepository(applicationContext)
        connectionStateStore = ConnectionServiceStateStore(applicationContext)
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
            AndroidConnectionService.stop(this)
            showDisconnected()
        }
    }

    private fun continueAfterStorageAccess() {
        refreshStorageRootsState()
        val snapshot = connectionStateStore.read()
        if (snapshot.isActive) {
            renderConnectionState(snapshot)
        } else {
            startDiscoveryFlow()
        }
    }

    private fun ensureStorageAccessGranted(): Boolean {
        if (storageRootsRepository.hasAllFilesAccess()) {
            return true
        }
        waitingForInitialStorageGrant = true
        manageAllFilesAccessLauncher.launch(storageRootsRepository.buildManageAllFilesAccessIntent())
        return false
    }

    private fun startDiscoveryFlow() {
        val snapshot = connectionStateStore.read()
        if (snapshot.isActive) {
            renderConnectionState(snapshot)
            return
        }
        if (!ensureStorageAccessGranted()) {
            return
        }
        val generation = ++scanGeneration
        discoveredServers = emptyList()
        disconnectRequested = false
        refreshStorageRootsState()
        showLoading(getString(R.string.main_status_preparing), null, null)
        executor.execute {
            try {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                lastWifiInfo = wifiInfo
                runOnUiThread {
                    if (generation != scanGeneration) {
                        return@runOnUiThread
                    }
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
                val servers = wifiDropScanner.scan(
                    wifiInfo = wifiInfo,
                    onProgress = { progress ->
                        runOnUiThread {
                            if (generation != scanGeneration) {
                                return@runOnUiThread
                            }
                            renderScanningState(wifiInfo, progress, discoveredServers)
                        }
                    },
                    onServerFound = { servers ->
                        runOnUiThread {
                            if (generation != scanGeneration) {
                                return@runOnUiThread
                            }
                            discoveredServers = servers
                            serverPickerScreen.show(servers)
                            serverListView.visibility = View.VISIBLE
                            statsText.text = buildNetworkStats(
                                wifiInfo = wifiInfo,
                                currentHost = null,
                                scannedHosts = null,
                                totalHosts = null,
                                foundServers = servers.size,
                            )
                        }
                    },
                    shouldContinue = {
                        generation == scanGeneration &&
                            !disconnectRequested &&
                            connectionStateStore.read().phase == ConnectionServicePhase.IDLE
                    },
                )
                runOnUiThread {
                    if (generation != scanGeneration) {
                        return@runOnUiThread
                    }
                    handleDiscoveredServers(wifiInfo, servers)
                }
            } catch (throwable: Throwable) {
                val error = throwable.toWiFiDropError(
                    WiFiDropError.UnknownError(getString(R.string.main_status_error_title)),
                )
                runOnUiThread {
                    if (generation != scanGeneration) {
                        return@runOnUiThread
                    }
                    handleError(error)
                }
            }
        }
    }

    private fun renderScanningState(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        progress: WifiDropScanner.ScanProgress,
        servers: List<WindowsServer>,
    ) {
        statusText.text = getString(R.string.main_status_scanning)
        statsText.text = buildNetworkStats(
            wifiInfo = wifiInfo,
            currentHost = progress.currentHost,
            scannedHosts = progress.scannedHosts,
            totalHosts = progress.totalHosts,
            foundServers = progress.foundServers,
        )
        detailText.text = getString(R.string.main_status_scanning_host, progress.currentHost)
        progressBar.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        serverListView.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
        if (servers.isNotEmpty()) {
            serverPickerScreen.show(servers)
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

        discoveredServers = servers
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

        scanGeneration++
        disconnectRequested = false
        AndroidConnectionService.start(this, server)
        renderConnectionState(
            ConnectionServiceSnapshot(
                phase = ConnectionServicePhase.CONNECTING,
                serverName = server.deviceName,
                serverHost = server.host,
                serverPort = server.tcpPort,
                detailMessage = getString(R.string.main_status_connecting_detail, server.host, server.tcpPort),
            ),
        )
    }

    private fun showDisconnected(detailMessage: String? = null) {
        statusText.text = getString(R.string.main_status_disconnected)
        statsText.text = buildIdleStats()
        detailText.text = detailMessage ?: getString(R.string.main_status_disconnected_detail)
        progressBar.visibility = View.GONE
        serverListView.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        disconnectButton.visibility = View.GONE
    }

    private fun renderConnectionState(snapshot: ConnectionServiceSnapshot) {
        when (snapshot.phase) {
            ConnectionServicePhase.IDLE -> showDisconnected(snapshot.detailMessage)
            ConnectionServicePhase.CONNECTING -> {
                showLoading(
                    getString(R.string.main_status_connecting, snapshot.serverName.orEmpty()),
                    buildConnectingStats(snapshot),
                    snapshot.detailMessage,
                )
            }

            ConnectionServicePhase.CONNECTED -> {
                statusText.text = getString(R.string.main_status_connected, snapshot.serverName.orEmpty())
                statsText.text = buildConnectedStats(snapshot)
                detailText.text = snapshot.detailMessage
                progressBar.visibility = View.GONE
                serverListView.visibility = View.GONE
                retryButton.visibility = View.GONE
                disconnectButton.visibility = View.VISIBLE
            }

            ConnectionServicePhase.ERROR -> {
                handleError(
                    WiFiDropError.WindowsRejectedConnection(
                        snapshot.errorMessage ?: getString(R.string.main_status_error_title),
                    ),
                )
            }
        }
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

    private fun buildConnectingStats(snapshot: ConnectionServiceSnapshot): String? {
        val lines = mutableListOf<String>()
        val wifiInfo = lastWifiInfo
        if (wifiInfo != null) {
            lines += getString(R.string.main_stats_wifi_ip, wifiInfo.ipv4Address.hostAddress.orEmpty())
            lines += getString(R.string.main_stats_prefix, wifiInfo.prefixLength)
        }
        if (!snapshot.serverHost.isNullOrBlank()) {
            lines += getString(R.string.main_stats_server_host, snapshot.serverHost)
        }
        if (snapshot.serverPort > 0) {
            lines += getString(R.string.main_stats_server_port, snapshot.serverPort)
        }
        lines += buildStorageStatsLines()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun buildConnectedStats(snapshot: ConnectionServiceSnapshot): String {
        val lines = mutableListOf(
            getString(R.string.main_stats_server_host, snapshot.serverHost.orEmpty()),
            getString(R.string.main_stats_server_port, snapshot.serverPort),
            getString(R.string.main_stats_webdav_port, snapshot.webDavPort),
        )
        lines += snapshot.rootDisplayNames.map { rootName ->
            getString(R.string.main_stats_storage_root_item, rootName)
        }
        val wifiInfo = lastWifiInfo
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
            StorageAccessState.NEEDS_ALL_FILES_ACCESS,
            StorageAccessState.NEEDS_TREE_GRANT -> {
                manageAllFilesAccessLauncher.launch(storageRootsRepository.buildManageAllFilesAccessIntent())
            }

            StorageAccessState.UNAVAILABLE -> {
                handleError(
                    WiFiDropError.UnsupportedStorageOperation(getString(R.string.storage_state_unavailable)),
                )
            }
        }
    }

    private fun buildStorageStatsLines(): List<String> {
        if (lastStorageRoots.isEmpty() || !storageRootsRepository.hasAllFilesAccess()) {
            return emptyList()
        }

        val readyRoots = lastStorageRoots.filter { it.accessState == StorageAccessState.READY }
        val lines = mutableListOf(
            getString(R.string.main_stats_storage_ready, readyRoots.size, lastStorageRoots.size),
        )
        lines += readyRoots.map { root ->
            getString(R.string.main_stats_storage_root_item, root.displayName)
        }
        return lines
    }

    private companion object {
        const val SERVER_SCAN_PORT = 49231
    }

    private fun registerConnectionStateReceiver() {
        if (connectionReceiverRegistered) {
            return
        }
        val filter = IntentFilter(AndroidConnectionService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(connectionStateReceiver, filter)
        }
        connectionReceiverRegistered = true
    }
}
