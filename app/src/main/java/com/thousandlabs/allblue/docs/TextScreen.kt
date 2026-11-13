package com.thousandlabs.allblue.docs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thousandlabs.allblue.Nav
import com.thousandlabs.allblue.Screen
import com.thousandlabs.allblue.data.Doc
import com.thousandlabs.allblue.data.Files
import com.thousandlabs.allblue.pdf.PdfTools
import com.thousandlabs.allblue.ui.ConfirmDialog
import com.thousandlabs.allblue.ui.TextDim
import com.thousandlabs.allblue.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val EDIT_LIMIT = 400 * 1024

private fun readText(f: File): Pair<String, Charset> {
    val bytes = f.readBytes()
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("﻿") to Charsets.UTF_8
    } catch (_: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1) to Charsets.ISO_8859_1
    }
}

/** Fichiers texte : lecture, et modification pour les fichiers de taille raisonnable. */
@Composable
fun TextScreen(doc: Doc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var charset by remember { mutableStateOf(Charsets.UTF_8) }
    var original by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val tooBig = doc.file.length() > EDIT_LIMIT

    LaunchedEffect(Unit) {
        val (t, cs) = withContext(Dispatchers.IO) {
            runCatching { readText(doc.file) }.getOrDefault("Fichier illisible." to Charsets.UTF_8)
        }
        val shown = if (t.length > 2_000_000) t.take(2_000_000) + "\n\n[… fichier trop long, affiché en partie]" else t
        text = shown; original = shown; charset = cs
    }
    val dirty = editing && text != original
    androidx.activity.compose.BackHandler { if (dirty) confirmLeave = true else Nav.pop() }

    Column(Modifier.fillMaxSize().background(Color(0xFFFBFBF8)).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xF0101626)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (dirty) confirmLeave = true else Nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = TextMain) }
            Column(Modifier.weight(1f)) {
                Text(doc.name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(if (editing) "Modification" else "Texte · ${Files.sizeLabel(doc.file.length())}", color = TextDim, style = MaterialTheme.typography.bodySmall)
            }
            if (!tooBig && !editing) IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "Modifier", tint = TextMain) }
            if (editing) IconButton(enabled = dirty, onClick = {
                val t = text ?: return@IconButton
                scope.launch {
                    val tmp = File(File(context.cacheDir, "travail").apply { mkdirs() }, "txt_${System.currentTimeMillis()}")
                    val d = withContext(Dispatchers.IO) { tmp.writeText(t, charset); Files.replaceDocument(context, doc, tmp) }
                    original = t; editing = false
                    Files.toast(context, "Enregistré")
                    if (d.file != doc.file) Nav.reopen(d)
                }
            }) { Icon(Icons.Outlined.Save, "Enregistrer", tint = if (dirty) Color(0xFF5B9BFF) else TextDim) }
            IconButton(onClick = { Files.share(context, listOf(doc.file)) }) { Icon(Icons.Outlined.Share, "Envoyer", tint = TextMain) }
        }
        val t = text
        if (t == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ouverture…", color = Color.Gray) }
        } else if (editing) {
            BasicTextField(
                value = t, onValueChange = { text = it },
                textStyle = TextStyle(color = Color(0xFF1C1F26), fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                cursorBrush = SolidColor(Color(0xFF2F6FE0)),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            )
        } else {
            SelectionContainer {
                Text(
                    t, color = Color(0xFF1C1F26), fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()
                )
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog("Quitter sans enregistrer ?", "Vos modifications seront perdues.", "Quitter",
            onDismiss = { confirmLeave = false }, onConfirm = { confirmLeave = false; Nav.pop() })
    }
}

/** Images : zoom, rotation, conversion en PDF. */
@Composable
fun ImageScreen(doc: Doc) {
    val context = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        bmp = withContext(Dispatchers.IO) { PdfTools.loadImage(doc.file, 3000) ?: BitmapFactory.decodeFile(doc.file.absolutePath) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), doc.name,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                        rotationZ = rotation
                    }
            )
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC000000)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = Color.White) }
            Text(doc.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { rotation = (rotation + 90f) % 360f }) { Icon(Icons.Outlined.RotateRight, "Pivoter", tint = Color.White) }
            IconButton(onClick = { Nav.push(Screen.ImagesToPdf(listOf(doc.file))) }) { Icon(Icons.Outlined.PictureAsPdf, "Convertir en PDF", tint = Color.White) }
            IconButton(onClick = { Files.share(context, listOf(doc.file)) }) { Icon(Icons.Outlined.Share, "Envoyer", tint = Color.White) }
            IconButton(onClick = { Files.openWithOther(context, doc.file) }) { Icon(Icons.Outlined.OpenInNew, "Ouvrir avec", tint = Color.White) }
        }
    }
}
