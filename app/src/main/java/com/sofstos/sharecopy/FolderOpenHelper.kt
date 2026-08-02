package com.sofstos.sharecopy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

object FolderOpenHelper {

    /** Строит "родной" SAF document URI (как у системного выбора папки) для пути под /storage/emulated/0 — многие ФМ понимают именно такой URI лучше, чем наш собственный FileProvider. */
    private fun externalStorageDocUri(path: String): Uri? {
        val root = "/storage/emulated/0"
        if (!path.startsWith(root)) return null
        val relative = path.removePrefix(root).trim('/')
        val docId = if (relative.isBlank()) "primary:" else "primary:$relative"
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    }

    private fun buildFileProviderIntent(context: Context, path: String): Intent? {
        val file = File(path)
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            return null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Строит intent для просмотра папки назначения. Возвращает null, если тип назначения не поддерживает открытие (SFTP/Mega). */
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
                externalStorageDocUri(path)?.let { uri ->
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                } ?: buildFileProviderIntent(context, path)
            }
            DestType.SFTP, DestType.MEGA -> null
        }
    }

    /**
     * Для "Открыть в системе" — собирает ВСЕ варианты intent для локальной папки (и SAF-style,
     * и через собственный FileProvider), чтобы объединить кандидатов из обоих соглашений об
     * "открытии папки", которые используют разные файловые менеджеры, в одном списке выбора.
     */
    fun buildAllOpenFolderIntents(context: Context, dest: Destination): List<Intent> {
        if (dest.type != DestType.MANUAL) {
            return listOfNotNull(buildOpenFolderIntent(context, dest))
        }
        val path = dest.path ?: return emptyList()
        val list = mutableListOf<Intent>()
        externalStorageDocUri(path)?.let { uri ->
            list.add(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        }
        buildFileProviderIntent(context, path)?.let { list.add(it) }
        return list
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
