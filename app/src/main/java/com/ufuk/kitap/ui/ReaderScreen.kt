@file:OptIn(ExperimentalMaterial3Api::class)

package com.ufuk.kitap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.ufuk.kitap.App
import com.ufuk.kitap.data.Prefs

/** Okuma görünümü ayarları (tüm kitaplarda ortak). */
class ReadSettings(private val prefs: Prefs) {
    var fontPct by mutableIntStateOf(prefs.int("read_font", 110))
        private set
    var theme by mutableStateOf(ReadTheme.entries.getOrElse(prefs.int("read_theme", 1)) { ReadTheme.SEPIA })
        private set
    var serif by mutableStateOf(prefs.bool("read_serif", true))
        private set
    var lineHeight by mutableFloatStateOf(prefs.int("read_lh", 160) / 100f)
        private set

    fun setFont(v: Int) { fontPct = v.coerceIn(70, 240); prefs.setInt("read_font", fontPct) }
    fun pickTheme(t: ReadTheme) { theme = t; prefs.setInt("read_theme", t.ordinal) }
    fun pickSerif(v: Boolean) { serif = v; prefs.setBool("read_serif", v) }
    fun setLine(v: Float) { lineHeight = v; prefs.setInt("read_lh", (v * 100).toInt()) }
}

@Composable
fun ReaderScreen(app: App, bookId: String, onClose: () -> Unit) {
    val book = remember(bookId) { app.repo.book(bookId) }
    BackHandler { onClose() }
    if (book == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val settings = remember { ReadSettings(app.prefs) }
    when (book.type) {
        "epub" -> EpubReader(app, book, settings, onClose)
        "pdf" -> PdfReader(app, book, onClose)
        else -> TxtReader(app, book, settings, onClose)
    }
}

@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String,
    theme: ReadTheme?,
    onClose: () -> Unit,
    onToc: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    extra: @Composable () -> Unit = {},
) {
    val bg = theme?.bg ?: MaterialTheme.colorScheme.surface
    val fg = theme?.fg ?: MaterialTheme.colorScheme.onSurface
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontFamily = FontFamily.Serif)
                if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
        actions = {
            extra()
            if (onToc != null) IconButton(onClick = onToc) { Icon(Icons.AutoMirrored.Filled.List, "İçindekiler") }
            if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Default.FormatSize, "Görünüm") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = bg, titleContentColor = fg, navigationIconContentColor = fg, actionIconContentColor = fg,
        ),
    )
}

@Composable
fun ReadSettingsDialog(s: ReadSettings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Görünüm") },
        text = {
            Column {
                Text("Yazı boyutu: %${s.fontPct}")
                Slider(value = s.fontPct.toFloat(), onValueChange = { s.setFont(it.toInt()) }, valueRange = 70f..240f, steps = 16)
                Text("Satır aralığı: ${"%.1f".format(s.lineHeight)}")
                Slider(value = s.lineHeight, onValueChange = { s.setLine((it * 10).toInt() / 10f) }, valueRange = 1.2f..2.2f, steps = 9)
                Spacer(Modifier.padding(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadTheme.entries.forEach { t ->
                        FilterChip(selected = s.theme == t, onClick = { s.pickTheme(t) }, label = { Text(t.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.serif, onClick = { s.pickSerif(true) }, label = { Text("Tırnaklı", fontFamily = FontFamily.Serif) })
                    FilterChip(selected = !s.serif, onClick = { s.pickSerif(false) }, label = { Text("Tırnaksız", fontFamily = FontFamily.SansSerif) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } },
    )
}

@Composable
fun TocDialog(items: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = items.indexOfLast { it.second <= current }.coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("İçindekiler") },
        text = {
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 460.dp)) {
                itemsIndexed(items) { _, (label, idx) ->
                    Text(
                        label,
                        fontWeight = if (idx == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (idx == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onPick(idx) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@Composable
fun GoToDialog(label: String, max: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("1 – $max") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toIntOrNull()?.let { onGo(it.coerceIn(1, max)) }
                onDismiss()
            }) { Text("Git") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
