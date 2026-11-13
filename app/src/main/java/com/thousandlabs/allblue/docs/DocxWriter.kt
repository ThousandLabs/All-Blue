package com.thousandlabs.allblue.docs

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Crée un document Word simple (.docx) à partir de pages de texte : PDF vers Word. */
object DocxWriter {

    private fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '\t' -> append(' ')
            ch.code < 0x20 -> Unit // caractères de contrôle interdits en XML
            else -> append(ch)
        }
    }

    /** [pages] : texte de chaque page, déjà remis en paragraphes (séparés par une ligne vide). */
    fun write(out: File, title: String, pages: List<String>) {
        val body = StringBuilder()
        body.append("<w:p><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr><w:t xml:space=\"preserve\">")
            .append(esc(title)).append("</w:t></w:r></w:p>")
        pages.forEachIndexed { i, page ->
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            page.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
                body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr>")
                para.split('\n').forEachIndexed { k, line ->
                    if (k > 0) body.append("<w:r><w:br/></w:r>")
                    body.append("<w:r><w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t></w:r>")
                }
                body.append("</w:p>")
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="fr-FR"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>"""
        val types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, content: String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", types)
            put("_rels/.rels", rels)
            put("word/document.xml", document)
            put("word/_rels/document.xml.rels", docRels)
            put("word/styles.xml", styles)
        }
    }
}
