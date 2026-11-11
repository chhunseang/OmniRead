package com.example.data.audio

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider

/**
 * Shares Studio narrations under a readable name ("Lord of Mysteries · Chapter 1395.m4b")
 * instead of the internal file name. The name rides along as a `name` query parameter.
 */
class SharedAudioProvider : FileProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val base = super.query(uri.buildUpon().clearQuery().build(), projection, selection, selectionArgs, sortOrder)
        val displayName = uri.getQueryParameter("name") ?: return base

        base.use { c ->
            val out = MatrixCursor(c.columnNames, c.count)
            while (c.moveToNext()) {
                out.addRow(Array<Any?>(c.columnCount) { i ->
                    if (c.getColumnName(i) == OpenableColumns.DISPLAY_NAME) displayName
                    else when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(i)
                    }
                })
            }
            return out
        }
    }

    override fun getType(uri: Uri): String? = super.getType(uri.buildUpon().clearQuery().build())
}
