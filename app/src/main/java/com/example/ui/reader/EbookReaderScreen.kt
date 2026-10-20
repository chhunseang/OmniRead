package com.example.ui.reader

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.AnnotationStyle
import com.example.data.local.entity.HighlightEntity
import com.example.data.local.entity.TtsEngineType
import com.example.data.reader.ReaderPreferencesRepository
import com.example.domain.reader.PageTurnMode
import com.example.domain.reader.ReaderChapterDocument
import com.example.domain.reader.ReaderPreferences
import com.example.domain.reader.ReaderThemePreset
import com.example.domain.reader.ReaderViewportState
import com.example.domain.reader.SentenceLocator
import com.example.domain.reader.TapZoneAction
import com.example.domain.tts.LiveTtsReadAloudStatus
import com.example.domain.tts.LiveTtsSessionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookReaderScreen(
    modifier: Modifier = Modifier,
    viewModel: EbookReaderViewModel = hiltViewModel(),
    onOpenAudiobookConverter: (() -> Unit)? = null
) {
    val viewportState by viewModel.viewportState.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPreferences.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsSessionState.collectAsStateWithLifecycle()
    val activeTtsSentence by viewModel.activeTtsSentence.collectAsStateWithLifecycle()
    val overlayState by viewModel.overlayState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(overlayState.screenBrightnessLevel) {
        (context as? Activity)?.window?.let { win ->
            val params = win.attributes
            params.screenBrightness = overlayState.screenBrightnessLevel.coerceIn(0.05f, 1.0f)
            win.attributes = params
        }
    }

    val customFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "CustomFont.ttf"
            viewModel.importCustomFontFile(uri.toString(), fileName)
        }
    }

    val bgColor = Color(prefs.customBackgroundArgb)
    val textColor = Color(prefs.customTextArgb)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .testTag("ebook_reader_root")
    ) {
        // 1. Main Reading Canvas (Paginated or Continuous Scroll) + 9-Zone Tap & Edge Swipe Detectors
        ReaderContentViewport(
            viewportState = viewportState,
            prefs = prefs,
            activeTtsSentence = activeTtsSentence,
            showTapGridGuide = overlayState.showTapGridVisualGuide,
            bgColor = bgColor,
            textColor = textColor,
            onTapZone = viewModel::onTapZoneTriggered,
            onSentenceTapped = viewModel::onSentenceTappedInReader,
            onSentenceLongPressed = viewModel::openSentenceAnnotationPopup,
            onLeftEdgeBrightnessDrag = viewModel::adjustBrightnessByDelta,
            onRightEdgeVolumeDrag = viewModel::adjustVolumeByDelta,
            onEdgeDragFinished = viewModel::clearGestureHudLabel,
            onVisibleBoundsChanged = viewModel::updateVisiblePageCharBounds
        )

        // 2. Blue-Light Warmth Filter Overlay (Moon+ Reader Eye Protection)
        if (prefs.blueLightFilterEnabled && prefs.blueLightFilterIntensity > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color(0xFFFF8F00).copy(alpha = (prefs.blueLightFilterIntensity * 0.45f).coerceIn(0f, 0.45f))
                    )
            )
        }

        // 3. Edge-Swipe Brightness / Volume HUD Indicator
        AnimatedVisibility(
            visible = overlayState.activeGestureHudLabel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (overlayState.activeGestureHudLabel?.startsWith("Brightness") == true) {
                            Icons.Default.Brightness6
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = null,
                        tint = Color(0xFFFFB300)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = overlayState.activeGestureHudLabel.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. Live TTS Mini Floating Controller Bar when Read-Aloud is active
        AnimatedVisibility(
            visible = ttsState.status != LiveTtsReadAloudStatus.IDLE,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (overlayState.isChromeVisible) 88.dp else 20.dp, start = 16.dp, end = 16.dp)
        ) {
            LiveTtsFloatingBar(
                ttsState = ttsState,
                activeSentence = activeTtsSentence,
                onPlayPause = viewModel::toggleLiveTtsReadAloud,
                onPreviousSentence = viewModel::skipTtsPreviousSentence,
                onNextSentence = viewModel::skipTtsNextSentence,
                onStop = viewModel::stopLiveTtsReadAloud,
                onOpenTtsSheet = {
                    viewModel.selectBottomSheetTab(ReaderBottomSheetTab.LIVE_TTS_READ_ALOUD)
                }
            )
        }

        // 5. Top Chrome Toolbar
        AnimatedVisibility(
            visible = overlayState.isChromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = viewportState.book?.title ?: "OmniRead eBook Reader",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = viewportState.activeChapterDocument?.chapterTitle
                                ?: "Chapter ${viewportState.currentChapterIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::toggleTapGridVisualGuide,
                        modifier = Modifier.testTag("toggle_tap_grid_guide_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Toggle 9-Zone Tap Grid Overlay",
                            tint = if (overlayState.showTapGridVisualGuide) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    IconButton(
                        onClick = { viewModel.addBookmarkAtCurrentViewport() },
                        modifier = Modifier.testTag("quick_bookmark_button")
                    ) {
                        Icon(
                            imageVector = if (viewportState.chapterBookmarks.isNotEmpty()) {
                                Icons.Default.Bookmark
                            } else {
                                Icons.Default.BookmarkAdd
                            },
                            contentDescription = "Bookmark current page"
                        )
                    }
                    if (onOpenAudiobookConverter != null) {
                        AssistChip(
                            onClick = onOpenAudiobookConverter,
                            label = { Text("TTS Studio") },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("open_tts_studio_chip")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
        }

        // 6. Bottom Chrome Control Bar
        AnimatedVisibility(
            visible = overlayState.isChromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                modifier = Modifier.testTag("reader_bottom_bar")
            ) {
                IconButton(
                    onClick = {
                        viewModel.selectBottomSheetTab(ReaderBottomSheetTab.TOC_AND_BOOKMARKS)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_toc_sheet_button")
                ) {
                    Icon(Icons.Default.Toc, contentDescription = "Table of Contents & Annotations")
                }

                IconButton(
                    onClick = {
                        viewModel.selectBottomSheetTab(ReaderBottomSheetTab.THEMES_AND_TYPOGRAPHY)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_typography_sheet_button")
                ) {
                    Icon(Icons.Default.FormatSize, contentDescription = "Themes & Typography")
                }

                IconButton(
                    onClick = {
                        viewModel.selectBottomSheetTab(ReaderBottomSheetTab.TAP_GRID_AND_GESTURES)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_gestures_sheet_button")
                ) {
                    Icon(Icons.Default.GridOn, contentDescription = "9-Zone Tap Grid & Auto-Scroll")
                }

                IconButton(
                    onClick = {
                        viewModel.selectBottomSheetTab(ReaderBottomSheetTab.SEARCH_IN_BOOK)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_search_sheet_button")
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Full-Text Regex Search")
                }

                IconButton(
                    onClick = {
                        viewModel.selectBottomSheetTab(ReaderBottomSheetTab.LIVE_TTS_READ_ALOUD)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_tts_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = "Live AI Read-Aloud",
                        tint = if (ttsState.status != LiveTtsReadAloudStatus.IDLE) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }

        // 7. Multi-Color Highlight / Underline / Note & Dictionary Popup Dialog
        overlayState.pendingAnnotation?.let { pending ->
            SentenceAnnotationDialog(
                pending = pending,
                onDismiss = viewModel::dismissSentenceAnnotationPopup,
                onUpdateDraft = viewModel::updatePendingAnnotationDraft,
                onSaveAnnotation = viewModel::savePendingHighlightAndNote,
                onStartReadAloudFromHere = { viewModel.startTtsFromSentence(pending.sentenceLocator) },
                onLookupWord = viewModel::lookupWordInPendingPopup
            )
        }

        // 8. Customization & Navigation Modal Bottom Sheet
        if (overlayState.activeBottomSheetTab != ReaderBottomSheetTab.NONE) {
            ModalBottomSheet(
                onDismissRequest = {
                    viewModel.selectBottomSheetTab(ReaderBottomSheetTab.NONE)
                }
            ) {
                when (overlayState.activeBottomSheetTab) {
                    ReaderBottomSheetTab.TOC_AND_BOOKMARKS -> TocAndAnnotationsSheet(
                        viewportState = viewportState,
                        exportedJsonPreview = overlayState.exportedAnnotationsJsonPreview,
                        onSelectChapter = { idx ->
                            viewModel.loadChapter(idx)
                            viewModel.selectBottomSheetTab(ReaderBottomSheetTab.NONE)
                        },
                        onDeleteHighlight = viewModel::deleteHighlight,
                        onDeleteBookmark = viewModel::deleteBookmark,
                        onExportAnnotations = viewModel::exportAllBookAnnotations
                    )

                    ReaderBottomSheetTab.THEMES_AND_TYPOGRAPHY -> ThemesAndTypographySheet(
                        prefs = prefs,
                        onSelectTheme = viewModel::selectThemePreset,
                        onBlueLightFilterChange = viewModel::setBlueLightFilter,
                        onUpdateTypography = viewModel::updateTypography,
                        onUpdateMargins = viewModel::updatePageMargins,
                        onImportCustomFont = {
                            customFontPicker.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream"))
                        }
                    )

                    ReaderBottomSheetTab.TAP_GRID_AND_GESTURES -> TapGridAndPageTurnSheet(
                        prefs = prefs,
                        onSelectPageTurnMode = viewModel::setPageTurnMode,
                        onSetAutoScroll = viewModel::setAutoScroll,
                        onUpdateTapZone = viewModel::updateTapZoneCell
                    )

                    ReaderBottomSheetTab.SEARCH_IN_BOOK -> InBookRegexSearchSheet(
                        query = overlayState.searchQuery,
                        useRegex = overlayState.searchUseRegex,
                        caseSensitive = overlayState.searchCaseSensitive,
                        results = overlayState.searchResults,
                        onQueryChanged = viewModel::updateSearchQuery,
                        onSelectMatch = viewModel::jumpToSearchResult
                    )

                    ReaderBottomSheetTab.LIVE_TTS_READ_ALOUD -> LiveTtsReadAloudSheet(
                        ttsState = ttsState,
                        availableVoices = overlayState.availableVoices,
                        onTogglePlayPause = viewModel::toggleLiveTtsReadAloud,
                        onStop = viewModel::stopLiveTtsReadAloud,
                        onSelectVoice = viewModel::setTtsVoiceAndEngine,
                        onSpeedChange = viewModel::setTtsSpeed
                    )

                    ReaderBottomSheetTab.NONE -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContentViewport(
    viewportState: ReaderViewportState,
    prefs: ReaderPreferences,
    activeTtsSentence: SentenceLocator?,
    showTapGridGuide: Boolean,
    bgColor: Color,
    textColor: Color,
    onTapZone: (Int, Int) -> Unit,
    onSentenceTapped: (SentenceLocator) -> Unit,
    onSentenceLongPressed: (SentenceLocator) -> Unit,
    onLeftEdgeBrightnessDrag: (Float) -> Unit,
    onRightEdgeVolumeDrag: (Float) -> Unit,
    onEdgeDragFinished: () -> Unit,
    onVisibleBoundsChanged: (Int, Int) -> Unit
) {
    val doc = viewportState.activeChapterDocument
    val fontFamily = remember(prefs.customFontFamilyName) {
        when (prefs.customFontFamilyName.lowercase()) {
            "sansserif" -> FontFamily.SansSerif
            "monospace" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> FontFamily.Serif
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 9-Zone Screen Tap Detector
            .pointerInput(prefs.tapZoneGrid) {
                detectTapGestures { offset ->
                    val col = ((offset.x / size.width.coerceAtLeast(1)) * 3f).toInt().coerceIn(0, 2)
                    val row = ((offset.y / size.height.coerceAtLeast(1)) * 3f).toInt().coerceIn(0, 2)
                    onTapZone(row, col)
                }
            }
            // Left & Right Edge Vertical Swipe Gestures for Brightness & Volume
            .pointerInput(prefs.leftEdgeBrightnessSwipe, prefs.rightEdgeVolumeSwipe) {
                var activeEdge: Int = 0 // -1 = left (brightness), +1 = right (volume), 0 = none
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        val edgeZonePx = size.width * 0.14f
                        activeEdge = when {
                            startOffset.x <= edgeZonePx -> -1
                            startOffset.x >= size.width - edgeZonePx -> 1
                            else -> 0
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (activeEdge != 0) {
                            change.consume()
                            val deltaFraction = -dragAmount / size.height.coerceAtLeast(1).toFloat()
                            if (activeEdge == -1) {
                                onLeftEdgeBrightnessDrag(deltaFraction)
                            } else if (activeEdge == 1) {
                                onRightEdgeVolumeDrag(deltaFraction)
                            }
                        }
                    },
                    onDragEnd = {
                        activeEdge = 0
                        onEdgeDragFinished()
                    },
                    onDragCancel = {
                        activeEdge = 0
                        onEdgeDragFinished()
                    }
                )
            }
    ) {
        if (doc == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = viewportState.errorMessage ?: "Loading publication...",
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@BoxWithConstraints
        }

        if (prefs.pageTurnMode == PageTurnMode.VERTICAL_CONTINUOUS) {
            val listState = rememberLazyListState()

            // Continuous Auto-Scroll loop
            LaunchedEffect(prefs.autoScrollEnabled, prefs.autoScrollSpeedPixelsPerSec) {
                while (prefs.autoScrollEnabled) {
                    listState.scrollBy(prefs.autoScrollSpeedPixelsPerSec * 0.032f)
                    delay(32L)
                }
            }

            // Auto-scroll to active TTS sentence when playing
            LaunchedEffect(activeTtsSentence?.sentenceIndex) {
                val targetSentence = activeTtsSentence ?: return@LaunchedEffect
                if (targetSentence.chapterIndex == doc.chapterIndex) {
                    val itemIdx = targetSentence.sentenceIndex.coerceIn(0, maxOf(0, doc.sentences.size - 1))
                    listState.animateScrollToItem(itemIdx)
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = prefs.marginLeftDp.dp,
                    end = prefs.marginRightDp.dp,
                    top = (prefs.marginTopDp + 48f).dp,
                    bottom = (prefs.marginBottomDp + 96f).dp
                ),
                verticalArrangement = Arrangement.spacedBy(prefs.paragraphSpacingDp.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("continuous_reader_lazy_column")
            ) {
                item {
                    Text(
                        text = doc.chapterTitle,
                        color = textColor,
                        fontFamily = fontFamily,
                        fontSize = (prefs.fontSizeSp + 5f).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                items(
                    items = doc.sentences,
                    key = { it.sentenceId }
                ) { sentence ->
                    SentenceBlockItem(
                        sentence = sentence,
                        isActiveTtsSentence = activeTtsSentence?.sentenceId == sentence.sentenceId,
                        highlights = viewportState.chapterHighlights,
                        prefs = prefs,
                        fontFamily = fontFamily,
                        textColor = textColor,
                        onTap = { onSentenceTapped(sentence) },
                        onLongPress = { onSentenceLongPressed(sentence) }
                    )
                }
            }
        } else {
            // Paginated viewport (CURL_FLIP, SLIDE, or FADE)
            val totalSentences = doc.sentences.size.coerceAtLeast(1)
            val totalPages = viewportState.totalPagesInChapter.coerceAtLeast(1)
            val currentPage = viewportState.currentPageInChapter.coerceIn(0, totalPages - 1)
            val sentencesPerPage = maxOf(1, (totalSentences + totalPages - 1) / totalPages)
            val startIdx = (currentPage * sentencesPerPage).coerceIn(0, totalSentences - 1)
            val endIdx = minOf(totalSentences, startIdx + sentencesPerPage)
            val pageSentences = doc.sentences.subList(startIdx, endIdx)

            LaunchedEffect(currentPage, pageSentences) {
                val minChar = pageSentences.firstOrNull()?.startCharOffset ?: 0
                val maxChar = pageSentences.lastOrNull()?.endCharOffset ?: Int.MAX_VALUE
                onVisibleBoundsChanged(minChar, maxChar)
            }

            AnimatedContent(
                targetState = Triple(doc.chapterIndex, currentPage, pageSentences),
                transitionSpec = {
                    val forward = targetState.first > initialState.first ||
                        (targetState.first == initialState.first && targetState.second >= initialState.second)
                    when (prefs.pageTurnMode) {
                        PageTurnMode.CURL_FLIP, PageTurnMode.SLIDE -> {
                            val enterOffset: (Int) -> Int = { fullWidth -> if (forward) fullWidth else -fullWidth }
                            val exitOffset: (Int) -> Int = { fullWidth -> if (forward) -fullWidth / 3 else fullWidth / 3 }
                            (slideInHorizontally(tween(320), enterOffset) + fadeIn(tween(240)))
                                .togetherWith(slideOutHorizontally(tween(320), exitOffset) + fadeOut(tween(200)))
                        }
                        else -> {
                            fadeIn(tween(240)).togetherWith(fadeOut(tween(240)))
                        }
                    }.using(SizeTransform(clip = false))
                },
                label = "PaginatedPageTurnAnimation"
            ) { (chapterIdx, pageNum, visibleSentences) ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = prefs.marginLeftDp.dp,
                            end = prefs.marginRightDp.dp,
                            top = (prefs.marginTopDp + 48f).dp,
                            bottom = (prefs.marginBottomDp + 72f).dp
                        )
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(prefs.paragraphSpacingDp.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (pageNum == 0) {
                            item {
                                Text(
                                    text = doc.chapterTitle,
                                    color = textColor,
                                    fontFamily = fontFamily,
                                    fontSize = (prefs.fontSizeSp + 5f).sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }

                        items(
                            items = visibleSentences,
                            key = { it.sentenceId }
                        ) { sentence ->
                            SentenceBlockItem(
                                sentence = sentence,
                                isActiveTtsSentence = activeTtsSentence?.sentenceId == sentence.sentenceId,
                                highlights = viewportState.chapterHighlights,
                                prefs = prefs,
                                fontFamily = fontFamily,
                                textColor = textColor,
                                onTap = { onSentenceTapped(sentence) },
                                onLongPress = { onSentenceLongPressed(sentence) }
                            )
                        }
                    }

                    // Subtle Curl Spine Shadow when CURL_FLIP mode is active
                    if (prefs.pageTurnMode == PageTurnMode.CURL_FLIP) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(14.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.08f)
                                        )
                                    )
                                )
                        )
                    }

                    // Page Footer Status Bar (Chapter, Page X/Y, Overall %)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Ch ${chapterIdx + 1}/${viewportState.chapters.size} • ${doc.chapterTitle}",
                            color = textColor.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Page ${pageNum + 1}/${viewportState.totalPagesInChapter} • ${(viewportState.overallProgression * 100).toInt()}%",
                            color = textColor.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Visual 9-Zone Tap Grid Guide Overlay when toggled on
        if (showTapGridGuide) {
            TapZoneGridOverlayGuide(prefs = prefs)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SentenceBlockItem(
    sentence: SentenceLocator,
    isActiveTtsSentence: Boolean,
    highlights: List<HighlightEntity>,
    prefs: ReaderPreferences,
    fontFamily: FontFamily,
    textColor: Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val matchingHighlight = highlights.firstOrNull { h ->
        h.startCharOffset < sentence.endCharOffset && h.endCharOffset > sentence.startCharOffset
    }

    val backgroundHighlightColor = when {
        isActiveTtsSentence -> Color(0xFFFFC107).copy(alpha = 0.36f)
        matchingHighlight != null && matchingHighlight.style == AnnotationStyle.HIGHLIGHT ->
            Color(matchingHighlight.colorArgb).copy(alpha = 0.38f)
        else -> Color.Transparent
    }

    val textDecoration = when (matchingHighlight?.style) {
        AnnotationStyle.UNDERLINE -> TextDecoration.Underline
        AnnotationStyle.STRIKETHROUGH -> TextDecoration.LineThrough
        else -> TextDecoration.None
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundHighlightColor)
            .border(
                width = if (isActiveTtsSentence) 1.5.dp else 0.dp,
                color = if (isActiveTtsSentence) Color(0xFFFFA000) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("reader_sentence_${sentence.sentenceIndex}")
    ) {
        Text(
            text = sentence.text,
            color = textColor,
            fontFamily = fontFamily,
            fontSize = prefs.fontSizeSp.sp,
            fontWeight = FontWeight(prefs.fontWeight.coerceIn(300, 800)),
            lineHeight = (prefs.fontSizeSp * prefs.lineSpacingMultiplier).sp,
            letterSpacing = (prefs.wordSpacingSp * 0.15f).sp,
            textAlign = if (prefs.fullJustification) TextAlign.Justify else TextAlign.Start,
            textDecoration = textDecoration
        )

        if (!matchingHighlight?.noteMarkdown.isNullOrBlank()) {
            Surface(
                color = Color(matchingHighlight?.colorArgb ?: 0xFFFFEB3B).copy(alpha = 0.25f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "📝 ${matchingHighlight?.noteMarkdown}",
                    color = textColor.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TapZoneGridOverlayGuide(prefs: ReaderPreferences) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
    ) {
        for (row in 0..2) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                for (col in 0..2) {
                    val action = prefs.tapZoneGrid.actionForZone(row, col)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(1.dp, Color.White.copy(alpha = 0.55f))
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.68f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Zone (${row + 1},${col + 1})\n${action.name.replace('_', ' ')}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTtsFloatingBar(
    ttsState: LiveTtsSessionState,
    activeSentence: SentenceLocator?,
    onPlayPause: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onStop: () -> Unit,
    onOpenTtsSheet: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_tts_floating_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenTtsSheet() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI Read-Aloud (${ttsState.activeVoiceId} • ${"%.2f".format(ttsState.playbackSpeed)}x)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = activeSentence?.text ?: "Pre-buffering 24kHz neural sentences...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onPreviousSentence) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous sentence")
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.testTag("live_tts_play_pause_button")
            ) {
                Icon(
                    imageVector = if (ttsState.status == LiveTtsReadAloudStatus.PLAYING ||
                        ttsState.status == LiveTtsReadAloudStatus.PRE_BUFFERING
                    ) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = "Play or Pause Read-Aloud"
                )
            }
            IconButton(onClick = onNextSentence) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next sentence")
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop Read-Aloud")
            }
        }
    }
}

@Composable
private fun SentenceAnnotationDialog(
    pending: PendingSentenceAnnotationState,
    onDismiss: () -> Unit,
    onUpdateDraft: (Long?, AnnotationStyle?, String?) -> Unit,
    onSaveAnnotation: () -> Unit,
    onStartReadAloudFromHere: () -> Unit,
    onLookupWord: (String) -> Unit
) {
    val paletteColors = listOf(
        0xFFFFEB3B to "Yellow",
        0xFF81C784 to "Mint",
        0xFF64B5F6 to "Sky",
        0xFFF06292 to "Rose",
        0xFFBA68C8 to "Violet"
    )
    var lookupWordInput by remember {
        mutableStateOf(
            pending.sentenceLocator.text.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sentence_annotation_dialog")
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Highlight, Note & Dictionary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = "“${pending.sentenceLocator.text}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                // Multi-color Highlight Swatches
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    paletteColors.forEach { (argb, label) ->
                        val selected = pending.selectedColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { onUpdateDraft(argb, null, null) }
                                .testTag("highlight_color_$label")
                        )
                    }
                }

                // Style Chips (Highlight, Underline, Strikethrough)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = pending.selectedStyle == AnnotationStyle.HIGHLIGHT,
                        onClick = { onUpdateDraft(null, AnnotationStyle.HIGHLIGHT, null) },
                        label = { Text("Highlight") },
                        leadingIcon = { Icon(Icons.Default.FormatColorFill, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = pending.selectedStyle == AnnotationStyle.UNDERLINE,
                        onClick = { onUpdateDraft(null, AnnotationStyle.UNDERLINE, null) },
                        label = { Text("Underline") },
                        leadingIcon = { Icon(Icons.Default.FormatUnderlined, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = pending.selectedStyle == AnnotationStyle.STRIKETHROUGH,
                        onClick = { onUpdateDraft(null, AnnotationStyle.STRIKETHROUGH, null) },
                        label = { Text("Strike") },
                        leadingIcon = { Icon(Icons.Default.FormatStrikethrough, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                OutlinedTextField(
                    value = pending.noteMarkdown,
                    onValueChange = { onUpdateDraft(null, null, it) },
                    label = { Text("Attach Markdown Study Note") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("annotation_note_input")
                )

                HorizontalDivider()

                // Dictionary Lookup Card
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = lookupWordInput,
                        onValueChange = { lookupWordInput = it },
                        label = { Text("Dictionary Lookup") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { onLookupWord(lookupWordInput) }) {
                        Text("Define")
                    }
                }

                pending.dictionaryResult?.let { dict ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "${dict.headword} ${dict.phonetic.orEmpty()} (${dict.partOfSpeech.orEmpty()})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            dict.definitions.forEach { def ->
                                Text(
                                    text = "• $def",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = onStartReadAloudFromHere,
                        modifier = Modifier.testTag("speak_from_sentence_button")
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Read From Here")
                    }

                    Button(
                        onClick = onSaveAnnotation,
                        modifier = Modifier.testTag("save_annotation_button")
                    ) {
                        Text("Save Annotation")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemesAndTypographySheet(
    prefs: ReaderPreferences,
    onSelectTheme: (ReaderThemePreset) -> Unit,
    onBlueLightFilterChange: (Boolean, Float) -> Unit,
    onUpdateTypography: (String?, Float?, Int?, Float?, Float?, Float?, Boolean?, Boolean?) -> Unit,
    onUpdateMargins: (Float, Float) -> Unit,
    onImportCustomFont: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
    ) {
        item {
            Text(
                text = "Visual Themes & Eye Protection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ReaderThemePreset.entries) { preset ->
                    val isSelected = prefs.themePreset == preset
                    Surface(
                        onClick = { onSelectTheme(preset) },
                        color = Color(preset.backgroundArgb),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                        ),
                        modifier = Modifier.testTag("theme_preset_${preset.name}")
                    ) {
                        Text(
                            text = preset.displayName,
                            color = Color(preset.textArgb),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Blue-Light Warmth", modifier = Modifier.width(135.dp))
                Switch(
                    checked = prefs.blueLightFilterEnabled,
                    onCheckedChange = { onBlueLightFilterChange(it, prefs.blueLightFilterIntensity) }
                )
                Slider(
                    value = prefs.blueLightFilterIntensity,
                    onValueChange = { onBlueLightFilterChange(true, it) },
                    valueRange = 0.05f..0.70f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { HorizontalDivider() }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Typography & Spacing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onImportCustomFont,
                    modifier = Modifier.testTag("import_ttf_font_button")
                ) {
                    Icon(Icons.Default.FontDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Load TTF/OTF")
                }
            }
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderPreferencesRepository.BUILTIN_FONT_FAMILIES.forEach { family ->
                    FilterChip(
                        selected = prefs.customFontFamilyName.equals(family, ignoreCase = true),
                        onClick = { onUpdateTypography(family, null, null, null, null, null, null, null) },
                        label = { Text(family) }
                    )
                }
            }
        }

        item {
            TypographySliderRow(
                label = "Font Size: ${prefs.fontSizeSp.toInt()} sp",
                value = prefs.fontSizeSp,
                range = 12f..36f,
                onValueChange = { onUpdateTypography(null, it, null, null, null, null, null, null) }
            )
            TypographySliderRow(
                label = "Font Weight: ${prefs.fontWeight}",
                value = prefs.fontWeight.toFloat(),
                range = 300f..700f,
                onValueChange = { onUpdateTypography(null, null, it.toInt(), null, null, null, null, null) }
            )
            TypographySliderRow(
                label = "Line Spacing: ${"%.2f".format(prefs.lineSpacingMultiplier)}x",
                value = prefs.lineSpacingMultiplier,
                range = 1.1f..2.2f,
                onValueChange = { onUpdateTypography(null, null, null, it, null, null, null, null) }
            )
            TypographySliderRow(
                label = "Paragraph Spacing: ${prefs.paragraphSpacingDp.toInt()} dp",
                value = prefs.paragraphSpacingDp,
                range = 2f..32f,
                onValueChange = { onUpdateTypography(null, null, null, null, it, null, null, null) }
            )
            TypographySliderRow(
                label = "Horizontal Margins: ${prefs.marginLeftDp.toInt()} dp",
                value = prefs.marginLeftDp,
                range = 8f..56f,
                onValueChange = { onUpdateMargins(it, prefs.marginTopDp) }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = prefs.fullJustification,
                    onClick = { onUpdateTypography(null, null, null, null, null, null, !prefs.fullJustification, null) },
                    label = { Text(if (prefs.fullJustification) "Full Justify" else "Left Align") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (prefs.fullJustification) {
                                Icons.Default.FormatAlignJustify
                            } else {
                                Icons.AutoMirrored.Filled.FormatAlignLeft
                            },
                            contentDescription = null
                        )
                    }
                )
                FilterChip(
                    selected = prefs.hyphenationEnabled,
                    onClick = { onUpdateTypography(null, null, null, null, null, null, null, !prefs.hyphenationEnabled) },
                    label = { Text("Auto-Hyphenation") }
                )
            }
        }
    }
}

@Composable
private fun TypographySliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(155.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TapGridAndPageTurnSheet(
    prefs: ReaderPreferences,
    onSelectPageTurnMode: (PageTurnMode) -> Unit,
    onSetAutoScroll: (Boolean, Float) -> Unit,
    onUpdateTapZone: (Int, Int, TapZoneAction) -> Unit
) {
    var selectedCell by remember { mutableStateOf(0 to 0) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
    ) {
        item {
            Text(
                text = "Page-Turn Mode & Auto-Scroll",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PageTurnMode.entries) { mode ->
                    FilterChip(
                        selected = prefs.pageTurnMode == mode,
                        onClick = { onSelectPageTurnMode(mode) },
                        label = { Text(mode.name.replace('_', ' ')) },
                        modifier = Modifier.testTag("page_turn_mode_${mode.name}")
                    )
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Auto-Scroll", modifier = Modifier.width(100.dp))
                Switch(
                    checked = prefs.autoScrollEnabled,
                    onCheckedChange = { onSetAutoScroll(it, prefs.autoScrollSpeedPixelsPerSec) }
                )
                Slider(
                    value = prefs.autoScrollSpeedPixelsPerSec,
                    onValueChange = { onSetAutoScroll(prefs.autoScrollEnabled, it) },
                    valueRange = 15f..180f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { HorizontalDivider() }

        item {
            Text(
                text = "Customizable 9-Zone Screen Tap Grid",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap a cell below to customize its assigned reader action:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0..2) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (c in 0..2) {
                            val isCellSelected = selectedCell == (r to c)
                            val action = prefs.tapZoneGrid.actionForZone(r, c)
                            OutlinedCard(
                                onClick = { selectedCell = r to c },
                                border = BorderStroke(
                                    width = if (isCellSelected) 2.dp else 1.dp,
                                    color = if (isCellSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "Zone (${r + 1},${c + 1})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = action.name.replace('_', ' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TapZoneAction.entries) { action ->
                    val currentAction = prefs.tapZoneGrid.actionForZone(selectedCell.first, selectedCell.second)
                    FilterChip(
                        selected = currentAction == action,
                        onClick = {
                            onUpdateTapZone(selectedCell.first, selectedCell.second, action)
                        },
                        label = { Text(action.name.replace('_', ' ')) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TocAndAnnotationsSheet(
    viewportState: ReaderViewportState,
    exportedJsonPreview: String?,
    onSelectChapter: (Int) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onExportAnnotations: () -> Unit
) {
    var activeSubTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeSubTab == 0,
                    onClick = { activeSubTab = 0 },
                    label = { Text("Chapters (${viewportState.chapters.size})") }
                )
                FilterChip(
                    selected = activeSubTab == 1,
                    onClick = { activeSubTab = 1 },
                    label = { Text("Highlights (${viewportState.chapterHighlights.size})") }
                )
                FilterChip(
                    selected = activeSubTab == 2,
                    onClick = { activeSubTab = 2 },
                    label = { Text("Bookmarks (${viewportState.chapterBookmarks.size})") }
                )
            }
            IconButton(
                onClick = onExportAnnotations,
                modifier = Modifier.testTag("export_annotations_button")
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "Export Annotations JSON")
            }
        }

        if (!exportedJsonPreview.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Exported JSON Bundle: ${exportedJsonPreview.take(140)}...",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (activeSubTab) {
                0 -> {
                    items(viewportState.chapters, key = { it.id }) { chapter ->
                        val isCurrent = chapter.chapterIndex == viewportState.currentChapterIndex
                        OutlinedCard(
                            onClick = { onSelectChapter(chapter.chapterIndex) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter.title,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    Text(
                                        text = "${chapter.wordCount} words",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    items(viewportState.chapterHighlights, key = { it.id }) { hl ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "“${hl.selectedText}”",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!hl.noteMarkdown.isNullOrBlank()) {
                                        Text(
                                            text = "Note: ${hl.noteMarkdown}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteHighlight(hl.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete highlight")
                                }
                            }
                        }
                    }
                }

                else -> {
                    items(viewportState.chapterBookmarks, key = { it.id }) { bm ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bm.label ?: bm.chapterTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = bm.snippetText,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2
                                    )
                                }
                                IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete bookmark")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InBookRegexSearchSheet(
    query: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    results: List<com.example.domain.reader.ReaderSearchResult>,
    onQueryChanged: (String, Boolean, Boolean) -> Unit,
    onSelectMatch: (com.example.domain.reader.ReaderSearchResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Full-Text & Regular Expression Search",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChanged(it, useRegex, caseSensitive) },
            label = { Text("Search book text or regex pattern...") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("in_book_search_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = useRegex,
                onClick = { onQueryChanged(query, !useRegex, caseSensitive) },
                label = { Text("Regex (.*)") }
            )
            FilterChip(
                selected = caseSensitive,
                onClick = { onQueryChanged(query, useRegex, !caseSensitive) },
                label = { Text("Case Sensitive") }
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${results.size} matches",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { match ->
                OutlinedCard(
                    onClick = { onSelectMatch(match) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${match.chapterTitle} • ${(match.progressionInChapter * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = match.surroundingSnippet,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTtsReadAloudSheet(
    ttsState: LiveTtsSessionState,
    availableVoices: List<com.example.domain.tts.TtsVoiceProfile>,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onSelectVoice: (TtsEngineType, com.example.domain.tts.TtsVoiceProfile) -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Live Read-Aloud with Real-Time Sentence Sync",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Pre-buffers 3 sentences ahead via Kokoro-82M / Cloud TTS, highlights each spoken sentence, and auto-turns pages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(availableVoices, key = { it.id }) { voice ->
                FilterChip(
                    selected = ttsState.activeVoiceId == voice.id,
                    onClick = { onSelectVoice(voice.engineType, voice) },
                    label = { Text(voice.name) }
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Speed, contentDescription = null)
            Text("Speed: ${"%.2f".format(ttsState.playbackSpeed)}x", modifier = Modifier.width(110.dp))
            Slider(
                value = ttsState.playbackSpeed,
                onValueChange = onSpeedChange,
                valueRange = 0.5f..3.0f,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sheet_tts_play_pause_button")
            ) {
                Icon(
                    imageVector = if (ttsState.status == LiveTtsReadAloudStatus.PLAYING) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (ttsState.status == LiveTtsReadAloudStatus.PLAYING) {
                        "Pause Read-Aloud"
                    } else {
                        "Start Read-Aloud"
                    }
                )
            }
            if (ttsState.status != LiveTtsReadAloudStatus.IDLE) {
                OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}
