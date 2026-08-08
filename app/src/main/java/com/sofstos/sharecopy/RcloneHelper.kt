package com.sofstos.sharecopy

import android.content.Context
import org.json.JSONObject
import org.rclone.gomobile.Gomobile
import org.rclone.gomobile.RcloneRPCResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Обёртка над librclone (rclone, встроенный как библиотека через gomobile).
 * Все операции идут через единый JSON RPC-интерфейс Gomobile.rcloneRPC(method, jsonParams).
 *
 * Модель "remote": вместо того чтобы полагаться на состояние, сохранённое ранее в конфиге
 * rclone, мы каждый раз перед использованием пересоздаём remote под актуальными параметрами
 * из нашего собственного хранилища (SettingsStore/Destination) — так исключается риск
 * рассинхронизации, если пользователь отредактировал пароль/хост в нашем UI.
 *
 * ВАЖНО про параллелизм: если запустить два копирования одновременно, librclone может не быть
 * безопасен для настоящих одновременных вызовов из разных потоков — на практике это приводило
 * к перепутанным логам и ошибкам вида "object not found" на ровном месте. Поэтому ВСЕ вызовы
 * rcloneRPC строго сериализуются через общий lock — параллельные операции встают в очередь.
 *
 * ВАЖНО про зависания: если удалённый сервер отвечает некорректно на уровне протокола (например,
 * Windows SFTP на "экзотический" диск — см. историю), сам вызов к rclone иногда не завершается
 * ВООБЩЕ. Без защиты от этого общая блокировка осталась бы захваченной навсегда, и вообще ЛЮБАЯ
 * следующая операция (даже на другой, полностью исправный сервер) зависала бы за ней в очереди.
 * Поэтому каждый вызов выполняется в отдельном потоке с таймаутом — если сервер не ответил за
 * отведённое время, мы считаем вызов проваленным и отпускаем блокировку для остальных операций
 * (сам зависший поток при этом просто "утекает" в фоне — процесс это не блокирует).
 */
object RcloneHelper {

    @Volatile private var initialized = false
    private val rpcLock = Any()
    private const val CALL_TIMEOUT_MS = 10 * 60_000L // 10 минут — слабые устройства (роутеры) на больших файлах могут быть медленными, а операция теперь всё равно идёт в фоне и не блокирует экран

    @Synchronized
    private fun ensureInit() {
        if (!initialized) {
            Gomobile.rcloneInitialize()
            initialized = true
        }
    }

    data class RpcResult(val success: Boolean, val json: JSONObject?, val message: String)

    fun call(context: Context, method: String, params: JSONObject = JSONObject()): RpcResult {
        ensureInit()
        synchronized(rpcLock) {
            Logger.log(context, "rclone", "→ $method $params")

            val latch = CountDownLatch(1)
            var rawResult: RcloneRPCResult? = null
            var rawError: Throwable? = null
            val worker = Thread {
                try {
                    rawResult = Gomobile.rcloneRPC(method, params.toString())
                } catch (e: Throwable) {
                    rawError = e
                } finally {
                    latch.countDown()
                }
            }
            worker.isDaemon = true
            worker.start()

            val completed = latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                Logger.log(context, "rclone", "✗ $method ТАЙМАУТ (${CALL_TIMEOUT_MS / 1000}с) — сервер завис, освобождаем очередь для остальных операций")
                return RpcResult(false, null, "Сервер не отвечает (таймаут ${CALL_TIMEOUT_MS / 1000}с) — возможно, завис или недоступен")
            }

            rawError?.let { e ->
                Logger.log(context, "rclone", "✗ $method exception=${e.message}")
                return RpcResult(false, null, e.message ?: e.javaClass.simpleName)
            }

            val result = rawResult ?: return RpcResult(false, null, "Пустой ответ от rclone")
            val output = result.output ?: "{}"
            val status = result.status.toInt()
            val json = try { JSONObject(output) } catch (e: Exception) { null }
            Logger.log(context, "rclone", "← $method status=$status body=$output")
            return if (status in 200..299) {
                RpcResult(true, json, "OK")
            } else {
                val errMsg = json?.optString("error")?.takeIf { it.isNotBlank() } ?: output
                RpcResult(false, json, errMsg)
            }
        }
    }

    /** Технически безопасное для rclone имя remote'а из id папки назначения. */
    fun remoteName(destId: String): String = "d" + destId.replace(Regex("[^A-Za-z0-9]"), "")

    /** Отпечаток параметров remote'а этой сессии — чтобы не пересоздавать подключение без необходимости. */
    private val createdRemotes = mutableMapOf<String, String>() // name -> fingerprint

    private fun paramsFingerprint(type: String, params: Map<String, String>): String =
        type + "|" + params.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Создаёт remote с именем [name] типа [type] и параметрами [params], ЕСЛИ он ещё не создан
     * в этой сессии с ТАКИМИ ЖЕ параметрами. Раньше remote пересоздавался (delete+create) перед
     * КАЖДОЙ операцией — это на практике рушило внутренний кэш соединений rclone и заставляло
     * заново устанавливать сетевое/SSH-соединение на каждый отдельный RC-вызов (список папки,
     * потом копирование — два переподключения вместо одного переиспользуемого), отсюда
     * ненормально долгие "config/create" (десятки секунд на операцию, которая должна быть
     * мгновенной). Теперь пересоздаём только при реальном изменении параметров.
     */
    fun createOrReplaceRemote(context: Context, name: String, type: String, params: Map<String, String>): RpcResult {
        val fingerprint = paramsFingerprint(type, params)
        synchronized(createdRemotes) {
            if (createdRemotes[name] == fingerprint) {
                Logger.log(context, "rclone", "remote '$name' уже создан в этой сессии с теми же параметрами — переиспользуем")
                return RpcResult(true, null, "OK (переиспользован)")
            }
        }

        // Игнорируем результат удаления — remote может не существовать, это нормально.
        call(context, "config/delete", JSONObject().apply { put("name", name) })

        val paramsJson = JSONObject()
        for ((k, v) in params) paramsJson.put(k, v)

        val body = JSONObject().apply {
            put("name", name)
            put("type", type)
            put("parameters", paramsJson)
            put("opt", JSONObject().apply {
                put("obscure", true)
                put("nonInteractive", true)
            })
        }
        val result = call(context, "config/create", body)
        if (result.success) {
            synchronized(createdRemotes) { createdRemotes[name] = fingerprint }
        }
        return result
    }

    /** Проверяет, что remote реально доступен — пробует создать/проверить целевую папку. */
    fun testRemotePath(context: Context, fsSpec: String): RpcResult {
        val body = JSONObject().apply {
            put("fs", fsSpec)
            put("remote", "")
        }
        // mkdir идемпотентен — если папка уже есть, ошибки не будет; заодно проверяет доступ на запись.
        return call(context, "operations/mkdir", body)
    }

    /** Список имён файлов/папок в [dir] (без рекурсии) — для подбора уникального имени при копировании. */
    fun listNames(context: Context, fsSpec: String, dir: String): Set<String> {
        val body = JSONObject().apply {
            put("fs", fsSpec)
            put("remote", dir)
            put("opt", JSONObject().apply { put("recurse", false) })
        }
        val result = call(context, "operations/list", body)
        if (!result.success || result.json == null) return emptySet()
        val list = result.json.optJSONArray("list") ?: return emptySet()
        val names = mutableSetOf<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            item.optString("Name").takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        return names
    }

    fun uniqueName(context: Context, fsSpec: String, dir: String, name: String): String {
        val existing = listNames(context, fsSpec, dir)
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }

    /**
     * "_config": {"Inplace": true} отключает временные .partial-файлы и переименование после
     * загрузки (стандартное поведение rclone) — пишем сразу под финальным именем. Это убирает
     * целый класс проблем с "осиротевшими" .partial-файлами на серверах, которые не до конца
     * поддерживают rename/переименование (замечено на OpenWrt).
     */
    private fun inplaceConfig(): JSONObject = JSONObject().apply {
        put("_config", JSONObject().apply { put("Inplace", true) })
    }

    data class CopyFileResult(val success: Boolean, val message: String, val targetName: String)

    /** Копирует один локальный файл [localDir]/[localName] в [dstFs]:[dstDir]/[localName] (с подбором уникального имени). */
    fun copyLocalFile(context: Context, localDir: String, localName: String, dstFs: String, dstDir: String): CopyFileResult {
        val target = uniqueName(context, dstFs, dstDir, localName)
        val body = JSONObject().apply {
            put("srcFs", localDir)
            put("srcRemote", localName)
            put("dstFs", dstFs)
            put("dstRemote", if (dstDir.isBlank()) target else "${dstDir.trimEnd('/')}/$target")
        }
        mergeInto(body, inplaceConfig())
        val result = call(context, "operations/copyfile", body)
        return CopyFileResult(result.success, result.message, target)
    }

    /**
     * Гарантированно НЕЗАВИСИМАЯ проверка "файл реально есть на сервере" — создаёт отдельное
     * подключение под ДРУГИМ именем remote'а (те же host/user/pass), делает листинг именно
     * через него и сразу удаляет за собой. Это важно: если использовать для проверки то же
     * самое (переиспользуемое) подключение, которым шла запись, есть риск, что rclone покажет
     * собственное оптимистичное представление о результате записи, а не реальное состояние на
     * сервере — именно так один раз "успешно" скопировался файл, которого по факту не было.
     */
    fun verifyFileExistsFresh(context: Context, type: String, params: Map<String, String>, dir: String, fileName: String): Boolean {
        val verifyName = "verify" + (System.nanoTime() % 100_000_000L)
        val paramsJson = JSONObject()
        for ((k, v) in params) paramsJson.put(k, v)
        val createBody = JSONObject().apply {
            put("name", verifyName)
            put("type", type)
            put("parameters", paramsJson)
            put("opt", JSONObject().apply { put("obscure", true); put("nonInteractive", true) })
        }
        val created = call(context, "config/create", createBody)
        if (!created.success) {
            Logger.log(context, "rclone", "verify: не удалось создать независимое подключение для проверки")
            return false
        }
        val found = listNames(context, "$verifyName:", dir).contains(fileName)
        call(context, "config/delete", JSONObject().apply { put("name", verifyName) })
        Logger.log(context, "rclone", "независимая проверка '$fileName' в '$dir' -> found=$found")
        return found
    }

    /** Рекурсивно копирует (или перемещает, что удаляет источник) целую локальную папку в [dstFs]:[dstDir]/<имя папки>. */
    fun copyLocalFolder(context: Context, localFolderPath: String, folderName: String, dstFs: String, dstDir: String, move: Boolean): CopyFileResult {
        val target = uniqueName(context, dstFs, dstDir, folderName)
        val fullDstDir = if (dstDir.isBlank()) target else "${dstDir.trimEnd('/')}/$target"
        // dstFs приходит в форме "remoteName:" — просто дописываем путь папки назначения.
        val body = JSONObject().apply {
            put("srcFs", localFolderPath)
            put("dstFs", dstFs + fullDstDir)
        }
        mergeInto(body, inplaceConfig())
        val result = call(context, if (move) "sync/move" else "sync/copy", body)
        return CopyFileResult(result.success, result.message, target)
    }

    private fun mergeInto(target: JSONObject, extra: JSONObject) {
        extra.keys().forEach { key -> target.put(key, extra.get(key)) }
    }
}
