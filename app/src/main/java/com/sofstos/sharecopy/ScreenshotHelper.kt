package com.sofstos.sharecopy

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore

object ScreenshotHelper {

    private const val LOOKBACK_WINDOW_MS = 15_000L

    /** Похож ли файл на скриншот (по имени или по относительному пути в MediaStore). */
    fun isScreenshotUri(context: Context, displayName: String?): Boolean {
        if (displayName != null && displayName.contains("screenshot", ignoreCase = true)) return true
        return false
    }

    /** Более точная проверка через MediaStore (относительный путь содержит "Screenshots"). Не всегда доступно (например, для файлов из кэша нашего же приложения). */
    fun isInScreenshotsFolder(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idx >= 0 && c.moveToFirst()) {
                    (c.getString(idx) ?: "").contains("Screenshot", ignoreCase = true)
                } else false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Момент создания/изменения файла (мс), если удаётся узнать через MediaStore. */
    fun getFileTimestampMs(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) * 1000L else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** Пакеты, которые никогда не считаются "содержимым" скриншота — сама наша активность и системный UI захвата скриншота. */
    private fun isExcludedPackage(context: Context, pkg: String): Boolean {
        return pkg == context.packageName || pkg == "com.android.systemui"
    }

    /**
     * Определяет название приложения, которое было на переднем плане непосредственно
     * перед [timestampMs] (то есть — какое приложение было видно на скриншоте).
     * Возвращает null, если нет разрешения "Доступ к использованию" или ничего не нашлось.
     */
    fun resolveForegroundAppLabelAt(context: Context, timestampMs: Long): String? {
        if (!hasUsageAccess(context)) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = timestampMs - LOOKBACK_WINDOW_MS
            val events = usm.queryEvents(start, timestampMs)
            var lastPackage: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (isForegroundEvent && !isExcludedPackage(context, event.packageName)) {
                    lastPackage = event.packageName
                }
            }
            lastPackage?.let { pkg ->
                try {
                    val ai = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
