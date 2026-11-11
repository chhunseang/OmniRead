package com.example.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** App-wide preferences (the Reader keeps its own typography settings separately). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+). */
    val dynamicColor: Boolean = true,
    val skipBackSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    /** Step back this far when you resume after a pause, so you don't lose the thread. 0 = off. */
    val rewindOnResumeSeconds: Int = 3
)

@Singleton
class AppSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        skipBackSeconds = prefs.getInt(KEY_SKIP_BACK, 10),
        skipForwardSeconds = prefs.getInt(KEY_SKIP_FWD, 30),
        rewindOnResumeSeconds = prefs.getInt(KEY_REWIND, 3)
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString(KEY_THEME, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putInt(KEY_SKIP_BACK, next.skipBackSeconds)
            .putInt(KEY_SKIP_FWD, next.skipForwardSeconds)
            .putInt(KEY_REWIND, next.rewindOnResumeSeconds)
            .apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_SKIP_BACK = "skip_back_s"
        const val KEY_SKIP_FWD = "skip_forward_s"
        const val KEY_REWIND = "rewind_on_resume_s"
    }
}
