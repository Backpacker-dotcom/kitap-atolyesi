package com.ufuk.kitap.importers

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.ufuk.kitap.data.Chapter
import com.ufuk.kitap.data.Repo
import com.ufuk.kitap.util.Files
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** İçe aktarma sonucu: önerilen kitap adı ve bölümler. */
class Imported(val title: String, val chapters: List<Chapter>)

object Importers {

    private val chapterHead = Regex(
        "^\\s*((bölüm|BÖLÜM|Bölüm|kısım|KISIM|Kısım|chapter|CHAPTER|Chapter)\\s*[\\dIVXLCivxlc]+.*|(bir|iki|üç|dört|beş|altı|yedi|sekiz|dokuz|on)[a-zçğıöşü]*\\s+(bölüm|BÖLÜM|Bölüm).*|#\\s+.+)\\s*$"
    )

    /** Düz metni bölümlere ayırır: "Bölüm 1", "Chapter 2", "# Başlık" satırlarından; bulamazsa ~3000 kelimede bir. */
    fun splitText(text: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val heads = lines.indices.filter { i -> lines[i].length < 90 && chapterHead.matches(lines[i]) }
        val result = ArrayList<Chapter>()
        if (heads.size >= 2) {
            val pre = lines.subList(0, heads[0]).joinToString("\n").trim()
            if (pre.isNotBlank()) result.add(Chapter(Repo.newId(), "Giriş", pre))
            heads.forEachIndexed { k, h ->
                val end = if (k + 1 < heads.size) heads[k + 1] else lines.size
                val title = lines[h].trim().removePrefix("#").trim()
                val body = lines.subList(h + 1, end).joinToString("\n").trim()
                result.add(Chapter(Repo.newId(), title, body))
            }
            return result
        }
        // Başlık yoksa: paragraf sınırında ~3000 kelimede bir böl
        val sb = StringBuilder()
        var words = 0
        lines.forEach { l ->
            sb.append(l).append('\n')
            words += l.split(Regex("\\s+")).count { it.isNotBlank() }
            if (words >= 3000 && l.isBlank()) {
                result.add(Chapter(Repo.newId(), "Bölüm ${result.size + 1}", sb.toString().trim()))
                sb.setLength(0); words = 0
            }
        }
        if (sb.isNotBlank() || result.isEmpty()) result.add(Chapter(Repo.newId(), "Bölüm ${result.size + 1}", sb.toString().trim()))
        return result
    }

    fun fromTxt(file: File, name: String): Imported {
        val text = Files.decodeText(file.readBytes())
        return Imported(name.substringBeforeLast('.'), splitText(text))
    }

    // ---------------- Word (.docx) ----------------

    private class Run(val text: String, val bold: Boolean, val italic: Boolean)

    fun fromDocx(file: File, name: String, imagesDir: File): Imported {
        var documentXml: ByteArray? = null
        var relsXml: ByteArray? = null
        val media = HashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                when {
                    e.name == "word/document.xml" -> documentXml = zis.readBytes()
                    e.name == "word/_rels/document.xml.rels" -> relsXml = zis.readBytes()
                    e.name.startsWith("word/media/") -> media[e.name.removePrefix("word/")] = zis.readBytes()
                }
            }
        }
        val doc = documentXml ?: error("Bu bir Word (.docx) dosyası değil gibi görünüyor")

        // ilişki id -> media/image1.png
        val rels = HashMap<String, String>()
        relsXml?.let { bytes ->
            pull(bytes) { p ->
                if (p.eventType == XmlPullParser.START_TAG && p.name.endsWith("Relationship")) {
                    val id = attr(p, "Id"); val target = attr(p, "Target")
                    if (id != null && target != null) rels[id] = target.removePrefix("/word/").removePrefix("../word/")
                }
            }
        }

        val chapters = ArrayList<Chapter>()
        var docTitle = ""
        var chTitle: String? = null
        val chBody = StringBuilder()
        fun flush() {
            if (chTitle != null || chBody.isNotBlank()) {
                chapters.add(Chapter(Repo.newId(), chTitle ?: (if (chapters.isEmpty()) "Giriş" else "Bölüm ${chapters.size + 1}"), chBody.toString().trim()))
            }
            chTitle = null; chBody.setLength(0)
        }

        var style = ""
        val runs = ArrayList<Run>()
        var bold = false; var italic = false
        var inRPr = false; var inT = false
        val paraImages = ArrayList<String>()
        var imgCounter = 0
        imagesDir.mkdirs()

        fun endParagraph() {
            // aynı biçimli ardışık parçaları birleştir
            val merged = ArrayList<Run>()
            runs.forEach { r ->
                val last = merged.lastOrNull()
                if (last != null && last.bold == r.bold && last.italic == r.italic) merged[merged.size - 1] = Run(last.text + r.text, r.bold, r.italic)
                else merged.add(r)
            }
            val plain = merged.joinToString("") { it.text }.trim()
            val styled = merged.joinToString("") { r ->
                if (r.text.isBlank()) r.text else {
                    val lead = r.text.takeWhile { it == ' ' }; val trail = r.text.takeLastWhile { it == ' ' }
                    val core = r.text.trim()
                    val w = when {
                        r.bold && r.italic -> "***$core***"
                        r.bold -> "**$core**"
                        r.italic -> "*$core*"
                        else -> core
                    }
                    lead + w + trail
                }
            }.trim()
            val s = style.lowercase().replace(" ", "")
            val level = Regex("(heading|balk|başlık|baslik)(\\d)").find(s)?.groupValues?.get(2)?.toIntOrNull()
            when {
                (s == "title" || s.startsWith("konubal")) && plain.isNotEmpty() && docTitle.isEmpty() -> docTitle = plain
                level == 1 && plain.isNotEmpty() -> { flush(); chTitle = plain }
                level != null && level >= 2 && plain.isNotEmpty() -> chBody.append(if (level == 2) "# " else "## ").append(plain).append('\n')
                styled.isNotEmpty() -> chBody.append(styled).append('\n')
            }
            paraImages.forEach { chBody.append("![](images/").append(it).append(")\n") }
            runs.clear(); paraImages.clear(); style = ""
        }

        pull(doc) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { runs.clear(); style = "" }
                    "w:pStyle" -> style = attr(p, "w:val") ?: ""
                    "w:r" -> { bold = false; italic = false }
                    "w:rPr" -> inRPr = true
                    "w:b" -> if (inRPr) bold = attr(p, "w:val").let { it == null || (it != "0" && it != "false") }
                    "w:i" -> if (inRPr) italic = attr(p, "w:val").let { it == null || (it != "0" && it != "false") }
                    "w:t" -> inT = true
                    "w:tab" -> runs.add(Run(" ", bold, italic))
                    "a:blip" -> {
                        val rid = attr(p, "r:embed")
                        val target = rid?.let { rels[it] }
                        val bytes = target?.let { media[it] }
                        if (bytes != null) {
                            val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Throwable) { null }
                            if (bmp != null) {
                                val fname = "img_${System.currentTimeMillis()}_${imgCounter++}.jpg"
                                Files.saveScaledJpeg(bmp, File(imagesDir, fname))
                                paraImages.add(fname)
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inT) runs.add(Run(p.text ?: "", bold, italic))
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inT = false
                    "w:rPr" -> inRPr = false
                    "w:p" -> endParagraph()
                }
            }
        }
        flush()

        // Hiç başlık yoksa düz metin gibi böl
        val result = if (chapters.size == 1 && chapters[0].title == "Giriş") {
            splitText(chapters[0].body)
        } else chapters
        return Imported(docTitle.ifBlank { name.substringBeforeLast('.') }, result.ifEmpty { listOf(Chapter(Repo.newId(), "Bölüm 1", "")) })
    }

    // ---------------- PDF (metin) ----------------

    fun fromPdf(ctx: Context, file: File, name: String): Imported {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val sb = StringBuilder()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            val n = doc.numberOfPages
            for (i in 1..n) {
                stripper.startPage = i
                stripper.endPage = i
                sb.append(joinLines(stripper.getText(doc))).append("\n\n")
            }
            val t = doc.documentInformation?.title
            if (!t.isNullOrBlank()) return Imported(t, splitText(sb.toString()))
        }
        if (sb.isBlank()) error("Bu PDF'de seçilebilir metin yok (taranmış sayfa olabilir)")
        return Imported(name.substringBeforeLast('.'), splitText(sb.toString()))
    }

    /** PDF'deki satır sonlarını paragraflara çevirir. */
    private fun joinLines(page: String): String {
        val lines = page.replace("\r", "").split('\n').map { it.trimEnd() }
        val nonEmpty = lines.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return ""
        val avg = nonEmpty.sumOf { it.length } / nonEmpty.size
        val out = StringBuilder()
        val cur = StringBuilder()
        fun end() { if (cur.isNotBlank()) out.append(cur.toString().trim()).append('\n'); cur.setLength(0) }
        lines.forEachIndexed { i, l ->
            if (l.isBlank()) { end(); return@forEachIndexed }
            if (chapterHead.matches(l)) { end(); out.append(l.trim()).append('\n'); return@forEachIndexed }
            if (cur.isNotEmpty() && cur.endsWith("-") && l.firstOrNull()?.isLowerCase() == true) {
                cur.setLength(cur.length - 1); cur.append(l.trim())
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(l.trim())
            }
            val last = l.trimEnd().lastOrNull()
            val short = l.length < avg * 0.7
            val next = lines.getOrNull(i + 1)
            if (short || (last != null && last in ".!?:\"”»" && (next == null || next.length < avg * 0.9))) end()
        }
        end()
        return out.toString()
    }

    // ---------------- yardımcılar ----------------

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == name) return p.getAttributeValue(i)
        return null
    }

    private fun pull(bytes: ByteArray, onEvent: (XmlPullParser) -> Unit) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(bytes), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            onEvent(p)
            ev = p.next()
        }
    }
}
