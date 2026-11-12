package com.ufuk.kitap.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpError(val code: Int, message: String) : Exception(message)

object Http {
    /** Basit JSON isteği. body null değilse JSON olarak gönderilir. Hata kodunda HttpError fırlatır. */
    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
        timeoutMs: Int = 60_000,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection PATCH desteklemez: Google API'lerinin kabul ettiği başlıkla gönder
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw HttpError(code, errorMessage(text, code))
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun errorMessage(text: String, code: Int): String = try {
        val o = JSONObject(text)
        val e = o.opt("error")
        when (e) {
            is JSONObject -> e.optString("message", "HTTP $code")
            is String -> o.optString("error_description", e)
            else -> "HTTP $code"
        }
    } catch (_: Exception) {
        if (text.isNotBlank()) text.take(200) else "HTTP $code"
    }
}

object Gemini {
    /** Gemini'ye metin istemi gönderir, yanıt metnini döndürür. */
    fun generate(apiKey: String, model: String, prompt: String): String {
        if (apiKey.isBlank()) throw IllegalStateException("Önce Ayarlar'dan Gemini API anahtarını gir")
        val m = model.ifBlank { "gemini-2.5-flash" }.trim()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.7))
        val res = Http.request(
            "POST",
            "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
            body.toString(),
            mapOf("x-goog-api-key" to apiKey.trim()),
            timeoutMs = 120_000,
        )
        val o = JSONObject(res)
        val cands = o.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = o.optJSONObject("promptFeedback")?.optString("blockReason")
            throw IllegalStateException("Yanıt gelmedi" + (if (!reason.isNullOrBlank()) " ($reason)" else ""))
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString().trim()
    }

    private const val RULES = "Metin Türkçe bir e-kitaptan. Şu biçim işaretlerini aynen koru: **kalın**, *italik*, '# ' ile başlayan ara başlıklar, '***' sahne araları ve ![](images/...) görsel satırları. Her paragraf ayrı satırda olsun. Yalnızca sonucu yaz; açıklama, giriş cümlesi veya tırnak ekleme."

    fun promptFix(text: String) = "Aşağıdaki metnin yazım, noktalama ve dil bilgisi hatalarını düzelt. Üslubu ve anlamı değiştirme. $RULES\n\n---\n$text"
    fun promptPolish(text: String) = "Aşağıdaki metni daha akıcı ve edebi hale getir; anlamı, olay örgüsünü ve yazarın sesini koru, uzunluğu fazla değiştirme. $RULES\n\n---\n$text"
    fun promptContinue(bookTitle: String, chapterTitle: String, text: String) =
        "\"$bookTitle\" adlı kitabın \"$chapterTitle\" bölümü aşağıda. Aynı üslupla, kaldığı yerden devam eden 2-4 paragraf yaz. Sadece yeni paragrafları yaz. $RULES\n\n---\n${text.takeLast(6000)}"
    fun promptTitle(text: String) = "Aşağıdaki kitap bölümü için 5 kısa ve etkileyici bölüm başlığı öner. Her birini ayrı satıra yaz, numara veya işaret koyma.\n\n---\n${text.take(8000)}"
    fun promptCustom(instruction: String, text: String) = "$instruction\n$RULES\n\n---\n$text"
}
