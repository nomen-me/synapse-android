package me.nomen.synapse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta provisória (o painel web é escuro, fundo #0D1117). Ajustar quando o guia visual da Nomen for fornecido.
private val Escuro = darkColorScheme(
    primary = Color(0xFF6AA9FF), onPrimary = Color(0xFF00305F),
    background = Color(0xFF0D1117), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B222C), onSurfaceVariant = Color(0xFFB4BFCB),
    error = Color(0xFFFF8A80), onError = Color(0xFF410002)
)
private val Claro = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    background = Color(0xFFF8F9FB), onBackground = Color(0xFF1B1F24),
    surface = Color(0xFFF8F9FB), onSurface = Color(0xFF1B1F24),
    surfaceVariant = Color(0xFFE3E7EE), onSurfaceVariant = Color(0xFF444B55),
    error = Color(0xFFB3261E), onError = Color.White
)

@Composable
fun SynapseTheme(escuro: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (escuro) Escuro else Claro, content = content)
}
