package com.example.wakeupmusic.library.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.example.wakeupmusic.library.db.LibraryRepository

/**
 * Folders added with the system folder picker (F06, the optional "add a folder" button). Picking a folder needs no
 * permission. The read grant is made persistent, so the folder stays in the library after a restart.
 */
object FolderGrants {

    private const val KIND_ROOT = "SAF_ROOT"

    /** Adds a folder as a library source. Returns false if the system will not persist the grant. */
    fun addRoot(context: Context, repo: LibraryRepository, treeUri: Uri): Boolean {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            return false
        }
        repo.addRule(KIND_ROOT, treeUri.toString())
        return true
    }

    /** Removes a source folder and every track indexed from it. */
    fun removeRoot(context: Context, repo: LibraryRepository, treeUri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already released. Nothing to do.
        }
        repo.deleteUnseen(treeUri.toString(), -1L)
        repo.scanRules().filter { it.kind == KIND_ROOT && it.value == treeUri.toString() }.forEach { repo.removeRule(it.id) }
        repo.pruneOrphans()
        repo.rebuildFolders()
    }

    /**
     * A folder path a rule can use, for folders on primary storage only. "primary:Music" becomes
     * "/storage/emulated/0/Music". Returns null for SD cards and other providers.
     */
    fun pathForTree(treeUri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2 || !parts[0].equals("primary", ignoreCase = true)) return null
        return if (parts[1].isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/${parts[1].trimEnd('/')}"
    }
}
