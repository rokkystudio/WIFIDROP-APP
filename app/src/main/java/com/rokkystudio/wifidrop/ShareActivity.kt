package com.rokkystudio.wifidrop

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rokkystudio.wifidrop.network.WifiDropScanner
import com.rokkystudio.wifidrop.network.WifiNetworkProvider
import com.rokkystudio.wifidrop.network.WindowsServer
import com.rokkystudio.wifidrop.network.WindowsUploadClient
import com.rokkystudio.wifidrop.storage.SharedFileReader
import com.rokkystudio.wifidrop.ui.ShareServerPickerScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Принимает Share Intent, ищет Windows-серверы WiFiDrop в локальной Wi‑Fi сети
 * и загружает выбранные файлы в upload endpoint Windows.
 */
class ShareActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var serverListView: ListView
    private lateinit var retryButton: Button
    private lateinit var closeButton: Button

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var wifiDropScanner: WifiDropScanner
    private lateinit var sharedFileReader: SharedFileReader
    private lateinit var windowsUploadClient: WindowsUploadClient
    private lateinit var serverPickerScreen: ShareServerPickerScreen

    private var sharedFiles: List<SharedFileReader.SharedFile> = emptyList()
    private var lastWifiInfo: WifiNetworkProvider.WifiNetworkInfo? = null

    /**
     * Создаёт экран обработки Share Intent и запускает сценарий передачи.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_share)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.shareRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindDependencies()
        bindActions()
        startShareFlow()
    }

    /**
     * Останавливает фоновый executor при закрытии экрана.
     */
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Привязывает view из layout.
     */
    private fun bindViews() {
        statusText = findViewById(R.id.shareStatusText)
        detailText = findViewById(R.id.shareDetailText)
        progressBar = findViewById(R.id.shareProgressBar)
        serverListView = findViewById(R.id.serverListView)
        retryButton = findViewById(R.id.retryButton)
        closeButton = findViewById(R.id.closeButton)
    }

    /**
     * Создаёт зависимости Share Activity.
     */
    private fun bindDependencies() {
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        wifiDropScanner = WifiDropScanner()
        sharedFileReader = SharedFileReader(applicationContext)
        windowsUploadClient = WindowsUploadClient(sharedFileReader)
        serverPickerScreen = ShareServerPickerScreen(
            context = this,
            listView = serverListView,
            onServerSelected = ::uploadToServer,
        )
    }

    /**
     * Назначает действия для кнопок экрана.
     */
    private fun bindActions() {
        retryButton.setOnClickListener {
            startShareFlow()
        }
        closeButton.setOnClickListener {
            finish()
        }
    }

    /**
     * Запускает чтение Share Intent и поиск Windows-серверов.
     */
    private fun startShareFlow() {
        showLoading(getString(R.string.share_status_preparing), null)
        executor.execute {
            try {
                sharedFiles = sharedFileReader.readFromIntent(intent)
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                lastWifiInfo = wifiInfo
                runOnUiThread {
                    showLoading(getString(R.string.share_status_scanning), null)
                }
                val servers = wifiDropScanner.scan(wifiInfo)
                runOnUiThread {
                    handleDiscoveredServers(servers)
                }
            } catch (throwable: Throwable) {
                val error = throwable.toWiFiDropError(
                    WiFiDropError.UnknownError(getString(R.string.share_status_error_title)),
                )
                runOnUiThread {
                    handleError(error)
                }
            }
        }
    }

    /**
     * Обрабатывает найденные Windows-серверы и продолжает сценарий передачи.
     */
    private fun handleDiscoveredServers(servers: List<WindowsServer>) {
        when {
            servers.isEmpty() -> handleError(WiFiDropError.ServerNotFound)
            servers.size == 1 -> uploadToServer(servers.single())
            else -> {
                statusText.text = getString(R.string.share_status_select_server)
                detailText.text = null
                progressBar.visibility = View.GONE
                retryButton.visibility = View.GONE
                closeButton.visibility = View.VISIBLE
                serverListView.visibility = View.VISIBLE
                serverPickerScreen.show(servers)
            }
        }
    }

    /**
     * Выполняет отправку файлов на выбранный Windows-сервер.
     */
    private fun uploadToServer(server: WindowsServer) {
        val wifiInfo = lastWifiInfo
        if (wifiInfo == null) {
            handleError(WiFiDropError.NoWifiNetwork)
            return
        }

        showLoading(
            getString(R.string.share_status_uploading, server.deviceName),
            null,
        )
        executor.execute {
            try {
                val uploadedCount = windowsUploadClient.uploadFiles(wifiInfo, server, sharedFiles)
                runOnUiThread {
                    val message = if (uploadedCount == 1) {
                        getString(R.string.share_result_single)
                    } else {
                        getString(R.string.share_result_multiple, uploadedCount)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (throwable: Throwable) {
                val error = throwable.toWiFiDropError(
                    WiFiDropError.UploadFailed(reason = getString(R.string.share_status_error_title)),
                )
                runOnUiThread {
                    handleError(error)
                }
            }
        }
    }

    /**
     * Отображает ошибку в соответствии с правилами Share MVP.
     */
    private fun handleError(error: WiFiDropError) {
        when (error) {
            WiFiDropError.NoWifiNetwork -> {
                Toast.makeText(this, getString(R.string.share_error_wifi_only), Toast.LENGTH_LONG).show()
                finish()
            }
            WiFiDropError.LocalNetworkBlocked -> {
                Toast.makeText(this, getString(R.string.share_error_local_network_blocked), Toast.LENGTH_LONG).show()
                finish()
            }
            else -> {
                statusText.text = getString(R.string.share_status_error_title)
                detailText.text = error.toUserMessage(this)
                progressBar.visibility = View.GONE
                serverListView.visibility = View.GONE
                retryButton.visibility = if (error == WiFiDropError.ServerNotFound) View.VISIBLE else View.GONE
                closeButton.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Показывает состояние ожидания во время поиска или отправки.
     */
    private fun showLoading(status: String, detail: String?) {
        statusText.text = status
        detailText.text = detail
        progressBar.visibility = View.VISIBLE
        serverListView.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.GONE
    }
}
