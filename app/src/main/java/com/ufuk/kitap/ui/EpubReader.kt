package com.ufuk.kitap.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ufuk.kitap.App
import com.ufuk.kitap.data.Book
import com.ufuk.kitap.epub.Epub
import com.ufuk.kitap.epub.EpubBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private class TapBridge(val onTap: () -> Unit) {
    @JavascriptInterface
    fun tap() = onTap()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReader(app: App, book: Book, s: ReadSettings, onClose: () -> Unit) {
    val result by produceState<Result<EpubBook>?>(null, book.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dir = app.repo.epubDir(book.id)
                if (dir.listFiles().isNullOrEmpty()) Epub.unzip(app.repo.bookFile(book), dir)
                Epub.open(dir)
            }
        }
    }
    val eb = result?.getOrNull()
    if (eb == null) {
        Box(Modifier.fillMaxSize().background(s.theme.bg), contentAlignment = Alignment.Center) {
            val err = result?.exceptionOrNull()
            if (err != null) Text("Kitap açılamadı: ${err.message}", color = s.theme.fg, modifier = Modifier.padding(24.dp))
            else CircularProgressIndicator()
        }
        return
    }
    val n = eb.spine.size.coerceAtLeast(1)
    val start = remember {
        val p = book.position.split(':')
        (p.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, n - 1) to (p.getOrNull(1)?.toFloatOrNull() ?: 0f)
    }
    var chapter by remember { mutableIntStateOf(start.first) }
    var frac by remember { mutableFloatStateOf(start.second) }
    val pendingFrac = remember { floatArrayOf(start.second) }
    var bars by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    fun css(): String {
        val (bg, fg) = s.theme.css
        val family = if (s.serif) "Georgia, 'Noto Serif', serif" else "'Roboto', 'Noto Sans', sans-serif"
        return """
html, body { background: $bg !important; color: $fg !important; }
body { font-size: ${s.fontPct}% !important; line-height: ${s.lineHeight} !important; font-family: $family !important;
  margin: 0 auto !important; padding: 18px 20px 120px !important; max-width: 46em !important; word-wrap: break-word; -webkit-text-size-adjust: none; }
p, div, span, li, td, blockquote, h1, h2, h3, h4, h5, h6 { color: inherit !important; background-color: transparent !important; }
p, div, span, li, blockquote { font-family: inherit !important; line-height: inherit !important; }
a { color: inherit !important; }
img, svg, video { max-width: 100% !important; height: auto !important; }
"""
    }

    fun applyCss(w: WebView, scrollTo: Float?) {
        val js = StringBuilder("(function(){var s=document.getElementById('ka-style');if(!s){s=document.createElement('style');s.id='ka-style';(document.head||document.documentElement).appendChild(s);}s.textContent=")
            .append(JSONObject.quote(css())).append(";")
        if (scrollTo != null) js.append("setTimeout(function(){var h=document.documentElement.scrollHeight-window.innerHeight;window.scrollTo(0,Math.max(0,h*$scrollTo));},80);")
        js.append("if(!window.__kaTap){window.__kaTap=1;document.addEventListener('click',function(e){var t=e.target;while(t){if(t.tagName==='A')return;t=t.parentElement;}KA.tap();});}})();")
        w.evaluateJavascript(js.toString(), null)
    }

    fun goChapter(i: Int, at: Float = 0f) {
        if (i !in 0 until n) return
        pendingFrac[0] = at
        frac = at
        chapter = i
    }

    // Bölüm değişince sayfayı yükle
    LaunchedEffect(chapter, web) {
        val w = web ?: return@LaunchedEffect
        w.loadUrl(Uri.fromFile(eb.spine[chapter]).toString())
    }
    // Görünüm değişince yeniden yüklemeden uygula
    LaunchedEffect(s.fontPct, s.theme, s.serif, s.lineHeight) {
        web?.let { applyCss(it, frac) }
    }
    // İlerlemeyi kaydet (kısa bir beklemeyle)
    LaunchedEffect(chapter, frac) {
        delay(800)
        app.repo.updateProgress(book.id, "$chapter:$frac", ((chapter + frac) / n).coerceIn(0f, 1f))
    }
    DisposableEffect(Unit) {
        onDispose {
            app.repo.updateProgress(book.id, "$chapter:$frac", ((chapter + frac) / n).coerceIn(0f, 1f))
            web?.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(s.theme.bg)) {
        if (bars) ReaderTopBar(
            title = book.title,
            subtitle = eb.chapterTitles.getOrElse(chapter) { "" },
            theme = s.theme,
            onClose = onClose,
            onToc = { showToc = true },
            onSettings = { showSettings = true },
        ) else Box(Modifier.statusBarsPadding())

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.builtInZoomControls = false
                    settings.setSupportZoom(false)
                    settings.textZoom = 100
                    addJavascriptInterface(TapBridge { post { bars = !bars } }, "KA")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            applyCss(view, pendingFrac[0])
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.scheme == "file") {
                                val path = u.path ?: return true
                                val idx = eb.spine.indexOfFirst { it.absolutePath == File(path).absolutePath }
                                if (idx >= 0 && idx != chapter) goChapter(idx)
                                else if (idx == chapter && u.fragment != null) {
                                    view.evaluateJavascript("var e=document.getElementById(${JSONObject.quote(u.fragment)});if(e)e.scrollIntoView();", null)
                                }
                                return true
                            }
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {
                            }
                            return true
                        }
                    }
                    setOnScrollChangeListener { v, _, y, _, _ ->
                        val wv = v as WebView
                        @Suppress("DEPRECATION")
                        val max = wv.contentHeight * wv.scale - wv.height
                        frac = if (max <= 1f) 0f else (y / max).coerceIn(0f, 1f)
                    }
                    web = this
                }
            },
        )

        if (bars) Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { goChapter(chapter - 1) }, enabled = chapter > 0) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Önceki bölüm", tint = s.theme.fg)
            }
            Text(
                "Bölüm ${chapter + 1}/$n  •  %${(((chapter + frac) / n) * 100).toInt()}",
                color = s.theme.fg, fontSize = 13.sp, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { goChapter(chapter + 1) }, enabled = chapter < n - 1) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, "Sonraki bölüm", tint = s.theme.fg)
            }
        } else Box(Modifier.navigationBarsPadding())
    }

    if (showToc) {
        val items = eb.toc.ifEmpty { eb.chapterTitles.mapIndexed { i, t -> t to i } }
        TocDialog(items, chapter, onPick = { goChapter(it) }, onDismiss = { showToc = false })
    }
    if (showSettings) ReadSettingsDialog(s) { showSettings = false }
}
