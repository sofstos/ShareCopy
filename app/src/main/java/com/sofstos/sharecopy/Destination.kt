package com.sofstos.sharecopy

import org.json.JSONObject
import java.util.UUID

enum class DestType { SAF, MANUAL }

data class Destination(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: DestType,
    // Для SAF: content:// URI дерева каталога (persistable permission)
    val treeUri: String? = null,
    // Для MANUAL: абсолютный путь в файловой системе
    val path: String? = null,
    // Для MANUAL: писать через "su" (root), а не напрямую
    val useRoot: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("treeUri", treeUri ?: JSONObject.NULL)
        put("path", path ?: JSONObject.NULL)
        put("useRoot", useRoot)
    }

    companion object {
        fun fromJson(o: JSONObject): Destination = Destination(
            id = o.getString("id"),
            name = o.getString("name"),
            type = DestType.valueOf(o.getString("type")),
            treeUri = if (o.isNull("treeUri")) null else o.getString("treeUri"),
            path = if (o.isNull("path")) null else o.getString("path"),
            useRoot = o.optBoolean("useRoot", false)
        )
    }
}
