package com.sofstos.sharecopy

import android.content.Context
import org.json.JSONArray

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("sharecopy_prefs", Context.MODE_PRIVATE)

    fun getDestinations(): MutableList<Destination> {
        val raw = prefs.getString(KEY_DESTINATIONS, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        val list = mutableListOf<Destination>()
        for (i in 0 until arr.length()) {
            list.add(Destination.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun saveDestinations(list: List<Destination>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_DESTINATIONS, arr.toString()).apply()
    }

    fun addDestination(d: Destination) {
        val list = getDestinations()
        list.add(d)
        saveDestinations(list)
    }

    fun updateDestination(updated: Destination) {
        val list = getDestinations()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
        } else {
            list.add(updated)
        }
        saveDestinations(list)
    }

    fun removeDestination(id: String) {
        val list = getDestinations()
        list.removeAll { it.id == id }
        saveDestinations(list)
    }

    // Последний выбранный чекбокс "Переместить" — запоминаем для удобства
    fun getLastMoveFlag(): Boolean = prefs.getBoolean(KEY_LAST_MOVE, false)
    fun setLastMoveFlag(value: Boolean) = prefs.edit().putBoolean(KEY_LAST_MOVE, value).apply()

    companion object {
        private const val KEY_DESTINATIONS = "destinations"
        private const val KEY_LAST_MOVE = "last_move_flag"
    }
}
