package com.thousandlabs.allblue.docs

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Modification directe des fichiers Word, Excel et PowerPoint.
 * On ne touche qu'aux textes et aux cellules modifiés : tout le reste du fichier
 * (mise en forme, images, styles) est recopié tel quel.
 */
object OfficeEdit {

    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    // ---------- Outils XML et ZIP ----------

    fun parse(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    fun serialize(doc: Document): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        doc.xmlStandalone = true
        val out = ByteArrayOutputStream()
        t.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    fun entry(zip: ZipFile, name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    /** Recopie l'archive en remplaçant ou supprimant certaines parties. */
    fun rewrite(src: File, out: File, replace: Map<String, ByteArray>, remove: Set<String> = emptySet()) {
        ZipFile(src).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (e in zip.entries()) {
                    if (e.name in remove) continue
                    zos.putNextEntry(ZipEntry(e.name))
                    val data = replace[e.name]
                    if (data != null) zos.write(data) else zip.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun Element.children(ns: String, local: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && n.localName == local) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.descendants(ns: String, local: String): List<Element> {
        val list = getElementsByTagNameNS(ns, local)
        return List(list.length) { list.item(it) as Element }
    }

    private fun rels(zip: ZipFile, relsPath: String, baseDir: String): Map<String, String> {
        val bytes = entry(zip, relsPath) ?: return emptyMap()
        val d = parse(bytes)
        val list = d.getElementsByTagName("Relationship")
        val out = HashMap<String, String>()
        for (i in 0 until list.length) {
            val e = list.item(i) as Element
            if (e.getAttribute("TargetMode") == "External") continue
            val target = e.getAttribute("Target")
            out[e.getAttribute("Id")] = if (target.startsWith("/")) target.trimStart('/') else normalize("$baseDir/$target")
        }
        return out
    }

    private fun normalize(path: String): String {
        val st = ArrayList<String>()
        for (p in path.split('/')) when (p) { "", "." -> Unit; ".." -> if (st.isNotEmpty()) st.removeAt(st.lastIndex); else -> st.add(p) }
        return st.joinToString("/")
    }

    // ---------- Paragraphes (Word et PowerPoint) ----------

    /** Un paragraphe modifiable : [where] indique son emplacement (« Tableau », « Diapo 3 »…). */
    data class Para(val part: String, val index: Int, val text: String, val kind: String, val where: String)

    private fun isLeafParagraph(p: Element, ns: String): Boolean = p.getElementsByTagNameNS(ns, "p").length == 0

    /** Runs de texte d'un paragraphe (y compris dans les liens), dans l'ordre. */
    private fun runs(p: Element, ns: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    if (c.namespaceURI == ns && c.localName == "r") out.add(c)
                    else if (!(c.namespaceURI == ns && (c.localName == "pPr" || c.localName == "p"))) walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(p)
        return out
    }

    private fun runText(r: Element, ns: String): String = r.children(ns, "t").joinToString("") { it.textContent }

    fun paragraphText(p: Element, ns: String): String = runs(p, ns).joinToString("") { runText(it, ns) }

    /**
     * Remplace le texte d'un paragraphe en conservant au mieux la mise en forme :
     * seul le passage réellement modifié change, dans le morceau de texte où il se trouve.
     */
    fun setParagraphText(doc: Document, p: Element, ns: String, prefix: String, newText: String) {
        val rs = runs(p, ns).filter { it.children(ns, "t").isNotEmpty() }
        val old = rs.map { runText(it, ns) }
        val oldAll = old.joinToString("")
        if (oldAll == newText) return
        if (rs.isEmpty()) {
            // paragraphe vide : on crée un morceau de texte
            val r = doc.createElementNS(ns, "$prefix:r")
            val t = doc.createElementNS(ns, "$prefix:t")
            if (ns == W) t.setAttributeNS(XML_NS, "xml:space", "preserve")
            t.textContent = newText
            r.appendChild(t)
            // en Word, garder la mise en forme du dernier caractère du paragraphe si elle existe
            val pPr = p.children(ns, "pPr").firstOrNull()
            pPr?.children(ns, "rPr")?.firstOrNull()?.let { r.insertBefore(it.cloneNode(true).let { c -> doc.renameNode(c, ns, "$prefix:rPr") }, t) }
            if (ns == A) {
                val end = p.children(ns, "endParaRPr").firstOrNull()
                if (end != null) p.insertBefore(r, end) else p.appendChild(r)
            } else p.appendChild(r)
            return
        }
        var pre = 0
        while (pre < oldAll.length && pre < newText.length && oldAll[pre] == newText[pre]) pre++
        var suf = 0
        while (suf < oldAll.length - pre && suf < newText.length - pre &&
            oldAll[oldAll.length - 1 - suf] == newText[newText.length - 1 - suf]) suf++
        val middle = newText.substring(pre, newText.length - suf)
        val cutEnd = oldAll.length - suf
        var pos = 0
        var inserted = false
        rs.forEachIndexed { i, r ->
            val a = pos; val b = pos + old[i].length; pos = b
            val sb = StringBuilder()
            for (k in a until b) {
                if (!inserted && k == pre) { sb.append(middle); inserted = true }
                if (k < pre || k >= cutEnd) sb.append(old[i][k - a])
            }
            if (!inserted && (pre in a until b || (pre == b && i == rs.lastIndex) || (b == a && pre == a))) { sb.append(middle); inserted = true }
            val ts = r.children(ns, "t")
            ts.first().textContent = sb.toString()
            if (ns == W) ts.first().setAttributeNS(XML_NS, "xml:space", "preserve")
            ts.drop(1).forEach { r.removeChild(it) }
        }
        if (!inserted) {
            val last = rs.last().children(ns, "t").first()
            last.textContent = last.textContent + middle
        }
    }

    // ---------- Word ----------

    class WordDoc(val doc: Document, val paragraphs: List<Element>)

    fun loadWord(file: File): Pair<WordDoc, List<Para>> = ZipFile(file).use { zip ->
        val d = parse(entry(zip, "word/document.xml") ?: error("Document Word illisible"))
        val all = d.documentElement.descendants(W, "p").filter { isLeafParagraph(it, W) }
        val paras = all.mapIndexed { i, p ->
            val style = p.children(W, "pPr").firstOrNull()?.children(W, "pStyle")?.firstOrNull()?.getAttributeNS(W, "val").orEmpty().lowercase(Locale.ROOT)
            val kind = when {
                style.startsWith("heading") || style.startsWith("titre") || style == "title" -> "Titre"
                p.children(W, "pPr").firstOrNull()?.children(W, "numPr")?.isNotEmpty() == true -> "Liste"
                else -> ""
            }
            val inTable = generateSequence(p.parentNode) { it.parentNode }.any { it is Element && it.localName == "tc" }
            Para("word/document.xml", i, paragraphText(p, W), kind, if (inTable) "Tableau" else "")
        }
        WordDoc(d, all) to paras
    }

    /**
     * Enregistre : [edits] index → nouveau texte, [deleted] paragraphes supprimés,
     * [insertAfter] index → textes des nouveaux paragraphes à ajouter après.
     */
    fun saveWord(src: File, out: File, edits: Map<Int, String>, deleted: Set<Int>, insertAfter: Map<Int, List<String>>) {
        val (wd, _) = loadWord(src)
        val d = wd.doc
        for ((i, text) in edits) wd.paragraphs.getOrNull(i)?.let { setParagraphText(d, it, W, "w", text) }
        for ((i, texts) in insertAfter) {
            val ref = wd.paragraphs.getOrNull(i) ?: continue
            var anchor: Node = ref
            val pPr = ref.children(W, "pPr").firstOrNull()
            val rPr = runs(ref, W).firstOrNull()?.children(W, "rPr")?.firstOrNull()
            for (t in texts) {
                // nouveau paragraphe propre : même style de paragraphe et même police que la référence
                val np = d.createElementNS(W, "w:p")
                pPr?.let { np.appendChild(it.cloneNode(true)) }
                np.children(W, "pPr").firstOrNull()?.children(W, "sectPr")?.forEach { it.parentNode.removeChild(it) }
                val r = d.createElementNS(W, "w:r")
                rPr?.let { r.appendChild(it.cloneNode(true)) }
                val te = d.createElementNS(W, "w:t")
                te.setAttributeNS(XML_NS, "xml:space", "preserve")
                te.textContent = t
                r.appendChild(te)
                np.appendChild(r)
                anchor.parentNode.insertBefore(np, anchor.nextSibling)
                anchor = np
            }
        }
        for (i in deleted) {
            val p = wd.paragraphs.getOrNull(i) ?: continue
            val parent = p.parentNode
            // une cellule de tableau doit garder au moins un paragraphe
            val isLastInCell = (parent as? Element)?.localName == "tc" && (parent as Element).children(W, "p").size <= 1
            // un paragraphe qui porte un changement de section ne peut pas disparaître : on le vide
            val carriesSection = p.children(W, "pPr").firstOrNull()?.children(W, "sectPr")?.isNotEmpty() == true
            if (isLastInCell || carriesSection) {
                runs(p, W).forEach { it.parentNode.removeChild(it) }
            } else parent.removeChild(p)
        }
        rewrite(src, out, mapOf("word/document.xml" to serialize(d)))
    }

    // ---------- PowerPoint ----------

    class Slides(val docs: Map<String, Document>, val paragraphs: List<Pair<String, Element>>)

    fun loadSlides(file: File): Pair<Slides, List<Para>> = ZipFile(file).use { zip ->
        val pres = parse(entry(zip, "ppt/presentation.xml") ?: error("Présentation illisible"))
        val rel = rels(zip, "ppt/_rels/presentation.xml.rels", "ppt")
        val ids = pres.documentElement.descendants(P, "sldId")
        val docs = LinkedHashMap<String, Document>()
        val paras = ArrayList<Pair<String, Element>>()
        val out = ArrayList<Para>()
        ids.forEachIndexed { si, idEl ->
            val path = rel[idEl.getAttributeNS(R, "id")] ?: return@forEachIndexed
            val bytes = entry(zip, path) ?: return@forEachIndexed
            val d = parse(bytes)
            docs[path] = d
            d.documentElement.descendants(A, "p").filter { p -> runs(p, A).isNotEmpty() || p.parentNode?.localName == "txBody" }.forEach { p ->
                out.add(Para(path, paras.size, paragraphText(p, A), "", "Diapo ${si + 1}"))
                paras.add(path to p)
            }
        }
        Slides(docs, paras) to out
    }

    fun saveSlides(src: File, out: File, edits: Map<Int, String>) {
        val (sl, _) = loadSlides(src)
        val touched = HashSet<String>()
        for ((i, text) in edits) {
            val (path, p) = sl.paragraphs.getOrNull(i) ?: continue
            setParagraphText(sl.docs.getValue(path), p, A, "a", text)
            touched.add(path)
        }
        rewrite(src, out, touched.associateWith { serialize(sl.docs.getValue(it)) })
    }

    // ---------- Excel ----------

    class Sheet(val name: String, val path: String)

    /** Contenu d'une cellule : valeur affichée et formule éventuelle (sans le signe =). */
    data class Cell(val value: String, val formula: String?)

    class Workbook(val sheets: List<Sheet>, val shared: List<String>)

    fun loadWorkbook(file: File): Workbook = ZipFile(file).use { zip ->
        val shared = ArrayList<String>()
        entry(zip, "xl/sharedStrings.xml")?.let { b ->
            parse(b).documentElement.children(S, "si").forEach { si ->
                // le texte phonétique (rPh) n'est pas affiché
                val sb = StringBuilder()
                fun walk(n: Node) {
                    var c = n.firstChild
                    while (c != null) {
                        if (c is Element) {
                            if (c.localName == "rPh") { c = c.nextSibling; continue }
                            if (c.localName == "t") sb.append(c.textContent) else walk(c)
                        }
                        c = c.nextSibling
                    }
                }
                walk(si)
                shared.add(sb.toString())
            }
        }
        val rel = rels(zip, "xl/_rels/workbook.xml.rels", "xl")
        val wb = parse(entry(zip, "xl/workbook.xml") ?: error("Classeur illisible"))
        val sheets = wb.documentElement.descendants(S, "sheet").mapNotNull { s ->
            val path = rel[s.getAttributeNS(R, "id")] ?: return@mapNotNull null
            Sheet(s.getAttribute("name").ifEmpty { "Feuille" }, path)
        }
        Workbook(sheets, shared)
    }

    fun ref(col: Int, row: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return "$sb$row"
    }

    /** (colonne à partir de 0, ligne à partir de 1) */
    fun parseRef(r: String): Pair<Int, Int>? {
        val m = Regex("^\\$?([A-Za-z]{1,3})\\$?(\\d+)$").find(r.trim()) ?: return null
        var c = 0
        for (ch in m.groupValues[1].uppercase(Locale.ROOT)) c = c * 26 + (ch - 'A' + 1)
        return (c - 1) to m.groupValues[2].toInt()
    }

    fun readSheet(file: File, wb: Workbook, sheet: Sheet): MutableMap<String, Cell> = ZipFile(file).use { zip ->
        val out = LinkedHashMap<String, Cell>()
        val d = parse(entry(zip, sheet.path) ?: return@use out)
        d.documentElement.descendants(S, "c").forEach { c ->
            val r = c.getAttribute("r").ifEmpty { return@forEach }
            val t = c.getAttribute("t")
            val v = c.children(S, "v").firstOrNull()?.textContent
            val f = c.children(S, "f").firstOrNull()?.textContent?.takeIf { it.isNotEmpty() }
            val value = when (t) {
                "s" -> wb.shared.getOrNull(v?.toIntOrNull() ?: -1).orEmpty()
                "inlineStr" -> c.descendants(S, "t").joinToString("") { it.textContent }
                "b" -> if (v == "1") "VRAI" else "FAUX"
                else -> v.orEmpty()
            }
            if (value.isNotEmpty() || f != null) out[r.uppercase(Locale.ROOT)] = Cell(value, f)
        }
        out
    }

    /**
     * Enregistre les cellules modifiées d'une feuille. [changes] : référence → texte saisi
     * (« =SOMME(A1:A3) » pour une formule, vide pour effacer). Les formules simples sont
     * calculées tout de suite ; Excel recalcule de toute façon tout le classeur à l'ouverture.
     */
    fun saveSheet(src: File, out: File, sheet: Sheet, changes: Map<String, String>, current: Map<String, Cell>) {
        ZipFile(src).use { zip ->
            val d = parse(entry(zip, sheet.path) ?: error("Feuille illisible"))
            val root = d.documentElement
            val sheetData = root.children(S, "sheetData").firstOrNull() ?: d.createElementNS(S, root.prefixed("sheetData")).also { root.appendChild(it) }

            // valeurs après modification, pour calculer les formules
            val values = HashMap<String, Cell>(current)
            for ((r, input) in changes) {
                val txt = input.trim()
                when {
                    txt.isEmpty() -> values.remove(r)
                    txt.startsWith("=") && txt.length > 1 -> values[r] = Cell("", Formula.toExcel(txt.substring(1)))
                    else -> values[r] = Cell(txt, null)
                }
            }
            val computed = Formula.computeAll(values)

            for ((r, input) in changes) {
                val (col, row) = parseRef(r) ?: continue
                val txt = input.trim()
                val rowEl = findOrCreateRow(d, sheetData, row)
                rowEl.removeAttribute("spans")
                var cell = rowEl.children(S, "c").firstOrNull { it.getAttribute("r").equals(r, true) }
                if (txt.isEmpty()) { cell?.let { rowEl.removeChild(it) }; continue }
                if (cell == null) {
                    cell = d.createElementNS(S, root.prefixed("c"))
                    cell.setAttribute("r", r)
                    val after = rowEl.children(S, "c").firstOrNull { (parseRef(it.getAttribute("r"))?.first ?: -1) > col }
                    rowEl.insertBefore(cell, after)
                }
                // formule partagée dont cette cellule est la source : les autres cellules gardent leur valeur
                cell.children(S, "f").firstOrNull()?.let { f0 ->
                    if (f0.getAttribute("t") == "shared" && f0.hasAttribute("ref")) {
                        val si = f0.getAttribute("si")
                        root.descendants(S, "f").filter { it !== f0 && it.getAttribute("t") == "shared" && it.getAttribute("si") == si }
                            .forEach { it.parentNode.removeChild(it) }
                    }
                }
                // on repart d'une cellule propre en gardant son style (s=…)
                while (cell.firstChild != null) cell.removeChild(cell.firstChild)
                cell.removeAttribute("t")
                val number = parseNumber(txt)
                when {
                    txt.startsWith("=") && txt.length > 1 -> {
                        val f = d.createElementNS(S, root.prefixed("f")); f.textContent = Formula.toExcel(txt.substring(1)); cell.appendChild(f)
                        computed[r]?.let { v ->
                            val ve = d.createElementNS(S, root.prefixed("v")); ve.textContent = numberString(v); cell.appendChild(ve)
                        }
                    }
                    number != null -> {
                        val ve = d.createElementNS(S, root.prefixed("v")); ve.textContent = numberString(number); cell.appendChild(ve)
                    }
                    txt.equals("VRAI", true) || txt.equals("FAUX", true) -> {
                        cell.setAttribute("t", "b")
                        val ve = d.createElementNS(S, root.prefixed("v")); ve.textContent = if (txt.equals("VRAI", true)) "1" else "0"; cell.appendChild(ve)
                    }
                    else -> {
                        cell.setAttribute("t", "inlineStr")
                        val isEl = d.createElementNS(S, root.prefixed("is"))
                        val t = d.createElementNS(S, root.prefixed("t")); t.textContent = txt
                        t.setAttributeNS(XML_NS, "xml:space", "preserve")
                        isEl.appendChild(t); cell.appendChild(isEl)
                    }
                }
            }
            // met à jour les résultats des autres formules simples de la feuille
            root.descendants(S, "c").forEach { c ->
                val r = c.getAttribute("r").uppercase(Locale.ROOT)
                if (r in changes || c.children(S, "f").isEmpty()) return@forEach
                val v = computed[r] ?: return@forEach
                if (c.getAttribute("t").isNotEmpty() && c.getAttribute("t") != "n") return@forEach
                val ve = c.children(S, "v").firstOrNull() ?: d.createElementNS(S, root.prefixed("v")).also { c.appendChild(it) }
                ve.textContent = numberString(v)
            }
            // agrandit la zone déclarée de la feuille si besoin
            root.children(S, "dimension").firstOrNull()?.let { dim ->
                val refs = (values.keys).mapNotNull { parseRef(it) }
                if (refs.isNotEmpty()) dim.setAttribute("ref", "A1:" + ref(refs.maxOf { it.first }, refs.maxOf { it.second }))
            }

            // Classeur : demande à Excel de tout recalculer à l'ouverture, et retire la chaîne de calcul (devenue fausse)
            val wbDoc = parse(entry(zip, "xl/workbook.xml")!!)
            val wbRoot = wbDoc.documentElement
            val calc = wbRoot.children(S, "calcPr").firstOrNull() ?: wbDoc.createElementNS(S, wbRoot.prefixed("calcPr")).also { cp ->
                val order = listOf("oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr", "smartTagTypes",
                    "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst")
                val before = generateSequence(wbRoot.firstChild) { it.nextSibling }.firstOrNull { it is Element && it.localName in order }
                wbRoot.insertBefore(cp, before)
            }
            calc.setAttribute("fullCalcOnLoad", "1")
            val replace = HashMap<String, ByteArray>()
            replace[sheet.path] = serialize(d)
            replace["xl/workbook.xml"] = serialize(wbDoc)
            val remove = HashSet<String>()
            if (zip.getEntry("xl/calcChain.xml") != null) {
                remove.add("xl/calcChain.xml")
                entry(zip, "[Content_Types].xml")?.let { b ->
                    val ct = parse(b)
                    ct.getElementsByTagName("Override").let { l -> (0 until l.length).map { l.item(it) as Element } }
                        .filter { it.getAttribute("PartName") == "/xl/calcChain.xml" }.forEach { it.parentNode.removeChild(it) }
                    replace["[Content_Types].xml"] = serialize(ct)
                }
                entry(zip, "xl/_rels/workbook.xml.rels")?.let { b ->
                    val rd = parse(b)
                    rd.getElementsByTagName("Relationship").let { l -> (0 until l.length).map { l.item(it) as Element } }
                        .filter { it.getAttribute("Target").endsWith("calcChain.xml") }.forEach { it.parentNode.removeChild(it) }
                    replace["xl/_rels/workbook.xml.rels"] = serialize(rd)
                }
            }
            rewrite(src, out, replace, remove)
        }
    }

    private fun Element.prefixed(local: String) = if (prefix.isNullOrEmpty()) local else "$prefix:$local"

    private fun findOrCreateRow(d: Document, sheetData: Element, row: Int): Element {
        val rows = sheetData.children(S, "row")
        rows.firstOrNull { it.getAttribute("r").toIntOrNull() == row }?.let { return it }
        val el = d.createElementNS(S, sheetData.prefixed("row"))
        el.setAttribute("r", row.toString())
        val after = rows.firstOrNull { (it.getAttribute("r").toIntOrNull() ?: 0) > row }
        sheetData.insertBefore(el, after)
        return el
    }

    /** Accepte « 1 250,5 », « 1250.5 », « 12 % ». */
    fun parseNumber(s: String): Double? {
        var t = s.trim().replace(" ", "").replace(" ", "").replace(" ", "")
        var percent = false
        if (t.endsWith("%")) { percent = true; t = t.dropLast(1) }
        if (t.count { it == ',' } == 1 && !t.contains('.')) t = t.replace(',', '.')
        if (!Regex("^-?\\d+(\\.\\d+)?([eE]-?\\d+)?$").matches(t)) return null
        val v = t.toDoubleOrNull() ?: return null
        return if (percent) v / 100 else v
    }

    fun numberString(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
}

/**
 * Petit calculateur de formules, pour afficher tout de suite un résultat : + - * / ^ et parenthèses,
 * références (A1), et les fonctions SOMME, MOYENNE, MIN, MAX, NB, ARRONDI, ABS, SI (noms français ou anglais).
 * Les formules plus complexes sont laissées à Excel, qui recalcule à l'ouverture.
 */
object Formula {
    private val FR_EN = linkedMapOf("SOMME" to "SUM", "MOYENNE" to "AVERAGE", "NB" to "COUNT", "ARRONDI" to "ROUND", "SI" to "IF")

    /** Formule saisie à la française (« SOMME(A1;A3) », « 2,5 ») → syntaxe enregistrée par Excel. */
    fun toExcel(f: String): String {
        var t = f.trim()
        for ((fr, en) in FR_EN) t = t.replace(Regex("(?i)\\b$fr\\s*\\("), "$en(")
        if (t.contains(';')) {
            t = t.replace(Regex("(\\d),(\\d)"), "$1.$2").replace(';', ',')
        }
        return t
    }

    /** Syntaxe Excel → affichage à la française pour la modification. */
    fun toFrench(f: String): String {
        var t = f
        for ((fr, en) in FR_EN) t = t.replace(Regex("(?i)\\b$en\\s*\\("), "$fr(")
        return t.replace(',', ';').replace(Regex("(\\d)\\.(\\d)"), "$1,$2")
    }

    fun computeAll(cells: Map<String, OfficeEdit.Cell>): MutableMap<String, Double> {
        val values = HashMap<String, Double>()
        cells.forEach { (r, c) -> if (c.formula == null) OfficeEdit.parseNumber(c.value)?.let { values[r] = it } }
        val formulas = cells.filter { it.value.formula != null }
        repeat(6) {
            var changed = false
            for ((r, c) in formulas) {
                val v = runCatching { Parser(c.formula!!, values).parse() }.getOrNull() ?: continue
                if (v.isNaN() || v.isInfinite()) continue
                if (values[r] != v) { values[r] = v; changed = true }
            }
            if (!changed) return values
        }
        return values
    }

    private class Parser(val s: String, val values: Map<String, Double>) {
        var i = 0
        fun parse(): Double { val v = expr(); skip(); if (i != s.length) error("fin"); return v }
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): Double {
            var v = term()
            while (true) { skip(); when { eat('+') -> v += term(); eat('-') -> v -= term(); else -> return v } }
        }
        fun term(): Double {
            var v = power()
            while (true) { skip(); when { eat('*') -> v *= power(); eat('/') -> v /= power(); else -> return v } }
        }
        fun power(): Double { val b = unary(); skip(); return if (eat('^')) Math.pow(b, power()) else b }
        fun unary(): Double { skip(); return if (eat('-')) -unary() else if (eat('+')) unary() else atom() }
        fun eat(c: Char): Boolean { if (i < s.length && s[i] == c) { i++; return true }; return false }
        fun atom(): Double {
            skip()
            if (eat('(')) { val v = expr(); skip(); if (!eat(')')) error(")"); return v }
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '$')) i++
            val tok = s.substring(start, i)
            if (tok.isEmpty()) error("vide")
            skip()
            if (i < s.length && s[i] == '(') { i++; return call(tok.uppercase(Locale.ROOT)) }
            tok.toDoubleOrNull()?.let { return it }
            val r = tok.replace("$", "").uppercase(Locale.ROOT)
            OfficeEdit.parseRef(r) ?: error("réf")
            return values[r] ?: 0.0
        }
        fun args(): List<List<Double>> {
            val out = ArrayList<List<Double>>()
            skip()
            if (eat(')')) return out
            while (true) {
                skip()
                val save = i
                val range = Regex("^\\$?[A-Za-z]{1,3}\\$?\\d+:\\$?[A-Za-z]{1,3}\\$?\\d+").find(s.substring(i))
                if (range != null) {
                    i += range.value.length
                    val (a, b) = range.value.replace("$", "").split(':')
                    val (c1, r1) = OfficeEdit.parseRef(a)!!; val (c2, r2) = OfficeEdit.parseRef(b)!!
                    val list = ArrayList<Double>()
                    for (rr in minOf(r1, r2)..maxOf(r1, r2)) for (cc in minOf(c1, c2)..maxOf(c1, c2)) values[OfficeEdit.ref(cc, rr)]?.let { list.add(it) }
                    out.add(list)
                } else { i = save; out.add(listOf(expr())) }
                skip()
                if (eat(',')) continue
                if (eat(')')) return out
                error("args")
            }
        }
        fun call(name: String): Double {
            if (name == "SI" || name == "IF") {
                val a = args().map { it.firstOrNull() ?: 0.0 }
                return if ((a.getOrNull(0) ?: 0.0) != 0.0) a.getOrElse(1) { 1.0 } else a.getOrElse(2) { 0.0 }
            }
            val all = args()
            val flat = all.flatten()
            return when (name) {
                "SOMME", "SUM" -> flat.sum()
                "MOYENNE", "AVERAGE" -> if (flat.isEmpty()) error("vide") else flat.average()
                "MIN" -> flat.minOrNull() ?: 0.0
                "MAX" -> flat.maxOrNull() ?: 0.0
                "NB", "COUNT" -> flat.size.toDouble()
                "ABS" -> kotlin.math.abs(flat.first())
                "ARRONDI", "ROUND" -> {
                    val d = all.getOrNull(1)?.firstOrNull()?.toInt() ?: 0
                    java.math.BigDecimal(flat.first()).setScale(d, java.math.RoundingMode.HALF_UP).toDouble()
                }
                else -> error("fonction inconnue")
            }
        }
    }
}
