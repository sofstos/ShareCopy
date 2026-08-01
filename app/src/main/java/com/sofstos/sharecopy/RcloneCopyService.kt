package com.sofstos.sharecopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Копирование/перемещение на SFTP/Mega может занимать долгое время (сеть, задержки).
 * Раньше это делалось прямо в ShareTargetActivity/FolderBrowserActivity фоновым потоком —
 * экран при этом "висел" на статусе "Копирование…" без обратной связи, и было непонятно,
 * работает приложение или зависло. Теперь для сетевых назначений используется этот
 * foreground-сервис: экран закрывается сразу, а прогресс/результат показывает уведомление.
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

        startForeground(FOREGROUND_NOTIF_ID, buildProgressNotification(opWord, destName))
        val resultNotifId = FOREGROUND_NOTIF_ID + startId

        Thread {
            val (success, resultText) = runCopy(intent, dest, move, opWord, destName)

            if (success) VibrationHelper.success(applicationContext) else VibrationHelper.error(applicationContext)

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(resultNotifId, buildResultNotification(resultText))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }.start()

        return START_NOT_STICKY
    }

    private fun runCopy(intent: Intent, dest: Destination?, move: Boolean, opWord: String, destName: String): Pair<Boolean, String> {
        if (dest == null) {
            return false to getString(R.string.error_no_destinations)
        }

        val isFolder = intent.getBooleanExtra(EXTRA_IS_FOLDER, false)
        return if (isFolder) {
            val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""
            val folderUseRoot = intent.getBooleanExtra(EXTRA_FOLDER_USE_ROOT, false)
            val result = FileCopyEngine.runFolder(applicationContext, folderPath, folderUseRoot, dest, move)
            val text = if (result.success) getString(R.string.folder_result_ok, opWord, destName)
                       else getString(R.string.folder_result_fail, opWord, result.message)
            result.success to text
        } else {
            val sources: ArrayList<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableArrayListExtra(EXTRA_SOURCES, Uri::class.java) ?: arrayListOf()
            else
                @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_SOURCES) ?: arrayListOf()
            val subfolder = intent.getStringExtra(EXTRA_SUBFOLDER)
            val result = FileCopyEngine.run(applicationContext, sources, dest, move, subfolder)
            val success = result.total > 0 && result.succeeded == result.total
            val text = when {
                result.total == 0 -> getString(R.string.error_no_destinations)
                result.succeeded == result.total -> getString(R.string.copy_result_ok, opWord, result.succeeded, result.total, destName)
                result.succeeded == 0 -> getString(R.string.copy_result_fail_all, opWord, result.total, destName)
                else -> getString(R.string.copy_result_partial, opWord, result.succeeded, result.total, destName)
            }
            success to text
        }
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

    private fun buildProgressNotification(opWord: String, destName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_progress_title, opWord))
            .setContentText(getString(R.string.notif_progress_text, opWord, destName))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun buildResultNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
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
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_SUBFOLDER = "subfolder"
        private const val EXTRA_IS_FOLDER = "isFolder"
        private const val EXTRA_FOLDER_PATH = "folderPath"
        private const val EXTRA_FOLDER_USE_ROOT = "folderUseRoot"

        fun buildFilesIntent(
            context: Context,
            sources: ArrayList<Uri>,
            destId: String,
            move: Boolean,
            subfolder: String?
        ): Intent = Intent(context, RcloneCopyService::class.java).apply {
            putExtra(EXTRA_IS_FOLDER, false)
            putParcelableArrayListExtra(EXTRA_SOURCES, sources)
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
