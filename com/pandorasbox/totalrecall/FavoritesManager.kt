package com.pandorasbox.totalrecall

import android.content.Context
import android.content.SharedPreferences

class FavoritesManager(context: Context) {
    companion object {
        private const val PREF_NAME = "total_recall_favorites"
        private const val KEY_FAVORITES = "favorite_media_ids"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getFavoriteIds(): Set<Long> {
        val set = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun isFavorite(mediaId: Long): Boolean {
        return getFavoriteIds().contains(mediaId)
    }

    fun toggleFavorite(mediaId: Long): Boolean {
        val current = getFavoriteIds().toMutableSet()
        val isNowFav = if (current.contains(mediaId)) {
            current.remove(mediaId)
            false
        } else {
            current.add(mediaId)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current.map { it.toString() }.toSet()).apply()
        return isNowFav
    }
}
