package com.thousandlabs.allblue.docs

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** Livre EPUB décompressé dans le cache : liste des chapitres dans l'ordre de lecture. */
class EpubBook(val dir: File, val chapters: List<File>, val title: String)

object Epub {
    fun open(file: File, cacheRoot: File): EpubBook {
        val dir = File(cacheRoot, "epub/" + (file.absolutePath + file.lastModified()).hashCode().toString(16))
        val ready = File(dir, ".pret")
        if (!ready.exists()) {
            dir.deleteRecursively(); dir.mkdirs()
            ZipFile(file).use { zip ->
                for (e in zip.entries()) {
                    if (e.isDirectory) continue
                    val out = File(dir, e.name)
                    // sécurité : on refuse les chemins qui sortent du dossier
                    if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            ready.writeText("ok")
        }
        val container = File(dir, "META-INF/container.xml").takeIf { it.exists() }?.readText() ?: error("EPUB illisible")
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: error("EPUB illisible")
        val opf = File(dir, opfPath)
        val base = opf.parentFile ?: dir
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        var title = file.nameWithoutExtension
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(opf.readText().reader())
        }
        var inTitle = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "item" -> {
                        val id = p.getAttributeValue(null, "id"); val href = p.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = java.net.URLDecoder.decode(href, "UTF-8")
                    }
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    "title" -> inTitle = true
                }
                XmlPullParser.TEXT -> if (inTitle && p.text.isNotBlank()) { title = p.text.trim(); inTitle = false }
                XmlPullParser.END_TAG -> if (p.name.substringAfter(':') == "title") inTitle = false
            }
        }
        val chapters = spine.mapNotNull { manifest[it] }.map { File(base, it) }.filter { it.exists() }
        if (chapters.isEmpty()) error("Aucun chapitre lisible")
        return EpubBook(dir, chapters, title)
    }
}
