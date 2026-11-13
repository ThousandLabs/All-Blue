package com.thousandlabs.allblue.docs

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Conversion des fichiers Word, Excel et PowerPoint en pages web affichables, sans connexion.
 * Ces formats sont des archives ZIP contenant du XML : on les lit directement.
 */
object OfficeHtml {

    private fun ZipFile.text(name: String): String? =
        getEntry(name.trimStart('/'))?.let { e -> getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

    private fun ZipFile.bytes(name: String): ByteArray? =
        getEntry(name.trimStart('/'))?.let { e -> getInputStream(e).use { it.readBytes() } }

    private fun parser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(xml.reader())
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Lit un fichier de relations (.rels) : identifiant → chemin complet dans l'archive. */
    private fun rels(zip: ZipFile, relsPath: String, baseDir: String): Map<String, String> {
        val xml = zip.text(relsPath) ?: return emptyMap()
        val p = parser(xml)
        val out = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.attr("Id") ?: continue
                val target = p.attr("Target") ?: continue
                if (p.attr("TargetMode") == "External") continue
                out[id] = resolve(baseDir, target)
            }
        }
        return out
    }

    private fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = (baseDir.trimEnd('/') + "/" + target).split('/').toMutableList()
        val stack = ArrayList<String>()
        for (part in parts) when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            else -> stack.add(part)
        }
        return stack.joinToString("/")
    }

    private fun imageData(zip: ZipFile, path: String): String? {
        val ext = path.substringAfterLast('.').lowercase(Locale.ROOT)
        val mime = when (ext) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
            "bmp" -> "image/bmp"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"
            else -> return null // EMF / WMF : non affichables
        }
        val bytes = zip.bytes(path) ?: return null
        if (bytes.size > 6 * 1024 * 1024) return null
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun on(p: XmlPullParser): Boolean = p.attr("w:val")?.lowercase().let { it == null || (it != "0" && it != "false" && it != "none") }

    // =====================================================================
    // Word
    // =====================================================================

    fun docx(file: File): String = ZipFile(file).use { zip ->
        val xml = zip.text("word/document.xml") ?: error("Document Word illisible")
        val rel = rels(zip, "word/_rels/document.xml.rels", "word")
        val out = StringBuilder()
        val p = parser(xml)
        var para: StringBuilder? = null
        var pTag = "p"; var pAlign: String? = null; var pList = false
        var inPPr = false; var inRPr = false; var inT = false
        var b = false; var ital = false; var u = false; var strike = false
        var color: String? = null; var hl: String? = null
        var emptyRun = 0

        fun styled(text: String): String {
            var s = esc(text)
            if (b) s = "<b>$s</b>"
            if (ital) s = "<i>$s</i>"
            if (u) s = "<u>$s</u>"
            if (strike) s = "<s>$s</s>"
            val css = buildString {
                if (color != null && color != "auto" && color != "000000") append("color:#$color;")
                if (hl != null && hl != "none") append("background:${highlight(hl!!)};")
            }
            return if (css.isNotEmpty()) "<span style=\"$css\">$s</span>" else s
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { para = StringBuilder(); pTag = "p"; pAlign = null; pList = false }
                    "w:pPr" -> inPPr = true
                    "w:pStyle" -> if (inPPr) {
                        val v = (p.attr("w:val") ?: "").lowercase(Locale.ROOT)
                        pTag = when {
                            v == "title" || v == "titre" -> "h1"
                            v.startsWith("heading1") || v.startsWith("titre1") -> "h1"
                            v.startsWith("heading2") || v.startsWith("titre2") -> "h2"
                            v.startsWith("heading") || v.startsWith("titre") -> "h3"
                            v.contains("list") || v.contains("liste") -> { pList = true; "p" }
                            else -> "p"
                        }
                    }
                    "w:jc" -> if (inPPr) pAlign = when (p.attr("w:val")) {
                        "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; else -> null
                    }
                    "w:numPr" -> if (inPPr) pList = true
                    "w:r" -> { b = false; ital = false; u = false; strike = false; color = null; hl = null }
                    "w:rPr" -> if (!inPPr) inRPr = true
                    "w:b" -> if (inRPr) b = on(p)
                    "w:i" -> if (inRPr) ital = on(p)
                    "w:u" -> if (inRPr) u = on(p)
                    "w:strike" -> if (inRPr) strike = on(p)
                    "w:color" -> if (inRPr) color = p.attr("w:val")
                    "w:highlight" -> if (inRPr) hl = p.attr("w:val")
                    "w:t" -> inT = true
                    "w:tab" -> if (!inPPr) para?.append("&emsp;")
                    "w:br" -> if (!inPPr) para?.append(if (p.attr("w:type") == "page") "<hr>" else "<br>")
                    "a:blip" -> p.attr("r:embed")?.let { rel[it] }?.let { imageData(zip, it) }?.let {
                        (para ?: out).append("<img src=\"$it\">")
                    }
                    "v:imagedata" -> p.attr("r:id")?.let { rel[it] }?.let { imageData(zip, it) }?.let {
                        (para ?: out).append("<img src=\"$it\">")
                    }
                    "w:tbl" -> out.append("<div class=\"tw\"><table>")
                    "w:tr" -> out.append("<tr>")
                    "w:tc" -> out.append("<td>")
                }
                XmlPullParser.TEXT -> if (inT) para?.append(styled(p.text))
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inT = false
                    "w:pPr" -> inPPr = false
                    "w:rPr" -> inRPr = false
                    "w:p" -> {
                        val content = para?.toString().orEmpty()
                        para = null
                        if (content.isBlank()) {
                            if (emptyRun++ < 1) out.append("<p class=\"e\">&nbsp;</p>")
                        } else {
                            emptyRun = 0
                            val style = pAlign?.let { " style=\"text-align:$it\"" } ?: ""
                            if (pList) out.append("<p class=\"li\"$style>$content</p>")
                            else out.append("<$pTag$style>$content</$pTag>")
                        }
                    }
                    "w:tc" -> out.append("</td>")
                    "w:tr" -> out.append("</tr>")
                    "w:tbl" -> out.append("</table></div>")
                }
            }
        }
        page(file.nameWithoutExtension, WORD_CSS, "<article>$out</article>")
    }

    private fun highlight(name: String) = when (name) {
        "yellow" -> "#fff176"; "green" -> "#a5d6a7"; "cyan" -> "#80deea"; "magenta" -> "#f48fb1"
        "blue" -> "#90caf9"; "red" -> "#ef9a9a"; "lightGray" -> "#e0e0e0"; "darkGray" -> "#9e9e9e"
        else -> "#fff59d"
    }

    // =====================================================================
    // Excel
    // =====================================================================

    fun xlsx(file: File): String = ZipFile(file).use { zip ->
        val shared = ArrayList<String>()
        zip.text("xl/sharedStrings.xml")?.let { xml ->
            val p = parser(xml)
            var sb: StringBuilder? = null
            var inT = false
            var inRPh = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> sb = StringBuilder()
                        "t" -> inT = true
                        "rPh" -> inRPh = true
                    }
                    XmlPullParser.TEXT -> if (inT && !inRPh) sb?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inT = false
                        "rPh" -> inRPh = false
                        "si" -> { shared.add(sb?.toString().orEmpty()); sb = null }
                    }
                }
            }
        }
        val rel = rels(zip, "xl/_rels/workbook.xml.rels", "xl")
        val sheets = ArrayList<Pair<String, String>>() // nom, chemin
        zip.text("xl/workbook.xml")?.let { xml ->
            val p = parser(xml)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val path = p.attr("r:id")?.let { rel[it] } ?: continue
                    if (p.attr("state") == "hidden" || p.attr("state") == "veryHidden") continue
                    sheets.add((p.attr("name") ?: "Feuille") to path)
                }
            }
        }
        val tabs = StringBuilder()
        val bodies = StringBuilder()
        sheets.forEachIndexed { si, (name, path) ->
            tabs.append("<button onclick=\"show($si)\" id=\"b$si\"${if (si == 0) " class=\"on\"" else ""}>${esc(name)}</button>")
            val xml = zip.text(path)
            bodies.append("<div class=\"sheet\" id=\"s$si\"${if (si == 0) "" else " style=\"display:none\""}>")
            if (xml == null) bodies.append("<p>Feuille illisible.</p>") else bodies.append(sheetTable(xml, shared))
            bodies.append("</div>")
        }
        if (sheets.isEmpty()) bodies.append("<p>Aucune feuille lisible.</p>")
        val script = "<script>function show(i){document.querySelectorAll('.sheet').forEach(function(e,k){e.style.display=k==i?'block':'none'});" +
            "document.querySelectorAll('.tabs button').forEach(function(e,k){e.className=k==i?'on':''});}</script>"
        page(file.nameWithoutExtension, XLSX_CSS, "<div class=\"tabs\">$tabs</div>$bodies$script")
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun formatNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) {
            return String.format(Locale.FRANCE, "%,d", d.toLong()).replace(' ', ' ').replace(' ', ' ')
        }
        val s = String.format(Locale.FRANCE, "%,.6f", d).trimEnd('0').trimEnd(',')
        return s.replace(' ', ' ').replace(' ', ' ')
    }

    private fun sheetTable(xml: String, shared: List<String>): String {
        val rows = java.util.TreeMap<Int, HashMap<Int, Pair<String, Boolean>>>() // valeur, est un nombre
        val p = parser(xml)
        var row = 0
        var col = 0
        var type: String? = null
        var value: StringBuilder? = null
        var inV = false; var inT = false
        var maxCol = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = (p.attr("r")?.toIntOrNull() ?: (row + 1))
                    "c" -> {
                        val ref = p.attr("r")
                        col = if (ref != null) colIndex(ref) else col + 1
                        type = p.attr("t"); value = StringBuilder()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = value?.toString().orEmpty()
                        if (raw.isNotEmpty() && row <= 3000 && col < 80) {
                            val cell = when (type) {
                                "s" -> (shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "") to false
                                "b" -> (if (raw == "1") "VRAI" else "FAUX") to false
                                "inlineStr", "str", "e" -> raw to false
                                else -> formatNumber(raw) to true
                            }
                            rows.getOrPut(row) { HashMap() }[col] = cell
                            if (col > maxCol) maxCol = col
                        }
                        value = null
                    }
                }
            }
        }
        if (rows.isEmpty()) return "<p class=\"empty\">Feuille vide.</p>"
        val sb = StringBuilder("<div class=\"tw\"><table><thead><tr><th></th>")
        for (c in 0..maxCol) sb.append("<th>").append(colName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        var last = 0
        for ((r, cells) in rows) {
            if (r - last > 1 && last > 0) sb.append("<tr class=\"gap\"><td class=\"rn\">…</td></tr>")
            last = r
            sb.append("<tr><td class=\"rn\">").append(r).append("</td>")
            for (c in 0..maxCol) {
                val cell = cells[c]
                if (cell == null) sb.append("<td></td>")
                else sb.append(if (cell.second) "<td class=\"n\">" else "<td>").append(esc(cell.first)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table></div>")
        return sb.toString()
    }

    // =====================================================================
    // PowerPoint
    // =====================================================================

    fun pptx(file: File): String = ZipFile(file).use { zip ->
        val presXml = zip.text("ppt/presentation.xml") ?: error("Présentation illisible")
        val presRel = rels(zip, "ppt/_rels/presentation.xml.rels", "ppt")
        var cx = 12192000L; var cy = 6858000L
        val slidePaths = ArrayList<String>()
        run {
            val p = parser(presXml)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "p:sldSz" -> { cx = p.attr("cx")?.toLongOrNull() ?: cx; cy = p.attr("cy")?.toLongOrNull() ?: cy }
                    "p:sldId" -> p.attr("r:id")?.let { presRel[it] }?.let { slidePaths.add(it) }
                }
            }
        }
        val slideWpt = cx / 12700f
        val sb = StringBuilder()
        slidePaths.forEachIndexed { index, path ->
            val xml = zip.text(path) ?: return@forEachIndexed
            val dir = path.substringBeforeLast('/')
            val rel = rels(zip, "$dir/_rels/${path.substringAfterLast('/')}.rels", dir)
            sb.append("<section class=\"slide\" style=\"aspect-ratio:$cx/$cy\"><div class=\"num\">${index + 1}</div>")
            sb.append(slideBody(zip, xml, rel, cx, cy, slideWpt))
            sb.append("</section>")
        }
        if (slidePaths.isEmpty()) sb.append("<p>Aucune diapositive.</p>")
        val script = "<script>function togglePresent(){document.body.classList.toggle('present');}</script>"
        page(file.nameWithoutExtension, PPTX_CSS, "<main>$sb</main>$script")
    }

    private fun slideBody(zip: ZipFile, xml: String, rel: Map<String, String>, cx: Long, cy: Long, slideWpt: Float): String {
        val out = StringBuilder()
        val p = parser(xml)
        // Forme en cours
        var inShape = false; var isPic = false
        var x = -1L; var y = -1L; var w = -1L; var h = -1L
        var inXfrm = false
        val text = StringBuilder()
        var para: StringBuilder? = null
        var pAlign: String? = null
        var inT = false
        var sz = 0; var b = false; var ital = false; var col: String? = null
        var inRPr = false
        var picSrc: String? = null
        var fill: String? = null
        var inSpPr = false
        var fallbackY = 6
        var shapeDepth = 0

        fun flushShape() {
            val hasPos = x >= 0 && w > 0
            val left: Float; val top: Float; val width: Float; val height: Float
            if (hasPos) {
                left = x * 100f / cx; top = y * 100f / cy; width = w * 100f / cx; height = h * 100f / cy
            } else {
                left = 6f; top = fallbackY.toFloat(); width = 88f; height = 14f; fallbackY = (fallbackY + 16).coerceAtMost(84)
            }
            val pos = "left:${left}%;top:${top}%;width:${width}%;height:${height}%;"
            if (isPic) {
                picSrc?.let { out.append("<img class=\"el\" style=\"$pos\" src=\"$it\">") }
            } else if (text.isNotBlank() || fill != null) {
                val bg = fill?.let { "background:#$it;" } ?: ""
                out.append("<div class=\"el\" style=\"$pos$bg\">$text</div>")
            }
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "p:sp", "p:pic", "p:cxnSp", "p:graphicFrame" -> {
                        shapeDepth++
                        if (shapeDepth == 1) {
                            inShape = true; isPic = p.name == "p:pic"
                            x = -1; y = -1; w = -1; h = -1; text.clear(); picSrc = null; fill = null
                        }
                    }
                    "p:spPr" -> inSpPr = true
                    "a:xfrm", "p:xfrm" -> inXfrm = true
                    "a:off" -> if (inXfrm && inShape && x < 0) { x = p.attr("x")?.toLongOrNull() ?: -1; y = p.attr("y")?.toLongOrNull() ?: -1 }
                    "a:ext" -> if (inXfrm && inShape && w < 0) { w = p.attr("cx")?.toLongOrNull() ?: -1; h = p.attr("cy")?.toLongOrNull() ?: -1 }
                    "a:p" -> { para = StringBuilder(); pAlign = null }
                    "a:pPr" -> pAlign = when (p.attr("algn")) { "ctr" -> "center"; "r" -> "right"; "just" -> "justify"; else -> null }
                    "a:r", "a:fld" -> { sz = 0; b = false; ital = false; col = null }
                    "a:rPr", "a:endParaRPr" -> {
                        inRPr = p.name == "a:rPr"
                        if (inRPr) {
                            sz = p.attr("sz")?.toIntOrNull() ?: 0
                            b = p.attr("b") == "1"; ital = p.attr("i") == "1"
                        }
                    }
                    "a:srgbClr" -> {
                        if (inRPr) col = p.attr("val")
                        else if (inSpPr && fill == null && inShape) fill = p.attr("val")
                    }
                    "a:t" -> inT = true
                    "a:br" -> para?.append("<br>")
                    "a:blip" -> if (inShape) p.attr("r:embed")?.let { rel[it] }?.let { imageData(zip, it) }?.let { picSrc = it; isPic = true }
                    "a:ln" -> if (inSpPr) inSpPr = false // la couleur du contour n'est pas un fond
                }
                XmlPullParser.TEXT -> if (inT && para != null) {
                    var s = esc(p.text)
                    if (b) s = "<b>$s</b>"
                    if (ital) s = "<i>$s</i>"
                    val css = buildString {
                        if (sz > 0) append("font-size:${String.format(Locale.US, "%.2f", (sz / 100f) / slideWpt * 100f)}cqw;")
                        col?.let { append("color:#$it;") }
                    }
                    para!!.append(if (css.isEmpty()) s else "<span style=\"$css\">$s</span>")
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "a:t" -> inT = false
                    "a:rPr" -> inRPr = false
                    "a:xfrm", "p:xfrm" -> inXfrm = false
                    "p:spPr" -> inSpPr = false
                    "a:p" -> {
                        val c = para?.toString().orEmpty()
                        para = null
                        val style = pAlign?.let { " style=\"text-align:$it\"" } ?: ""
                        text.append("<p$style>${c.ifBlank { "&nbsp;" }}</p>")
                    }
                    "p:sp", "p:pic", "p:cxnSp", "p:graphicFrame" -> {
                        shapeDepth--
                        if (shapeDepth == 0) { flushShape(); inShape = false }
                    }
                }
            }
        }
        return out.toString()
    }

    // =====================================================================
    // Mise en page commune
    // =====================================================================

    fun page(title: String, css: String, body: String) = """<!DOCTYPE html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=6">
<title>${esc(title)}</title><style>$BASE_CSS$css</style></head><body>$body</body></html>"""

    private const val BASE_CSS = """
:root{--bg:#f4f5f7;--fg:#1c1f26;--paper:#ffffff;--line:#d9dde4;--dim:#6b7280}
body.night{--bg:#0f131b;--fg:#dce1ea;--paper:#171c26;--line:#2c3443;--dim:#8a93a6}
body.sepia{--bg:#e9dfc8;--fg:#4a3b28;--paper:#f6ecd6;--line:#d6c7a6;--dim:#7b6a50}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg);font-family:sans-serif;-webkit-text-size-adjust:100%}
img{max-width:100%;height:auto}
mark.hit{background:#ffd54f;color:#000}
"""

    private const val WORD_CSS = """
article{background:var(--paper);margin:10px;padding:22px 18px;border-radius:6px;line-height:1.55;font-size:16px;word-wrap:break-word;box-shadow:0 1px 3px rgba(0,0,0,.12)}
h1{font-size:1.5em;margin:.6em 0 .3em}h2{font-size:1.25em;margin:.6em 0 .3em}h3{font-size:1.1em;margin:.5em 0 .3em}
p{margin:.35em 0}p.e{margin:0;line-height:.8}p.li{padding-left:1.2em;text-indent:-.9em}p.li:before{content:"\2022\00a0\00a0"}
.tw{overflow-x:auto;margin:.6em 0}table{border-collapse:collapse;min-width:60%}td{border:1px solid var(--line);padding:4px 6px;vertical-align:top}td p{margin:.15em 0}
hr{border:none;border-top:1px dashed var(--line);margin:1.2em 0}
"""

    private const val XLSX_CSS = """
.tabs{position:sticky;top:0;z-index:3;display:flex;gap:6px;overflow-x:auto;padding:8px;background:var(--bg);border-bottom:1px solid var(--line)}
.tabs button{border:1px solid var(--line);background:var(--paper);color:var(--fg);border-radius:16px;padding:6px 12px;white-space:nowrap;font-size:14px}
.tabs button.on{background:#2f6fe0;color:#fff;border-color:#2f6fe0}
.tw{overflow:auto}table{border-collapse:collapse;font-size:13px;background:var(--paper)}
th,td{border:1px solid var(--line);padding:4px 8px;white-space:nowrap;max-width:320px;overflow:hidden;text-overflow:ellipsis}
th{position:sticky;top:0;background:#e8ebf1;color:#4b5563;font-weight:600;z-index:1}
body.night th{background:#222a38;color:#aab3c5}
td.rn{background:#eef0f4;color:#6b7280;text-align:right;position:sticky;left:0}
body.night td.rn{background:#1d2430}
td.n{text-align:right;font-variant-numeric:tabular-nums}
tr.gap td{border:none;background:transparent}.empty{padding:20px;color:var(--dim)}
"""

    private const val PPTX_CSS = """
main{padding:10px;display:flex;flex-direction:column;gap:14px}
.slide{position:relative;width:100%;background:#fff;color:#111;border-radius:6px;overflow:hidden;container-type:inline-size;box-shadow:0 1px 4px rgba(0,0,0,.2)}
.slide .num{position:absolute;right:6px;bottom:4px;font-size:11px;color:#999;z-index:5}
.el{position:absolute;box-sizing:border-box;overflow:hidden;font-size:2.6cqw;line-height:1.2;padding:.4cqw}
.el p{margin:0 0 .4cqw 0}img.el{object-fit:contain;padding:0}
body.present{background:#000}
body.present main{padding:0;gap:0;height:100vh;overflow-y:scroll;scroll-snap-type:y mandatory}
body.present .slide{border-radius:0;scroll-snap-align:center;margin:auto 0;flex:0 0 auto}
body.present main{justify-content:flex-start}
"""
}
