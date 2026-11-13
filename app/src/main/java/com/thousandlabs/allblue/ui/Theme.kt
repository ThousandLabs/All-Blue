package com.thousandlabs.allblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.thousandlabs.allblue.data.Prefs
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.thousandlabs.allblue.data.DocType

/** Couleurs de l'interface : thème clair ou sombre, plus la couleur principale choisie. */
data class Palette(
    val dark: Boolean,
    val bg: Color, val surface1: Color, val surface2: Color,
    val text: Color, val textDim: Color, val bar: Color, val viewer: Color, val outline: Color,
    val accent: Color, val onAccent: Color
)

/** Couleurs principales proposées : (nom, version thème clair, version thème sombre). */
val ACCENTS = listOf(
    Triple("Bleu océan", Color(0xFF2F6FE0), Color(0xFF5B9BFF)),
    Triple("Turquoise", Color(0xFF0E9F9A), Color(0xFF3DD6C8)),
    Triple("Corail", Color(0xFFE0563F), Color(0xFFFF8A70)),
    Triple("Or", Color(0xFFB67C00), Color(0xFFF4C95D)),
    Triple("Violet", Color(0xFF7B4FD8), Color(0xFFB39BFF)),
    Triple("Vert", Color(0xFF1E9E57), Color(0xFF4FD68C))
)

enum class AppTheme(val label: String) { LIGHT("Clair"), DARK("Sombre"), SYSTEM("Comme le téléphone") }

fun appTheme(): AppTheme = AppTheme.entries.getOrElse(Prefs.getInt("app_theme", 0)) { AppTheme.LIGHT }
fun accentIndex(): Int = Prefs.getInt("accent", 0).coerceIn(0, ACCENTS.lastIndex)

fun palette(dark: Boolean, accent: Int): Palette {
    val (_, light, darkAccent) = ACCENTS[accent.coerceIn(0, ACCENTS.lastIndex)]
    return if (dark) Palette(
        dark = true,
        bg = Color(0xFF121826), surface1 = Color(0xFF1A2233), surface2 = Color(0xFF243049),
        text = Color(0xFFEDF1F8), textDim = Color(0xFF9AA6BD), bar = Color(0xF2151C2B), viewer = Color(0xFF1A1F2B),
        outline = Color(0xFF34425F), accent = darkAccent, onAccent = Color(0xFF0A1322)
    ) else Palette(
        dark = false,
        bg = Color(0xFFFFFFFF), surface1 = Color(0xFFF4F6FA), surface2 = Color(0xFFE9EDF4),
        text = Color(0xFF151B27), textDim = Color(0xFF5F6878), bar = Color(0xF7FFFFFF), viewer = Color(0xFFE7EAF0),
        outline = Color(0xFFD3D9E3), accent = light, onAccent = Color.White
    )
}

val LocalPalette = staticCompositionLocalOf { palette(false, 0) }

val Night: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
val Surface1: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface1
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val TextMain: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
val TextDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textDim
val BarBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bar
val ViewerBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.viewer
val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline

@Composable
fun AllBlueTheme(content: @Composable () -> Unit) {
    val version by Prefs.version.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val p = remember(version, systemDark) {
        val dark = when (appTheme()) { AppTheme.LIGHT -> false; AppTheme.DARK -> true; AppTheme.SYSTEM -> systemDark }
        palette(dark, accentIndex())
    }
    val scheme = if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.surface1, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.textDim,
        surfaceContainer = p.surface1, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
        surfaceContainerLow = p.surface1, surfaceContainerLowest = p.bg,
        secondaryContainer = p.accent.copy(alpha = 0.25f), onSecondaryContainer = p.text,
        outline = p.outline, outlineVariant = p.outline, error = Color(0xFFFF6B6B)
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.textDim,
        surfaceContainer = p.surface1, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
        surfaceContainerLow = p.surface1, surfaceContainerLowest = p.bg,
        secondaryContainer = p.accent.copy(alpha = 0.16f), onSecondaryContainer = p.text,
        outline = p.outline, outlineVariant = p.outline, error = Color(0xFFD62828)
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Icône au trait et couleur propres à chaque type de document. */
fun typeIcon(t: DocType): ImageVector = when (t) {
    DocType.PDF -> Icons.Outlined.PictureAsPdf
    DocType.WORD -> Icons.Outlined.Description
    DocType.EXCEL -> Icons.Outlined.TableChart
    DocType.POWERPOINT -> Icons.Outlined.Slideshow
    DocType.EPUB -> Icons.Outlined.MenuBook
    DocType.TEXT -> Icons.AutoMirrored.Outlined.Article
    DocType.IMAGE -> Icons.Outlined.Image
    DocType.OTHER -> Icons.Outlined.InsertDriveFile
}

fun typeColor(t: DocType): Color = when (t) {
    DocType.PDF -> Color(0xFFFF6B6B)
    DocType.WORD -> Color(0xFF5B9BFF)
    DocType.EXCEL -> Color(0xFF3DD68C)
    DocType.POWERPOINT -> Color(0xFFFF9F43)
    DocType.EPUB -> Color(0xFFB98CFF)
    DocType.TEXT -> Color(0xFFB8C2D6)
    DocType.IMAGE -> Color(0xFF4FD1C5)
    DocType.OTHER -> Color(0xFF9AA6BD)
}

@Composable
fun TypeBadge(t: DocType, modifier: Modifier = Modifier) {
    Box(
        modifier.size(42.dp).background(typeColor(t).copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) { Icon(typeIcon(t), null, tint = typeColor(t), modifier = Modifier.size(22.dp)) }
}

/** Fenêtre d'attente pendant un travail long (fusion, compression…). */
@Composable
fun BusyOverlay(label: String, progress: Float? = null) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(Surface1, RoundedCornerShape(18.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (progress == null) CircularProgressIndicator()
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.size(width = 220.dp, height = 6.dp))
            Text(label, color = TextMain)
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, icon: ImageVector) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = TextDim, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextMain)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextDim)
    }
}

@Composable
fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = TextDim)
    }
}
