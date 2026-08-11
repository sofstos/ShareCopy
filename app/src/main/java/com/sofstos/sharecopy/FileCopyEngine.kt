package com.sofstos.sharecopy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        move: Boolean,
        subfolderName: String? = null
    ): CopyResult {
        var succeeded = 0
        val notRemoved = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (uri in sources) {
            var displayName = queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
            displayName = maybeRenameScreenshot(context, uri, displayName)
            try {
                val ok = when (destination.type) {
                    DestType.SAF -> writeToSaf(context, uri, destination, displayName, subfolderName)
                    DestType.MANUAL -> writeToManual(context, uri, destination, displayName, subfolderName)
                    DestType.SFTP, DestType.MEGA -> writeToRclone(context, uri, destination, displayName, subfolderName)
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

    /**
     * Если [uri] похож на скриншот — пытается определить, какое приложение было на переднем
     * плане в момент его создания (через ScreenshotHelper, без фонового сервиса), и переименовать
     * файл в формате "ИмяПриложения_YYYY-MM-DD_hh-mm-ss.ext". Если определить не удалось —
     * возвращает исходное имя без изменений.
     */
    fun maybeRenameScreenshot(context: Context, uri: Uri, originalName: String): String {
        val looksLikeScreenshot = ScreenshotHelper.isScreenshotUri(context, originalName) ||
            ScreenshotHelper.isInScreenshotsFolder(context, uri)
        if (!looksLikeScreenshot) return originalName

        val ts = ScreenshotHelper.getFileTimestampMs(context, uri) ?: return originalName
        val appLabel = ScreenshotHelper.resolveForegroundAppLabelAt(context, ts) ?: return originalName

        val dot = originalName.lastIndexOf('.')
        val ext = if (dot > 0) originalName.substring(dot + 1) else "png"
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        return "${sanitizeName(appLabel)}_$stamp.$ext"
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToSaf(context: Context, source: Uri, destination: Destination, name: String, subfolderName: String?): Boolean {
        val treeUri = Uri.parse(destination.treeUri ?: return false)
        var treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!subfolderName.isNullOrBlank()) {
            treeDoc = (treeDoc.findFile(subfolderName)?.takeIf { it.isDirectory }
                ?: treeDoc.createDirectory(subfolderName))
                ?: return false
        }
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

    private fun writeToManual(context: Context, source: Uri, destination: Destination, name: String, subfolderName: String?): Boolean {
        val baseRaw = destination.path ?: return false
        val basePath = if (!subfolderName.isNullOrBlank())
            baseRaw.trimEnd('/') + "/" + sanitizeName(subfolderName)
        else
            baseRaw
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

    /** Общие параметры подключения rclone (тип бэкенда, параметры, базовый путь на удалённом хранилище) для SFTP/MEGA. */
    fun rcloneBackendInfo(destination: Destination): Triple<String, Map<String, String>, String>? {
        return when (destination.type) {
            DestType.SFTP -> {
                val host = destination.sftpHost ?: return null
                val user = destination.sftpUser ?: return null
                val pass = destination.sftpPassword ?: ""
                val basePath = destination.sftpRemotePath?.trimEnd('/')?.ifBlank { "/" } ?: "/"
                Triple(
                    "sftp",
                    mapOf(
                        "host" to host, "user" to user, "pass" to pass, "port" to destination.sftpPort.toString(),
                        // Многие SFTP-серверы (OpenWrt, нередко и Windows OpenSSH) не поддерживают
                        // изменение времени модификации файла после записи — rclone по умолчанию
                        // пытается это сделать и считает всю операцию проваленной при отказе.
                        "set_modtime" to "false",
                        // По умолчанию rclone отправляет несколько пакетов записи одновременно
                        // (конвейером), не дожидаясь подтверждения на каждый — это быстрее на
                        // "нормальных" серверах, но лёгкие встроенные реализации sftp-сервера
                        // (например dropbear/busybox на OpenWrt) с этим не справляются и просто
                        // перестают отвечать. Отключаем — пишем строго последовательно, как WinSCP.
                        "disable_concurrent_writes" to "true",
                        // КРИТИЧНО для скорости: если сервер предлагает diffie-hellman-group-
                        // exchange-sha256/sha1 в качестве обмена ключами, Go-библиотека crypto/ssh
                        // (которую использует rclone) выполняет там очень медленную проверку числа
                        // на простоту — подтверждённый баг Go, 30-45+ секунд на одно подключение
                        // (см. golang/go#41151). Явно перечисляем только быстрые алгоритмы —
                        // современные (curve25519/ecdh, доли миллисекунды) плюс запасной вариант
                        // подешевле (group14, ~20мс), которые поддерживает почти любой SSH-сервер.
                        "key_exchange" to "curve25519-sha256@libssh.org curve25519-sha256 ecdh-sha2-nistp256 ecdh-sha2-nistp384 ecdh-sha2-nistp521 diffie-hellman-group14-sha256 diffie-hellman-group14-sha1"
                    ),
                    basePath
                )
            }
            DestType.MEGA -> {
                val user = destination.megaUser ?: return null
                val pass = destination.megaPassword ?: ""
                // У Mega ведущий "/" в относительном пути создаёт папку с ПУСТЫМ именем —
                // поэтому здесь (в отличие от SFTP) убираем слэши с обеих сторон целиком.
                val basePath = destination.megaRemotePath?.trim('/') ?: ""
                Triple("mega", mapOf("user" to user, "pass" to pass), basePath)
            }
            else -> null
        }
    }

    /**
     * Загружает уже подготовленные локальные файлы (имя файла на диске = желаемое имя на сервере).
     * Используется вместо writeToRclone, когда содержимое content:// уже заранее скопировано в
     * реальные файлы (см. ShareTargetActivity) — так сервис копирования не зависит от разрешений
     * на исходный content:// URI, которые Android может отозвать после закрытия экрана-источника.
     */
    fun uploadStagedFiles(context: Context, files: List<File>, destination: Destination, subfolderName: String?): CopyResult {
        val (_, _, basePath) = rcloneBackendInfo(destination)
            ?: return CopyResult(files.size, 0, emptyList(), files.map { "${it.name} (не настроены параметры подключения)" })

        // Подключение уже создано и сохранено на диске (в rclone.conf) в момент сохранения этой
        // папки назначения в настройках — пересоздавать его перед КАЖДЫМ копированием не нужно,
        // это была основная причина медлительности (даже с быстрым обменом ключей всё равно
        // лишний RPC-вызов на каждый файл). Так же устроены "быстрые" rclone-приложения вроде
        // Round-Sync: настройка подключения — разовое действие, а не часть каждой передачи.
        val remoteName = RcloneHelper.remoteName(destination.id)
        val dstDir = if (!subfolderName.isNullOrBlank()) joinRemoteDir(basePath, sanitizeName(subfolderName)) else basePath
        val dstFsSpec = RcloneHelper.fsSpec(remoteName, dstDir)

        var succeeded = 0
        val errors = mutableListOf<String>()
        for (file in files) {
            val dir = file.parentFile?.absolutePath
            if (dir == null) {
                errors.add("${file.name} (внутренняя ошибка пути)")
                continue
            }
            val result = RcloneHelper.copyLocalFile(context, dir, file.name, dstFsSpec)
            if (!result.success) {
                errors.add("${file.name} (${result.message})")
                continue
            }
            val verified = RcloneHelper.verifyFileExists(context, dstFsSpec, result.targetName)
            if (verified) {
                succeeded++
            } else {
                errors.add("${file.name} (rclone сообщил об успехе, но независимая проверка не нашла файл на сервере)")
            }
        }
        return CopyResult(files.size, succeeded, emptyList(), errors)
    }

    private fun writeToRclone(context: Context, source: Uri, destination: Destination, name: String, subfolderName: String?): Boolean {
        val (_, _, basePath) = rcloneBackendInfo(destination) ?: return false
        val remoteName = RcloneHelper.remoteName(destination.id)
        val dstDir = if (!subfolderName.isNullOrBlank()) joinRemoteDir(basePath, sanitizeName(subfolderName)) else basePath
        val dstFsSpec = RcloneHelper.fsSpec(remoteName, dstDir)

        // rclone работает с реальными локальными путями, а не content:// — стримим во временный
        // файл с ИСХОДНЫМ именем в уникальной по операции подпапке кэша.
        val tempDir = File(context.cacheDir, "rclone_upload/${System.nanoTime()}")
        return try {
            tempDir.mkdirs()
            val tempFile = File(tempDir, name)
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            } ?: return false

            val result = RcloneHelper.copyLocalFile(context, tempDir.absolutePath, name, dstFsSpec)
            if (!result.success) return false
            RcloneHelper.verifyFileExists(context, dstFsSpec, result.targetName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Склеивает базовый путь и подпапку через "/", НО не добавляет ведущий слэш, если base пуст —
     * иначе для бэкендов вроде Mega (в отличие от SFTP) это создаёт папку с пустым именем.
     */
    private fun joinRemoteDir(base: String, sub: String): String {
        val b = base.trimEnd('/')
        return if (b.isBlank()) sub else "$b/$sub"
    }

    /** Убирает символы, недопустимые в именах файлов/папок на большинстве файловых систем Android. */
    fun sanitizeName(raw: String): String {
        val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "unknown" }
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
                DestType.SFTP, DestType.MEGA -> copyFolderToRclone(context, srcFile, destination, sourceUseRoot, move)
            }
        } catch (e: Exception) {
            FolderCopyResult(false, e.message ?: "Неизвестная ошибка")
        }
    }

    private fun copyFolderToRclone(context: Context, srcRoot: File, destination: Destination, sourceUseRoot: Boolean, move: Boolean): FolderCopyResult {
        if (sourceUseRoot) {
            return FolderCopyResult(
                false,
                "Копирование на SFTP/Mega требует прямого чтения файлов, root-источник не поддерживается — выбери назначение с типом \"Путь вручную\""
            )
        }
        if (!srcRoot.canRead()) {
            return FolderCopyResult(false, "Нет прав на чтение исходной папки: ${srcRoot.path}")
        }
        val (_, _, basePath) = rcloneBackendInfo(destination)
            ?: return FolderCopyResult(false, "Не настроены параметры подключения")
        val remoteName = RcloneHelper.remoteName(destination.id)

        val result = RcloneHelper.copyLocalFolder(context, srcRoot.absolutePath, srcRoot.name, RcloneHelper.fsSpec(remoteName, basePath), move)
        if (!result.success) return FolderCopyResult(false, result.message)

        val verified = RcloneHelper.verifyFileExists(context, RcloneHelper.fsSpec(remoteName, basePath), result.targetName)
        return if (verified) FolderCopyResult(true, "OK")
        else FolderCopyResult(false, "rclone сообщил об успехе, но проверка не нашла папку на сервере")
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
    fun tryRemoveSource(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
}
