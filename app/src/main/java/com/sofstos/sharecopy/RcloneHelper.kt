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
 * КРИТИЧЕСКИ ВАЖНО про формат путей: rclone распознаёт ведущий "/" как "путь от НАСТОЯЩЕГО
 * корня сервера" ТОЛЬКО когда путь идёт ОДНОЙ СТРОКОЙ вместе с именем подключения
 * ("remoteName:/абсолютный/путь"). Если передать имя подключения и путь ОТДЕЛЬНЫМИ RC-полями
 * ("fs":"remoteName:", "remote":"/абсолютный/путь") — ведущий "/" игнорируется, и путь
 * трактуется как ДОБАВЛЕНИЕ к домашней папке пользователя на сервере! Это была настоящая
 * причина того, что на OpenWrt файлы годами тихо улетали в /root/root/MyShare/... вместо
 * /root/MyShare/..., а на Windows то же самое приводило к невалидному пути с "C:" внутри
 * другого пути и ошибке SSH_FX_BAD_MESSAGE. Поэтому здесь ВЕЗДЕ путь конструируется и
 * передаётся ТОЛЬКО как единая строка "remoteName:/путь" — никогда отдельным полем.
 *
 * Модель "remote": вместо того чтобы полагаться на состояние, сохранённое ранее в конфиге
 * rclone, мы пересоздаём remote под актуальными параметрами из нашего собственного хранилища
 * (SettingsStore/Destination), но только если параметры реально изменились — так исключается
 * и риск рассинхронизации, и лишние медленные переподключения.
 *
 * ВАЖНО про параллелизм: если запустить два копирования одновременно, librclone может не быть
 * безопасен для настоящих одновременных вызовов из разных потоков — на практике это приводило
 * к перепутанным логам и ошибкам вида "object not found" на ровном месте. Поэтому ВСЕ вызовы
 * rcloneRPC строго сериализуются через общий lock — параллельные операции встают в очередь.
 *
 * ВАЖНО про зависания: если удалённый сервер отвечает некорректно на уровне протокола, сам
 * вызов к rclone иногда не завершается ВООБЩЕ. Без защиты общая блокировка осталась бы
 * захваченной навсегда, и любая следующая операция (даже на другой, исправный сервер)
 * зависала бы за ней в очереди. Поэтому каждый вызов выполняется в отдельном потоке с
 * таймаутом — если сервер не ответил за отведённое время, вызов считается проваленным и
 * блокировка отпускается (зависший поток при этом просто "утекает" в фоне).
 */
object RcloneHelper {

    @Volatile private var initialized = false
    private val rpcLock = Any()
    private const val CALL_TIMEOUT_MS = 10 * 60_000L

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

    /** Склеивает имя remote'а и путь в ОДНУ строку — единственный правильный способ передать абсолютный путь (см. комментарий класса). */
    fun fsSpec(remoteName: String, path: String): String = "$remoteName:$path"

    private val createdRemotes = mutableMapOf<String, String>() // name -> fingerprint

    private fun paramsFingerprint(type: String, params: Map<String, String>): String =
        type + "|" + params.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** Создаёт remote с именем [name] типа [type] и параметрами [params], только если он ещё не создан в этой сессии с ТАКИМИ ЖЕ параметрами. */
    fun createOrReplaceRemote(context: Context, name: String, type: String, params: Map<String, String>): RpcResult {
        val fingerprint = paramsFingerprint(type, params)
        synchronized(createdRemotes) {
            if (createdRemotes[name] == fingerprint) {
                Logger.log(context, "rclone", "remote '$name' уже создан в этой сессии с теми же параметрами — переиспользуем")
                return RpcResult(true, null, "OK (переиспользован)")
            }
        }

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

    /** Проверяет, что [fsSpec] (уже вида "remoteName:/путь") реально доступен — пробует создать/проверить папку. */
    fun testRemotePath(context: Context, fsSpec: String): RpcResult {
        val body = JSONObject().apply {
            put("fs", fsSpec)
            put("remote", "")
        }
        return call(context, "operations/mkdir", body)
    }

    /** Список имён файлов/папок в [fsSpec] (уже вида "remoteName:/путь", без рекурсии). */
    fun listNames(context: Context, fsSpec: String): Set<String> {
        val body = JSONObject().apply {
            put("fs", fsSpec)
            put("remote", "")
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

    fun uniqueName(context: Context, fsSpec: String, name: String): String {
        val existing = listNames(context, fsSpec)
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
     * загрузки — пишем сразу под финальным именем. Убирает класс проблем с "осиротевшими"
     * .partial-файлами на серверах, которые не до конца поддерживают rename (замечено на OpenWrt).
     */
    private fun inplaceConfig(): JSONObject = JSONObject().apply {
        put("_config", JSONObject().apply { put("Inplace", true) })
    }

    data class CopyFileResult(val success: Boolean, val message: String, val targetName: String)

    /** Копирует один локальный файл [localDir]/[localName] в папку [dstFsSpec] (уже вида "remoteName:/путь"), с подбором уникального имени. */
    fun copyLocalFile(context: Context, localDir: String, localName: String, dstFsSpec: String): CopyFileResult {
        val target = uniqueName(context, dstFsSpec, localName)
        val body = JSONObject().apply {
            put("srcFs", localDir)
            put("srcRemote", localName)
            put("dstFs", dstFsSpec)
            put("dstRemote", target)
        }
        mergeInto(body, inplaceConfig())
        val result = call(context, "operations/copyfile", body)
        return CopyFileResult(result.success, result.message, target)
    }

    /**
     * Проверка "файл реально есть на сервере" через УЖЕ ОТКРЫТОЕ соединение [fsSpec]
     * (то же самое, которым шла запись). Раньше здесь создавалось отдельное, полностью
     * независимое подключение для перестраховки — но настоящая причина прошлых ложных
     * "успехов" была в другом (путь неправильно склеивался, см. историю) и уже исправлена.
     * Повторный логин на каждый файл был просто лишними затратами времени — особенно
     * заметно на Mega, где вход в аккаунт сам по себе не мгновенный.
     */
    fun verifyFileExists(context: Context, fsSpec: String, fileName: String): Boolean {
        val found = listNames(context, fsSpec).contains(fileName)
        Logger.log(context, "rclone", "проверка '$fileName' в '$fsSpec' -> found=$found")
        return found
    }

    /** Рекурсивно копирует (или перемещает) целую локальную папку в [dstBaseFsSpec] (уже вида "remoteName:/путь") под новым подкаталогом с именем [folderName]. */
    fun copyLocalFolder(context: Context, localFolderPath: String, folderName: String, dstBaseFsSpec: String, move: Boolean): CopyFileResult {
        val target = uniqueName(context, dstBaseFsSpec, folderName)
        // dstBaseFsSpec уже вида "remoteName:/путь" — просто дописываем имя новой папки через "/".
        val fullDstFs = dstBaseFsSpec.trimEnd('/') + "/" + target
        val body = JSONObject().apply {
            put("srcFs", localFolderPath)
            put("dstFs", fullDstFs)
        }
        mergeInto(body, inplaceConfig())
        val result = call(context, if (move) "sync/move" else "sync/copy", body)
        return CopyFileResult(result.success, result.message, target)
    }

    private fun mergeInto(target: JSONObject, extra: JSONObject) {
        extra.keys().forEach { key -> target.put(key, extra.get(key)) }
    }
}
