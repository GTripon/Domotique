package com.example.domotique1.ui.theme

import androidx.compose.ui.graphics.Color

// Fond : bleu nuit très profond, presque noir, pour que les lueurs ressortent.
val InkNight = Color(0xFF050A12)
val InkDeep = Color(0xFF0A1220)
val InkPanel = Color(0xFF0E1A2B)
val InkInner = Color(0xFF132033)

// Lueurs principales.
val NeonCyan = Color(0xFF22D3EE)
val NeonTurquoise = Color(0xFF2DD4BF)
val NeonMagenta = Color(0xFFE93DB2)
val NeonViolet = Color(0xFF8B5CF6)
val NeonAmber = Color(0xFFFFB020)
val NeonLime = Color(0xFFA3E635)
val NeonRed = Color(0xE58B5CF6)

// Textes.
val PaperWhite = Color(0xFFF2F7FF)
val PaperDim = Color(0xFF9FB3CC)
val PaperFaint = Color(0xFF64788F)

/**
 * Palette du thème. `lightColorScheme` n'est jamais utilisé : l'application impose
 * son propre fond, les volets étant commandés en faible lumière.
 */
internal val NeonColorsDark = androidx.compose.material3.darkColorScheme(
    primary = NeonCyan,
    onPrimary = InkNight,
    secondary = NeonMagenta,
    onSecondary = InkNight,
    tertiary = NeonViolet,
    onTertiary = InkNight,
    background = InkNight,
    onBackground = PaperWhite,
    surface = InkDeep,
    onSurface = PaperWhite,
    surfaceVariant = InkInner,
    onSurfaceVariant = PaperDim,
    error = NeonRed,
    onError = InkNight,
    outline = Color(0xFF1E3350),
)
