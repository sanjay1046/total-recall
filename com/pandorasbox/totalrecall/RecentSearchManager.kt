package com.pandorasbox.totalrecall

import android.content.Context
import android.content.SharedPreferences

class RecentSearchManager(context: Context) {
    companion object {
        private const val PREF_NAME = "total_recall_recent_searches"
        private const val KEY_RECENT_LIST = "recent_queries"
        private const val MAX_RECENT = 5
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT_LIST, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        val current = getRecentSearches().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)

        val updated = current.take(MAX_RECENT).joinToString("|||")
        prefs.edit().putString(KEY_RECENT_LIST, updated).apply()
    }

    fun clearRecentSearches() {
        prefs.edit().remove(KEY_RECENT_LIST).apply()
    }
}
