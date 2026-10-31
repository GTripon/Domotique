package com.example.domotique1.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * Thème « console Néon » : fond bleu nuit dégradé, typographie monospace unique,
 * couleurs saturées pour les lueurs. L'application force son propre fond sombre,
 * les volets se commandant dans la pénombre.
 */
@Composable
fun Domotique1Theme(
    content: @Composable () -> Unit,
) {
    val brush = Brush.verticalGradient(
        0.0f to InkNight,
        0.45f to InkDeep,
        1.0f to Color(0xFF0A1626),
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(brush)) {
            MaterialTheme(
                colorScheme = NeonColorsDark,
                typography = TypographyNeon,
                content = content,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun Domotique1ThemePreview() {
    Domotique1Theme {
        MaterialTheme {
            Surface(color = InkPanel) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    androidx.compose.material3.Text("VOLETS")
                    androidx.compose.material3.Text("*2*1*14##")
                }
            }
        }
    }
}
