@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.ufuk.kitap.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.App
import com.ufuk.kitap.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** PdfRenderer aynı anda tek sayfa açabildiği için tüm çizimler sırayla yapılır. */
private class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 8
    }
    val count = renderer.pageCount
    val ratios: FloatArray = FloatArray(count) { i ->
        renderer.openPage(i).use { p -> p.width.toFloat() / p.height.coerceAtLeast(1) }
    }

    suspend fun render(i: Int, widthPx: Int): ImageBitmap = lock.withLock {
        check(!closed) { "kapalı" }
        val key = "$i@$widthPx"
        cache[key]?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            renderer.openPage(i).use { p ->
                val w = widthPx.coerceIn(200, 2600)
                val h = (w / ratios[i]).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        }.also { cache[key] = it }
    }

    @Volatile var closed = false

    fun close() {
        closed = true
        // Çizim sürüyorsa bitmesini bekleyip kapat
        Thread {
            kotlinx.coroutines.runBlocking { lock.withLock { try { renderer.close(); pfd.close() } catch (_: Exception) {} } }
        }.start()
    }
}

@Composable
fun PdfReader(app: App, book: Book, onClose: () -> Unit) {
    val result by produceState<Result<PdfDoc>?>(null, book.id) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDoc(app.repo.bookFile(book)) } }
    }
    val doc = result?.getOrNull()
    // Belgeyi yalnızca ekrandan çıkınca kapat (o anki belgeyi yakala)
    DisposableEffect(doc) { onDispose { doc?.close() } }
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = result?.exceptionOrNull()
            if (err != null) Text("PDF açılamadı: ${err.message}", modifier = Modifier.padding(24.dp)) else CircularProgressIndicator()
        }
        return
    }
    val startPage = remember { (book.position.toIntOrNull() ?: 0).coerceIn(0, (doc.count - 1).coerceAtLeast(0)) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    var bars by remember { mutableStateOf(true) }
    var goTo by remember { mutableStateOf(false) }
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(700).collect { p ->
            app.repo.updateProgress(book.id, p.toString(), if (doc.count > 0) (p + 1f) / doc.count else 0f)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val p = listState.firstVisibleItemIndex
            app.repo.updateProgress(book.id, p.toString(), if (doc.count > 0) (p + 1f) / doc.count else 0f)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF3A3A3A))) {
        if (bars) ReaderTopBar(
            title = book.title,
            subtitle = "Sayfa ${page + 1} / ${doc.count}",
            theme = null,
            onClose = onClose,
            onToc = null,
            onSettings = null,
            extra = {
                IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Icon(Icons.Default.ZoomOut, "Küçült") }
                IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(3f) }, enabled = zoom < 3f) { Icon(Icons.Default.ZoomIn, "Büyüt") }
                Text("Git", modifier = Modifier.clickable { goTo = true }.padding(12.dp))
            },
        ) else Box(Modifier.padding(top = 0.dp))

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val pageWidth = maxWidth * zoom
            val widthPx = with(density) { pageWidth.toPx() }.toInt()
            val hScroll = rememberScrollState()
            Box(
                Modifier.fillMaxSize()
                    .then(if (zoom > 1f) Modifier.horizontalScroll(hScroll) else Modifier)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(pageWidth).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(doc.count) { i ->
                        PdfPage(doc, i, widthPx, Modifier.fillMaxWidth().aspectRatio(doc.ratios[i]).clickable { bars = !bars })
                    }
                }
            }
            if (!bars) Text(
                "${page + 1}/${doc.count}", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(10.dp)
                    .background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }

    if (goTo) GoToDialog("Sayfaya git", doc.count, onGo = { p -> scope.launch { listState.scrollToItem(p - 1) } }, onDismiss = { goTo = false })
}

@Composable
private fun PdfPage(doc: PdfDoc, i: Int, widthPx: Int, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, i, widthPx) {
        value = try { doc.render(i, widthPx) } catch (e: Throwable) { null }
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, "Sayfa ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        else Text("${i + 1}", color = Color.LightGray)
    }
}
