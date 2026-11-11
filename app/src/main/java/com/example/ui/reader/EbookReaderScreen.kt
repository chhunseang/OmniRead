package com.example.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.DpSize
import com.example.domain.reader.ReaderTexture
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.BookFormat
import com.example.domain.reader.ReaderPreferences
import com.example.domain.reader.ReaderThemePreset
import com.example.domain.reader.ReadingMode
import com.example.domain.reader.TapLayout
import com.example.domain.tts.LiveTtsReadAloudStatus
import com.example.domain.tts.LiveTtsSessionState
import com.example.ui.theme.Literata
import com.example.ui.components.EDGE_FADE_ALPHA
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
private fun rememberReaderFontFamily(prefs: ReaderPreferences): FontFamily {
    return remember(prefs.customFontFamilyName, prefs.customFontFileUri) {
        val customPath = prefs.customFontFileUri
        val customName = customPath?.let { File(it).nameWithoutExtension }
        when {
            customPath != null && prefs.customFontFamilyName == customName && File(customPath).exists() ->
                runCatching { FontFamily(Font(File(customPath))) }.getOrDefault(Literata)
            prefs.customFontFamilyName.equals("Serif", true) -> FontFamily.Serif
            prefs.customFontFamilyName.equals("Sans", true) -> FontFamily.SansSerif
            prefs.customFontFamilyName.equals("Mono", true) -> FontFamily.Monospace
            else -> Literata
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EbookReaderScreen(
    modifier: Modifier = Modifier,
    viewModel: EbookReaderViewModel = hiltViewModel(),
    onBackToLibrary: () -> Unit = {}
) {
    val viewport by viewModel.viewportState.collectAsStateWithLifecycle()
    val prefs by viewModel.readerPreferences.collectAsStateWithLifecycle()
    val tts by viewModel.ttsSessionState.collectAsStateWithLifecycle()
    val activeSentence by viewModel.activeTtsSentence.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    val bgColor = Color(prefs.customBackgroundArgb)
    val textColor = Color(prefs.customTextArgb)
    val fontFamily = rememberReaderFontFamily(prefs)
    val controller = remember { ReaderPageController() }
    val scope = rememberCoroutineScope()

    // Reader-only brightness / keep-screen-on, restored when leaving the reader
    val context = LocalContext.current
    DisposableEffect(prefs.screenBrightness) {
        val window = (context as? Activity)?.window
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = prefs.screenBrightness } }
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { viewModel.onReaderHidden() } }

    // Immersive reading: system bars only appear together with the reader menu
    DisposableEffect(ui.chromeVisible) {
        val window = (context as? Activity)?.window
        val insets = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (ui.chromeVisible) insets?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else insets?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "CustomFont.ttf"
            viewModel.importCustomFont(uri.toString(), name)
        }
    }

    val doc = viewport.activeChapterDocument
    val chapters = viewport.chapters
    val chapterIdx = viewport.currentChapterIndex
    val prevTitle = chapters.getOrNull(chapterIdx - 1)?.title
    val nextTitle = chapters.getOrNull(chapterIdx + 1)?.title
    val ttsActive = tts.status != LiveTtsReadAloudStatus.IDLE && tts.status != LiveTtsReadAloudStatus.ERROR
    val paragraphs = remember(doc) { doc?.let { buildParagraphs(it) }.orEmpty() }

    // PDFs: the slider moves between PDF pages (each PDF page is one "chapter")
    val isPdfBook = viewport.book?.format == BookFormat.PDF
    val pdfPageController = remember { ReaderPageController() }
    pdfPageController.pageCount = chapters.size
    pdfPageController.currentPage = chapterIdx
    pdfPageController.jump = { page -> viewModel.loadChapter(page) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .readerTexture(prefs.backgroundTexture)
            // The reader is an overlay on top of the library: it must swallow every touch itself
            // (even while a chapter is still loading) so nothing reaches the books underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                }
            }
            .testTag("ebook_reader_root")
    ) {
        when {
            viewport.book == null && !viewport.isLoading -> EmptyReaderState(textColor, onBackToLibrary)

            doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (viewport.errorMessage != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(viewport.errorMessage.orEmpty(), color = textColor, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onBackToLibrary) { Text("Back to Library") }
                    }
                } else {
                    CircularProgressIndicator(color = textColor.copy(alpha = 0.5f))
                }
            }

            prefs.readingMode == ReadingMode.CONTINUOUS || viewport.book?.format == BookFormat.PDF -> ContinuousReader(
                doc = doc,
                paragraphs = paragraphs,
                isPdf = viewport.book?.format == BookFormat.PDF,
                pdfFileUri = viewport.book?.fileUri,
                prefs = prefs,
                fontFamily = fontFamily,
                textColor = textColor,
                initialCharOffset = viewport.currentCharOffset,
                nextChapterTitle = nextTitle,
                activeSentence = activeSentence,
                ttsActive = ttsActive,
                controller = controller,
                onTapAction = { viewModel.toggleChrome() },
                onSentenceTap = viewModel::onSentenceTapped,
                onNextChapter = viewModel::nextChapter,
                onPreviousChapter = viewModel::previousChapter,
                onPositionChanged = viewModel::onReadingPositionChanged
            )

            else -> PagedReader(
                doc = doc,
                paragraphs = paragraphs,
                prefs = prefs,
                fontFamily = fontFamily,
                textColor = textColor,
                initialCharOffset = viewport.currentCharOffset,
                previousChapterTitle = prevTitle,
                nextChapterTitle = nextTitle,
                activeSentence = activeSentence,
                ttsActive = ttsActive,
                controller = controller,
                onTapAction = { viewModel.toggleChrome() },
                onSentenceTap = viewModel::onSentenceTapped,
                onGoToNextChapter = viewModel::nextChapter,
                onGoToPreviousChapterEnd = { if (chapterIdx > 0) viewModel.loadChapterAtEnd(chapterIdx - 1) },
                onPositionChanged = viewModel::onReadingPositionChanged
            )
        }

        // Warm-light filter
        if (prefs.blueLightFilterEnabled && prefs.blueLightFilterIntensity > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF8F00).copy(alpha = (prefs.blueLightFilterIntensity * 0.45f).coerceIn(0f, 0.4f)))
            )
        }

        // Status bar: battery + clock · chapter · page (visible while the menu is hidden)
        val continuous = prefs.readingMode == ReadingMode.CONTINUOUS
        val pageText = when {
            !prefs.showPageNumber || controller.pageCount <= 0 -> ""
            continuous -> "${((controller.currentPage + 1) * 100 / controller.pageCount).coerceIn(0, 100)}%"
            else -> "${controller.currentPage + 1} / ${controller.pageCount}"
        }
        if (doc != null && !isPdfBook && !ui.chromeVisible) {
            if (prefs.showStatusBar) {
                if (continuous) {
                    // Text scrolls underneath in continuous mode: fade it out behind the bar
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, bgColor)))
                    )
                }
                ReaderStatusBar(
                    chapterTitle = chapters.getOrNull(chapterIdx)?.title ?: doc.chapterTitle,
                    chapterNumber = chapterIdx + 1,
                    chapterCount = chapters.size,
                    pageText = pageText,
                    color = textColor,
                    background = bgColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = prefs.marginLeftDp.dp, end = prefs.marginRightDp.dp, bottom = 6.dp)
                )
            } else if (pageText.isNotEmpty() && !continuous) {
                Text(
                    pageText,
                    style = TextStyle(
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        shadow = Shadow(bgColor, blurRadius = 4f)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 6.dp)
                )
            }
        }

        // ---- Chrome: soft scrims behind the system bars, then floating cards ----
        // While scrubbing pages the rest of the chrome steps aside, so the page itself is visible
        var scrubbing by remember { mutableStateOf(false) }
        val chromeShown = ui.chromeVisible && viewport.book != null
        val scrimColor = MaterialTheme.colorScheme.surfaceContainer
        AnimatedVisibility(
            visible = chromeShown && !scrubbing,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 104.dp)
                    // Same edge fade as the rest of the app
                    .background(
                        Brush.verticalGradient(
                            0f to scrimColor.copy(alpha = EDGE_FADE_ALPHA),
                            1f to scrimColor.copy(alpha = 0f)
                        )
                    )
            )
        }
        AnimatedVisibility(
            visible = chromeShown && !scrubbing,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() + 176.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to scrimColor.copy(alpha = 0f),
                            1f to scrimColor.copy(alpha = EDGE_FADE_ALPHA)
                        )
                    )
            )
        }

        // ---- Top: floating title card ----
        AnimatedVisibility(
            visible = chromeShown && !scrubbing,
            enter = chromeEnter(fromTop = true),
            exit = chromeExit(toTop = true),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    IconButton(onClick = onBackToLibrary) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            viewport.book?.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            doc?.chapterTitle.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (chapters.size > 1) {
                        Text(
                            "${(viewport.overallProgression * 100).roundToInt().coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- Bottom: read-aloud mini player + menu ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            AnimatedVisibility(visible = tts.status != LiveTtsReadAloudStatus.IDLE) {
                ReadAloudBar(
                    tts = tts,
                    onPlayPause = viewModel::toggleReadAloud,
                    onPrevious = viewModel::skipToPreviousSentence,
                    onNext = viewModel::skipToNextSentence,
                    onStop = viewModel::stopReadAloud,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        .let { if (ui.chromeVisible) it else it.navigationBarsPadding() }
                )
            }
            AnimatedVisibility(
                visible = chromeShown,
                enter = chromeEnter(fromTop = false),
                exit = chromeExit(toTop = false)
            ) {
                ReaderBottomMenu(
                    prefs = prefs,
                    chapterIndex = chapterIdx,
                    chapterCount = chapters.size,
                    controller = if (isPdfBook) pdfPageController else controller,
                    readAloudPlaying = tts.status == LiveTtsReadAloudStatus.PLAYING,
                    onPreviousChapter = viewModel::previousChapter,
                    onNextChapter = viewModel::nextChapter,
                    onSeekPage = { page -> scope.launch { controller.goTo(page) } },
                    // Paged / scroll modes follow the finger live; PDFs jump when you let go
                    onScrub = if (isPdfBook) null else { page -> scope.launch { controller.snapTo(page) } },
                    onScrubbingChange = { scrubbing = it },
                    onContents = { viewModel.showSheet(ReaderSheet.CONTENTS) },
                    onReadingMode = viewModel::setReadingMode,
                    onReadAloud = viewModel::toggleReadAloud,
                    onSettings = { viewModel.showSheet(ReaderSheet.SETTINGS) }
                )
            }
        }

        ui.message?.let { msg ->
            LaunchedEffect(msg) {
                delay(2500)
                viewModel.dismissMessage()
            }
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }

    if (ui.sheet != ReaderSheet.NONE) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.showSheet(ReaderSheet.NONE) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            // Settings keep the page visible behind a light scrim, so changes show live
            scrimColor = if (ui.sheet == ReaderSheet.SETTINGS) Color.Black.copy(alpha = 0.12f)
            else androidx.compose.material3.BottomSheetDefaults.ScrimColor
        ) {
            when (ui.sheet) {
                ReaderSheet.CONTENTS -> ContentsSheet(
                    chapterTitles = chapters.map { it.title },
                    currentIndex = chapterIdx,
                    onSelect = viewModel::loadChapter
                )
                ReaderSheet.SETTINGS -> ReaderSettingsSheet(
                    prefs = prefs,
                    viewModel = viewModel,
                    onImportFont = {
                        fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/octet-stream"))
                    }
                )
                ReaderSheet.NONE -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reader chrome: a floating title card on top and one floating control card at the bottom
// ([◀ chapter] page slider [chapter ▶] over a row of labelled actions).
// ---------------------------------------------------------------------------------------------

/** Cards glide a short distance and fade in; the bottom card trails the top one slightly. */
private fun chromeEnter(fromTop: Boolean): EnterTransition {
    val delay = if (fromTop) 0 else 40
    return fadeIn(tween(durationMillis = 200, delayMillis = delay)) +
        slideInVertically(spring(dampingRatio = 0.9f, stiffness = 520f)) { h -> if (fromTop) -h / 2 else h / 3 }
}

private fun chromeExit(toTop: Boolean): ExitTransition =
    fadeOut(tween(140)) +
        slideOutVertically(tween(160)) { h -> if (toTop) -h / 3 else h / 4 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomMenu(
    prefs: ReaderPreferences,
    chapterIndex: Int,
    chapterCount: Int,
    controller: ReaderPageController,
    readAloudPlaying: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekPage: (Int) -> Unit,
    onScrub: ((Int) -> Unit)?,
    onScrubbingChange: (Boolean) -> Unit,
    onContents: () -> Unit,
    onReadingMode: (ReadingMode) -> Unit,
    onReadAloud: () -> Unit,
    onSettings: () -> Unit
) {
    val rtl = prefs.readingMode == ReadingMode.PAGED_RTL
    val density = LocalDensity.current
    val count = controller.pageCount.coerceAtLeast(1)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: controller.currentPage.toFloat()
    // Where the slider sits inside the panel, for the floating "page" bubble while dragging
    var panelCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var sliderBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val view = LocalView.current
    var lastScrubPage by remember { mutableIntStateOf(-1) }
    // One tick per page, thinned out for very long chapters so it stays a texture, not a buzz
    val tickStep = kotlin.math.ceil(count / 60f).toInt().coerceAtLeast(1)
    val tickConstant = if (android.os.Build.VERSION.SDK_INT >= 34) {
        android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    } else {
        android.view.HapticFeedbackConstants.CLOCK_TICK
    }

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .onGloballyPositioned { panelCoords = it }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                // Row 1: chapter skip · page slider · chapter skip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (leftEnabled, leftAction) = if (rtl) (chapterIndex + 1 < chapterCount) to onNextChapter
                    else (chapterIndex > 0) to onPreviousChapter
                    val (rightEnabled, rightAction) = if (rtl) (chapterIndex > 0) to onPreviousChapter
                    else (chapterIndex + 1 < chapterCount) to onNextChapter

                    IconButton(onClick = leftAction, enabled = leftEnabled) {
                        Icon(Icons.Default.SkipPrevious, if (rtl) "Next chapter" else "Previous chapter", Modifier.size(22.dp))
                    }
                    val left = if (rtl) "$count" else "${shown.roundToInt() + 1}"
                    val right = if (rtl) "${shown.roundToInt() + 1}" else "$count"
                    Text(
                        left,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(28.dp)
                    )
                    CompactSlider(
                        value = if (rtl) (count - 1 - shown) else shown,
                        range = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                        onChange = { v ->
                            val value = if (rtl) (count - 1 - v) else v
                            if (dragValue == null) {
                                lastScrubPage = controller.currentPage
                                onScrubbingChange(true)
                            }
                            dragValue = value
                            val page = value.roundToInt().coerceIn(0, count - 1)
                            if (page != lastScrubPage) {
                                if (page / tickStep != lastScrubPage / tickStep) view.performHapticFeedback(tickConstant)
                                lastScrubPage = page
                                onScrub?.invoke(page)
                            }
                        },
                        onChangeFinished = {
                            dragValue?.let { v ->
                                val page = v.roundToInt().coerceIn(0, count - 1)
                                if (onScrub == null) onSeekPage(page) else onScrub(page)
                            }
                            dragValue = null
                            onScrubbingChange(false)
                        },
                        enabled = count > 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                            .onGloballyPositioned { coords ->
                                sliderBounds = panelCoords?.takeIf { it.isAttached }?.localBoundingBoxOf(coords)
                            }
                    )
                    Text(
                        right,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(28.dp)
                    )
                    IconButton(onClick = rightAction, enabled = rightEnabled) {
                        Icon(Icons.Default.SkipNext, if (rtl) "Previous chapter" else "Next chapter", Modifier.size(22.dp))
                    }
                }

                // Row 2: labelled actions (tucked away while scrubbing)
                AnimatedVisibility(
                    visible = dragValue == null,
                    enter = fadeIn(tween(180)) + androidx.compose.animation.expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + androidx.compose.animation.shrinkVertically(tween(200))
                ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ChromeAction(Icons.AutoMirrored.Filled.ViewList, "Contents", onContents)
                    Box {
                        var modeMenu by remember { mutableStateOf(false) }
                        ChromeAction(Icons.Default.AutoStories, "Mode") { modeMenu = true }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            ReadingMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = { RadioButton(selected = prefs.readingMode == mode, onClick = null) },
                                    onClick = { modeMenu = false; onReadingMode(mode) }
                                )
                            }
                        }
                    }
                    ChromeAction(
                        if (readAloudPlaying) Icons.Default.Pause else Icons.Default.Headphones,
                        if (readAloudPlaying) "Pause" else "Listen",
                        onReadAloud
                    )
                    ChromeAction(Icons.Default.Settings, "Settings", onSettings)
                }
                }
            }
        }

        // Floating page bubble that follows the thumb while scrubbing
        val bounds = sliderBounds
        AnimatedVisibility(
            visible = dragValue != null && bounds != null,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val b = bounds ?: androidx.compose.ui.geometry.Rect.Zero
                val inset = with(density) { 2.dp.toPx() }
                val fraction = if (count > 1) {
                    val v = if (rtl) (count - 1 - shown) else shown
                    (v / (count - 1)).coerceIn(0f, 1f)
                } else 0f
                val centerX = b.left + inset + fraction * (b.width - inset * 2)
                val x = (centerX - placeable.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val y = (b.top - placeable.height - with(density) { 14.dp.toPx() }).roundToInt()
                layout(constraints.maxWidth, 0) { placeable.place(x, y) }
            }
        ) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Page ${shown.roundToInt() + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** Icon with a small label underneath, for the bottom action row. */
@Composable
private fun ChromeAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun EmptyReaderState(textColor: Color, onBackToLibrary: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = textColor.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No book open", color = textColor, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBackToLibrary) { Text("Open Library") }
    }
}

@Composable
private fun ReadAloudBar(
    tts: LiveTtsSessionState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 4.dp)) {
            if (tts.status == LiveTtsReadAloudStatus.ERROR) {
                Text(
                    tts.errorMessage ?: "Read-aloud failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                )
                IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Dismiss") }
                return@Row
            }
            Text(
                when (tts.status) {
                    LiveTtsReadAloudStatus.INITIALIZING_MODEL -> "Loading voice…"
                    LiveTtsReadAloudStatus.PRE_BUFFERING -> "Preparing…"
                    LiveTtsReadAloudStatus.PAUSED -> "Paused"
                    else -> "Reading aloud"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous sentence") }
            IconButton(onClick = onPlayPause) {
                when (tts.status) {
                    LiveTtsReadAloudStatus.INITIALIZING_MODEL, LiveTtsReadAloudStatus.PRE_BUFFERING ->
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    LiveTtsReadAloudStatus.PAUSED -> Icon(Icons.Default.PlayArrow, "Resume")
                    else -> Icon(Icons.Default.Pause, "Pause")
                }
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next sentence") }
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Stop") }
        }
    }
}

@Composable
private fun ContentsSheet(chapterTitles: List<String>, currentIndex: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth()) {
        Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(chapterTitles) { idx, title ->
                val selected = idx == currentIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(idx) }
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        "${idx + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp)
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings sheet: three compact tabs (Text · Page · Colour) made of grouped one-line rows.
// The sheet stays short and the scrim light, so the page above shows every change live.
// ---------------------------------------------------------------------------------------------

private data class ColorPair(val background: Long, val text: Long)

private val CUSTOM_COLOR_PAIRS = listOf(
    ColorPair(0xFFFFFFFF, 0xFF000000),
    ColorPair(0xFFEDE6D6, 0xFF3B3024),
    ColorPair(0xFFDCE8D5, 0xFF223322),
    ColorPair(0xFFDDE6F0, 0xFF1F2A36),
    ColorPair(0xFF2B2B2B, 0xFFE0E0E0),
    ColorPair(0xFF0E1A12, 0xFF9CCC9C),
    ColorPair(0xFF000000, 0xFFFFB74D)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    prefs: ReaderPreferences,
    viewModel: EbookReaderViewModel,
    onImportFont: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val maxBodyHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.46f).dp
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            val labels = listOf("Text", "Page", "Colour")
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    icon = {}
                ) { Text(label) }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (tab) {
                0 -> TextTab(prefs, viewModel, onImportFont)
                1 -> PageTab(prefs, viewModel)
                else -> ColourTab(prefs, viewModel)
            }
        }
    }
}

// ---- Text ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextTab(prefs: ReaderPreferences, viewModel: EbookReaderViewModel, onImportFont: () -> Unit) {
    val customName = prefs.customFontFileUri?.let { File(it).nameWithoutExtension }
    val current = prefs.customFontFamilyName
    val builtIns = listOf("Serif", "Sans", "Mono")

    SettingsGroup("Font") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            FontChip("Literata", Literata, current != customName && builtIns.none { it.equals(current, true) }) {
                viewModel.setFontFamily("Literata")
            }
            FontChip("Serif", FontFamily.Serif, current.equals("Serif", true)) { viewModel.setFontFamily("Serif") }
            FontChip("Sans", FontFamily.SansSerif, current.equals("Sans", true)) { viewModel.setFontFamily("Sans") }
            FontChip("Mono", FontFamily.Monospace, current.equals("Mono", true)) { viewModel.setFontFamily("Mono") }
            if (customName != null) {
                FontChip(customName, FontFamily.Default, current == customName) { viewModel.setFontFamily(customName) }
            }
            FilterChip(
                selected = false,
                onClick = onImportFont,
                label = { Text("Import…") },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }
            )
        }
        StepperRow(
            label = "Size",
            valueText = "${prefs.fontSizeSp.roundToInt()}",
            onMinus = { viewModel.setFontSize(prefs.fontSizeSp.roundToInt() - 1f) },
            onPlus = { viewModel.setFontSize(prefs.fontSizeSp.roundToInt() + 1f) }
        )
        val weights = listOf(300 to "Light", 400 to "Regular", 500 to "Medium", 700 to "Bold")
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 8.dp)
        ) {
            weights.forEachIndexed { i, (w, label) ->
                SegmentedButton(
                    selected = prefs.fontWeight == w,
                    onClick = { viewModel.setFontWeight(w) },
                    shape = SegmentedButtonDefaults.itemShape(i, weights.size),
                    icon = {}
                ) { Text(label, fontWeight = FontWeight(w), maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }

    SettingsGroup("Spacing") {
        SliderRow("Line", "%.1f".format(prefs.lineSpacingMultiplier), prefs.lineSpacingMultiplier, 1.0f..2.4f, viewModel::setLineSpacing)
        SliderRow("Paragraph", "${prefs.paragraphSpacingDp.roundToInt()}", prefs.paragraphSpacingDp, 0f..32f, viewModel::setParagraphSpacing)
    }

    SettingsGroup("Layout") {
        ToggleRow("Justify", prefs.fullJustification, viewModel::setJustified)
        ToggleRow("Indent first lines", prefs.firstLineIndent, viewModel::setFirstLineIndent)
        ToggleRow(
            "Book's own styles",
            prefs.publisherStyles,
            viewModel::setPublisherStyles,
            supporting = "Fonts, centred titles and indents from the book"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontChip(name: String, family: FontFamily, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(name, fontFamily = family, maxLines = 1) }
    )
}

// ---- Page ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PageTab(prefs: ReaderPreferences, viewModel: EbookReaderViewModel) {
    SettingsGroup("Margins") {
        SliderRow("Left", "${prefs.marginLeftDp.roundToInt()}", prefs.marginLeftDp, 0f..80f, viewModel::setMarginLeft)
        SliderRow("Right", "${prefs.marginRightDp.roundToInt()}", prefs.marginRightDp, 0f..80f, viewModel::setMarginRight)
        SliderRow("Top", "${prefs.marginTopDp.roundToInt()}", prefs.marginTopDp, 0f..120f, viewModel::setMarginTop)
        SliderRow("Bottom", "${prefs.marginBottomDp.roundToInt()}", prefs.marginBottomDp, 0f..120f, viewModel::setMarginBottom)
    }

    SettingsGroup("Turning pages") {
        val modes = listOf(
            ReadingMode.PAGED_LTR to "Left → right",
            ReadingMode.PAGED_RTL to "Right → left",
            ReadingMode.PAGED_VERTICAL to "Vertical",
            ReadingMode.CONTINUOUS to "Scroll"
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            modes.forEach { (mode, label) ->
                FilterChip(
                    selected = prefs.readingMode == mode,
                    onClick = { viewModel.setReadingMode(mode) },
                    label = { Text(label) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Tap zones",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TapLayout.L_SHAPED to "L-shape",
                        TapLayout.EDGES to "Edges",
                        TapLayout.DISABLED to "Off"
                    ).forEach { (layout, label) ->
                        FilterChip(
                            selected = prefs.tapLayout == layout,
                            onClick = { viewModel.setTapLayout(layout) },
                            label = { Text(label) }
                        )
                    }
                }
                ToggleRow("Invert", prefs.invertTapZones, viewModel::setInvertTapZones)
            }
            Spacer(Modifier.width(12.dp))
            TapZonePreview(prefs)
        }
    }

    SettingsGroup("Display") {
        ToggleRow("Status bar", prefs.showStatusBar, viewModel::setShowStatusBar, supporting = "Battery, clock and chapter")
        ToggleRow("Page number", prefs.showPageNumber, viewModel::setShowPageNumber)
        ToggleRow("Keep screen on", prefs.keepScreenOn, viewModel::setKeepScreenOn)
        ToggleRow("Hyphenation", prefs.hyphenationEnabled, viewModel::setHyphenation, supporting = "Scroll mode only")
    }
}

/** Small diagram of the current tap layout (green = next, red = previous, centre = menu). */
@Composable
private fun TapZonePreview(prefs: ReaderPreferences) {
    val next = Color(0x6643A047)
    val prev = Color(0x66E53935)
    Column(
        Modifier
            .width(64.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    ) {
        for (row in 0..2) {
            Row(Modifier.weight(1f)) {
                for (col in 0..2) {
                    val action = resolveTap(
                        androidx.compose.ui.geometry.Offset((col + 0.5f) / 3f, (row + 0.5f) / 3f), 1f, 1f, prefs
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(
                                when (action) {
                                    TapAction.NEXT -> next
                                    TapAction.PREV -> prev
                                    TapAction.MENU -> Color.Transparent
                                }
                            )
                    )
                }
            }
        }
    }
}

// ---- Colour --------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColourTab(prefs: ReaderPreferences, viewModel: EbookReaderViewModel) {
    SettingsGroup("Light") {
        val auto = prefs.screenBrightness < 0f
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
            Icon(
                Icons.Filled.BrightnessLow, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            CompactSlider(
                value = if (auto) 0.5f else prefs.screenBrightness,
                range = 0.05f..1f,
                onChange = viewModel::setBrightness,
                dimmed = auto,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            FilterChip(selected = auto, onClick = { viewModel.setBrightness(if (auto) 0.5f else -1f) }, label = { Text("Auto") })
        }
        ToggleRow("Warm light", prefs.blueLightFilterEnabled, { viewModel.setWarmthFilter(it, prefs.blueLightFilterIntensity) })
        if (prefs.blueLightFilterEnabled) {
            SliderRow(
                "Warmth",
                "${(prefs.blueLightFilterIntensity * 100).roundToInt()}%",
                prefs.blueLightFilterIntensity,
                0.05f..0.75f,
                { viewModel.setWarmthFilter(true, it) }
            )
        }
    }

    SettingsGroup("Theme") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp)
        ) {
            listOf(
                ReaderThemePreset.DAY to "Day",
                ReaderThemePreset.SEPIA to "Sepia",
                ReaderThemePreset.LINEN_NIGHT to "Linen",
                ReaderThemePreset.NIGHT to "Night",
                ReaderThemePreset.AMOLED_BLACK to "Black"
            ).forEach { (preset, label) ->
                ColorSwatch(Color(preset.backgroundArgb), Color(preset.textArgb), label, prefs.themePreset == preset) {
                    viewModel.selectTheme(preset)
                }
            }
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .width(1.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            CUSTOM_COLOR_PAIRS.forEach { pair ->
                ColorSwatch(
                    Color(pair.background), Color(pair.text), null,
                    prefs.themePreset == ReaderThemePreset.CUSTOM &&
                        prefs.customBackgroundArgb == pair.background && prefs.customTextArgb == pair.text
                ) { viewModel.setCustomColors(pair.background, pair.text) }
            }
        }
    }

    SettingsGroup("Texture") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            ReaderTexture.entries.forEach { texture ->
                TextureTile(
                    texture = texture,
                    background = Color(prefs.customBackgroundArgb),
                    selected = prefs.backgroundTexture == texture,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setBackgroundTexture(texture) }
            }
        }
    }
}

@Composable
private fun TextureTile(
    texture: ReaderTexture,
    background: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .readerTexture(texture)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(12.dp)
                )
                .clickable(onClick = onClick)
        )
        Text(
            texture.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ---- Building blocks -----------------------------------------------------------------------

/** A titled card that groups related rows. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                content = content
            )
        }
    }
}

/** One-line switch row (the whole row is tappable). */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onChange(!checked) }
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.scale(0.8f))
    }
}

/** Label · slim slider · value, all on one line. */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.width(80.dp))
        CompactSlider(value, range, onChange, modifier = Modifier.weight(1f))
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(44.dp)
        )
    }
}

/** A slider with a thin track and a slim handle, instead of the chunky default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = if (dimmed) {
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.outline,
            activeTrackColor = MaterialTheme.colorScheme.outline,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    } else {
        SliderDefaults.colors()
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        onValueChangeFinished = onChangeFinished,
        enabled = enabled,
        valueRange = range,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                thumbSize = DpSize(4.dp, 22.dp)
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                modifier = Modifier.height(6.dp),
                drawStopIndicator = null,
                thumbTrackGapSize = 3.dp
            )
        },
        modifier = modifier.height(36.dp)
    )
}

/** Label · [−] value [+]. */
@Composable
private fun StepperRow(label: String, valueText: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, "Smaller", Modifier.size(18.dp))
        }
        Text(
            valueText,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp)
        )
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, "Larger", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ColorSwatch(background: Color, text: Color, label: String?, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(background)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                )
                .clickable(onClick = onClick)
        ) { Text("Aa", color = text, fontWeight = FontWeight.SemiBold) }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
