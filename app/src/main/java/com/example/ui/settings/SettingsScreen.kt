package com.example.ui.settings

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.settings.ThemeMode
import com.example.ui.theme.PlayfairDisplay

/**
 * App settings, grouped the way you think about the app: how it looks, reading, listening,
 * narration, and your library & storage. Reader typography stays in the reader's own Aa panel.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenStudio: () -> Unit,
    onManageStudioAudiobooks: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val reader by viewModel.reader.collectAsStateWithLifecycle()
    val kokoro by viewModel.kokoroState.collectAsStateWithLifecycle()
    val folders by viewModel.watchedFolders.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri.toString())
    }
    LaunchedEffect(Unit) { viewModel.refreshStorage() }
    LaunchedEffect(storage.message) {
        val msg = storage.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.dismissMessage()
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Spacer(Modifier.width(4.dp))
                        Text("Settings", fontFamily = PlayfairDisplay, style = MaterialTheme.typography.headlineMedium)
                    }
                }

                // --- Appearance ---
                item { SectionTitle("Appearance") }
                item {
                    Group {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RowIcon(if (settings.themeMode == ThemeMode.LIGHT) Icons.Outlined.LightMode else Icons.Outlined.DarkMode)
                            Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        Choice(
                            options = ThemeMode.entries.map { it.label },
                            selected = settings.themeMode.ordinal,
                            onSelect = { i -> viewModel.update { it.copy(themeMode = ThemeMode.entries[i]) } },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Divider()
                            SwitchRow(
                                Icons.Outlined.ColorLens, "Wallpaper colours",
                                if (settings.dynamicColor) "Colours follow your wallpaper" else "Classic paper and leather colours",
                                settings.dynamicColor
                            ) { on -> viewModel.update { it.copy(dynamicColor = on) } }
                        }
                    }
                }

                // --- Reading ---
                item { SectionTitle("Reading") }
                item {
                    Group {
                        SwitchRow(Icons.Outlined.ScreenLockPortrait, "Keep the screen on", "While a book is open", reader.keepScreenOn) { on ->
                            viewModel.updateReader { it.copy(keepScreenOn = on) }
                        }
                        Divider()
                        SwitchRow(Icons.Outlined.ShortText, "Status bar", "Battery, time, chapter and page along the bottom", reader.showStatusBar) { on ->
                            viewModel.updateReader { it.copy(showStatusBar = on) }
                        }
                        Divider()
                        SwitchRow(Icons.Outlined.Style, "The book's own style", "Fonts, alignment and indents from the publisher", reader.publisherStyles) { on ->
                            viewModel.updateReader { it.copy(publisherStyles = on) }
                        }
                        Divider()
                        InfoRow(Icons.Outlined.TextFields, "Text, margins and colours", "Tap the middle of a page while reading, then Aa")
                    }
                }

                // --- Listening ---
                item { SectionTitle("Listening") }
                item {
                    Group {
                        LabeledChoice(
                            Icons.Outlined.FastRewind, "Skip back",
                            options = listOf(5, 10, 30),
                            selected = settings.skipBackSeconds,
                            label = { "$it s" }
                        ) { s -> viewModel.update { it.copy(skipBackSeconds = s) } }
                        Divider()
                        LabeledChoice(
                            Icons.Outlined.FastForward, "Skip forward",
                            options = listOf(5, 10, 30),
                            selected = settings.skipForwardSeconds,
                            label = { "$it s" }
                        ) { s -> viewModel.update { it.copy(skipForwardSeconds = s) } }
                        Divider()
                        LabeledChoice(
                            Icons.Outlined.Replay, "Rewind when resuming",
                            subtitle = "After a pause, start a few seconds back so you don't lose the thread",
                            options = listOf(0, 3, 5, 10),
                            selected = settings.rewindOnResumeSeconds,
                            label = { if (it == 0) "Off" else "$it s" }
                        ) { s -> viewModel.update { it.copy(rewindOnResumeSeconds = s) } }
                    }
                }

                // --- Narration ---
                item { SectionTitle("Narration") }
                item {
                    Group {
                        NavRow(
                            Icons.Outlined.RecordVoiceOver, "Narrator, pace and engine",
                            "Used by the Studio and by Listen in the Reader",
                            onClick = onOpenStudio
                        )
                        Divider()
                        NavRow(
                            Icons.Outlined.GraphicEq, "Kokoro voices",
                            when {
                                kokoro.installed -> "Installed · works offline · ${size(kokoro.installedSizeBytes)}"
                                kokoro.busy -> kokoro.statusText
                                else -> "Not installed · set up in the Studio"
                            },
                            onClick = onOpenStudio
                        )
                    }
                }

                // --- Library & storage ---
                item { SectionTitle("Library & storage") }
                item {
                    Group {
                        ActionRow(
                            Icons.Outlined.Folder, "Watched folders",
                            when (folders.size) {
                                0 -> "None yet · new books in a folder appear on rescan"
                                1 -> "1 folder · ${libraryLine(counts)}"
                                else -> "${folders.size} folders · ${libraryLine(counts)}"
                            },
                            button = if (folders.isEmpty()) "Add" else if (storage.rescanning) "Checking…" else "Rescan",
                            enabled = !storage.rescanning
                        ) { if (folders.isEmpty()) folderPicker.launch(null) else viewModel.rescanFolders() }
                        Divider()
                        ActionRow(
                            Icons.Outlined.AutoStories, "Made in Studio",
                            if (counts.studio == 0) "No narrations yet"
                            else "${counts.studio} audiobook${if (counts.studio == 1) "" else "s"} · ${size(storage.studioBytes)} · share, save or delete",
                            button = "Manage",
                            enabled = counts.studio > 0,
                            onClick = onManageStudioAudiobooks
                        )
                        Divider()
                        ActionRow(
                            Icons.Outlined.CleaningServices, "Cache",
                            "${size(storage.cacheBytes)} · book pictures, fonts and voice clips, rebuilt when needed",
                            button = "Clear",
                            enabled = storage.cacheBytes > 0,
                            onClick = viewModel::clearCache
                        )
                    }
                }

                // --- About ---
                item { SectionTitle("About") }
                item {
                    Group {
                        InfoRow(Icons.Outlined.Info, "OmniRead $version", "Read, listen and narrate, all on your phone")
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
        }
    }
}

// --- Building blocks ---

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp)
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    modifier = Modifier.padding(start = 56.dp, end = 16.dp)
)

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(18.dp))
}

@Composable
private fun Texts(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        RowIcon(icon)
        Texts(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        RowIcon(icon)
        Texts(title, subtitle, Modifier.weight(1f))
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        RowIcon(icon)
        Texts(title, subtitle, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String?, button: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        RowIcon(icon)
        Texts(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text(button) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {}
            ) { Text(label) }
        }
    }
}

@Composable
private fun <T> LabeledChoice(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Texts(title, subtitle, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Choice(
            options = options.map(label),
            selected = options.indexOf(selected).coerceAtLeast(0),
            onSelect = { onSelect(options[it]) },
            modifier = Modifier.padding(start = 40.dp)
        )
    }
}

private fun libraryLine(c: LibraryCounts): String =
    "${if (c.books == 1) "1 book" else "${c.books} books"}, ${if (c.audiobooks == 1) "1 audiobook" else "${c.audiobooks} audiobooks"}"
