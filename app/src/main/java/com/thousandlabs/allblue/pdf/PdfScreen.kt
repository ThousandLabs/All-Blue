package com.thousandlabs.allblue.pdf

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.Screen
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.Feature
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.data.ReadingTheme
import com.thousandlabs.allblue.data.readingTheme
import com.thousandlabs.allblue.data.setReadingTheme
import com.thousandlabs.allblue.ui.BarBg
import com.thousandlabs.allblue.ui.ViewerBg
import com.thousandlabs.allblue.ui.Accent
import com.thousandlabs.allblue.ui.InputDialog
import com.thousandlabs.allblue.ui.Surface1
import com.thousandlabs.allblue.ui.TextDim
import com.thousandlabs.allblue.ui.TextMain
import com.thousandlabs.allblue.ui.parsePages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

/** Texte des pages, gardé en mémoire pour ne pas le recalculer. */
object PdfTextCache {
    private val map = HashMap<String, List<String>>()
    fun key(f: File) = "${f.absolutePath}:${f.lastModified()}:${f.length()}"
    fun get(f: File) = map[key(f)]
    fun put(f: File, v: List<String>) { if (map.size > 6) map.clear(); map[key(f)] = v }
}

fun readingFilter(t: ReadingTheme): ColorFilter? = when (t) {
    ReadingTheme.NORMAL -> null
    ReadingTheme.NIGHT -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.86f, 0f, 0f, 0f, 235f,
                0f, -0.86f, 0f, 0f, 235f,
                0f, 0f, -0.86f, 0f, 240f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    ReadingTheme.SEPIA -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private data class Hit(val page: Int, val snippet: String)

@OptIn(FlowPreview::class)
@Composable
fun PdfScreen(doc: Doc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current

    var engine by remember { mutableStateOf<PdfEngine?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needPassword by remember { mutableStateOf(false) }

    LaunchedEffect(doc.file) {
        runCatching { PdfEngine.open(doc.file) }
            .onSuccess { engine = it }
            .onFailure { e ->
                if (e is SecurityException || withContext(Dispatchers.IO) { PdfTools.isEncrypted(doc.file) }) needPassword = true
                else error = "Ce PDF est endommagé ou illisible."
            }
    }
    DisposableEffect(engine) {
        val e = engine
        onDispose { e?.close() }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = Feature.KEEP_SCREEN.enabled
        onDispose { view.keepScreenOn = false }
    }

    if (needPassword) {
        InputDialog(
            title = "PDF protégé",
            message = "Ce document est protégé par un mot de passe. Une copie sans mot de passe sera créée à côté de l'original.",
            label = "Mot de passe",
            password = true,
            confirm = "Ouvrir",
            onDismiss = { Nav.pop() },
            onConfirm = { pw ->
                scope.launch {
                    Nav.busy.value = "Déverrouillage…"
                    val out = Files.siblingName(doc.file, "sans mot de passe")
                    val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.removePassword(doc.file, pw, out) }.isSuccess }
                    Nav.busy.value = null
                    if (ok) { needPassword = false; Nav.reopen(Doc(out, out.name)); Prefs.addRecent(out.absolutePath) }
                    else Files.toast(context, "Mot de passe incorrect.")
                }
            }
        )
    }

    val eng = engine
    if (eng == null) {
        Box(Modifier.fillMaxSize().background(ViewerBg), contentAlignment = Alignment.Center) {
            if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = TextMain)
                TextButton(onClick = { Nav.pop() }) { Text("Retour") }
            }
        }
        return
    }

    // ---------- État de lecture ----------
    val prefsVersion by Prefs.version.collectAsState()
    val theme = remember(prefsVersion) { readingTheme() }
    val startPage = remember { if (Feature.RESUME.enabled) Prefs.lastPage(doc.file.absolutePath).coerceIn(0, eng.pageCount - 1) else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val hScroll = rememberScrollState()
    var scale by remember { mutableFloatStateOf(1f) }
    var settledScale by remember { mutableFloatStateOf(1f) }
    var chrome by remember { mutableStateOf(true) }
    var textMode by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(Prefs.getFloat("text_size", 17f)) }
    val favorites by Prefs.favorites.collectAsState()
    val isFav = doc.file.absolutePath in favorites

    var texts by remember { mutableStateOf(PdfTextCache.get(doc.file)) }
    var textProgress by remember { mutableStateOf<Float?>(null) }
    suspend fun ensureTexts(): List<String> {
        texts?.let { return it }
        textProgress = 0f
        var t = withContext(Dispatchers.IO) {
            runCatching { PdfTools.pagesText(doc.file) { i, n -> textProgress = i.toFloat() / n } }.getOrDefault(List(eng.pageCount) { "" })
        }
        // Document scanné déjà reconnu : on reprend le texte de l'OCR
        if (t.count { it.isBlank() } * 2 > t.size) {
            withContext(Dispatchers.IO) { Ocr.cached(context, doc.file) }?.takeIf { it.size == t.size }?.let { o ->
                t = t.mapIndexed { i, s -> if (s.isBlank()) o[i].text else s }
            }
        }
        PdfTextCache.put(doc.file, t)
        texts = t
        textProgress = null
        return t
    }

    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(600).collect {
            Prefs.setLastPage(doc.file.absolutePath, it)
        }
    }

    // ---------- Reconnaissance du texte (documents scannés) ----------
    var askOcr by remember { mutableStateOf(false) }
    var ocrProgress by remember { mutableStateOf<String?>(null) }
    suspend fun runOcr(): List<OcrPage>? {
        ocrProgress = "Reconnaissance du texte…"
        val pages = runCatching {
            Ocr.pdf(context, eng, doc.file) { i, n -> ocrProgress = "Reconnaissance du texte… page $i sur $n" }
        }.getOrNull()
        ocrProgress = null
        if (pages == null) { Files.toast(context, "La reconnaissance du texte a échoué."); return null }
        val base = texts ?: List(eng.pageCount) { "" }
        val merged = base.mapIndexed { i, s -> if (s.isBlank()) pages.getOrNull(i)?.text.orEmpty() else s }
        PdfTextCache.put(doc.file, merged)
        texts = merged
        return pages
    }

    // ---------- Lecture à voix haute ----------
    var speakingPage by remember { mutableIntStateOf(-1) }
    val tts = remember {
        Tts(context, onPageStart = { p ->
            speakingPage = p
            scope.launch { if (!textMode) listState.animateScrollToItem(p) }
        }, onStop = { speakingPage = -1 })
    }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    // ---------- Fenêtres ----------
    var showMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var showSendPages by remember { mutableStateOf(false) }
    var showProtect by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(ViewerBg)) {
        if (textMode) {
            TextModeView(texts, textProgress, fontSize, theme, listStateIndex = currentPage, speakingPage = speakingPage,
                onTap = { chrome = !chrome }, onOcr = { askOcr = true })
        } else {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        // Zoom à deux doigts (les gestes à un doigt restent pour le défilement)
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var zooming = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    zooming = true
                                    val z = event.calculateZoom()
                                    val c = event.calculateCentroid(useCurrent = true)
                                    if (z != 1f) {
                                        val old = scale
                                        val new = (old * z).coerceIn(1f, 5f)
                                        if (new != old) {
                                            val f = new / old
                                            val targetX = ((hScroll.value + c.x) * f - c.x).toInt()
                                            scale = new
                                            scope.launch { hScroll.scrollTo(targetX) }
                                            scope.launch { listState.scrollBy((c.y) * (f - 1f)) }
                                        }
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (zooming) settledScale = scale
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { chrome = !chrome },
                            onDoubleTap = { pos ->
                                val old = scale
                                val new = if (old > 1.05f) 1f else 2.2f
                                val f = new / old
                                scale = new
                                settledScale = new
                                scope.launch { hScroll.scrollTo(((hScroll.value + pos.x) * f - pos.x).toInt()) }
                                scope.launch { listState.scrollBy(pos.y * (f - 1f)) }
                            }
                        )
                    }
            ) {
                val viewW = constraints.maxWidth
                val contentW = (viewW * scale).toInt()
                // Qualité de l'image : on recalcule la page en plus net une fois le zoom terminé
                val renderW = (viewW * settledScale.coerceAtMost(2.5f)).toInt().let { (it / 120) * 120 }.coerceAtLeast(240)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().horizontalScroll(hScroll).width(with(density) { contentW.toDp() }),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(eng.pageSizes, key = { i, _ -> i }) { i, size ->
                        PageView(eng, i, size, renderW, theme)
                    }
                }
            }
        }

        // ---------- Barre du haut ----------
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(BarBg).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
                Column(Modifier.weight(1f)) {
                    Text(doc.name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("Page ${currentPage + 1} sur ${eng.pageCount}", color = TextDim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { showGoTo = true })
                }
                IconButton(onClick = { showSearch = true }) { Icon(Icons.Outlined.Search, "Rechercher", tint = TextMain) }
                IconButton(onClick = { Prefs.toggleFavorite(doc.file.absolutePath) }) {
                    Icon(if (isFav) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Favori", tint = if (isFav) Color(0xFFFFC857) else TextMain)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, "Plus", tint = TextMain) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        Text("Couleur de lecture", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                        ReadingTheme.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label, fontWeight = if (t == theme) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { Icon(if (t == theme) Icons.Outlined.Check else Icons.Outlined.Palette, null) },
                                onClick = { setReadingTheme(t); showMenu = false })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Compresser") }, leadingIcon = { Icon(Icons.Outlined.Compress, null) },
                            onClick = { showMenu = false; showCompress = true })
                        DropdownMenuItem(text = { Text("Envoyer certaines pages") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = { showMenu = false; showSendPages = true })
                        DropdownMenuItem(text = { Text("Page actuelle en image") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    val bmp = eng.renderForExport(currentPage, 2000) ?: return@launch
                                    val out = File(File(context.cacheDir, "travail").apply { mkdirs() }, "${doc.file.nameWithoutExtension} - page ${currentPage + 1}.jpg")
                                    withContext(Dispatchers.IO) { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                                    Files.share(context, listOf(out))
                                }
                            })
                        DropdownMenuItem(text = { Text("Convertir en Word") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    val t = ensureTexts()
                                    if (t.all { it.isBlank() }) { askOcr = true; return@launch }
                                    Nav.busy.value = "Conversion en Word…"
                                    val out = Files.siblingName(doc.file, "Word", "docx")
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { com.thousandlabs.allblue.docs.DocxWriter.write(out, doc.file.nameWithoutExtension, t.map { reflow(it) }) }.isSuccess
                                    }
                                    Nav.busy.value = null
                                    if (ok) {
                                        Files.toast(context, "Document Word créé : ${out.name}")
                                        Prefs.addRecent(out.absolutePath)
                                        Nav.push(Screen.Viewer(Doc(out, out.name)))
                                    } else Files.toast(context, "La conversion a échoué.")
                                }
                            })
                        DropdownMenuItem(text = { Text("Reconnaître le texte (OCR)") }, leadingIcon = { Icon(Icons.Outlined.DocumentScanner, null) },
                            onClick = { showMenu = false; askOcr = true })
                        DropdownMenuItem(text = { Text("Enregistrer une copie") }, leadingIcon = { Icon(Icons.Outlined.FileCopy, null) },
                            onClick = {
                                showMenu = false
                                val out = Files.siblingName(doc.file, "copie")
                                runCatching { doc.file.copyTo(out) }.onSuccess { Files.toast(context, "Copie : ${out.name}") }
                            })
                        DropdownMenuItem(text = { Text("Protéger par mot de passe") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                            onClick = { showMenu = false; showProtect = true })
                        DropdownMenuItem(text = { Text("Imprimer") }, leadingIcon = { Icon(Icons.Outlined.Print, null) },
                            onClick = { showMenu = false; PrintHelper.printPdf(context, doc.file, doc.name) })
                        DropdownMenuItem(text = { Text("Ouvrir avec une autre appli") }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                            onClick = { showMenu = false; Files.openWithOther(context, doc.file) })
                        DropdownMenuItem(text = { Text("Infos du fichier") }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { showMenu = false; showInfo = true })
                    }
                }
            }
        }

        // ---------- Barre du bas ----------
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(BarBg).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (eng.pageCount > 1 && !textMode) {
                    var dragValue by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragValue ?: currentPage.toFloat(),
                        onValueChange = { dragValue = it },
                        onValueChangeFinished = {
                            dragValue?.let { v -> scope.launch { listState.scrollToItem(v.toInt()) } }
                            dragValue = null
                        },
                        valueRange = 0f..(eng.pageCount - 1).toFloat(),
                        modifier = Modifier.padding(horizontal = 8.dp).height(32.dp)
                    )
                }
                if (textMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Taille du texte", color = TextDim, modifier = Modifier.weight(1f))
                        IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(11f); Prefs.putFloat("text_size", fontSize) }) {
                            Icon(Icons.Outlined.TextDecrease, "Plus petit", tint = TextMain)
                        }
                        IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(34f); Prefs.putFloat("text_size", fontSize) }) {
                            Icon(Icons.Outlined.TextIncrease, "Plus grand", tint = TextMain)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Outlined.Toc, "Sommaire") { showOutline = true }
                    if (Feature.TEXT_MODE.enabled) ToolButton(Icons.AutoMirrored.Outlined.Notes, if (textMode) "Pages" else "Texte") {
                        textMode = !textMode
                        if (textMode) scope.launch { ensureTexts() }
                    }
                    if (Feature.TTS.enabled) {
                        if (speakingPage >= 0) ToolButton(Icons.Outlined.Stop, "Arrêter") { tts.stop() }
                        else ToolButton(Icons.AutoMirrored.Outlined.VolumeUp, "Écouter") {
                            scope.launch {
                                val t = ensureTexts()
                                if (t.all { it.isBlank() }) askOcr = true
                                else tts.start(t, currentPage)
                            }
                        }
                    }
                    if (Feature.ANNOTATE.enabled) ToolButton(Icons.Outlined.Draw, "Annoter") {
                        tts.stop(); Nav.push(Screen.Annotate(doc, currentPage))
                    }
                    if (Feature.PAGE_TOOLS.enabled) ToolButton(Icons.Outlined.ViewModule, "Pages") {
                        tts.stop(); Nav.push(Screen.PageTools(doc))
                    }
                    ToolButton(Icons.Outlined.Share, "Envoyer") { Files.share(context, listOf(doc.file)) }
                }
            }
        }

        textProgress?.let { p ->
            Column(Modifier.align(Alignment.Center).background(Surface1, RoundedCornerShape(16.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Lecture du texte…", color = TextMain)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.width(200.dp))
            }
        }
    }

    // ---------- Fenêtres ----------
    if (askOcr) {
        AlertDialog(
            onDismissRequest = { askOcr = false },
            title = { Text("Reconnaître le texte") },
            text = {
                Text("All Blue va lire le texte des pages scannées, sans connexion (une à deux secondes par page). " +
                    "Ensuite, la recherche, le mode texte, la lecture à voix haute et la conversion en Word fonctionneront.\n\n" +
                    "Vous pourrez aussi créer une copie « cherchable » du PDF, où le texte se sélectionne et se retrouve dans n'importe quel lecteur.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { askOcr = false; scope.launch { runOcr() } }) { Text("Reconnaître le texte") }
                    TextButton(onClick = {
                        askOcr = false
                        scope.launch {
                            val pages = runOcr() ?: return@launch
                            Nav.busy.value = "Création du PDF cherchable…"
                            val out = Files.siblingName(doc.file, "cherchable")
                            val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.makeSearchable(doc.file, pages, out) }.isSuccess }
                            Nav.busy.value = null
                            if (ok) {
                                Files.toast(context, "PDF cherchable créé : ${out.name}")
                                Prefs.addRecent(out.absolutePath)
                                Nav.push(Screen.Viewer(Doc(out, out.name)))
                            } else Files.toast(context, "La création a échoué.")
                        }
                    }) { Text("Reconnaître et créer un PDF cherchable") }
                    TextButton(onClick = { askOcr = false }) { Text("Annuler") }
                }
            }
        )
    }

    ocrProgress?.let { com.thousandlabs.allblue.ui.BusyOverlay(it) }

    if (showGoTo) {
        InputDialog(title = "Aller à la page", label = "1 à ${eng.pageCount}", number = true, confirm = "Aller",
            onDismiss = { showGoTo = false },
            onConfirm = { v ->
                showGoTo = false
                v.trim().toIntOrNull()?.let { p -> scope.launch { listState.scrollToItem((p - 1).coerceIn(0, eng.pageCount - 1)) } }
            })
    }

    if (showSearch) {
        SearchDialog(
            loadTexts = { ensureTexts() },
            onOcr = { showSearch = false; askOcr = true },
            onDismiss = { showSearch = false },
            onPick = { p -> showSearch = false; scope.launch { listState.scrollToItem(p) } }
        )
    }

    if (showOutline) {
        var entries by remember { mutableStateOf<List<OutlineEntry>?>(null) }
        LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { PdfTools.outline(doc.file) } }
        AlertDialog(
            onDismissRequest = { showOutline = false },
            title = { Text("Sommaire") },
            text = {
                val e = entries
                when {
                    e == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    e.isEmpty() -> Text("Ce document n'a pas de sommaire. Utilisez la barre en bas ou touchez le numéro de page pour aller directement à une page.")
                    else -> LazyColumn(Modifier.height(420.dp)) {
                        itemsIndexed(e) { _, item ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    showOutline = false; textMode = false
                                    scope.launch { listState.scrollToItem(item.page) }
                                }.padding(start = (item.depth * 14).dp, top = 10.dp, bottom = 10.dp)
                            ) {
                                Text(item.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.page + 1}", color = TextDim)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showOutline = false }) { Text("Fermer") } }
        )
    }

    if (showCompress) {
        AlertDialog(
            onDismissRequest = { showCompress = false },
            title = { Text("Compresser le PDF") },
            text = {
                Text("Crée une version allégée à côté de l'original (l'original n'est pas modifié). " +
                    "Les pages sont transformées en images : idéal pour les documents scannés à envoyer sur WhatsApp, " +
                    "mais le texte ne sera plus sélectionnable.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    listOf(Triple("Légère (bonne qualité)", 1400, 72), Triple("Forte (plus léger)", 1050, 50)).forEach { (label, w, q) ->
                        TextButton(onClick = {
                            showCompress = false
                            scope.launch {
                                val out = Files.siblingName(doc.file, "compressé")
                                Nav.busy.value = "Compression…"
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { PdfTools.compress(eng, out, w, q) { i, n -> Nav.busy.value = "Compression… page $i sur $n" } }.isSuccess
                                }
                                Nav.busy.value = null
                                if (ok) {
                                    val before = doc.file.length(); val after = out.length()
                                    if (after >= before) {
                                        Files.toast(context, "Ce fichier est déjà bien compressé : ${Files.sizeLabel(after)} contre ${Files.sizeLabel(before)}.")
                                    } else {
                                        Files.toast(context, "${Files.sizeLabel(before)} → ${Files.sizeLabel(after)}")
                                    }
                                    Prefs.addRecent(out.absolutePath)
                                    Nav.push(Screen.Viewer(Doc(out, out.name)))
                                } else Files.toast(context, "La compression a échoué.")
                            }
                        }) { Text(label) }
                    }
                    TextButton(onClick = { showCompress = false }) { Text("Annuler") }
                }
            }
        )
    }

    if (showSendPages) {
        InputDialog(
            title = "Envoyer certaines pages",
            message = "Exemple : 1-3, 5, 8",
            label = "Pages (1 à ${eng.pageCount})",
            confirm = "Envoyer",
            onDismiss = { showSendPages = false },
            onConfirm = { v ->
                showSendPages = false
                val pages = parsePages(v, eng.pageCount)
                if (pages.isEmpty()) { Files.toast(context, "Aucune page valide."); return@InputDialog }
                scope.launch {
                    val out = File(File(context.cacheDir, "travail").apply { mkdirs() }, "${doc.file.nameWithoutExtension} (pages ${v.trim()}).pdf".let(Files::sanitize))
                    val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.rebuild(doc.file, pages.map { it to 0 }, out) }.isSuccess }
                    if (ok) Files.share(context, listOf(out)) else Files.toast(context, "Impossible d'extraire ces pages.")
                }
            }
        )
    }

    if (showProtect) {
        InputDialog(
            title = "Protéger par mot de passe",
            message = "Une copie protégée sera créée à côté de l'original. Notez bien le mot de passe : il est impossible de le retrouver.",
            label = "Mot de passe",
            password = true,
            confirm = "Protéger",
            onDismiss = { showProtect = false },
            onConfirm = { pw ->
                showProtect = false
                scope.launch {
                    val out = Files.siblingName(doc.file, "protégé")
                    val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.addPassword(doc.file, pw, out) }.isSuccess }
                    Files.toast(context, if (ok) "Copie protégée : ${out.name}" else "La protection a échoué.")
                }
            }
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Infos du fichier") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nom : ${doc.name}")
                    Text("Pages : ${eng.pageCount}")
                    Text("Taille : ${Files.sizeLabel(doc.file.length())}")
                    Text("Modifié : ${Files.dateLabel(doc.file.lastModified())}")
                    Text("Emplacement : ${if (doc.isCopy) "reçu d'une autre appli" else doc.file.parent ?: ""}", color = TextDim)
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Fermer") } }
        )
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = TextMain, modifier = Modifier.size(22.dp))
        Text(label, color = TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun PageView(eng: PdfEngine, index: Int, size: Pair<Int, Int>, renderW: Int, theme: ReadingTheme) {
    var bmp by remember(index) { mutableStateOf(eng.cached(index, renderW)) }
    LaunchedEffect(index, renderW) {
        if (bmp == null || bmp!!.width != renderW) {
            // garde l'ancienne image (moins nette) affichée pendant le calcul de la nouvelle
            eng.render(index, renderW)?.let { bmp = it }
        }
    }
    val ratio = size.first.toFloat() / size.second.coerceAtLeast(1)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(if (theme == ReadingTheme.NIGHT) Color(0xFF15181F) else Color.White),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null && !b.isRecycled) {
            Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                colorFilter = readingFilter(theme))
        } else {
            Text("${index + 1}", color = Color(0xFF9AA0AA))
        }
    }
}

@Composable
private fun TextModeView(
    texts: List<String>?, progress: Float?, fontSize: Float, theme: ReadingTheme,
    listStateIndex: Int, speakingPage: Int, onTap: () -> Unit, onOcr: () -> Unit
) {
    val (bg, fg) = when (theme) {
        ReadingTheme.NORMAL -> Color(0xFFFBFBF8) to Color(0xFF1C1F26)
        ReadingTheme.NIGHT -> Color(0xFF12161F) to Color(0xFFDCE1EA)
        ReadingTheme.SEPIA -> Color(0xFFF3E9D2) to Color(0xFF4A3B28)
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = listStateIndex.coerceAtLeast(0))
    LaunchedEffect(speakingPage) { if (speakingPage >= 0) state.animateScrollToItem(speakingPage) }
    Box(Modifier.fillMaxSize().background(bg).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        if (texts == null) {
            if (progress == null) Text("Préparation…", color = fg, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        if (texts.all { it.isBlank() }) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Ce PDF ne contient pas de texte lisible : c'est un document scanné (des photos de pages).",
                    color = fg
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Button(onClick = onOcr) { Text("Reconnaître le texte") }
            }
            return@Box
        }
        SelectionContainer {
            LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 80.dp, bottom = 160.dp)) {
                itemsIndexed(texts) { i, t ->
                    Column(Modifier.padding(bottom = 20.dp)) {
                        Text("Page ${i + 1}", color = if (i == speakingPage) Accent else fg.copy(alpha = 0.5f),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(reflow(t), color = fg, fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp)
                    }
                }
            }
        }
    }
}

/** Recolle les lignes coupées du PDF pour que le texte s'adapte à la largeur de l'écran. */
internal fun reflow(t: String): String {
    val lines = t.replace("\r", "").split('\n')
    val sb = StringBuilder()
    for ((i, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isEmpty()) { if (!sb.endsWith("\n\n")) sb.append("\n\n"); continue }
        sb.append(line)
        val next = lines.getOrNull(i + 1)?.trim().orEmpty()
        val endsSentence = line.endsWith('.') || line.endsWith(':') || line.endsWith('!') || line.endsWith('?')
        val nextIsList = next.startsWith("•") || next.startsWith("-") || next.firstOrNull()?.isDigit() == true && next.take(4).contains('.')
        when {
            line.endsWith('-') && next.firstOrNull()?.isLowerCase() == true -> sb.setLength(sb.length - 1)
            endsSentence && (next.firstOrNull()?.isUpperCase() == true || nextIsList) -> sb.append('\n')
            nextIsList -> sb.append('\n')
            else -> sb.append(' ')
        }
    }
    return sb.toString().trim()
}

@Composable
private fun SearchDialog(loadTexts: suspend () -> List<String>, onOcr: () -> Unit, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var texts by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { texts = loadTexts() }
    val folded = remember(texts) { texts?.map { fold(it) } }
    val hits = remember(query, folded) {
        val q = fold(query.trim())
        if (q.length < 2 || folded == null) emptyList() else buildList {
            folded.forEachIndexed { p, t ->
                var idx = t.indexOf(q)
                var count = 0
                while (idx >= 0 && count < 3) {
                    val orig = texts!![p]
                    val a = (idx - 40).coerceAtLeast(0)
                    val b = (idx + q.length + 60).coerceAtMost(orig.length)
                    add(Hit(p, (if (a > 0) "…" else "") + orig.substring(a, b).replace('\n', ' ') + if (b < orig.length) "…" else ""))
                    idx = t.indexOf(q, idx + q.length); count++
                }
                if (size > 300) return@buildList
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rechercher dans le document") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Mot ou expression") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Effacer") } })
                Spacer(Modifier.height(8.dp))
                when {
                    texts == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    texts!!.all { it.isBlank() } -> Column {
                        Text("Ce PDF est un document scanné : il faut d'abord reconnaître son texte.", color = TextDim)
                        TextButton(onClick = onOcr) { Text("Reconnaître le texte") }
                    }
                    query.trim().length >= 2 && hits.isEmpty() -> Text("Aucun résultat.", color = TextDim)
                    hits.isNotEmpty() -> {
                        Text("${hits.size}${if (hits.size > 300) "+" else ""} résultat(s)", color = TextDim, style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.height(380.dp)) {
                            itemsIndexed(hits) { _, h ->
                                Column(Modifier.fillMaxWidth().clickable { onPick(h.page) }.padding(vertical = 8.dp)) {
                                    Text("Page ${h.page + 1}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(h.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
