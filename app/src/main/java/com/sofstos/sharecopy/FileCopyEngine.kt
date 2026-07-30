package com.sofstos.sharecopy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection

data class CopyResult(
    val total: Int,
    val succeeded: Int,
    val notRemovedAfterMove: List<String>,
    val errors: List<String>
)

data class FolderCopyResult(
    val success: Boolean,
    val message: String
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

    /**
     * Копирует или перемещает целую папку [sourcePath] (реальный путь в файловой системе)
     * в [destination]. [sourceUseRoot] — нужен ли su для чтения источника (например, папка
     * в защищённой области, недоступной обычному приложению).
     */
    fun runFolder(
        context: Context,
        sourcePath: String,
        sourceUseRoot: Boolean,
        destination: Destination,
        move: Boolean
    ): FolderCopyResult {
        val srcFile = File(sourcePath.trimEnd('/'))
        val srcName = srcFile.name.ifBlank { "folder_${System.currentTimeMillis()}" }

        return try {
            when (destination.type) {
                DestType.MANUAL -> copyFolderManual(sourcePath.trimEnd('/'), srcName, destination, sourceUseRoot, move)
                DestType.SAF -> copyFolderToSaf(context, srcFile, srcName, destination, sourceUseRoot, move)
            }
        } catch (e: Exception) {
            FolderCopyResult(false, e.message ?: "Неизвестная ошибка")
        }
    }

    private fun copyFolderManual(
        sourcePath: String,
        srcName: String,
        destination: Destination,
        sourceUseRoot: Boolean,
        move: Boolean
    ): FolderCopyResult {
        val destBase = destination.path?.trimEnd('/') ?: return FolderCopyResult(false, "Не указан путь назначения")
        val needsRoot = sourceUseRoot || destination.useRoot
        val targetName = uniqueFolderNameShell(destBase, srcName, needsRoot)
        val destFull = "$destBase/$targetName"

        val ok = RootHelper.copyOrMoveTree(sourcePath, destFull, move, needsRoot)
        return FolderCopyResult(
            ok,
            if (ok) "OK" else "Не удалось выполнить копирование (проверь права доступа / root)"
        )
    }

    private fun uniqueFolderNameShell(destBase: String, name: String, useRoot: Boolean): String {
        if (!RootHelper.pathExists("$destBase/$name", useRoot)) return name
        var i = 1
        while (RootHelper.pathExists("$destBase/$name ($i)", useRoot)) i++
        return "$name ($i)"
    }

    private fun copyFolderToSaf(
        context: Context,
        srcRoot: File,
        srcName: String,
        destination: Destination,
        sourceUseRoot: Boolean,
        move: Boolean
    ): FolderCopyResult {
        if (sourceUseRoot) {
            return FolderCopyResult(
                false,
                "Копирование в SAF-папку требует прямого чтения файлов, root-источник не поддерживается — выбери назначение с типом \"Путь вручную\""
            )
        }
        if (!srcRoot.canRead()) {
            return FolderCopyResult(false, "Нет прав на чтение исходной папки: ${srcRoot.path}")
        }
        val treeUri = Uri.parse(destination.treeUri ?: return FolderCopyResult(false, "Не указана SAF-папка"))
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return FolderCopyResult(false, "SAF-папка недоступна")
        val uniqueName = uniqueSafName(treeDoc, srcName)
        val destDir = treeDoc.createDirectory(uniqueName) ?: return FolderCopyResult(false, "Не удалось создать папку в SAF")

        val ok = copyFileTreeToSaf(context, srcRoot, destDir)
        if (ok && move) {
            srcRoot.deleteRecursively()
        }
        return FolderCopyResult(ok, if (ok) "OK" else "Ошибка при копировании части файлов")
    }

    private fun copyFileTreeToSaf(context: Context, srcDir: File, destDoc: DocumentFile): Boolean {
        val children = srcDir.listFiles() ?: return true
        for (child in children) {
            if (child.isDirectory) {
                val sub = destDoc.createDirectory(child.name) ?: return false
                if (!copyFileTreeToSaf(context, child, sub)) return false
            } else {
                val mime = URLConnection.guessContentTypeFromName(child.name) ?: "application/octet-stream"
                val newFile = destDoc.createFile(mime, child.name) ?: return false
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    child.inputStream().use { input -> input.copyTo(output, bufferSize = 64 * 1024) }
                } ?: return false
            }
        }
        return true
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
