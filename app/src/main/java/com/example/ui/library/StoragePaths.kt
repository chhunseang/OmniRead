package com.example.ui.library

import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder

/** Turns SAF content:// URIs into paths people recognise ("Internal storage/Books/x.epub"). */
object StoragePaths {

    fun readable(uriString: String?): String {
        if (uriString.isNullOrBlank()) return "Unknown location"
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return uri.path.orEmpty()
        // content://authority/tree/<treeId>/document/<docId>  or  .../document/<docId>  or  .../tree/<treeId>
        val segments = uri.pathSegments
        val docIndex = segments.indexOf("document")
        val treeIndex = segments.indexOf("tree")
        val docId = when {
            docIndex >= 0 && docIndex + 1 < segments.size -> segments[docIndex + 1]
            treeIndex >= 0 && treeIndex + 1 < segments.size -> segments[treeIndex + 1]
            else -> runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        } ?: return decode(uri.lastPathSegment ?: uriString)

        val volume = docId.substringBefore(':', "")
        val path = docId.substringAfter(':', docId)
        val root = when {
            volume.equals("primary", ignoreCase = true) -> "Internal storage"
            volume.equals("home", ignoreCase = true) -> "Documents"
            volume.equals("downloads", ignoreCase = true) || volume.equals("raw", ignoreCase = true) -> "Downloads"
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "SD card"
            volume.isBlank() -> ""
            else -> volume
        }
        val cleanPath = if (path.startsWith("/storage/emulated/0/")) path.removePrefix("/storage/emulated/0/") else path
        return listOf(root, cleanPath).filter { it.isNotBlank() }.joinToString("/")
    }

    /** True when [fileUri] lives inside the picked folder tree [treeUri] (any depth). */
    fun isInside(fileUri: String, treeUri: String): Boolean {
        val folder = readable(treeUri).trimEnd('/')
        return folder.isNotBlank() && readable(fileUri).startsWith("$folder/")
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576f)
        else -> "%.2f GB".format(bytes / 1073741824f)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
