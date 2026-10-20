package com.example.ui.audio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.TtsSyncCueEntity
import com.example.domain.audio.AudiobookPlaybackState
import com.example.domain.audio.SleepTimerMode
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookPlayerScreen(
    modifier: Modifier = Modifier,
    viewModel: AudiobookPlayerViewModel = hiltViewModel(),
    onOpenInFullEbookReader: ((String) -> Unit)? = null
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audiobook.m4b"
            viewModel.importAudiobookFileOrFolderUri(uri.toString(), name, isFolder = false)
        }
    }

    val audioFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val folderName = treeUri.lastPathSegment?.substringAfterLast(':') ?: "Audiobook Folder"
            viewModel.importAudiobookFileOrFolderUri(treeUri.toString(), folderName, isFolder = true)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = playbackState.activeBook?.title ?: "Local Audiobook Player",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(playbackState.activeBook?.author ?: "OmniRead Media3 Player")
                                playbackState.activeBook?.narrator?.let { append(" • Narrated by $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            audioFilePicker.launch(arrayOf("audio/*"))
                        },
                        modifier = Modifier.testTag("import_audio_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AudioFile,
                            contentDescription = "Open .m4b/.mp3/.flac file"
                        )
                    }
                    IconButton(
                        onClick = {
                            audioFolderPicker.launch(null)
                        },
                        modifier = Modifier.testTag("import_audio_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "Import multi-file audiobook folder"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Audiobook Selector Strip + "Switch to Synced Text View" Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(uiState.availableAudiobooks, key = { it.id }) { book ->
                        FilterChip(
                            selected = playbackState.activeBook?.id == book.id,
                            onClick = { viewModel.selectAndLoadAudiobook(book.id) },
                            label = {
                                Text(
                                    text = book.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                if (uiState.hasSyncedTextAvailable) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = uiState.isSyncedTextViewMode,
                        onClick = viewModel::toggleSyncedTextViewMode,
                        label = {
                            Text(if (uiState.isSyncedTextViewMode) "Cover View" else "Synced Text")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.isSyncedTextViewMode) {
                                    Icons.Default.Headphones
                                } else {
                                    Icons.Default.Subtitles
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("switch_synced_text_view_toggle")
                    )
                }
            }

            // 2. Center Hero Area: Cover Artwork Card OR Live Synchronized LRC/JSON Text View
            AnimatedContent(
                targetState = uiState.isSyncedTextViewMode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                label = "CoverVsSyncedTextContent"
            ) { showSyncedText ->
                if (showSyncedText) {
                    SyncedAudiobookTranscriptCard(
                        cues = uiState.chapterSyncCues,
                        activeCue = playbackState.activeSyncedCue,
                        onCueTapped = viewModel::seekToSyncCue,
                        onOpenInFullReader = {
                            playbackState.activeBook?.id?.let { id ->
                                onOpenInFullEbookReader?.invoke(id)
                            }
                        }
                    )
                } else {
                    AudiobookCoverArtCard(
                        playbackState = playbackState,
                        onSwitchToSyncedText = viewModel::toggleSyncedTextViewMode
                    )
                }
            }

            // 3. Interactive Waveform Progress Scrubber & Timestamps
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val activeChapter = playbackState.chapters.getOrNull(playbackState.currentChapterIndex)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeChapter?.title
                            ?: "Chapter ${playbackState.currentChapterIndex + 1} of ${max(1, playbackState.chapters.size)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (playbackState.sleepTimer.mode != SleepTimerMode.OFF) {
                        AssistChip(
                            onClick = { viewModel.openBottomSheet(AudiobookSheetType.SLEEP_TIMER) },
                            label = {
                                Text("Sleep ${formatTimeMs(playbackState.sleepTimer.remainingMs)}")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Bedtime,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                InteractiveWaveformScrubber(
                    amplitudes = playbackState.waveformAmplitudes,
                    progressFraction = (
                        playbackState.currentTrackPositionMs.toFloat() /
                            playbackState.currentTrackDurationMs.coerceAtLeast(1L).toFloat()
                        ).coerceIn(0f, 1f),
                    onScrubToFraction = { fraction ->
                        val targetMs = (fraction * playbackState.currentTrackDurationMs).toLong()
                        viewModel.seekToTrackPosition(targetMs)
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTimeMs(playbackState.currentTrackPositionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${"%.2f".format(playbackState.playbackSpeed)}x" +
                            (if (playbackState.skipSilenceEnabled) " • Trim Silence" else "") +
                            (if (playbackState.voiceBoostConfig.enabled) " • Voice Boost" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "-${formatTimeMs((playbackState.currentTrackDurationMs - playbackState.currentTrackPositionMs).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. Primary Transport Controls (Prev Chapter, Skip Back, Play/Pause, Skip Forward, Next Chapter)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val prevCh = (playbackState.currentChapterIndex - 1).coerceAtLeast(0)
                        viewModel.seekToChapter(prevCh)
                    },
                    modifier = Modifier.testTag("audio_prev_chapter_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous chapter",
                        modifier = Modifier.size(30.dp)
                    )
                }

                OutlinedButton(
                    onClick = viewModel::skipBackward,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("audio_skip_backward_button")
                ) {
                    Icon(Icons.Default.Replay10, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${playbackState.skipBackwardIntervalMs / 1000}s")
                }

                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier
                        .size(68.dp)
                        .testTag("audio_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }

                OutlinedButton(
                    onClick = viewModel::skipForward,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("audio_skip_forward_button")
                ) {
                    Text("${playbackState.skipForwardIntervalMs / 1000}s")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.Forward30, contentDescription = null, modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = {
                        val nextCh = (playbackState.currentChapterIndex + 1)
                            .coerceAtMost(max(0, playbackState.chapters.size - 1))
                        viewModel.seekToChapter(nextCh)
                    },
                    modifier = Modifier.testTag("audio_next_chapter_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next chapter",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // 5. Bottom Feature Action Strip (Chapters, Speed/EQ, Bookmark+Note, Sleep Timer)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButtonActionItem(
                        icon = Icons.Default.Toc,
                        label = "Chapters (${playbackState.chapters.size})",
                        tag = "open_audio_chapters_sheet_button",
                        onClick = { viewModel.openBottomSheet(AudiobookSheetType.CHAPTERS_AND_TRACKS) }
                    )
                    TextButtonActionItem(
                        icon = Icons.Default.Equalizer,
                        label = "${"%.1f".format(playbackState.playbackSpeed)}x & EQ",
                        tag = "open_audio_dsp_sheet_button",
                        onClick = { viewModel.openBottomSheet(AudiobookSheetType.SPEED_PITCH_AND_EQ) }
                    )
                    TextButtonActionItem(
                        icon = Icons.Default.BookmarkAdd,
                        label = "Add Note",
                        tag = "add_audio_bookmark_button",
                        onClick = viewModel::openCreateBookmarkDialog
                    )
                    TextButtonActionItem(
                        icon = Icons.Default.Bookmarks,
                        label = "Notes (${playbackState.bookmarks.size})",
                        tag = "open_audio_bookmarks_sheet_button",
                        onClick = { viewModel.openBottomSheet(AudiobookSheetType.AUDIO_BOOKMARKS) }
                    )
                    TextButtonActionItem(
                        icon = Icons.Default.Bedtime,
                        label = "Sleep",
                        tag = "open_sleep_timer_sheet_button",
                        onClick = { viewModel.openBottomSheet(AudiobookSheetType.SLEEP_TIMER) }
                    )
                }
            }
        }

        // Create Audio Bookmark with Note Dialog
        if (uiState.showCreateBookmarkDialog) {
            CreateAudioBookmarkDialog(
                label = uiState.bookmarkLabelDraft,
                note = uiState.bookmarkNoteDraft,
                activeCueText = playbackState.activeSyncedCue?.textContent,
                onUpdateDraft = viewModel::updateBookmarkDraft,
                onSave = viewModel::saveAudioBookmark,
                onDismiss = viewModel::dismissCreateBookmarkDialog
            )
        }

        // Bottom Sheets for Chapters, Speed/Pitch/EQ, Sleep Timer, and Bookmarks
        if (uiState.activeSheet != AudiobookSheetType.NONE) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.openBottomSheet(AudiobookSheetType.NONE) }
            ) {
                when (uiState.activeSheet) {
                    AudiobookSheetType.CHAPTERS_AND_TRACKS -> ChaptersSelectorSheet(
                        playbackState = playbackState,
                        onSelectChapter = viewModel::seekToChapter
                    )

                    AudiobookSheetType.SPEED_PITCH_AND_EQ -> SpeedPitchAndEqSheet(
                        playbackState = playbackState,
                        onSpeedChange = viewModel::setPlaybackSpeed,
                        onPitchChange = viewModel::setPlaybackPitch,
                        onSkipSilenceChange = viewModel::setSkipSilence,
                        onVoiceBoostChange = viewModel::setVoiceBoostEq,
                        onSkipIntervalsChange = viewModel::setCustomSkipIntervals
                    )

                    AudiobookSheetType.SLEEP_TIMER -> SleepTimerSheet(
                        playbackState = playbackState,
                        onStartTimer = viewModel::startSleepTimer,
                        onCancelTimer = viewModel::cancelSleepTimer
                    )

                    AudiobookSheetType.AUDIO_BOOKMARKS -> AudioBookmarksSheet(
                        bookmarks = playbackState.bookmarks,
                        onJumpToBookmark = viewModel::jumpToBookmark,
                        onDeleteBookmark = viewModel::deleteBookmark
                    )

                    AudiobookSheetType.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun AudiobookCoverArtCard(
    playbackState: AudiobookPlaybackState,
    onSwitchToSyncedText: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1A237E),
                            Color(0xFF283593),
                            Color(0xFF0D1333)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFFFB300).copy(alpha = 0.2f),
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }

                Text(
                    text = playbackState.activeBook?.title ?: "OmniRead Audiobook",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${playbackState.activeBook?.author ?: "Unknown Author"} • ${playbackState.activeBook?.format?.name ?: "M4B"}",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Live Subtitle Preview Box when LRC/JSON sync cue is active
                playbackState.activeSyncedCue?.let { cue ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSwitchToSyncedText() }
                    ) {
                        Text(
                            text = "“${cue.textContent}”",
                            color = Color(0xFFFFE082),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedAudiobookTranscriptCard(
    cues: List<TtsSyncCueEntity>,
    activeCue: TtsSyncCueEntity?,
    onCueTapped: (TtsSyncCueEntity) -> Unit,
    onOpenInFullReader: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("synced_audiobook_transcript_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Synchronized LRC / JSON Sentence View",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(
                    onClick = onOpenInFullReader,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("open_in_full_ebook_reader_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open eBook View", style = MaterialTheme.typography.labelSmall)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(cues, key = { it.id }) { cue ->
                    val isActive = activeCue?.id == cue.id
                    Surface(
                        onClick = { onCueTapped(cue) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        border = if (isActive) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTimeMs(cue.startTimeMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(48.dp)
                            )
                            Text(
                                text = cue.textContent,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveWaveformScrubber(
    amplitudes: List<Float>,
    progressFraction: Float,
    onScrubToFraction: (Float) -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onScrubToFraction(frac)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onScrubToFraction(frac)
                }
            }
            .testTag("audiobook_waveform_scrubber")
    ) {
        val bars = amplitudes.ifEmpty { List(64) { 0.4f } }
        val barCount = bars.size
        val slotWidth = size.width / barCount
        val barWidth = (slotWidth * 0.62f).coerceAtLeast(2f)

        for (i in 0 until barCount) {
            val barCenterFraction = (i + 0.5f) / barCount.toFloat()
            val amp = bars[i].coerceIn(0.10f, 1.0f)
            val barHeight = size.height * amp
            val topY = (size.height - barHeight) / 2f
            val leftX = i * slotWidth + (slotWidth - barWidth) / 2f

            drawRoundRect(
                color = if (barCenterFraction <= progressFraction) activeColor else inactiveColor,
                topLeft = Offset(leftX, topY),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun TextButtonActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ChaptersSelectorSheet(
    playbackState: AudiobookPlaybackState,
    onSelectChapter: (Int) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
    ) {
        item {
            Text(
                text = "Audiobook Chapters (${playbackState.chapters.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(playbackState.chapters, key = { it.id }) { chapter ->
            val isCurrent = chapter.chapterIndex == playbackState.currentChapterIndex
            OutlinedCard(
                onClick = { onSelectChapter(chapter.chapterIndex) },
                border = BorderStroke(
                    width = if (isCurrent) 2.dp else 1.dp,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            text = "${formatTimeMs(chapter.startTimeMs ?: 0L)} – ${formatTimeMs(chapter.endTimeMs ?: 0L)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Playing",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPitchAndEqSheet(
    playbackState: AudiobookPlaybackState,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSkipSilenceChange: (Boolean) -> Unit,
    onVoiceBoostChange: (Boolean, Int) -> Unit,
    onSkipIntervalsChange: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Playback Speed, Silence Trimming & Voice Boost EQ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speed, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Speed: ${"%.2f".format(playbackState.playbackSpeed)}x",
                modifier = Modifier.width(120.dp),
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = playbackState.playbackSpeed,
                onValueChange = onSpeedChange,
                valueRange = 0.5f..3.5f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("audiobook_speed_slider")
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { preset ->
                FilterChip(
                    selected = kotlin.math.abs(playbackState.playbackSpeed - preset) < 0.04f,
                    onClick = { onSpeedChange(preset) },
                    label = { Text("${preset}x") }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Pitch Correction: ${"%.2f".format(playbackState.playbackPitch)}x",
                modifier = Modifier.width(150.dp)
            )
            Slider(
                value = playbackState.playbackPitch,
                onValueChange = onPitchChange,
                valueRange = 0.7f..1.5f,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Trim & Skip Silence", fontWeight = FontWeight.Medium)
                Text(
                    "Uses Media3 SilenceSkippingAudioProcessor to remove dead air between sentences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = playbackState.skipSilenceEnabled,
                onCheckedChange = onSkipSilenceChange,
                modifier = Modifier.testTag("skip_silence_switch")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice Boost Equalizer & Loudness DSP", fontWeight = FontWeight.Medium)
                Text(
                    "Attenuates low rumble (<120Hz) and boosts spoken vocal presence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = playbackState.voiceBoostConfig.enabled,
                onCheckedChange = { onVoiceBoostChange(it, playbackState.voiceBoostConfig.loudnessGainMb) },
                modifier = Modifier.testTag("voice_boost_switch")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerSheet(
    playbackState: AudiobookPlaybackState,
    onStartTimer: (SleepTimerMode, Int, Boolean) -> Unit,
    onCancelTimer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Sleep Timer with Fade-Out & Shake-to-Reset",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Gently fades out audio over the final 10 seconds. Shake your phone to reset the countdown without unlocking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 15, 30, 45, 60).forEach { mins ->
                OutlinedButton(
                    onClick = {
                        onStartTimer(SleepTimerMode.DURATION_MINUTES, mins, true)
                    }
                ) {
                    Text("$mins min")
                }
            }
            OutlinedButton(
                onClick = { onStartTimer(SleepTimerMode.END_OF_CHAPTER, 0, true) }
            ) {
                Text("End of Chapter")
            }
            OutlinedButton(
                onClick = { onStartTimer(SleepTimerMode.END_OF_TRACK, 0, true) }
            ) {
                Text("End of Track")
            }
        }

        if (playbackState.sleepTimer.mode != SleepTimerMode.OFF) {
            Button(
                onClick = onCancelTimer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel Active Sleep Timer (${formatTimeMs(playbackState.sleepTimer.remainingMs)})")
            }
        }
    }
}

@Composable
private fun AudioBookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onJumpToBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
    ) {
        item {
            Text(
                text = "Audio Bookmarks & Study Notes (${bookmarks.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(bookmarks, key = { it.id }) { bm ->
            OutlinedCard(
                onClick = { onJumpToBookmark(bm) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bm.label ?: bm.chapterTitle,
                            fontWeight = FontWeight.Bold
                        )
                        if (!bm.noteMarkdown.isNullOrBlank()) {
                            Text(
                                text = "📝 ${bm.noteMarkdown}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = bm.snippetText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete audio bookmark")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateAudioBookmarkDialog(
    label: String,
    note: String,
    activeCueText: String?,
    onUpdateDraft: (String?, String?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add Audio Bookmark with Note",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (!activeCueText.isNullOrBlank()) {
                    Text(
                        text = "“$activeCueText”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { onUpdateDraft(it, null) },
                    label = { Text("Bookmark Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { onUpdateDraft(null, it) },
                    label = { Text("Markdown Note") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("audio_bookmark_note_input")
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
                        modifier = Modifier.testTag("save_audio_bookmark_button")
                    ) {
                        Text("Save Bookmark")
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}
