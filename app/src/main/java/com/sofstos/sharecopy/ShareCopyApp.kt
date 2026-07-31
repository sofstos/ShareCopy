package com.sofstos.sharecopy

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class ShareCopyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ВАЖНО: у Android уже есть встроенный провайдер с именем "BC" (урезанная системная
        // версия BouncyCastle, используется платформой для legacy-нужд) — он зарегистрирован
        // всегда, ещё до запуска нашего кода. Поэтому просто добавить полноценный BC
        // недостаточно: проверка "провайдер BC уже есть? тогда не добавляем" всегда сработает
        // не в нашу пользу, а библиотеки вроде sshj, которые явно запрашивают провайдер по
        // имени "BC", будут и дальше получать урезанную системную версию, в которой не хватает
        // части алгоритмов (ловили ошибки то на X25519, то на банальном SHA-256).
        //
        // Решение — явно УДАЛИТЬ системную заглушку и поставить на её место полноценный BC.
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            // Если по какой-то причине не получилось — не роняем приложение,
            // просто SFTP-соединения с "капризными" алгоритмами могут не заработать.
        }
    }
}
