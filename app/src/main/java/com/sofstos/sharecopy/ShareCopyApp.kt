package com.sofstos.sharecopy

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class ShareCopyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Расширяет набор поддерживаемых алгоритмов обмена ключами/шифрования для sshj (SFTP),
        // т.к. встроенный в Android криптопровайдер поддерживает не всё, что может требовать
        // современный SSH-сервер.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
