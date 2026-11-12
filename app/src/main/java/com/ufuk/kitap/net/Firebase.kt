package com.ufuk.kitap.net

import android.util.Base64
import com.ufuk.kitap.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Firebase'e Google Play Hizmetleri olmadan, doğrudan web servisleri (REST) üzerinden bağlanır.
 * Böylece Google servisi olmayan Huawei cihazlarda da çalışır.
 */
class Firebase(private val prefs: Prefs) {

    val apiKey get() = prefs.str("fb_api_key").trim().ifEmpty { DEFAULT_API_KEY }
    val projectId get() = prefs.str("fb_project").trim().ifEmpty { DEFAULT_PROJECT }
    val uid get() = prefs.str("fb_uid")
    val email get() = prefs.str("fb_email")
    val isConfigured get() = apiKey.isNotEmpty() && projectId.isNotEmpty()
    val isSignedIn get() = uid.isNotEmpty() && prefs.str("fb_refresh").isNotEmpty()

    // ---------- Oturum ----------

    fun signIn(email: String, password: String) = auth("accounts:signInWithPassword", email, password)
    fun signUp(email: String, password: String) = auth("accounts:signUp", email, password)

    private fun auth(path: String, email: String, password: String) {
        check(isConfigured) { "Önce Firebase Web API anahtarını ve Proje kimliğini gir" }
        val body = JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true)
        val res = try {
            JSONObject(Http.request("POST", "https://identitytoolkit.googleapis.com/v1/$path?key=$apiKey", body.toString()))
        } catch (e: HttpError) {
            throw IllegalStateException(trAuthError(e.message ?: ""))
        }
        prefs.setStr("fb_uid", res.getString("localId"))
        prefs.setStr("fb_email", res.optString("email", email))
        prefs.setStr("fb_id_token", res.getString("idToken"))
        prefs.setStr("fb_refresh", res.getString("refreshToken"))
        prefs.setLong("fb_expires", System.currentTimeMillis() + (res.optString("expiresIn", "3600").toLong() - 120) * 1000)
    }

    fun signOut() {
        listOf("fb_uid", "fb_email", "fb_id_token", "fb_refresh").forEach { prefs.setStr(it, "") }
        prefs.setSet("pending_deletes", emptySet())
    }

    private fun trAuthError(m: String): String = when {
        m.contains("EMAIL_EXISTS") -> "Bu e-posta zaten kayıtlı, 'Giriş yap'ı kullan"
        m.contains("INVALID_LOGIN_CREDENTIALS") || m.contains("INVALID_PASSWORD") || m.contains("EMAIL_NOT_FOUND") -> "E-posta veya şifre hatalı"
        m.contains("WEAK_PASSWORD") -> "Şifre en az 6 karakter olmalı"
        m.contains("INVALID_EMAIL") -> "E-posta adresi geçersiz"
        m.contains("OPERATION_NOT_ALLOWED") || m.contains("CONFIGURATION_NOT_FOUND") -> "Firebase'de E-posta/Şifre girişi açık değil (Authentication > Sign-in method)"
        m.contains("API key not valid") || m.contains("API_KEY_INVALID") -> "Web API anahtarı geçersiz"
        else -> m
    }

    private fun token(): String {
        if (System.currentTimeMillis() < prefs.long("fb_expires") && prefs.str("fb_id_token").isNotEmpty()) return prefs.str("fb_id_token")
        val refresh = prefs.str("fb_refresh")
        check(refresh.isNotEmpty()) { "Oturum kapalı" }
        val res = JSONObject(
            Http.request(
                "POST", "https://securetoken.googleapis.com/v1/token?key=$apiKey",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                contentType = "application/x-www-form-urlencoded",
            )
        )
        prefs.setStr("fb_id_token", res.getString("id_token"))
        prefs.setStr("fb_refresh", res.optString("refresh_token", refresh))
        prefs.setLong("fb_expires", System.currentTimeMillis() + (res.optString("expires_in", "3600").toLong() - 120) * 1000)
        return prefs.str("fb_id_token")
    }

    // ---------- Firestore ----------

    private val docsRoot get() = "projects/$projectId/databases/(default)/documents"
    private val base get() = "https://firestore.googleapis.com/v1/$docsRoot"
    private fun auth() = mapOf("Authorization" to "Bearer ${token()}")

    /** users/<uid>/ altındaki yol */
    fun userPath(rel: String) = "users/$uid/$rel"

    fun get(path: String): JSONObject? = try {
        JSONObject(Http.request("GET", "$base/$path", headers = auth())).optJSONObject("fields") ?: JSONObject()
    } catch (e: HttpError) {
        if (e.code == 404) null else throw e
    }

    /** Koleksiyondaki tüm belgeler: (belge id, alanlar) */
    fun list(collection: String): List<Pair<String, JSONObject>> {
        val out = ArrayList<Pair<String, JSONObject>>()
        var pageToken = ""
        do {
            val url = "$base/$collection?pageSize=300" + (if (pageToken.isNotEmpty()) "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8") else "")
            val res = JSONObject(Http.request("GET", url, headers = auth()))
            val docs = res.optJSONArray("documents") ?: JSONArray()
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                out.add(d.getString("name").substringAfterLast('/') to (d.optJSONObject("fields") ?: JSONObject()))
            }
            pageToken = res.optString("nextPageToken", "")
        } while (pageToken.isNotEmpty())
        return out
    }

    /** Toplu yazma/silme (tek istekte, tümü ya hep ya hiç). */
    class Batch {
        val writes = JSONArray()
        var bytes = 0
    }

    fun set(b: Batch, path: String, fields: JSONObject) {
        b.writes.put(JSONObject().put("update", JSONObject().put("name", "$docsRoot/$path").put("fields", fields)))
        b.bytes += fields.toString().length
    }

    fun delete(b: Batch, path: String) {
        b.writes.put(JSONObject().put("delete", "$docsRoot/$path"))
    }

    fun commit(b: Batch) {
        if (b.writes.length() == 0) return
        Http.request("POST", "$base:commit", JSONObject().put("writes", b.writes).toString(), auth(), timeoutMs = 180_000)
    }

    companion object {
        // Ufuk'un "kitap-atolyesi" Firebase projesi (web anahtarı gizli değildir; erişimi Firestore kuralları korur)
        const val DEFAULT_API_KEY = "AIzaSyABOtxF7CfykMMADilhgr6kYAi1t2M_T04"
        const val DEFAULT_PROJECT = "kitap-atolyesi"

        fun s(v: String) = JSONObject().put("stringValue", v)
        fun i(v: Long) = JSONObject().put("integerValue", v.toString())
        fun d(v: Double) = JSONObject().put("doubleValue", v)
        fun bytes(v: ByteArray) = JSONObject().put("bytesValue", Base64.encodeToString(v, Base64.NO_WRAP))

        fun JSONObject.str(k: String): String = optJSONObject(k)?.optString("stringValue", "") ?: ""
        fun JSONObject.long(k: String): Long {
            val o = optJSONObject(k) ?: return 0L
            return o.optString("integerValue", "").toLongOrNull() ?: o.optDouble("doubleValue", 0.0).toLong()
        }
        fun JSONObject.dbl(k: String): Double {
            val o = optJSONObject(k) ?: return 0.0
            return if (o.has("doubleValue")) o.optDouble("doubleValue", 0.0) else o.optString("integerValue", "0").toDoubleOrNull() ?: 0.0
        }
        fun JSONObject.bytesOf(k: String): ByteArray {
            val s = optJSONObject(k)?.optString("bytesValue", "") ?: ""
            return if (s.isEmpty()) ByteArray(0) else Base64.decode(s, Base64.DEFAULT)
        }
    }
}
