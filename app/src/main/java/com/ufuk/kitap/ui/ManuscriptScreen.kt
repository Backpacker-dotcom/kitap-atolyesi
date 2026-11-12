@file:OptIn(ExperimentalMaterial3Api::class)

package com.ufuk.kitap.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.App
import com.ufuk.kitap.data.Chapter
import com.ufuk.kitap.data.Manuscript
import com.ufuk.kitap.data.Repo
import com.ufuk.kitap.epub.EpubWriter
import com.ufuk.kitap.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ManuscriptScreen(app: App, id: String, onBack: () -> Unit, openChapter: (String) -> Unit, openReader: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by app.repo.manuscripts.collectAsState()
    val m = all.firstOrNull { it.id == id }
    if (m == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var title by remember { mutableStateOf(m.title) }
    var author by remember { mutableStateOf(m.author) }
    var desc by remember { mutableStateOf(m.description) }
    var busy by remember { mutableStateOf<String?>(null) }
    var deleteCh by remember { mutableStateOf<Chapter?>(null) }

    fun current() = app.repo.manuscript(id) ?: m
    fun save(mm: Manuscript) = app.repo.saveManuscript(mm)

    LaunchedEffect(title, author, desc) {
        delay(500)
        val c = current()
        if (c.title != title || c.author != author || c.description != desc) {
            save(c.copy(title = title, author = author, description = desc))
            if (author.isNotBlank()) app.prefs.setStr("author_name", author)
        }
    }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val name = "cover_${System.currentTimeMillis()}.jpg"
            val ok = withContext(Dispatchers.IO) { Files.importImage(ctx, uri, File(app.repo.imagesDir(id), name), 1600) }
            if (ok) {
                val c = current()
                if (c.cover.isNotBlank()) File(app.repo.imagesDir(id), c.cover).delete()
                save(c.copy(cover = name))
            } else toast(ctx, "Görsel okunamadı")
        }
    }

    suspend fun buildEpub(): File = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "export_${id}.epub")
        EpubWriter.write(current(), app.repo.imagesDir(id), out)
        out
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = "EPUB hazırlanıyor…"
            try {
                val f = buildEpub()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                    f.delete()
                }
                toast(ctx, "EPUB kaydedildi")
            } catch (e: Exception) {
                toast(ctx, "Kaydedilemedi: ${e.message}")
            }
            busy = null
        }
    }

    fun readInLibrary() = scope.launch {
        busy = "Kitaplığa ekleniyor…"
        try {
            val f = buildEpub()
            val bookId = withContext(Dispatchers.IO) {
                val key = "ms_book_$id"
                val old = app.prefs.str(key)
                val b = app.repo.importFile(f, (current().title.ifBlank { "Kitap" }) + ".epub", null)
                if (old.isNotEmpty() && old != b.id) app.repo.book(old)?.let { ob ->
                    // eski sürümün kaldığın yerini yeniye taşı
                    app.repo.replaceBookMeta(b.copy(position = ob.position, percent = ob.percent, posUpdatedAt = System.currentTimeMillis()))
                    app.repo.deleteBook(old)
                }
                app.prefs.setStr(key, b.id)
                f.delete()
                b.id
            }
            openReader(bookId)
        } catch (e: Exception) {
            toast(ctx, "Olmadı: ${e.message}")
        }
        busy = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "Adsız kitap" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 820.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.width(104.dp).aspectRatio(0.68f).clip(RoundedCornerShape(6.dp)).clickable { pickCover.launch("image/*") }) {
                                val cf = if (m.cover.isNotBlank()) File(app.repo.imagesDir(id), m.cover) else null
                                if (cf != null && cf.exists()) FileImage(cf, 400, Modifier.fillMaxSize(), m.cover)
                                else CoverPlaceholder(title, author, Modifier.fillMaxSize())
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { pickCover.launch("image/*") }.padding(4.dp)) {
                                Icon(Icons.Default.Image, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                                Text("Kapak", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(title, { title = it }, label = { Text("Kitap adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(author, { author = it }, label = { Text("Yazar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    OutlinedTextField(desc, { desc = it }, label = { Text("Kısa açıklama (isteğe bağlı)") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { readInLibrary() }, enabled = busy == null, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("E-kitap yap ve oku")
                        }
                        OutlinedButton(onClick = { export.launch(Files.safeName(title.ifBlank { "kitap" }) + ".epub") }, enabled = busy == null, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(6.dp)); Text("EPUB kaydet")
                        }
                    }
                    busy?.let {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bölümler", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${m.chapters.size} bölüm • ${m.wordCount()} kelime", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(m.chapters, key = { _, c -> c.id }) { i, c ->
                    Card(Modifier.fillMaxWidth().clickable { openChapter(c.id) }) {
                        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${i + 1}. ${c.title.ifBlank { "Bölüm ${i + 1}" }}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                                val preview = c.body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("![") }?.take(90) ?: "Boş bölüm"
                                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                val l = m.chapters.toMutableList(); val x = l.removeAt(i); l.add(i - 1, x); save(current().copy(chapters = l))
                            }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Yukarı") }
                            IconButton(onClick = {
                                val l = m.chapters.toMutableList(); val x = l.removeAt(i); l.add(i + 1, x); save(current().copy(chapters = l))
                            }, enabled = i < m.chapters.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Aşağı") }
                            IconButton(onClick = { deleteCh = c }) { Icon(Icons.Default.Delete, "Sil") }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = {
                        val c = Chapter(Repo.newId(), "Bölüm ${m.chapters.size + 1}", "")
                        save(current().copy(chapters = current().chapters + c))
                        openChapter(c.id)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Bölüm ekle")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "İpucu: Bölümlerde **kalın**, *italik*, satır başında # ara başlık ve *** sahne arası kullanabilirsin. Görselleri bölüm içindeki 🖼 düğmesiyle eklersin.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    deleteCh?.let { c ->
        ConfirmDialog("Bölümü sil", "\"${c.title}\" silinsin mi?", onConfirm = {
            save(current().copy(chapters = current().chapters.filter { it.id != c.id }))
        }, onDismiss = { deleteCh = null })
    }
}
