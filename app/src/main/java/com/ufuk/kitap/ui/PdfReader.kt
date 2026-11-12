@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.ufuk.kitap.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

private enum class Turn { NONE, FWD, BACK_UNCURL, BACK_MIRROR }

/**
 * Kitap görünümü: sayfa, tutulan köşeden kâğıt gibi kıvrılarak çevrilir (arka yüz ve gölgelerle).
 * Geniş ekranda (tablet yatay) iki sayfa yan yana açılır; yaprak sırtın üzerinden karşı sayfaya döner.
 */
@Composable
private fun FlipBook(doc: PdfDoc, page: Int, onPage: (Int) -> Unit, onCenterTap: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val cw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val ch = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val double = maxWidth > maxHeight && maxWidth > 700.dp
        val step = if (double) 2 else 1
        val base = if (double) page - page % 2 else page
        val canFwd = base + step <= doc.count - 1
        val canBack = base - step >= 0
        val ratio = doc.ratios[base.coerceIn(0, (doc.count - 1).coerceAtLeast(0))]
        val pw = minOf(if (double) cw / 2 else cw, ch * ratio)
        val ph = pw / ratio
        val originX = if (double) cw / 2 else (cw - pw) / 2
        val originY = (ch - ph) / 2
        val slotPx = pw.toInt().coerceAtLeast(1)
        val scope = rememberCoroutineScope()

        val imgs = remember { mutableStateMapOf<Int, ImageBitmap>() }
        LaunchedEffect(base, slotPx) {
            val want = (base - 2..base + 3).filter { it in 0 until doc.count }
            imgs.keys.toList().forEach { if (it !in want) imgs.remove(it) }
            for (i in want.sortedBy { abs(it - base) }) try { imgs[i] = doc.render(i, slotPx) } catch (_: Throwable) {}
        }

        var turn by remember { mutableStateOf(Turn.NONE) }
        var cornerTop by remember { mutableStateOf(false) }
        var dragPos by remember { mutableStateOf<Offset?>(null) }
        val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val shadowPaint = remember {
            android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                setShadowLayer(28f, 0f, 0f, 0x70000000)
            }
        }

        suspend fun finish(complete: Boolean) {
            val t = turn
            if (t == Turn.NONE) return
            val cy = if (cornerTop) 0f else ph
            val c = Offset(pw, cy)
            val far = Offset(-pw, cy)
            val target = if (t != Turn.BACK_UNCURL) (if (complete) far else c) else (if (complete) c else far)
            val dur = ((target - pos.value).getDistance() / (2 * pw) * 650).toInt().coerceIn(140, 650)
            pos.animateTo(target, tween(dur, easing = LinearOutSlowInEasing))
            if (complete) onPage(if (t == Turn.FWD) base + step else base - step)
            turn = Turn.NONE
        }

        // Kenara dokununca: köşe hafif bir yay çizerek kendiliğinden çevrilir
        suspend fun auto(t: Turn) {
            cornerTop = false
            val c = Offset(pw, ph)
            val far = Offset(-pw, ph)
            val mid = Offset(0f, ph * 0.86f)
            if (t == Turn.BACK_UNCURL) {
                pos.snapTo(far); turn = t
                pos.animateTo(mid, tween(330, easing = FastOutLinearInEasing))
                pos.animateTo(c, tween(330, easing = LinearOutSlowInEasing))
            } else {
                pos.snapTo(c - Offset(2f, 2f)); turn = t
                pos.animateTo(mid, tween(330, easing = FastOutLinearInEasing))
                pos.animateTo(far, tween(330, easing = LinearOutSlowInEasing))
            }
            onPage(if (t == Turn.FWD) base + step else base - step)
            turn = Turn.NONE
        }

        val backTurn = if (double) Turn.BACK_MIRROR else Turn.BACK_UNCURL
        val gestures = Modifier
            .pointerInput(base, double, canFwd, canBack) {
                detectTapGestures { off ->
                    if (turn != Turn.NONE) return@detectTapGestures
                    when {
                        off.x < cw * 0.28f -> if (canBack) scope.launch { auto(backTurn) }
                        off.x > cw * 0.72f -> if (canFwd) scope.launch { auto(Turn.FWD) }
                        else -> onCenterTap()
                    }
                }
            }
            .pointerInput(base, double, canFwd, canBack, pw, ph) {
                var start = Offset.Zero
                var total = Offset.Zero
                var dragging = false
                detectDragGestures(
                    onDragStart = { start = it; total = Offset.Zero; dragging = false },
                    onDrag = { _, amt ->
                        total += amt
                        if (!dragging) {
                            if (turn != Turn.NONE || abs(total.x) < 1f) return@detectDragGestures
                            val t = if (total.x < 0) (if (canFwd) Turn.FWD else null) else (if (canBack) backTurn else null)
                            if (t == null) return@detectDragGestures
                            cornerTop = start.y < ch / 2
                            dragging = true
                            turn = t
                        }
                        val cy = if (cornerTop) 0f else ph
                        val g = 1.7f
                        val x = when (turn) {
                            Turn.FWD -> pw + total.x * g
                            Turn.BACK_MIRROR -> pw - total.x * g
                            else -> -pw + total.x * g
                        }
                        dragPos = Offset(x.coerceIn(-pw, pw), cy + total.y * 0.55f)
                    },
                    onDragEnd = {
                        if (dragging) {
                            dragging = false
                            val p = dragPos ?: pos.value
                            val complete = if (turn != Turn.BACK_UNCURL) p.x < pw * 0.5f else p.x > -pw * 0.5f
                            scope.launch { pos.snapTo(p); dragPos = null; finish(complete) }
                        }
                    },
                    onDragCancel = {
                        if (dragging) {
                            dragging = false
                            val p = dragPos ?: pos.value
                            scope.launch { pos.snapTo(p); dragPos = null; finish(false) }
                        }
                    },
                )
            }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val t = turn
            val p0 = dragPos ?: pos.value
            fun pg(idx: Int, x: Float, hflip: Boolean = false, alpha: Float = 1f, paper: Boolean = true) {
                if (idx !in 0 until doc.count) return
                withTransform({ if (hflip) scale(-1f, 1f, pivot = Offset(x + pw / 2, ph / 2)) }) {
                    if (paper) drawRect(Color.White, topLeft = Offset(x, 0f), size = Size(pw, ph))
                    imgs[idx]?.let {
                        drawImage(
                            it, dstOffset = IntOffset(x.roundToInt(), 0), dstSize = IntSize(pw.roundToInt(), ph.roundToInt()),
                            alpha = alpha, filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
            val mirrored = t == Turn.BACK_MIRROR
            withTransform({
                translate(originX, originY)
                if (mirrored) scale(-1f, 1f, pivot = Offset.Zero)
            }) {
                if (t == Turn.NONE) {
                    if (double) { pg(base, -pw); pg(base + 1, 0f) } else pg(base, 0f)
                } else {
                    val leaf: Int; val under: Int; val back: Int; val other: Int
                    when (t) {
                        Turn.FWD -> if (double) { leaf = base + 1; under = base + 3; back = base + 2; other = base }
                        else { leaf = base; under = base + 1; back = -1; other = -1 }
                        Turn.BACK_UNCURL -> { leaf = base - 1; under = base; back = -1; other = -1 }
                        else -> { leaf = base; under = base - 2; back = base - 1; other = base + 1 }
                    }
                    if (other >= 0) pg(other, -pw, mirrored)
                    pg(under, 0f, mirrored)

                    val cy = if (cornerTop) 0f else ph
                    val c = Offset(pw, cy)
                    val spine = Offset(0f, cy)
                    // Köşe sırttan sayfa genişliğinden uzağa gidemez (kâğıt yırtılmaz)
                    var p = p0
                    val ds = (p - spine).getDistance()
                    if (ds > pw) p = spine + (p - spine) * (pw / ds)
                    val v = p - c
                    val len = v.getDistance()
                    if (len < 2f) {
                        pg(leaf, 0f, mirrored)
                    } else {
                        val d = v / len                    // kıvrım çizgisinin normali (köşeden parmağa)
                        val m = (c + p) / 2f               // kıvrım çizgisi üzerindeki nokta
                        val td = Offset(-d.y, d.x)
                        val big = (pw + ph) * 4
                        val kept = Path().apply {
                            val a1 = m + td * big; val a2 = m - td * big
                            moveTo(a1.x, a1.y); lineTo(a2.x, a2.y)
                            lineTo(a2.x + d.x * big, a2.y + d.y * big); lineTo(a1.x + d.x * big, a1.y + d.y * big); close()
                        }
                        val lifted = Path().apply {
                            val a1 = m + td * big; val a2 = m - td * big
                            moveTo(a1.x, a1.y); lineTo(a2.x, a2.y)
                            lineTo(a2.x - d.x * big, a2.y - d.y * big); lineTo(a1.x - d.x * big, a1.y - d.y * big); close()
                        }
                        // Alttaki sayfaya düşen gölge
                        clipRect(0f, 0f, pw, ph) {
                            clipPath(lifted) {
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent),
                                        start = m, end = m - d * (len * 0.4f + 24f),
                                    ),
                                    size = Size(pw, ph),
                                )
                            }
                        }
                        // Sayfanın henüz kalkmamış kısmı
                        clipPath(kept) { pg(leaf, 0f, mirrored) }
                        // Kıvrılan parça: kıvrım çizgisine göre yansıtılmış sayfa arkası
                        val md = m.x * d.x + m.y * d.y
                        val reflect = Matrix().apply {
                            this[0, 0] = 1 - 2 * d.x * d.x; this[1, 0] = -2 * d.x * d.y
                            this[0, 1] = -2 * d.x * d.y; this[1, 1] = 1 - 2 * d.y * d.y
                            this[3, 0] = 2 * md * d.x; this[3, 1] = 2 * md * d.y
                        }
                        clipPath(kept) {
                            withTransform({ transform(reflect) }) {
                                drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, pw, ph, shadowPaint) }
                                if (back >= 0) pg(back, 0f, !mirrored)
                                else {
                                    drawRect(Color(0xFFF3EFE6), size = Size(pw, ph))
                                    pg(leaf, 0f, mirrored, alpha = 0.10f, paper = false)
                                }
                                // Kıvrımın yuvarlaklığı: çizgiye yakın koyu, ortada parlak
                                drawRect(
                                    Brush.linearGradient(
                                        0f to Color.Black.copy(alpha = 0.30f),
                                        0.22f to Color.Transparent,
                                        0.55f to Color.White.copy(alpha = 0.18f),
                                        1f to Color.Black.copy(alpha = 0.10f),
                                        start = m, end = m - d * (len / 2f + 1f),
                                    ),
                                    size = Size(pw, ph),
                                )
                            }
                        }
                    }
                }
                // Sırt gölgesi (çift sayfa)
                if (double) drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f), Color.Transparent), startX = -18f, endX = 18f,
                    ),
                    topLeft = Offset(-18f, 0f), size = Size(36f, ph),
                )
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
