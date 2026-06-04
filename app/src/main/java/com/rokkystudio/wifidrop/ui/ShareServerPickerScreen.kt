package com.rokkystudio.wifidrop.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.rokkystudio.wifidrop.R
import com.rokkystudio.wifidrop.network.WindowsServer

/**
 * Отображает список найденных Windows-серверов для выбора получателя Share.
 */
class ShareServerPickerScreen(
    private val context: Context,
    private val listView: ListView,
    private val onServerSelected: (WindowsServer) -> Unit,
) {
    /**
     * Показывает список серверов и обрабатывает выбор пользователя.
     */
    fun show(servers: List<WindowsServer>) {
        val adapter = object : ArrayAdapter<WindowsServer>(
            context,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            servers,
        ) {
            /**
             * Возвращает строку списка с именем сервера и адресом.
             */
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val server = getItem(position) ?: return view
                val titleView = view.findViewById<TextView>(android.R.id.text1)
                val subtitleView = view.findViewById<TextView>(android.R.id.text2)
                titleView.text = context.getString(R.string.share_server_row_title, server.deviceName)
                subtitleView.text = context.getString(R.string.share_server_row_subtitle, server.host, server.tcpPort)
                return view
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let(onServerSelected)
        }
    }
}
