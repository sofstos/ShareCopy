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
    /** Маскирует секреты, даже если вызывающий код передал в лог весь JSON RPC-запрос. */
    private val SECRET_VALUE = Regex(
        "(?i)(\\\"?(?:pass|password|token|secret|client_secret|access_token|refresh_token)\\\"?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|[^,}\\s]+)"
    )

    private fun sanitizeSecrets(message: String): String = SECRET_VALUE.replace(message) { match ->
        val replacement = if (match.groupValues[2].startsWith("\"")) "\"***REDACTED***\"" else "***REDACTED***"
        match.groupValues[1] + replacement
    }

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        val folder = SettingsStore(context).getLogFolderPath() ?: return
        try {
            val dir = File(folder)
            if (!dir.exists()) dir.mkdirs()
            val fileName = "ShareCopy_log_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".txt"
            val file = File(dir, fileName)
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val sanitized = sanitizeSecrets(message)
            val safeMessage = if (sanitized.length > MAX_LINE_LENGTH) sanitized.take(MAX_LINE_LENGTH) + "…(обрезано)" else sanitized
            FileWriter(file, true).use { writer ->
                writer.append("[$timestamp] [$tag] $safeMessage\n")
            }
        } catch (e: Exception) {
            // Логирование не должно мешать основной работе приложения — молча игнорируем.
        }
    }
}
