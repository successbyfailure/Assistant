package com.sbf.assistant

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    const val STYLE_DEFAULT = "default"
    const val STYLE_CUSTOM = "custom"

    fun apply(activity: AppCompatActivity) {
        val settings = SettingsManager(activity)
        val mode = when (settings.themeMode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)

        when (settings.themeStyle) {
            STYLE_CUSTOM -> activity.setTheme(R.style.Theme_Assistant_Custom)
            else -> activity.setTheme(R.style.Theme_Assistant)
        }
    }
}
