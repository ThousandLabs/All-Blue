package com.thousandlabs.allblue.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Affichage des pages avec le moteur PDF intégré à Android (PDFium, celui de Chrome) :
 * rapide et léger. Seules les pages visibles sont calculées, et gardées en cache.
 */
class PdfEngine private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Taille de chaque page en points PDF (largeur, hauteur), rotation comprise. */
    val pageSizes: List<Pair<Int, Int>>
) : Closeable {

    val pageCount: Int get() = pageSizes.size
    private val mutex = Mutex()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(index: Int, width: Int): Bitmap? = cache.get("$index@$width")

    /** Calcule l'image d'une page à la largeur [width] en pixels. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$width"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            if (closed) return@withLock null
            cache.get(key)?.let { return@withLock it }
            runCatching {
                renderer.openPage(index).use { page ->
                    val w = width.coerceIn(64, 4096)
                    val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(64, 6000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bmp)
                    bmp
                }
            }.getOrNull()
        }
    }

    /** Image pour l'export (compression, conversion) : non mise en cache. */
    suspend fun renderForExport(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            runCatching {
                renderer.openPage(index).use { page ->
                    val h = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt()
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bmp
                }
            }.getOrNull()
        }
    }

    override fun close() {
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        cache.evictAll()
    }

    companion object {
        /** Ouvre un PDF. Lève [SecurityException] s'il est protégé par un mot de passe. */
        suspend fun open(file: File): PdfEngine = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                pfd.close(); throw e
            }
            val sizes = (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { it.width to it.height }
            }
            PdfEngine(pfd, renderer, sizes)
        }
    }
}
