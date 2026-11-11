package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/** Classic book-cover display face, used for screen titles and book titles. */
val PlayfairDisplay = FontFamily(
    variableFont(R.font.playfair_display, 400),
    variableFont(R.font.playfair_display, 500),
    variableFont(R.font.playfair_display, 600),
    variableFont(R.font.playfair_display, 700)
)

/** Literata – a typeface designed for long-form reading; the Reader's default font. */
val Literata = FontFamily(
    variableFont(R.font.literata, 300),
    variableFont(R.font.literata, 400),
    variableFont(R.font.literata, 500),
    variableFont(R.font.literata, 600),
    variableFont(R.font.literata, 700)
)

private val base = Typography()

val Typography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = PlayfairDisplay, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = PlayfairDisplay, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = PlayfairDisplay, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = PlayfairDisplay, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = PlayfairDisplay, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(
        fontFamily = PlayfairDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge,
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall
)
