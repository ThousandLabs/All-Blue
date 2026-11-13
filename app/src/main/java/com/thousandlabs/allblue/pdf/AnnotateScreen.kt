package com.thousandlabs.allblue.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.ui.ConfirmDialog
import com.thousandlabs.allblue.ui.InputDialog
import com.thousandlabs.allblue.ui.Surface1
import com.thousandlabs.allblue.ui.TextDim
import com.thousandlabs.allblue.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Tool(val label: String) { PEN("Stylo"), HIGHLIGHT("Surligneur"), TEXT("Texte"), SIGN("Signature"), MASK("Masquer") }

private val inkColors = listOf(
    Color(0xFF111111) to "Noir", Color(0xFF1E5BD8) to "Bleu", Color(0xFFD62828) to "Rouge", Color(0xFF138A4B) to "Vert"
)
private val highlightColors = listOf(
    Color(0xFFFFE14D) to "Jaune", Color(0xFF7CF29A) to "Vert", Color(0xFF7FD3FF) to "Bleu", Color(0xFFFF9ED2) to "Rose"
)

fun signatureFile(context: android.content.Context) = File(context.filesDir, "signature.png")

@Composable
fun AnnotateScreen(doc: Doc, startPage: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<PdfEngine?>(null) }
    LaunchedEffect(Unit) { engine = runCatching { PdfEngine.open(doc.file) }.getOrNull() }
    DisposableEffect(engine) { val e = engine; onDispose { e?.close() } }

    var page by remember { mutableIntStateOf(startPage) }
    val marks = remember { HashMap<Int, SnapshotStateList<Mark>>() }
    fun pageMarks(p: Int) = marks.getOrPut(p) { mutableStateListOf() }
    val total = marks.values.sumOf { it.size }

    var tool by remember { mutableStateOf(Tool.PEN) }
    var inkColor by remember { mutableStateOf(inkColors[1].first) }
    var hlColor by remember { mutableStateOf(highlightColors[0].first) }
    var thickness by remember { mutableFloatStateOf(0.5f) }
    var showColors by remember { mutableStateOf(false) }
    var textAt by remember { mutableStateOf<P?>(null) }
    var showSignPad by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }

    var signature by remember { mutableStateOf(signatureFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }) }

    androidx.activity.compose.BackHandler { if (total > 0) confirmLeave = true else Nav.pop() }

    fun save(asCopy: Boolean) {
        scope.launch {
            Nav.busy.value = "Enregistrement…"
            val tmp = File(File(context.cacheDir, "travail").apply { mkdirs() }, "annot_${System.currentTimeMillis()}.pdf")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfTools.applyMarks(doc.file, marks.mapValues { it.value.toList() }, tmp)
                    if (asCopy) {
                        val out = Files.siblingName(doc.file, "annoté")
                        tmp.copyTo(out, overwrite = true); tmp.delete()
                        Doc(out, out.name)
                    } else Files.replaceDocument(context, doc, tmp)
                }
            }
            Nav.busy.value = null
            result.onSuccess { d ->
                engine?.close(); engine = null
                Files.toast(context, if (asCopy) "Enregistré : ${d.name}" else "Annotations enregistrées")
                com.thousandlabs.allblue.data.Prefs.addRecent(d.file.absolutePath)
                Nav.reopen(d)
            }.onFailure { Files.toast(context, "L'enregistrement a échoué.") }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1F2B))) {
        // ---------- Barre du haut ----------
        Row(
            Modifier.fillMaxWidth().background(Color(0xF0101626)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (total > 0) confirmLeave = true else Nav.pop() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain)
            }
            Text("Annoter", color = TextMain, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { pageMarks(page).removeLastOrNull() }, enabled = pageMarks(page).isNotEmpty()) {
                Icon(Icons.AutoMirrored.Outlined.Undo, "Annuler", tint = if (pageMarks(page).isNotEmpty()) TextMain else TextDim)
            }
            Box {
                Button(onClick = { saveMenu = true }, enabled = total > 0) { Text("Enregistrer") }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("Enregistrer dans ce fichier") }, onClick = { saveMenu = false; save(false) })
                    DropdownMenuItem(text = { Text("Enregistrer une copie") }, onClick = { saveMenu = false; save(true) })
                }
            }
        }

        // ---------- Page ----------
        val eng = engine
        Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            if (eng != null) {
                val (pw, ph) = eng.pageSizes[page]
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val ratio = pw.toFloat() / ph
                    val fitW = minOf(maxWidth.value, maxHeight.value * ratio)
                    var bmp by remember(page) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(page) { bmp = eng.render(page, (constraints.maxWidth * 1.6f).toInt().coerceAtMost(2400)) }
                    Box(Modifier.width(fitW.dp).aspectRatio(ratio).background(Color.White)) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        DrawingLayer(
                            marks = pageMarks(page),
                            tool = tool,
                            color = if (tool == Tool.HIGHLIGHT) hlColor else if (tool == Tool.MASK) Color.Black else inkColor,
                            width = if (tool == Tool.HIGHLIGHT) 0.012f + thickness * 0.03f else 0.0015f + thickness * 0.006f,
                            signature = signature,
                            onTextAt = { textAt = it },
                            onNeedSignature = { showSignPad = true }
                        )
                    }
                }
            }
        }

        // ---------- Outils ----------
        Column(Modifier.fillMaxWidth().background(Color(0xF0101626)).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (eng != null && eng.pageCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, "Page précédente", tint = TextMain) }
                    Text("Page ${page + 1} sur ${eng.pageCount}", color = TextDim, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { if (page < eng.pageCount - 1) page++ }, enabled = page < eng.pageCount - 1) { Icon(Icons.Outlined.ChevronRight, "Page suivante", tint = TextMain) }
                }
            }
            if (tool == Tool.PEN || tool == Tool.HIGHLIGHT) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Épaisseur", color = TextDim, fontSize = 12.sp)
                    Slider(value = thickness, onValueChange = { thickness = it }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    val c = if (tool == Tool.HIGHLIGHT) hlColor else inkColor
                    Box {
                        Box(Modifier.size(30.dp).background(c, CircleShape).border(2.dp, Color.White, CircleShape).clickable { showColors = true })
                        DropdownMenu(expanded = showColors, onDismissRequest = { showColors = false }) {
                            (if (tool == Tool.HIGHLIGHT) highlightColors else inkColors).forEach { (col, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    leadingIcon = { Box(Modifier.size(20.dp).background(col, CircleShape)) },
                                    onClick = { if (tool == Tool.HIGHLIGHT) hlColor = col else inkColor = col; showColors = false }
                                )
                            }
                        }
                    }
                }
            }
            if (tool == Tool.SIGN && signature != null) {
                TextButton(onClick = { showSignPad = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Refaire ma signature") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tool.entries.forEach { t ->
                    val icon = when (t) {
                        Tool.PEN -> Icons.Outlined.Edit
                        Tool.HIGHLIGHT -> Icons.Outlined.BorderColor
                        Tool.TEXT -> Icons.Outlined.TextFields
                        Tool.SIGN -> Icons.Outlined.Gesture
                        Tool.MASK -> Icons.Outlined.HideSource
                    }
                    val sel = t == tool
                    Column(
                        Modifier.background(if (sel) Color(0x335B9BFF) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable {
                                tool = t
                                if (t == Tool.SIGN && signature == null) showSignPad = true
                                if (t == Tool.MASK) Files.toast(context, "Tracez un rectangle sur l'information à cacher définitivement.")
                            }.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, t.label, tint = if (sel) Color(0xFF5B9BFF) else TextMain)
                        Text(t.label, color = if (sel) Color(0xFF5B9BFF) else TextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    textAt?.let { pos ->
        InputDialog(
            title = "Ajouter du texte",
            label = "Texte",
            singleLine = false,
            confirm = "Ajouter",
            onDismiss = { textAt = null },
            onConfirm = { t ->
                pageMarks(page).add(TextMark(pos, t, 0.012f + thickness * 0.03f, inkColor.toArgb()))
                textAt = null
            }
        )
    }

    if (showSignPad) {
        SignaturePad(onDismiss = { showSignPad = false }, onSaved = { b ->
            signature = b
            showSignPad = false
            runCatching { signatureFile(context).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            Files.toast(context, "Signature enregistrée. Touchez la page pour la placer.")
        })
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Quitter sans enregistrer ?",
            message = "Vos annotations seront perdues.",
            confirm = "Quitter",
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; Nav.pop() }
        )
    }
}

@Composable
private fun DrawingLayer(
    marks: SnapshotStateList<Mark>,
    tool: Tool,
    color: Color,
    width: Float,
    signature: Bitmap?,
    onTextAt: (P) -> Unit,
    onNeedSignature: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val current = remember { mutableStateListOf<P>() }
    var boxStart by remember { mutableStateOf<P?>(null) }
    var boxEnd by remember { mutableStateOf<P?>(null) }
    fun norm(o: Offset) = P((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))

    val gestures = when (tool) {
        Tool.PEN, Tool.HIGHLIGHT -> Modifier.pointerInput(tool, color, width) {
            detectDragGestures(
                onDragStart = { current.clear(); current.add(norm(it)) },
                onDrag = { change, _ -> change.consume(); current.add(norm(change.position)) },
                onDragEnd = {
                    if (current.size > 1) {
                        // Surligneur : trait horizontal bien droit, comme un vrai surligneur
                        val pts = if (tool == Tool.HIGHLIGHT && kotlin.math.abs(current.last().y - current.first().y) < 0.03f) {
                            val y = current.map { it.y }.average().toFloat()
                            listOf(P(current.first().x, y), P(current.last().x, y))
                        } else current.toList()
                        marks.add(StrokeMark(pts, color.toArgb(), width, tool == Tool.HIGHLIGHT))
                    }
                    current.clear()
                },
                onDragCancel = { current.clear() }
            )
        }
        Tool.MASK -> Modifier.pointerInput(tool) {
            detectDragGestures(
                onDragStart = { boxStart = norm(it); boxEnd = norm(it) },
                onDrag = { change, _ -> change.consume(); boxEnd = norm(change.position) },
                onDragEnd = {
                    val a = boxStart; val b = boxEnd
                    if (a != null && b != null && kotlin.math.abs(a.x - b.x) > 0.01f && kotlin.math.abs(a.y - b.y) > 0.005f) {
                        marks.add(BoxMark(a, b, android.graphics.Color.BLACK))
                    }
                    boxStart = null; boxEnd = null
                }
            )
        }
        Tool.TEXT -> Modifier.pointerInput(tool) { detectTapGestures { onTextAt(norm(it)) } }
        Tool.SIGN -> Modifier.pointerInput(tool, signature) {
            detectTapGestures { o ->
                val s = signature
                if (s == null) onNeedSignature()
                else {
                    val w = 0.32f
                    val ratio = s.height.toFloat() / s.width
                    val p = norm(o)
                    // la signature est centrée sur le doigt
                    val hNorm = w * ratio * size.width / size.height
                    marks.add(ImageMark(P((p.x - w / 2).coerceIn(0f, 1f - w), (p.y - hNorm / 2).coerceAtLeast(0f)), w, ratio, s))
                }
            }
        }
    }

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
            .then(gestures)
    ) {
        marks.forEach { drawMark(it) }
        if (current.size > 1) drawPoints(current, color, width, tool == Tool.HIGHLIGHT)
        val a = boxStart; val b = boxEnd
        if (a != null && b != null) {
            drawRect(Color.Black.copy(alpha = 0.6f), Offset(minOf(a.x, b.x) * this.size.width, minOf(a.y, b.y) * this.size.height),
                Size(kotlin.math.abs(a.x - b.x) * this.size.width, kotlin.math.abs(a.y - b.y) * this.size.height))
        }
    }
}

private fun DrawScope.drawPoints(points: List<P>, color: Color, width: Float, highlighter: Boolean) {
    val path = Path()
    points.forEachIndexed { i, p ->
        val x = p.x * size.width; val y = p.y * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path, if (highlighter) color.copy(alpha = 0.4f) else color,
        style = Stroke(width = width * size.width, cap = if (highlighter) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawMark(m: Mark) {
    when (m) {
        is StrokeMark -> drawPoints(m.points, Color(m.color), m.width, m.highlighter)
        is TextMark -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = m.color
                textSize = m.size * size.width
            }
            m.text.split('\n').forEachIndexed { i, line ->
                drawContext.canvas.nativeCanvas.drawText(
                    line, m.pos.x * size.width, m.pos.y * size.height + (i + 0.8f) * paint.textSize * 1.25f, paint
                )
            }
        }
        is ImageMark -> {
            val w = m.w * size.width
            val h = w * m.ratio
            drawImage(
                m.bitmap.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset((m.pos.x * size.width).toInt(), (m.pos.y * size.height).toInt()),
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            )
        }
        is BoxMark -> drawRect(
            Color(m.color),
            Offset(minOf(m.a.x, m.b.x) * size.width, minOf(m.a.y, m.b.y) * size.height),
            Size(kotlin.math.abs(m.a.x - m.b.x) * size.width, kotlin.math.abs(m.a.y - m.b.y) * size.height)
        )
    }
}

/** Zone pour dessiner sa signature une fois ; elle est ensuite réutilisable sur tous les documents. */
@Composable
private fun SignaturePad(onDismiss: () -> Unit, onSaved: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var padSize by remember { mutableStateOf(IntSize(1, 1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ma signature") },
        text = {
            Column {
                Text("Signez avec le doigt dans le cadre.", color = TextDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(10.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current.clear(); current.add(it) },
                                onDrag = { c, _ -> c.consume(); current.add(c.position) },
                                onDragEnd = { if (current.size > 1) strokes.add(current.toList()); current.clear() }
                            )
                        }
                ) {
                    (strokes + listOf(current.toList())).forEach { s ->
                        if (s.size < 2) return@forEach
                        val path = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, Color(0xFF0B1D51), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val all = strokes.flatten()
                val pad = 12f
                val minX = (all.minOf { it.x } - pad).coerceAtLeast(0f)
                val minY = (all.minOf { it.y } - pad).coerceAtLeast(0f)
                val maxX = (all.maxOf { it.x } + pad).coerceAtMost(padSize.width.toFloat())
                val maxY = (all.maxOf { it.y } + pad).coerceAtMost(padSize.height.toFloat())
                val w = (maxX - minX).toInt().coerceAtLeast(10)
                val h = (maxY - minY).toInt().coerceAtLeast(10)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF0B1D51.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                strokes.forEach { s ->
                    val p = android.graphics.Path()
                    p.moveTo(s[0].x - minX, s[0].y - minY)
                    s.drop(1).forEach { p.lineTo(it.x - minX, it.y - minY) }
                    c.drawPath(p, paint)
                }
                onSaved(bmp)
            }) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text("Effacer") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        }
    )
}
