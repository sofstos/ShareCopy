package com.sofstos.sharecopy

import android.app.Application
import android.system.Os
import java.io.File

class ShareCopyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // rclone (через librclone) по умолчанию ищет свой конфиг-файл в домашней директории
        // пользователя ($HOME/.config/rclone/rclone.conf) — на Android переменная HOME обычно
        // не задана процессу приложения, так что нужно явно указать rclone, где хранить
        // конфиг с удалёнными "remotes" (SFTP/Mega/...), причём именно как настоящую
        // переменную окружения ОС (не просто System.setProperty — Go-рантайм читает её через
        // обычный getenv на уровне libc, поэтому используется android.system.Os.setenv).
        try {
            val configDir = File(filesDir, "rclone")
            if (!configDir.exists()) configDir.mkdirs()
            val configFile = File(configDir, "rclone.conf")
            Os.setenv("RCLONE_CONFIG", configFile.absolutePath, true)
        } catch (e: Exception) {
            // Не критично для запуска приложения — просто настройки удалённых папок (rclone)
            // не будут сохраняться между запусками, если это не сработало.
        }

        // Восстанавливаем remotes в фоне из постоянного RCLONE_CONFIG/настроек приложения.
        // Запуск ShareTargetActivity не должен ждать сеть и инициализацию всех назначений.
        Thread {
            try {
                RcloneHelper.restoreConfiguredRemotes(this)
            } catch (_: Exception) {
                // Конкретное назначение будет восстановлено синхронно при первой операции.
            }
        }.start()
    }
}
