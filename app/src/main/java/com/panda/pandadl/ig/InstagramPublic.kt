package com.panda.pandadl.ig

import com.panda.pandadl.core.FriendlyException
import com.panda.pandadl.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.0: lee posts públicos de Instagram SIN login.
 *
 * Truco: pedir la página con el user-agent de Googlebot. Instagram sirve a
 * los crawlers (por SEO) una versión con los datos del medio incluidos en
 * bloques <script data-sjs>{...}</script>: video_versions, image_versions2
 * y carousel_media con URLs directas al CDN.
 *
 * Técnica inspirada en InstaGrab (GPL-3.0, faisaljs/InstaGrab);
 * reimplementada desde cero para PandaDL.
 *
 * Las historias NO funcionan por aquí (requieren sesión): siguen usando
 * [InstagramClient] con la cuenta conectada.
 */
object InstagramPublic {

    private const val GOOGLEBOT_UA = "Googlebot/2.1 (+http://www.google.com/bot.html)"

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val DATA_SJS_RE =
        Regex(
            """<script\b[^>]*\bdata-sjs[^>]*>(\{.+?\})</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** Convierte el shortcode (p. ej. Cs9QmkrviAr) a su ID numérico de medio. */
    fun shortcodeToMediaId(shortcode: String): String {
        var id = 0L
        for (ch in shortcode) {
            val d = ALPHABET.indexOf(ch)
            if (d < 0) throw FriendlyException("Enlace de Instagram inválido.")
            id = try {
                Math.addExact(Math.multiplyExact(id, 64L), d.toLong())
            } catch (e: ArithmeticException) {
                throw FriendlyException("Enlace de Instagram inválido.")
            }
        }
        return id.toString()
    }

    /** Resuelve un post/reel/foto público. Lanza [FriendlyException] si no se puede. */
    fun fetchMedia(shortcode: String): IgMedia {
        val (code, html) = Http.get(
            "https://www.instagram.com/p/$shortcode/",
            mapOf("User-Agent" to GOOGLEBOT_UA),
        )
        if (code != 200 || html.isBlank()) {
            throw FriendlyException(
                "Instagram no devolvió el post (HTTP $code). " +
                    "Revisa tu conexión e inténtalo de nuevo.",
            )
        }
        return extractMedia(html, shortcode)
    }

    private fun extractMedia(html: String, shortcode: String): IgMedia {
        val mediaId = shortcodeToMediaId(shortcode)
        var product: JSONObject? = null
        for (m in DATA_SJS_RE.findAll(html)) {
            val json = runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
                ?: continue
            val found = findProduct(json, mediaId)
            if (found != null) {
                product = found
                break
            }
        }
        val p = product ?: throw FriendlyException(
            "No pude leer ese post. ¿Es privado o fue eliminado?",
        )
        val items = mutableListOf<IgItem>()
        val carousel = p.optJSONArray("carousel_media")
        if (carousel != null) {
            for (i in 0 until carousel.length()) {
                carousel.optJSONObject(i)?.let { items += parseSingle(it) }
            }
        } else {
            items += parseSingle(p)
        }
        val ok = items.filter { it.videoUrl != null || it.imageUrl != null }
        if (ok.isEmpty()) throw FriendlyException("El post no trajo nada descargable.")
        return IgMedia(
            shortcode = shortcode,
            userName = p.optJSONObject("user")?.optString("username").orEmpty(),
            caption = p.optJSONObject("caption")?.optString("text").orEmpty(),
            items = ok,
            // La versión para crawlers no trae datos de música.
            musicArtist = null,
            musicTitle = null,
            audioUrl = null,
        )
    }

    /** Busca recursivamente el dict del producto por su pk/id. */
    private fun findProduct(value: Any?, mediaId: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("if_not_gated_logged_out")?.let { gated ->
                    val pk = gated.optString("pk").ifEmpty { gated.optString("id") }
                    if (pk == mediaId) return gated
                }
                val pk = value.optString("pk").ifEmpty { value.optString("id") }
                if (pk == mediaId &&
                    (value.has("video_versions") ||
                        value.has("carousel_media") ||
                        value.has("image_versions2"))
                ) {
                    return value
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    findProduct(value.opt(keys.next()), mediaId)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findProduct(value.opt(i), mediaId)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseSingle(o: JSONObject): IgItem {
        val videoUrl = bestVideo(o.optJSONArray("video_versions"))
        val imageUrl =
            bestImage(o.optJSONObject("image_versions2")?.optJSONArray("candidates"))
        return IgItem(
            isVideo = videoUrl != null,
            videoUrl = videoUrl,
            imageUrl = imageUrl,
        )
    }

    /** Elige el video de mayor resolución (o el primero si no informan tamaño). */
    private fun bestVideo(versions: JSONArray?): String? {
        if (versions == null) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until versions.length()) {
            val v = versions.optJSONObject(i) ?: continue
            val url = v.optString("url")
            val w = v.optInt("width", 0)
            if (url.startsWith("http") && w >= bestW) {
                bestW = w
                best = url
            }
        }
        if (best == null) {
            for (i in 0 until versions.length()) {
                val url = versions.optJSONObject(i)?.optString("url").orEmpty()
                if (url.startsWith("http")) {
                    best = url
                    break
                }
            }
        }
        return best
    }

    /** Elige la imagen de mayor resolución. */
    private fun bestImage(candidates: JSONArray?): String? {
        if (candidates == null) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            val url = c.optString("url")
            val w = c.optInt("width", 0)
            if (url.startsWith("http") && w > bestW) {
                bestW = w
                best = url
            }
        }
        return best
    }
}
