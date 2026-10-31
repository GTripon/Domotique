package com.example.domotique1.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Une seule famille, Monospace, pour tout l'écran : les trames OpenWebNet, les adresses
 * WHERE et les valeurs numériques se relisent comme dans une console de bus.
 */
val FontConsole = FontFamily.Monospace

val TypographyNeon = Typography(
    displayLarge = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = 2.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        letterSpacing = 0.6.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.4.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontConsole,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
    ),
)
