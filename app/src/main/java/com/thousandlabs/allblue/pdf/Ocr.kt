package com.thousandlabs.allblue.pdf

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ligne reconnue, position relative à la page (0 à 1, origine en haut à gauche). */
data class OcrLine(val text: String, val l: Float, val t: Float, val r: Float, val b: Float)

data class OcrPage(val text: String, val lines: List<OcrLine>)

/**
 * Reconnaissance du texte des documents scannés et des photos, entièrement sur le téléphone.
 * Les résultats des PDF sont gardés pour ne pas refaire le travail.
 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bmp: Bitmap): OcrPage = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                val w = bmp.width.toFloat().coerceAtLeast(1f)
                val h = bmp.height.toFloat().coerceAtLeast(1f)
                val lines = ArrayList<OcrLine>()
                val text = StringBuilder()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        lines.add(OcrLine(line.text, box.left / w, box.top / h, box.right / w, box.bottom / h))
                        text.append(line.text).append('\n')
                    }
                    text.append('\n')
                }
                if (cont.isActive) cont.resume(OcrPage(text.toString().trim(), lines))
            }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    private fun cacheFile(context: Context, pdf: File): File {
        val key = (pdf.absolutePath + ":" + pdf.length() + ":" + pdf.lastModified()).hashCode().toString(16)
        return File(File(context.filesDir, "ocr").apply { mkdirs() }, "$key.json")
    }

    fun cached(context: Context, pdf: File): List<OcrPage>? = runCatching {
        val f = cacheFile(context, pdf)
        if (!f.exists()) return null
        val arr = JSONArray(f.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ls = o.getJSONArray("lines")
            OcrPage(o.getString("text"), List(ls.length()) { k ->
                val l = ls.getJSONArray(k)
                OcrLine(l.getString(0), l.getDouble(1).toFloat(), l.getDouble(2).toFloat(), l.getDouble(3).toFloat(), l.getDouble(4).toFloat())
            })
        }
    }.getOrNull()

    private fun save(context: Context, pdf: File, pages: List<OcrPage>) = runCatching {
        val arr = JSONArray()
        pages.forEach { p ->
            val ls = JSONArray()
            p.lines.forEach { l -> ls.put(JSONArray().put(l.text).put(l.l.toDouble()).put(l.t.toDouble()).put(l.r.toDouble()).put(l.b.toDouble())) }
            arr.put(JSONObject().put("text", p.text).put("lines", ls))
        }
        cacheFile(context, pdf).writeText(arr.toString())
        // on garde au plus 60 documents reconnus
        cacheFile(context, pdf).parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
    }

    /** Reconnaît toutes les pages d'un PDF scanné (environ une à deux secondes par page). */
    suspend fun pdf(context: Context, engine: PdfEngine, pdf: File, onProgress: (Int, Int) -> Unit): List<OcrPage> {
        cached(context, pdf)?.takeIf { it.size == engine.pageCount }?.let { return it }
        val out = ArrayList<OcrPage>()
        for (i in 0 until engine.pageCount) {
            onProgress(i + 1, engine.pageCount)
            val bmp = engine.renderForExport(i, 1700)
            out.add(if (bmp == null) OcrPage("", emptyList()) else runCatching { recognize(bmp) }.getOrDefault(OcrPage("", emptyList())).also { bmp.recycle() })
        }
        save(context, pdf, out)
        return out
    }
}
