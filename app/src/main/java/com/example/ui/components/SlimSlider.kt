package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** A slider with a thin track and a slim handle (the app-wide style, instead of the chunky default). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors()
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(interactionSource = interaction, colors = colors, enabled = enabled, thumbSize = DpSize(4.dp, 22.dp))
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                enabled = enabled,
                modifier = Modifier.height(6.dp),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 3.dp
            )
        },
        modifier = modifier.height(36.dp)
    )
}
