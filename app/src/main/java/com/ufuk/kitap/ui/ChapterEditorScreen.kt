@file:OptIn(ExperimentalMaterial3Api::class)

package com.ufuk.kitap.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.App
import com.ufuk.kitap.net.Gemini
import com.ufuk.kitap.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class AiKind { FIX, POLISH, CONTINUE, TITLE, CUSTOM }

private class AiResult(val kind: AiKind, val text: String, val selection: TextRange)

@Composable
fun ChapterEditorScreen(app: App, msId: String, chapterId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m0 = remember { app.repo.manuscript(msId) }
    val c0 = remember { m0?.chapters?.firstOrNull { it.id == chapterId } }
    if (m0 == null || c0 == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var title by remember { mutableStateOf(c0.title) }
    var body by remember { mutableStateOf(TextFieldValue(c0.body)) }
    var aiMenu by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<AiResult?>(null) }
    var customAsk by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(true) }

    fun persist() {
        val m = app.repo.manuscript(msId) ?: return
        val chs = m.chapters.map { if (it.id == chapterId) it.copy(title = title, body = body.text) else it }
        if (chs != m.chapters) app.repo.saveManuscript(m.copy(chapters = chs))
        saved = true
    }

    LaunchedEffect(title, body.text) {
        saved = false
        delay(700)
        persist()
    }
    // Geri tuşuyla çıkarken son yazılanlar kaybolmasın
    DisposableEffect(Unit) { onDispose { persist() } }

    fun insert(text: String, wrapEnd: String? = null) {
        val sel = body.selection
        val t = body.text
        val newText: String
        val cursor: Int
        if (wrapEnd != null) {
            val inner = t.substring(sel.min, sel.max).ifEmpty { "metin" }
            newText = t.substring(0, sel.min) + text + inner + wrapEnd + t.substring(sel.max)
            cursor = sel.min + text.length + inner.length + wrapEnd.length
        } else {
            newText = t.substring(0, sel.min) + text + t.substring(sel.max)
            cursor = sel.min + text.length
        }
        body = TextFieldValue(newText, TextRange(cursor))
    }

    fun lineStart(prefix: String) {
        val t = body.text
        val s = t.lastIndexOf('\n', (body.selection.min - 1).coerceAtLeast(0)).let { if (it < 0 || body.selection.min == 0) 0 else it + 1 }
        val newText = t.substring(0, s) + prefix + t.substring(s)
        body = TextFieldValue(newText, TextRange(body.selection.min + prefix.length))
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val name = "img_${System.currentTimeMillis()}.jpg"
            val ok = withContext(Dispatchers.IO) { Files.importImage(ctx, uri, File(app.repo.imagesDir(msId), name)) }
            if (ok) insert("\n![](images/$name)\n") else toast(ctx, "Görsel eklenemedi")
        }
    }

    fun runAi(kind: AiKind, instruction: String = "") {
        val key = app.prefs.str("gemini_key")
        if (key.isBlank()) { toast(ctx, "Önce Ayarlar > Yapay zekâ bölümüne Gemini API anahtarını gir"); return }
        val sel = body.selection
        val selected = if (sel.collapsed) "" else body.text.substring(sel.min, sel.max)
        val target = selected.ifEmpty { body.text }
        if (target.isBlank() && kind != AiKind.CONTINUE) { toast(ctx, "Önce biraz metin yaz"); return }
        val prompt = when (kind) {
            AiKind.FIX -> Gemini.promptFix(target)
            AiKind.POLISH -> Gemini.promptPolish(target)
            AiKind.CONTINUE -> Gemini.promptContinue(app.repo.manuscript(msId)?.title ?: "", title, body.text)
            AiKind.TITLE -> Gemini.promptTitle(body.text)
            AiKind.CUSTOM -> Gemini.promptCustom(instruction, target)
        }
        aiBusy = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { Gemini.generate(key, app.prefs.str("gemini_model", "gemini-2.5-flash"), prompt) }
                aiResult = AiResult(kind, res, if (selected.isEmpty()) TextRange(0, body.text.length) else sel)
            } catch (e: Exception) {
                toast(ctx, "Yapay zekâ hatası: ${e.message}")
            }
            aiBusy = false
        }
    }

    val words = body.text.split(Regex("\\s+")).count { it.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(m0.title, maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (saved) "$words kelime • kaydedildi" else "$words kelime • kaydediliyor…", fontSize = 12.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = { persist(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
                actions = {
                    if (aiBusy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    Box {
                        IconButton(onClick = { aiMenu = true }, enabled = !aiBusy) { Icon(Icons.Default.AutoAwesome, "Yapay zekâ", tint = MaterialTheme.colorScheme.secondary) }
                        DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                            Text("Seçili metne, seçim yoksa bölümün tamamına uygulanır", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).widthIn(max = 240.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DropdownMenuItem(text = { Text("Yazım ve dil bilgisini düzelt") }, onClick = { aiMenu = false; runAi(AiKind.FIX) })
                            DropdownMenuItem(text = { Text("Daha akıcı yaz") }, onClick = { aiMenu = false; runAi(AiKind.POLISH) })
                            DropdownMenuItem(text = { Text("Devamını öner") }, onClick = { aiMenu = false; runAi(AiKind.CONTINUE) })
                            DropdownMenuItem(text = { Text("Bölüm başlığı öner") }, onClick = { aiMenu = false; runAi(AiKind.TITLE) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Kendi isteğimi yazayım…") }, onClick = { aiMenu = false; customAsk = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 860.dp).fillMaxSize()) {
                OutlinedTextField(
                    title, { title = it }, label = { Text("Bölüm başlığı") }, singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pickImage.launch("image/*") }) { Icon(Icons.Default.Image, "Görsel ekle") }
                    IconButton(onClick = { insert("**", "**") }) { Icon(Icons.Default.FormatBold, "Kalın") }
                    IconButton(onClick = { insert("*", "*") }) { Icon(Icons.Default.FormatItalic, "İtalik") }
                    IconButton(onClick = { lineStart("# ") }) { Icon(Icons.Default.Title, "Ara başlık") }
                    IconButton(onClick = { insert("\n***\n") }) { Icon(Icons.Default.MoreHoriz, "Sahne arası") }
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = { aiMenu = true }, label = { Text("✨ Yapay zekâ") }, enabled = !aiBusy)
                }
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("Yazmaya başla… Her satır bir paragraf olur.") },
                    textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 26.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    if (customAsk) AlertDialog(
        onDismissRequest = { customAsk = false },
        title = { Text("Yapay zekâya ne yapsın?") },
        text = {
            OutlinedTextField(customText, { customText = it }, placeholder = { Text("ör. Bu bölümü daha gerilimli yap, diyalog ekle") }, minLines = 3)
        },
        confirmButton = { TextButton(onClick = { customAsk = false; if (customText.isNotBlank()) runAi(AiKind.CUSTOM, customText) }) { Text("Gönder") } },
        dismissButton = { TextButton(onClick = { customAsk = false }) { Text("Vazgeç") } },
    )

    aiResult?.let { r ->
        if (r.kind == AiKind.TITLE) {
            val options = r.text.lines().map { it.trim().trim('-', '*', '•', '"', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotBlank() }.take(8)
            AlertDialog(
                onDismissRequest = { aiResult = null },
                title = { Text("Başlık önerileri") },
                text = {
                    Column {
                        options.forEach { o ->
                            Text(o, fontFamily = FontFamily.Serif, modifier = Modifier.fillMaxWidth().clickable { title = o; aiResult = null }.padding(vertical = 10.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { aiResult = null }) { Text("Kapat") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { aiResult = null },
                title = { Text(when (r.kind) { AiKind.FIX -> "Düzeltilmiş metin"; AiKind.POLISH -> "Akıcı hali"; AiKind.CONTINUE -> "Devam önerisi"; else -> "Sonuç" }) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(r.text, fontFamily = FontFamily.Serif, fontSize = 15.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = body.text
                        body = if (r.kind == AiKind.CONTINUE) {
                            val nt = t.trimEnd() + "\n" + r.text.trim() + "\n"
                            TextFieldValue(nt, TextRange(nt.length))
                        } else {
                            val a = r.selection.min.coerceIn(0, t.length); val b = r.selection.max.coerceIn(a, t.length)
                            val nt = t.substring(0, a) + r.text.trim() + t.substring(b)
                            TextFieldValue(nt, TextRange(a + r.text.trim().length))
                        }
                        aiResult = null
                    }) { Text(if (r.kind == AiKind.CONTINUE) "Sona ekle" else "Uygula") }
                },
                dismissButton = { TextButton(onClick = { aiResult = null }) { Text("Vazgeç") } },
            )
        }
    }
}
