package com.pandorasbox.totalrecall

import android.content.Context
import android.content.SharedPreferences

class OnboardingPreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("total_recall_prefs", Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("has_completed_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_completed_onboarding", value).apply()
}
