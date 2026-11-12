package com.ufuk.kitap.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object Files {

    fun sha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {
        }
        return name ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "dosya")
    }

    fun copyUriTo(ctx: Context, uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Dosya açılamadı")
        input.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
    }

    /** UTF-8 dener, olmazsa Türkçe Windows kodlamasıyla okur. */
    fun decodeText(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1254"))
        }
    }

    fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= maxSide || opts.outHeight / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        }
    }

    /** Görseli makul boyuta küçültüp JPEG olarak kaydeder (e-kitap ve eşitleme için hafif kalsın). */
    fun saveScaledJpeg(src: Bitmap, dest: File, maxSide: Int = 1600, quality: Int = 85) {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun importImage(ctx: Context, uri: Uri, dest: File, maxSide: Int = 1600): Boolean {
        val tmp = File(ctx.cacheDir, "img_in_" + System.nanoTime())
        return try {
            copyUriTo(ctx, uri, tmp)
            val bmp = decodeSampled(tmp, maxSide * 2) ?: return false
            saveScaledJpeg(bmp, dest, maxSide)
            true
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
