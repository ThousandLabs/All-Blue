package com.thousandlabs.allblue.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.Screen
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.DocType
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.data.typeOf
import com.thousandlabs.allblue.pdf.PdfTools
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipFile

// ---------------------------------------------------------------------------
// Recherche dans le contenu des documents
// ---------------------------------------------------------------------------

object ContentSearch {
    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun zipText(f: File, filter: (String) -> Boolean): String = ZipFile(f).use { z ->
        val sb = StringBuilder()
        for (e in z.entries()) {
            if (!filter(e.name)) continue
            val xml = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            sb.append(xml.replace(Regex("<[^>]+>"), " ")).append(' ')
            if (sb.length > 3_000_000) break
        }
        sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"")
    }

    private fun textOf(f: File): String? = runCatching {
        when (typeOf(f.name)) {
            DocType.PDF -> if (f.length() > 40L * 1024 * 1024) null else
                PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly()).use { d ->
                    PDFTextStripper().apply { endPage = minOf(d.numberOfPages, 60) }.getText(d)
                }
            DocType.WORD -> zipText(f) { it == "word/document.xml" }
            DocType.POWERPOINT -> zipText(f) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            DocType.EXCEL -> zipText(f) { it == "xl/sharedStrings.xml" }
            DocType.TEXT -> if (f.length() > 3L * 1024 * 1024) null else f.readText()
            else -> null
        }
    }.getOrNull()

    fun search(files: List<File>, query: String, onProgress: (Int, Int) -> Unit): List<Pair<File, String?>> {
        val q = fold(query)
        val candidates = files.filter { typeOf(it.name) in setOf(DocType.PDF, DocType.WORD, DocType.POWERPOINT, DocType.EXCEL, DocType.TEXT) }.take(500)
        val out = ArrayList<Pair<File, String?>>()
        candidates.forEachIndexed { i, f ->
            onProgress(i + 1, candidates.size)
            val text = textOf(f) ?: return@forEachIndexed
            val folded = fold(text)
            val idx = folded.indexOf(q)
            if (idx >= 0) {
                val a = (idx - 30).coerceAtLeast(0)
                val b = (idx + q.length + 50).coerceAtMost(text.length)
                out.add(f to ("…" + text.substring(a, b).replace(Regex("\\s+"), " ").trim() + "…"))
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Doublons : même taille et même contenu
// ---------------------------------------------------------------------------

object Duplicates {
    private fun hash(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < 1024 * 1024) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n); total += n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun find(files: List<File>): List<List<File>> =
        files.filter { it.length() > 0 }.groupBy { it.length() }.values.filter { it.size > 1 }
            .flatMap { same -> same.groupBy { hash(it) }.filterKeys { it != null }.values.filter { it.size > 1 } }
            .sortedByDescending { g -> g.size * g[0].length() }
}

// ---------------------------------------------------------------------------
// Barre d'écran commune
// ---------------------------------------------------------------------------

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit = { Nav.pop() }) {
    Row(
        Modifier.fillMaxWidth().background(Surface1).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Fusion de PDF
// ---------------------------------------------------------------------------

@Composable
fun MergeScreen(initial: List<File>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<File>().apply { addAll(initial) } }
    Column(Modifier.fillMaxSize().background(Night)) {
        TopBar("Fusionner des PDF", "${files.size} fichiers · l'ordre ci-dessous sera celui du PDF final")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(files, key = { i, f -> f.absolutePath + i }) { i, f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Accent, modifier = Modifier.width(28.dp))
                    TypeBadge(DocType.PDF)
                    Spacer(Modifier.width(10.dp))
                    Text(f.name, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(enabled = i > 0, onClick = { val t = files[i - 1]; files[i - 1] = files[i]; files[i] = t }) {
                        Icon(Icons.Outlined.ArrowUpward, "Monter", tint = if (i > 0) TextMain else TextDim)
                    }
                    IconButton(enabled = i < files.lastIndex, onClick = { val t = files[i + 1]; files[i + 1] = files[i]; files[i] = t }) {
                        Icon(Icons.Outlined.ArrowDownward, "Descendre", tint = if (i < files.lastIndex) TextMain else TextDim)
                    }
                    IconButton(onClick = { files.removeAt(i) }) { Icon(Icons.Outlined.Close, "Retirer", tint = TextDim) }
                }
            }
        }
        Button(
            enabled = files.size >= 2,
            onClick = {
                scope.launch {
                    Nav.busy.value = "Fusion…"
                    val out = File(Files.outputFolder(), Files.sanitize("${files.first().nameWithoutExtension} (fusion).pdf")).let {
                        if (it.exists()) Files.siblingName(it, "2") else it
                    }
                    val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.merge(files.toList(), out) }.isSuccess }
                    Nav.busy.value = null
                    if (ok) {
                        Files.toast(context, "PDF créé dans Documents/All Blue")
                        Nav.pop(); Nav.open(Doc(out, out.name))
                    } else Files.toast(context, "La fusion a échoué (un des PDF est peut-être protégé).")
                }
            },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
        ) { Text(if (files.size < 2) "Choisissez au moins 2 PDF" else "Fusionner") }
    }
}

// ---------------------------------------------------------------------------
// Photos en PDF
// ---------------------------------------------------------------------------

@Composable
fun ImagesToPdfScreen(initial: List<File>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<File>().apply { addAll(initial) } }
    var a4 by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Night)) {
        TopBar("Photos en PDF", "${files.size} image(s)")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = a4, onClick = { a4 = true }, label = { Text("Pages A4") })
            FilterChip(selected = !a4, onClick = { a4 = false }, label = { Text("Taille des photos") })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(files, key = { i, f -> f.absolutePath + i }) { i, f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(f)
                    Spacer(Modifier.width(10.dp))
                    Text("Page ${i + 1}", color = TextMain, modifier = Modifier.weight(1f))
                    IconButton(enabled = i > 0, onClick = { val t = files[i - 1]; files[i - 1] = files[i]; files[i] = t }) {
                        Icon(Icons.Outlined.ArrowUpward, "Monter", tint = if (i > 0) TextMain else TextDim)
                    }
                    IconButton(enabled = i < files.lastIndex, onClick = { val t = files[i + 1]; files[i + 1] = files[i]; files[i] = t }) {
                        Icon(Icons.Outlined.ArrowDownward, "Descendre", tint = if (i < files.lastIndex) TextMain else TextDim)
                    }
                    IconButton(onClick = { files.removeAt(i) }) { Icon(Icons.Outlined.Close, "Retirer", tint = TextDim) }
                }
            }
        }
        Button(
            enabled = files.isNotEmpty(),
            onClick = {
                scope.launch {
                    val out = File(Files.outputFolder(), "Photos ${java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.FRANCE).format(java.util.Date())}.pdf")
                    Nav.busy.value = "Création du PDF…"
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { PdfTools.imagesToPdf(files.toList(), out, a4) { i, n -> Nav.busy.value = "Création du PDF… $i sur $n" } }.isSuccess
                    }
                    Nav.busy.value = null
                    if (ok) {
                        Files.toast(context, "PDF créé dans Documents/All Blue")
                        Nav.pop(); Nav.open(Doc(out, out.name))
                    } else Files.toast(context, "La création du PDF a échoué.")
                }
            },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
        ) { Text("Créer le PDF") }
    }
}

@Composable
private fun Thumbnail(f: File) {
    var bmp by remember(f) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(f) { bmp = withContext(Dispatchers.IO) { PdfTools.loadImage(f, 240) } }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(56.dp).background(Surface2, RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    else Spacer(Modifier.size(56.dp).background(Surface2, RoundedCornerShape(8.dp)))
}

// ---------------------------------------------------------------------------
// Copies de secours
// ---------------------------------------------------------------------------

@Composable
fun BackupsScreen() {
    val context = LocalContext.current
    var list by remember { mutableStateOf(Files.backupDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    Column(Modifier.fillMaxSize().background(Night)) {
        TopBar("Copies de secours", "L'original de chaque document, avant modification (40 dernières)")
        if (list.isEmpty()) {
            EmptyState("Aucune copie", "Une copie est faite automatiquement avant chaque modification.", Icons.Outlined.History)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(list, key = { _, f -> f.absolutePath }) { _, f ->
                val name = f.name.substringAfter("__")
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(typeOf(name))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Copie du ${Files.dateLabel(f.lastModified())} · ${Files.sizeLabel(f.length())}", color = TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        // On restaure sous forme d'un nouveau fichier, pour ne jamais rien écraser
                        val out = File(Files.outputFolder(), Files.sanitize(name)).let { if (it.exists()) Files.siblingName(it, "restauré") else it }
                        runCatching { f.copyTo(out) }.onSuccess {
                            Files.toast(context, "Restauré dans Documents/All Blue")
                            Prefs.addRecent(out.absolutePath)
                            Nav.open(Doc(out, out.name))
                        }
                    }) { Icon(Icons.Outlined.Restore, "Restaurer", tint = TextMain) }
                    IconButton(onClick = {
                        val named = File(File(context.cacheDir, "travail").apply { mkdirs() }, name)
                        runCatching { f.copyTo(named, overwrite = true) }.onSuccess { Files.share(context, listOf(named)) }
                    }) { Icon(Icons.Outlined.Share, "Envoyer", tint = TextMain) }
                    IconButton(onClick = { f.delete(); list = list - f }) { Icon(Icons.Outlined.Close, "Supprimer", tint = TextDim) }
                }
            }
        }
    }
}

