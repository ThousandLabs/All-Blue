package com.thousandlabs.allblue.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Point en coordonnées relatives à la page affichée : (0,0) en haut à gauche, (1,1) en bas à droite. */
data class P(val x: Float, val y: Float)

sealed interface Mark
/** Trait de stylo ou de surligneur. [width] est relatif à la largeur de la page. */
data class StrokeMark(val points: List<P>, val color: Int, val width: Float, val highlighter: Boolean) : Mark
/** Texte posé sur la page ; [pos] est le coin haut-gauche, [size] relatif à la largeur de la page. */
data class TextMark(val pos: P, val text: String, val size: Float, val color: Int) : Mark
/** Image (signature) ; [pos] coin haut-gauche, [w] largeur relative, [ratio] hauteur / largeur de l'image. */
data class ImageMark(val pos: P, val w: Float, val ratio: Float, val bitmap: Bitmap) : Mark
/** Rectangle opaque : masquage définitif d'une information. */
data class BoxMark(val a: P, val b: P, val color: Int) : Mark

/** Entrée du sommaire. */
data class OutlineEntry(val title: String, val page: Int, val depth: Int)

/** Modifications et lecture du contenu des PDF (moteur PDFBox, licence Apache). */
object PdfTools {

    private fun load(file: File, password: String? = null): PDDocument =
        if (password == null) PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        else PDDocument.load(file, password, MemoryUsageSetting.setupTempFileOnly())

    fun isEncrypted(file: File): Boolean = runCatching { load(file).use { it.isEncrypted } }
        .getOrElse { it is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException }

    // ---------- Texte ----------

    /** Texte de chaque page (pour la recherche, le mode texte et la lecture à voix haute). */
    fun pagesText(file: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<String> = load(file).use { doc ->
        val n = doc.numberOfPages
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        List(n) { i ->
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            onProgress(i + 1, n)
            runCatching { stripper.getText(doc) }.getOrDefault("")
        }
    }

    fun outline(file: File): List<OutlineEntry> = runCatching {
        load(file).use { doc ->
            val out = ArrayList<OutlineEntry>()
            val root = doc.documentCatalog.documentOutline ?: return@use out
            fun walk(item: PDOutlineItem, depth: Int) {
                val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                if (page >= 0) out.add(OutlineEntry(item.title ?: "Sans titre", page, depth))
                item.children().forEach { walk(it, depth + 1) }
                if (out.size > 2000) return
            }
            root.children().forEach { walk(it, 0) }
            out
        }
    }.getOrDefault(emptyList())

    // ---------- Pages ----------

    /**
     * Reconstruit le document : [order] liste les pages d'origine (index à partir de 0) dans le nouvel ordre,
     * avec une rotation supplémentaire en degrés. Les pages absentes sont supprimées.
     */
    fun rebuild(src: File, order: List<Pair<Int, Int>>, out: File) {
        load(src).use { doc ->
            val result = PDDocument()
            try {
                for ((index, extra) in order) {
                    val page = doc.getPage(index)
                    val imported = result.importPage(page)
                    imported.rotation = ((page.rotation + extra) % 360 + 360) % 360
                }
                result.save(out)
            } finally {
                result.close()
            }
        }
    }

    fun merge(files: List<File>, out: File) {
        val merger = PDFMergerUtility()
        files.forEach { merger.addSource(it) }
        merger.destinationFileName = out.absolutePath
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
    }

    // ---------- Mot de passe ----------

    fun removePassword(src: File, password: String, out: File) {
        load(src, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    fun addPassword(src: File, password: String, out: File) {
        load(src).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(out)
        }
    }

    // ---------- Images ----------

    /** Charge une image en la réduisant (et en la remettant à l'endroit d'après la photo). */
    fun loadImage(file: File, maxSide: Int = 2200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation == 0f && scale >= 1f) return bmp
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray {
        val opaque = if (bmp.hasAlpha()) {
            Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                val c = android.graphics.Canvas(it); c.drawColor(Color.WHITE); c.drawBitmap(bmp, 0f, 0f, null)
            }
        } else bmp
        return ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    /** Ajoute une page contenant [bmp]. [fitA4] : page A4 avec marges, sinon page à la taille de l'image. */
    private fun addImagePage(doc: PDDocument, bmp: Bitmap, quality: Int, fitA4: Boolean, pageW: Float? = null, pageH: Float? = null) {
        val img = JPEGFactory.createFromStream(doc, ByteArrayInputStream(jpegBytes(bmp, quality)))
        val landscape = bmp.width > bmp.height
        val rect = when {
            pageW != null && pageH != null -> PDRectangle(pageW, pageH)
            fitA4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
            else -> PDRectangle(bmp.width * 0.75f, bmp.height * 0.75f)
        }
        val page = PDPage(rect)
        doc.addPage(page)
        val margin = if (fitA4 && pageW == null) 18f else 0f
        val availW = rect.width - 2 * margin
        val availH = rect.height - 2 * margin
        val s = minOf(availW / bmp.width, availH / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, (rect.width - w) / 2, (rect.height - h) / 2, w, h)
        }
    }

    fun imagesToPdf(images: List<File>, out: File, fitA4: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        PDDocument().use { doc ->
            images.forEachIndexed { i, f ->
                onProgress(i + 1, images.size)
                val bmp = loadImage(f) ?: return@forEachIndexed
                addImagePage(doc, bmp, 85, fitA4)
                bmp.recycle()
            }
            if (doc.numberOfPages == 0) error("Aucune image lisible")
            doc.save(out)
        }
    }

    /**
     * Compression forte : chaque page est transformée en image JPEG. Le fichier devient beaucoup plus léger,
     * mais le texte n'est plus sélectionnable. Idéal pour les documents scannés à envoyer sur WhatsApp.
     */
    suspend fun compress(engine: PdfEngine, out: File, widthPx: Int, quality: Int, onProgress: (Int, Int) -> Unit) {
        PDDocument().use { doc ->
            for (i in 0 until engine.pageCount) {
                onProgress(i + 1, engine.pageCount)
                val bmp = engine.renderForExport(i, widthPx) ?: continue
                val (pw, ph) = engine.pageSizes[i]
                addImagePage(doc, bmp, quality, fitA4 = false, pageW = pw.toFloat(), pageH = ph.toFloat())
                bmp.recycle()
            }
            doc.save(out)
        }
    }

    // ---------- Annotations ----------

    /**
     * Grave les annotations dans les pages (elles font ensuite partie du document,
     * visibles dans tous les lecteurs). [marks] : index de page → annotations.
     */
    fun applyMarks(src: File, marks: Map<Int, List<Mark>>, out: File) {
        load(src).use { doc ->
            for ((index, list) in marks) {
                if (list.isEmpty() || index !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(index)
                val box = page.cropBox ?: page.mediaBox
                val rot = ((page.rotation % 360) + 360) % 360
                // Taille de la page telle qu'elle est affichée
                val dispW = if (rot == 90 || rot == 270) box.height else box.width

                fun map(p: P): Pair<Float, Float> {
                    val (u, v) = when (rot) {
                        90 -> p.y to 1f - p.x
                        180 -> 1f - p.x to 1f - p.y
                        270 -> 1f - p.y to p.x
                        else -> p.x to p.y
                    }
                    return box.lowerLeftX + u * box.width to box.lowerLeftY + (1f - v) * box.height
                }

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (m in list) when (m) {
                        is StrokeMark -> {
                            if (m.points.size < 2) continue
                            cs.saveGraphicsState()
                            if (m.highlighter) {
                                val gs = PDExtendedGraphicsState().apply { strokingAlphaConstant = 0.35f; nonStrokingAlphaConstant = 0.35f }
                                cs.setGraphicsStateParameters(gs)
                            }
                            cs.setStrokingColor(Color.red(m.color) / 255f, Color.green(m.color) / 255f, Color.blue(m.color) / 255f)
                            cs.setLineWidth(m.width * dispW)
                            cs.setLineCapStyle(if (m.highlighter) 2 else 1)
                            cs.setLineJoinStyle(1)
                            val (x0, y0) = map(m.points[0])
                            cs.moveTo(x0, y0)
                            for (k in 1 until m.points.size) {
                                val (x, y) = map(m.points[k]); cs.lineTo(x, y)
                            }
                            cs.stroke()
                            cs.restoreGraphicsState()
                        }
                        is TextMark -> {
                            val font = PDType1Font.HELVETICA
                            val size = m.size * dispW
                            cs.setNonStrokingColor(Color.red(m.color) / 255f, Color.green(m.color) / 255f, Color.blue(m.color) / 255f)
                            m.text.split('\n').forEachIndexed { line, raw ->
                                val txt = encodable(font, raw)
                                if (txt.isBlank()) return@forEachIndexed
                                val top = P(m.pos.x, m.pos.y + (line + 0.8f) * m.size * 1.25f * dispW / displayHeight(box, rot))
                                val (x, y) = map(top)
                                cs.beginText()
                                cs.setFont(font, size)
                                cs.newLineAtOffset(x, y)
                                cs.showText(txt)
                                cs.endText()
                            }
                        }
                        is ImageMark -> {
                            val img = LosslessFactory.createFromImage(doc, m.bitmap)
                            val w = m.w * dispW
                            val h = w * m.ratio
                            val (x, yTop) = map(m.pos)
                            if (rot == 0) cs.drawImage(img, x, yTop - h, w, h)
                            else cs.drawImage(img, x, yTop - h, w, h) // pages tournées : posée sans rotation
                        }
                        is BoxMark -> {
                            val (x1, y1) = map(m.a)
                            val (x2, y2) = map(m.b)
                            cs.setNonStrokingColor(Color.red(m.color) / 255f, Color.green(m.color) / 255f, Color.blue(m.color) / 255f)
                            cs.addRect(minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
                            cs.fill()
                        }
                    }
                }
            }
            doc.save(out)
        }
    }

    private fun displayHeight(box: PDRectangle, rot: Int) = if (rot == 90 || rot == 270) box.width else box.height

    /** Retire les caractères que la police standard ne sait pas écrire (émojis, etc.). */
    private fun encodable(font: PDType1Font, s: String): String {
        val cleaned = s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace('–', '-').replace('—', '-').replace(' ', ' ').replace('\t', ' ')
        val sb = StringBuilder()
        for (ch in cleaned) {
            if (runCatching { font.encode(ch.toString()) }.isSuccess) sb.append(ch)
        }
        return sb.toString()
    }
}
