package com.example.ui.converter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.isStudioMade
import com.example.data.local.entity.studioVoiceName
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.TtsEngineType
import com.example.domain.tts.AudioExportFormat
import com.example.domain.tts.BatchConversionProgress
import com.example.domain.tts.KokoroModelState
import com.example.domain.tts.TtsVoiceProfile
import com.example.ui.audio.formatDurationWords
import com.example.ui.components.LocalNavBarClearance
import com.example.ui.components.ReturningTopBar
import com.example.ui.components.ScreenHeader
import com.example.ui.components.SlimSlider
import com.example.ui.components.rememberReturningHeaderState
import com.example.ui.library.BookCover
import com.example.ui.theme.PlayfairDisplay
import kotlin.math.roundToInt

/** Rough narration pace used for estimates (words per minute at 1.0×). */
private const val NARRATION_WPM = 155f

private enum class StudioSheet { NONE, VOICES, CHAPTERS }

@Composable
fun ConverterScreen(
    modifier: Modifier = Modifier,
    viewModel: ConverterViewModel = hiltViewModel(),
    onOpenGeneratedAudiobook: ((String) -> Unit)? = null,
    onOpenSettings: () -> Unit = {}
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(StudioSheet.NONE) }
    // Studio narrations live in Listen; this view manages them too
    val listenVm: com.example.ui.audio.AudiobookPlayerViewModel = hiltViewModel()
    val listenUi by listenVm.uiState.collectAsStateWithLifecycle()
    val studioBooks = listenUi.availableAudiobooks.filter { it.isStudioMade }
    var actionsFor by remember { mutableStateOf<BookEntity?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val modelArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importKokoroModelArchive(uri.toString())
    }

    val navClearance = LocalNavBarClearance.current
    val listState = rememberLazyListState()
    val returningHeader = rememberReturningHeaderState()
    val headerScrolledAway by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Box(modifier.fillMaxSize().nestedScroll(returningHeader.nestedScrollConnection)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navClearance + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                ScreenHeader(title = "Studio", subtitle = "Turn your books into audiobooks", horizontalPadding = 4.dp) {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                }
            }
            ui.statusMessage?.let { msg ->
                item(key = "status") { StatusBanner(msg, viewModel::dismissStatus) }
            }

            // Setup the engine first, if it isn't ready
            if (!ui.engineReady) {
                item(key = "setup") {
                    if (ui.selectedEngine == TtsEngineType.LOCAL_KOKORO) {
                        StudioCard(title = "Set up the narrator") {
                            KokoroModelPanel(
                                ui.kokoroModel,
                                onDownload = viewModel::downloadKokoroModel,
                                onCancel = viewModel::cancelKokoroDownload,
                                onImport = { modelArchivePicker.launch(arrayOf("*/*")) },
                                onDelete = viewModel::deleteKokoroModel
                            )
                        }
                    } else {
                        StudioCard(title = "Add your API key") {
                            Text(
                                "${engineLabel(ui.selectedEngine)} needs your own API key before it can narrate.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(onClick = { sheet = StudioSheet.VOICES }) { Text("Open voice settings") }
                        }
                    }
                }
            }

            item(key = "narrator") {
                NarratorCard(
                    ui = ui,
                    onPreview = { viewModel.previewSpeakerVoice(ui.selectedVoice) },
                    onStopPreview = viewModel::stopVoicePreview,
                    onChange = { sheet = StudioSheet.VOICES },
                    onSpeed = viewModel::setSynthesisSpeed
                )
            }

            item(key = "new") {
                NewAudiobookCard(
                    ui = ui,
                    onSelectBook = viewModel::selectBook,
                    onOpenChapters = { sheet = StudioSheet.CHAPTERS },
                    onFormat = viewModel::setExportFormat,
                    onSyncFiles = { on ->
                        viewModel.toggleGenerateLrcSidecar(on)
                        viewModel.toggleGenerateJsonSidecar(on)
                    },
                    onCreate = viewModel::enqueueBatchAudiobookConversion
                )
            }

            // A finished job whose audiobook was deleted has nothing left to show
            val jobs = ui.conversionQueue.filter { job ->
                job.isRunning || job.errorMessage != null || job.outputAudiobookBookId == null ||
                    listenUi.availableAudiobooks.any { it.id == job.outputAudiobookBookId }
            }
            if (jobs.isNotEmpty()) {
                item(key = "jobs_title") {
                    Text("In the studio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
                items(jobs, key = { it.jobId }) { job ->
                    JobCard(
                        job = job,
                        book = ui.availableEbooks.firstOrNull { it.id == job.bookId },
                        onCancel = { viewModel.cancelConversionJob(job.jobId) },
                        onDismiss = { viewModel.dismissConversionJob(job.jobId) },
                        onListen = { (job.outputAudiobookBookId ?: job.bookId).let { onOpenGeneratedAudiobook?.invoke(it) } }
                    )
                }
            }

            // Everything narrated here, with play / share / save / delete
            if (studioBooks.isNotEmpty()) {
                item(key = "made_title") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                        Text("Made in Studio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${studioBooks.size} · ${android.text.format.Formatter.formatShortFileSize(context, studioBooks.sumOf { listenVm.fileSizeBytes(it) })}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item(key = "made_list") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            studioBooks.forEach { book ->
                                StudioMadeRow(
                                    book = book,
                                    onPlay = { onOpenGeneratedAudiobook?.invoke(book.id) },
                                    onMore = { actionsFor = book }
                                )
                            }
                        }
                    }
                }
            }
        }
        ReturningTopBar(
            visible = returningHeader.revealed && headerScrolledAway,
            title = "Studio",
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    actionsFor?.let { target ->
        val live = studioBooks.firstOrNull { it.id == target.id }
        if (live == null) actionsFor = null
        else com.example.ui.audio.AudiobookActionsSheet(book = live, viewModel = listenVm, onDismiss = { actionsFor = null })
    }

    when (sheet) {
        StudioSheet.VOICES -> VoicePickerSheet(
            ui = ui,
            onDismiss = { sheet = StudioSheet.NONE },
            onEngine = viewModel::selectEngine,
            onSelect = viewModel::selectVoice,
            onPreview = viewModel::previewSpeakerVoice,
            onStopPreview = viewModel::stopVoicePreview,
            onPreviewText = viewModel::updatePreviewSampleText,
            onCloudConfig = viewModel::updateCloudEndpointSettings,
            onDownloadModel = viewModel::downloadKokoroModel,
            onCancelDownload = viewModel::cancelKokoroDownload,
            onImportModel = { modelArchivePicker.launch(arrayOf("*/*")) },
            onDeleteModel = viewModel::deleteKokoroModel
        )
        StudioSheet.CHAPTERS -> ChapterPickerSheet(
            ui = ui,
            onDismiss = { sheet = StudioSheet.NONE },
            onToggle = viewModel::toggleChapterSelection,
            onStory = viewModel::selectStoryChapters,
            onAll = viewModel::selectAllChapters,
            onNone = viewModel::clearChapterSelection
        )
        StudioSheet.NONE -> Unit
    }
}

// ---- Building blocks ---------------------------------------------------------------------------

@Composable
private fun StudioCard(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = PlayfairDisplay, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
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

private fun engineLabel(engine: TtsEngineType) = when (engine) {
    TtsEngineType.LOCAL_KOKORO -> "Kokoro"
    TtsEngineType.CLOUD_OPENAI -> "OpenAI"
    TtsEngineType.CLOUD_ELEVENLABS -> "ElevenLabs"
    TtsEngineType.CLOUD_GEMINI -> "Gemini"
}

private fun accentLabel(code: String): String = when (code) {
    "en-US" -> "American English"
    "en-GB" -> "British English"
    "es" -> "Spanish"
    "fr" -> "French"
    "hi" -> "Hindi"
    "it" -> "Italian"
    "ja" -> "Japanese"
    "pt-BR" -> "Brazilian Portuguese"
    "zh" -> "Mandarin Chinese"
    else -> code
}

/** Round voice "avatar": the voice's initial on a soft tint (warmer for female, cooler for male). */
@Composable
private fun VoiceAvatar(voice: TtsVoiceProfile, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val female = voice.gender.equals("Female", true)
    val bg = if (female) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (female) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(voice.name.take(1), color = fg, style = MaterialTheme.typography.titleMedium, fontFamily = PlayfairDisplay)
    }
}

// ---- Narrator --------------------------------------------------------------------------------

@Composable
private fun NarratorCard(
    ui: ConverterUiState,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onChange: () -> Unit,
    onSpeed: (Float) -> Unit
) {
    val voice = ui.selectedVoice
    val previewing = ui.isPreviewPlaying && ui.previewingVoiceId == voice.id
    StudioCard(title = "Narrator", trailing = { TextButton(onClick = onChange) { Text("Change") } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceAvatar(voice, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(voice.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${accentLabel(voice.languageCode)} · ${voice.gender}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (ui.selectedEngine == TtsEngineType.LOCAL_KOKORO) "Kokoro · on your phone" else "${engineLabel(ui.selectedEngine)} · cloud",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            FilledTonalIconButton(onClick = { if (previewing) onStopPreview() else onPreview() }, enabled = ui.engineReady) {
                when {
                    previewing && ui.statusMessage?.startsWith("Generating") == true -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    previewing -> Icon(Icons.Default.Stop, "Stop preview")
                    else -> Icon(Icons.AutoMirrored.Filled.VolumeUp, "Preview ${voice.name}")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pace", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
            SlimSlider(
                value = ui.synthesisSpeed,
                onValueChange = { onSpeed((it * 20).roundToInt() / 20f) },
                valueRange = 0.5f..2f,
                modifier = Modifier.weight(1f).testTag("synthesis_speed_slider")
            )
            Text(
                "%.2f×".format(ui.synthesisSpeed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp)
            )
        }
        Text(
            "Also reads aloud for you in the Reader.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- New audiobook ----------------------------------------------------------------------------

@Composable
private fun NewAudiobookCard(
    ui: ConverterUiState,
    onSelectBook: (BookEntity) -> Unit,
    onOpenChapters: () -> Unit,
    onFormat: (AudioExportFormat) -> Unit,
    onSyncFiles: (Boolean) -> Unit,
    onCreate: () -> Unit
) {
    StudioCard(title = "New audiobook") {
        if (ui.availableEbooks.isEmpty()) {
            Text(
                "Add EPUB, MOBI, FB2 or TXT books in the Library first — they'll show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@StudioCard
        }
        // Book carousel
        val books = ui.availableEbooks.sortedByDescending { it.lastOpenedAt ?: 0L }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.id }) { book ->
                val selected = book.id == ui.selectedBook?.id
                Column(
                    Modifier
                        .width(92.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectBook(book) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                    ) {
                        BookCover(book, Modifier.fillMaxSize())
                        if (selected) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                            ) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp)) }
                        }
                    }
                    Text(
                        book.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        val book = ui.selectedBook ?: return@StudioCard
        val selected = ui.chapters.filter { it.chapterIndex in ui.selectedChapterIndices }
        val words = selected.sumOf { it.wordCount }
        val audioMs = (words / NARRATION_WPM / ui.synthesisSpeed.coerceAtLeast(0.5f) * 60_000f).toLong()

        // Chapters summary → picker
        Surface(
            onClick = onOpenChapters,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${selected.size} of ${ui.chapters.size} chapters" +
                            if (audioMs > 0) " · about ${formatDurationWords(audioMs)} of audio" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Choose chapters", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Output
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Format", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                listOf(AudioExportFormat.M4B to "M4B", AudioExportFormat.WAV to "WAV").forEachIndexed { i, (fmt, label) ->
                    SegmentedButton(
                        selected = ui.exportFormat == fmt,
                        onClick = { onFormat(fmt) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {}
                    ) { Text(label) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onSyncFiles(!(ui.generateLrcSidecar && ui.generateJsonSyncSidecar)) }) {
            Column(Modifier.weight(1f)) {
                Text("Follow along with the text", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Saves sentence timings, so the words light up as it plays",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = ui.generateLrcSidecar && ui.generateJsonSyncSidecar, onCheckedChange = onSyncFiles)
        }

        Button(
            onClick = onCreate,
            enabled = selected.isNotEmpty() && ui.engineReady,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("start_batch_conversion_button")
        ) {
            Icon(Icons.Default.GraphicEq, null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !ui.engineReady -> "Set up the narrator first"
                    selected.isEmpty() -> "Choose some chapters"
                    else -> "Create audiobook"
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            if (ui.selectedEngine == TtsEngineType.LOCAL_KOKORO) {
                "Made on your phone, in the background — you can leave the app. Narrating takes about twice as long as the audio it makes."
            } else {
                "Made in the background using your ${engineLabel(ui.selectedEngine)} account."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- Jobs -------------------------------------------------------------------------------------

@Composable
private fun JobCard(
    job: BatchConversionProgress,
    book: BookEntity?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onListen: () -> Unit
) {
    val done = !job.isRunning && job.errorMessage == null
    val failed = !job.isRunning && job.errorMessage != null
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("conversion_job_card_${job.jobId}")
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (book != null) {
                BookCover(book, Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book?.title ?: "Audiobook", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    job.isRunning -> {
                        val pct = (job.overallProgressFraction * 100).roundToInt()
                        LinearProgressIndicator(
                            progress = { job.overallProgressFraction.coerceIn(0f, 1f) },
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                        )
                        Text(
                            if (job.totalChaptersCount > 0 && job.currentChapterTitle != null) {
                                "$pct% · chapter ${(job.completedChaptersCount + 1).coerceAtMost(job.totalChaptersCount)} of ${job.totalChaptersCount} · ${job.currentChapterTitle}"
                            } else "$pct% · getting started…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        job.etaText?.let {
                            Text(
                                it.replace("remaining", "left"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    done -> Text("Ready to listen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    else -> Text(
                        job.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.errorMessage == "Cancelled by user") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                job.isRunning -> TextButton(onClick = onCancel) { Text("Stop") }
                done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (job.outputAudiobookFileUri != null) {
                        FilledTonalButton(onClick = onListen) {
                            Icon(Icons.Default.Headphones, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Listen")
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Remove from list") }
                }
                failed -> IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Remove from list") }
            }
        }
    }
}

// ---- Voice picker -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    ui: ConverterUiState,
    onDismiss: () -> Unit,
    onEngine: (TtsEngineType) -> Unit,
    onSelect: (TtsVoiceProfile) -> Unit,
    onPreview: (TtsVoiceProfile) -> Unit,
    onStopPreview: () -> Unit,
    onPreviewText: (String) -> Unit,
    onCloudConfig: (String, String, String) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onImportModel: () -> Unit,
    onDeleteModel: () -> Unit
) {
    var accent by rememberSaveable(ui.selectedEngine) { mutableStateOf<String?>(null) }
    var gender by rememberSaveable(ui.selectedEngine) { mutableStateOf<String?>(null) }
    val voices = ui.availableVoices
    val accents = voices.map { it.languageCode }.distinct()
    val shown = voices.filter { (accent == null || it.languageCode == accent) && (gender == null || it.gender.equals(gender, true)) }

    ModalBottomSheet(
        onDismissRequest = { onStopPreview(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose a narrator", style = MaterialTheme.typography.titleLarge, fontFamily = PlayfairDisplay)
                    val engines = listOf(
                        TtsEngineType.LOCAL_KOKORO, TtsEngineType.CLOUD_OPENAI,
                        TtsEngineType.CLOUD_ELEVENLABS, TtsEngineType.CLOUD_GEMINI
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        engines.forEachIndexed { i, engine ->
                            SegmentedButton(
                                selected = ui.selectedEngine == engine,
                                onClick = { onEngine(engine) },
                                shape = SegmentedButtonDefaults.itemShape(i, engines.size),
                                icon = {},
                                modifier = Modifier.testTag("engine_chip_${engine.name}")
                            ) { Text(engineLabel(engine), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                        }
                    }
                    if (ui.selectedEngine == TtsEngineType.LOCAL_KOKORO) {
                        KokoroModelPanel(ui.kokoroModel, onDownloadModel, onCancelDownload, onImportModel, onDeleteModel)
                    } else {
                        OutlinedTextField(
                            value = ui.cloudApiKey,
                            onValueChange = { onCloudConfig(ui.cloudBaseUrl, it, ui.cloudModelId) },
                            label = { Text("API key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = ui.cloudModelId,
                                onValueChange = { onCloudConfig(ui.cloudBaseUrl, ui.cloudApiKey, it) },
                                label = { Text("Model") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ui.cloudBaseUrl,
                                onValueChange = { onCloudConfig(it, ui.cloudApiKey, ui.cloudModelId) },
                                label = { Text("Base URL") },
                                singleLine = true,
                                modifier = Modifier.weight(1.4f)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = ui.previewSampleText,
                        onValueChange = onPreviewText,
                        label = { Text("Preview sentence") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    FilterChip(selected = gender == "Female", onClick = { gender = if (gender == "Female") null else "Female" }, label = { Text("Female") })
                    FilterChip(selected = gender == "Male", onClick = { gender = if (gender == "Male") null else "Male" }, label = { Text("Male") })
                    if (accents.size > 1) {
                        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        accents.forEach { code ->
                            FilterChip(
                                selected = accent == code,
                                onClick = { accent = if (accent == code) null else code },
                                label = { Text(accentLabel(code)) }
                            )
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { voice ->
                val selected = voice.id == ui.selectedVoice.id
                val previewing = ui.isPreviewPlaying && ui.previewingVoiceId == voice.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(voice) }
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("voice_card_${voice.id}")
                ) {
                    VoiceAvatar(voice)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(voice.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(
                            "${accentLabel(voice.languageCode)} · ${voice.gender}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { if (previewing) onStopPreview() else onPreview(voice) }, enabled = ui.engineReady) {
                        when {
                            previewing && ui.statusMessage?.startsWith("Generating") == true ->
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            previewing -> Icon(Icons.Default.Stop, "Stop preview", tint = MaterialTheme.colorScheme.primary)
                            else -> Icon(Icons.AutoMirrored.Filled.VolumeUp, "Preview ${voice.name}", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                    else Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

// ---- Chapter picker ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterPickerSheet(
    ui: ConverterUiState,
    onDismiss: () -> Unit,
    onToggle: (Int) -> Unit,
    onStory: () -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        val selectedCount = ui.selectedChapterIndices.size
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Chapters", style = MaterialTheme.typography.titleLarge, fontFamily = PlayfairDisplay)
                    Text(
                        "$selectedCount of ${ui.chapters.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = onDismiss) { Text("Done") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                OutlinedButton(onClick = onStory) { Text("Story only") }
                OutlinedButton(onClick = onAll) { Text("All") }
                OutlinedButton(onClick = onNone) { Text("None") }
            }
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), modifier = Modifier.heightIn(max = 620.dp)) {
                items(ui.chapters, key = { it.id }) { chapter ->
                    ChapterRow(chapter, chapter.chapterIndex in ui.selectedChapterIndices, ui.synthesisSpeed) { onToggle(chapter.chapterIndex) }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntity, checked: Boolean, speed: Float, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp)
            .testTag("chapter_row_${chapter.chapterIndex}")
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(chapter.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val minutes = chapter.wordCount / NARRATION_WPM / speed.coerceAtLeast(0.5f)
            Text(
                if (chapter.wordCount < 30) "Pictures / front matter" else "${chapter.wordCount} words · ~${formatDurationWords((minutes * 60_000f).toLong())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (chapter.isTtsConverted) {
            Icon(Icons.Default.CheckCircle, "Already made", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

// ---- Kokoro model -----------------------------------------------------------------------------

@Composable
private fun KokoroModelPanel(
    model: KokoroModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (model.installed) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        model.installed -> Icons.Default.CheckCircle
                        model.errorMessage != null -> Icons.Default.ErrorOutline
                        else -> Icons.Default.Download
                    },
                    contentDescription = null,
                    tint = if (model.errorMessage != null && !model.installed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (model.installed) "Kokoro voices installed" else "Kokoro voices (on-device)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        when {
                            model.busy -> model.statusText
                            model.installed -> "Works offline · ${model.installedSizeBytes / 1_048_576} MB · 53 voices"
                            else -> "One-time download (~130 MB, Wi-Fi recommended). Free and offline afterwards."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (model.installed && !model.busy) {
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
            }
            if (model.busy) {
                val p = model.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            model.errorMessage?.takeIf { !model.busy }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!model.installed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.busy) {
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    } else {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                        OutlinedButton(onClick = onImport) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("From file")
                        }
                    }
                }
            }
        }
    }
}

/** A narration made here: cover, title, voice and length, with play and more actions. */
@Composable
private fun StudioMadeRow(book: BookEntity, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onMore)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
    ) {
        BookCover(book, Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    book.studioVoiceName,
                    book.totalDurationMs.takeIf { it > 0 }?.let { com.example.ui.audio.formatDurationWords(it) },
                    when {
                        book.progressionPercentage >= 0.98f -> "finished"
                        book.progressionPercentage > 0.005f -> "${(book.progressionPercentage * 100).roundToInt()}% listened"
                        else -> null
                    }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FilledTonalIconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play") }
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "Share, save or delete") }
    }
}
