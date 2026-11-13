package com.thousandlabs.allblue.docs

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.DocType
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.data.ReadingTheme
import com.thousandlabs.allblue.data.readingTheme
import com.thousandlabs.allblue.data.setReadingTheme
import com.thousandlabs.allblue.pdf.PrintHelper
import com.thousandlabs.allblue.ui.TextDim
import com.thousandlabs.allblue.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lecteur Word, Excel, PowerPoint et EPUB (affichage dans une page web locale, sans connexion). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficeScreen(doc: Doc) {
    val context = LocalContext.current
    val prefsVersion by Prefs.version.collectAsState()
    val theme = remember(prefsVersion) { readingTheme() }
    val favorites by Prefs.favorites.collectAsState()
    val isFav = doc.file.absolutePath in favorites

    var html by remember { mutableStateOf<File?>(null) }
    var epub by remember { mutableStateOf<EpubBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chapter by remember { mutableIntStateOf(Prefs.getInt("chap:${doc.file.absolutePath}", 0)) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var zoom by remember { mutableIntStateOf(Prefs.getInt("web_zoom", 100)) }
    var menu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf("") }

    LaunchedEffect(doc.file) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "travail").apply { mkdirs() }
                when (doc.type) {
                    DocType.EPUB -> { epub = Epub.open(doc.file, context.cacheDir); null }
                    else -> {
                        val page = when (doc.type) {
                            DocType.WORD -> OfficeHtml.docx(doc.file)
                            DocType.EXCEL -> OfficeHtml.xlsx(doc.file)
                            else -> OfficeHtml.pptx(doc.file)
                        }
                        File(dir, "vue_${System.currentTimeMillis()}.html").apply { writeText(page) }
                    }
                }
            }
        }
        r.onSuccess { html = it }.onFailure {
            error = "Ce fichier n'a pas pu être lu. Il est peut-être endommagé, protégé, ou dans l'ancien format (.doc, .xls, .ppt)."
        }
    }

    val ep = epub
    val target: File? = if (ep != null) ep.chapters.getOrNull(chapter.coerceIn(0, ep.chapters.lastIndex)) else html
    val themeJs = "document.body.classList.remove('night','sepia');" + when (theme) {
        ReadingTheme.NIGHT -> "document.body.classList.add('night');"
        ReadingTheme.SEPIA -> "document.body.classList.add('sepia');"
        ReadingTheme.NORMAL -> ""
    }
    // Pour les EPUB, les couleurs sont imposées par une feuille de style ajoutée au chapitre
    val epubCss = when (theme) {
        ReadingTheme.NORMAL -> "body{background:#fbfbf8;color:#1c1f26}"
        ReadingTheme.NIGHT -> "body,p,div,span,h1,h2,h3,li{background:#12161f !important;color:#dce1ea !important}a{color:#7fb4ff !important}"
        ReadingTheme.SEPIA -> "body,p,div,span,h1,h2,h3,li{background:#f3e9d2 !important;color:#4a3b28 !important}"
    } + "body{padding:14px 16px;line-height:1.6;font-family:serif}img{max-width:100%;height:auto}"
    val injectJs = if (ep != null) {
        "(function(){var s=document.getElementById('ab-css')||document.createElement('style');s.id='ab-css';s.textContent=${jsString(epubCss)};document.head&&document.head.appendChild(s);})();"
    } else themeJs

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1F2B))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xF0101626)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
            if (searching) {
                TextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        webView?.setFindListener { active, count, _ -> hits = if (count == 0) "0" else "${active + 1}/$count" }
                        webView?.findAllAsync(q)
                    },
                    singleLine = true,
                    placeholder = { Text("Rechercher") },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)
                )
                Text(hits, color = TextDim, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { webView?.findNext(false) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Précédent", tint = TextMain) }
                IconButton(onClick = { webView?.findNext(true) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Suivant", tint = TextMain) }
                IconButton(onClick = { searching = false; query = ""; hits = ""; webView?.clearMatches() }) { Icon(Icons.Outlined.Close, "Fermer", tint = TextMain) }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(ep?.title ?: doc.name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        if (ep != null) "Chapitre ${chapter + 1} sur ${ep.chapters.size}" else doc.type.label,
                        color = TextDim, style = MaterialTheme.typography.bodySmall
                    )
                }
                if (doc.type != DocType.EPUB && error == null) {
                    IconButton(onClick = { Nav.push(com.thousandlabs.allblue.Screen.EditOffice(doc)) }) {
                        Icon(Icons.Outlined.Edit, "Modifier", tint = TextMain)
                    }
                }
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Rechercher", tint = TextMain) }
                IconButton(onClick = { Prefs.toggleFavorite(doc.file.absolutePath) }) {
                    Icon(if (isFav) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Favori", tint = if (isFav) Color(0xFFFFC857) else TextMain)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Plus", tint = TextMain) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (doc.type == DocType.POWERPOINT) {
                            DropdownMenuItem(text = { Text("Mode présentation") }, leadingIcon = { Icon(Icons.Outlined.Slideshow, null) },
                                onClick = { menu = false; webView?.evaluateJavascript("togglePresent()", null) })
                        }
                        DropdownMenuItem(text = { Text("Exporter en PDF") }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                            onClick = {
                                menu = false
                                webView?.let { PrintHelper.printWebView(context, it, doc.file.nameWithoutExtension) }
                                Files.toast(context, "Choisissez « Enregistrer au format PDF » comme imprimante.")
                            })
                        Text("Couleur de lecture", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                        ReadingTheme.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label, fontWeight = if (t == theme) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { Icon(if (t == theme) Icons.Outlined.Check else Icons.Outlined.Palette, null) },
                                onClick = { setReadingTheme(t); menu = false })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Envoyer") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = { menu = false; Files.share(context, listOf(doc.file)) })
                        DropdownMenuItem(text = { Text("Ouvrir avec une autre appli") }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                            onClick = { menu = false; Files.openWithOther(context, doc.file) })
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = TextMain)
                    TextButton(onClick = { Files.openWithOther(context, doc.file) }) { Text("Ouvrir avec une autre appli") }
                }
                target == null -> Text("Préparation…", color = TextDim, modifier = Modifier.align(Alignment.Center))
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            setBackgroundColor(android.graphics.Color.parseColor("#1A1F2B"))
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    (view.tag as? String)?.let { view.evaluateJavascript(it, null) }
                                }
                                // les liens internes d'un EPUB s'ouvrent dans le lecteur, les liens web ne s'ouvrent pas
                                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                                    request.url.scheme != "file"
                            }
                            webView = this
                        }
                    },
                    update = { wv ->
                        wv.tag = injectJs
                        wv.settings.textZoom = zoom
                        val url = "file://" + target.absolutePath
                        if (wv.url != url) wv.loadUrl(url) else wv.evaluateJavascript(injectJs, null)
                    }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Color(0xF0101626)).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (ep != null) {
                IconButton(enabled = chapter > 0, onClick = {
                    chapter--; Prefs.putInt("chap:${doc.file.absolutePath}", chapter)
                }) { Icon(Icons.Outlined.ChevronLeft, "Chapitre précédent", tint = TextMain) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoom = (zoom - 10).coerceAtLeast(60); Prefs.putInt("web_zoom", zoom) }) {
                    Icon(Icons.Outlined.TextDecrease, "Texte plus petit", tint = TextMain)
                }
                Text("$zoom %", color = TextDim)
                IconButton(onClick = { zoom = (zoom + 10).coerceAtMost(220); Prefs.putInt("web_zoom", zoom) }) {
                    Icon(Icons.Outlined.TextIncrease, "Texte plus grand", tint = TextMain)
                }
            }
            if (ep != null) {
                IconButton(enabled = chapter < ep.chapters.lastIndex, onClick = {
                    chapter++; Prefs.putInt("chap:${doc.file.absolutePath}", chapter)
                }) { Icon(Icons.Outlined.ChevronRight, "Chapitre suivant", tint = TextMain) }
            }
        }
    }
}

private fun jsString(s: String): String =
    "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"
