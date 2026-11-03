package com.rifters.riftedreader.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(val nightMode: Int) {
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    companion object {
        val DEFAULT = DARK

        fun fromString(value: String?): AppTheme =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

class AppThemePreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    var appTheme: AppTheme
        get() {
            val name = prefs.getString(KEY_APP_THEME, AppTheme.DEFAULT.name)
            return AppTheme.fromString(name)
        }
        set(value) {
            prefs.edit().putString(KEY_APP_THEME, value.name).apply()
            applyTheme(value)
        }

    fun applyTheme(theme: AppTheme = appTheme) {
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }

    companion object {
        private const val PREFS_NAME = "app_theme_preferences"
        const val KEY_APP_THEME = "app_theme"
    }
}
