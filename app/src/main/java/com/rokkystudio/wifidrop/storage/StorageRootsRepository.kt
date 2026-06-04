package com.rokkystudio.wifidrop.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import com.rokkystudio.wifidrop.R
import java.io.File
import java.util.Locale

enum class StorageRootType {
    INTERNAL,
    REMOVABLE,
}

enum class StorageAccessState {
    READY,
    NEEDS_ALL_FILES_ACCESS,
    NEEDS_TREE_GRANT,
    UNAVAILABLE,
}

enum class StorageBackendType {
    DIRECT_FILE,
    SAF_TREE,
}

data class StorageRootEntry(
    val id: String,
    val displayName: String,
    val type: StorageRootType,
    val accessState: StorageAccessState,
    val isWritable: Boolean,
    val volumeId: String? = null,
    val storageVolume: StorageVolume? = null,
    val treeUri: Uri? = null,
    val directPath: String? = null,
)

data class PublishedStorageRoot(
    val id: String,
    val displayName: String,
    val backendType: StorageBackendType,
    val isWritable: Boolean,
    val directPath: String? = null,
    val treeUri: Uri? = null,
)

/**
 * Управляет доступными корнями хранилищ Android, которые публикуются через WebDAV.
 * Internal storage идёт через direct file access, removable volumes - через SAF grants.
 */
class StorageRootsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val storageManager = appContext.getSystemService(StorageManager::class.java)

    fun listRoots(): List<StorageRootEntry> {
        val entries = mutableListOf<StorageRootEntry>()
        entries += buildInternalRoot()

        val persistedTreesByVolumeId = loadPersistedTrees().associateBy { it.volumeId }
        val matchedVolumeIds = linkedSetOf<String>()

        storageManager?.storageVolumes
            .orEmpty()
            .filter { !it.isPrimary && it.isRemovable }
            .forEach { volume ->
                val volumeId = volume.uuid?.lowercase(Locale.US)
                val persistedTree = volumeId?.let { persistedTreesByVolumeId[it] }
                if (volumeId != null) {
                    matchedVolumeIds += volumeId
                }
                entries += buildRemovableRoot(volume, persistedTree)
            }

        loadPersistedTrees()
            .filterNot { it.volumeId in matchedVolumeIds }
            .forEach { persistedTree ->
                entries += buildDetachedTreeRoot(persistedTree)
            }

        return makeDisplayNamesUnique(entries)
    }

    fun listPublishedRoots(): List<PublishedStorageRoot> {
        return listRoots()
            .filter { it.accessState == StorageAccessState.READY }
            .mapNotNull { entry ->
                when {
                    entry.directPath != null -> PublishedStorageRoot(
                        id = entry.id,
                        displayName = entry.displayName,
                        backendType = StorageBackendType.DIRECT_FILE,
                        isWritable = entry.isWritable,
                        directPath = entry.directPath,
                    )

                    entry.treeUri != null -> PublishedStorageRoot(
                        id = entry.id,
                        displayName = entry.displayName,
                        backendType = StorageBackendType.SAF_TREE,
                        isWritable = entry.isWritable,
                        treeUri = entry.treeUri,
                    )

                    else -> null
                }
            }
    }

    fun saveTreeUri(treeUri: Uri) {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val volumeId = documentId.substringBefore(':').lowercase(Locale.US)
        val current = preferences.getStringSet(KEY_TREE_URIS, emptySet()).orEmpty().toMutableSet()
        current.removeIf { existing ->
            runCatching {
                DocumentsContract.getTreeDocumentId(Uri.parse(existing))
                    .substringBefore(':')
                    .lowercase(Locale.US) == volumeId
            }.getOrDefault(false)
        }
        current += treeUri.toString()
        preferences.edit().putStringSet(KEY_TREE_URIS, current).apply()
    }

    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    fun buildManageAllFilesAccessIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        )
    }

    fun buildGrantTreeIntent(entry: StorageRootEntry): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && entry.storageVolume != null) {
            entry.storageVolume.createOpenDocumentTreeIntent()
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return intent
    }

    private fun buildInternalRoot(): StorageRootEntry {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val accessState = if (hasAllFilesAccess()) {
            StorageAccessState.READY
        } else {
            StorageAccessState.NEEDS_ALL_FILES_ACCESS
        }
        return StorageRootEntry(
            id = "internal-primary",
            displayName = appContext.getString(R.string.storage_root_internal),
            type = StorageRootType.INTERNAL,
            accessState = accessState,
            isWritable = accessState == StorageAccessState.READY,
            directPath = rootPath,
        )
    }

    private fun buildRemovableRoot(
        volume: StorageVolume,
        persistedTree: PersistedTree?,
    ): StorageRootEntry {
        val volumeId = volume.uuid?.lowercase(Locale.US)
        val label = volume.getDescription(appContext)
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.storage_root_external_fallback)
        val document = persistedTree?.documentFile
        val ready = document?.exists() == true && document.isDirectory
        val accessState = when {
            ready -> StorageAccessState.READY
            persistedTree != null -> StorageAccessState.UNAVAILABLE
            else -> StorageAccessState.NEEDS_TREE_GRANT
        }
        return StorageRootEntry(
            id = "removable-${volumeId ?: label.lowercase(Locale.US)}",
            displayName = label,
            type = StorageRootType.REMOVABLE,
            accessState = accessState,
            isWritable = ready,
            volumeId = volumeId,
            storageVolume = volume,
            treeUri = persistedTree?.uri,
        )
    }

    private fun buildDetachedTreeRoot(
        persistedTree: PersistedTree,
    ): StorageRootEntry {
        val document = persistedTree.documentFile
        val ready = document?.exists() == true && document.isDirectory
        val label = document?.name
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.storage_root_external_detached, persistedTree.volumeId.uppercase(Locale.US))
        return StorageRootEntry(
            id = "detached-${persistedTree.volumeId}",
            displayName = label,
            type = StorageRootType.REMOVABLE,
            accessState = if (ready) StorageAccessState.READY else StorageAccessState.UNAVAILABLE,
            isWritable = ready,
            volumeId = persistedTree.volumeId,
            treeUri = persistedTree.uri,
        )
    }

    private fun loadPersistedTrees(): List<PersistedTree> {
        return preferences.getStringSet(KEY_TREE_URIS, emptySet())
            .orEmpty()
            .mapNotNull { value ->
                runCatching {
                    val uri = Uri.parse(value)
                    val volumeId = DocumentsContract.getTreeDocumentId(uri)
                        .substringBefore(':')
                        .lowercase(Locale.US)
                    PersistedTree(
                        uri = uri,
                        volumeId = volumeId,
                        documentFile = DocumentFile.fromTreeUri(appContext, uri),
                    )
                }.getOrNull()
            }
    }

    private fun makeDisplayNamesUnique(entries: List<StorageRootEntry>): List<StorageRootEntry> {
        val seen = linkedMapOf<String, Int>()
        return entries.map { entry ->
            val baseName = entry.displayName
            val nextIndex = (seen[baseName] ?: 0) + 1
            seen[baseName] = nextIndex
            if (nextIndex == 1) {
                entry
            } else {
                entry.copy(displayName = "$baseName ($nextIndex)")
            }
        }
    }

    private data class PersistedTree(
        val uri: Uri,
        val volumeId: String,
        val documentFile: DocumentFile?,
    )

    private companion object {
        const val PREFERENCES_NAME = "wifidrop_storage_roots"
        const val KEY_TREE_URIS = "tree_uris"
    }
}
