package com.example.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.ReadingSessionEntity
import com.example.data.local.entity.ReadingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STATUS_LABELS = listOf(
    ReadingStatus.UNREAD to "Unread",
    ReadingStatus.READING to "Reading",
    ReadingStatus.COMPLETED to "Finished",
    ReadingStatus.ABANDONED to "Dropped"
)

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    return when {
        totalMin < 1 -> "<1 min"
        totalMin < 60 -> "$totalMin min"
        else -> "${totalMin / 60} h ${totalMin % 60} min"
    }
}

private fun formatDate(ms: Long?, pattern: String = "d MMM yyyy"): String =
    ms?.let { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(it)) } ?: "—"

/**
 * Full-screen book page opened by long-pressing a book: actions, reading progress & statistics,
 * reading history, file information, and the metadata editor.
 */
@Composable
fun BookDetailsDialog(
    bookId: String,
    allTags: List<String>,
    allCollections: List<String>,
    onDismiss: () -> Unit,
    onOpen: (BookEntity) -> Unit,
    onListen: (BookEntity) -> Unit,
    onConvert: (BookEntity) -> Unit,
    viewModel: BookDetailsViewModel = hiltViewModel()
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val book = state.book
            val draft = state.draft
            when {
                book == null -> Box(Modifier.fillMaxSize())
                draft != null -> MetadataEditor(
                    draft = draft,
                    busy = state.busy,
                    message = state.message,
                    allTags = allTags,
                    allCollections = allCollections,
                    onChange = viewModel::updateDraft,
                    onPickCover = viewModel::pickCustomCover,
                    onResetCover = viewModel::resetCoverFromFile,
                    onFillFromFile = viewModel::fillFromFile,
                    onCancel = viewModel::cancelEditing,
                    onSave = viewModel::saveDraft,
                    onMessageShown = viewModel::clearMessage
                )
                else -> BookDetailsContent(
                    state = state,
                    book = book,
                    onBack = onDismiss,
                    onEdit = viewModel::startEditing,
                    onOpen = { onDismiss(); onOpen(book) },
                    onListen = { onDismiss(); onListen(book) },
                    onConvert = { onDismiss(); onConvert(book) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onStatus = viewModel::setReadingStatus,
                    onRemove = { deleteFile -> viewModel.removeFromLibrary(deleteFile, onDismiss) },
                    message = state.message,
                    onMessageShown = viewModel::clearMessage
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookDetailsContent(
    state: BookDetailsState,
    book: BookEntity,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onListen: () -> Unit,
    onConvert: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStatus: (ReadingStatus) -> Unit,
    onRemove: (deleteFile: Boolean) -> Unit,
    message: String?,
    onMessageShown: () -> Unit
) {
    var confirmRemove by remember { mutableStateOf(false) }
    var alsoDeleteFile by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(5000)
            onMessageShown()
        }
    }
    // Full-screen dialogs don't always receive the gesture-bar inset, so reserve at least 48dp
    val bottomSpace = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding().coerceAtLeast(48.dp) + 32.dp
    val isAudio = book.mediaType == MediaType.AUDIOBOOK
    val stats = state.stats

    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomSpace),
        modifier = Modifier.fillMaxSize()
    ) {
        message?.let { msg ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favorite",
                        tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit details") }
            }
        }

        // Hero
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Box(
                    Modifier
                        .width(118.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(10.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                ) { BookCover(book, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    book.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    book.seriesName?.let { s ->
                        Text(
                            "$s${book.seriesIndex?.let { " · #${if (it % 1f == 0f) it.toInt() else it}" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    book.narrator?.let {
                        Text("Read by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Actions
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(if (isAudio) Icons.Default.Headphones else Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isAudio -> "Play"
                            book.progressionPercentage > 0.005f -> "Continue reading"
                            else -> "Start reading"
                        }
                    )
                }
                if (book.mediaType == MediaType.HYBRID_SYNCED) {
                    FilledTonalButton(onClick = onListen) { Icon(Icons.Default.Headphones, "Listen") }
                }
                if (!isAudio) {
                    FilledTonalButton(onClick = onConvert) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Audiobook")
                    }
                }
            }
        }

        // Status
        item {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                STATUS_LABELS.forEachIndexed { i, (status, label) ->
                    SegmentedButton(
                        selected = book.readingStatus == status,
                        onClick = { onStatus(status) },
                        shape = SegmentedButtonDefaults.itemShape(i, STATUS_LABELS.size),
                        icon = {},
                        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    )
                }
            }
        }

        // Progress & statistics
        item {
            DetailSection("Reading") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${(book.progressionPercentage * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (book.totalChapters > 0) "chapter ${book.currentChapterIndex + 1} of ${book.totalChapters}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                LinearProgressIndicator(
                    progress = { book.progressionPercentage.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                )
                stats.estimatedRemainingMs?.let {
                    Text(
                        "About ${formatDuration(it)} left at your pace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    maxItemsInEachRow = 3,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val wpm = stats.averageWpm
                    StatTile("Time read", formatDuration(stats.totalReadMs), Modifier.weight(1f))
                    StatTile("Speed", if (wpm > 0) "$wpm wpm" else "—", Modifier.weight(1f))
                    StatTile("Pages / hour", if (wpm > 0) "${wpm * 60 / 250}" else "—", Modifier.weight(1f))
                    StatTile("Sessions", "${stats.sessionCount}", Modifier.weight(1f))
                    StatTile("Reading days", "${stats.readingDays}", Modifier.weight(1f))
                    StatTile("Words read", if (stats.wordsRead >= 1000) "${stats.wordsRead / 1000}k" else "${stats.wordsRead}", Modifier.weight(1f))
                }
                InfoRow("First opened", formatDate(stats.firstReadAt))
                InfoRow("Last read", formatDate(stats.lastReadAt ?: book.lastOpenedAt, "d MMM yyyy, HH:mm"))
                if (book.completedAt != null) InfoRow("Finished", formatDate(book.completedAt))
            }
        }

        // History
        item {
            DetailSection("History") {
                if (state.sessions.isEmpty()) {
                    Text(
                        "No reading sessions yet. Sessions longer than 30 seconds are recorded when you leave the reader.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(state.sessions.take(50), key = { it.id }) { session -> SessionRow(session) }

        // Description
        book.description?.takeIf { it.isNotBlank() }?.let { desc ->
            item {
                DetailSection("About") {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .animateContentSize()
                            .clickable { expanded = !expanded }
                    )
                }
            }
        }

        // File
        item {
            DetailSection("File") {
                InfoRow("Format", book.format.name)
                InfoRow("Size", StoragePaths.humanSize(book.fileSizeBytes))
                InfoRow("Location", state.filePath, monospace = true)
                state.folderPath?.let { InfoRow("Folder", it, monospace = true) }
                InfoRow("Added", formatDate(book.addedAt))
                if (book.totalChapters > 0) InfoRow(if (book.format.name == "PDF") "Pages" else "Chapters", "${book.totalChapters}")
                if (book.totalWords > 0) InfoRow("Words", "%,d".format(book.totalWords))
                if (book.totalDurationMs > 0) InfoRow("Length", formatDuration(book.totalDurationMs))
                InfoRow("Language", book.language)
                book.publisher?.let { InfoRow("Publisher", it) }
                book.publishedDate?.let { InfoRow("Published", it) }
                book.isbn?.let { InfoRow("ISBN", it) }
                if (book.tags.isNotEmpty()) InfoRow("Tags", book.tags.joinToString(", "))
                if (book.collections.isNotEmpty()) InfoRow("Collections", book.collections.joinToString(", "))
            }
        }

        item {
            TextButton(
                onClick = { confirmRemove = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Remove from library", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from library?") },
            text = {
                Column {
                    Text("\"${book.title}\" and its reading history will be removed from OmniRead.")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { alsoDeleteFile = !alsoDeleteFile }
                    ) {
                        androidx.compose.material3.Checkbox(checked = alsoDeleteFile, onCheckedChange = { alsoDeleteFile = it })
                        Text("Also delete the file from my phone")
                    }
                    if (alsoDeleteFile) {
                        Text(
                            if (book.format == com.example.data.local.entity.BookFormat.AUDIO_FOLDER)
                                "The whole audiobook folder and its files will be permanently deleted. This can't be undone."
                            else "\"${state.filePath.substringAfterLast('/')}\" will be permanently deleted from " +
                                "${state.filePath.substringBeforeLast('/', "your phone")}. This can't be undone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove(alsoDeleteFile) }) {
                    Text(if (alsoDeleteFile) "Delete" else "Remove", color = if (alsoDeleteFile) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SessionRow(session: ReadingSessionEntity) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatDate(session.startedAt, "EEE d MMM"), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatDate(session.startedAt, "HH:mm")}–${formatDate(session.endedAt, "HH:mm")} · ${formatDuration(session.durationMs)}" +
                    if (session.usedTtsReadAloud) " · read aloud" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${(session.startProgression * 100).toInt()}% → ${(session.endProgression * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium
            )
            if (session.averageWpm in 1..600) {
                Text("${session.averageWpm} wpm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Metadata editor
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataEditor(
    draft: BookMetadataDraft,
    busy: Boolean,
    message: String?,
    allTags: List<String>,
    allCollections: List<String>,
    onChange: ((BookMetadataDraft) -> BookMetadataDraft) -> Unit,
    onPickCover: (String) -> Unit,
    onResetCover: () -> Unit,
    onFillFromFile: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onMessageShown: () -> Unit
) {
    BackHandler(onBack = onCancel)
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickCover(uri.toString())
    }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2500)
            onMessageShown()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
            Text("Edit details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(
                    bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars
                        .asPaddingValues().calculateBottomPadding().coerceAtLeast(48.dp) + 32.dp
                )
        ) {
            // Cover
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(
                    Modifier
                        .width(96.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(6.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    BookCover(
                        BookEntity(
                            id = "draft",
                            title = draft.title,
                            author = draft.authors,
                            format = com.example.data.local.entity.BookFormat.EPUB,
                            mediaType = MediaType.EBOOK,
                            fileUri = "",
                            coverImagePath = draft.coverPath
                        ),
                        Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { coverPicker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Change cover")
                    }
                    TextButton(onClick = onResetCover) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use cover from file")
                    }
                    TextButton(onClick = { onChange { it.copy(coverPath = null) } }) { Text("No cover") }
                }
            }
            OutlinedButton(onClick = onFillFromFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Fill in from the book file")
            }

            EditorSection("Book")
            Field("Title", draft.title) { v -> onChange { it.copy(title = v) } }
            Field("Subtitle", draft.subtitle) { v -> onChange { it.copy(subtitle = v) } }
            Field("Authors", draft.authors, supporting = "Separate several authors with &") { v -> onChange { it.copy(authors = v) } }
            Field("Narrator", draft.narrator) { v -> onChange { it.copy(narrator = v) } }

            EditorSection("Series")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Series", draft.seriesName, Modifier.weight(1f)) { v -> onChange { it.copy(seriesName = v) } }
                Field("Number", draft.seriesIndex, Modifier.width(96.dp), keyboard = KeyboardType.Decimal) { v ->
                    onChange { it.copy(seriesIndex = v) }
                }
            }

            EditorSection("Publication")
            Field("Publisher", draft.publisher) { v -> onChange { it.copy(publisher = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Published", draft.publishedDate, Modifier.weight(1f), supporting = "e.g. 2021 or 2021-06-15") { v ->
                    onChange { it.copy(publishedDate = v) }
                }
                Field("Language", draft.language, Modifier.width(110.dp), supporting = "en, km…") { v ->
                    onChange { it.copy(language = v) }
                }
            }
            Field("ISBN", draft.isbn, keyboard = KeyboardType.Number) { v -> onChange { it.copy(isbn = v) } }

            EditorSection("Shelving")
            Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                STATUS_LABELS.forEachIndexed { i, (status, label) ->
                    SegmentedButton(
                        selected = draft.readingStatus == status,
                        onClick = { onChange { it.copy(readingStatus = status) } },
                        shape = SegmentedButtonDefaults.itemShape(i, STATUS_LABELS.size),
                        icon = {},
                        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    )
                }
            }
            ChipListEditor("Tags", draft.tags, allTags) { v -> onChange { it.copy(tags = v) } }
            ChipListEditor("Collections", draft.collections, allCollections) { v -> onChange { it.copy(collections = v) } }

            EditorSection("Description")
            OutlinedTextField(
                value = draft.description,
                onValueChange = { v -> onChange { it.copy(description = v) } },
                minLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EditorSection(title: String) {
    Column(Modifier.padding(top = 12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    supporting: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None
        ),
        modifier = modifier
    )
}

/** Chip list with an "add" field and one-tap suggestions taken from the rest of the library. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipListEditor(
    label: String,
    values: List<String>,
    suggestions: List<String>,
    onValues: (List<String>) -> Unit
) {
    var input by remember { mutableStateOf("") }
    fun add(v: String) {
        val clean = v.trim().trimEnd(',')
        if (clean.isNotEmpty() && values.none { it.equals(clean, true) }) onValues(values + clean)
        input = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                values.forEach { v ->
                    InputChip(
                        selected = true,
                        onClick = { onValues(values - v) },
                        label = { Text(v) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove $v", Modifier.size(16.dp)) }
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { v -> if (v.endsWith(",")) add(v) else input = v },
            placeholder = { Text("Add ${label.lowercase()}…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
            keyboardActions = KeyboardActions(onDone = { add(input) }),
            modifier = Modifier.fillMaxWidth()
        )
        val matching = suggestions
            .filter { s -> values.none { it.equals(s, true) } }
            .filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }
            .take(8)
        if (matching.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                matching.forEach { s -> SuggestionChip(onClick = { add(s) }, label = { Text(s) }) }
            }
        }
    }
}
