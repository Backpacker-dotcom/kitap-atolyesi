package com.ufuk.kitap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ufuk.kitap.epub.Epub
import com.ufuk.kitap.util.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kitap", Context.MODE_PRIVATE)
    fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun setStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun int(k: String, d: Int): Int = sp.getInt(k, d)
    fun setInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun long(k: String, d: Long = 0L): Long = sp.getLong(k, d)
    fun setLong(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    fun bool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    fun setBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    fun set(k: String): MutableSet<String> = HashSet(sp.getStringSet(k, emptySet()) ?: emptySet())
    fun setSet(k: String, v: Set<String>) = sp.edit().putStringSet(k, v).apply()
}

class Repo(private val ctx: Context, val prefs: Prefs) {
    val books = MutableStateFlow<List<Book>>(emptyList())
    val manuscripts = MutableStateFlow<List<Manuscript>>(emptyList())

    private val booksRoot = File(ctx.filesDir, "books")
    private val msRoot = File(ctx.filesDir, "manuscripts")
    private val libFile = File(ctx.filesDir, "library.json")

    fun load() {
        val list = ArrayList<Book>()
        if (libFile.exists()) {
            try {
                val arr = JSONArray(libFile.readText())
                for (i in 0 until arr.length()) list.add(Book.fromJson(arr.getJSONObject(i)))
            } catch (_: Exception) {
            }
        }
        books.value = list.filter { bookFile(it).exists() }
        val ms = ArrayList<Manuscript>()
        msRoot.listFiles()?.forEach { d ->
            val f = File(d, "manuscript.json")
            if (f.exists()) try {
                ms.add(Manuscript.fromJson(JSONObject(f.readText())))
            } catch (_: Exception) {
            }
        }
        manuscripts.value = ms.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    private fun saveBooks(list: List<Book>) {
        books.value = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(libFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(libFile)
    }

    // ---------- Kitaplık ----------

    fun bookDir(id: String) = File(booksRoot, id)
    fun bookFile(b: Book) = File(bookDir(b.id), "book.${b.type}")
    fun coverFile(id: String) = File(bookDir(id), "cover.jpg")
    fun epubDir(id: String) = File(bookDir(id), "x")
    fun book(id: String) = books.value.firstOrNull { it.id == id }

    private fun detectType(name: String, head: ByteArray): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "epub") return "epub"
        if (ext == "pdf") return "pdf"
        if (head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte()) return "pdf"
        if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return "epub"
        return "txt"
    }

    /** Telefondaki bir dosyayı kitaplığa kopyalar. Aynı dosya zaten varsa onu döndürür. */
    fun importUri(uri: Uri): Book {
        val name = Files.displayName(ctx, uri)
        val tmp = File(ctx.cacheDir, "in_" + System.nanoTime())
        Files.copyUriTo(ctx, uri, tmp)
        try {
            return importFile(tmp, name, null)
        } finally {
            tmp.delete()
        }
    }

    /** Hazır bir dosyayı kitaplığa ekler. remote: eşitlemeden gelen bilgiler (ilerleme vb.). */
    fun importFile(src: File, name: String, remote: Book?): Book {
        val id = remote?.id ?: Files.sha1(src)
        book(id)?.let { return it }
        val head = ByteArray(8)
        src.inputStream().use { it.read(head) }
        val type = remote?.type ?: detectType(name, head)
        val dir = bookDir(id)
        dir.mkdirs()
        val dest = File(dir, "book.$type")
        src.copyTo(dest, overwrite = true)

        var title = name.substringBeforeLast('.').replace('_', ' ')
        var author = ""
        try {
            when (type) {
                "epub" -> {
                    Epub.unzip(dest, epubDir(id))
                    val eb = Epub.open(epubDir(id))
                    title = eb.title.ifBlank { title }
                    author = eb.author
                    eb.cover?.let { c -> Files.decodeSampled(c, 600)?.let { Files.saveScaledJpeg(it, coverFile(id), 600) } }
                }
                "pdf" -> renderPdfCover(dest, coverFile(id))
            }
        } catch (e: Exception) {
            if (type == "epub") {
                dir.deleteRecursively()
                throw IllegalStateException("EPUB dosyası okunamadı: ${e.message}")
            }
        }
        val b = Book(
            id = id,
            title = remote?.title?.ifBlank { null } ?: title,
            author = remote?.author?.ifBlank { null } ?: author,
            type = type,
            size = dest.length(),
            addedAt = remote?.addedAt ?: System.currentTimeMillis(),
            position = remote?.position ?: "",
            percent = remote?.percent ?: 0f,
            posUpdatedAt = remote?.posUpdatedAt ?: 0L,
        )
        saveBooks(books.value + b)
        return b
    }

    private fun renderPdfCover(pdf: File, out: File) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return
                r.openPage(0).use { p ->
                    val w = 500
                    val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Files.saveScaledJpeg(bmp, out, 500, 80)
                }
            }
        }
    }

    fun deleteBook(id: String, remember: Boolean = true) {
        bookDir(id).deleteRecursively()
        saveBooks(books.value.filter { it.id != id })
        if (remember) addPendingDelete("book:$id")
    }

    fun updateProgress(id: String, position: String, percent: Float) {
        val now = System.currentTimeMillis()
        saveBooks(books.value.map {
            if (it.id == id) it.copy(position = position, percent = percent, posUpdatedAt = now, lastOpened = now) else it
        })
    }

    fun replaceBookMeta(b: Book) = saveBooks(books.value.map { if (it.id == b.id) b else it })

    // ---------- El yazmaları ----------

    fun msDir(id: String) = File(msRoot, id)
    fun imagesDir(id: String) = File(msDir(id), "images")
    fun manuscript(id: String) = manuscripts.value.firstOrNull { it.id == id }

    fun newManuscript(title: String = "Yeni Kitap", chapters: List<Chapter> = listOf(Chapter(newId(), "Bölüm 1", ""))): Manuscript {
        val now = System.currentTimeMillis()
        val m = Manuscript(
            id = UUID.randomUUID().toString(),
            title = title,
            author = prefs.str("author_name"),
            chapters = chapters,
            createdAt = now,
            updatedAt = now,
        )
        saveManuscript(m, touch = false)
        return m
    }

    @Synchronized
    fun saveManuscript(m: Manuscript, touch: Boolean = true) {
        val mm = if (touch) m.copy(updatedAt = System.currentTimeMillis()) else m
        val dir = msDir(mm.id)
        dir.mkdirs()
        val f = File(dir, "manuscript.json")
        val tmp = File(dir, "manuscript.json.tmp")
        tmp.writeText(mm.toJson().toString())
        tmp.renameTo(f)
        manuscripts.value = (manuscripts.value.filter { it.id != mm.id } + mm).sortedByDescending { it.updatedAt }
    }

    fun deleteManuscript(id: String, remember: Boolean = true) {
        msDir(id).deleteRecursively()
        manuscripts.value = manuscripts.value.filter { it.id != id }
        if (remember) addPendingDelete("ms:$id")
    }

    // ---------- Eşitleme için silinenler listesi ----------

    private fun addPendingDelete(key: String) {
        if (prefs.str("fb_uid").isEmpty()) return
        val s = prefs.set("pending_deletes")
        s.add(key)
        prefs.setSet("pending_deletes", s)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
