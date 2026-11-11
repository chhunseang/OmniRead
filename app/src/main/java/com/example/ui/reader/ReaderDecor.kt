package com.example.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.reader.ReaderTexture
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Background textures (linen / paper / canvas), generated once as a seamless tile
// ---------------------------------------------------------------------------------------------

private const val TILE = 256

private fun generateTexture(texture: ReaderTexture): android.graphics.Bitmap? {
    if (texture == ReaderTexture.NONE) return null
    val rnd = Random(texture.ordinal * 7919 + 17)
    val pixels = IntArray(TILE * TILE)
    // Periodic value noise on a coarse grid, for soft blotches
    fun valueNoise(cells: Int): (Int, Int) -> Float {
        val grid = Array(cells) { FloatArray(cells) { rnd.nextFloat() * 2f - 1f } }
        val step = TILE.toFloat() / cells
        return { x, y ->
            val gx = x / step
            val gy = y / step
            val x0 = gx.toInt() % cells
            val y0 = gy.toInt() % cells
            val x1 = (x0 + 1) % cells
            val y1 = (y0 + 1) % cells
            val fx = gx - gx.toInt()
            val fy = gy - gy.toInt()
            val sx = fx * fx * (3 - 2 * fx)
            val sy = fy * fy * (3 - 2 * fy)
            val top = grid[y0][x0] + (grid[y0][x1] - grid[y0][x0]) * sx
            val bottom = grid[y1][x0] + (grid[y1][x1] - grid[y1][x0]) * sx
            top + (bottom - top) * sy
        }
    }
    val blotch = valueNoise(8)
    val streakH = valueNoise(32)
    val rows = FloatArray(TILE) { rnd.nextFloat() * 2f - 1f }
    val cols = FloatArray(TILE) { rnd.nextFloat() * 2f - 1f }
    for (y in 0 until TILE) {
        for (x in 0 until TILE) {
            val grain = rnd.nextFloat() * 2f - 1f
            val v = when (texture) {
                // Woven threads: horizontal and vertical fibres whose strength drifts along their length
                ReaderTexture.LINEN ->
                    rows[y] * (0.55f + 0.45f * streakH(x, y)) * 0.55f +
                        cols[x] * (0.55f + 0.45f * streakH(y, x)) * 0.45f +
                        grain * 0.25f + blotch(x, y) * 0.25f
                ReaderTexture.PAPER -> grain * 0.45f + blotch(x, y) * 0.55f + streakH(x, y) * 0.2f
                ReaderTexture.CANVAS -> {
                    val wx = sin(x * 2 * PI / 4).toFloat()
                    val wy = sin(y * 2 * PI / 4).toFloat()
                    (if ((x / 4 + y / 4) % 2 == 0) wx else wy) * 0.45f + grain * 0.3f + blotch(x, y) * 0.3f
                }
                ReaderTexture.NONE -> 0f
            }.coerceIn(-1f, 1f)
            val alpha = (kotlin.math.abs(v) * 30).toInt().coerceIn(0, 255) // ≤ ~12% opacity
            pixels[y * TILE + x] = if (v >= 0) (alpha shl 24) or 0xFFFFFF else (alpha shl 24)
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, TILE, TILE, android.graphics.Bitmap.Config.ARGB_8888)
}

/** Draws [texture] as a subtle repeating overlay behind the page. */
@Composable
fun Modifier.readerTexture(texture: ReaderTexture): Modifier {
    val brush = remember(texture) {
        generateTexture(texture)?.let { ShaderBrush(ImageShader(it.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)) }
    }
    return if (brush == null) this else drawBehind { drawRect(brush) }
}

// ---------------------------------------------------------------------------------------------
// Status bar: battery + clock · chapter · page
// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberBatteryLevel(): Int {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (l >= 0 && scale > 0) level = l * 100 / scale
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        read(
            androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}

@Composable
private fun rememberClock(): String {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(java.util.Date())) }
    LaunchedEffect(format) {
        while (true) {
            now = format.format(java.util.Date())
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    return now
}

@Composable
fun ReaderStatusBar(
    chapterTitle: String,
    chapterNumber: Int,
    chapterCount: Int,
    pageText: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier
) {
    val battery = rememberBatteryLevel()
    val clock = rememberClock()
    val style = TextStyle(
        color = color.copy(alpha = 0.6f),
        fontSize = MaterialTheme.typography.labelSmall.fontSize,
        shadow = Shadow(background, blurRadius = 4f)
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            if (battery >= 0) "$battery%  $clock" else clock,
            style = style,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp)
        )
        Text(
            if (chapterCount > 1) "$chapterTitle ($chapterNumber/$chapterCount)" else chapterTitle,
            style = style,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            pageText,
            style = style,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp).padding(start = 0.dp)
        )
    }
}
