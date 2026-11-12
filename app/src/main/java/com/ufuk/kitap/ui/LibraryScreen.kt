@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ufuk.kitap.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.App
import com.ufuk.kitap.data.Book
import com.ufuk.kitap.data.Manuscript
import com.ufuk.kitap.importers.Imported
import com.ufuk.kitap.importers.Importers
import com.ufuk.kitap.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    app: App,
    openBook: (String) -> Unit,
    openManuscript: (String) -> Unit,
    openSettings: () -> Unit,
    sync: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    val books by app.repo.books.collectAsState()
    val manuscripts by app.repo.manuscripts.collectAsState()
    val syncing by SyncState.running
    var busy by remember { mutableStateOf<String?>(null) }
    var deleteBook by remember { mutableStateOf<Book?>(null) }
    var deleteMs by remember { mutableStateOf<Manuscript?>(null) }
    var newMenu by remember { mutableStateOf(false) }
    var importKind by remember { mutableStateOf("") }
    var crash by remember { mutableStateOf(File(ctx.filesDir, "crash.txt").let { if (it.exists()) it.readText() else null }) }

    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = "Kitap ekleniyor…"
            scope.launch {
                try {
                    val b = withContext(Dispatchers.IO) { app.repo.importUri(uri) }
                    openBook(b.id)
                } catch (e: Exception) {
                    toast(ctx, "Eklenemedi: ${e.message}")
                }
                busy = null
            }
        }
    }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = "Dosya kitaba dönüştürülüyor…"
            val kind = importKind
            scope.launch {
                try {
                    val id = withContext(Dispatchers.IO) {
                        val name = Files.displayName(ctx, uri)
                        val tmp = File(ctx.cacheDir, "src_" + System.nanoTime())
                        Files.copyUriTo(ctx, uri, tmp)
                        try {
                            val m0 = app.repo.newManuscript()
                            val imp: Imported = when (kind) {
                                "docx" -> Importers.fromDocx(tmp, name, app.repo.imagesDir(m0.id))
                                "pdf" -> Importers.fromPdf(ctx, tmp, name)
                                else -> Importers.fromTxt(tmp, name)
                            }
                            app.repo.saveManuscript(m0.copy(title = imp.title, chapters = imp.chapters))
                            m0.id
                        } finally {
                            tmp.delete()
                        }
                    }
                    openManuscript(id)
                } catch (e: Throwable) {
                    toast(ctx, "Dönüştürülemedi: ${e.message}")
                }
                busy = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kitap Atölyesi", fontFamily = FontFamily.Serif) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                actions = {
                    if (syncing) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else IconButton(onClick = sync) { Icon(Icons.Default.Sync, "Eşitle") }
                    IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Ayarlar") }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(
                onClick = { pickBook.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "application/octet-stream")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Kitap ekle") },
            ) else ExtendedFloatingActionButton(
                onClick = { newMenu = true },
                icon = { Icon(Icons.Default.EditNote, null) },
                text = { Text("Yeni kitap yaz") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kitaplığım (${books.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Yazdıklarım (${manuscripts.size})") })
            }
            val msg by SyncState.message
            if (syncing && msg.isNotEmpty()) Text(msg, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            busy?.let {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text(it)
                }
            }
            if (tab == 0) BookGrid(app, books, openBook) { deleteBook = it }
            else ManuscriptList(app, manuscripts, openManuscript) { deleteMs = it }
        }
    }

    if (newMenu) AlertDialog(
        onDismissRequest = { newMenu = false },
        title = { Text("Yeni kitap") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Boş kitap") },
                    supportingContent = { Text("Bölüm bölüm kendin yaz") },
                    leadingContent = { Icon(Icons.Default.EditNote, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        newMenu = false
                        openManuscript(app.repo.newManuscript().id)
                    }),
                )
                ListItem(
                    headlineContent = { Text("Word dosyasından") },
                    supportingContent = { Text(".docx — başlıklar bölüm olur, resimler korunur") },
                    leadingContent = { Icon(Icons.Default.Description, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        newMenu = false; importKind = "docx"
                        pickSource.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream"))
                    }),
                )
                ListItem(
                    headlineContent = { Text("PDF dosyasından") },
                    supportingContent = { Text("Metni alır, bölümlere ayırır") },
                    leadingContent = { Icon(Icons.Default.PictureAsPdf, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        newMenu = false; importKind = "pdf"
                        pickSource.launch(arrayOf("application/pdf"))
                    }),
                )
                ListItem(
                    headlineContent = { Text("Metin dosyasından") },
                    supportingContent = { Text(".txt — \"Bölüm 1\" satırlarından ayırır") },
                    leadingContent = { Icon(Icons.Default.TextSnippet, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        newMenu = false; importKind = "txt"
                        pickSource.launch(arrayOf("text/plain"))
                    }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { newMenu = false }) { Text("Kapat") } },
    )

    crash?.let { c ->
        AlertDialog(
            onDismissRequest = { File(ctx.filesDir, "crash.txt").delete(); crash = null },
            title = { Text("Uygulama az önce kapandı") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("Bu ekranın görüntüsünü Claude'a gönder, düzeltsin:", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(c, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { File(ctx.filesDir, "crash.txt").delete(); crash = null }) { Text("Tamam") } },
        )
    }

    deleteBook?.let { b ->
        ConfirmDialog("Kitabı sil", "\"${b.title}\" kitaplıktan silinsin mi?", onConfirm = { app.repo.deleteBook(b.id) }, onDismiss = { deleteBook = null })
    }
    deleteMs?.let { m ->
        ConfirmDialog("Yazdığın kitabı sil", "\"${m.title}\" ve tüm bölümleri silinsin mi? Bu geri alınamaz.", onConfirm = { app.repo.deleteManuscript(m.id) }, onDismiss = { deleteMs = null })
    }
}

@Composable
private fun BookGrid(app: App, books: List<Book>, open: (String) -> Unit, askDelete: (Book) -> Unit) {
    if (books.isEmpty()) {
        Empty("Kitaplığın boş", "EPUB, PDF veya TXT eklemek için \"Kitap ekle\"ye dokun.\nDosya yöneticisinden \"Birlikte aç → Kitap Atölyesi\" de olur.")
        return
    }
    val sorted = books.sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(sorted, key = { it.id }) { b ->
            Column(Modifier.combinedClickable(onClick = { open(b.id) }, onLongClick = { askDelete(b) })) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(6.dp))) {
                    val cover = app.repo.coverFile(b.id)
                    if (cover.exists()) FileImage(cover, 400, Modifier.fillMaxSize())
                    else CoverPlaceholder(b.title, b.author, Modifier.fillMaxSize())
                    Text(
                        b.type.uppercase(), color = Color.White, fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            .background(Color(0x99000000), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { b.percent.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
                Spacer(Modifier.height(4.dp))
                Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 16.sp)
                Text(
                    (if (b.author.isNotBlank()) b.author + " • " else "") + "%${(b.percent * 100).toInt()}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ManuscriptList(app: App, list: List<Manuscript>, open: (String) -> Unit, askDelete: (Manuscript) -> Unit) {
    if (list.isEmpty()) {
        Empty("Henüz yazdığın kitap yok", "\"Yeni kitap yaz\" ile boş bir kitap başlat ya da Word, PDF veya metin dosyasından dönüştür.")
        return
    }
    val df = remember { SimpleDateFormat("d MMM yyyy HH:mm", Locale("tr")) }
    LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth().combinedClickable(onClick = { open(m.id) }, onLongClick = { askDelete(m) })) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(56.dp).aspectRatio(0.68f).clip(RoundedCornerShape(4.dp))) {
                        val cover = if (m.cover.isNotBlank()) File(app.repo.imagesDir(m.id), m.cover) else null
                        if (cover != null && cover.exists()) FileImage(cover, 200, Modifier.fillMaxSize(), m.updatedAt)
                        else CoverPlaceholder("", "", Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title.ifBlank { "Adsız" }, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, maxLines = 2)
                        if (m.author.isNotBlank()) Text(m.author, fontSize = 13.sp)
                        Text(
                            "${m.chapters.size} bölüm • ${m.wordCount()} kelime • ${df.format(Date(m.updatedAt))}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { askDelete(m) }) { Icon(Icons.Default.Delete, "Sil") }
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
