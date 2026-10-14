package com.panda.pandadl.ig

import com.panda.pandadl.core.FriendlyException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Login directo a Instagram por HTTP, sin WebView (v2.7).
 * Usa el endpoint /api/v1/web/accounts/login/ajax/ con el formato
 * enc_password que espera la web.
 */
object InstagramAuth {

    private const val APP_ID = "936619743392459"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Moto G32) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * Inicia sesión y devuelve el cookie-string para guardar en InstagramSession.
     * Lanza FriendlyException si falla.
     */
    fun login(username: String, password: String): String {
        // Paso 1: GET a instagram.com para obtener el csrftoken y www-claim.
        val (csrfToken, wwwClaim, initCookies) = fetchTokens()

        // Paso 2: POST al endpoint de login AJAX.
        val timestamp = System.currentTimeMillis() / 1000
        val encPassword = "#PWD_INSTAGRAM_BROWSER:0:$timestamp:$password"
        val params = mapOf(
            "username" to username,
            "enc_password" to encPassword,
            "queryParams" to "{}",
            "optIntoOneTap" to "false",
        )
        val postData = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val conn = URL("https://www.instagram.com/api/v1/web/accounts/login/ajax/")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 25_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("X-IG-App-ID", APP_ID)
            conn.setRequestProperty("X-CSRFToken", csrfToken)
            // v3.7.3: X-IG-WWW-Claim es obligatorio (sin él da HTTP 400).
            if (wwwClaim.isNotEmpty()) {
                conn.setRequestProperty("X-IG-WWW-Claim", wwwClaim)
            }
            conn.setRequestProperty("Origin", "https://www.instagram.com")
            conn.setRequestProperty("Referer", "https://www.instagram.com/")
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
            if (initCookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", initCookies)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(postData)
                it.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()

            // Extraer cookies de la respuesta.
            val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            val cookieString = setCookies.joinToString("; ") { it.substringBefore(";") }

            if (code != 200) {
                throw FriendlyException("Instagram no aceptó el login (HTTP $code).")
            }

            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                throw FriendlyException("Respuesta inesperada de Instagram al iniciar sesión.")
            }

            if (json.optBoolean("authenticated", false)) {
                if (!cookieString.contains("sessionid=")) {
                    throw FriendlyException(
                        "Login OK pero sin cookie de sesión. Intenta de nuevo.",
                    )
                }
                return cookieString
            }

            // No autenticado: ver el motivo.
            val message = json.optString("message").takeIf { it.isNotBlank() }
            val userMsg = when {
                json.optBoolean("checkpoint_required", false) ->
                    "Instagram pide verificación adicional (revisa tu email/SMS)."
                json.optString("status") == "fail" ->
                    message ?: "Usuario o contraseña incorrectos."
                else -> message ?: "No se pudo iniciar sesión."
            }
            throw FriendlyException(userMsg)
        } finally {
            conn.disconnect()
        }
    }

    /** GET inicial para obtener csrftoken y www-claim. Devuelve (csrf, claim, cookies). */
    private fun fetchTokens(): Triple<String, String, String> {
        val conn = URL("https://www.instagram.com/").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 25_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "text/html")
            conn.setRequestProperty("X-IG-App-ID", APP_ID)

            // Leer el HTML para extraer el www-claim.
            val html = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) { "" }

            val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            val cookies = setCookies.joinToString("; ") { it.substringBefore(";") }
            val csrf = Regex("""\bcsrftoken=([^;]+)""").find(cookies)
                ?.groupValues?.get(1)
                ?: throw FriendlyException(
                    "No se pudo obtener el token de seguridad de Instagram.",
                )
            // El www-claim viene en el HTML como "x-ig-www-claim":"hmac..."
            val claim = Regex(""""x-ig-www-claim"\s*:\s*"([^"]+)"""").find(html)
                ?.groupValues?.get(1).orEmpty()
            return Triple(csrf, claim, cookies)
        } finally {
            conn.disconnect()
        }
    }
}
