package com.sofstos.sharecopy

import java.io.DataOutputStream
import java.io.InputStream

object RootHelper {

    /** Проверка доступности root. Кэшируем результат на время работы процесса. */
    private var cachedRootAvailable: Boolean? = null

    fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val result = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
        cachedRootAvailable = result
        return result
    }

    /**
     * Создаёт директорию (если нужно) и записывает поток [input] в файл [fullPath] через su.
     * Возвращает true при успехе.
     */
    fun writeFileAsRoot(fullPath: String, input: InputStream): Boolean {
        return try {
            val dir = fullPath.substringBeforeLast('/')
            val mkdirProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p '$dir'"))
            mkdirProcess.waitFor()

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > '$fullPath'"))
            val out = DataOutputStream(process.outputStream)
            input.use { ins ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (ins.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
            out.close()
            val exit = process.waitFor()
            exit == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Удаление исходного файла через root (используется для режима "переместить", если нужно). */
    fun deleteAsRoot(fullPath: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -f '$fullPath'"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
