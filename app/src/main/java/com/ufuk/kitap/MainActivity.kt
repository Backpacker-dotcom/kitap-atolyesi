package com.ufuk.kitap

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.ufuk.kitap.ui.ChapterEditorScreen
import com.ufuk.kitap.ui.KitapTheme
import com.ufuk.kitap.ui.LibraryScreen
import com.ufuk.kitap.ui.ManuscriptScreen
import com.ufuk.kitap.ui.ReaderScreen
import com.ufuk.kitap.ui.SettingsScreen
import com.ufuk.kitap.ui.SyncState
import com.ufuk.kitap.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String) : Screen
    data class Manuscript(val id: String) : Screen
    data class Chapter(val msId: String, val chapterId: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private val pendingOpen = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val app = application as App
        setContent {
            KitapTheme {
                AppRoot(app, pendingOpen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> streamExtra(i)
            else -> null
        }
        if (uri != null) pendingOpen.value = uri
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
}

/** Eşitlemeyi arka planda başlatır; sonucu SyncState'e yazar. */
fun startSync(app: App, scope: CoroutineScope, quiet: Boolean = false, onDone: (String) -> Unit = {}) {
    if (SyncState.running.value) return
    if (!app.firebase.isConfigured || !app.firebase.isSignedIn) {
        if (!quiet) onDone("Eşitleme için Ayarlar'dan Firebase'e giriş yap")
        return
    }
    SyncState.running.value = true
    scope.launch {
        val msg = withContext(Dispatchers.IO) {
            try {
                app.sync.syncAll { s -> SyncState.message.value = s }
            } catch (e: Exception) {
                "Eşitleme hatası: ${e.message}"
            }
        }
        SyncState.message.value = msg
        SyncState.running.value = false
        SyncState.tick.value++
        onDone(msg)
    }
}

@Composable
fun AppRoot(app: App, pendingOpen: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Library) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    LaunchedEffect(Unit) {
        startSync(app, scope, quiet = true)
    }

    LaunchedEffect(pendingOpen.value) {
        val uri = pendingOpen.value ?: return@LaunchedEffect
        pendingOpen.value = null
        try {
            val b = withContext(Dispatchers.IO) { app.repo.importUri(uri) }
            push(Screen.Reader(b.id))
        } catch (e: Exception) {
            toast(ctx, "Açılamadı: ${e.message}")
        }
    }

    when (val s = stack.last()) {
        Screen.Library -> LibraryScreen(
            app = app,
            openBook = { push(Screen.Reader(it)) },
            openManuscript = { push(Screen.Manuscript(it)) },
            openSettings = { push(Screen.Settings) },
            sync = { startSync(app, scope) { toast(ctx, it) } },
        )
        is Screen.Reader -> ReaderScreen(app, s.bookId, onClose = {
            pop()
            val b = app.repo.book(s.bookId)
            if (b != null) scope.launch(Dispatchers.IO) { try { app.sync.pushProgress(b) } catch (_: Exception) {} }
        })
        is Screen.Manuscript -> ManuscriptScreen(
            app, s.id,
            onBack = { pop() },
            openChapter = { push(Screen.Chapter(s.id, it)) },
            openReader = { push(Screen.Reader(it)) },
        )
        is Screen.Chapter -> ChapterEditorScreen(app, s.msId, s.chapterId, onBack = { pop() })
        Screen.Settings -> SettingsScreen(app, onBack = { pop() }, sync = { startSync(app, scope) { toast(ctx, it) } })
    }
}
