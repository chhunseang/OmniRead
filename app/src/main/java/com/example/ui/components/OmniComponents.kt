package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

@Immutable
data class PillNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val testTag: String = ""
)

/**
 * Compact floating pill navigation (Google Photos style).
 * - Only the active tab shows its icon (inline, left of the label).
 * - Press and drag across the bar: the highlight glides with your finger and snaps onto the
 *   tab underneath (light "keyboard tap" haptic on each snap). The page only changes when you
 *   lift your finger. A plain tap switches on release.
 */
@Composable
fun PillNavigationBar(
    items: List<PillNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Horizontal bounds (px, relative to the row) of each tab, for the indicator and hit-testing
    val bounds = remember(items.size) { mutableStateListOf<IntRange>().apply { repeat(items.size) { add(0..0) } } }
    var hovered by remember { mutableIntStateOf(-1) } // tab under the finger while pressed
    var fingerX by remember { mutableFloatStateOf(0f) }
    val indicatorX = remember { Animatable(0f) }
    val indicatorW = remember { Animatable(0f) }
    val visualIndex = if (hovered >= 0) hovered else selectedIndex
    // Slightly bouncy spring for the highlight; the tabs themselves resize without bounce
    val snapSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 420f)
    val contentSpring = spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 1f, stiffness = 420f)

    // Label widths (stable whatever is selected) let us compute where every tab WILL end up,
    // so the highlight morphs straight from the old tab's size/position to the new tab's final
    // size/position instead of chasing tabs while they resize.
    val labelWidths = remember(items.size) { mutableStateListOf<Int>().apply { repeat(items.size) { add(0) } } }
    val itemPadPx = with(density) { 16.dp.roundToPx() }
    val iconExtraPx = with(density) { (22.dp + 10.dp).roundToPx() }
    fun tabWidth(index: Int, selected: Int) =
        itemPadPx * 2 + labelWidths[index] + if (index == selected) iconExtraPx else 0
    fun finalLeft(index: Int, selected: Int) = (0 until index).sumOf { tabWidth(it, selected) }

    val labelsMeasured = labelWidths.all { it > 0 }
    LaunchedEffect(visualIndex, selectedIndex, labelsMeasured) {
        if (!labelsMeasured) return@LaunchedEffect
        val x = finalLeft(visualIndex, selectedIndex).toFloat()
        val w = tabWidth(visualIndex, selectedIndex).toFloat()
        if (indicatorW.value == 0f) {
            indicatorX.snapTo(x)
            indicatorW.snapTo(w)
        } else {
            launch { indicatorX.animateTo(x, snapSpring) }
            launch { indicatorW.animateTo(w, snapSpring) }
        }
    }

    fun nearestIndex(x: Float): Int = bounds.indices.minByOrNull { i ->
        val b = bounds[i]
        when {
            x < b.first -> b.first - x
            x > b.last -> x - b.last
            else -> 0f
        }
    } ?: -1

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = PILL_BOTTOM_GAP)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp
        ) {
            Box(Modifier.padding(PILL_INNER_PADDING)) {
                Box(
                    Modifier
                        .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                        .width(with(density) { indicatorW.value.toDp() })
                        .height(PILL_ITEM_HEIGHT)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(PILL_ITEM_HEIGHT)
                        .pointerInput(items.size) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fingerX = down.position.x
                                hovered = nearestIndex(down.position.x)
                                if (hovered != currentSelected) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    fingerX = change.position.x
                                    val idx = nearestIndex(change.position.x)
                                    if (idx >= 0 && idx != hovered) {
                                        hovered = idx
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    }
                                    change.consume()
                                }
                                // Release: commit the tab under the finger
                                val chosen = hovered
                                hovered = -1
                                if (chosen >= 0 && chosen != currentSelected) currentOnSelect(chosen)
                            }
                        }
                ) {
                    items.forEachIndexed { index, item ->
                        val highlighted = index == visualIndex
                        val content by animateColorAsState(
                            if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                            label = "pillContent"
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxHeight()
                                .onGloballyPositioned { c ->
                                    val x = c.positionInParent().x.toInt()
                                    val range = x..(x + c.size.width)
                                    if (bounds[index] != range) bounds[index] = range
                                }
                                .animateContentSize(contentSpring)
                                .padding(horizontal = 16.dp)
                                .testTag(item.testTag)
                        ) {
                            if (index == selectedIndex) {
                                Icon(
                                    imageVector = item.selectedIcon,
                                    contentDescription = null,
                                    tint = content,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                item.label,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                ),
                                color = content,
                                maxLines = 1,
                                modifier = Modifier.onSizeChanged { if (labelWidths[index] != it.width) labelWidths[index] = it.width }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Proportions measured from Google Photos on a Pixel 6:
// 56dp capsule = 40dp highlight + 8dp inner padding, floating 16dp above the gesture bar.
private val PILL_ITEM_HEIGHT = 40.dp
private val PILL_INNER_PADDING = 8.dp
private val PILL_BOTTOM_GAP = 16.dp

/** Total height the floating bar occupies above the gesture bar (capsule + gap). */
val PillNavigationBarHeight = PILL_ITEM_HEIGHT + PILL_INNER_PADDING * 2 + PILL_BOTTOM_GAP

/**
 * Bottom space each tab screen should leave so its last item can scroll clear of the floating
 * navigation bar (provided by the app shell; includes the gesture-bar inset).
 */
val LocalNavBarClearance = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Opacity of the background at the very screen edge for every edge fade (app tabs and reader). */
const val EDGE_FADE_ALPHA = 0.8f

/**
 * Telegram-style fade: content scrolling under the floating bar dissolves into the
 * background towards the bottom edge of the screen. Purely visual – touches pass through.
 */
@Composable
fun BottomFade(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to bg.copy(alpha = 0f),
                    1f to bg.copy(alpha = EDGE_FADE_ALPHA)
                )
            )
    )
}

/**
 * Browser-style returning header: hides while you scroll down, slides back as soon as you
 * scroll up a little. Attach [nestedScrollConnection] to the scrolling container's parent.
 */
@androidx.compose.runtime.Stable
class ReturningHeaderState {
    var revealed by androidx.compose.runtime.mutableStateOf(false)
    val nestedScrollConnection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
        ): androidx.compose.ui.geometry.Offset {
            if (available.y > 6f) revealed = true else if (available.y < -6f) revealed = false
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }
}

@Composable
fun rememberReturningHeaderState(): ReturningHeaderState = remember { ReturningHeaderState() }

/** Compact pinned title bar shown by [ReturningHeaderState] once the big header has scrolled away. */
@Composable
fun ReturningTopBar(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut()
    ) {
        Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 3.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                actions()
            }
        }
    }
}

/** Soft scrim under the status bar (mirrors [BottomFade]). */
@Composable
fun TopFade(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            // Reaches a little past the status bar so text never sits right behind the clock
            .height(androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to bg.copy(alpha = EDGE_FADE_ALPHA),
                    0.6f to bg.copy(alpha = EDGE_FADE_ALPHA * 0.85f),
                    1f to bg.copy(alpha = 0f)
                )
            )
    )
}

/** Large serif screen title with an optional quiet subtitle and trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = horizontalPadding, end = (horizontalPadding - 12.dp).coerceAtLeast(0.dp), top = 16.dp, bottom = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

/** Calm rounded section container used across screens. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}
