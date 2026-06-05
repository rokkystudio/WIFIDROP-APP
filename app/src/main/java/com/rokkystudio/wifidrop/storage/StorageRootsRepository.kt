package com.rokkystudio.wifidrop.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
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
 * Internal storage и removable volumes публикуются через direct file access
 * после выдачи MANAGE_EXTERNAL_STORAGE.
 */
class StorageRootsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val storageManager = appContext.getSystemService(StorageManager::class.java)

    fun listRoots(): List<StorageRootEntry> {
        val entries = mutableListOf<StorageRootEntry>()
        entries += buildInternalRoot()

        storageManager?.storageVolumes
            .orEmpty()
            .filter { !it.isPrimary && it.isRemovable }
            .forEach { volume ->
                entries += buildRemovableRoot(volume)
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

    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    fun buildManageAllFilesAccessIntent(): Intent {
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        )
        return if (packageIntent.resolveActivity(appContext.packageManager) != null) {
            packageIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
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
    ): StorageRootEntry {
        val volumeId = volume.uuid?.lowercase(Locale.US)
        val label = volume.getDescription(appContext)
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.storage_root_external_fallback)
        val rootDirectory = resolveVolumeDirectory(volume)
        val hasAllFilesAccess = hasAllFilesAccess()
        val ready = hasAllFilesAccess && rootDirectory?.isDirectory == true
        val accessState = when {
            ready -> StorageAccessState.READY
            !hasAllFilesAccess -> StorageAccessState.NEEDS_ALL_FILES_ACCESS
            else -> StorageAccessState.UNAVAILABLE
        }
        return StorageRootEntry(
            id = "removable-${volumeId ?: label.lowercase(Locale.US)}",
            displayName = label,
            type = StorageRootType.REMOVABLE,
            accessState = accessState,
            isWritable = ready && rootDirectory?.canWrite() == true,
            volumeId = volumeId,
            directPath = rootDirectory?.absolutePath,
        )
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

    private fun resolveVolumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.let { directory ->
                if (directory.exists() && directory.isDirectory) {
                    return directory
                }
            }
        }

        val volumeId = volume.uuid?.lowercase(Locale.US)
        return appContext.getExternalFilesDirs(null)
            .orEmpty()
            .mapNotNull(::resolveVolumeRootFromAppExternalDir)
            .firstOrNull { candidate ->
                when {
                    !candidate.exists() || !candidate.isDirectory -> false
                    volumeId != null -> candidate.absolutePath.lowercase(Locale.US).contains(volumeId)
                    else -> Environment.isExternalStorageRemovable(candidate)
                }
            }
    }

    private fun resolveVolumeRootFromAppExternalDir(appExternalDir: File?): File? {
        var current = appExternalDir?.absoluteFile ?: return null
        while (true) {
            val parent = current.parentFile ?: return null
            if (current.name == "Android") {
                return parent.takeIf { it.exists() && it.isDirectory }
            }
            current = parent
        }
    }
}
