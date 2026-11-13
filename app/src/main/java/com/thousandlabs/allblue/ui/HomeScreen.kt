package com.thousandlabs.allblue.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.thousandlabs.allblue.BuildConfig
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.Screen
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.DocType
import com.thousandlabs.allblue.data.Feature
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.data.isListedDocument
import com.thousandlabs.allblue.data.typeOf
import com.thousandlabs.allblue.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Tab(val label: String, val icon: ImageVector) {
    RECENT("Récents", Icons.Outlined.History),
    BROWSE("Parcourir", Icons.Outlined.FolderOpen),
    TOOLS("Outils", Icons.Outlined.Widgets),
    SETTINGS("Réglages", Icons.Outlined.Settings)
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.RECENT) }
    var access by remember { mutableStateOf(Files.hasAllFilesAccess()) }
    var searchOpen by remember { mutableStateOf(false) }

    // Revérifie l'autorisation quand on revient des réglages d'Android
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = Files.hasAllFilesAccess() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        Nav.busy.value = "Ouverture…"
        Thread {
            val d = runCatching { Files.docFromUri(context, uri) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Nav.busy.value = null
                if (d != null) Nav.open(d) else Files.toast(context, "Impossible d'ouvrir ce fichier.")
            }
        }.start()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("All Blue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = TextMain)
                Text("Tous vos documents, sans publicité", color = TextDim, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Outlined.Search, "Rechercher", tint = TextMain) }
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.Folder, "Ouvrir un fichier", tint = TextMain) }
        }

        if (!access) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Surface2, RoundedCornerShape(16.dp)).padding(16.dp)
            ) {
                Text("Autoriser l'accès aux fichiers", fontWeight = FontWeight.SemiBold, color = TextMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nécessaire pour parcourir vos dossiers, retrouver les documents WhatsApp, chercher dans tous vos fichiers " +
                        "et enregistrer les modifications à côté de l'original. Rien ne quitte votre téléphone.",
                    color = TextDim, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { context.startActivity(Files.allFilesAccessIntent(context)) }) { Text("Autoriser") }
            }
        }

        UpdateBanner()

        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.RECENT -> RecentTab()
                Tab.BROWSE -> BrowseTab(access)
                Tab.TOOLS -> ToolsTab()
                Tab.SETTINGS -> SettingsTab()
            }
        }

        NavigationBar(containerColor = Surface1) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(t.icon, t.label) },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Accent.copy(alpha = 0.22f))
                )
            }
        }
    }

    if (searchOpen) SearchOverlay(onClose = { searchOpen = false })
}

// ---------------------------------------------------------------------------
// Ligne de document
// ---------------------------------------------------------------------------

@Composable
fun DocRow(file: File, subtitle: String? = null, onRemove: (() -> Unit)? = null) {
    val favorites by Prefs.favorites.collectAsState()
    val fav = file.absolutePath in favorites
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { if (file.exists()) Nav.openFile(file) else Files.toast(context, "Ce fichier n'existe plus.") }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeBadge(typeOf(file.name))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = if (file.exists()) TextMain else TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle ?: if (file.exists()) "${Files.sizeLabel(file.length())} · ${Files.dateLabel(file.lastModified())}" else "Fichier introuvable",
                color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { Prefs.toggleFavorite(file.absolutePath) }) {
            Icon(if (fav) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Favori", tint = if (fav) Color(0xFFFFC857) else TextDim)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Plus", tint = TextDim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Envoyer") }, onClick = { menu = false; if (file.exists()) Files.share(context, listOf(file)) })
                DropdownMenuItem(text = { Text("Ouvrir avec une autre appli") }, onClick = { menu = false; Files.openWithOther(context, file) })
                if (onRemove != null) DropdownMenuItem(text = { Text("Retirer de la liste") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Récents et favoris
// ---------------------------------------------------------------------------

@Composable
private fun RecentTab() {
    val recents by Prefs.recents.collectAsState()
    val favorites by Prefs.favorites.collectAsState()
    var showFav by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showFav, onClick = { showFav = false }, label = { Text("Récents") })
            FilterChip(selected = showFav, onClick = { showFav = true }, label = { Text("Favoris (${favorites.size})") })
        }
        val list = if (showFav) favorites.toList().sortedBy { File(it).name.lowercase() } else recents
        if (list.isEmpty()) {
            EmptyState(
                if (showFav) "Aucun favori" else "Aucun document récent",
                if (showFav) "Touchez l'étoile d'un document pour le retrouver ici." else "Ouvrez un fichier depuis Parcourir, WhatsApp ou votre gestionnaire de fichiers.",
                if (showFav) Icons.Outlined.StarBorder else Icons.Outlined.History
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it }) { path ->
                    DocRow(File(path), onRemove = if (showFav) ({ Prefs.toggleFavorite(path) }) else ({ Prefs.removeRecent(path) }))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Parcourir
// ---------------------------------------------------------------------------

@Composable
private fun BrowseTab(access: Boolean) {
    var dir by remember { mutableStateOf<File?>(null) }
    var allDocs by remember { mutableStateOf(false) }
    BackHandler(enabled = dir != null || allDocs) {
        if (allDocs) allDocs = false
        else dir = dir?.parentFile?.takeIf { it.absolutePath.startsWith(Files.root.absolutePath) && dir != Files.root }
    }
    if (!access) {
        EmptyState("Accès aux fichiers nécessaire", "Autorisez l'accès en haut de l'écran pour parcourir vos dossiers.", Icons.Outlined.Storage)
        return
    }
    when {
        allDocs -> AllDocuments(onBack = { allDocs = false })
        dir == null -> {
            val wa = remember { Files.whatsappFolders() }
            LazyColumn {
                item { SectionTitle("Raccourcis") }
                if (Feature.WHATSAPP.enabled) {
                    if (wa.isEmpty()) item { Place(Icons.Outlined.Chat, "Documents WhatsApp", "Aucun dossier WhatsApp trouvé") {} }
                    else items(wa) { f -> Place(Icons.Outlined.Chat, if (f.absolutePath.contains("Business")) "Documents WhatsApp Business" else "Documents WhatsApp", "Fichiers reçus sur WhatsApp") { dir = f } }
                }
                item { Place(Icons.Outlined.AutoFixHigh, "Tous mes documents", "PDF, Word, Excel, PowerPoint… de tout le téléphone") { allDocs = true } }
                item { Place(Icons.Outlined.Download, "Téléchargements", Files.downloads().absolutePath) { dir = Files.downloads() } }
                item { Place(Icons.Outlined.Folder, "Documents", Files.documents().absolutePath) { dir = Files.documents() } }
                item { Place(Icons.Outlined.Storage, "Stockage interne", "Tout le téléphone") { dir = Files.root } }
            }
        }
        else -> FolderView(dir!!, onOpenDir = { dir = it }, onUp = {
            dir = dir!!.parentFile?.takeIf { dir != Files.root && it.absolutePath.startsWith(Files.root.absolutePath) }
        })
    }
}

@Composable
private fun Place(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).background(Accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextDim)
    }
}

@Composable
private fun FolderView(dir: File, onOpenDir: (File) -> Unit, onUp: () -> Unit) {
    var entries by remember(dir) { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(dir) { entries = withContext(Dispatchers.IO) { Files.listFolder(dir) } }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Dossier parent", tint = TextMain) }
            Text(
                dir.absolutePath.removePrefix(Files.root.absolutePath).ifEmpty { "Stockage interne" }.trimStart('/'),
                color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
            )
        }
        val e = entries
        when {
            e == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            e.isEmpty() -> EmptyState("Dossier vide", "Aucun document lisible ici.", Icons.Outlined.FolderOpen)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(e, key = { it.absolutePath }) { f ->
                    if (f.isDirectory) Place(Icons.Outlined.Folder, f.name, "${f.listFiles()?.size ?: 0} éléments") { onOpenDir(f) }
                    else DocRow(f)
                }
            }
        }
    }
}

@Composable
private fun AllDocuments(onBack: () -> Unit) {
    var docs by remember { mutableStateOf<List<File>?>(null) }
    var filter by remember { mutableStateOf<DocType?>(null) }
    LaunchedEffect(Unit) { docs = withContext(Dispatchers.IO) { Files.walkDocuments() } }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
            Text("Tous mes documents" + (docs?.let { " (${it.size})" } ?: ""), color = TextMain)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Tout") })
            listOf(DocType.PDF, DocType.WORD, DocType.EXCEL, DocType.POWERPOINT, DocType.EPUB, DocType.TEXT).forEach { t ->
                FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(t.label) })
            }
        }
        val d = docs
        if (d == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Recherche dans tout le téléphone…", color = TextDim)
            }
        } else {
            val shown = if (filter == null) d else d.filter { typeOf(it.name) == filter }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.absolutePath }) { f -> DocRow(f, subtitle = "${Files.sizeLabel(f.length())} · ${f.parentFile?.name ?: ""}") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Recherche (nom et contenu)
// ---------------------------------------------------------------------------

@Composable
private fun SearchOverlay(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var inContent by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<File, String?>>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<File>?>(null) }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { if (Files.hasAllFilesAccess()) Files.walkDocuments() else emptyList() } }

    fun doSearch() {
        val q = query.trim()
        val files = all ?: return
        if (q.length < 2) { results = emptyList(); return }
        val byName = files.filter { it.name.contains(q, ignoreCase = true) }.map { it to null as String? }
        results = byName
        if (!inContent) return
        running = true
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                ContentSearch.search(files, q) { i, n -> progress = "Lecture des documents… $i sur $n" }
            }
            results = byName + found.filter { f -> byName.none { it.first == f.first } }
            running = false; progress = ""
        }
    }

    Column(Modifier.fillMaxSize().background(Night)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
            OutlinedTextField(
                value = query, onValueChange = { query = it; if (!inContent) doSearch() },
                placeholder = { Text("Nom du fichier ou mot") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { doSearch() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = emptyList() }) { Icon(Icons.Outlined.Close, "Effacer") } }
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chercher aussi dans le contenu", color = TextMain)
                Text("PDF, Word, PowerPoint et textes. Plus lent.", color = TextDim, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = inContent, onCheckedChange = { inContent = it; if (it) doSearch() })
        }
        if (inContent) TextButton(onClick = { doSearch() }, modifier = Modifier.padding(start = 12.dp), enabled = !running) { Text("Lancer la recherche") }
        if (all == null || running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            if (progress.isNotEmpty()) Text(progress, color = TextDim, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        if (query.trim().length >= 2 && results.isEmpty() && !running && all != null) {
            EmptyState("Aucun résultat", if (inContent) "Aucun document ne contient ce mot." else "Activez la recherche dans le contenu pour chercher à l'intérieur des documents.", Icons.Outlined.Search)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { it.first.absolutePath }) { (f, snippet) ->
                DocRow(f, subtitle = snippet ?: f.parentFile?.name)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Outils
// ---------------------------------------------------------------------------

@Composable
private fun ToolsTab() {
    val context = LocalContext.current
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Nav.busy.value = "Préparation…"
        Thread {
            val files = uris.mapNotNull { runCatching { Files.docFromUri(context, it, "application/pdf").file }.getOrNull() }
            android.os.Handler(android.os.Looper.getMainLooper()).post { Nav.busy.value = null; Nav.push(Screen.Merge(files)) }
        }.start()
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Nav.busy.value = "Préparation…"
        Thread {
            val files = uris.mapNotNull { runCatching { Files.docFromUri(context, it, "image/jpeg").file }.getOrNull() }
            android.os.Handler(android.os.Looper.getMainLooper()).post { Nav.busy.value = null; Nav.push(Screen.ImagesToPdf(files)) }
        }.start()
    }
    var showDuplicates by remember { mutableStateOf(false) }
    LazyColumn {
        item { SectionTitle("Créer") }
        item { Place(Icons.Outlined.MergeType, "Fusionner des PDF", "Assembler plusieurs PDF en un seul") { pickPdfs.launch(arrayOf("application/pdf")) } }
        item {
            Place(Icons.Outlined.Image, "Photos en PDF", "Vos photos ou captures en un seul PDF") {
                pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        item { SectionTitle("Ranger") }
        item { Place(Icons.Outlined.ContentCopy, "Trouver les doublons", "Les fichiers reçus plusieurs fois") { showDuplicates = true } }
        item { Place(Icons.Outlined.History, "Copies de secours", "Les versions d'origine avant modification") { Nav.push(Screen.Backups) } }
        item { SectionTitle("Astuce") }
        item {
            Text(
                "Pour convertir un Word, un Excel ou un PowerPoint en PDF : ouvrez-le, puis menu > Exporter en PDF.",
                color = TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
    }
    if (showDuplicates) DuplicatesOverlay(onClose = { showDuplicates = false })
}

@Composable
private fun DuplicatesOverlay(onClose: () -> Unit) {
    var groups by remember { mutableStateOf<List<List<File>>?>(null) }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { if (Files.hasAllFilesAccess()) Duplicates.find(Files.walkDocuments(20000)) else emptyList() } }
    Column(Modifier.fillMaxSize().background(Night)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
            Text("Doublons", color = TextMain, style = MaterialTheme.typography.titleMedium)
        }
        val g = groups
        when {
            g == null -> Column(Modifier.fillMaxWidth().padding(24.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Comparaison des fichiers…", color = TextDim) }
            g.isEmpty() -> EmptyState("Aucun doublon", "Chaque document n'existe qu'une fois.", Icons.Outlined.ContentCopy)
            else -> {
                val wasted = g.sumOf { grp -> grp.drop(1).sumOf { it.length() } }
                Text("${g.size} groupes · ${Files.sizeLabel(wasted)} récupérables. Supprimez les copies inutiles depuis votre gestionnaire de fichiers.",
                    color = TextDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn {
                    items(g) { grp ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text("${grp.size} copies · ${Files.sizeLabel(grp[0].length())}", color = Accent, modifier = Modifier.padding(horizontal = 20.dp), fontSize = 12.sp)
                            grp.forEach { f -> DocRow(f, subtitle = f.parent?.removePrefix(Files.root.absolutePath)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Réglages
// ---------------------------------------------------------------------------

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val version by Prefs.version.collectAsState()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("Lecteur par défaut") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    "Pour qu'All Blue ouvre automatiquement vos fichiers : ouvrez un PDF depuis WhatsApp ou vos fichiers, " +
                        "choisissez All Blue puis « Toujours ». Faites de même une fois pour Word, Excel et PowerPoint.",
                    color = TextDim, style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }) { Text("Ouvrir les applis par défaut d'Android") }
            }
        }
        item { SectionTitle("Fonctions") }
        Feature.entries.forEach { f ->
            item(key = f.key + version) {
                Row(Modifier.fillMaxWidth().clickable { Prefs.putBool(f.key, !f.enabled) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, color = TextMain)
                        Text(f.description, color = TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = f.enabled, onCheckedChange = { Prefs.putBool(f.key, it) })
                }
            }
        }
        item { SectionTitle("Lecture à voix haute") }
        item {
            var rate by remember { mutableStateOf(Prefs.getFloat("tts_rate", 1f)) }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Vitesse : ${String.format(java.util.Locale.FRANCE, "%.1f", rate)}×", color = TextMain)
                Slider(value = rate, onValueChange = { rate = it }, onValueChangeFinished = { Prefs.putFloat("tts_rate", rate) }, valueRange = 0.6f..2f)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Choisir ou installer une voix française") }
            }
        }
        item { SectionTitle("Historique") }
        item {
            TextButton(onClick = { Prefs.clearRecents(); Files.toast(context, "Liste des récents effacée") }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Effacer la liste des récents")
            }
        }
        item { SectionTitle("Mises à jour") }
        item { UpdateSettings() }
        item { SectionTitle("À propos") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("All Blue ${BuildConfig.VERSION_NAME}", color = TextMain)
                Text("Une appli Thousand Labs. Sans publicité, sans compte, tout reste sur votre téléphone.", color = TextDim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Page officielle sur GitHub") }
            }
        }
    }
}

const val REPO_URL = "https://github.com/ThousandLabs/All-Blue"

@Composable
private fun UpdateSettings() {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    var auto by remember { mutableStateOf(Updater.autoEnabled()) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vérifier automatiquement", color = TextMain, modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; Updater.setAutoEnabled(it) })
        }
        val label = when (val s = state) {
            Updater.State.Checking -> "Vérification…"
            Updater.State.UpToDate -> "Vous avez la dernière version."
            is Updater.State.Available -> "Nouvelle version ${s.versionName} disponible."
            is Updater.State.Downloading -> "Téléchargement… ${(s.progress * 100).toInt()} %"
            is Updater.State.Installing -> "Installation…"
            is Updater.State.Error -> s.message
            Updater.State.Idle -> "Version actuelle : ${BuildConfig.VERSION_NAME}"
        }
        Text(label, color = TextDim, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { Updater.check() }) { Text("Vérifier maintenant") }
            if (state is Updater.State.Available || (state as? Updater.State.Error)?.release != null) {
                TextButton(onClick = {
                    if (!Updater.canInstall(context)) context.startActivity(Updater.installPermissionIntent(context))
                    else Updater.downloadAndInstall(context)
                }) { Text("Installer") }
            }
        }
    }
}

@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    val dismissed by Updater.bannerDismissed.collectAsState()
    val s = state
    if (s !is Updater.State.Available || dismissed) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(Accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Nouvelle version ${s.versionName}", color = TextMain, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            if (!Updater.canInstall(context)) context.startActivity(Updater.installPermissionIntent(context))
            else Updater.downloadAndInstall(context)
        }) { Text("Installer") }
        IconButton(onClick = { Updater.dismissBanner() }) { Icon(Icons.Outlined.Close, "Plus tard", tint = TextDim) }
    }
}

