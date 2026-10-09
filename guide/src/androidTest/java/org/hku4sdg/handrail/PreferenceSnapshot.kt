package org.hku4sdg.handrail

import android.content.Context

/** Platform checks must leave existing preferences and practice records intact. */
internal class PreferenceSnapshot(context: Context) {
    private val stores = listOf("guide", "practice").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
    private val values = stores.map { it.all }
    fun restore() {
        stores.zip(values).forEach { (store, saved) ->
            val edit = store.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit())
        }
    }
}
