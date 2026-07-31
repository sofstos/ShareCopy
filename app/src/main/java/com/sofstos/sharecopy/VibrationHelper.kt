package com.sofstos.sharecopy

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object VibrationHelper {

    private val SUCCESS_PATTERN = longArrayOf(0, 400, 200, 400)
    private val ERROR_PATTERN = longArrayOf(0, 100, 100, 100, 100, 100)

    fun success(context: Context) {
        if (!SettingsStore(context).isVibrationEnabled()) return
        vibrate(context, SUCCESS_PATTERN)
    }

    fun error(context: Context) {
        if (!SettingsStore(context).isVibrationEnabled()) return
        vibrate(context, ERROR_PATTERN)
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            // Игнорируем — вибрация не критична для работы приложения
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
