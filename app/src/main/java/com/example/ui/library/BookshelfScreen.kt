package com.example.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.MediaType
import com.example.ui.components.ScreenHeader
import com.example.ui.theme.PlayfairDisplay
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    modifier: Modifier = Modifier,
    viewModel: BookshelfViewModel = hiltViewModel(),
    onOpenEbook: (BookEntity) -> Unit,
    onOpenAudiobook: (BookEntity) -> Unit,
    onConvertBookToAudiobook: (BookEntity) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val continueReading by viewModel.continueReading.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val books by viewModel.filteredBooks.collectAsStateWithLifecycle()
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val analytics by viewModel.analyticsSummary.collectAsStateWithLifecycle()
    val watchedFolders by viewModel.watchedFolderUris.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var detailsBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val navClearance = com.example.ui.components.LocalNavBarClearance.current
    val view = androidx.compose.ui.platform.LocalView.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addAndScanWatchedFolder(uri.toString())
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSingleBookOrAudioUri(uri.toString())
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.dismissStatus()
    }

    val open: (BookEntity) -> Unit = { book ->
        if (book.mediaType == MediaType.AUDIOBOOK) onOpenAudiobook(book) else onOpenEbook(book)
    }

    val sourcesButton: @Composable () -> Unit = {
        IconButton(onClick = { showSources = true }, modifier = Modifier.testTag("library_add_button")) {
            Icon(Icons.Default.CreateNewFolder, "Folders & files")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, "Settings")
        }
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Browser-style: the title bar slides back in as soon as you scroll up a little
    val returningHeader = com.example.ui.components.rememberReturningHeaderState()
    val headerScrolledAway by remember { androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(returningHeader.nestedScrollConnection)
            // Pull down while the library is at the top to reveal the search bar
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    var startedAtTop = !gridState.canScrollBackward
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (gridState.canScrollBackward) startedAtTop = false
                        if (startedAtTop && !showSearch && change.position.y - down.position.y > 160f) {
                            showSearch = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                }
            }
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navClearance),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("library_grid")
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    ScreenHeader(
                        title = "Library",
                        subtitle = librarySubtitle(books.size, analytics),
                        horizontalPadding = 4.dp
                    ) { sourcesButton() }
                    androidx.compose.animation.AnimatedVisibility(visible = showSearch) {
                        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            placeholder = { Text("Title, author, series or tag") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.updateSearchQuery("")
                                    showSearch = false
                                }) { Icon(Icons.Default.Close, "Close search") }
                            },
                            singleLine = true,
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .focusRequester(searchFocus)
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BookshelfFilterTab.entries) { tab ->
                            FilterChip(
                                selected = uiState.filterTab == tab,
                                onClick = { viewModel.selectFilterTab(tab) },
                                label = { Text(tab.label) },
                                shape = CircleShape,
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                    if (uiState.isScanningFolders || scanProgress != null) {
                        Text(
                            scanProgress ?: "Scanning…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Pick up where you left off (hidden while searching or filtering)
            val resume = continueReading
            if (resume != null && uiState.searchQuery.isBlank() && uiState.filterTab == BookshelfFilterTab.ALL) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue_reading") {
                    ContinueReadingCard(resume, onOpen = { open(resume.book) })
                }
            }

            if (books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyLibrary(
                        filtered = uiState.searchQuery.isNotBlank() || uiState.filterTab != BookshelfFilterTab.ALL,
                        onAddFiles = { filePicker.launch(arrayOf("*/*")) },
                        onAddFolder = { folderPicker.launch(null) }
                    )
                }
            }

            items(books, key = { it.id }) { book ->
                BookTile(
                    book = book,
                    onClick = { open(book) },
                    onLongClick = {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        detailsBookId = book.id
                    }
                )
            }
        }

        com.example.ui.components.ReturningTopBar(
            visible = returningHeader.revealed && headerScrolledAway,
            title = "Library",
            modifier = Modifier.align(Alignment.TopCenter)
        ) { sourcesButton() }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = navClearance)) { Snackbar(it) }
    }

    if (showSources) {
        LibrarySourcesSheet(
            watchedFolders = watchedFolders.toList(),
            books = allBooks,
            scanning = uiState.isScanningFolders || scanProgress != null,
            onDismiss = { showSources = false },
            onAddFolder = { showSources = false; folderPicker.launch(null) },
            onAddFile = { showSources = false; filePicker.launch(arrayOf("*/*")) },
            onRescan = { showSources = false; viewModel.rescanWatchedFolders() },
            onRemoveFolder = viewModel::removeWatchedFolder,
            onOpenBook = { id -> showSources = false; detailsBookId = id }
        )
    }

    detailsBookId?.let { id ->
        BookDetailsDialog(
            bookId = id,
            allTags = allBooks.flatMap { it.tags }.distinct().sorted(),
            allCollections = allBooks.flatMap { it.collections }.distinct().sorted(),
            onDismiss = { detailsBookId = null },
            onOpen = open,
            onListen = onOpenAudiobook,
            onConvert = onConvertBookToAudiobook
        )
    }
}

/** Where the library's books come from: watched folders (with subfolders) and individually added files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySourcesSheet(
    watchedFolders: List<String>,
    books: List<BookEntity>,
    scanning: Boolean,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onAddFile: () -> Unit,
    onRescan: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onOpenBook: (String) -> Unit
) {
    val folderPaths = remember(watchedFolders) { watchedFolders.associateWith { StoragePaths.readable(it) } }
    val looseFiles = remember(books, folderPaths) {
        books.filter { b -> folderPaths.values.none { f -> StoragePaths.readable(b.fileUri).startsWith("${f.trimEnd('/')}/") } }
            .filter { it.fileUri.isNotBlank() && !it.fileUri.startsWith("file:") }
    }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    "Folders & files",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Button(onClick = onAddFolder) {
                        Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add folder")
                    }
                    OutlinedButton(onClick = onAddFile) {
                        Icon(Icons.Default.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add file")
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp)
                ) {
                    Text(
                        "Folders · scanned with all subfolders",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (watchedFolders.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = onRescan, enabled = !scanning) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (scanning) "Scanning…" else "Rescan")
                        }
                    }
                }
            }
            if (watchedFolders.isEmpty()) {
                item {
                    Text(
                        "No folders yet. Add a folder and every book inside it (and its subfolders) is imported; new files appear when you rescan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }
            items(watchedFolders, key = { it }) { uri ->
                val path = folderPaths[uri].orEmpty()
                val count = books.count { StoragePaths.readable(it.fileUri).startsWith("${path.trimEnd('/')}/") }
                ListItem(
                    headlineContent = { Text(path.substringAfterLast('/').ifBlank { path }) },
                    supportingContent = {
                        Text(
                            "$path · ${if (count == 1) "1 book" else "$count books"}",
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = { Icon(Icons.Default.CreateNewFolder, null) },
                    trailingContent = {
                        IconButton(onClick = { confirmRemove = uri }) { Icon(Icons.Default.Close, "Stop watching folder") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            item {
                Text(
                    "Files added one by one",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            if (looseFiles.isEmpty()) {
                item {
                    Text(
                        "None",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }
            }
            items(looseFiles, key = { it.id }) { book ->
                ListItem(
                    headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            StoragePaths.readable(book.fileUri),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = { Icon(Icons.Default.NoteAdd, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpenBook(book.id) }
                )
            }
        }
    }

    confirmRemove?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Stop watching this folder?") },
            text = { Text("${folderPaths[uri]}\n\nBooks already imported from it stay in your library; new files there won't be picked up on rescan.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onRemoveFolder(uri); confirmRemove = null }) { Text("Stop watching") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } }
        )
    }
}

private fun librarySubtitle(count: Int, analytics: ReadingAnalyticsSummary): String = buildString {
    append(if (count == 1) "1 book" else "$count books")
    if (analytics.currentDailyStreakDays > 0) append(" · ${analytics.currentDailyStreakDays}-day streak")
    if (analytics.totalHoursRead > 0f) append(" · ${analytics.totalHoursRead} h read")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("bookshelf_item_card_${book.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(4.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
        ) {
            BookCover(book, Modifier.fillMaxSize())
            if (book.isFavorite) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
            val progress = book.progressionPercentage.coerceIn(0f, 1f)
            if (progress > 0.005f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            when (book.readingState()) {
                ReadingState.FINISHED -> "Finished"
                ReadingState.READING -> "${(book.progressionPercentage * 100).toInt().coerceAtLeast(1)}% · ${book.author}"
                ReadingState.NOT_STARTED -> book.author
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Continue reading": the book you were last in, one tap back to your page. */
@Composable
private fun ContinueReadingCard(resume: ContinueReading, onOpen: () -> Unit) {
    val book = resume.book
    val progress = book.progressionPercentage.coerceIn(0f, 1f)
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
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
                Text("Continue reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    resume.chapterTitle ?: book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("${(progress * 100).toInt()}% read")
                        // Chapter numbers count picture pages too, so only fall back to them without a title
                        if (resume.chapterTitle == null && book.totalChapters > 1) append(" · chapter ${book.currentChapterIndex + 1} of ${book.totalChapters}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.FilledIconButton(onClick = onOpen, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Read", modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** The book's own cover image, or a typeset placeholder cover when it has none. */
@Composable
fun BookCover(book: BookEntity, modifier: Modifier = Modifier) {
    val coverFile = book.coverImagePath?.let { File(it) }?.takeIf { it.isFile }
    if (coverFile != null) {
        AsyncImage(
            model = coverFile,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        // Placeholder covers use the wallpaper palette (Material You) so they sit naturally in the grid
        val scheme = MaterialTheme.colorScheme
        val (base, ink) = listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        )[abs(book.title.hashCode()) % 3]
        Box(
            modifier = modifier.background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.8f).compositeOverBlack()))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
                Box(Modifier.width(28.dp).height(1.dp).background(ink.copy(alpha = 0.6f)))
                Spacer(Modifier.height(8.dp))
                Text(
                    book.title,
                    fontFamily = PlayfairDisplay,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(28.dp).height(1.dp).background(ink.copy(alpha = 0.6f)))
                Spacer(Modifier.height(6.dp))
                Text(
                    book.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)

@Composable
private fun EmptyLibrary(filtered: Boolean, onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (filtered) "Nothing here" else "Your shelves are empty",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (filtered) "Try another filter or search." else "Add EPUB, MOBI, FB2, TXT or PDF books from your phone. Audiobooks you add go to Listen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!filtered) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAddFiles) { Text("Add files") }
                OutlinedButton(onClick = onAddFolder) { Text("Add a folder") }
            }
        }
    }
}

