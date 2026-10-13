package ai.storyteller.photocraft.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark, low-glare chrome so the photo stays the brightest thing on screen. */
private val Colors = darkColorScheme(
    primary = Color(0xFF2F7BF5),
    onPrimary = Color.White,
    secondary = Color(0xFF8AB4F8),
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF262626),
    surfaceContainer = Color(0xFF2C2C2C),
    onSurface = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFFB0B0B0),
)

@Composable
fun PhotoCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
