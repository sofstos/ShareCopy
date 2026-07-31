package com.sofstos.sharecopy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

object FolderOpenHelper {

    /** Строит intent для просмотра папки назначения. Возвращает null, если тип назначения не поддерживает открытие (SFTP). */
    fun buildOpenFolderIntent(context: Context, dest: Destination): Intent? {
        return when (dest.type) {
            DestType.SAF -> {
                val treeUri = Uri.parse(dest.treeUri ?: return null)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            }
            DestType.MANUAL -> {
                val path = dest.path ?: return null
                val file = File(path)
                val uri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    return null
                }
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            DestType.SFTP -> null
        }
    }

    /** Ищет установленные приложения, которые в принципе умеют открывать папки (для выбора "фиксированного ФМ"). */
    fun listFileManagerCandidates(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>() // packageName -> label

        val probeIntents = listOf(
            Intent(Intent.ACTION_VIEW).apply { type = "resource/folder" },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        )

        for (intent in probeIntents) {
            try {
                val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                for (ri in list) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == context.packageName) continue
                    found[pkg] = ri.loadLabel(pm).toString()
                }
            } catch (e: Exception) {
                // игнорируем, пробуем следующий вариант
            }
        }

        return found.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }
}
