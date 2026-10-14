package com.panda.pandadl.fb

import com.panda.pandadl.core.FriendlyException
import com.panda.pandadl.net.Http

/** Una calidad encontrada para el video (HD/SD). */
data class FbQuality(val label: String, val url: String)

/**
 * Motor de Facebook: lee el HTML de la página del video y extrae las URLs
 * directas (hd_src / sd_src). Best-effort: algunos videos piden login
 * o son privados y no hay forma de bajarlos sin la sesión.
 */
object FacebookClient {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val ID_RES = listOf(
        Regex("""[?&]v=(\d{6,})"""),
        Regex("""/(?:videos|reel|watch)/(\d{6,})"""),
        Regex("""video\.php\?v=(\d{6,})"""),
        Regex("""fb\.watch/([A-Za-z0-9_-]+)"""),
    )

    /** Patrones (HD primero). */
    private val URL_PATTERNS = listOf(
        """"browser_native_hd_url":"([^"]+)"""" to "HD",
        """"browser_native_sd_url":"([^"]+)"""" to "SD",
        """"playable_url":"([^"]+)"""" to "SD",
        """hd_src:"([^"]+)""" to "HD",
        """hd_src\s*:\s*"([^"]+)""" to "HD",
        """sd_src:"([^"]+)""" to "SD",
        """sd_src\s*:\s*"([^"]+)""" to "SD",
    )

    fun extractId(url: String): String? =
        ID_RES.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    /** Devuelve todas las calidades encontradas (HD primero, sin duplicados). */
    fun fetchVideoUrls(pageUrl: String): List<FbQuality> {
        val (code, html) = Http.get(pageUrl, mapOf("User-Agent" to UA))
        if (code != 200) {
            throw FriendlyException("Facebook no abrió el video (HTTP $code).")
        }
        if ("login" in html.take(5000).lowercase() && html.length < 60_000) {
            throw FriendlyException(
                "Facebook pide iniciar sesión para este video. " +
                    "Prueba con uno público.",
            )
        }
        val found = linkedMapOf<String, String>() // url -> etiqueta
        for ((pattern, quality) in URL_PATTERNS) {
            for (m in Regex(pattern).findAll(html)) {
                val url = unescape(m.groupValues[1])
                if (url.startsWith("http") && url !in found) {
                    found[url] = quality
                }
            }
        }
        if (found.isEmpty()) {
            throw FriendlyException(
                "No se encontró el video en la página. " +
                    "¿Es privado o Facebook cambió su formato?",
            )
        }
        return found.entries
            .sortedBy { if (it.value == "HD") 0 else 1 }
            .map { FbQuality(it.value, it.key) }
    }

    /** Des-escapa los escapes JSON típicos de Facebook (\/, \uXXXX...). */
    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            sb.append(
                                s.substring(i + 2, i + 6).toIntOrNull(16)
                                    ?.toChar() ?: n,
                            )
                            i += 4
                        } else sb.append(n)
                    }
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
