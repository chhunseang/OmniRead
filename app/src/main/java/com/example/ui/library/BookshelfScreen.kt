package com.example.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.MediaType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookshelfScreen(
    modifier: Modifier = Modifier,
    viewModel: BookshelfViewModel = hiltViewModel(),
    onOpenEbook: (BookEntity) -> Unit,
    onOpenAudiobook: (BookEntity) -> Unit,
    onConvertBookToAudiobook: (BookEntity) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val books by viewModel.filteredBooks.collectAsStateWithLifecycle()
    val analytics by viewModel.analyticsSummary.collectAsStateWithLifecycle()
    val watchedFolders by viewModel.watchedFolderUris.collectAsStateWithLifecycle()

    val safFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.addAndScanWatchedFolder(treeUri.toString())
        }
    }

    val singleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { docUri ->
        if (docUri != null) {
            viewModel.importSingleBookOrAudioUri(docUri.toString())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OmniRead Bookshelf",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${books.size} titles • ${watchedFolders.size} watched SAF folder(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { singleFilePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("import_single_book_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Import eBook or Audiobook file")
                    }
                    IconButton(
                        onClick = { safFolderPicker.launch(null) },
                        modifier = Modifier.testTag("scan_saf_folder_button")
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Scan & Watch Local Folder")
                    }
                    if (watchedFolders.isNotEmpty()) {
                        IconButton(
                            onClick = viewModel::rescanWatchedFolders,
                            modifier = Modifier.testTag("rescan_watched_folders_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan Watched Folders")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Reading Statistics & Daily Streak Banner (Moon+ Reader Analytics)
            item {
                ReadingAnalyticsHeaderCard(analytics = analytics)
            }

            // 2. Search Bar & Category Filter Tabs
            item {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Search title, author, series, or tag...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bookshelf_search_input")
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BookshelfFilterTab.entries) { tab ->
                        FilterChip(
                            selected = uiState.filterTab == tab,
                            onClick = { viewModel.selectFilterTab(tab) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("bookshelf_tab_${tab.name}")
                        )
                    }
                }
            }

            // Status Banner
            uiState.statusMessage?.let { msg ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 3. Bookshelf Cards List
            items(books, key = { it.id }) { book ->
                BookshelfItemCard(
                    book = book,
                    onPrimaryOpen = {
                        if (book.mediaType == MediaType.AUDIOBOOK) {
                            onOpenAudiobook(book)
                        } else {
                            onOpenEbook(book)
                        }
                    },
                    onListenAudio = { onOpenAudiobook(book) },
                    onConvertTts = { onConvertBookToAudiobook(book) },
                    onToggleFavorite = { viewModel.toggleBookFavorite(book) },
                    onEditMetadata = { viewModel.openMetadataEditor(book) },
                    onTagClick = { viewModel.selectTagFilter(it) }
                )
            }
        }

        // Metadata Editor Dialog
        uiState.editingBookDraft?.let { draft ->
            BookMetadataEditorDialog(
                draft = draft,
                onUpdateDraft = viewModel::updateMetadataDraft,
                onSave = viewModel::saveMetadataEdits,
                onDeleteBook = { viewModel.deleteBookFromLibrary(draft.book.id) },
                onDismiss = viewModel::dismissMetadataEditor
            )
        }
    }
}

@Composable
private fun ReadingAnalyticsHeaderCard(analytics: ReadingAnalyticsSummary) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reading_analytics_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF1A237E),
                            Color(0xFF283593)
                        )
                    )
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnalyticsMetricItem(
                icon = Icons.Default.LocalFireDepartment,
                value = "${analytics.currentDailyStreakDays}d",
                label = "Daily Streak",
                tint = Color(0xFFFFB300)
            )
            AnalyticsMetricItem(
                icon = Icons.Default.Speed,
                value = "${analytics.averageWpm}",
                label = "Words / Min",
                tint = Color(0xFF81C784)
            )
            AnalyticsMetricItem(
                icon = Icons.Default.Timer,
                value = "${analytics.totalHoursRead}h",
                label = "Hours Read",
                tint = Color(0xFF64B5F6)
            )
            AnalyticsMetricItem(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                value = "${analytics.totalWordsRead / 1000}k",
                label = "Words Read",
                tint = Color(0xFFBA68C8)
            )
        }
    }
}

@Composable
private fun AnalyticsMetricItem(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookshelfItemCard(
    book: BookEntity,
    onPrimaryOpen: () -> Unit,
    onListenAudio: () -> Unit,
    onConvertTts: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditMetadata: () -> Unit,
    onTagClick: (String) -> Unit
) {
    OutlinedCard(
        onClick = onPrimaryOpen,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bookshelf_item_card_${book.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Book Cover Thumbnail Badge
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 58.dp, height = 82.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = if (book.mediaType == MediaType.AUDIOBOOK) {
                                    listOf(Color(0xFF4A148C), Color(0xFF1A237E))
                                } else {
                                    listOf(Color(0xFF1A237E), Color(0xFF004D40))
                                }
                            )
                        )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (book.mediaType == MediaType.AUDIOBOOK) {
                                Icons.Default.Headphones
                            } else {
                                Icons.AutoMirrored.Filled.MenuBook
                            },
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = book.format.name,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (!book.seriesName.isNullOrBlank()) {
                        Text(
                            text = "${book.seriesName} #${book.seriesIndex ?: 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(book.author)
                            book.narrator?.let { append(" • 🎙 $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (book.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            book.tags.take(4).forEach { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable { onTagClick(tag) }
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (book.isFavorite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            contentDescription = "Favorite",
                            tint = if (book.isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onEditMetadata,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("edit_metadata_button_${book.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Book Metadata",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Reading / Listening Progress Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LinearProgressIndicator(
                    progress = { book.progressionPercentage.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                )
                Text(
                    text = "${(book.progressionPercentage * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // Action Buttons Row (Read, Listen, Convert to AI Audiobook)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (book.mediaType != MediaType.AUDIOBOOK) {
                        AssistChip(
                            onClick = onPrimaryOpen,
                            label = { Text("Read eBook") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                    if (book.mediaType == MediaType.AUDIOBOOK || book.mediaType == MediaType.HYBRID_SYNCED) {
                        AssistChip(
                            onClick = onListenAudio,
                            label = { Text("Play Audio") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                if (book.mediaType != MediaType.AUDIOBOOK) {
                    TextButton(
                        onClick = onConvertTts,
                        modifier = Modifier.testTag("convert_book_tts_button_${book.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI TTS Convert")
                    }
                }
            }
        }
    }
}

@Composable
private fun BookMetadataEditorDialog(
    draft: MetadataEditorDraft,
    onUpdateDraft: ((MetadataEditorDraft) -> MetadataEditorDraft) -> Unit,
    onSave: () -> Unit,
    onDeleteBook: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("book_metadata_editor_dialog")
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit Metadata, Series & Tags",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDeleteBook) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Book",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { value -> onUpdateDraft { it.copy(title = value) } },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.author,
                        onValueChange = { value -> onUpdateDraft { it.copy(author = value) } },
                        label = { Text("Author") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.narrator,
                        onValueChange = { value -> onUpdateDraft { it.copy(narrator = value) } },
                        label = { Text("Narrator") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.seriesName,
                        onValueChange = { value -> onUpdateDraft { it.copy(seriesName = value) } },
                        label = { Text("Series Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.seriesIndexText,
                        onValueChange = { value -> onUpdateDraft { it.copy(seriesIndexText = value) } },
                        label = { Text("Book #") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp)
                    )
                }

                OutlinedTextField(
                    value = draft.tagsCommaSeparated,
                    onValueChange = { value -> onUpdateDraft { it.copy(tagsCommaSeparated = value) } },
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.collectionsCommaSeparated,
                    onValueChange = { value -> onUpdateDraft { it.copy(collectionsCommaSeparated = value) } },
                    label = { Text("Collections (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSave,
                        modifier = Modifier.testTag("save_metadata_edits_button")
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
    }
}
