package com.ufuk.kitap.epub

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Açılmış (zip'ten çıkarılmış) bir EPUB'un okunmaya hazır hali. */
class EpubBook(
    val title: String,
    val author: String,
    val spine: List<File>,
    val chapterTitles: List<String>,
    val toc: List<Pair<String, Int>>, // (başlık, spine sırası)
    val cover: File?,
)

object Epub {

    fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(dest, e.name)
                // zip içinden klasör dışına yazmayı engelle
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
            }
        }
    }

    private fun local(name: String) = name.substringAfter(':')

    private fun resolve(base: File, href: String): File {
        val clean = Uri.decode(href.substringBefore('#'))
        return File(base, clean).canonicalFile
    }

    fun open(dir: File): EpubBook {
        // 1) container.xml -> OPF yolu
        val container = File(dir, "META-INF/container.xml")
        var opfPath: String? = null
        if (container.exists()) {
            parse(container) { p ->
                if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "rootfile" && opfPath == null) {
                    opfPath = attr(p, "full-path")
                }
            }
        }
        val opf = opfPath?.let { File(dir, it) }?.takeIf { it.exists() }
            ?: dir.walkTopDown().firstOrNull { it.extension.equals("opf", true) }
            ?: error("EPUB içinde içerik dosyası (OPF) bulunamadı")
        val opfDir = opf.parentFile ?: dir

        // 2) OPF: başlık, yazar, manifest, spine
        var title = ""
        var author = ""
        val manifest = HashMap<String, Triple<String, String, String>>() // id -> (href, mediaType, properties)
        val spineIds = ArrayList<String>()
        var coverId: String? = null
        var tocId: String? = null
        var textTarget: String? = null
        parse(opf) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    when (local(p.name)) {
                        "title" -> textTarget = if (title.isEmpty()) "title" else null
                        "creator" -> textTarget = if (author.isEmpty()) "creator" else null
                        "item" -> {
                            val id = attr(p, "id") ?: ""
                            manifest[id] = Triple(attr(p, "href") ?: "", attr(p, "media-type") ?: "", attr(p, "properties") ?: "")
                        }
                        "itemref" -> {
                            val linear = attr(p, "linear")
                            attr(p, "idref")?.let { if (linear != "no") spineIds.add(it) }
                        }
                        "spine" -> tocId = attr(p, "toc")
                        "meta" -> if (attr(p, "name") == "cover") coverId = attr(p, "content")
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        if (textTarget == "title") title = t
                        if (textTarget == "creator") author = t
                    }
                }
                XmlPullParser.END_TAG -> textTarget = null
            }
        }

        val spine = spineIds.mapNotNull { manifest[it] }.map { resolve(opfDir, it.first) }.filter { it.exists() }
        val coverHref = manifest.values.firstOrNull { it.third.contains("cover-image") }?.first
            ?: coverId?.let { manifest[it]?.first }
            ?: manifest.entries.firstOrNull { it.key.contains("cover", true) && it.value.second.startsWith("image") }?.value?.first
        val cover = coverHref?.let { resolve(opfDir, it) }?.takeIf { it.exists() }

        // 3) İçindekiler: önce EPUB3 nav, yoksa EPUB2 ncx
        val toc = ArrayList<Pair<String, Int>>()
        val navHref = manifest.values.firstOrNull { it.third.split(' ').contains("nav") }?.first
        val ncxHref = tocId?.let { manifest[it]?.first }
            ?: manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }?.first
        try {
            if (navHref != null) {
                val nav = resolve(opfDir, navHref)
                readNav(nav, spine, toc)
            }
            if (toc.isEmpty() && ncxHref != null) {
                val ncx = resolve(opfDir, ncxHref)
                readNcx(ncx, spine, toc)
            }
        } catch (_: Exception) {
        }

        val titles = spine.indices.map { i ->
            toc.firstOrNull { it.second == i }?.first ?: "Bölüm ${i + 1}"
        }
        return EpubBook(title.ifBlank { opf.nameWithoutExtension }, author, spine, titles, toc, cover)
    }

    private fun readNav(nav: File, spine: List<File>, out: MutableList<Pair<String, Int>>) {
        if (!nav.exists()) return
        val base = nav.parentFile ?: return
        var inToc = false
        var href: String? = null
        val sb = StringBuilder()
        parse(nav) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val n = local(p.name)
                    if (n == "nav") {
                        val type = attr(p, "type") ?: ""
                        inToc = type.contains("toc") || (type.isEmpty() && out.isEmpty())
                    }
                    if (inToc && n == "a") { href = attr(p, "href"); sb.setLength(0) }
                }
                XmlPullParser.TEXT -> if (href != null) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = local(p.name)
                    if (inToc && n == "a" && href != null) {
                        val f = resolve(base, href!!)
                        val idx = spine.indexOf(f)
                        val label = sb.toString().replace(Regex("\\s+"), " ").trim()
                        if (idx >= 0 && label.isNotEmpty()) out.add(label to idx)
                        href = null
                    }
                    if (n == "nav") inToc = false
                }
            }
        }
    }

    private fun readNcx(ncx: File, spine: List<File>, out: MutableList<Pair<String, Int>>) {
        if (!ncx.exists()) return
        val base = ncx.parentFile ?: return
        var label: String? = null
        var inText = false
        parse(ncx) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "navPoint" -> label = null
                    "text" -> inText = true
                    "content" -> {
                        val src = attr(p, "src")
                        if (src != null && label != null) {
                            val idx = spine.indexOf(resolve(base, src))
                            if (idx >= 0) out.add(label!!.trim() to idx)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inText && label == null) label = p.text
                XmlPullParser.END_TAG -> if (local(p.name) == "text") inText = false
            }
        }
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (local(p.getAttributeName(i)) == name) return p.getAttributeValue(i)
        }
        return null
    }

    private fun parse(file: File, onEvent: (XmlPullParser) -> Unit) {
        file.inputStream().buffered().use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            try {
                p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
            } catch (_: Exception) {
            }
            p.setInput(input, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                onEvent(p)
                ev = try {
                    p.next()
                } catch (e: Exception) {
                    XmlPullParser.END_DOCUMENT
                }
            }
        }
    }
}
