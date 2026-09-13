package com.pandorasbox.totalrecall

import android.content.Context
import android.content.SharedPreferences

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK
}

class ThemePreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("total_recall_prefs", Context.MODE_PRIVATE)

    var themeMode: AppThemeMode
        get() {
            val name = prefs.getString("app_theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
            return try {
                AppThemeMode.valueOf(name)
            } catch (e: Exception) {
                AppThemeMode.SYSTEM
            }
        }
        set(value) {
            prefs.edit().putString("app_theme_mode", value.name).apply()
        }
}
