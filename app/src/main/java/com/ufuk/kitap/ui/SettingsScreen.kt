@file:OptIn(ExperimentalMaterial3Api::class)

package com.ufuk.kitap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufuk.kitap.App
import com.ufuk.kitap.net.Gemini
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val FIRESTORE_RULES = """rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}"""

@Composable
fun SettingsScreen(app: App, onBack: () -> Unit, sync: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = app.prefs
    var geminiKey by remember { mutableStateOf(p.str("gemini_key")) }
    var geminiModel by remember { mutableStateOf(p.str("gemini_model", "gemini-3.6-flash")) }
    var testing by remember { mutableStateOf(false) }
    var fbKey by remember { mutableStateOf(app.firebase.apiKey) }
    var fbProject by remember { mutableStateOf(app.firebase.projectId) }
    var advanced by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(p.str("fb_email")) }
    var password by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(app.firebase.isSignedIn) }
    var authBusy by remember { mutableStateOf(false) }
    val syncing by SyncState.running
    val syncMsg by SyncState.message

    fun saveFb() { p.setStr("fb_api_key", fbKey.trim()); p.setStr("fb_project", fbProject.trim()) }

    fun doAuth(register: Boolean) {
        saveFb()
        authBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (register) app.firebase.signUp(email, password) else app.firebase.signIn(email, password)
                }
                signedIn = true
                password = ""
                toast(ctx, if (register) "Hesap oluşturuldu, eşitleme başlıyor" else "Giriş yapıldı, eşitleme başlıyor")
                sync()
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Olmadı")
            }
            authBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = {
                    IconButton(onClick = {
                        p.setStr("gemini_key", geminiKey.trim()); p.setStr("gemini_model", geminiModel.trim()); saveFb(); onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ---------- Senkronizasyon ----------
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Telefon ↔ Tablet eşitleme (Firebase)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Kitapların, kaldığın sayfa ve yazdığın kitaplar iki cihazda aynı olur. Google servisi gerektirmez; Huawei tablette de çalışır. 30 MB'tan büyük dosyalarda yalnızca kaldığın yer eşitlenir.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!signedIn) {
                            if (advanced) {
                                OutlinedTextField(fbKey, { fbKey = it.trim() }, label = { Text("Web API anahtarı (AIza…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(fbProject, { fbProject = it.trim() }, label = { Text("Proje kimliği (Project ID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            } else {
                                Text("Proje: $fbProject", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedTextField(email, { email = it.trim() }, label = { Text("E-posta") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(password, { password = it }, label = { Text("Şifre (en az 6 karakter)") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { doAuth(false) }, enabled = !authBusy && email.isNotBlank() && password.length >= 6) { Text("Giriş yap") }
                                OutlinedButton(onClick = { doAuth(true) }, enabled = !authBusy && email.isNotBlank() && password.length >= 6) { Text("Hesap oluştur") }
                                if (authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                "İlk cihazda \"Hesap oluştur\", ikinci cihazda aynı bilgilerle \"Giriş yap\".",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("Giriş yapıldı: ${app.firebase.email}", fontSize = 14.sp)
                            val last = p.long("last_sync")
                            if (last > 0) Text(
                                "Son eşitleme: " + SimpleDateFormat("d MMM HH:mm", Locale("tr")).format(Date(last)),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (syncMsg.isNotEmpty()) Text(syncMsg, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = sync, enabled = !syncing) { Text("Şimdi eşitle") }
                                OutlinedButton(onClick = { app.firebase.signOut(); signedIn = false }) { Text("Çıkış yap") }
                                if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        if (!signedIn && !advanced) TextButton(onClick = { advanced = true }) { Text("Farklı Firebase projesi kullan") }
                        TextButton(onClick = { copy(ctx, FIRESTORE_RULES); toast(ctx, "Firestore kuralları kopyalandı") }) {
                            Text("Firestore güvenlik kurallarını kopyala")
                        }
                    }
                }

                // ---------- Yapay zekâ ----------
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Yapay zekâ (Gemini)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Yazım düzeltme, akıcılaştırma, devam önerisi ve başlık önerisi için. \"Anahtar al\"a dokun, Google hesabınla gir, \"Create API key\" ile oluşturduğun anahtarı kopyalayıp buraya yapıştır ve \"Dene\"ye bas. Bir cihaza girmen yeter; eşitlemeyle diğerine de gelir.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(geminiKey, { geminiKey = it.trim(); p.setStr("gemini_key", it.trim()) }, label = { Text("Gemini API anahtarı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(geminiModel, { geminiModel = it.trim(); p.setStr("gemini_model", it.trim()) }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { open(ctx, "https://aistudio.google.com/apikey") }) { Text("Anahtar al") }
                            Button(onClick = {
                                testing = true
                                scope.launch {
                                    try {
                                        val r = withContext(Dispatchers.IO) { Gemini.generate(geminiKey, geminiModel, "Sadece 'Hazırım' yaz.") }
                                        toast(ctx, "Çalışıyor: $r")
                                    } catch (e: Exception) {
                                        toast(ctx, "Hata: ${e.message}")
                                    }
                                    testing = false
                                }
                            }, enabled = geminiKey.isNotBlank() && !testing) { Text("Dene") }
                            if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                Text(
                    "Kitap Atölyesi • sürüm " + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName,
                    fontSize = 12.sp, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp).width(1.dp))
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("kurallar", text))
}

private fun open(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}
