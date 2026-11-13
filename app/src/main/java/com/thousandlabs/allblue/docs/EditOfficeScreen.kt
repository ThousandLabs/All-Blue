package com.thousandlabs.allblue.docs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.DocType
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.ui.ConfirmDialog
import com.thousandlabs.allblue.ui.Surface1
import com.thousandlabs.allblue.ui.TextDim
import com.thousandlabs.allblue.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Modification directe d'un Word, d'un Excel ou d'un PowerPoint. */
@Composable
fun EditOfficeScreen(doc: Doc) {
    when (doc.type) {
        DocType.EXCEL -> ExcelEditor(doc)
        else -> ParagraphEditor(doc)
    }
}

private fun workFile(context: android.content.Context, ext: String) =
    File(File(context.cacheDir, "travail").apply { mkdirs() }, "edit_${System.nanoTime()}.$ext")

/** Remplace l'original (ou crée une copie) puis rouvre le document modifié. */
private suspend fun finishSave(context: android.content.Context, doc: Doc, tmp: File, asCopy: Boolean) {
    val d = withContext(Dispatchers.IO) {
        if (asCopy) {
            val out = Files.siblingName(doc.file, "modifié")
            tmp.copyTo(out, overwrite = true); tmp.delete(); Doc(out, out.name)
        } else Files.replaceDocument(context, doc, tmp)
    }
    Prefs.addRecent(d.file.absolutePath)
    Files.toast(context, if (asCopy) "Enregistré : ${d.name}" else "Modifications enregistrées")
    Nav.reopen(d)
}

@Composable
private fun EditorBar(title: String, subtitle: String, canSave: Boolean, onBack: () -> Unit, onSave: (Boolean) -> Unit, extra: @Composable () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xF0101626)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        extra()
        Box {
            Button(onClick = { menu = true }, enabled = canSave) { Text("Enregistrer") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Enregistrer dans ce fichier") }, onClick = { menu = false; onSave(false) })
                DropdownMenuItem(text = { Text("Enregistrer une copie") }, onClick = { menu = false; onSave(true) })
            }
        }
    }
}

// ===========================================================================
// Word et PowerPoint : paragraphe par paragraphe
// ===========================================================================

@Composable
private fun ParagraphEditor(doc: Doc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWord = doc.type == DocType.WORD
    var paras by remember { mutableStateOf<List<OfficeEdit.Para>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val texts = remember { mutableStateMapOf<Int, String>() }
    val deleted = remember { mutableStateListOf<Int>() }
    val inserts = remember { mutableStateMapOf<Int, List<String>>() }
    var filter by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { if (isWord) OfficeEdit.loadWord(doc.file).second else OfficeEdit.loadSlides(doc.file).second }
        }.onSuccess { paras = it }.onFailure { error = "Ce fichier ne peut pas être modifié (protégé ou endommagé)." }
    }

    val p = paras
    val edits = p?.let { list -> texts.filter { (i, t) -> list.getOrNull(i)?.text != t } } ?: emptyMap()
    val dirty = edits.isNotEmpty() || deleted.isNotEmpty() || inserts.values.any { l -> l.any { it.isNotBlank() } }
    BackHandler { if (dirty) confirmLeave = true else Nav.pop() }

    fun save(asCopy: Boolean) {
        scope.launch {
            Nav.busy.value = "Enregistrement…"
            val tmp = workFile(context, doc.file.extension.ifEmpty { if (isWord) "docx" else "pptx" })
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (isWord) OfficeEdit.saveWord(doc.file, tmp, edits, deleted.toSet(),
                        inserts.mapValues { e -> e.value.filter { it.isNotBlank() } }.filterValues { it.isNotEmpty() })
                    else OfficeEdit.saveSlides(doc.file, tmp, edits)
                }
            }
            Nav.busy.value = null
            ok.onSuccess { finishSave(context, doc, tmp, asCopy) }.onFailure { Files.toast(context, "L'enregistrement a échoué.") }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1F2B)).imePadding()) {
        EditorBar(
            title = doc.name,
            subtitle = if (isWord) "Modifier le document" else "Modifier les textes des diapos",
            canSave = dirty, onBack = { if (dirty) confirmLeave = true else Nav.pop() }, onSave = ::save
        ) {
            IconButton(onClick = { searching = !searching; if (!searching) filter = "" }) { Icon(Icons.Outlined.Search, "Chercher", tint = TextMain) }
        }
        if (searching) {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Retrouver un passage") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        when {
            error != null -> Text(error!!, color = TextMain, modifier = Modifier.padding(24.dp))
            p == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                val shown = if (filter.isBlank()) p else p.filter { (texts[it.index] ?: it.text).contains(filter.trim(), ignoreCase = true) }
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(
                            if (isWord) "Touchez un paragraphe pour le corriger. La mise en forme (gras, couleurs, styles) est conservée."
                            else "Touchez un texte pour le corriger. La mise en page des diapos est conservée.",
                            color = TextDim, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    var lastWhere = ""
                    shown.forEach { para ->
                        if (!isWord && para.where != lastWhere) {
                            lastWhere = para.where
                            item(key = "h-${para.where}") {
                                Text(para.where, color = Color(0xFF5B9BFF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                        item(key = para.index) {
                            ParagraphCard(
                                label = listOf(para.kind, if (isWord) para.where else "").filter { it.isNotEmpty() }.joinToString(" · "),
                                text = texts[para.index] ?: para.text,
                                changed = (texts[para.index] ?: para.text) != para.text,
                                deleted = para.index in deleted,
                                canStructure = isWord,
                                onChange = { texts[para.index] = it },
                                onDelete = { if (para.index in deleted) deleted.remove(para.index) else deleted.add(para.index) },
                                onInsert = { inserts[para.index] = (inserts[para.index] ?: emptyList()) + "" }
                            )
                        }
                        inserts[para.index]?.forEachIndexed { k, t ->
                            item(key = "ins-${para.index}-$k") {
                                ParagraphCard(
                                    label = "Nouveau paragraphe", text = t, changed = true, deleted = false, canStructure = true, isNew = true,
                                    onChange = { v -> inserts[para.index] = inserts[para.index]!!.toMutableList().also { it[k] = v } },
                                    onDelete = { inserts[para.index] = inserts[para.index]!!.toMutableList().also { it.removeAt(k) } },
                                    onInsert = { inserts[para.index] = inserts[para.index]!!.toMutableList().also { it.add(k + 1, "") } }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog("Quitter sans enregistrer ?", "Vos modifications seront perdues.", "Quitter",
            onDismiss = { confirmLeave = false }, onConfirm = { confirmLeave = false; Nav.pop() })
    }
}

@Composable
private fun ParagraphCard(
    label: String, text: String, changed: Boolean, deleted: Boolean, canStructure: Boolean, isNew: Boolean = false,
    onChange: (String) -> Unit, onDelete: () -> Unit, onInsert: () -> Unit
) {
    val border = when { deleted -> Color(0xFFFF6B6B); changed -> Color(0xFF5B9BFF); else -> Color(0xFFDADFE8) }
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).border(1.5.dp, border, RoundedCornerShape(10.dp)).padding(10.dp)) {
        if (label.isNotEmpty() || canStructure) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color(0xFF6B7280), fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (canStructure) {
                    IconButton(onClick = onInsert, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.Add, "Ajouter un paragraphe après", tint = Color(0xFF2F6FE0)) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        if (deleted) Icon(Icons.AutoMirrored.Outlined.Undo, "Rétablir", tint = Color(0xFFD62828))
                        else Icon(if (isNew) Icons.Outlined.Close else Icons.Outlined.DeleteOutline, "Supprimer", tint = Color(0xFF6B7280))
                    }
                }
            }
        }
        BasicTextField(
            value = text, onValueChange = onChange, enabled = !deleted,
            textStyle = TextStyle(
                color = Color(0xFF1C1F26), fontSize = 16.sp, lineHeight = 23.sp,
                textDecoration = if (deleted) TextDecoration.LineThrough else TextDecoration.None
            ),
            cursorBrush = SolidColor(Color(0xFF2F6FE0)),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(if (isNew) "Tapez le nouveau texte" else "(paragraphe vide)", color = Color(0xFFA0A6B2), fontSize = 16.sp)
                inner()
            }
        )
    }
}

// ===========================================================================
// Excel : grille de cellules
// ===========================================================================

private const val CELL_W = 104
private const val HEAD_W = 44

@Composable
private fun ExcelEditor(doc: Doc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wb by remember { mutableStateOf<OfficeEdit.Workbook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheetIndex by remember { mutableIntStateOf(0) }
    val cellsBySheet = remember { mutableStateMapOf<Int, Map<String, OfficeEdit.Cell>>() }
    val changes = remember { mutableStateMapOf<Int, Map<String, String>>() }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { OfficeEdit.loadWorkbook(doc.file) } }
            .onSuccess { wb = it }.onFailure { error = "Ce classeur ne peut pas être modifié (protégé ou endommagé)." }
    }
    LaunchedEffect(wb, sheetIndex) {
        val w = wb ?: return@LaunchedEffect
        if (sheetIndex !in cellsBySheet && sheetIndex in w.sheets.indices) {
            cellsBySheet[sheetIndex] = withContext(Dispatchers.IO) { runCatching { OfficeEdit.readSheet(doc.file, w, w.sheets[sheetIndex]) }.getOrDefault(mutableMapOf()) }
        }
    }
    val dirty = changes.values.any { it.isNotEmpty() }
    BackHandler { if (dirty) confirmLeave = true else Nav.pop() }

    fun save(asCopy: Boolean) {
        val w = wb ?: return
        scope.launch {
            Nav.busy.value = "Enregistrement…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var current = doc.file
                    var tmp: File? = null
                    for ((si, ch) in changes) {
                        if (ch.isEmpty()) continue
                        val next = workFile(context, "xlsx")
                        OfficeEdit.saveSheet(current, next, w.sheets[si], ch, cellsBySheet[si] ?: emptyMap())
                        if (tmp != null) tmp.delete()
                        tmp = next; current = next
                    }
                    tmp ?: error("rien")
                }
            }
            Nav.busy.value = null
            result.onSuccess { finishSave(context, doc, it, asCopy) }.onFailure { Files.toast(context, "L'enregistrement a échoué.") }
        }
    }

    val w = wb
    Column(Modifier.fillMaxSize().background(Color(0xFF1A1F2B))) {
        EditorBar(doc.name, "Touchez une cellule pour la modifier", dirty, onBack = { if (dirty) confirmLeave = true else Nav.pop() }, onSave = ::save)
        when {
            error != null -> Text(error!!, color = TextMain, modifier = Modifier.padding(24.dp))
            w == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                if (w.sheets.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        w.sheets.forEachIndexed { i, s -> FilterChip(selected = i == sheetIndex, onClick = { sheetIndex = i }, label = { Text(s.name) }) }
                    }
                }
                val cells = cellsBySheet[sheetIndex]
                if (cells == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    val ch = changes[sheetIndex] ?: emptyMap()
                    val refs = (cells.keys + ch.keys).mapNotNull { OfficeEdit.parseRef(it) }
                    val maxCol = ((refs.maxOfOrNull { it.first } ?: 0) + 3).coerceIn(6, 80)
                    val maxRow = ((refs.maxOfOrNull { it.second } ?: 0) + 30).coerceIn(40, 3000)
                    Grid(cells, ch, maxCol, maxRow, hScroll) { editing = it }
                }
            }
        }
    }

    editing?.let { r ->
        val cells = cellsBySheet[sheetIndex] ?: emptyMap()
        val cell = cells[r]
        val pending = changes[sheetIndex]?.get(r)
        val initial = pending ?: cell?.formula?.let { "=" + Formula.toFrench(it) } ?: cell?.value.orEmpty()
        CellDialog(r, initial, onDismiss = { editing = null }) { v ->
            changes[sheetIndex] = (changes[sheetIndex] ?: emptyMap()) + (r to v)
            editing = null
        }
    }

    if (confirmLeave) {
        ConfirmDialog("Quitter sans enregistrer ?", "Vos modifications seront perdues.", "Quitter",
            onDismiss = { confirmLeave = false }, onConfirm = { confirmLeave = false; Nav.pop() })
    }
}

@Composable
private fun Grid(cells: Map<String, OfficeEdit.Cell>, ch: Map<String, String>, maxCol: Int, maxRow: Int, hScroll: ScrollState, onTap: (String) -> Unit) {
    val headBg = Color(0xFFE8EBF1)
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row {
            Box(Modifier.width(HEAD_W.dp).height(30.dp).background(headBg))
            Row(Modifier.horizontalScroll(hScroll)) {
                for (c in 0..maxCol) {
                    Box(Modifier.width(CELL_W.dp).height(30.dp).background(headBg).border(0.5.dp, Color(0xFFD0D5DE)), contentAlignment = Alignment.Center) {
                        Text(OfficeEdit.ref(c, 1).dropLast(1), color = Color(0xFF4B5563), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        LazyColumn {
            items((1..maxRow).toList(), key = { it }) { row ->
                Row {
                    Box(Modifier.width(HEAD_W.dp).height(36.dp).background(headBg).border(0.5.dp, Color(0xFFD0D5DE)), contentAlignment = Alignment.Center) {
                        Text("$row", color = Color(0xFF6B7280), fontSize = 12.sp)
                    }
                    Row(Modifier.horizontalScroll(hScroll)) {
                        for (c in 0..maxCol) {
                            val r = OfficeEdit.ref(c, row)
                            val pending = ch[r]
                            val cell = cells[r]
                            val shown = pending ?: cell?.value?.let { v -> OfficeEdit.parseNumber(v)?.let { n -> fmt(n) } ?: v } ?: ""
                            val numeric = (pending ?: cell?.value)?.let { OfficeEdit.parseNumber(it) } != null
                            Box(
                                Modifier.width(CELL_W.dp).height(36.dp)
                                    .background(if (pending != null) Color(0xFFE3EEFF) else Color.White)
                                    .border(0.5.dp, Color(0xFFDDE1E8))
                                    .clickable { onTap(r) }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Text(shown, color = Color(0xFF1C1F26), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                                    fontWeight = if (cell?.formula != null) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(n: Double): String =
    if (n == Math.floor(n) && kotlin.math.abs(n) < 1e15) String.format(java.util.Locale.FRANCE, "%,d", n.toLong()).replace(' ', ' ').replace(' ', ' ')
    else String.format(java.util.Locale.FRANCE, "%,.4f", n).trimEnd('0').trimEnd(',').replace(' ', ' ').replace(' ', ' ')

@Composable
private fun CellDialog(ref: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cellule $ref") },
        text = {
            Column {
                OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = false, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Un nombre (1 250,50), un texte, ou une formule qui commence par = : =SOMME(A1:A5), =MOYENNE(B2;B9), =A1*12.",
                    color = TextDim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text("Valider") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm("") }) { Text("Effacer") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
        containerColor = Surface1
    )
}
