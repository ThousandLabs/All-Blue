package com.thousandlabs.allblue.docs

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.roundToInt

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
    // Lecture fidèle : thème et couleurs, fonds, masques et modèles (placeholders),
    // formes (rondes, arrondies, traits), transparence, groupes, puces, tableaux, images.
    // =====================================================================

    private class Lvl {
        var sz: Int? = null; var b: Boolean? = null; var i: Boolean? = null; var u: Boolean? = null
        var color: String? = null; var algn: String? = null; var font: String? = null
        var marL: Long? = null; var indent: Long? = null; var bu: String? = null
        var lnPct: Float? = null; var spcBef: Int? = null; var spcAft: Int? = null

        fun copy(): Lvl = Lvl().under(this)

        /** Complète les propriétés absentes avec celles de [o]. */
        fun under(o: Lvl?): Lvl {
            if (o == null) return this
            if (sz == null) sz = o.sz
            if (b == null) b = o.b
            if (i == null) i = o.i
            if (u == null) u = o.u
            if (color == null) color = o.color
            if (algn == null) algn = o.algn
            if (font == null) font = o.font
            if (marL == null) marL = o.marL
            if (indent == null) indent = o.indent
            if (bu == null) bu = o.bu
            if (lnPct == null) lnPct = o.lnPct
            if (spcBef == null) spcBef = o.spcBef
            if (spcAft == null) spcAft = o.spcAft
            return this
        }
    }

    private class PRun { val text = StringBuilder(); val props = Lvl(); var br = false; var field: String? = null }
    private class PPara { val props = Lvl(); var lvl = 0; val runs = ArrayList<PRun>(); var endSz: Int? = null }
    private class PCell {
        val paras = ArrayList<PPara>(); var fill: String? = null; var span = 1; var rowSpan = 1; var merged = false
        val borders = HashMap<Char, String>(); var anchor: String? = null; val mar = longArrayOf(91440, 45720, 91440, 45720)
    }
    private class PRow(val h: Long) { val cells = ArrayList<PCell>() }
    private class Grp {
        var ox = 0L; var oy = 0L; var ex = 1L; var ey = 1L; var chx = 0L; var chy = 0L; var chex = 1L; var chey = 1L
    }

    private class PShape(val kind: String) {
        var x: Long? = null; var y: Long? = null; var w: Long? = null; var h: Long? = null; var rot = 0f
        var isPh = false; var ph: String? = null; var phIdx: String? = null; var hidden = false
        var geom: String? = null; var adj: Long? = null
        var fill: String? = null; var fillAlpha = 100f; var fillSet = false
        var line: String? = null; var lineW = 12700L; var lineSet = false
        var styleFill: String? = null; var styleFont: String? = null
        var anchor: String? = null; val ins = arrayOfNulls<Long>(4); var wrap = true; var scale = 100f
        val lst = HashMap<Int, Lvl>()
        val paras = ArrayList<PPara>(); var img: String? = null
        var table = false; var chart = false; val cols = ArrayList<Long>(); val rows = ArrayList<PRow>()
        var tblStyle: String? = null; var firstRow = false; var bandRow = false
        var groups: List<Grp> = emptyList()
    }

    private class PPart {
        val shapes = ArrayList<PShape>(); var bg: String? = null
        val txStyles = HashMap<String, HashMap<Int, Lvl>>(); var showMaster = true
        var clrMap: Map<String, String>? = null
    }

    private class PTheme {
        val colors = hashMapOf(
            "dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6",
            "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5", "accent4" to "FFC000",
            "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72"
        )
        var map: Map<String, String> = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
        var major = "sans-serif"; var minor = "sans-serif"
        fun scheme(name: String): String? = colors[map[name] ?: name]
    }

    private class PColor(val base: String?, val ctx: List<String>) {
        val mods = ArrayList<Pair<String, Int>>(); var alpha = 100f
    }

    private val COLOR_TAGS = setOf("a:srgbClr", "a:schemeClr", "a:sysClr", "a:prstClr")
    private val SHAPE_TAGS = setOf("p:sp", "p:pic", "p:cxnSp", "p:graphicFrame")
    private val PRESET_COLORS = mapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "blue" to "0000FF",
        "green" to "008000", "yellow" to "FFFF00", "gray" to "808080"
    )
    private const val DEFAULT_TABLE_STYLE = "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"

    private fun lvlIndex(tag: String): Int? = when {
        tag == "a:defPPr" -> 0
        tag.length == 9 && tag.startsWith("a:lvl") && tag.endsWith("pPr") -> tag[5].digitToIntOrNull()?.minus(1)
        else -> null
    }

    // ---------- Couleurs ----------

    private fun rgbOf(hex: String): FloatArray {
        val v = hex.toIntOrNull(16) ?: 0
        return floatArrayOf(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
    }

    private fun hexOf(r: Float, g: Float, b: Float): String {
        fun c(x: Float) = (x * 255f).roundToInt().coerceIn(0, 255)
        return String.format(Locale.US, "%02X%02X%02X", c(r), c(g), c(b))
    }

    private fun hue(m1: Float, m2: Float, hIn: Float): Float {
        val h = ((hIn % 1f) + 1f) % 1f
        return when {
            h < 1f / 6f -> m1 + (m2 - m1) * h * 6f
            h < 0.5f -> m2
            h < 2f / 3f -> m1 + (m2 - m1) * (2f / 3f - h) * 6f
            else -> m1
        }
    }

    /** Applique luminosité, ombre et teinte (lumMod, lumOff, shade, tint). */
    private fun applyMods(hex: String, mods: List<Pair<String, Int>>): String {
        if (mods.isEmpty()) return hex
        val c = rgbOf(hex); var r = c[0]; var g = c[1]; var b = c[2]
        for ((k, v) in mods) when (k) {
            "lumMod", "lumOff" -> {
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                var l = (mx + mn) / 2f
                var hh = 0f; var s = 0f
                if (mx != mn) {
                    val d = mx - mn
                    s = if (l <= 0.5f) d / (mx + mn) else d / (2f - mx - mn)
                    val rc = (mx - r) / d; val gc = (mx - g) / d; val bc = (mx - b) / d
                    hh = when (mx) { r -> bc - gc; g -> 2f + rc - bc; else -> 4f + gc - rc }
                    hh = ((hh / 6f) % 1f + 1f) % 1f
                }
                l = if (k == "lumMod") l * v / 100000f else l + v / 100000f
                l = l.coerceIn(0f, 1f)
                if (s == 0f) { r = l; g = l; b = l } else {
                    val m2 = if (l <= 0.5f) l * (1f + s) else l + s - l * s
                    val m1 = 2f * l - m2
                    r = hue(m1, m2, hh + 1f / 3f); g = hue(m1, m2, hh); b = hue(m1, m2, hh - 1f / 3f)
                }
            }
            "shade" -> { val f = v / 100000f; r *= f; g *= f; b *= f }
            "tint" -> { val f = v / 100000f; r = 1f - (1f - r) * f; g = 1f - (1f - g) * f; b = 1f - (1f - b) * f }
        }
        return hexOf(r, g, b)
    }

    private fun parseTheme(xml: String, th: PTheme) {
        val p = parser(xml)
        val stack = ArrayList<String>()
        var slot: String? = null; var font: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    stack.add(p.name)
                    if (stack.size >= 2 && stack[stack.size - 2] == "a:clrScheme") slot = p.name.removePrefix("a:")
                    val sl = slot
                    if (sl != null && p.name == "a:srgbClr") th.colors[sl] = (p.attr("val") ?: "000000").uppercase(Locale.ROOT)
                    if (sl != null && p.name == "a:sysClr") th.colors[sl] = (p.attr("lastClr") ?: "000000").uppercase(Locale.ROOT)
                    if (p.name == "a:majorFont" || p.name == "a:minorFont") font = p.name
                    if (p.name == "a:latin" && font != null) {
                        val tf = p.attr("typeface")
                        if (!tf.isNullOrBlank()) { if (font == "a:majorFont") th.major = tf else th.minor = tf }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (stack.size >= 2 && stack[stack.size - 2] == "a:clrScheme") slot = null
                    if (p.name == "a:majorFont" || p.name == "a:minorFont") font = null
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            }
        }
    }

    // ---------- Lecture d'une diapo, d'un modèle ou d'un masque ----------

    private fun readPpr(p: XmlPullParser, lv: Lvl) {
        p.attr("algn")?.let { lv.algn = when (it) { "ctr" -> "center"; "r" -> "right"; "just", "dist" -> "justify"; else -> "left" } }
        p.attr("marL")?.toLongOrNull()?.let { lv.marL = it }
        p.attr("indent")?.toLongOrNull()?.let { lv.indent = it }
    }

    private fun readRpr(p: XmlPullParser, lv: Lvl) {
        p.attr("sz")?.toIntOrNull()?.let { lv.sz = it }
        p.attr("b")?.let { lv.b = it == "1" || it == "true" }
        p.attr("i")?.let { lv.i = it == "1" || it == "true" }
        p.attr("u")?.let { lv.u = it != "none" }
    }

    private fun parsePart(xml: String, theme: PTheme): PPart {
        val res = PPart()
        val p = parser(xml)
        val stack = ArrayList<String>()
        val groups = ArrayList<Grp>()
        var grp: Grp? = null
        var shape: PShape? = null
        var para: PPara? = null
        var run: PRun? = null
        var lvl: Lvl? = null
        var color: PColor? = null
        var row: PRow? = null
        var cell: PCell? = null

        fun assign(c: PColor) {
            val base = c.base ?: return
            val hx = applyMods(base, c.mods)
            val ctx = c.ctx
            val parent = ctx.lastOrNull() ?: return
            val gp = if (ctx.size >= 2) ctx[ctx.size - 2] else ""
            val sh = shape
            when (parent) {
                "a:solidFill" -> when (gp) {
                    "p:spPr" -> if (sh != null) { sh.fill = hx; sh.fillAlpha = c.alpha }
                    "a:ln" -> if (sh != null && "p:spPr" in ctx) { sh.line = hx; sh.lineSet = true }
                    "a:rPr" -> run?.props?.color = hx
                    "a:defRPr" -> lvl?.color = hx
                    "a:tcPr" -> cell?.fill = hx
                    "a:lnL", "a:lnR", "a:lnT", "a:lnB" -> cell?.borders?.put(gp[4], hx)
                    "p:bgPr" -> res.bg = hx
                }
                "a:gs" -> {
                    if ("p:bgPr" in ctx) { if (res.bg == null) res.bg = hx }
                    else if (sh != null && "p:spPr" in ctx && sh.fill == null) { sh.fill = hx; sh.fillAlpha = c.alpha }
                }
                "p:bgRef" -> res.bg = hx
                "a:fillRef" -> sh?.styleFill = hx
                "a:fontRef" -> sh?.styleFont = hx
            }
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    stack.add(n)
                    val parent = if (stack.size >= 2) stack[stack.size - 2] else ""
                    val gp = if (stack.size >= 3) stack[stack.size - 3] else ""
                    when (n) {
                        "p:sld", "p:sldLayout" -> if (p.attr("showMasterSp") == "0") res.showMaster = false
                        "p:clrMap" -> {
                            val m = HashMap<String, String>()
                            for (k in 0 until p.attributeCount) m[p.getAttributeName(k)] = p.getAttributeValue(k)
                            res.clrMap = m
                        }
                        "p:grpSp" -> grp = Grp()
                        "p:cNvPr" -> if (p.attr("hidden") == "1" || p.attr("hidden") == "true") shape?.hidden = true
                        "p:ph" -> shape?.let { it.isPh = true; it.ph = p.attr("type") ?: "body"; it.phIdx = p.attr("idx") }
                        "a:off" -> if (parent == "a:xfrm" || parent == "p:xfrm") {
                            val g = grp; val sh = shape
                            if (gp == "p:grpSpPr" && g != null) { g.ox = p.attr("x")?.toLongOrNull() ?: 0; g.oy = p.attr("y")?.toLongOrNull() ?: 0 }
                            else if (sh != null && sh.x == null) { sh.x = p.attr("x")?.toLongOrNull() ?: 0; sh.y = p.attr("y")?.toLongOrNull() ?: 0 }
                        }
                        "a:ext" -> if (parent == "a:xfrm" || parent == "p:xfrm") {
                            val g = grp; val sh = shape
                            if (gp == "p:grpSpPr" && g != null) {
                                g.ex = (p.attr("cx")?.toLongOrNull() ?: 1).coerceAtLeast(1)
                                g.ey = (p.attr("cy")?.toLongOrNull() ?: 1).coerceAtLeast(1)
                            } else if (sh != null && sh.w == null) { sh.w = p.attr("cx")?.toLongOrNull() ?: 0; sh.h = p.attr("cy")?.toLongOrNull() ?: 0 }
                        }
                        "a:chOff" -> grp?.let { it.chx = p.attr("x")?.toLongOrNull() ?: 0; it.chy = p.attr("y")?.toLongOrNull() ?: 0 }
                        "a:chExt" -> grp?.let {
                            it.chex = (p.attr("cx")?.toLongOrNull() ?: 1).coerceAtLeast(1)
                            it.chey = (p.attr("cy")?.toLongOrNull() ?: 1).coerceAtLeast(1)
                        }
                        "a:xfrm" -> if (parent == "p:spPr") p.attr("rot")?.toLongOrNull()?.let { shape?.rot = it / 60000f }
                        "a:prstGeom" -> shape?.geom = p.attr("prst")
                        "a:gd" -> if (parent == "a:avLst" && p.attr("name") == "adj") {
                            Regex("val (-?\\d+)").find(p.attr("fmla") ?: "")?.groupValues?.get(1)?.toLongOrNull()?.let { shape?.adj = it }
                        }
                        "a:noFill" -> {
                            val sh = shape
                            if (sh != null && parent == "p:spPr") sh.fillSet = true
                            if (sh != null && parent == "a:ln") sh.lineSet = true
                        }
                        "a:solidFill", "a:gradFill", "a:blipFill", "a:pattFill" -> if (parent == "p:spPr") shape?.fillSet = true
                        "a:ln" -> if (parent == "p:spPr") p.attr("w")?.toLongOrNull()?.let { shape?.lineW = it }
                        "a:blip" -> shape?.img = p.attr("r:embed")
                        "a:bodyPr" -> shape?.let { sh ->
                            p.attr("anchor")?.let { sh.anchor = it }
                            listOf("lIns", "tIns", "rIns", "bIns").forEachIndexed { k, a -> p.attr(a)?.toLongOrNull()?.let { sh.ins[k] = it } }
                            if (p.attr("wrap") == "none") sh.wrap = false
                        }
                        "a:normAutofit" -> p.attr("fontScale")?.toFloatOrNull()?.let { shape?.scale = it / 1000f }
                        "a:defRPr" -> if (lvlIndex(parent) != null) lvl?.let { readRpr(p, it) }
                        "a:latin" -> if (parent == "a:rPr" || parent == "a:defRPr") {
                            val tgt = if (parent == "a:rPr") run?.props else lvl
                            tgt?.font = p.attr("typeface")
                        }
                        "a:tbl" -> shape?.table = true
                        "a:tblPr" -> shape?.let { it.firstRow = p.attr("firstRow") == "1"; it.bandRow = p.attr("bandRow") == "1" }
                        "c:chart" -> shape?.chart = true
                        "a:gridCol" -> shape?.cols?.add(p.attr("w")?.toLongOrNull() ?: 0)
                        "a:tr" -> shape?.let { val r = PRow(p.attr("h")?.toLongOrNull() ?: 0); it.rows.add(r); row = r }
                        "a:tc" -> row?.let { r ->
                            val c = PCell(); r.cells.add(c); cell = c
                            c.span = p.attr("gridSpan")?.toIntOrNull() ?: 1
                            c.rowSpan = p.attr("rowSpan")?.toIntOrNull() ?: 1
                            val hm = p.attr("hMerge"); val vm = p.attr("vMerge")
                            c.merged = hm == "1" || hm == "true" || vm == "1" || vm == "true"
                        }
                        "a:tcPr" -> cell?.let { c ->
                            listOf("marL", "marT", "marR", "marB").forEachIndexed { k, a -> p.attr(a)?.toLongOrNull()?.let { c.mar[k] = it } }
                            p.attr("anchor")?.let { c.anchor = it }
                        }
                        "a:p" -> shape?.let { sh ->
                            val pa = PPara(); para = pa
                            val c = cell
                            if (c != null) c.paras.add(pa) else sh.paras.add(pa)
                        }
                        "a:pPr" -> para?.let { pa -> readPpr(p, pa.props); pa.lvl = p.attr("lvl")?.toIntOrNull() ?: 0; lvl = pa.props }
                        "a:r", "a:fld" -> para?.let { pa -> val r = PRun(); if (n == "a:fld") r.field = p.attr("type"); pa.runs.add(r); run = r }
                        "a:br" -> para?.let { pa -> pa.runs.add(PRun().also { it.br = true }) }
                        "a:rPr" -> run?.let { readRpr(p, it.props) }
                        "a:endParaRPr" -> p.attr("sz")?.toIntOrNull()?.let { para?.endSz = it }
                    }
                    // Styles de niveaux (lstStyle d'une forme, styles du masque, style par défaut)
                    val li = lvlIndex(n)
                    if (li != null) {
                        val lv = Lvl(); readPpr(p, lv); lvl = lv
                        val sh = shape
                        val key = listOf("p:titleStyle", "p:bodyStyle", "p:otherStyle").firstOrNull { it in stack }
                        when {
                            sh != null && "a:lstStyle" in stack -> sh.lst[li] = lv
                            key != null -> res.txStyles.getOrPut(key) { HashMap() }[li] = lv
                            "p:defaultTextStyle" in stack -> res.txStyles.getOrPut("default") { HashMap() }[li] = lv
                        }
                    }
                    // Puces et espacements
                    val lv = lvl
                    if (lv != null && (parent == "a:pPr" || lvlIndex(parent) != null)) when (n) {
                        "a:buNone" -> lv.bu = ""
                        "a:buChar" -> lv.bu = p.attr("char") ?: "•"
                        "a:buAutoNum" -> lv.bu = "#" + (p.attr("type") ?: "arabicPeriod")
                    }
                    if (lv != null && n == "a:spcPct" && parent == "a:lnSpc") lv.lnPct = (p.attr("val")?.toFloatOrNull() ?: 100000f) / 1000f
                    if (lv != null && n == "a:spcPts" && parent == "a:spcBef") lv.spcBef = p.attr("val")?.toIntOrNull() ?: 0
                    if (lv != null && n == "a:spcPts" && parent == "a:spcAft") lv.spcAft = p.attr("val")?.toIntOrNull() ?: 0
                    // Couleurs
                    if (n in COLOR_TAGS) {
                        val base = when (n) {
                            "a:srgbClr" -> (p.attr("val") ?: "000000").uppercase(Locale.ROOT)
                            "a:sysClr" -> (p.attr("lastClr") ?: "000000").uppercase(Locale.ROOT)
                            "a:prstClr" -> PRESET_COLORS[p.attr("val")] ?: "000000"
                            else -> p.attr("val")?.takeIf { it != "phClr" }?.let { theme.scheme(it) }
                        }
                        color = PColor(base, ArrayList(stack.subList(0, stack.size - 1)))
                    } else if (parent in COLOR_TAGS) {
                        val c = color
                        val v = p.attr("val")?.toIntOrNull() ?: 100000
                        if (c != null && n == "a:alpha") c.alpha = v / 1000f
                        if (c != null && (n == "a:lumMod" || n == "a:lumOff" || n == "a:shade" || n == "a:tint")) c.mods.add(n.removePrefix("a:") to v)
                    }
                    if (n == "a:tableStyleId") shape?.tblStyle = ""
                }
                XmlPullParser.TEXT -> {
                    val top = stack.lastOrNull()
                    if (top == "a:t") run?.text?.append(p.text)
                    if (top == "a:tableStyleId") shape?.let { it.tblStyle = (it.tblStyle ?: "") + p.text.trim() }
                }
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    if (n in COLOR_TAGS) color?.let { assign(it); color = null }
                    when (n) {
                        "a:r", "a:fld" -> run = null
                        "a:p" -> { para = null; lvl = null }
                        "a:pPr" -> lvl = null
                        "a:tc" -> cell = null
                        "a:tr" -> row = null
                        "p:grpSpPr" -> grp?.let { groups.add(it); grp = null }
                        "p:grpSp" -> if (groups.isNotEmpty()) groups.removeAt(groups.lastIndex)
                    }
                    if (lvlIndex(n) != null) lvl = null
                    if (n in SHAPE_TAGS) shape?.let { res.shapes.add(it); shape = null }
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            }
            // Une forme commence : on la crée après avoir empilé la balise
            if (p.eventType == XmlPullParser.START_TAG && p.name in SHAPE_TAGS && shape == null) {
                shape = PShape(p.name.removePrefix("p:")).also { it.groups = groups.toList() }
            }
        }
        return res
    }

    // ---------- Rendu ----------

    private fun num(v: Double) = String.format(Locale.US, "%.3f", v)
    /** Longueur en EMU → unité relative à la largeur de la diapo. */
    private fun cqw(emu: Double, cx: Long) = num(emu * 100.0 / cx) + "cqw"
    private fun cqwPt(pt: Double, cx: Long) = num(pt * 12700.0 * 100.0 / cx) + "cqw"

    private fun fontCss(face: String?, theme: PTheme): String? {
        if (face.isNullOrBlank()) return null
        val f = when { face.startsWith("+mj") -> theme.major; face.startsWith("+mn") -> theme.minor; else -> face }
        val low = f.lowercase(Locale.ROOT)
        val serif = listOf("cambria", "georgia", "times", "garamond", "serif", "bookman", "century", "palatino", "constantia").any { it in low }
        return "'" + f.replace("'", "").replace("\"", "") + "'," + (if (serif) "serif" else "sans-serif")
    }

    private fun autoNum(kind: String, n: Int): String {
        val k = kind.removePrefix("#")
        val s = when {
            k.startsWith("alphaLc") -> ('a' + (n - 1) % 26).toString()
            k.startsWith("alphaUc") -> ('A' + (n - 1) % 26).toString()
            k.startsWith("roman") -> {
                val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
                val syms = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
                val sb = StringBuilder(); var v = n
                for (j in vals.indices) while (v >= vals[j]) { sb.append(syms[j]); v -= vals[j] }
                if (k.startsWith("romanUc")) sb.toString().uppercase(Locale.ROOT) else sb.toString()
            }
            else -> n.toString()
        }
        return when {
            k.endsWith("ParenR") -> "$s)"
            k.endsWith("ParenBoth") -> "($s)"
            k.endsWith("Plain") -> s
            else -> "$s."
        }
    }

    private fun renderParas(paras: List<PPara>, chain: (Int) -> List<Lvl>, cx: Long, scale: Float, theme: PTheme, defColor: String?, slideNum: Int): String {
        val out = StringBuilder()
        val counters = HashMap<Int, Int>()
        for (pa in paras) {
            val base = pa.props.copy()
            for (c in chain(pa.lvl)) base.under(c)
            val runs = StringBuilder()
            var maxSz = 0f
            var hasText = false
            for (r in pa.runs) {
                if (r.br) { runs.append("<br>"); continue }
                val rp = r.props.copy().under(base)
                val sz = (rp.sz ?: 1800) * scale / 100f
                if (sz > maxSz) maxSz = sz
                val t = if (r.field == "slidenum") slideNum.toString() else r.text.toString()
                if (t.isNotBlank()) hasText = true
                val css = StringBuilder("font-size:").append(cqwPt(sz / 100.0, cx)).append(';')
                (rp.color ?: defColor)?.let { css.append("color:#").append(it).append(';') }
                if (rp.b == true) css.append("font-weight:bold;")
                if (rp.i == true) css.append("font-style:italic;")
                if (rp.u == true) css.append("text-decoration:underline;")
                fontCss(rp.font, theme)?.let { css.append("font-family:").append(it).append(';') }
                runs.append("<span style=\"").append(css).append("\">").append(esc(t)).append("</span>")
            }
            if (maxSz == 0f) maxSz = (pa.endSz ?: base.sz ?: 1800) * scale / 100f
            val bu = base.bu
            var pre = ""
            if (!bu.isNullOrEmpty() && hasText) {
                val sym = if (bu.startsWith("#")) {
                    val c = (counters[pa.lvl] ?: 0) + 1; counters[pa.lvl] = c; autoNum(bu, c)
                } else bu
                pre = "<span class=\"bu\" style=\"color:#${base.color ?: defColor ?: "000000"}\">${esc(sym)}</span>"
            }
            val css = StringBuilder("font-size:").append(cqwPt(maxSz / 100.0, cx)).append(';')
            css.append("line-height:").append(num(((base.lnPct ?: 100f) / 100f * 1.2f).toDouble())).append(';')
            base.algn?.let { css.append("text-align:").append(it).append(';') }
            val marL = base.marL ?: 0L; val ind = base.indent ?: 0L
            if (marL != 0L || ind != 0L) css.append("padding-left:").append(cqw(marL.toDouble(), cx)).append(";text-indent:").append(cqw(ind.toDouble(), cx)).append(';')
            base.spcBef?.takeIf { it > 0 }?.let { css.append("margin-top:").append(cqwPt(it / 100.0 * scale / 100.0, cx)).append(';') }
            base.spcAft?.takeIf { it > 0 }?.let { css.append("margin-bottom:").append(cqwPt(it / 100.0 * scale / 100.0, cx)).append(';') }
            out.append("<p style=\"").append(css).append("\">").append(pre).append(if (runs.isEmpty()) "&nbsp;" else runs).append("</p>")
        }
        return out.toString()
    }

    private fun findPh(shapes: List<PShape>, sh: PShape?): PShape? {
        if (sh == null || !sh.isPh) return null
        sh.phIdx?.let { idx -> shapes.firstOrNull { it.isPh && it.phIdx == idx }?.let { return it } }
        shapes.firstOrNull { it.isPh && it.ph == sh.ph }?.let { return it }
        fun alias(t: String?) = when (t) { "ctrTitle" -> "title"; "subTitle", "obj" -> "body"; else -> t }
        return shapes.firstOrNull { it.isPh && alias(it.ph) == alias(sh.ph) }
    }

    private fun styleKey(sh: PShape) = when {
        !sh.isPh -> "default"
        sh.ph == "title" || sh.ph == "ctrTitle" -> "p:titleStyle"
        sh.ph == "body" || sh.ph == "subTitle" || sh.ph == "obj" -> "p:bodyStyle"
        else -> "p:otherStyle"
    }

    private fun rgba(hex: String, alpha: Float): String {
        if (alpha >= 100f) return "#$hex"
        val c = rgbOf(hex)
        return "rgba(${(c[0] * 255).roundToInt()},${(c[1] * 255).roundToInt()},${(c[2] * 255).roundToInt()},${num(alpha / 100.0)})"
    }

    private fun renderShape(
        zip: ZipFile, sh: PShape, cx: Long, cy: Long, theme: PTheme, rel: Map<String, String>, slideNum: Int,
        layoutShapes: List<PShape>, masterShapes: List<PShape>, master: PPart?, presDef: Map<Int, Lvl>
    ): String {
        if (sh.hidden) return ""
        val lph = if (sh.isPh) findPh(layoutShapes, sh) else null
        val mph = if (sh.isPh) findPh(masterShapes, lph ?: sh) else null
        val src = listOfNotNull(sh, lph, mph)
        val c0 = src.firstOrNull { it.x != null } ?: return ""
        // Position, en tenant compte des groupes
        var x = (c0.x ?: 0L).toDouble(); var y = (c0.y ?: 0L).toDouble()
        var w = (c0.w ?: 0L).toDouble(); var h = (c0.h ?: 0L).toDouble()
        for (g in sh.groups.asReversed()) {
            val sx = g.ex.toDouble() / g.chex; val sy = g.ey.toDouble() / g.chey
            x = g.ox + (x - g.chx) * sx; y = g.oy + (y - g.chy) * sy; w *= sx; h *= sy
        }
        val pos = StringBuilder("left:${num(x * 100 / cx)}%;top:${num(y * 100 / cy)}%;width:${num(w * 100 / cx)}%;height:${num(h * 100 / cy)}%;")
        if (sh.rot != 0f) pos.append("transform:rotate(${num(sh.rot.toDouble())}deg);")
        val geom = sh.geom
        val lineW = "max(1px,${cqw(sh.lineW.toDouble(), cx)})"
        // Traits
        if (sh.kind == "cxnSp" || geom == "line" || geom == "straightConnector1") {
            val col = sh.line ?: "000000"
            return if (h == 0.0 || w >= h) "<div class=\"el\" style=\"${pos}height:0;border-top:$lineW solid #$col\"></div>"
            else "<div class=\"el\" style=\"${pos}width:0;border-left:$lineW solid #$col\"></div>"
        }
        // Images
        val imgPath = sh.img?.let { rel[it] }
        if (imgPath != null) {
            val data = imageData(zip, imgPath)
            if (data != null) {
                val rad = if (geom == "ellipse") "border-radius:50%;" else ""
                return "<img class=\"el\" style=\"$pos$rad\" src=\"$data\">"
            }
        }
        val style = StringBuilder(pos)
        val fill = sh.fill ?: if (!sh.fillSet) sh.styleFill else null
        if (fill != null) style.append("background:").append(rgba(fill, sh.fillAlpha)).append(';')
        val line = sh.line
        if (line != null && sh.lineSet && line != fill) style.append("border:$lineW solid #$line;")
        when (geom) {
            "ellipse" -> style.append("border-radius:50%;")
            "roundRect" -> style.append("border-radius:").append(cqw(minOf(w, h) * (sh.adj ?: 16667L) / 100000.0, cx)).append(';')
        }
        if (sh.table) return "<div class=\"el\" style=\"$style\">${renderTable(sh, cx, theme, slideNum, presDef)}</div>"
        if (sh.chart) return "<div class=\"el chart\" style=\"$style\">Graphique</div>"
        val hasText = sh.paras.any { pa -> pa.runs.any { it.text.isNotBlank() || it.field != null } }
        if (!hasText) return if (fill != null || (line != null && sh.lineSet)) "<div class=\"el\" style=\"$style\"></div>" else ""
        val key = styleKey(sh)
        val tx = master?.txStyles?.get(key) ?: emptyMap<Int, Lvl>()
        val styleFont = sh.styleFont?.let { c -> Lvl().also { it.color = c } }
        val chain: (Int) -> List<Lvl> = { l ->
            val list = ArrayList<Lvl>()
            for (c in src) c.lst[l]?.let { list.add(it) }
            styleFont?.let { list.add(it) }
            tx[l]?.let { list.add(it) }
            if (!sh.isPh) presDef[l]?.let { list.add(it) }
            list
        }
        val scale = src.firstOrNull { it.scale != 100f }?.scale ?: 100f
        val anchor = src.firstNotNullOfOrNull { it.anchor } ?: "t"
        val just = when (anchor) { "ctr" -> "center"; "b" -> "flex-end"; else -> "flex-start" }
        val defaults = longArrayOf(91440, 45720, 91440, 45720)
        val ins = LongArray(4) { k -> src.firstNotNullOfOrNull { it.ins[k] } ?: defaults[k] }
        val pad = "padding:${cqw(ins[1].toDouble(), cx)} ${cqw(ins[2].toDouble(), cx)} ${cqw(ins[3].toDouble(), cx)} ${cqw(ins[0].toDouble(), cx)};"
        val wrap = if (sh.wrap) "" else "white-space:nowrap;"
        val body = renderParas(sh.paras, chain, cx, scale, theme, theme.scheme("tx1"), slideNum)
        return "<div class=\"el\" style=\"$style\"><div class=\"tx\" style=\"justify-content:$just;$pad$wrap\">$body</div></div>"
    }

    private fun renderTable(sh: PShape, cx: Long, theme: PTheme, slideNum: Int, presDef: Map<Int, Lvl>): String {
        val total = sh.cols.sum().coerceAtLeast(1L)
        val out = StringBuilder("<table class=\"tb\"><colgroup>")
        for (w in sh.cols) out.append("<col style=\"width:${num(w * 100.0 / total)}%\">")
        out.append("</colgroup>")
        // Style de tableau par défaut de PowerPoint (« Style moyen 2 - Accent 1 ») quand aucune couleur n'est donnée
        val anyFill = sh.rows.any { r -> r.cells.any { it.fill != null || it.borders.isNotEmpty() } }
        val styled = !anyFill && sh.tblStyle == DEFAULT_TABLE_STYLE
        val accent = theme.scheme("accent1") ?: "4472C4"
        val head = Lvl().also { it.color = theme.scheme("lt1") ?: "FFFFFF"; it.b = true }
        sh.rows.forEachIndexed { ri, row ->
            out.append("<tr style=\"height:${cqw(row.h.toDouble(), cx)}\">")
            val isHead = styled && sh.firstRow && ri == 0
            for (c in row.cells) {
                if (c.merged) continue
                val css = StringBuilder()
                val fill = c.fill ?: if (styled) {
                    when {
                        isHead -> accent
                        sh.bandRow && (ri - (if (sh.firstRow) 1 else 0)) % 2 == 0 -> applyMods(accent, listOf("tint" to 40000))
                        else -> applyMods(accent, listOf("tint" to 20000))
                    }
                } else null
                fill?.let { css.append("background:#").append(it).append(';') }
                if (styled) css.append("border:1px solid #FFFFFF;")
                else if (!anyFill) css.append("border:1px solid #BFBFBF;")
                for ((side, name) in listOf('L' to "left", 'R' to "right", 'T' to "top", 'B' to "bottom")) {
                    c.borders[side]?.let { css.append("border-$name:1px solid #$it;") }
                }
                css.append("padding:${cqw(c.mar[1].toDouble(), cx)} ${cqw(c.mar[2].toDouble(), cx)} ${cqw(c.mar[3].toDouble(), cx)} ${cqw(c.mar[0].toDouble(), cx)};")
                css.append("vertical-align:").append(when (c.anchor) { "ctr" -> "middle"; "b" -> "bottom"; else -> "top" }).append(';')
                val span = (if (c.span > 1) " colspan=\"${c.span}\"" else "") + (if (c.rowSpan > 1) " rowspan=\"${c.rowSpan}\"" else "")
                val chain: (Int) -> List<Lvl> = { l -> listOfNotNull(if (isHead) head else null, presDef[l]) }
                out.append("<td$span style=\"$css\">").append(renderParas(c.paras, chain, cx, 100f, theme, theme.scheme("tx1"), slideNum)).append("</td>")
            }
            out.append("</tr>")
        }
        return out.append("</table>").toString()
    }

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
        val presDef: Map<Int, Lvl> = parsePart(presXml, PTheme()).txStyles["default"] ?: emptyMap()
        fun relsOf(path: String): Map<String, String> {
            val dir = path.substringBeforeLast('/')
            return rels(zip, "$dir/_rels/${path.substringAfterLast('/')}.rels", dir)
        }
        // Masques et modèles lus une seule fois
        val themes = HashMap<String, PTheme>()
        val parts = HashMap<String, PPart>()
        fun themeFor(master: String?): PTheme {
            if (master == null) return PTheme()
            return themes.getOrPut(master) {
                val th = PTheme()
                relsOf(master).values.firstOrNull { "/theme/" in it || it.startsWith("ppt/theme") }?.let { tp -> zip.text(tp)?.let { parseTheme(it, th) } }
                zip.text(master)?.let { mx -> parsePart(mx, th).clrMap?.let { th.map = it } }
                th
            }
        }
        fun part(path: String?, theme: PTheme): PPart? {
            if (path == null) return null
            return parts.getOrPut(path) { parsePart(zip.text(path) ?: "<x/>", theme) }
        }
        val sb = StringBuilder()
        slidePaths.forEachIndexed { index, path ->
            val xml = zip.text(path) ?: return@forEachIndexed
            val rel = relsOf(path)
            val layout = rel.values.firstOrNull { "slideLayouts/" in it }
            val lrel = layout?.let { relsOf(it) } ?: emptyMap()
            val master = lrel.values.firstOrNull { "slideMasters/" in it }
            val mrel = master?.let { relsOf(it) } ?: emptyMap()
            val theme = themeFor(master)
            val mp = part(master, theme)
            val lp = part(layout, theme)
            val sp = parsePart(xml, theme)
            val bg = sp.bg ?: lp?.bg ?: mp?.bg ?: "FFFFFF"
            val sn = index + 1
            sb.append("<section class=\"slide\" style=\"aspect-ratio:$cx/$cy;background:#$bg\"><div class=\"num\">$sn</div>")
            if (sp.showMaster && lp?.showMaster != false && mp != null) {
                for (s in mp.shapes) if (!s.isPh) sb.append(renderShape(zip, s, cx, cy, theme, mrel, sn, emptyList(), emptyList(), mp, presDef))
            }
            if (sp.showMaster && lp != null) {
                for (s in lp.shapes) if (!s.isPh) sb.append(renderShape(zip, s, cx, cy, theme, lrel, sn, emptyList(), emptyList(), mp, presDef))
            }
            for (s in sp.shapes) {
                sb.append(renderShape(zip, s, cx, cy, theme, rel, sn, lp?.shapes ?: emptyList(), mp?.shapes ?: emptyList(), mp, presDef))
            }
            sb.append("</section>")
        }
        if (slidePaths.isEmpty()) sb.append("<p>Aucune diapositive.</p>")
        val script = "<script>function togglePresent(){document.body.classList.toggle('present');}</script>"
        page(file.nameWithoutExtension, PPTX_CSS, "<main>$sb</main>$script")
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
.el{position:absolute;box-sizing:border-box;font-size:2.6cqw;line-height:1.2}
.tx{display:flex;flex-direction:column;width:100%;height:100%;box-sizing:border-box;overflow-wrap:break-word}
.tx p{margin:0}
.bu{display:inline-block;min-width:1.1em;text-indent:0}
img.el{object-fit:fill}
.tb{width:100%;border-collapse:collapse;table-layout:fixed}.tb td{box-sizing:border-box;overflow-wrap:break-word}.tb p{margin:0}
.chart{display:flex;align-items:center;justify-content:center;color:#888;border:1px dashed #bbb}
body.present{background:#000}
body.present main{padding:0;gap:0;height:100vh;overflow-y:scroll;scroll-snap-type:y mandatory}
body.present .slide{border-radius:0;scroll-snap-align:center;margin:auto 0;flex:0 0 auto}
body.present main{justify-content:flex-start}
"""
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
