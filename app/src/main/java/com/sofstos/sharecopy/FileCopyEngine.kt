package com.sofstos.sharecopy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

data class CopyResult(
    val total: Int,
    val succeeded: Int,
    val notRemovedAfterMove: List<String>,
    val errors: List<String>
)

object FileCopyEngine {

    fun run(
        context: Context,
        sources: List<Uri>,
        destination: Destination,
        move: Boolean
    ): CopyResult {
        var succeeded = 0
        val notRemoved = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (uri in sources) {
            val displayName = queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
            try {
                val ok = when (destination.type) {
                    DestType.SAF -> writeToSaf(context, uri, destination, displayName)
                    DestType.MANUAL -> writeToManual(context, uri, destination, displayName)
                }
                if (ok) {
                    succeeded++
                    if (move) {
                        val removed = tryRemoveSource(context, uri)
                        if (!removed) notRemoved.add(displayName)
                    }
                } else {
                    errors.add(displayName)
                }
            } catch (e: Exception) {
                errors.add("$displayName (${e.message})")
            }
        }

        return CopyResult(sources.size, succeeded, notRemoved, errors)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToSaf(context: Context, source: Uri, destination: Destination, name: String): Boolean {
        val treeUri = Uri.parse(destination.treeUri ?: return false)
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val mime = context.contentResolver.getType(source) ?: "application/octet-stream"
        val uniqueName = uniqueSafName(treeDoc, name)
        val newFile = treeDoc.createFile(mime, uniqueName) ?: return false
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            } ?: return false
        } ?: return false
        return true
    }

    private fun uniqueSafName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (dir.findFile("$base ($i)$ext") != null) i++
        return "$base ($i)$ext"
    }

    private fun writeToManual(context: Context, source: Uri, destination: Destination, name: String): Boolean {
        val basePath = destination.path ?: return false
        val uniqueName = uniqueManualName(basePath, name, destination.useRoot)
        val fullPath = basePath.trimEnd('/') + "/" + uniqueName

        if (destination.useRoot) {
            val input = context.contentResolver.openInputStream(source) ?: return false
            return RootHelper.writeFileAsRoot(fullPath, input)
        } else {
            val dir = File(basePath)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, uniqueName)
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return false
            return outFile.exists() && outFile.length() > 0
        }
    }

    private fun uniqueManualName(basePath: String, name: String, useRoot: Boolean): String {
        if (useRoot) return name // проверка занятости имени через su избыточна для большинства сценариев
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var i = 1
        while (File(basePath, candidate).exists()) {
            candidate = "$base ($i)$ext"
            i++
        }
        return candidate
    }

    /** Пытаемся удалить исходный файл (для режима "переместить"). Часто невозможно из-за прав, выданных приложением-источником. */
    private fun tryRemoveSource(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
}
