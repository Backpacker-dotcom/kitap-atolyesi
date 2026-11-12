@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.ufuk.kitap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.App
import com.ufuk.kitap.data.Book
import com.ufuk.kitap.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TxtReader(app: App, book: Book, s: ReadSettings, onClose: () -> Unit) {
    val paras by produceState<List<String>?>(null, book.id) {
        value = withContext(Dispatchers.IO) {
            Files.decodeText(app.repo.bookFile(book).readBytes())
                .replace("\r\n", "\n").replace('\r', '\n')
                .split('\n').map { it.trimEnd() }
                .filterIndexed { i, l -> l.isNotEmpty() || i == 0 }
        }
    }
    val list = paras
    if (list == null) {
        Box(Modifier.fillMaxSize().background(s.theme.bg), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // "Bölüm ..." satırlarından içindekiler
    val toc = remember(list) {
        val heads = Regex("^\\s*(bölüm|BÖLÜM|Bölüm|chapter|CHAPTER|Chapter|kısım|KISIM|Kısım)\\s*[\\dIVXLCivxlc]+.*$")
        list.mapIndexedNotNull { i, l -> if (l.length < 90 && heads.matches(l)) l.trim() to i else null }
    }
    val start = remember { (book.position.toIntOrNull() ?: 0).coerceIn(0, (list.size - 1).coerceAtLeast(0)) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val scope = rememberCoroutineScope()
    var bars by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val idx by remember { derivedStateOf { state.firstVisibleItemIndex } }
    fun pct(i: Int) = if (list.size <= 1) 1f else i.toFloat() / (list.size - 1)

    LaunchedEffect(Unit) {
        snapshotFlow { state.firstVisibleItemIndex }.debounce(700).collect { i ->
            app.repo.updateProgress(book.id, i.toString(), pct(i))
        }
    }
    DisposableEffect(Unit) {
        onDispose { app.repo.updateProgress(book.id, state.firstVisibleItemIndex.toString(), pct(state.firstVisibleItemIndex)) }
    }

    val family = if (s.serif) FontFamily.Serif else FontFamily.SansSerif
    val size = (17 * s.fontPct / 100f).sp
    Column(Modifier.fillMaxSize().background(s.theme.bg)) {
        if (bars) ReaderTopBar(
            title = book.title,
            subtitle = "%${(pct(idx) * 100).toInt()}",
            theme = s.theme,
            onClose = onClose,
            onToc = if (toc.isNotEmpty()) ({ showToc = true }) else null,
            onSettings = { showSettings = true },
        ) else Box(Modifier.statusBarsPadding())
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { bars = !bars } }, contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = state,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 120.dp),
            ) {
                itemsIndexed(list) { _, p ->
                    Text(
                        p.ifEmpty { " " },
                        color = s.theme.fg, fontFamily = family, fontSize = size, lineHeight = s.lineHeight.em,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
    if (showToc) TocDialog(toc, idx, onPick = { i -> scope.launch { state.scrollToItem(i) } }, onDismiss = { showToc = false })
    if (showSettings) ReadSettingsDialog(s) { showSettings = false }
}
