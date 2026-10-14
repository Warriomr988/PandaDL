package com.panda.pandadl.ig

import android.content.Context

/**
 * Guarda la cookie de sesión de Instagram tras el login en el WebView.
 * Sin sessionid Instagram no entrega los datos del post (login wall).
 */
class InstagramSession(context: Context) {

    private val prefs =
        context.getSharedPreferences("ig_session", Context.MODE_PRIVATE)

    /** Extrae sessionid/ds_user_id/csrftoken del cookie-string del WebView. */
    fun saveFromCookieString(cookie: String): Boolean {
        val wanted = listOf("sessionid", "ds_user_id", "csrftoken")
        val found = wanted.mapNotNull { name ->
            Regex("""\b$name=([^;]+)""").find(cookie)?.groupValues?.get(1)
                ?.let { name to it }
        }.toMap()
        if (!found.containsKey("sessionid")) return false
        prefs.edit().apply {
            for ((k, v) in found) putString(k, v)
            apply()
        }
        return true
    }

    /** Cabecera Cookie lista para los requests. Null si no hay sesión. */
    fun cookieHeader(): String? {
        val sid = prefs.getString("sessionid", null) ?: return null
        val parts = mutableListOf("sessionid=$sid")
        prefs.getString("ds_user_id", null)?.let { parts += "ds_user_id=$it" }
        prefs.getString("csrftoken", null)?.let { parts += "csrftoken=$it" }
        return parts.joinToString("; ")
    }

    fun isConnected(): Boolean = prefs.contains("sessionid")

    fun clear() {
        prefs.edit().clear().apply()
    }
}
