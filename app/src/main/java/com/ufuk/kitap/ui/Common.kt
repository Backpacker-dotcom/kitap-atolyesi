package com.ufuk.kitap.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Uygulama genelinde eşitleme durumu */
object SyncState {
    val running = mutableStateOf(false)
    val message = mutableStateOf("")
    val tick = mutableStateOf(0) // kitaplık yenilensin diye
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

@Composable
fun FileImage(file: File?, maxSide: Int, modifier: Modifier, version: Any? = null, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<ImageBitmap?>(null, file?.path, version) {
        value = if (file == null) null else withContext(Dispatchers.IO) { Files.decodeSampled(file, maxSide)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = contentScale)
}

private val coverColors = listOf(
    Color(0xFF7A3E1D), Color(0xFF1F4E5F), Color(0xFF4A3B6B), Color(0xFF2F5D3A), Color(0xFF8C2F39), Color(0xFF5B4636),
)

/** Kapak görseli yoksa başlıklı renkli bir kapak çizer. */
@Composable
fun CoverPlaceholder(title: String, author: String, modifier: Modifier) {
    val c = coverColors[(title.hashCode() and 0x7fffffff) % coverColors.size]
    Box(
        modifier.background(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.75f)))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 5, modifier = Modifier.padding(10.dp),
            )
            if (author.isNotBlank()) Text(author, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Sil", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
