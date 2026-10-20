package com.example.ui.converter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.TtsEngineType
import com.example.domain.tts.AudioExportFormat
import com.example.domain.tts.BatchConversionProgress
import com.example.domain.tts.TtsVoiceProfile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConverterScreen(
    modifier: Modifier = Modifier,
    viewModel: ConverterViewModel = hiltViewModel(),
    onOpenGeneratedAudiobook: ((String) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val modelArchivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importKokoroModelArchive(uri.toString())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI TTS & Audiobook Studio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Kokoro-82M 24kHz Offline ONNX & Cloud Batch Converter",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = {
                            modelArchivePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        label = { Text("Import ONNX ZIP") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Import Kokoro ONNX Model ZIP",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("import_onnx_model_button")
                    )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Banner if present
            uiState.statusMessage?.let { status ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // 1. Source eBook Selection
            item {
                BookSelectionCard(
                    ebooks = uiState.availableEbooks,
                    selectedBook = uiState.selectedBook,
                    onSelectBook = viewModel::selectBook
                )
            }

            // 2. TTS Engine Selector (Local Kokoro-82M vs Cloud API)
            item {
                EngineAndVoiceSelectionCard(
                    uiState = uiState,
                    onSelectEngine = viewModel::selectEngine,
                    onSelectVoice = viewModel::selectVoice,
                    onPreviewVoice = viewModel::previewSpeakerVoice,
                    onStopPreview = viewModel::stopVoicePreview,
                    onSpeedChange = viewModel::setSynthesisSpeed,
                    onUpdateCloudConfig = viewModel::updateCloudEndpointSettings,
                    onUpdatePreviewText = viewModel::updatePreviewSampleText
                )
            }

            // 3. Output Format & Sidecar Sync Configuration
            item {
                ExportFormatConfigCard(
                    exportFormat = uiState.exportFormat,
                    generateLrc = uiState.generateLrcSidecar,
                    generateJson = uiState.generateJsonSyncSidecar,
                    embedChapters = uiState.embedChapterMarkers,
                    onSelectFormat = viewModel::setExportFormat,
                    onToggleLrc = viewModel::toggleGenerateLrcSidecar,
                    onToggleJson = viewModel::toggleGenerateJsonSidecar,
                    onToggleEmbedChapters = viewModel::toggleEmbedChapterMarkers
                )
            }

            // 4. Chapter Selection List
            item {
                ChapterSelectionCard(
                    chapters = uiState.chapters,
                    selectedIndices = uiState.selectedChapterIndices,
                    onToggleChapter = viewModel::toggleChapterSelection,
                    onSelectAll = viewModel::selectAllChapters,
                    onClearAll = viewModel::clearChapterSelection,
                    onStartConversion = viewModel::enqueueBatchAudiobookConversion
                )
            }

            // 5. Active & Completed Conversion Queue
            if (uiState.conversionQueue.isNotEmpty()) {
                item {
                    Text(
                        text = "Conversion Queue (${uiState.conversionQueue.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(
                    items = uiState.conversionQueue,
                    key = { it.jobId }
                ) { jobProgress ->
                    ConversionQueueItemCard(
                        progress = jobProgress,
                        onCancel = { viewModel.cancelConversionJob(jobProgress.jobId) },
                        onOpenAudiobook = {
                            onOpenGeneratedAudiobook?.invoke(jobProgress.bookId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSelectionCard(
    ebooks: List<BookEntity>,
    selectedBook: BookEntity?,
    onSelectBook: (BookEntity) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LibraryBooks,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "1. Source eBook (EPUB / MOBI / AZW3)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ebooks, key = { it.id }) { book ->
                    val isSelected = book.id == selectedBook?.id
                    OutlinedCard(
                        onClick = { onSelectBook(book) },
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        modifier = Modifier
                            .width(240.dp)
                            .testTag("select_ebook_card_${book.id}")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = book.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${book.format.name} • ${book.totalChapters} chapters • ${book.totalWords} words",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineAndVoiceSelectionCard(
    uiState: ConverterUiState,
    onSelectEngine: (TtsEngineType) -> Unit,
    onSelectVoice: (TtsVoiceProfile) -> Unit,
    onPreviewVoice: (TtsVoiceProfile) -> Unit,
    onStopPreview: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onUpdateCloudConfig: (String, String, String) -> Unit,
    onUpdatePreviewText: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "2. Hybrid AI TTS Engine & Speaker Voice",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val engineOptions = listOf(
                    TtsEngineType.LOCAL_KOKORO to "Local Kokoro-82M (ONNX)",
                    TtsEngineType.CLOUD_OPENAI to "OpenAI TTS HD",
                    TtsEngineType.CLOUD_ELEVENLABS to "ElevenLabs v2",
                    TtsEngineType.CLOUD_GEMINI to "Gemini Audio"
                )
                engineOptions.forEach { (engine, label) ->
                    FilterChip(
                        selected = uiState.selectedEngine == engine,
                        onClick = { onSelectEngine(engine) },
                        label = { Text(label) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (engine == TtsEngineType.LOCAL_KOKORO) {
                                    Icons.Default.Memory
                                } else {
                                    Icons.Default.CloudQueue
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("engine_chip_${engine.name}")
                    )
                }
            }

            // Cloud API credentials panel when a Cloud engine is active
            AnimatedVisibility(visible = uiState.selectedEngine != TtsEngineType.LOCAL_KOKORO) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.cloudBaseUrl,
                        onValueChange = {
                            onUpdateCloudConfig(it, uiState.cloudApiKey, uiState.cloudModelId)
                        },
                        label = { Text("Cloud TTS Base URL") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_base_url_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.cloudApiKey,
                            onValueChange = {
                                onUpdateCloudConfig(uiState.cloudBaseUrl, it, uiState.cloudModelId)
                            },
                            label = { Text("API Key (Bearer / xi-api-key)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cloud_api_key_input")
                        )
                        OutlinedTextField(
                            value = uiState.cloudModelId,
                            onValueChange = {
                                onUpdateCloudConfig(uiState.cloudBaseUrl, uiState.cloudApiKey, it)
                            },
                            label = { Text("Model ID") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.7f)
                                .testTag("cloud_model_id_input")
                        )
                    }
                }
            }

            Text(
                text = "Speaker Voices (${uiState.availableVoices.size} available) — Tap play to preview 24kHz audio:",
                style = MaterialTheme.typography.labelLarge
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.availableVoices, key = { it.id }) { voice ->
                    val isSelected = voice.id == uiState.selectedVoice.id
                    val isPreviewingThis = uiState.isPreviewPlaying && uiState.previewingVoiceId == voice.id

                    OutlinedCard(
                        onClick = { onSelectVoice(voice) },
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        modifier = Modifier
                            .width(210.dp)
                            .testTag("voice_card_${voice.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = voice.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "ID #${voice.speakerIndex} • ${voice.languageCode} • ${voice.gender}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (isPreviewingThis) onStopPreview() else onPreviewVoice(voice)
                                },
                                modifier = Modifier.testTag("preview_voice_button_${voice.id}")
                            ) {
                                Icon(
                                    imageVector = if (isPreviewingThis) {
                                        Icons.Default.Stop
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = "Preview ${voice.name}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Preview Sample Sentence & Speed Slider
            OutlinedTextField(
                value = uiState.previewSampleText,
                onValueChange = onUpdatePreviewText,
                label = { Text("Speaker Preview Sample Text") },
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview_sample_text_input")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Synthesis Speed: ${"%.2f".format(uiState.synthesisSpeed)}x",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(155.dp)
                )
                Slider(
                    value = uiState.synthesisSpeed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..2.5f,
                    steps = 7,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("synthesis_speed_slider")
                )
            }
        }
    }
}

@Composable
private fun ExportFormatConfigCard(
    exportFormat: AudioExportFormat,
    generateLrc: Boolean,
    generateJson: Boolean,
    embedChapters: Boolean,
    onSelectFormat: (AudioExportFormat) -> Unit,
    onToggleLrc: (Boolean) -> Unit,
    onToggleJson: (Boolean) -> Unit,
    onToggleEmbedChapters: (Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "3. Container Format & Text-Sync Sidecars",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = exportFormat == format,
                        onClick = { onSelectFormat(format) },
                        label = {
                            Text(".${format.extension.uppercase()} (${format.mimeType})")
                        },
                        modifier = Modifier.testTag("export_format_chip_${format.name}")
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Generate .LRC & JSON Sentence Sync Sidecar",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Saves exact character-offset & millisecond timestamps (TtsSyncCueEntity) for real-time text highlighting during audiobook playback",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = generateLrc && generateJson,
                    onCheckedChange = { enabled ->
                        onToggleLrc(enabled)
                        onToggleJson(enabled)
                    },
                    modifier = Modifier.testTag("toggle_sync_sidecar_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Embed Chapter Markers & Cover Metadata",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Writes chapter start/end boundaries for Media3 chapter navigation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = embedChapters,
                    onCheckedChange = onToggleEmbedChapters,
                    modifier = Modifier.testTag("toggle_embed_chapters_switch")
                )
            }
        }
    }
}

@Composable
private fun ChapterSelectionCard(
    chapters: List<ChapterEntity>,
    selectedIndices: Set<Int>,
    onToggleChapter: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onStartConversion: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "4. Select Chapters (${selectedIndices.size}/${chapters.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onSelectAll,
                        modifier = Modifier.testTag("select_all_chapters_button")
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("All")
                    }
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.testTag("clear_all_chapters_button")
                    ) {
                        Icon(Icons.Default.RemoveDone, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("None")
                    }
                }
            }

            chapters.forEach { chapter ->
                val checked = chapter.chapterIndex in selectedIndices
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleChapter(chapter.chapterIndex) }
                        .testTag("chapter_row_${chapter.chapterIndex}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleChapter(chapter.chapterIndex) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${chapter.wordCount} words • Est. ${(chapter.wordCount / 155).coerceAtLeast(1)} min audio",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (chapter.isTtsConverted) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Already converted",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onStartConversion,
                enabled = selectedIndices.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_batch_conversion_button")
            ) {
                Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Convert ${selectedIndices.size} Chapter(s) to Synchronized Audiobook",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ConversionQueueItemCard(
    progress: BatchConversionProgress,
    onCancel: () -> Unit,
    onOpenAudiobook: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conversion_job_card_${progress.jobId}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (progress.errorMessage == null) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Cancel
                            },
                            contentDescription = null,
                            tint = if (progress.errorMessage == null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Job #${progress.jobId} • ${
                            when {
                                progress.isRunning -> "Synthesizing Chapter ${progress.completedChaptersCount + 1}/${progress.totalChaptersCount}"
                                progress.errorMessage != null -> progress.errorMessage
                                else -> "Completed & Registered in Library"
                            }
                        }",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (progress.isRunning) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cancel_job_button_${progress.jobId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel conversion job"
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progress.overallProgressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sentence ${progress.currentSentenceIndex}/${progress.totalSentencesInChapter}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress.overallProgressFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!progress.isRunning && progress.outputAudiobookFileUri != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LRC + JSON TtsSyncCueEntity sidecars linked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenAudiobook,
                        modifier = Modifier.testTag("open_converted_audiobook_button_${progress.jobId}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play Synced")
                    }
                }
            }
        }
    }
}
