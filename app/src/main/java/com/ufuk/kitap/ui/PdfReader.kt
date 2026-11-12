@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.ufuk.kitap.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 14
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
    val last = (doc.count - 1).coerceAtLeast(0)
    var page by remember { mutableIntStateOf((book.position.toIntOrNull() ?: 0).coerceIn(0, last)) }
    var flip by remember { mutableStateOf(app.prefs.bool("pdf_flip", true)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var bars by remember { mutableStateOf(true) }
    var goTo by remember { mutableStateOf(false) }
    fun pct() = if (doc.count > 0) (page + 1f) / doc.count else 0f

    LaunchedEffect(page) {
        delay(600)
        app.repo.updateProgress(book.id, page.toString(), pct())
    }
    DisposableEffect(Unit) { onDispose { app.repo.updateProgress(book.id, page.toString(), pct()) } }

    Column(Modifier.fillMaxSize().background(Color(0xFF2E2A27))) {
        if (bars) ReaderTopBar(
            title = book.title,
            subtitle = "Sayfa ${page + 1} / ${doc.count}",
            theme = null,
            onClose = onClose,
            onToc = null,
            onSettings = null,
            extra = {
                if (!flip) {
                    IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Icon(Icons.Default.ZoomOut, "Küçült") }
                    IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(3f) }, enabled = zoom < 3f) { Icon(Icons.Default.ZoomIn, "Büyüt") }
                }
                IconButton(onClick = { flip = !flip; app.prefs.setBool("pdf_flip", flip) }) {
                    if (flip) Icon(Icons.Default.ViewDay, "Kaydırarak oku") else Icon(Icons.Default.AutoStories, "Kitap gibi oku")
                }
                Text("Git", modifier = Modifier.clickable { goTo = true }.padding(12.dp))
            },
        ) else Box(Modifier.statusBarsPadding())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (flip) {
                FlipBook(doc, page, onPage = { page = it.coerceIn(0, last) }, onCenterTap = { bars = !bars }, modifier = Modifier.fillMaxSize())
            } else {
                ScrollPages(doc, page, zoom, onPage = { page = it }, onTap = { bars = !bars })
            }
            if (!bars) Text(
                "${page + 1}/${doc.count}", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(10.dp)
                    .background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (bars && doc.count > 1) Row(
            Modifier.fillMaxWidth().background(Color(0xFF1F1C1A)).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = page.toFloat(), onValueChange = { page = it.roundToInt().coerceIn(0, last) },
                valueRange = 0f..last.toFloat(), modifier = Modifier.weight(1f),
            )
            Text("  ${page + 1}/${doc.count}", color = Color.White, fontSize = 12.sp)
        }
    }

    if (goTo) GoToDialog("Sayfaya git", doc.count, onGo = { p -> page = p - 1 }, onDismiss = { goTo = false })
}

/** Alt alta kaydırmalı görünüm (yakınlaştırma ile). */
@Composable
private fun ScrollPages(doc: PdfDoc, page: Int, zoom: Float, onPage: (Int) -> Unit, onTap: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = page)
    LaunchedEffect(Unit) { snapshotFlow { listState.firstVisibleItemIndex }.collect { onPage(it) } }
    LaunchedEffect(page) { if (listState.firstVisibleItemIndex != page) listState.scrollToItem(page) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = maxWidth * zoom
        val widthPx = with(LocalDensity.current) { pageWidth.toPx() }.toInt()
        val hScroll = rememberScrollState()
        Box(Modifier.fillMaxSize().then(if (zoom > 1f) Modifier.horizontalScroll(hScroll) else Modifier)) {
            LazyColumn(state = listState, modifier = Modifier.width(pageWidth).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(doc.count) { i ->
                    PdfPage(doc, i, widthPx, Modifier.fillMaxWidth().aspectRatio(doc.ratios[i]).clickable { onTap() })
                }
            }
        }
    }
}

/**
 * Kitap görünümü: sayfalar parmakla sürüklenerek ya da kenara dokunarak çevrilir.
 * Geniş ekranda (tablet yatay) iki sayfa yan yana açılır ve yaprak ortadaki sırttan döner.
 */
@Composable
private fun FlipBook(doc: PdfDoc, page: Int, onPage: (Int) -> Unit, onCenterTap: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val double = maxWidth > maxHeight && maxWidth > 700.dp
        val step = if (double) 2 else 1
        val base = if (double) page - page % 2 else page
        val canFwd = base + step <= doc.count - 1
        val canBack = base - step >= 0
        val slotPx = (if (double) wPx / 2 else wPx).toInt()
        val progress = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density

        suspend fun settle(target: Float) {
            progress.animateTo(target, tween(if (double) 520 else 380))
            if (target == 1f) onPage(base + step) else if (target == -1f) onPage(base - step)
            progress.snapTo(0f)
        }

        // Komşu sayfaları önceden hazırla ki çevirirken boş görünmesin
        LaunchedEffect(base, slotPx) {
            for (i in base - step until base + 2 * step) if (i in 0 until doc.count) try { doc.render(i, slotPx) } catch (_: Throwable) {}
        }

        val gestures = Modifier
            .pointerInput(base, double, canFwd, canBack) {
                detectTapGestures { off ->
                    when {
                        off.x < wPx * 0.28f -> if (canBack) scope.launch { settle(-1f) }
                        off.x > wPx * 0.72f -> if (canFwd) scope.launch { settle(1f) }
                        else -> onCenterTap()
                    }
                }
            }
            .pointerInput(base, double, canFwd, canBack) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch { val p = progress.value; settle(if (p > 0.2f) 1f else if (p < -0.2f) -1f else 0f) }
                    },
                    onDragCancel = { scope.launch { settle(0f) } },
                    onHorizontalDrag = { _, dx ->
                        scope.launch {
                            val np = (progress.value - dx / (wPx * 0.8f)).coerceIn(if (canBack) -1f else 0f, if (canFwd) 1f else 0f)
                            progress.snapTo(np)
                        }
                    },
                )
            }

        val p = progress.value
        Box(Modifier.fillMaxSize().then(gestures)) {
            if (!double) {
                // Tek sayfa: üstteki yaprak sol kenardan döner, altından diğer sayfa çıkar
                val staticIdx = if (p > 0f) base + 1 else base
                val leafIdx = if (p > 0f) base else if (p < 0f) base - 1 else -1
                val angle = if (p > 0f) -90f * p else -90f * (1f + p)
                PageSlot(doc, staticIdx, slotPx, Modifier.fillMaxSize(), Alignment.Center)
                if (leafIdx >= 0) PageSlot(
                    doc, leafIdx, slotPx,
                    Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f); rotationY = angle; cameraDistance = 24f * density
                    }.shade(abs(angle) / 90f * 0.35f),
                    Alignment.Center,
                )
            } else {
                val q = abs(p)
                val leftIdx = if (p < 0f) base - 2 else base
                val rightIdx = if (p > 0f) base + 3 else base + 1
                val left = Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart)
                val right = Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterEnd)
                PageSlot(doc, leftIdx, slotPx, left, Alignment.CenterEnd)
                PageSlot(doc, rightIdx, slotPx, right, Alignment.CenterStart)
                if (q > 0f) {
                    // Yaprağın ön yüzü ilk yarıda, arka yüzü ikinci yarıda görünür
                    val onRight = (p > 0f && q < 0.5f) || (p < 0f && q >= 0.5f)
                    val leafIdx = when {
                        p > 0f -> if (q < 0.5f) base + 1 else base + 2
                        else -> if (q < 0.5f) base else base - 1
                    }
                    val angle = when {
                        p > 0f -> if (q < 0.5f) -180f * q else 180f * (1f - q)
                        else -> if (q < 0.5f) 180f * q else -180f * (1f - q)
                    }
                    PageSlot(
                        doc, leafIdx, slotPx,
                        (if (onRight) right else left).graphicsLayer {
                            transformOrigin = TransformOrigin(if (onRight) 0f else 1f, 0.5f); rotationY = angle; cameraDistance = 24f * density
                        }.shade(abs(angle) / 90f * 0.3f),
                        if (onRight) Alignment.CenterStart else Alignment.CenterEnd,
                    )
                }
                // Sırt gölgesi
                Box(Modifier.align(Alignment.Center).fillMaxHeight().width(2.dp).background(Color(0x33000000)))
            }
        }
    }
}

private fun Modifier.shade(alpha: Float) = drawWithContent {
    drawContent()
    if (alpha > 0f) drawRect(Color.Black, alpha = alpha.coerceIn(0f, 1f))
}

@Composable
private fun PageSlot(doc: PdfDoc, idx: Int, widthPx: Int, modifier: Modifier, alignment: Alignment) {
    Box(modifier, contentAlignment = alignment) {
        if (idx in 0 until doc.count) PdfPage(doc, idx, widthPx, Modifier.aspectRatio(doc.ratios[idx]))
    }
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
