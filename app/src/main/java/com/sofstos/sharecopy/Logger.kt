package com.sofstos.sharecopy

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой файловый лог для диагностики (в дополнение к обычным Toast/уведомлениям).
 * Пишет построчно в файл ShareCopy_log_YYYY-MM-DD.txt внутри папки логов, если она
 * настроена в меню ☰. Если папка не настроена или недоступна — просто ничего не делает,
 * не мешает основной работе приложения.
 */
object Logger {

    private const val MAX_LINE_LENGTH = 4000

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        val folder = SettingsStore(context).getLogFolderPath() ?: return
        try {
            val dir = File(folder)
            if (!dir.exists()) dir.mkdirs()
            val fileName = "ShareCopy_log_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".txt"
            val file = File(dir, fileName)
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val safeMessage = if (message.length > MAX_LINE_LENGTH) message.take(MAX_LINE_LENGTH) + "…(обрезано)" else message
            FileWriter(file, true).use { writer ->
                writer.append("[$timestamp] [$tag] $safeMessage\n")
            }
        } catch (e: Exception) {
            // Логирование не должно мешать основной работе приложения — молча игнорируем.
        }
    }
}
