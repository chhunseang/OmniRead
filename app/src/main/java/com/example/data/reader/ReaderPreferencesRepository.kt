package com.example.data.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.domain.reader.PageTurnMode
import com.example.domain.reader.ReaderPreferences
import com.example.domain.reader.ReaderThemePreset
import com.example.domain.reader.TapZoneAction
import com.example.domain.reader.TapZoneGridConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) {
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val customFontsDir = File(context.filesDir, "fonts").apply { mkdirs() }

    private val _preferences = MutableStateFlow(ReaderPreferences())
    val preferences: StateFlow<ReaderPreferences> = _preferences.asStateFlow()

    init {
        scope.launch {
            dataStore.data.collectLatest { prefs ->
                val rawJson = prefs[KEY_READER_PREFERENCES_JSON]
                if (!rawJson.isNullOrBlank()) {
                    val decoded = runCatching {
                        json.decodeFromString<ReaderPreferences>(rawJson)
                    }.getOrNull()
                    if (decoded != null) {
                        _preferences.value = decoded
                    }
                }
            }
        }
    }

    suspend fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        val updated = transform(_preferences.value)
        _preferences.value = updated
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_READER_PREFERENCES_JSON] = json.encodeToString(updated)
            }
        }
    }

    suspend fun setThemePreset(preset: ReaderThemePreset) {
        updatePreferences { current ->
            current.copy(
                themePreset = preset,
                customBackgroundArgb = preset.backgroundArgb,
                customTextArgb = preset.textArgb
            )
        }
    }

    suspend fun setCustomThemeColors(backgroundArgb: Long, textArgb: Long) {
        updatePreferences { current ->
            current.copy(
                themePreset = ReaderThemePreset.CUSTOM,
                customBackgroundArgb = backgroundArgb,
                customTextArgb = textArgb
            )
        }
    }

    suspend fun setBlueLightFilter(enabled: Boolean, intensity: Float) {
        updatePreferences { current ->
            current.copy(
                blueLightFilterEnabled = enabled,
                blueLightFilterIntensity = intensity.coerceIn(0f, 0.75f)
            )
        }
    }

    suspend fun setTypography(
        fontFamilyName: String? = null,
        fontSizeSp: Float? = null,
        fontWeight: Int? = null,
        lineSpacingMultiplier: Float? = null,
        paragraphSpacingDp: Float? = null,
        wordSpacingSp: Float? = null,
        fullJustification: Boolean? = null,
        hyphenationEnabled: Boolean? = null
    ) {
        updatePreferences { current ->
            current.copy(
                customFontFamilyName = fontFamilyName ?: current.customFontFamilyName,
                fontSizeSp = (fontSizeSp ?: current.fontSizeSp).coerceIn(11f, 42f),
                fontWeight = (fontWeight ?: current.fontWeight).coerceIn(300, 800),
                lineSpacingMultiplier = (lineSpacingMultiplier ?: current.lineSpacingMultiplier).coerceIn(1.0f, 2.5f),
                paragraphSpacingDp = (paragraphSpacingDp ?: current.paragraphSpacingDp).coerceIn(0f, 36f),
                wordSpacingSp = (wordSpacingSp ?: current.wordSpacingSp).coerceIn(0f, 6f),
                fullJustification = fullJustification ?: current.fullJustification,
                hyphenationEnabled = hyphenationEnabled ?: current.hyphenationEnabled
            )
        }
    }

    suspend fun setMargins(
        leftDp: Float,
        rightDp: Float,
        topDp: Float,
        bottomDp: Float
    ) {
        updatePreferences { current ->
            current.copy(
                marginLeftDp = leftDp.coerceIn(4f, 64f),
                marginRightDp = rightDp.coerceIn(4f, 64f),
                marginTopDp = topDp.coerceIn(8f, 72f),
                marginBottomDp = bottomDp.coerceIn(8f, 72f)
            )
        }
    }

    suspend fun setPageTurnMode(mode: PageTurnMode) {
        updatePreferences { current ->
            current.copy(pageTurnMode = mode)
        }
    }

    suspend fun setAutoScroll(enabled: Boolean, speedPixelsPerSec: Float) {
        updatePreferences { current ->
            current.copy(
                autoScrollEnabled = enabled,
                autoScrollSpeedPixelsPerSec = speedPixelsPerSec.coerceIn(10f, 240f)
            )
        }
    }

    suspend fun updateTapZoneAction(row: Int, col: Int, action: TapZoneAction) {
        updatePreferences { current ->
            val g = current.tapZoneGrid
            val nextGrid = when (row.coerceIn(0, 2) to col.coerceIn(0, 2)) {
                0 to 0 -> g.copy(topLeft = action)
                0 to 1 -> g.copy(topCenter = action)
                0 to 2 -> g.copy(topRight = action)
                1 to 0 -> g.copy(middleLeft = action)
                1 to 1 -> g.copy(middleCenter = action)
                1 to 2 -> g.copy(middleRight = action)
                2 to 0 -> g.copy(bottomLeft = action)
                2 to 1 -> g.copy(bottomCenter = action)
                else -> g.copy(bottomRight = action)
            }
            current.copy(tapZoneGrid = nextGrid)
        }
    }

    suspend fun resetTapZoneGridToDefault() {
        updatePreferences { current ->
            current.copy(tapZoneGrid = TapZoneGridConfig())
        }
    }

    /**
     * Imports a user-selected `.ttf` or `.otf` font via SAF URI into app-internal storage
     * and activates it in [ReaderPreferences].
     */
    suspend fun importCustomFontFile(uriString: String, displayFontName: String): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val uri = android.net.Uri.parse(uriString)
                val safeFileName = displayFontName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .let { if (it.endsWith(".ttf", true) || it.endsWith(".otf", true)) it else "$it.ttf" }
                val destFile = File(customFontsDir, safeFileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("Could not open font file stream: $uriString")

                val cleanName = safeFileName.substringBeforeLast('.')
                updatePreferences { current ->
                    current.copy(
                        customFontFamilyName = cleanName,
                        customFontFileUri = destFile.absolutePath
                    )
                }
                destFile.absolutePath
            }
        }

    /**
     * Generates a Readium / WebView CSS stylesheet matching the current [ReaderPreferences]
     * for live DOM typography & theme injection.
     */
    fun buildReadiumCssStylesheet(prefs: ReaderPreferences = _preferences.value): String {
        val bgHex = String.format("#%06X", 0xFFFFFF and prefs.customBackgroundArgb.toInt())
        val fgHex = String.format("#%06X", 0xFFFFFF and prefs.customTextArgb.toInt())
        val textAlign = if (prefs.fullJustification) "justify" else "start"
        val hyphens = if (prefs.hyphenationEnabled) "auto" else "none"
        return """
            :root {
                --RS__backgroundColor: $bgHex !important;
                --RS__textColor: $fgHex !important;
            }
            html, body {
                background-color: $bgHex !important;
                color: $fgHex !important;
                font-family: '${prefs.customFontFamilyName}', serif !important;
                font-size: ${prefs.fontSizeSp}px !important;
                font-weight: ${prefs.fontWeight} !important;
                line-height: ${prefs.lineSpacingMultiplier} !important;
                word-spacing: ${prefs.wordSpacingSp}px !important;
                margin: ${prefs.marginTopDp}px ${prefs.marginRightDp}px ${prefs.marginBottomDp}px ${prefs.marginLeftDp}px !important;
                text-align: $textAlign !important;
                -webkit-hyphens: $hyphens !important;
                hyphens: $hyphens !important;
            }
            p {
                margin-bottom: ${prefs.paragraphSpacingDp}px !important;
            }
            .omniread-tts-active-sentence {
                background-color: rgba(255, 193, 7, 0.35) !important;
                border-bottom: 2px solid #FFB300 !important;
                border-radius: 4px !important;
            }
        """.trimIndent()
    }

    companion object {
        private val KEY_READER_PREFERENCES_JSON = stringPreferencesKey("omniread_reader_preferences_json")

        val BUILTIN_FONT_FAMILIES = listOf(
            "Literata",
            "Serif",
            "SansSerif",
            "Monospace",
            "Georgia",
            "Palatino"
        )
    }
}
