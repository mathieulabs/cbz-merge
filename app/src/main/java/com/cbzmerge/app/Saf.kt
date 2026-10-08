package com.cbzmerge.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * A chapter file picked by the user.
 * [group] is the subfolder it came from ("" for files at the top level), e.g. "Season 2".
 */
data class Chapter(val uri: Uri, val name: String, val size: Long, val group: String = "")

/** A file inside a folder picked with the system folder picker. [folder] is its subfolder path, or "". */
data class DocEntry(val uri: Uri, val name: String, val size: Long, val folder: String = "")

/**
 * Small helpers around the Storage Access Framework.
 * The app never asks for storage permission: every file and folder comes from the system picker.
 */
object Saf {
    fun nameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment ?: "?"
                val size = if (c.isNull(1)) 0L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "?") to 0L
    }

    fun folderUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun folderName(resolver: ContentResolver, tree: Uri): String =
        runCatching { nameAndSize(resolver, folderUri(tree)).first }.getOrDefault("Selected folder")

    /** Files directly inside the folder (no subfolders). */
    fun listFiles(resolver: ContentResolver, tree: Uri): List<DocEntry> =
        walk(resolver, tree, DocumentsContract.getTreeDocumentId(tree), "", maxDepth = 0)

    /** Files in the folder and its subfolders, up to [maxDepth] levels down. */
    fun listFilesDeep(resolver: ContentResolver, tree: Uri, maxDepth: Int = 2): List<DocEntry> =
        walk(resolver, tree, DocumentsContract.getTreeDocumentId(tree), "", maxDepth)

    private fun walk(resolver: ContentResolver, tree: Uri, docId: String, path: String, maxDepth: Int): List<DocEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val files = mutableListOf<DocEntry>()
        val folders = mutableListOf<Pair<String, String>>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += id to name
                } else {
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    files += DocEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, path)
                }
            }
        }
        if (maxDepth > 0) {
            for ((id, name) in folders) {
                val sub = if (path.isEmpty()) name else "$path/$name"
                files += walk(resolver, tree, id, sub, maxDepth - 1)
            }
        }
        return files
    }

    fun delete(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
}
