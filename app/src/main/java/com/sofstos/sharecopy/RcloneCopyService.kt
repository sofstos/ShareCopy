package com.sofstos.sharecopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.math.min

/**
 * Копирование/перемещение на SFTP/Mega может занимать долгое время (сеть, задержки).
 * Раньше это делалось прямо в ShareTargetActivity/FolderBrowserActivity фоновым потоком —
 * экран при этом "висел" на статусе "Копирование…" без обратной связи. Теперь для сетевых
 * назначений используется этот foreground-сервис: экран закрывается сразу, а прогресс/
 * результат показывает уведомление.
 *
 * ВАЖНО про режим "файлы" (не папка): сервис получает уже ПОДГОТОВЛЕННЫЕ локальные файлы
 * (см. ShareTargetActivity.stageAndStartRcloneCopy), а не исходные content:// URI. Раньше
 * URI передавались прямо в сервис, и Android иногда отзывал разрешение на их чтение сразу
 * после закрытия Activity-источника (ShareTargetActivity), из-за чего копирование на SFTP
 * стабильно проваливалось. Теперь content:// читается ещё в самой Activity (это быстро,
 * т.к. локально), и сервису передаётся только путь к уже готовым файлам на диске.
 */
class RcloneCopyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val destId = intent.getStringExtra(EXTRA_DEST_ID)
        val move = intent.getBooleanExtra(EXTRA_MOVE, false)
        val dest = destId?.let { id -> SettingsStore(this).getDestinations().firstOrNull { it.id == id } }
        val opWord = getString(if (move) R.string.op_move else R.string.op_copy)
        val destName = dest?.name ?: "?"

        val initialProgress = CopyProgress(active = true, operation = opWord, destination = destName)
        CopyProgressBus.publish(initialProgress)
        startForeground(FOREGROUND_NOTIF_ID, buildProgressNotification(initialProgress))
        val resultNotifId = FOREGROUND_NOTIF_ID + startId
        Logger.log(this, "service", "start op=$opWord dest=$destName type=${dest?.type} isFolder=${intent.getBooleanExtra(EXTRA_IS_FOLDER, false)}")

        val stagingDirPath = intent.getStringExtra(EXTRA_STAGING_DIR)

        Thread {
            val (success, resultText) = runCopy(intent, dest, move, opWord, destName)
            Logger.log(this, "service", "done success=$success text=$resultText")
            val completedState = CopyProgressBus.current().copy(active = false, message = resultText)
            CopyProgressBus.publish(completedState)

            if (success) VibrationHelper.success(applicationContext) else VibrationHelper.error(applicationContext)

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(resultNotifId, buildResultNotification(resultText))

            // Подготовленные временные файлы (для режима "файлы") больше не нужны — чистим кэш.
            stagingDirPath?.let { File(it).deleteRecursively() }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            Thread.sleep(8_000L)
            if (!CopyProgressBus.current().active) CopyProgressBus.clear()
        }.start()

        return START_NOT_STICKY
    }

    private fun runCopy(intent: Intent, dest: Destination?, move: Boolean, opWord: String, destName: String): Pair<Boolean, String> {
        if (dest == null) {
            return false to getString(R.string.error_no_destinations)
        }

        val isFolder = intent.getBooleanExtra(EXTRA_IS_FOLDER, false)
        if (isFolder) {
            val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""
            val folderUseRoot = intent.getBooleanExtra(EXTRA_FOLDER_USE_ROOT, false)
            publishProgress(opWord, destName, listOf(CopyItemProgress(File(folderPath).name.ifBlank { "папка" }, CopyItemState.COPYING)), 0)
            val result = FileCopyEngine.runFolder(applicationContext, folderPath, folderUseRoot, dest, move)
            val state = if (result.success) CopyItemState.DONE else CopyItemState.FAILED
            publishProgress(opWord, destName, listOf(CopyItemProgress(File(folderPath).name.ifBlank { "папка" }, state)), 0, result.message)
            val text = if (result.success) getString(R.string.folder_result_ok, opWord, destName)
                       else getString(R.string.folder_result_fail, opWord, result.message)
            return result.success to text
        }

        val stagingDirPath = intent.getStringExtra(EXTRA_STAGING_DIR)
        val fileNames = intent.getStringArrayListExtra(EXTRA_FILE_NAMES) ?: arrayListOf()
        val subfolder = intent.getStringExtra(EXTRA_SUBFOLDER)

        if (stagingDirPath == null || fileNames.isEmpty()) {
            return false to getString(R.string.error_prepare_files_failed)
        }

        val stagingDir = File(stagingDirPath)
        val files = fileNames.map { File(stagingDir, it) }.filter { it.exists() }
        val progressItems = files.map { CopyItemProgress(it.name) }.toMutableList()
        publishProgress(opWord, destName, progressItems, -1, "Подготовка сетевого копирования")
        val result = FileCopyEngine.uploadStagedFiles(applicationContext, files, dest, subfolder) { index, name, state, bytes, totalBytes, error ->
            if (index in progressItems.indices) {
                progressItems[index] = CopyItemProgress(name, state, min(bytes, totalBytes.takeIf { it > 0L } ?: bytes), totalBytes, error)
            }
            publishProgress(opWord, destName, progressItems, index)
        }

        val success = result.total > 0 && result.succeeded == result.total
        val text = when {
            result.total == 0 -> getString(R.string.error_prepare_files_failed)
            result.succeeded == result.total -> getString(R.string.copy_result_ok, opWord, result.succeeded, result.total, destName)
            result.succeeded == 0 -> getString(R.string.copy_result_fail_all, opWord, result.total, destName) +
                errorsSuffix(result.errors)
            else -> getString(R.string.copy_result_partial, opWord, result.succeeded, result.total, destName) +
                errorsSuffix(result.errors)
        }
        return success to text
    }

    private fun publishProgress(
        operation: String,
        destination: String,
        items: List<CopyItemProgress>,
        currentIndex: Int,
        message: String = ""
    ) {
        val progress = CopyProgress(
            active = true,
            operation = operation,
            destination = destination,
            items = items.toList(),
            currentIndex = currentIndex,
            message = message
        )
        CopyProgressBus.publish(progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(FOREGROUND_NOTIF_ID, buildProgressNotification(progress))
    }

    /** Дописывает к тексту результата конкретные причины ошибок (укорачивая при необходимости). */
    private fun errorsSuffix(errors: List<String>): String {
        if (errors.isEmpty()) return ""
        val joined = errors.joinToString("; ")
        val trimmed = if (joined.length > 300) joined.take(300) + "…" else joined
        return "\n$trimmed"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_desc) }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildProgressNotification(progress: CopyProgress): Notification {
        val current = progress.currentItem
        val currentText = if (current != null) {
            getString(R.string.notif_progress_current, progress.currentIndex + 1, progress.items.size, current.name)
        } else {
            progress.message.ifBlank { getString(R.string.notif_progress_text, progress.operation, progress.destination) }
        }
        val totalBytes = progress.totalBytes
        val copiedBytes = progress.copiedBytes
        val style = NotificationCompat.InboxStyle()
            .setSummaryText(getString(R.string.notif_progress_summary, progress.completedCount, progress.items.size))
        progress.items.forEach { item ->
            val line = when (item.state) {
                CopyItemState.DONE -> getString(R.string.notif_item_done, item.name)
                CopyItemState.COPYING -> getString(R.string.notif_item_copying, item.name)
                CopyItemState.FAILED -> getString(R.string.notif_item_failed, item.name)
                CopyItemState.PENDING -> getString(R.string.notif_item_pending, item.name)
            }
            style.addLine(line)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_progress_title, progress.operation))
            .setContentText(currentText)
            .setStyle(style)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                if (totalBytes > 0L) totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                if (totalBytes > 0L) copiedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                totalBytes <= 0L
            )
            .build()
    }

    private fun buildResultNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sharecopy_network_copy"
        private const val FOREGROUND_NOTIF_ID = 1000

        private const val EXTRA_DEST_ID = "destId"
        private const val EXTRA_MOVE = "move"
        private const val EXTRA_SUBFOLDER = "subfolder"
        private const val EXTRA_IS_FOLDER = "isFolder"
        private const val EXTRA_FOLDER_PATH = "folderPath"
        private const val EXTRA_FOLDER_USE_ROOT = "folderUseRoot"
        private const val EXTRA_STAGING_DIR = "stagingDir"
        private const val EXTRA_FILE_NAMES = "fileNames"

        /**
         * [stagingDirPath] — папка с уже подготовленными (прочитанными из content://) локальными
         * файлами, [fileNames] — их имена (= желаемые имена на сервере) внутри этой папки.
         */
        fun buildStagedFilesIntent(
            context: Context,
            stagingDirPath: String,
            fileNames: List<String>,
            destId: String,
            move: Boolean,
            subfolder: String?
        ): Intent = Intent(context, RcloneCopyService::class.java).apply {
            putExtra(EXTRA_IS_FOLDER, false)
            putExtra(EXTRA_STAGING_DIR, stagingDirPath)
            putStringArrayListExtra(EXTRA_FILE_NAMES, ArrayList(fileNames))
            putExtra(EXTRA_DEST_ID, destId)
            putExtra(EXTRA_MOVE, move)
            putExtra(EXTRA_SUBFOLDER, subfolder)
        }

        fun buildFolderIntent(
            context: Context,
            folderPath: String,
            folderUseRoot: Boolean,
            destId: String,
            move: Boolean
        ): Intent = Intent(context, RcloneCopyService::class.java).apply {
            putExtra(EXTRA_IS_FOLDER, true)
            putExtra(EXTRA_FOLDER_PATH, folderPath)
            putExtra(EXTRA_FOLDER_USE_ROOT, folderUseRoot)
            putExtra(EXTRA_DEST_ID, destId)
            putExtra(EXTRA_MOVE, move)
        }
    }
}
