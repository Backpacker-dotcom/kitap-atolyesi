package com.ufuk.kitap.net

import android.content.Context
import com.ufuk.kitap.data.Book
import com.ufuk.kitap.data.Manuscript
import com.ufuk.kitap.data.Repo
import com.ufuk.kitap.net.Firebase.Companion.bytesOf
import com.ufuk.kitap.net.Firebase.Companion.dbl
import com.ufuk.kitap.net.Firebase.Companion.long
import com.ufuk.kitap.net.Firebase.Companion.str
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Firestore yapısı (users/<uid>/ altında):
 *  books/<kitapId>        kitap bilgisi + kaldığın yer
 *  manuscripts/<id>       yazdığın kitabın bilgisi, görsel listesi
 *  blobs/<dosyaId>_<n>    dosya parçaları (600 KB'lık), ücretsiz planda Storage gerekmesin diye
 */
class Sync(private val ctx: Context, private val repo: Repo, private val fb: Firebase) {

    private val chunk = 600 * 1024
    private val maxBookBytes = 30L * 1024 * 1024

    @Volatile var running = false
        private set

    fun syncAll(status: (String) -> Unit): String {
        check(fb.isConfigured && fb.isSignedIn) { "Eşitleme için Ayarlar'dan Firebase'e giriş yap" }
        if (running) return "Eşitleme zaten sürüyor"
        running = true
        try {
            var up = 0; var down = 0; var prog = 0
            status("Silinenler işleniyor…")
            processDeletes()

            // ---------- Ayarlar: Gemini anahtarı bir cihaza girilince diğerine de gelsin ----------
            try {
                val rk = fb.get(fb.userPath("settings/app"))?.str("geminiKey") ?: ""
                val lk = repo.prefs.str("gemini_key")
                if (lk.isEmpty() && rk.isNotEmpty()) repo.prefs.setStr("gemini_key", rk)
                else if (lk.isNotEmpty() && lk != rk) {
                    val b = Firebase.Batch()
                    fb.set(b, fb.userPath("settings/app"), JSONObject().put("geminiKey", Firebase.s(lk)))
                    fb.commit(b)
                }
            } catch (_: Exception) {
            }

            // ---------- Kitaplar ----------
            status("Kitaplar karşılaştırılıyor…")
            val remoteBooks = fb.list(fb.userPath("books")).associate { it.first to it.second }
            for (b in repo.books.value) {
                val r = remoteBooks[b.id]
                val file = repo.bookFile(b)
                val canUpload = file.exists() && file.length() <= maxBookBytes
                if (r == null) {
                    var chunks = 0L
                    if (canUpload) {
                        status("Yükleniyor: ${b.title}")
                        chunks = uploadBlob("b_${b.id}", file.readBytes()).toLong()
                        up++
                    }
                    putBook(b, chunks)
                } else {
                    val rChunks = r.long("chunks")
                    val rPos = r.long("posUpdatedAt")
                    if (rChunks == 0L && canUpload) {
                        status("Yükleniyor: ${b.title}")
                        val c = uploadBlob("b_${b.id}", file.readBytes()).toLong()
                        putBook(if (b.posUpdatedAt >= rPos) b else fromRemote(b.id, r), c)
                        up++
                    } else if (b.posUpdatedAt > rPos) {
                        putBook(b, rChunks); prog++
                    }
                    if (rPos > b.posUpdatedAt) {
                        val rb = fromRemote(b.id, r)
                        repo.replaceBookMeta(b.copy(position = rb.position, percent = rb.percent, posUpdatedAt = rb.posUpdatedAt))
                        prog++
                    }
                }
            }
            val localIds = repo.books.value.map { it.id }.toSet()
            for ((id, r) in remoteBooks) {
                if (id in localIds) continue
                val chunks = r.long("chunks").toInt()
                if (chunks <= 0) continue
                val rb = fromRemote(id, r)
                status("İndiriliyor: ${rb.title}")
                val tmp = File(ctx.cacheDir, "dl_$id")
                downloadBlob("b_$id", chunks, tmp)
                try {
                    repo.importFile(tmp, rb.title + "." + rb.type, rb)
                    down++
                } finally {
                    tmp.delete()
                }
            }

            // ---------- Yazdığın kitaplar ----------
            status("Yazdığın kitaplar karşılaştırılıyor…")
            val remoteMs = fb.list(fb.userPath("manuscripts")).associate { it.first to it.second }
            for (m in repo.manuscripts.value) {
                val r = remoteMs[m.id]
                if (r == null || m.updatedAt > r.long("updatedAt")) {
                    status("Yükleniyor: ${m.title}")
                    uploadManuscript(m, if (r != null) imageCounts(r) else emptyMap())
                    up++
                }
            }
            for ((id, r) in remoteMs) {
                val local = repo.manuscript(id)
                if (local == null || r.long("updatedAt") > local.updatedAt) {
                    status("İndiriliyor: ${r.str("title")}")
                    downloadManuscript(id, r)
                    down++
                }
            }
            repo.prefs.setLong("last_sync", System.currentTimeMillis())
            return "Eşitlendi • $up gönderildi, $down alındı" + (if (prog > 0) ", $prog ilerleme güncellendi" else "")
        } finally {
            running = false
        }
    }

    /** Sadece okuma ilerlemesini hızlıca gönderir (kitap kapatılınca). */
    fun pushProgress(b: Book) {
        if (!fb.isConfigured || !fb.isSignedIn) return
        val r = fb.get(fb.userPath("books/${b.id}"))
        if (r != null && r.long("posUpdatedAt") >= b.posUpdatedAt) return
        putBook(b, r?.long("chunks") ?: 0L)
    }

    private fun fromRemote(id: String, r: JSONObject) = Book(
        id = id,
        title = r.str("title"),
        author = r.str("author"),
        type = r.str("type").ifBlank { "epub" },
        size = r.long("size"),
        addedAt = r.long("addedAt"),
        position = r.str("position"),
        percent = r.dbl("percent").toFloat(),
        posUpdatedAt = r.long("posUpdatedAt"),
    )

    private fun putBook(b: Book, chunks: Long) {
        val f = JSONObject()
            .put("title", Firebase.s(b.title)).put("author", Firebase.s(b.author)).put("type", Firebase.s(b.type))
            .put("size", Firebase.i(b.size)).put("addedAt", Firebase.i(b.addedAt))
            .put("position", Firebase.s(b.position)).put("percent", Firebase.d(b.percent.toDouble()))
            .put("posUpdatedAt", Firebase.i(b.posUpdatedAt)).put("chunks", Firebase.i(chunks))
        val batch = Firebase.Batch()
        fb.set(batch, fb.userPath("books/${b.id}"), f)
        fb.commit(batch)
    }

    private fun uploadBlob(blobId: String, data: ByteArray): Int {
        var n = 0
        var off = 0
        do {
            val end = minOf(data.size, off + chunk)
            val batch = Firebase.Batch()
            fb.set(batch, fb.userPath("blobs/${blobId}_$n"), JSONObject().put("data", Firebase.bytes(data.copyOfRange(off, end))))
            fb.commit(batch)
            n++
            off = end
        } while (off < data.size)
        return n
    }

    private fun downloadBlob(blobId: String, chunks: Int, dest: File) {
        FileOutputStream(dest).use { out ->
            for (i in 0 until chunks) {
                val f = fb.get(fb.userPath("blobs/${blobId}_$i")) ?: error("Dosya parçası eksik ($blobId/$i)")
                out.write(f.bytesOf("data"))
            }
        }
    }

    private fun readBlob(blobId: String, chunks: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        for (i in 0 until chunks) {
            val f = fb.get(fb.userPath("blobs/${blobId}_$i")) ?: error("Dosya parçası eksik ($blobId/$i)")
            bos.write(f.bytesOf("data"))
        }
        return bos.toByteArray()
    }

    /** Uzak kayıttaki görsel -> parça sayısı */
    private fun imageCounts(r: JSONObject): Map<String, Int> {
        val counts = HashMap<String, Int>()
        r.str("imageChunks").split('\n').filter { it.contains(':') }.forEach {
            counts[it.substringBeforeLast(':')] = it.substringAfterLast(':').toIntOrNull() ?: 1
        }
        return counts
    }

    private fun uploadManuscript(m: Manuscript, remoteImages: Map<String, Int>) {
        val imgDir = repo.imagesDir(m.id)
        val images = imgDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name }?.sorted() ?: emptyList()
        val uploaded = ArrayList<String>()
        for (name in images) {
            val existing = remoteImages[name]
            if (existing == null) {
                val c = uploadBlob("mi_${m.id}_$name", File(imgDir, name).readBytes())
                uploaded.add("$name:$c")
            } else uploaded.add("$name:$existing")
        }
        val json = m.toJson().toString().toByteArray(Charsets.UTF_8)
        val chunks = uploadBlob("m_${m.id}", json)
        val f = JSONObject()
            .put("title", Firebase.s(m.title))
            .put("updatedAt", Firebase.i(m.updatedAt))
            .put("chunks", Firebase.i(chunks.toLong()))
            .put("images", Firebase.s(images.joinToString("\n")))
            .put("imageChunks", Firebase.s(uploaded.joinToString("\n")))
        val batch = Firebase.Batch()
        fb.set(batch, fb.userPath("manuscripts/${m.id}"), f)
        fb.commit(batch)
    }

    private fun downloadManuscript(id: String, r: JSONObject) {
        val json = String(readBlob("m_$id", r.long("chunks").toInt()), Charsets.UTF_8)
        val m = Manuscript.fromJson(JSONObject(json))
        val imgDir = repo.imagesDir(id)
        imgDir.mkdirs()
        val counts = imageCounts(r)
        r.str("images").split('\n').filter { it.isNotBlank() }.forEach { name ->
            val f = File(imgDir, name)
            if (!f.exists()) {
                try {
                    downloadBlob("mi_${id}_$name", counts[name] ?: 1, f)
                } catch (e: Exception) {
                    f.delete()
                }
            }
        }
        repo.saveManuscript(m, touch = false)
    }

    private fun processDeletes() {
        val pending = repo.prefs.set("pending_deletes")
        if (pending.isEmpty()) return
        for (key in pending.toList()) {
            try {
                val batch = Firebase.Batch()
                if (key.startsWith("book:")) {
                    val id = key.removePrefix("book:")
                    val r = fb.get(fb.userPath("books/$id"))
                    if (r != null) {
                        val c = r.long("chunks").toInt()
                        for (i in 0 until c) fb.delete(batch, fb.userPath("blobs/b_${id}_$i"))
                        fb.delete(batch, fb.userPath("books/$id"))
                    }
                } else if (key.startsWith("ms:")) {
                    val id = key.removePrefix("ms:")
                    val r = fb.get(fb.userPath("manuscripts/$id"))
                    if (r != null) {
                        for (i in 0 until r.long("chunks").toInt()) fb.delete(batch, fb.userPath("blobs/m_${id}_$i"))
                        imageCounts(r).forEach { (n, c) ->
                            for (i in 0 until c) fb.delete(batch, fb.userPath("blobs/mi_${id}_${n}_$i"))
                        }
                        fb.delete(batch, fb.userPath("manuscripts/$id"))
                    }
                }
                fb.commit(batch)
                pending.remove(key)
            } catch (_: Exception) {
            }
        }
        repo.prefs.setSet("pending_deletes", pending)
    }
}
