package com.sofstos.sharecopy

import org.json.JSONObject
import java.util.UUID

enum class DestType { SAF, MANUAL, SFTP }

data class Destination(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: DestType,
    // Для SAF: content:// URI дерева каталога (persistable permission)
    val treeUri: String? = null,
    // Для MANUAL: абсолютный путь в файловой системе
    val path: String? = null,
    // Для MANUAL: писать через "su" (root), а не напрямую
    val useRoot: Boolean = false,
    // Для SFTP: параметры подключения (логин/пароль)
    val sftpHost: String? = null,
    val sftpPort: Int = 22,
    val sftpUser: String? = null,
    val sftpPassword: String? = null,
    val sftpRemotePath: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("treeUri", treeUri ?: JSONObject.NULL)
        put("path", path ?: JSONObject.NULL)
        put("useRoot", useRoot)
        put("sftpHost", sftpHost ?: JSONObject.NULL)
        put("sftpPort", sftpPort)
        put("sftpUser", sftpUser ?: JSONObject.NULL)
        put("sftpPassword", sftpPassword ?: JSONObject.NULL)
        put("sftpRemotePath", sftpRemotePath ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Destination = Destination(
            id = o.getString("id"),
            name = o.getString("name"),
            type = DestType.valueOf(o.getString("type")),
            treeUri = if (o.isNull("treeUri")) null else o.getString("treeUri"),
            path = if (o.isNull("path")) null else o.getString("path"),
            useRoot = o.optBoolean("useRoot", false),
            sftpHost = if (o.isNull("sftpHost")) null else o.optString("sftpHost"),
            sftpPort = o.optInt("sftpPort", 22),
            sftpUser = if (o.isNull("sftpUser")) null else o.optString("sftpUser"),
            sftpPassword = if (o.isNull("sftpPassword")) null else o.optString("sftpPassword"),
            sftpRemotePath = if (o.isNull("sftpRemotePath")) null else o.optString("sftpRemotePath")
        )
    }
}
