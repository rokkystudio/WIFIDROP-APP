package com.rokkystudio.wifidrop.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.rokkystudio.wifidrop.R
import com.rokkystudio.wifidrop.storage.StorageAccessState
import com.rokkystudio.wifidrop.storage.StorageRootEntry
import com.rokkystudio.wifidrop.storage.StorageRootType

/**
 * Показывает список доступных хранилищ Android и обрабатывает действия по выдаче доступа.
 */
class StorageRootsScreen(
    private val context: Context,
    private val listView: ListView,
    private val onRootSelected: (StorageRootEntry) -> Unit,
) {
    fun show(roots: List<StorageRootEntry>) {
        val adapter = object : ArrayAdapter<StorageRootEntry>(
            context,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            roots,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val root = getItem(position) ?: return view
                val titleView = view.findViewById<TextView>(android.R.id.text1)
                val subtitleView = view.findViewById<TextView>(android.R.id.text2)
                titleView.text = root.displayName
                subtitleView.text = buildSubtitle(root)
                return view
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let(onRootSelected)
        }
    }

    private fun buildSubtitle(root: StorageRootEntry): String {
        val typeText = when (root.type) {
            StorageRootType.INTERNAL -> context.getString(R.string.storage_type_internal)
            StorageRootType.REMOVABLE -> context.getString(R.string.storage_type_removable)
        }
        val stateText = when (root.accessState) {
            StorageAccessState.READY -> if (root.isWritable) {
                context.getString(R.string.storage_state_ready_rw)
            } else {
                context.getString(R.string.storage_state_ready_ro)
            }

            StorageAccessState.NEEDS_ALL_FILES_ACCESS -> context.getString(R.string.storage_state_needs_all_files)
            StorageAccessState.NEEDS_TREE_GRANT -> context.getString(R.string.storage_state_needs_tree_grant)
            StorageAccessState.UNAVAILABLE -> context.getString(R.string.storage_state_unavailable)
        }
        return "$typeText • $stateText"
    }
}
