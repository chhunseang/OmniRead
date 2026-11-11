package com.example.ui.audio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.isStudioMade
import com.example.data.local.entity.TtsSyncCueEntity
import com.example.domain.audio.AudiobookPlaybackState
import com.example.domain.audio.SleepTimerMode
import com.example.ui.components.LocalNavBarClearance
import com.example.ui.components.ScreenHeader
import com.example.ui.components.SlimSlider
import com.example.ui.library.BookCover
import com.example.ui.theme.PlayfairDisplay
import kotlin.math.abs
import kotlin.math.roundToInt

// =============================================================================================
// Listen tab: the audiobook shelf ("continue listening" + all audiobooks)
// =============================================================================================

@Composable
fun AudiobookPlayerScreen(
    modifier: Modifier = Modifier,
    viewModel: AudiobookPlayerViewModel = hiltViewModel(),
    onOpenInFullEbookReader: ((String) -> Unit)? = null,
    onOpenStudio: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {}
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<BookEntity?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current

    val audioFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audiobook.m4b"
            viewModel.importAudiobookFileOrFolderUri(uri.toString(), name, isFolder = false)
        }
    }
    val audioFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            val folderName = treeUri.lastPathSegment?.substringAfterLast(':') ?: "Audiobook Folder"
            viewModel.importAudiobookFileOrFolderUri(treeUri.toString(), folderName, isFolder = true)
        }
    }

    val allBooks = ui.availableAudiobooks
    val studioCount = allBooks.count { it.isStudioMade }
    val yoursCount = allBooks.size - studioCount
    // Only offer the split when there is something on both sides
    val showFilter = studioCount > 0 && yoursCount > 0
    val filter = if (showFilter) ui.shelfFilter else ShelfFilter.ALL
    val books = when (filter) {
        ShelfFilter.ALL -> allBooks
        ShelfFilter.YOURS -> allBooks.filterNot { it.isStudioMade }
        ShelfFilter.STUDIO -> allBooks.filter { it.isStudioMade }
    }
    // The book to resume: what's loaded now, otherwise the last one listened to (and not finished)
    val resume = playback.activeBook?.let { active -> allBooks.firstOrNull { it.id == active.id } ?: active }
        ?: allBooks.firstOrNull { it.lastOpenedAt != null && it.readingStatus != ReadingStatus.COMPLETED && it.progressionPercentage < 0.98f }
    val navClearance = LocalNavBarClearance.current
    val listened = allBooks.sumOf { (it.totalDurationMs * it.progressionPercentage).toLong() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navClearance + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            ScreenHeader(
                title = "Listen",
                subtitle = when {
                    allBooks.isEmpty() -> "No audiobooks yet"
                    listened > 60_000L -> "${countLabel(allBooks.size)} · ${formatDurationWords(listened)} listened"
                    else -> countLabel(allBooks.size)
                },
                horizontalPadding = 4.dp
            ) {
                Box {
                    IconButton(onClick = { addMenuOpen = true }, modifier = Modifier.testTag("import_audio_file_button")) {
                        Icon(Icons.Default.Add, contentDescription = "Add audiobooks")
                    }
                    androidx.compose.material3.DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Add audio file") },
                            leadingIcon = { Icon(Icons.Default.AudioFile, null) },
                            onClick = { addMenuOpen = false; audioFilePicker.launch(arrayOf("audio/*")) }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Add a folder") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { addMenuOpen = false; audioFolderPicker.launch(null) }
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            }
        }

        ui.statusMessage?.let { msg ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                StatusBanner(msg, onDismiss = viewModel::dismissStatus)
            }
        }

        if (allBooks.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyShelf(
                    onAddFiles = { audioFilePicker.launch(arrayOf("audio/*")) },
                    onAddFolder = { audioFolderPicker.launch(null) },
                    onOpenStudio = onOpenStudio
                )
            }
            return@LazyVerticalGrid
        }

        if (resume != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "resume") {
                ContinueListeningCard(
                    book = resume,
                    playback = playback.takeIf { it.activeBook?.id == resume.id },
                    onOpen = { viewModel.openAudiobook(resume.id) },
                    onPlayPause = {
                        if (playback.activeBook?.id == resume.id) viewModel.togglePlayPause()
                        else viewModel.selectAndLoadAudiobook(resume.id, autoPlay = true)
                    }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "shelf_title") {
            Column(Modifier.padding(top = 4.dp)) {
                if (showFilter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShelfFilter.entries.forEach { f ->
                            val count = when (f) { ShelfFilter.ALL -> allBooks.size; ShelfFilter.YOURS -> yoursCount; ShelfFilter.STUDIO -> studioCount }
                            FilterChip(
                                selected = filter == f,
                                onClick = { viewModel.setShelfFilter(f) },
                                label = { Text("${f.label} $count") },
                                leadingIcon = if (f == ShelfFilter.STUDIO) {
                                    { Icon(Icons.Default.GraphicEq, null, Modifier.size(16.dp)) }
                                } else null,
                                shape = CircleShape,
                                border = null,
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                    Text(
                        when (filter) {
                            ShelfFilter.ALL -> "Long-press an audiobook for more options"
                            ShelfFilter.YOURS -> "Audio files you added from your phone"
                            ShelfFilter.STUDIO -> "Narrated from your books in the Studio · long-press to share, save or delete"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    )
                } else {
                    Text(
                        "Your audiobooks",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        items(books, key = { it.id }) { book ->
            ShelfBook(
                book = book,
                isActive = playback.activeBook?.id == book.id,
                isPlaying = playback.activeBook?.id == book.id && playback.isPlaying,
                onClick = { viewModel.openAudiobook(book.id) },
                onLongClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    actionsFor = book
                }
            )
        }
        if (onOpenStudio != null && studioCount == 0) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "studio_hint") {
                StudioHint(onOpenStudio)
            }
        }
    }

    actionsFor?.let { target ->
        // Use the live row so the sheet reflects progress changes
        val live = allBooks.firstOrNull { it.id == target.id }
        if (live == null) actionsFor = null
        else AudiobookActionsSheet(book = live, viewModel = viewModel, onDismiss = { actionsFor = null })
    }
}

private fun countLabel(n: Int) = if (n == 1) "1 audiobook" else "$n audiobooks"

@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun ContinueListeningCard(
    book: BookEntity,
    playback: AudiobookPlaybackState?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit
) {
    val progress = playback?.let { (it.totalBookPositionMs.toFloat() / it.totalBookDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f) }
        ?: book.progressionPercentage.coerceIn(0f, 1f)
    val total = playback?.totalBookDurationMs?.takeIf { it > 1 } ?: book.totalDurationMs
    val chapterTitle = playback?.let { p -> segments(p).let { parts -> parts.getOrNull(currentSegmentIndex(p, parts))?.takeIf { parts.size > 1 }?.title } }
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                book,
                Modifier
                    .width(76.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (playback?.isPlaying == true) "Now playing" else "Continue listening",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    chapterTitle ?: book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (total > 0L) "${(progress * 100).roundToInt()}% · ${formatDurationWords((total * (1 - progress)).toLong())} left"
                    else "${(progress * 100).roundToInt()}% listened",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(52.dp)) {
                Icon(
                    if (playback?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playback?.isPlaying == true) "Pause" else "Play",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ShelfBook(book: BookEntity, isActive: Boolean, isPlaying: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val progress = book.progressionPercentage.coerceIn(0f, 1f)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (isActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier
                )
        ) {
            BookCover(book, Modifier.fillMaxSize())
            if (book.isStudioMade) {
                StudioBadge(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 10.dp))
            }
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            if (isPlaying) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                ) {
                    Icon(
                        Icons.Default.GraphicEq, "Playing",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(5.dp)
                    )
                }
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            when {
                book.readingStatus == ReadingStatus.COMPLETED || progress >= 0.98f -> "Finished"
                progress <= 0f -> if (book.totalDurationMs > 0) formatDurationWords(book.totalDurationMs) else "New"
                book.totalDurationMs > 0 -> "${formatDurationWords((book.totalDurationMs * (1 - progress)).toLong())} left"
                else -> "${(progress * 100).roundToInt()}%"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun StudioHint(onOpenStudio: () -> Unit) {
    Surface(
        onClick = onOpenStudio,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Turn a book into an audiobook", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Narrated on your phone with Kokoro, ready to play here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyShelf(onAddFiles: () -> Unit, onAddFolder: () -> Unit, onOpenStudio: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp)
    ) {
        Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Nothing to listen to yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add your M4B / MP3 audiobooks, or make one from any book in Studio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAddFiles) { Text("Add files") }
            OutlinedButton(onClick = onAddFolder) { Text("Add folder") }
        }
        if (onOpenStudio != null) {
            TextButton(onClick = onOpenStudio, modifier = Modifier.padding(top = 4.dp)) { Text("Make one in Studio") }
        }
    }
}

// =============================================================================================
// Mini player: floats above the tab bar on every tab while an audiobook is loaded
// =============================================================================================

@Composable
fun MiniPlayer(
    playback: AudiobookPlaybackState,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book = playback.activeBook ?: return
    val parts = segments(playback)
    val chapter = parts.getOrNull(currentSegmentIndex(playback, parts))?.takeIf { parts.size > 1 }
    val progress = chapterProgress(playback).let { (pos, dur) -> if (dur > 0) pos.toFloat() / dur else 0f }
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp)
            ) {
                BookCover(
                    book,
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        chapter?.title ?: book.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onPlayPause) {
                    when {
                        playback.isBuffering && playback.isPlaying -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        playback.isPlaying -> Icon(Icons.Default.Pause, "Pause")
                        else -> Icon(Icons.Default.PlayArrow, "Play")
                    }
                }
            }
            // Thin progress line along the bottom edge (current chapter)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

val MiniPlayerHeight = 64.dp

// =============================================================================================
// Now playing: the full player
// =============================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: AudiobookPlayerViewModel,
    onCollapse: () -> Unit,
    onOpenInFullEbookReader: ((String) -> Unit)? = null
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val book = playback.activeBook

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background)
                    )
                )
        ) {
            if (book == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Box
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                // ---- Top bar ----
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "Close player", Modifier.size(30.dp)) }
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    if (ui.hasSyncedTextAvailable) {
                        IconButton(onClick = viewModel::toggleSyncedTextViewMode) {
                            Icon(
                                if (ui.isSyncedTextViewMode) Icons.Default.Headphones else Icons.Default.Subtitles,
                                contentDescription = if (ui.isSyncedTextViewMode) "Show cover" else "Show text"
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }

                // ---- Cover (or the synced text) ----
                AnimatedContent(
                    targetState = ui.isSyncedTextViewMode && ui.hasSyncedTextAvailable,
                    label = "coverOrText",
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { showText ->
                    if (showText) {
                        SyncedTranscript(
                            cues = ui.chapterSyncCues,
                            activeCue = playback.activeSyncedCue,
                            onCueTapped = viewModel::seekToSyncCue,
                            onOpenInReader = onOpenInFullEbookReader?.let { open -> { open(book.id) } }
                        )
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            // Largest 2:3 cover that fits the space
                            val coverWidth = minOf(maxWidth * 0.82f, maxHeight * (2f / 3f) * 0.94f)
                            BookCover(
                                book,
                                Modifier
                                    .width(coverWidth)
                                    .aspectRatio(2f / 3f)
                                    .shadow(18.dp, RoundedCornerShape(14.dp))
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        }
                    }
                }

                // ---- Title ----
                Spacer(Modifier.height(14.dp))
                Text(
                    book.title,
                    fontFamily = PlayfairDisplay,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    listOfNotNull(book.author, book.narrator?.let { "read by $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // ---- Chapter + scrubber ----
                Spacer(Modifier.height(14.dp))
                val parts = segments(playback)
                val partIndex = currentSegmentIndex(playback, parts)
                val chapter = parts.getOrNull(partIndex)
                Surface(
                    onClick = { viewModel.openBottomSheet(AudiobookSheetType.CHAPTERS_AND_TRACKS) },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            chapter?.title ?: book.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (parts.size > 1) {
                            Text(
                                "Chapter ${partIndex + 1} of ${parts.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ChapterScrubber(playback, onSeekBookMs = viewModel::seekToBookPosition)

                // ---- Transport ----
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    IconButton(
                        onClick = {
                            // Like a CD player: restart this chapter unless we're right at its start
                            val (pos, _) = chapterProgress(playback)
                            val target = if (pos > 4_000L || partIndex == 0) partIndex else partIndex - 1
                            parts.getOrNull(target)?.let { viewModel.seekToBookPosition(it.startMs) }
                        },
                        enabled = parts.isNotEmpty()
                    ) { Icon(Icons.Default.SkipPrevious, "Previous chapter", Modifier.size(30.dp)) }
                    IconButton(onClick = viewModel::skipBackward, modifier = Modifier.size(56.dp)) {
                        Icon(skipBackIcon(playback.skipBackwardIntervalMs), "Back ${playback.skipBackwardIntervalMs / 1000} seconds", Modifier.size(34.dp))
                    }
                    FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(76.dp).testTag("audio_play_pause_button")) {
                        if (playback.isBuffering && playback.isPlaying) {
                            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(
                                if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    IconButton(onClick = viewModel::skipForward, modifier = Modifier.size(56.dp)) {
                        Icon(skipForwardIcon(playback.skipForwardIntervalMs), "Forward ${playback.skipForwardIntervalMs / 1000} seconds", Modifier.size(34.dp))
                    }
                    IconButton(
                        onClick = { parts.getOrNull(partIndex + 1)?.let { viewModel.seekToBookPosition(it.startMs) } },
                        enabled = partIndex < parts.size - 1
                    ) { Icon(Icons.Default.SkipNext, "Next chapter", Modifier.size(30.dp)) }
                }

                // ---- Actions ----
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                ) {
                    PlayerAction(
                        Icons.Default.Speed,
                        formatSpeed(playback.playbackSpeed),
                        active = abs(playback.playbackSpeed - 1f) > 0.01f
                    ) { viewModel.openBottomSheet(AudiobookSheetType.SPEED_PITCH_AND_EQ) }
                    PlayerAction(
                        Icons.Default.Bedtime,
                        when (playback.sleepTimer.mode) {
                            SleepTimerMode.OFF -> "Sleep"
                            SleepTimerMode.END_OF_CHAPTER -> "End of ch."
                            SleepTimerMode.END_OF_TRACK -> "End of file"
                            SleepTimerMode.DURATION_MINUTES -> formatClock(playback.sleepTimer.remainingMs)
                        },
                        active = playback.sleepTimer.mode != SleepTimerMode.OFF
                    ) { viewModel.openBottomSheet(AudiobookSheetType.SLEEP_TIMER) }
                    PlayerAction(Icons.AutoMirrored.Filled.QueueMusic, "Chapters") {
                        viewModel.openBottomSheet(AudiobookSheetType.CHAPTERS_AND_TRACKS)
                    }
                    PlayerAction(
                        Icons.Default.Bookmarks,
                        if (playback.bookmarks.isEmpty()) "Bookmarks" else "Bookmarks · ${playback.bookmarks.size}"
                    ) { viewModel.openBottomSheet(AudiobookSheetType.AUDIO_BOOKMARKS) }
                }
            }

            if (ui.showCreateBookmarkDialog) {
                BookmarkDialog(
                    label = ui.bookmarkLabelDraft,
                    note = ui.bookmarkNoteDraft,
                    quote = playback.activeSyncedCue?.textContent,
                    onUpdateDraft = viewModel::updateBookmarkDraft,
                    onSave = viewModel::saveAudioBookmark,
                    onDismiss = viewModel::dismissCreateBookmarkDialog
                )
            }

            if (ui.activeSheet != AudiobookSheetType.NONE) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.openBottomSheet(AudiobookSheetType.NONE) },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    when (ui.activeSheet) {
                        AudiobookSheetType.CHAPTERS_AND_TRACKS -> ChaptersSheet(playback) { startMs ->
                            viewModel.seekToBookPosition(startMs)
                            viewModel.openBottomSheet(AudiobookSheetType.NONE)
                        }
                        AudiobookSheetType.SPEED_PITCH_AND_EQ -> SpeedSheet(
                            playback = playback,
                            onSpeed = viewModel::setPlaybackSpeed,
                            onSkipSilence = viewModel::setSkipSilence,
                            onVoiceBoost = { viewModel.setVoiceBoostEq(it, playback.voiceBoostConfig.loudnessGainMb) },
                            onSkipIntervals = viewModel::setCustomSkipIntervals
                        )
                        AudiobookSheetType.SLEEP_TIMER -> SleepSheet(
                            playback = playback,
                            onStart = { mode, minutes -> viewModel.startSleepTimer(mode, minutes) },
                            onCancel = {
                                viewModel.cancelSleepTimer()
                                viewModel.openBottomSheet(AudiobookSheetType.NONE)
                            }
                        )
                        AudiobookSheetType.AUDIO_BOOKMARKS -> BookmarksSheet(
                            bookmarks = playback.bookmarks,
                            positionMs = playback.totalBookPositionMs,
                            onAdd = {
                                viewModel.openBottomSheet(AudiobookSheetType.NONE)
                                viewModel.openCreateBookmarkDialog()
                            },
                            onJump = viewModel::jumpToBookmark,
                            onDelete = viewModel::deleteBookmark
                        )
                        AudiobookSheetType.NONE -> Unit
                    }
                }
            }
        }
    }
}

/** A navigable part of the audiobook: a timed chapter, or (when chapters carry no times) one audio file. */
private data class Segment(val title: String, val startMs: Long, val endMs: Long)

/**
 * The book's parts on the audio timeline. Chapters copied from an eBook have no timestamps; in that
 * case the audio files themselves are the parts, so "chapter 42 of 42" can't show at 2 %.
 */
private fun segments(p: AudiobookPlaybackState): List<Segment> {
    val total = p.totalBookDurationMs
    val timed = p.chapters.filter { it.startTimeMs != null }
    if (timed.isNotEmpty() && (timed.size == p.chapters.size || timed.any { (it.startTimeMs ?: 0L) > 0L })) {
        return timed.mapIndexed { i, ch ->
            val start = ch.startTimeMs ?: 0L
            val end = ch.endTimeMs?.takeIf { it > start } ?: timed.getOrNull(i + 1)?.startTimeMs ?: total
            Segment(ch.title, start, end.coerceAtLeast(start))
        }
    }
    return p.tracks.mapIndexed { i, t ->
        Segment(t.title.ifBlank { "Part ${i + 1}" }, t.startOffsetInBookMs, t.startOffsetInBookMs + t.durationMs)
    }
}

private fun currentSegmentIndex(p: AudiobookPlaybackState, parts: List<Segment>): Int =
    parts.indexOfLast { it.startMs <= p.totalBookPositionMs }.coerceAtLeast(0)

/** Position and length within the current part (falls back to the current file). */
private fun chapterProgress(p: AudiobookPlaybackState): Pair<Long, Long> {
    val parts = segments(p)
    val seg = parts.getOrNull(currentSegmentIndex(p, parts))
    if (seg != null && seg.endMs > seg.startMs) {
        return (p.totalBookPositionMs - seg.startMs).coerceIn(0L, seg.endMs - seg.startMs) to (seg.endMs - seg.startMs)
    }
    return p.currentTrackPositionMs to p.currentTrackDurationMs.coerceAtLeast(0L)
}

@Composable
private fun ChapterScrubber(playback: AudiobookPlaybackState, onSeekBookMs: (Long) -> Unit) {
    val (pos, dur) = chapterProgress(playback)
    val chapterStart = playback.totalBookPositionMs - pos
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shownPos = dragFraction?.let { (it * dur).toLong() } ?: pos
    Column(Modifier.fillMaxWidth()) {
        SlimSlider(
            value = dragFraction ?: if (dur > 0) pos.toFloat() / dur else 0f,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeekBookMs(chapterStart + (it * dur).toLong()) }
                dragFraction = null
            },
            enabled = dur > 0,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(shownPos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            val bookLeft = (playback.totalBookDurationMs - playback.totalBookPositionMs).coerceAtLeast(0L)
            if (segments(playback).size > 1) {
                Text(
                    "${formatDurationWords((bookLeft / playback.playbackSpeed.coerceAtLeast(0.1f)).toLong())} left in book",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
            }
            Text(
                "-${formatClock((dur - shownPos).coerceAtLeast(0L))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SyncedTranscript(
    cues: List<TtsSyncCueEntity>,
    activeCue: TtsSyncCueEntity?,
    onCueTapped: (TtsSyncCueEntity) -> Unit,
    onOpenInReader: (() -> Unit)?
) {
    val listState = rememberLazyListState()
    val activeIndex = cues.indexOfFirst { it.id == activeCue?.id }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            itemsIndexed(cues, key = { _, c -> c.id }) { i, cue ->
                val active = i == activeIndex
                Text(
                    cue.textContent,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = com.example.ui.theme.Literata,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCueTapped(cue) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
        if (onOpenInReader != null) {
            TextButton(onClick = onOpenInReader, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in the Reader")
            }
        }
    }
}

// ---- Sheets ----------------------------------------------------------------------------------

@Composable
private fun SheetTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontFamily = PlayfairDisplay, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun ChaptersSheet(playback: AudiobookPlaybackState, onSelect: (Long) -> Unit) {
    val parts = segments(playback)
    val currentIndex = currentSegmentIndex(playback, parts)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Chapters")
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.heightIn(max = 560.dp)) {
            itemsIndexed(parts) { i, chapter ->
                val current = i == currentIndex
                val past = i < currentIndex
                val length = (chapter.endMs - chapter.startMs).takeIf { it > 0 }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(chapter.startMs) }
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp)
                    )
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (current) {
                        Icon(Icons.Default.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    } else if (length != null) {
                        Text(
                            formatClock(length),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(
    playback: AudiobookPlaybackState,
    onSpeed: (Float) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onVoiceBoost: (Boolean) -> Unit,
    onSkipIntervals: (Int, Int) -> Unit
) {
    val speed = playback.playbackSpeed
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        SheetTitle("Playback")
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onSpeed(((speed - 0.05f) * 20).roundToInt() / 20f) }, enabled = speed > 0.5f) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    formatSpeed(speed),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(onClick = { onSpeed(((speed + 0.05f) * 20).roundToInt() / 20f) }, enabled = speed < 3f) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
            SlimSlider(
                value = speed,
                onValueChange = { onSpeed((it * 20).roundToInt() / 20f) },
                valueRange = 0.5f..3f,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f, 2.5f).forEach { preset ->
                    FilterChip(selected = abs(speed - preset) < 0.01f, onClick = { onSpeed(preset) }, label = { Text(formatSpeed(preset)) })
                }
            }
            HorizontalDivider()
            ToggleLine("Skip silence", "Trims long pauses between sentences", playback.skipSilenceEnabled, onSkipSilence)
            ToggleLine("Voice boost", "Clearer, louder speech for noisy places", playback.voiceBoostConfig.enabled, onVoiceBoost)
            HorizontalDivider()
            val back = (playback.skipBackwardIntervalMs / 1000).toInt()
            val fwd = (playback.skipForwardIntervalMs / 1000).toInt()
            Text("Jump back", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { s -> FilterChip(selected = back == s, onClick = { onSkipIntervals(fwd, s) }, label = { Text("${s}s") }) }
            }
            Text("Jump forward", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30, 60).forEach { s -> FilterChip(selected = fwd == s, onClick = { onSkipIntervals(s, back) }, label = { Text("${s}s") }) }
            }
        }
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SleepSheet(
    playback: AudiobookPlaybackState,
    onStart: (SleepTimerMode, Int) -> Unit,
    onCancel: () -> Unit
) {
    val timer = playback.sleepTimer
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetTitle("Sleep timer")
        if (timer.mode != SleepTimerMode.OFF) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (timer.mode) {
                            SleepTimerMode.END_OF_CHAPTER -> "Stops at the end of this chapter"
                            SleepTimerMode.END_OF_TRACK -> "Stops at the end of this file"
                            else -> "Stops in ${formatClock(timer.remainingMs)}"
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancel) { Text("Turn off") }
                }
            }
        }
        val options = listOf(
            Triple("5 minutes", SleepTimerMode.DURATION_MINUTES, 5),
            Triple("10 minutes", SleepTimerMode.DURATION_MINUTES, 10),
            Triple("15 minutes", SleepTimerMode.DURATION_MINUTES, 15),
            Triple("30 minutes", SleepTimerMode.DURATION_MINUTES, 30),
            Triple("45 minutes", SleepTimerMode.DURATION_MINUTES, 45),
            Triple("1 hour", SleepTimerMode.DURATION_MINUTES, 60),
            Triple("End of chapter", SleepTimerMode.END_OF_CHAPTER, 0)
        )
        options.forEach { (label, mode, minutes) ->
            val selected = timer.mode == mode && (mode != SleepTimerMode.DURATION_MINUTES || timer.totalConfiguredMs == minutes * 60_000L)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStart(mode, minutes) }
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            "The voice fades out gently. Shake the phone to add more time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    positionMs: Long,
    onAdd: () -> Unit,
    onJump: (BookmarkEntity) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetTitle("Bookmarks")
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Bookmark ${formatClock(positionMs)}")
        }
        if (bookmarks.isEmpty()) {
            Text(
                "Save a moment to come back to it later — with a note if you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 440.dp).padding(top = 8.dp)) {
            items(bookmarks, key = { it.id }) { bm ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(bm) }
                        .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(bm.label ?: bm.chapterTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!bm.noteMarkdown.isNullOrBlank()) {
                            Text(bm.noteMarkdown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                        }
                        Text(
                            bm.chapterTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onDelete(bm.id) }) {
                        Icon(Icons.Default.Delete, "Delete bookmark", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkDialog(
    label: String,
    note: String,
    quote: String?,
    onUpdateDraft: (String?, String?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New bookmark", style = MaterialTheme.typography.titleLarge, fontFamily = PlayfairDisplay)
                if (!quote.isNullOrBlank()) {
                    Text("“$quote”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { onUpdateDraft(it, null) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { onUpdateDraft(null, it) },
                    label = { Text("Note (optional)") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("audio_bookmark_note_input")
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onSave, modifier = Modifier.testTag("save_audio_bookmark_button")) { Text("Save") }
                }
            }
        }
    }
}

// ---- Formatting ------------------------------------------------------------------------------

private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "5 h 12 min", "12 min", "45 s". */
fun formatDurationWords(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        totalMin > 0 -> "$m min"
        else -> "${(ms / 1000L).coerceAtLeast(0L)} s"
    }
}

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).roundToInt() / 100f
    val text = if (rounded % 1f == 0f) "%.1f".format(rounded) else "%.2f".format(rounded).trimEnd('0')
    return "$text×"
}

/** Skip icons that show the interval chosen in Settings › Listening. */
private fun skipBackIcon(ms: Long): ImageVector = when (ms / 1000) {
    in 0..7 -> Icons.Default.Replay5
    in 8..19 -> Icons.Default.Replay10
    else -> Icons.Default.Replay30
}

private fun skipForwardIcon(ms: Long): ImageVector = when (ms / 1000) {
    in 0..7 -> Icons.Default.Forward5
    in 8..19 -> Icons.Default.Forward10
    else -> Icons.Default.Forward30
}
