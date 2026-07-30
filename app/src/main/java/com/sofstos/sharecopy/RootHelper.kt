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

    private fun escape(path: String): String = path.replace("'", "'\\''")

    private fun shell(cmd: String, useRoot: Boolean): Process {
        return if (useRoot) {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } else {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        }
    }

    /**
     * Список подпапок [path] (только каталоги, без рекурсии).
     * Работает и без root (обычный toybox `find`, доступный в системе из коробки),
     * и с root (тогда команда оборачивается в su -c).
     * Возвращает null при ошибке выполнения (нет доступа, путь не существует и т.п.).
     */
    fun listDirectories(path: String, useRoot: Boolean): List<String>? {
        return try {
            val p = escape(path)
            val cmd = "find '$p' -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort"
            val process = shell(cmd, useRoot)
            val out = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Проверяет, является ли [path] существующей папкой. */
    fun isDirectory(path: String, useRoot: Boolean): Boolean {
        return try {
            val p = escape(path)
            val process = shell("test -d '$p'", useRoot)
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Проверяет существование пути [path] (файл или папка). */
    fun pathExists(path: String, useRoot: Boolean): Boolean {
        return try {
            val p = escape(path)
            val process = shell("test -e '$p'", useRoot)
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Рекурсивно копирует или перемещает дерево [srcPath] в [destPath] (destPath — полный путь
     * итоговой папки, ещё не существующий). Создаёт родительский каталог назначения при необходимости.
     * useRoot — использовать ли su (нужен, если источник или назначение требуют повышенных прав).
     */
    fun copyOrMoveTree(srcPath: String, destPath: String, move: Boolean, useRoot: Boolean): Boolean {
        return try {
            val src = escape(srcPath)
            val dest = escape(destPath)
            val destParent = escape(destPath.substringBeforeLast('/').ifBlank { "/" })
            val action = if (move) "mv '$src' '$dest'" else "cp -a '$src' '$dest'"
            val cmd = "mkdir -p '$destParent' && $action"
            val process = shell(cmd, useRoot)
            val exit = process.waitFor()
            exit == 0
        } catch (e: Exception) {
            false
        }
    }
}
