package com.example.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.example.domain.reader.BlockAlign
import com.example.domain.reader.ChapterImage
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.LazyListScope
import com.example.domain.reader.EmbeddedFontFace
import com.example.domain.reader.ParagraphStyleInfo
import com.example.domain.reader.ReaderChapterDocument
import com.example.domain.reader.ReaderPreferences
import kotlin.math.roundToInt
import com.example.domain.reader.ReadingMode
import com.example.domain.reader.SentenceLocator
import com.example.domain.reader.TapLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------------------------

/** One paragraph of the chapter, with the character ranges of its sentences (TTS highlight / tap). */
@Immutable
data class ReaderParagraph(
    val text: String,
    val sentences: List<SentenceLocator>,
    val sentenceRanges: List<IntRange>,
    val isHeading: Boolean,
    /** The book's own styling for this paragraph (italics, alignment, fonts…), when known. */
    val style: ParagraphStyleInfo? = null
) {
    /** Chapter-level char offset for a position inside this paragraph's text. */
    fun chapterOffsetAt(localIndex: Int): Int {
        val i = sentenceRanges.indexOfLast { it.first <= localIndex }.coerceAtLeast(0)
        return sentences.getOrNull(i)?.startCharOffset ?: 0
    }
}

fun buildParagraphs(doc: ReaderChapterDocument): List<ReaderParagraph> =
    doc.sentences.groupBy { it.paragraphIndex }.toSortedMap().map { (paragraphIndex, sentences) ->
        // Rebuild the paragraph exactly as the tokenizer saw it (sentence offsets are chapter-wide),
        // so the book's style runs line up character for character.
        val base = sentences.first().startCharOffset
        val sb = StringBuilder()
        val ranges = ArrayList<IntRange>(sentences.size)
        sentences.forEach { s ->
            val start = (s.startCharOffset - base).coerceAtLeast(sb.length)
            while (sb.length < start) sb.append(' ')
            sb.append(s.text)
            ranges.add(start until sb.length)
        }
        val style = doc.paragraphStyles.getOrNull(paragraphIndex)
        ReaderParagraph(
            text = sb.toString(),
            sentences = sentences,
            sentenceRanges = ranges,
            isHeading = (style?.headingLevel ?: 0) > 0 ||
                Regex("^h[1-6]\\b").containsMatchIn(sentences.first().domCssSelector.orEmpty()),
            style = style
        )
    }

/** A run of text on a page: part of paragraph [paragraphIndex] (or the chapter title when -1). */
@Immutable
data class PageSlice(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    /** Pixel band of these lines inside the paragraph's full layout. */
    val top: Float = 0f,
    val bottom: Float = 0f,
    /** When >= 0 this slice is picture [imageIndex] of the chapter (paragraphIndex is then -2). */
    val imageIndex: Int = -1
)

/** Paragraph index used by picture slices. */
const val IMAGE_SLICE = -2

@Immutable
data class TextPage(val slices: List<PageSlice>)

/**
 * Pages laid out for one paragraph list. Deliberately NOT a data class: two picture-only chapters
 * can produce structurally equal layouts, and a state write that "equals" the old value is
 * dropped by Compose, which left the reader waiting on a spinner forever.
 */
class Pagination(val paragraphs: List<ReaderParagraph>, val pages: List<TextPage>)

/** What tapping a screen region means. */
enum class TapAction { PREV, NEXT, MENU }

fun resolveTap(pos: Offset, width: Float, height: Float, prefs: ReaderPreferences): TapAction {
    val x = pos.x / width.coerceAtLeast(1f)
    val y = pos.y / height.coerceAtLeast(1f)
    var action = when (prefs.tapLayout) {
        TapLayout.DISABLED -> TapAction.MENU
        TapLayout.EDGES -> when {
            x < 0.3f -> TapAction.PREV
            x > 0.7f -> TapAction.NEXT
            else -> TapAction.MENU
        }
        TapLayout.L_SHAPED -> when {
            x in 0.33f..0.66f && y in 0.33f..0.66f -> TapAction.MENU
            (x < 0.33f && y < 0.66f) || (y < 0.33f && x < 0.66f) -> TapAction.PREV
            else -> TapAction.NEXT
        }
    }
    // Right-to-left reading flips the horizontal meaning, as in Tachiyomi
    val flip = prefs.invertTapZones xor (prefs.readingMode == ReadingMode.PAGED_RTL)
    if (flip && action != TapAction.MENU) action = if (action == TapAction.PREV) TapAction.NEXT else TapAction.PREV
    return action
}

// ---------------------------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------------------------

@Immutable
data class ReaderTextStyles(val body: TextStyle, val heading: TextStyle, val title: TextStyle, val paragraphSpacing: Float)

fun readerTextStyles(prefs: ReaderPreferences, fontFamily: FontFamily, color: Color, paged: Boolean): ReaderTextStyles {
    val size = prefs.fontSizeSp
    val body = TextStyle(
        color = color,
        fontFamily = fontFamily,
        fontSize = size.sp,
        fontWeight = FontWeight(prefs.fontWeight.coerceIn(100, 900)),
        lineHeight = (size * prefs.lineSpacingMultiplier).sp,
        textAlign = if (prefs.fullJustification) TextAlign.Justify else TextAlign.Start,
        // Paged layout splits paragraphs at line boundaries, so it can't hyphenate across pages
        hyphens = if (prefs.hyphenationEnabled && !paged) Hyphens.Auto else Hyphens.None,
        lineBreak = LineBreak.Paragraph
    )
    // Titles are big, heavy glyphs: at full text colour they glare and their (often decorative)
    // detail gets lost, so they sit a step dimmer than the body text.
    val headingColor = color.copy(alpha = color.alpha * HEADING_ALPHA)
    val heading = body.copy(
        color = headingColor,
        fontSize = (size * 1.2f).sp,
        lineHeight = (size * 1.2f * 1.3f).sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    // Chapter title: larger, centred, with breathing room (like Moon+ Reader)
    val title = body.copy(
        color = headingColor,
        fontSize = (size * 1.5f).sp,
        lineHeight = (size * 1.5f * 1.3f).sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    return ReaderTextStyles(body, heading, title, prefs.paragraphSpacingDp)
}

/** A chapter ready to lay out: each paragraph's styled text and its paragraph style. */
@Immutable
class StyledChapter(
    val texts: List<AnnotatedString>,
    val styles: List<TextStyle>,
    /** Extra space above / below each paragraph (chapter-opening titles get a lot, like Moon+). */
    val gapTop: List<Dp>,
    val gapBottom: List<Dp>
)

/** Loads the book's embedded fonts, keyed by lower-cased family name. */
@Composable
fun rememberBookFonts(faces: List<EmbeddedFontFace>): Map<String, FontFamily> = remember(faces) {
    faces.groupBy { it.family }.mapNotNull { (family, list) ->
        runCatching {
            family to FontFamily(list.map {
                androidx.compose.ui.text.font.Font(
                    java.io.File(it.path),
                    FontWeight(it.weight.coerceIn(100, 900)),
                    if (it.italic) FontStyle.Italic else FontStyle.Normal
                )
            })
        }.getOrNull()
    }.toMap()
}

private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.5f
    2 -> 1.35f
    3 -> 1.2f
    else -> 1.1f
}

/**
 * Applies the book's own styling ("publisher styles" – what Moon+ Reader takes from the EPUB's
 * CSS) on top of the reader's base styles: italics/bold/underline/small caps inside paragraphs,
 * centred or right-aligned blocks, first-line indents, enlarged titles and embedded fonts.
 */
fun styleChapter(
    paragraphs: List<ReaderParagraph>,
    styles: ReaderTextStyles,
    prefs: ReaderPreferences,
    fonts: Map<String, FontFamily>
): StyledChapter {
    val publisher = prefs.publisherStyles
    val size = prefs.fontSizeSp
    val texts = ArrayList<AnnotatedString>(paragraphs.size)
    val paraStyles = ArrayList<TextStyle>(paragraphs.size)
    for (para in paragraphs) {
        val info = para.style
        val heading = para.isHeading
        val bookScale = info?.sizeScale ?: 1f
        val scale = when {
            heading -> if (publisher && bookScale > 1.05f) bookScale.coerceIn(1.05f, 2.2f) else headingScale(info?.headingLevel ?: 2)
            publisher && bookScale >= 1.15f -> bookScale.coerceAtMost(2f)
            else -> 1f
        }
        val bigText = heading || scale > 1.1f
        val align = when (if (publisher) info?.align else null) {
            BlockAlign.CENTER -> TextAlign.Center
            BlockAlign.END -> TextAlign.End
            else -> if (heading) TextAlign.Center else styles.body.textAlign
        }
        val indentEm = when {
            bigText || align == TextAlign.Center || align == TextAlign.End -> 0f
            publisher && (info?.indentEm ?: 0f) > 0f -> info!!.indentEm
            prefs.firstLineIndent -> 1.5f
            else -> 0f
        }
        val family = if (publisher) info?.fontFamily?.let { fonts[it] } else null
        val base = if (heading) styles.heading else styles.body
        paraStyles += base.copy(
            color = if (bigText) styles.heading.color else base.color,
            fontSize = (size * scale).sp,
            lineHeight = if (bigText) (size * scale * 1.3f).sp else base.lineHeight,
            textAlign = align,
            textIndent = if (indentEm > 0f) TextIndent(firstLine = (size * indentEm).sp) else null,
            fontFamily = family ?: base.fontFamily,
            // The book's own fonts never get a faked bold (italic may still be slanted). Faux bold
            // thickens every stroke and fills in decorative detail (e.g. distressed title fonts).
            fontSynthesis = if (family != null) FontSynthesis.Style else base.fontSynthesis
        )
        val runs = info?.runs.orEmpty()
        texts += if (runs.isEmpty()) AnnotatedString(para.text) else buildAnnotatedString {
            append(para.text)
            for (r in runs) {
                val start = r.start.coerceIn(0, para.text.length)
                val end = r.end.coerceIn(start, para.text.length)
                if (end <= start) continue
                addStyle(
                    SpanStyle(
                        fontStyle = if (r.italic) FontStyle.Italic else null,
                        fontWeight = if (r.bold && !heading) FontWeight.Bold else null,
                        textDecoration = when {
                            r.underline && r.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            r.underline -> TextDecoration.Underline
                            r.strike -> TextDecoration.LineThrough
                            else -> null
                        },
                        fontFeatureSettings = if (r.smallCaps) "smcp, c2sc" else null,
                        baselineShift = when (r.script) {
                            1 -> BaselineShift.Superscript
                            -1 -> BaselineShift.Subscript
                            else -> null
                        },
                        fontSize = if (r.script != 0) 0.7.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                        fontFamily = if (publisher) r.fontFamily?.let { fonts[it] } else null,
                        fontSynthesis = if (publisher && r.fontFamily != null && fonts[r.fontFamily] != null) FontSynthesis.Style else null
                    ),
                    start,
                    end
                )
            }
        }
    }
    // Chapter opening: the run of headings / big centred lines at the very start of the chapter
    val opening = paragraphs.indices.takeWhile { i ->
        paragraphs[i].isHeading || (paraStyles[i].textAlign == TextAlign.Center && paragraphs[i].text.length < 80)
    }.takeIf { it.size <= 4 }.orEmpty()
    val gapTop = paragraphs.indices.map { i ->
        when {
            i == 0 && opening.isNotEmpty() -> TITLE_TOP_GAP
            i in opening -> 0.dp
            paragraphs[i].isHeading -> 24.dp
            else -> 0.dp
        }
    }
    val gapBottom = paragraphs.indices.map { i ->
        when {
            opening.isNotEmpty() && i == opening.last() -> TITLE_BOTTOM_GAP
            i in opening -> 0.dp
            paragraphs[i].isHeading -> 8.dp
            else -> 0.dp
        }
    }
    return StyledChapter(texts, paraStyles, gapTop, gapBottom)
}

/** Chapter titles and headings are drawn at this fraction of the text colour's opacity. */
private const val HEADING_ALPHA = 0.9f

/** Space above and below a chapter's opening title (Moon+ drops the title well down the page). */
val TITLE_TOP_GAP = 96.dp
val TITLE_BOTTOM_GAP = 48.dp

/** Paragraphs longer than this are sliced as substrings instead of clipped full layouts. */
private const val CLIP_LAYOUT_MAX_CHARS = 4000

/** Splits a chapter into screen-sized pages, breaking paragraphs only between lines. */
fun paginate(
    measurer: TextMeasurer,
    title: String?,
    chapter: StyledChapter,
    styles: ReaderTextStyles,
    widthPx: Int,
    heightPx: Float,
    spacingPx: Float,
    gapTopPx: (Int) -> Float,
    gapBottomPx: (Int) -> Float,
    images: List<ChapterImage> = emptyList(),
    imageHeightPx: (Int) -> Int = { 0 }
): List<TextPage> {
    val pages = ArrayList<TextPage>()
    var current = ArrayList<PageSlice>()
    var used = 0f
    fun newPage() {
        if (current.isNotEmpty()) pages.add(TextPage(current))
        current = ArrayList()
        used = 0f
    }
    // Pictures are atomic: they move to the next page whole if they don't fit
    fun placeImage(imageIndex: Int) {
        val h = imageHeightPx(imageIndex).toFloat()
        if (h <= 0f) return
        if (current.isNotEmpty() && used + spacingPx + h > heightPx) newPage()
        val gap = if (current.isEmpty()) 0f else spacingPx
        current.add(PageSlice(IMAGE_SLICE, 0, 0, 0f, h, imageIndex = imageIndex))
        used += gap + h
    }
    val items: List<Triple<Int, AnnotatedString, TextStyle>> =
        (if (title != null) listOf(Triple(-1, AnnotatedString(title), styles.title)) else emptyList()) +
            chapter.texts.mapIndexed { i, t -> Triple(i, t, chapter.styles[i]) }

    var nextImage = 0
    for ((idx, text, style) in items) {
        // Pictures that come before this paragraph in the book
        if (idx >= 0) {
            while (nextImage < images.size && images[nextImage].afterParagraph <= idx) placeImage(nextImage++)
        }
        if (text.isBlank()) continue
        val layout = measurer.measure(text, style = style, constraints = Constraints(maxWidth = widthPx))
        // Titles get extra air above and below them
        val gapTop = gapTopPx(idx)
        val gapBottom = gapBottomPx(idx)
        var line = 0
        while (line < layout.lineCount) {
            val gap = if (current.isEmpty()) 0f else spacingPx
            val extraTop = if (line == 0) gapTop else 0f
            val avail = heightPx - used - gap - extraTop - gapBottom
            val top = layout.getLineTop(line)
            var last = line - 1
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= avail) last++
            if (last < line) {
                if (current.isEmpty()) last = line else {
                    newPage()
                    continue
                }
            }
            val bottom = layout.getLineBottom(last)
            current.add(PageSlice(idx, layout.getLineStart(line), layout.getLineEnd(last), top, bottom))
            used += gap + extraTop + (bottom - top) + (if (last == layout.lineCount - 1) gapBottom else 0f)
            line = last + 1
            if (line < layout.lineCount) newPage()
        }
    }
    while (nextImage < images.size) placeImage(nextImage++)
    newPage()
    // A chapter whose pictures couldn't be read still needs one (blank) page
    if (pages.isEmpty()) pages.add(TextPage(emptyList()))
    return pages
}

/** On-screen size of a picture: its natural size (1 image px ≈ 1 dp), shrunk to fit the page. */
fun imageDisplaySize(image: ChapterImage, density: Float, maxWidthPx: Int, maxHeightPx: Int): IntSize {
    if (image.width <= 0 || image.height <= 0) return IntSize.Zero
    var w = minOf(maxWidthPx.toFloat(), image.width * density)
    var h = w * image.height / image.width
    if (h > maxHeightPx) {
        h = maxHeightPx.toFloat()
        w = h * image.width / image.height
    }
    return IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
}

/** A picture from the book, centred at its laid-out size. */
@Composable
private fun BookImage(image: ChapterImage, size: IntSize, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = java.io.File(image.path),
            contentDescription = image.alt,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = with(density) { Modifier.width(size.width.toDp()).height(size.height.toDp()) }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Paged reader (horizontal L→R / R→L, or vertical)
// ---------------------------------------------------------------------------------------------

/** Imperative handle the chrome (slider) uses to move between pages. */
class ReaderPageController {
    var pageCount by mutableStateOf(0)
    var currentPage by mutableStateOf(0)
    internal var jump: (suspend (Int) -> Unit)? = null
    internal var snap: (suspend (Int) -> Unit)? = null
    suspend fun goTo(page: Int) {
        jump?.invoke(page)
    }

    /** Jumps without animation (live scrubbing with the page slider). */
    suspend fun snapTo(page: Int) {
        (snap ?: jump)?.invoke(page)
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PagedReader(
    doc: ReaderChapterDocument,
    paragraphs: List<ReaderParagraph>,
    prefs: ReaderPreferences,
    fontFamily: FontFamily,
    textColor: Color,
    initialCharOffset: Int,
    previousChapterTitle: String?,
    nextChapterTitle: String?,
    activeSentence: SentenceLocator?,
    ttsActive: Boolean,
    controller: ReaderPageController,
    onTapAction: (TapAction) -> Unit,
    onSentenceTap: (SentenceLocator) -> Boolean,
    onGoToNextChapter: () -> Unit,
    onGoToPreviousChapterEnd: () -> Unit,
    onPositionChanged: (Int) -> Unit
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val nav = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues()
    val styles = remember(prefs, fontFamily, textColor) { readerTextStyles(prefs, fontFamily, textColor, paged = true) }
    val bookFonts = rememberBookFonts(doc.embeddedFonts)
    val chapter = remember(paragraphs, styles, bookFonts) { styleChapter(paragraphs, styles, prefs, bookFonts) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topPad = status.calculateTopPadding() + prefs.marginTopDp.dp
        // Room at the bottom for the status bar / page number
        val bottomPad = nav.calculateBottomPadding() + prefs.marginBottomDp.dp + 22.dp
        val leftPx = with(density) { prefs.marginLeftDp.dp.roundToPx() }
        val rightPx = with(density) { prefs.marginRightDp.dp.roundToPx() }
        val widthPx = (constraints.maxWidth - leftPx - rightPx).coerceAtLeast(1)
        val heightPx = with(density) { (maxHeight - topPad - bottomPad).toPx() } * 0.98f
        val spacingPx = with(density) { prefs.paragraphSpacingDp.dp.toPx() }
        val titleTopPx = with(density) { TITLE_TOP_GAP.toPx() }
        val titleBottomPx = with(density) { TITLE_BOTTOM_GAP.toPx() }
        val gapTopPx = { i: Int -> if (i < 0) titleTopPx else with(density) { chapter.gapTop.getOrElse(i) { 0.dp }.toPx() } }
        val gapBottomPx = { i: Int -> if (i < 0) titleBottomPx else with(density) { chapter.gapBottom.getOrElse(i) { 0.dp }.toPx() } }

        // Picture-only chapters (cover, gallery) show just their pictures, like the book does
        val titleOnFirstPage = paragraphs.firstOrNull()?.let {
            !(it.isHeading || it.text.equals(doc.chapterTitle, ignoreCase = true))
        } ?: doc.images.isEmpty()
        val imageSizes = remember(doc.images, widthPx, heightPx) {
            doc.images.map { imageDisplaySize(it, density.density, widthPx, heightPx.toInt()) }
        }

        // Rubber-band pull past the first / last page (like pull-to-refresh, but to change chapter).
        // Positive = content pulled toward the next chapter. Survives the chapter switch so the new
        // chapter can slide in from the other side.
        val edgePull = remember { Animatable(0f) }
        var pendingChapterDir by remember { mutableIntStateOf(0) }
        val vertical = prefs.readingMode == ReadingMode.PAGED_VERTICAL
        val rtl = prefs.readingMode == ReadingMode.PAGED_RTL
        val spanPx = if (vertical) constraints.maxHeight.toFloat() else constraints.maxWidth.toFloat()
        val maxPullPx = spanPx * 0.42f
        val commitPx = with(density) { EDGE_PULL_COMMIT.toPx() }
        val view = LocalView.current

        // Pages are tagged with the paragraph list they were laid out from, so a page set from the
        // previous chapter is never rendered against the new chapter's paragraphs.
        val pagination by produceState<Pagination?>(
            null, doc, paragraphs, chapter, widthPx, heightPx, spacingPx
        ) {
            value = Pagination(paragraphs, withContext(Dispatchers.Default) {
                paginate(
                    measurer,
                    if (titleOnFirstPage) doc.chapterTitle else null,
                    chapter,
                    styles,
                    widthPx,
                    heightPx,
                    spacingPx,
                    gapTopPx,
                    gapBottomPx,
                    doc.images,
                    { i -> imageSizes.getOrNull(i)?.height ?: 0 }
                )
            })
        }

        val pageList = pagination?.takeIf { it.paragraphs === paragraphs }?.pages
        if (pageList == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = textColor.copy(alpha = 0.5f))
            }
            return@BoxWithConstraints
        }

        key(pageList) {

        fun pageStartOffset(page: TextPage): Int {
            page.slices.firstOrNull { it.paragraphIndex >= 0 }?.let { s ->
                return paragraphs.getOrNull(s.paragraphIndex)?.chapterOffsetAt(s.start) ?: 0
            }
            // A page of pictures only: place it just before the paragraph that follows it
            val image = page.slices.firstOrNull { it.imageIndex >= 0 }?.let { doc.images.getOrNull(it.imageIndex) } ?: return 0
            val next = paragraphs.getOrNull(image.afterParagraph)?.sentences?.firstOrNull()?.startCharOffset
            return when {
                next != null -> (next - 1).coerceAtLeast(0)
                paragraphs.isNotEmpty() -> paragraphs.last().sentences.lastOrNull()?.endCharOffset ?: 0
                else -> 0
            }
        }

        // No transition pages: swiping / tapping past either end goes straight to the next chapter
        val leadCount = 0
        val totalCount = pageList.size
        val startPage = remember(pageList) {
            val idx = pageList.indexOfLast { pageStartOffset(it) <= initialCharOffset }.coerceAtLeast(0)
            if (initialCharOffset <= 0) 0 else idx
        }
        val pagerState = rememberPagerState(initialPage = leadCount + startPage) { totalCount }
        val scope = rememberCoroutineScope()

        // A freshly opened chapter slides in from the side the reader pulled toward
        LaunchedEffect(Unit) {
            val dir = pendingChapterDir
            pendingChapterDir = 0
            if (dir != 0) {
                edgePull.snapTo(-dir * spanPx * 0.35f)
                edgePull.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 420f))
            } else if (edgePull.value != 0f) {
                edgePull.snapTo(0f)
            }
        }

        /** Slides the current chapter away and opens the neighbouring one ([dir] = +1 next, -1 previous). */
        fun commitChapterChange(dir: Int) {
            if (pendingChapterDir != 0) return
            pendingChapterDir = dir
            scope.launch {
                edgePull.animateTo(dir * spanPx, tween(durationMillis = 170, easing = FastOutLinearInEasing))
                if (dir > 0) onGoToNextChapter() else onGoToPreviousChapterEnd()
            }
        }

        val hasNext = nextChapterTitle != null
        val hasPrev = previousChapterTitle != null

        // Expose paging to the chrome
        LaunchedEffect(pagerState, pageList) {
            controller.pageCount = pageList.size
            controller.jump = { p -> pagerState.animateScrollToPage(leadCount + p.coerceIn(0, pageList.size - 1)) }
            controller.snap = { p -> pagerState.scrollToPage(leadCount + p.coerceIn(0, pageList.size - 1)) }
        }
        LaunchedEffect(pagerState, pageList) {
            snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { settled ->
                val contentPage = (settled - leadCount).coerceIn(0, pageList.size - 1)
                controller.currentPage = contentPage
                onPositionChanged(pageStartOffset(pageList[contentPage]))
            }
        }

        // Read-aloud: turn the page when the spoken sentence moves off it
        LaunchedEffect(activeSentence?.sentenceId, pageList) {
            val s = activeSentence ?: return@LaunchedEffect
            if (s.chapterIndex != doc.chapterIndex || s.bookId != doc.bookId) return@LaunchedEffect
            val pIdx = paragraphs.indexOfFirst { p -> p.sentences.any { it.sentenceId == s.sentenceId } }
            if (pIdx < 0) return@LaunchedEffect
            val para = paragraphs[pIdx]
            val range = para.sentenceRanges[para.sentences.indexOfFirst { it.sentenceId == s.sentenceId }]
            val target = pageList.indexOfFirst { page ->
                page.slices.any { it.paragraphIndex == pIdx && range.first < it.end && range.last + 1 > it.start }
            }
            if (target >= 0 && target + leadCount != pagerState.currentPage) pagerState.animateScrollToPage(target + leadCount)
        }

        // Tap → sentence lookup (read-aloud) or tap zone
        val sliceCoords = remember(pageList) { HashMap<Pair<Int, Int>, LayoutCoordinates>() }
        val sliceLayouts = remember(pageList) { HashMap<Pair<Int, Int>, TextLayoutResult>() }
        var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        fun sentenceAt(pos: Offset): SentenceLocator? {
            val root = rootCoords ?: return null
            val page = pagerState.currentPage - leadCount
            val slices = pageList.getOrNull(page)?.slices ?: return null
            slices.forEachIndexed { i, slice ->
                if (slice.paragraphIndex < 0) return@forEachIndexed
                val coords = sliceCoords[page to i]?.takeIf { it.isAttached } ?: return@forEachIndexed
                val local = coords.localPositionOf(root, pos)
                if (local.x < 0 || local.y < 0 || local.x > coords.size.width || local.y > coords.size.height) return@forEachIndexed
                val layout = sliceLayouts[page to i] ?: return null
                val para = paragraphs.getOrNull(slice.paragraphIndex) ?: return null
                val charIdx = if (para.text.length <= CLIP_LAYOUT_MAX_CHARS) {
                    layout.getOffsetForPosition(local.copy(y = local.y + slice.top))
                } else {
                    slice.start + layout.getOffsetForPosition(local)
                }
                val sIdx = para.sentenceRanges.indexOfLast { it.first <= charIdx }.coerceAtLeast(0)
                return para.sentences.getOrNull(sIdx)
            }
            return null
        }

        val handleTap by rememberUpdatedState { pos: Offset, w: Float, h: Float ->
            val sentence = if (ttsActive) sentenceAt(pos) else null
            if (sentence == null || !onSentenceTap(sentence)) {
                when (val action = resolveTap(pos, w, h, prefs)) {
                    TapAction.MENU -> onTapAction(action)
                    TapAction.NEXT -> scope.launch {
                        if (pagerState.currentPage + 1 < totalCount) pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        else if (hasNext) commitChapterChange(1)
                    }
                    TapAction.PREV -> scope.launch {
                        if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        else if (hasPrev) commitChapterChange(-1)
                    }
                }
            }
            Unit
        }

        val pageContent: @Composable (Int) -> Unit = { index ->
            val contentPage = (index - leadCount).coerceIn(0, pageList.size - 1)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = prefs.marginLeftDp.dp, end = prefs.marginRightDp.dp, top = topPad, bottom = bottomPad)
            ) {
                PageBody(
                        page = pageList[contentPage],
                        pageIndex = contentPage,
                        doc = doc,
                        paragraphs = paragraphs,
                        chapter = chapter,
                        styles = styles,
                        widthPx = widthPx,
                        imageSizes = imageSizes,
                        activeSentence = activeSentence,
                        onSliceLayout = { key, layout -> sliceLayouts[key] = layout },
                        onSliceCoords = { key, coords -> sliceCoords[key] = coords }
                    )
            }
        }

        // Resistance curve: follows the finger at first, then stiffens toward maxPullPx
        fun rubberBand(raw: Float): Float = maxPullPx * (1f - 1f / (raw / maxPullPx + 1f))

        val gestureModifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoords = it }
            // Pulling past the first / last page stretches like a rubber band; let go past the
            // threshold and it snaps to the neighbouring chapter.
            .pointerInput(pageList, vertical, rtl, hasNext, hasPrev) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (pendingChapterDir != 0) return@awaitEachGesture
                    var dir = 0 // +1 pulling toward next chapter, -1 toward previous
                    var raw = 0f
                    var armed = false
                    var crossTotal = 0f
                    var primaryTotal = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - change.previousPosition
                        val primary = if (vertical) delta.y else delta.x
                        crossTotal += kotlin.math.abs(if (vertical) delta.x else delta.y)
                        primaryTotal += kotlin.math.abs(primary)
                        // Dragging left (or up) moves forward; right-to-left mode flips it
                        val forward = if (rtl) primary else -primary
                        if (dir == 0) {
                            val settled = kotlin.math.abs(pagerState.currentPageOffsetFraction) < 0.01f
                            val atEnd = pagerState.currentPage == pageList.size - 1
                            val atStart = pagerState.currentPage == 0
                            if (settled && primaryTotal > crossTotal) {
                                if (atEnd && hasNext && forward > 0f) dir = 1
                                else if (atStart && hasPrev && forward < 0f) dir = -1
                            }
                        }
                        if (dir != 0) {
                            raw = (raw + forward * dir).coerceAtLeast(0f)
                            val shown = rubberBand(raw)
                            scope.launch { edgePull.snapTo(dir * shown) }
                            val nowArmed = shown >= commitPx
                            if (nowArmed && !armed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            armed = nowArmed
                            change.consume()
                            if (raw == 0f) dir = 0 // pulled back to the page: hand the gesture back
                        }
                    }
                    if (armed && dir != 0) {
                        commitChapterChange(dir)
                    } else if (edgePull.value != 0f || raw > 0f) {
                        scope.launch { edgePull.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 520f)) }
                    }
                }
            }
            .graphicsLayer {
                val offset = edgePull.value
                if (vertical) translationY = -offset else translationX = if (rtl) offset else -offset
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos -> handleTap(pos, size.width.toFloat(), size.height.toFloat()) })
            }
            .testTag("reader_pager")

        // The rubber band replaces the platform's stretch overscroll at the chapter edges
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            if (vertical) {
                VerticalPager(state = pagerState, modifier = gestureModifier, beyondViewportPageCount = 1) { pageContent(it) }
            } else {
                HorizontalPager(
                    state = pagerState,
                    reverseLayout = rtl,
                    modifier = gestureModifier,
                    beyondViewportPageCount = 1
                ) { pageContent(it) }
            }
        }

        EdgePullHint(
            pull = edgePull.value,
            commitPx = commitPx,
            maxPullPx = maxPullPx,
            vertical = vertical,
            rtl = rtl,
            nextTitle = nextChapterTitle,
            previousTitle = previousChapterTitle,
            color = textColor
        )
        }
    }
}

/** How far (after resistance) the page must be pulled before letting go changes chapter. */
private val EDGE_PULL_COMMIT = 72.dp

/** The "Next chapter" / "Previous chapter" hint revealed in the gap behind a rubber-band pull. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.EdgePullHint(
    pull: Float,
    commitPx: Float,
    maxPullPx: Float,
    vertical: Boolean,
    rtl: Boolean,
    nextTitle: String?,
    previousTitle: String?,
    color: Color
) {
    if (pull == 0f) return
    val density = LocalDensity.current
    val next = pull > 0f
    val revealed = kotlin.math.abs(pull)
    val armed = revealed >= commitPx
    // Fade in while pulling; fade out again as the page flies off after letting go
    val alpha = if (revealed <= maxPullPx) (revealed / commitPx).coerceIn(0f, 1f)
    else (1f - (revealed - maxPullPx) / (maxPullPx * 0.6f)).coerceIn(0f, 1f)
    val scale by androidx.compose.animation.core.animateFloatAsState(if (armed) 1f else 0.82f, label = "hintScale")
    val alignment = when {
        vertical -> if (next) Alignment.BottomCenter else Alignment.TopCenter
        rtl -> if (next) Alignment.CenterStart else Alignment.CenterEnd
        else -> if (next) Alignment.CenterEnd else Alignment.CenterStart
    }
    val size = with(density) { revealed.toDp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(alignment)
            .then(if (vertical) Modifier.fillMaxWidth().height(size) else Modifier.fillMaxHeight().width(size))
            .graphicsLayer { this.alpha = alpha }
    ) {
        // Only the arrow and a short word live in the (narrow) gap
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .requiredWidth(72.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            val icon = when {
                vertical -> if (next) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
                (next xor rtl) -> Icons.AutoMirrored.Filled.ArrowForward
                else -> Icons.AutoMirrored.Filled.ArrowBack
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = if (armed) 0.18f else 0.08f), CircleShape)
            ) {
                Icon(icon, contentDescription = null, tint = color.copy(alpha = if (armed) 0.95f else 0.6f), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (next) "Next" else "Previous",
                style = MaterialTheme.typography.labelMedium,
                color = color.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }

}

@Composable
private fun PageBody(
    page: TextPage,
    pageIndex: Int,
    doc: ReaderChapterDocument,
    paragraphs: List<ReaderParagraph>,
    chapter: StyledChapter,
    styles: ReaderTextStyles,
    widthPx: Int,
    imageSizes: List<IntSize>,
    activeSentence: SentenceLocator?,
    onSliceLayout: (Pair<Int, Int>, TextLayoutResult) -> Unit,
    onSliceCoords: (Pair<Int, Int>, LayoutCoordinates) -> Unit
) {
    // A page holding only pictures (a cover, a full-page illustration) sits centred on the page
    val picturesOnly = page.slices.isNotEmpty() && page.slices.all { it.imageIndex >= 0 }
    Column(
        verticalArrangement = if (picturesOnly) {
            Arrangement.spacedBy(styles.paragraphSpacing.dp, Alignment.CenterVertically)
        } else {
            Arrangement.spacedBy(styles.paragraphSpacing.dp)
        },
        modifier = if (picturesOnly) Modifier.fillMaxSize() else Modifier
    ) {
        page.slices.forEachIndexed { i, slice ->
            val key = pageIndex to i
            if (slice.imageIndex >= 0) {
                val image = doc.images.getOrNull(slice.imageIndex)
                val size = imageSizes.getOrNull(slice.imageIndex)
                if (image != null && size != null) BookImage(image, size)
                return@forEachIndexed
            }
            if (slice.paragraphIndex < 0) {
                val titleText = AnnotatedString(doc.chapterTitle)
                ClippedLines(
                    titleText, styles.title, slice, widthPx,
                    Modifier.padding(
                        top = if (slice.start == 0) TITLE_TOP_GAP else 0.dp,
                        bottom = if (slice.end >= titleText.length) TITLE_BOTTOM_GAP else 0.dp
                    )
                )
                return@forEachIndexed
            }
            val para = paragraphs.getOrNull(slice.paragraphIndex) ?: return@forEachIndexed
            val full = chapter.texts.getOrNull(slice.paragraphIndex) ?: return@forEachIndexed
            val gapModifier = Modifier.padding(
                top = if (slice.start == 0) chapter.gapTop.getOrElse(slice.paragraphIndex) { 0.dp } else 0.dp,
                bottom = if (slice.end >= full.length) chapter.gapBottom.getOrElse(slice.paragraphIndex) { 0.dp } else 0.dp
            )
            val style = chapter.styles[slice.paragraphIndex]
            val highlight = activeSentence?.takeIf { it.chapterIndex == doc.chapterIndex }?.let { s ->
                val sIdx = para.sentences.indexOfFirst { it.sentenceId == s.sentenceId }
                para.sentenceRanges.getOrNull(sIdx)
            }
            val text = remember(full, highlight) {
                if (highlight == null) full else buildAnnotatedString {
                    append(full)
                    val from = highlight.first.coerceIn(0, full.length)
                    val to = (highlight.last + 1).coerceIn(0, full.length)
                    if (to > from) addStyle(SpanStyle(background = Color(0x55FFC107)), from, to)
                }
            }
            val modifier = gapModifier.onGloballyPositioned { onSliceCoords(key, it) }
            if (full.length <= CLIP_LAYOUT_MAX_CHARS) {
                // Lay out the whole paragraph and show only this page's lines, so line breaks and
                // justification match the measured pages exactly (even the last line on a page).
                ClippedLines(text, style, slice, widthPx, modifier) { onSliceLayout(key, it) }
            } else {
                val end = slice.end.coerceAtMost(text.length)
                var sub = text.subSequence(slice.start.coerceAtMost(end), end)
                while (sub.isNotEmpty() && sub.text.last().isWhitespace()) sub = sub.subSequence(0, sub.length - 1)
                Text(
                    text = sub,
                    style = if (slice.start > 0) style.copy(textIndent = null) else style,
                    onTextLayout = { onSliceLayout(key, it) },
                    modifier = modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Shows lines [slice.top, slice.bottom) of [text]'s full layout at exactly [widthPx]. */
@Composable
private fun ClippedLines(
    text: AnnotatedString,
    style: TextStyle,
    slice: PageSlice,
    widthPx: Int,
    modifier: Modifier = Modifier,
    onLayout: (TextLayoutResult) -> Unit = {}
) {
    val top = slice.top.roundToInt()
    val height = (slice.bottom - slice.top).roundToInt().coerceAtLeast(1)
    // Only cut where the paragraph really continues on another page. Decorative fonts (chapter
    // titles especially) draw above / below their line box, and must not be clipped there.
    val cutTop = slice.start > 0
    val cutBottom = slice.end < text.length
    Text(
        text = text,
        style = style,
        onTextLayout = onLayout,
        modifier = modifier
            .drawWithContent {
                val overflow = size.height * 2 + 2000f
                clipRect(
                    left = -overflow,
                    top = if (cutTop) 0f else -overflow,
                    right = size.width + overflow,
                    bottom = if (cutBottom) size.height else size.height + overflow
                ) { this@drawWithContent.drawContent() }
            }
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints(minWidth = widthPx, maxWidth = widthPx))
                layout(widthPx, height) { placeable.place(0, -top) }
            }
    )
}

@Composable
private fun ChapterTransition(
    topLabel: String,
    topTitle: String,
    bottomLabel: String?,
    bottomTitle: String,
    color: Color,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Text(topLabel, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.6f))
        Text(topTitle, style = MaterialTheme.typography.titleLarge, color = color)
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = color.copy(alpha = 0.15f), modifier = Modifier.width(64.dp))
        Spacer(Modifier.height(28.dp))
        if (bottomLabel != null) {
            Text(bottomLabel, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.6f))
        }
        Text(bottomTitle, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Continuous (long-strip) reader
// ---------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ContinuousReader(
    doc: ReaderChapterDocument,
    paragraphs: List<ReaderParagraph>,
    isPdf: Boolean,
    pdfFileUri: String? = null,
    prefs: ReaderPreferences,
    fontFamily: FontFamily,
    textColor: Color,
    initialCharOffset: Int,
    nextChapterTitle: String?,
    activeSentence: SentenceLocator?,
    ttsActive: Boolean,
    controller: ReaderPageController,
    onTapAction: (TapAction) -> Unit,
    onSentenceTap: (SentenceLocator) -> Boolean,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onPositionChanged: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val styles = remember(prefs, fontFamily, textColor) { readerTextStyles(prefs, fontFamily, textColor, paged = false) }
    val bookFonts = rememberBookFonts(doc.embeddedFonts)
    val chapter = remember(paragraphs, styles, bookFonts) { styleChapter(paragraphs, styles, prefs, bookFonts) }
    val onNextChapterState by rememberUpdatedState(onNextChapter)
    val onPreviousChapterState by rememberUpdatedState(onPreviousChapter)
    val hasNextChapter by rememberUpdatedState(nextChapterTitle != null)
    val textLayouts = remember(doc) { HashMap<Int, TextLayoutResult>() }
    val paragraphCoords = remember(doc) { HashMap<Int, LayoutCoordinates>() }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val showTitle = paragraphs.firstOrNull()?.let { !(it.isHeading || it.text.equals(doc.chapterTitle, true)) }
        ?: doc.images.isEmpty()
    // List items are: title, then pictures interleaved with paragraphs, then the footer
    val imagesBefore = remember(doc.images, paragraphs) {
        IntArray(paragraphs.size + 1) { p -> doc.images.count { it.afterParagraph <= p } }
    }
    fun itemOf(paragraph: Int): Int = 1 + paragraph + imagesBefore[paragraph.coerceIn(0, paragraphs.size)]
    fun paragraphAt(item: Int): Int = paragraphs.indices.lastOrNull { itemOf(it) <= item } ?: 0

    LaunchedEffect(doc.bookId, doc.chapterIndex) {
        val target = paragraphs.indexOfFirst { (it.sentences.lastOrNull()?.endCharOffset ?: 0) > initialCharOffset }
        if (initialCharOffset > 0 && target > 0) listState.scrollToItem(itemOf(target)) else listState.scrollToItem(0)
    }
    // "Pages" for the slider = paragraphs in continuous mode
    LaunchedEffect(listState, paragraphs) {
        controller.pageCount = paragraphs.size.coerceAtLeast(1)
        controller.jump = { p -> listState.animateScrollToItem(itemOf(p)) }
        controller.snap = { p -> listState.scrollToItem(itemOf(p)) }
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { idx ->
            val pIdx = paragraphAt(idx)
            val para = paragraphs.getOrNull(pIdx)
            controller.currentPage = pIdx.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
            onPositionChanged(if (idx == 0) 0 else para?.sentences?.firstOrNull()?.startCharOffset ?: 0)
        }
    }
    LaunchedEffect(activeSentence?.sentenceId) {
        val s = activeSentence ?: return@LaunchedEffect
        if (s.chapterIndex != doc.chapterIndex || s.bookId != doc.bookId) return@LaunchedEffect
        val pIdx = paragraphs.indexOfFirst { p -> p.sentences.any { it.sentenceId == s.sentenceId } }
        if (pIdx < 0) return@LaunchedEffect
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemOf(pIdx) }
        if (info == null || info.offset + info.size > listState.layoutInfo.viewportEndOffset || info.offset < 0) {
            listState.animateScrollToItem(itemOf(pIdx))
        }
    }

    fun sentenceAt(pos: Offset): SentenceLocator? {
        val root = rootCoords ?: return null
        for ((pIdx, coords) in paragraphCoords) {
            if (!coords.isAttached) continue
            val local = coords.localPositionOf(root, pos)
            if (local.x < 0 || local.y < 0 || local.x > coords.size.width || local.y > coords.size.height) continue
            val layout = textLayouts[pIdx] ?: return null
            val para = paragraphs.getOrNull(pIdx) ?: return null
            val charIdx = layout.getOffsetForPosition(local)
            return para.sentences.getOrNull(para.sentenceRanges.indexOfLast { it.first <= charIdx }.coerceAtLeast(0))
        }
        return null
    }

    val handleTap by rememberUpdatedState { pos: Offset, w: Float, h: Float ->
        val sentence = if (ttsActive) sentenceAt(pos) else null
        if (sentence == null || !onSentenceTap(sentence)) {
            val step = listState.layoutInfo.viewportSize.height * 0.9f
            when (resolveTap(pos, w, h, prefs)) {
                TapAction.MENU -> onTapAction(TapAction.MENU)
                TapAction.NEXT -> scope.launch {
                    if (listState.canScrollForward) listState.animateScrollBy(step) else if (nextChapterTitle != null) onNextChapter()
                }
                TapAction.PREV -> scope.launch {
                    if (listState.canScrollBackward) listState.animateScrollBy(-step) else onPreviousChapter()
                }
            }
        }
        Unit
    }

    val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val nav = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = prefs.marginLeftDp.dp,
            end = prefs.marginRightDp.dp,
            top = status.calculateTopPadding() + prefs.marginTopDp.dp,
            bottom = nav.calculateBottomPadding() + prefs.marginBottomDp.dp + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(prefs.paragraphSpacingDp.dp),
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoords = it }
            // Pulling past the end / start of the chapter moves to the neighbouring chapter
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val atStart = !listState.canScrollBackward
                    val atEnd = !listState.canScrollForward
                    var dy = 0f
                    var dx = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dx += change.position.x - change.previousPosition.x
                        dy += change.position.y - change.previousPosition.y
                        if (!change.pressed) break
                    }
                    val threshold = 72.dp.toPx()
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                        if (atEnd && dy < -threshold && hasNextChapter) onNextChapterState()
                        if (atStart && dy > threshold) onPreviousChapterState()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos -> handleTap(pos, size.width.toFloat(), size.height.toFloat()) })
            }
            .testTag("reader_text_column")
    ) {
        item(key = "title") {
            if (showTitle) {
                Text(
                    doc.chapterTitle,
                    style = styles.title,
                    modifier = Modifier.fillMaxWidth().padding(top = TITLE_TOP_GAP, bottom = TITLE_BOTTOM_GAP)
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
        if (isPdf && doc.embeddedImageUris.isNotEmpty()) {
            itemsIndexed(doc.embeddedImageUris, key = { i, _ -> "img_$i" }) { _, uri ->
                if (uri.startsWith("pdfpage:") && pdfFileUri != null) {
                    PdfPageImage(pdfFileUri, uri.removePrefix("pdfpage:").toIntOrNull() ?: 0)
                } else {
                    AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            var nextImage = 0
            fun LazyListScope.imagesUpTo(paragraph: Int) {
                while (nextImage < doc.images.size && doc.images[nextImage].afterParagraph <= paragraph) {
                    val image = doc.images[nextImage]
                    item(key = "pic_$nextImage") {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val density = LocalDensity.current
                            val size = remember(image, constraints.maxWidth) {
                                imageDisplaySize(
                                    image, density.density, constraints.maxWidth,
                                    (constraints.maxWidth * 1.6f).toInt()
                                )
                            }
                            BookImage(image, size)
                        }
                    }
                    nextImage++
                }
            }
            paragraphs.forEachIndexed { pIdx, para ->
                imagesUpTo(pIdx)
                item(key = "p_$pIdx") {
                val highlight = activeSentence?.takeIf { it.chapterIndex == doc.chapterIndex }?.let { s ->
                    para.sentenceRanges.getOrNull(para.sentences.indexOfFirst { it.sentenceId == s.sentenceId })
                }
                val full = chapter.texts.getOrElse(pIdx) { AnnotatedString(para.text) }
                val text = remember(full, highlight) {
                    if (highlight == null) full else buildAnnotatedString {
                        append(full)
                        val to = (highlight.last + 1).coerceAtMost(full.length)
                        if (to > highlight.first) addStyle(SpanStyle(background = Color(0x55FFC107)), highlight.first, to)
                    }
                }
                Text(
                    text = text,
                    style = chapter.styles.getOrElse(pIdx) { styles.body },
                    onTextLayout = { textLayouts[pIdx] = it },
                    modifier = Modifier
                        .padding(
                            top = chapter.gapTop.getOrElse(pIdx) { 0.dp },
                            bottom = chapter.gapBottom.getOrElse(pIdx) { 0.dp }
                        )
                        .fillMaxWidth()
                        .onGloballyPositioned { paragraphCoords[pIdx] = it }
                )
                }
            }
            imagesUpTo(Int.MAX_VALUE)
        }
        // No "chapter finished" card: just breathing room before the next chapter loads
        item(key = "footer") { Spacer(Modifier.height(48.dp)) }
    }
}

/** Renders one PDF page on demand (pages are no longer pre-rendered when a PDF is opened). */
@Composable
private fun PdfPageImage(pdfUri: String, pageIndex: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val targetWidth = constraints.maxWidth.coerceIn(320, 1600)
        val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pdfUri, pageIndex, targetWidth) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(android.net.Uri.parse(pdfUri), "r")?.use { pfd ->
                        android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                            renderer.openPage(pageIndex).use { page ->
                                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                                val bmp = android.graphics.Bitmap.createBitmap(
                                    targetWidth,
                                    (page.height * scale).toInt().coerceAtLeast(1),
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp.asImageBitmap()
                            }
                        }
                    }
                }.getOrNull()
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
            )
        } else {
            Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
