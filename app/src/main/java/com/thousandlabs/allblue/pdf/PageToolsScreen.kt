package com.thousandlabs.allblue.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.Screen
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.data.Prefs
import com.thousandlabs.allblue.ui.ConfirmDialog
import com.thousandlabs.allblue.ui.TextDim
import com.thousandlabs.allblue.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PageItem(val src: Int, val rotation: Int = 0, val id: Long = src.toLong())

@Composable
fun PageToolsScreen(doc: Doc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<PdfEngine?>(null) }
    LaunchedEffect(Unit) { engine = runCatching { PdfEngine.open(doc.file) }.getOrNull() }
    DisposableEffect(engine) { val e = engine; onDispose { e?.close() } }

    val items = remember { mutableStateListOf<PageItem>() }
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        if (items.isEmpty()) items.addAll(List(e.pageCount) { PageItem(it) })
    }
    val selected = remember { mutableStateListOf<Long>() }
    var changed by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { if (changed) confirmLeave = true else Nav.pop() }

    fun targets(): List<Int> = if (selected.isEmpty()) items.indices.toList() else items.indices.filter { items[it].id in selected }

    fun build(order: List<PageItem>, onDone: (File) -> Unit) {
        scope.launch {
            Nav.busy.value = "Préparation…"
            val tmp = File(File(context.cacheDir, "travail").apply { mkdirs() }, "pages_${System.currentTimeMillis()}.pdf")
            val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.rebuild(doc.file, order.map { it.src to it.rotation }, tmp) }.isSuccess }
            Nav.busy.value = null
            if (ok) onDone(tmp) else Files.toast(context, "L'opération a échoué.")
        }
    }

    fun save(asCopy: Boolean) {
        if (items.isEmpty()) { Files.toast(context, "Le document doit garder au moins une page."); return }
        build(items.toList()) { tmp ->
            scope.launch {
                engine?.close(); engine = null
                val d = withContext(Dispatchers.IO) {
                    if (asCopy) {
                        val out = Files.siblingName(doc.file, "pages modifiées")
                        tmp.copyTo(out, overwrite = true); tmp.delete(); Doc(out, out.name)
                    } else Files.replaceDocument(context, doc, tmp)
                }
                Prefs.addRecent(d.file.absolutePath)
                Files.toast(context, "Enregistré")
                Nav.reopen(d)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1F2B))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xF0101626)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (changed) confirmLeave = true else Nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
            Column(Modifier.weight(1f)) {
                Text("Pages", color = TextMain, fontWeight = FontWeight.Medium)
                Text(
                    if (selected.isEmpty()) "${items.size} pages · touchez pour sélectionner" else "${selected.size} sélectionnée(s)",
                    color = TextDim, fontSize = 12.sp
                )
            }
            IconButton(onClick = {
                if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.id }) }
            }) { Icon(Icons.Outlined.SelectAll, "Tout sélectionner", tint = TextMain) }
            Box {
                Button(onClick = { saveMenu = true }, enabled = changed) { Text("Enregistrer") }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("Enregistrer dans ce fichier") }, onClick = { saveMenu = false; save(false) })
                    DropdownMenuItem(text = { Text("Enregistrer une copie") }, onClick = { saveMenu = false; save(true) })
                }
            }
        }

        val eng = engine
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { pos, item ->
                val isSel = item.id in selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.72f)
                            .border(if (isSel) 3.dp else 1.dp, if (isSel) Color(0xFF5B9BFF) else Color(0xFF34425F), RoundedCornerShape(8.dp))
                            .padding(4.dp)
                            .clickable(onClick = { if (isSel) selected.remove(item.id) else selected.add(item.id) }),
                        contentAlignment = Alignment.Center
                    ) {
                        if (eng != null) Thumb(eng, item.src, item.rotation)
                        if (isSel) Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF5B9BFF),
                            modifier = Modifier.align(Alignment.TopEnd).background(Color.White, CircleShape).size(22.dp))
                    }
                    Text("${pos + 1}", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Color(0xF0101626)).navigationBarsPadding().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Action(Icons.Outlined.RotateRight, "Pivoter") {
                targets().forEach { i -> items[i] = items[i].copy(rotation = (items[i].rotation + 90) % 360) }
                changed = true
            }
            Action(Icons.Outlined.ArrowUpward, "Avancer") {
                val idx = targets().takeIf { selected.isNotEmpty() } ?: return@Action
                for (i in idx) if (i > 0 && items[i - 1].id !in selected) { val t = items[i - 1]; items[i - 1] = items[i]; items[i] = t }
                changed = true
            }
            Action(Icons.Outlined.ArrowDownward, "Reculer") {
                val idx = targets().takeIf { selected.isNotEmpty() } ?: return@Action
                for (i in idx.reversed()) if (i < items.lastIndex && items[i + 1].id !in selected) { val t = items[i + 1]; items[i + 1] = items[i]; items[i] = t }
                changed = true
            }
            Action(Icons.Outlined.ContentCut, "Extraire") {
                if (selected.isEmpty()) { Files.toast(context, "Sélectionnez d'abord les pages à extraire."); return@Action }
                val order = items.filter { it.id in selected }
                build(order) { tmp ->
                    val out = Files.siblingName(doc.file, "extrait")
                    tmp.copyTo(out, overwrite = true); tmp.delete()
                    Prefs.addRecent(out.absolutePath)
                    Files.toast(context, "Nouveau PDF : ${out.name}")
                    Nav.push(Screen.Viewer(Doc(out, out.name)))
                }
            }
            Action(Icons.Outlined.Delete, "Supprimer") {
                if (selected.isEmpty()) { Files.toast(context, "Sélectionnez d'abord les pages à supprimer."); return@Action }
                if (selected.size == items.size) { Files.toast(context, "Impossible de supprimer toutes les pages."); return@Action }
                items.removeAll { it.id in selected }
                selected.clear()
                changed = true
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog("Quitter sans enregistrer ?", "Les changements sur les pages seront perdus.", "Quitter",
            onDismiss = { confirmLeave = false }, onConfirm = { confirmLeave = false; Nav.pop() })
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = TextMain)
        Text(label, color = TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun Thumb(eng: PdfEngine, index: Int, rotation: Int) {
    var bmp by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) { bmp = eng.render(index, 260) }
    bmp?.let {
        Image(it.asImageBitmap(), null, Modifier.fillMaxSize().rotate(rotation.toFloat()), contentScale = ContentScale.Fit)
    }
}
