package com.sofstos.sharecopy

import android.app.Application

class ShareCopyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ранее здесь регистрировался внешний BouncyCastle как криптопровайдер для SFTP,
        // но на Android это конфликтует с встроенными (урезанными) классами BC внутри самой
        // системы — часть алгоритмов у внешнего BC становится недоступна непредсказуемым
        // образом (ловили и "no such algorithm: X25519", и "no such algorithm: SHA-256").
        // Полагаемся на встроенную криптографию Android (Conscrypt) — она справляется
        // со стандартными алгоритмами SFTP без конфликтов.
    }
}
