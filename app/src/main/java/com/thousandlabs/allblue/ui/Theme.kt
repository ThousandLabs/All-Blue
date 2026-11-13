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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.thousandlabs.allblue.data.DocType

val Night = Color(0xFF0E1424)
val Surface1 = Color(0xFF162036)
val Surface2 = Color(0xFF1E2A45)
val Accent = Color(0xFF5B9BFF)
val TextMain = Color(0xFFEDF1F8)
val TextDim = Color(0xFF9AA6BD)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFF7FB4FF),
    background = Night,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Color(0xFF34425F),
    error = Color(0xFFFF6B6B)
)

@Composable
fun AllBlueTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

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
