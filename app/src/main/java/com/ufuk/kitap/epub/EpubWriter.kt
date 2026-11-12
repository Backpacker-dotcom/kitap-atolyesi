package com.ufuk.kitap.epub

import com.ufuk.kitap.data.Manuscript
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Editördeki basit yazım kuralları:
 *  # Ara başlık      ## Küçük başlık
 *  **kalın**  *italik*
 *  ***  (sahne arası)
 *  ![açıklama](images/dosya.jpg)  (tek satırda görsel)
 * Her dolu satır bir paragraftır.
 */
object Markup {
    val imageLine = Regex("^!\\[(.*?)]\\((.+?)\\)$")

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun inline(s: String): String {
        var t = esc(s)
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "<em>$1</em>")
        return t
    }

    fun toXhtml(body: String): String {
        val sb = StringBuilder()
        body.lines().forEach { raw ->
            val l = raw.trim()
            when {
                l.isEmpty() -> {}
                l == "***" || l == "* * *" -> sb.append("<hr class=\"scene\"/>\n")
                l.startsWith("## ") -> sb.append("<h3>").append(inline(l.removePrefix("## "))).append("</h3>\n")
                l.startsWith("# ") -> sb.append("<h2>").append(inline(l.removePrefix("# "))).append("</h2>\n")
                imageLine.matches(l) -> {
                    val m = imageLine.find(l)!!
                    sb.append("<div class=\"img\"><img src=\"").append(esc(m.groupValues[2])).append("\" alt=\"")
                        .append(esc(m.groupValues[1])).append("\"/></div>\n")
                }
                else -> sb.append("<p>").append(inline(l)).append("</p>\n")
            }
        }
        return sb.toString()
    }

    fun imagesUsed(body: String): List<String> =
        body.lines().mapNotNull { imageLine.find(it.trim())?.groupValues?.get(2) }
            .filter { it.startsWith("images/") }.map { it.removePrefix("images/") }
}

object EpubWriter {

    private const val CSS = """
body { font-family: serif; line-height: 1.55; margin: 0 4%; }
h1 { text-align: center; margin: 2em 0 1em; font-size: 1.6em; }
h2 { margin: 1.4em 0 .6em; font-size: 1.25em; }
h3 { margin: 1.2em 0 .5em; font-size: 1.1em; }
p { text-indent: 1.3em; margin: 0 0 .35em; text-align: justify; }
hr.scene { border: 0; text-align: center; margin: 1.2em 0; }
hr.scene:after { content: "* * *"; }
div.img { text-align: center; margin: 1em 0; }
div.img img { max-width: 100%; height: auto; }
.titlepage { text-align: center; margin-top: 25%; }
.titlepage .author { margin-top: 1.5em; font-size: 1.2em; }
.titlepage .desc { margin-top: 2em; font-style: italic; text-indent: 0; text-align: center; }
.cover { text-align: center; margin: 0; padding: 0; }
.cover img { max-width: 100%; max-height: 100%; }
"""

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    private fun page(title: String, lang: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
<head><meta charset="utf-8"/><title>${Markup.esc(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
$body
</body>
</html>
"""

    /** El yazmasından EPUB 3 dosyası üretir (eski okuyucular için toc.ncx de eklenir). */
    fun write(m: Manuscript, imagesDir: File, out: File) {
        val lang = m.language.ifBlank { "tr" }
        val title = m.title.ifBlank { "Adsız Kitap" }
        val uid = "urn:uuid:" + m.id
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        val usedImages = LinkedHashSet<String>()
        m.chapters.forEach { usedImages.addAll(Markup.imagesUsed(it.body)) }
        val hasCover = m.cover.isNotBlank() && File(imagesDir, m.cover).exists()
        if (hasCover) usedImages.add(m.cover)
        val images = usedImages.filter { File(imagesDir, it).exists() }

        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            // mimetype: ilk dosya ve sıkıştırılmamış olmalı
            val mt = "application/epub+zip".toByteArray()
            val e = ZipEntry("mimetype")
            e.method = ZipEntry.STORED
            e.size = mt.size.toLong()
            e.compressedSize = mt.size.toLong()
            e.crc = CRC32().apply { update(mt) }.value
            zip.putNextEntry(e); zip.write(mt); zip.closeEntry()

            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }

            put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
""")
            put("OEBPS/style.css", CSS)

            val manifest = StringBuilder()
            val spine = StringBuilder()
            val navItems = StringBuilder()
            val ncxItems = StringBuilder()

            manifest.append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            manifest.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")

            if (hasCover) {
                put("OEBPS/cover.xhtml", page(title, lang, "<div class=\"cover\"><img src=\"images/${Markup.esc(m.cover)}\" alt=\"Kapak\"/></div>"))
                manifest.append("<item id=\"coverpage\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("<itemref idref=\"coverpage\"/>\n")
            }

            val tp = StringBuilder("<div class=\"titlepage\"><h1>${Markup.esc(title)}</h1>")
            if (m.author.isNotBlank()) tp.append("<p class=\"author\">${Markup.esc(m.author)}</p>")
            if (m.description.isNotBlank()) tp.append("<p class=\"desc\">${Markup.esc(m.description)}</p>")
            tp.append("</div>")
            put("OEBPS/title.xhtml", page(title, lang, tp.toString()))
            manifest.append("<item id=\"titlepage\" href=\"title.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("<itemref idref=\"titlepage\"/>\n")

            m.chapters.forEachIndexed { i, c ->
                val n = i + 1
                val ct = c.title.ifBlank { "Bölüm $n" }
                val file = "chap_$n.xhtml"
                put("OEBPS/$file", page(ct, lang, "<h1>${Markup.esc(ct)}</h1>\n" + Markup.toXhtml(c.body)))
                manifest.append("<item id=\"c$n\" href=\"$file\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("<itemref idref=\"c$n\"/>\n")
                navItems.append("<li><a href=\"$file\">${Markup.esc(ct)}</a></li>\n")
                ncxItems.append("<navPoint id=\"np$n\" playOrder=\"$n\"><navLabel><text>${Markup.esc(ct)}</text></navLabel><content src=\"$file\"/></navPoint>\n")
            }

            images.forEachIndexed { i, name ->
                val props = if (hasCover && name == m.cover) " properties=\"cover-image\"" else ""
                val id = if (hasCover && name == m.cover) "cover-img" else "img$i"
                manifest.append("<item id=\"$id\" href=\"images/${Markup.esc(name)}\" media-type=\"${mime(name)}\"$props/>\n")
                zip.putNextEntry(ZipEntry("OEBPS/images/$name"))
                File(imagesDir, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            put("OEBPS/nav.xhtml", page("İçindekiler", lang, "<nav epub:type=\"toc\" id=\"toc\"><h1>İçindekiler</h1><ol>\n$navItems</ol></nav>"))
            put("OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="$uid"/></head>
<docTitle><text>${Markup.esc(title)}</text></docTitle>
<navMap>
$ncxItems</navMap>
</ncx>
""")
            val coverMeta = if (hasCover) "<meta name=\"cover\" content=\"cover-img\"/>\n" else ""
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">$uid</dc:identifier>
<dc:title>${Markup.esc(title)}</dc:title>
<dc:creator>${Markup.esc(m.author.ifBlank { "Bilinmeyen" })}</dc:creator>
<dc:language>$lang</dc:language>
${if (m.description.isNotBlank()) "<dc:description>${Markup.esc(m.description)}</dc:description>" else ""}
<meta property="dcterms:modified">$modified</meta>
$coverMeta</metadata>
<manifest>
$manifest</manifest>
<spine toc="ncx">
$spine</spine>
</package>
""")
        }
    }
}
