package com.panda.pandadl.ig

import com.panda.pandadl.core.FriendlyException
import com.panda.pandadl.net.Http
import org.json.JSONArray
import org.json.JSONObject

/** Medio individual dentro de un post (foto o video). */
data class IgItem(
    val isVideo: Boolean,
    val videoUrl: String?,
    val imageUrl: String?,
)

/** Una historia (foto o video). */
data class IgStoryItem(
    val isVideo: Boolean,
    val imageUrl: String?,
    val videoUrl: String?,
    val takenAt: Long,
)

/** Post de Instagram ya resuelto. */
data class IgMedia(
    val shortcode: String,
    val userName: String,
    val caption: String,
    val items: List<IgItem>,
    val musicArtist: String?,
    val musicTitle: String?,
    /** URL directa del audio si el JSON la trae (no siempre viene). */
    val audioUrl: String?,
) {
    val hasMusic: Boolean get() = musicArtist != null || musicTitle != null
    val musicLabel: String get() =
        listOfNotNull(musicArtist, musicTitle).joinToString(" – ").ifEmpty { "música" }
}

/**
 * Lee los datos de un post con la sesión del usuario.
 * Endpoint: /p/<shortcode>/?__a=1&__d=dis (sirve para p, reel y tv).
 */
object InstagramClient {

    // App ID público de la web de Instagram.
    private const val APP_ID = "936619743392459"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Moto G32) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val SHORTCODE_RE =
        Regex("""instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")

    fun extractShortcode(url: String): String? =
        SHORTCODE_RE.find(url)?.groupValues?.get(1)

    /** Saca el @usuario de un enlace tipo instagram.com/stories/usuario/... */
    fun extractStoriesUsername(url: String): String? =
        Regex("""instagram\.com/stories/([A-Za-z0-9._]{1,30})""")
            .find(url)?.groupValues?.get(1)

    /** Acepta "@usuario" o "usuario" sueltos (para el pegado mágico). */
    fun parseAtUsername(text: String): String? =
        Regex("""^@?([A-Za-z0-9._]{1,30})$""").find(text.trim())
            ?.groupValues?.get(1)
            ?.takeIf { it.contains(".") || it.length > 2 }

    /** Resuelve el ID numérico del usuario desde su perfil. */
    fun userIdFromUsername(username: String, cookie: String): String {
        val clean = username.removePrefix("@")
        val headers = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html",
            "Cookie" to cookie,
        )
        val (code, html) = Http.get("https://www.instagram.com/$clean/", headers)
        Regex(""""profilePage_(\d+)"""").find(html)?.groupValues?.get(1)?.let { return it }
        // Plan B: API web_profile_info.
        val (code2, body2) = Http.get(
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=$clean",
            mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
                "X-IG-App-ID" to APP_ID,
                "Cookie" to cookie,
            ),
        )
        if (code2 == 200) {
            JSONObject(body2).optJSONObject("data")?.optJSONObject("user")
                ?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        throw FriendlyException(
            "No encontré el perfil @$clean (HTTP $code). " +
                "¿Existe y tu sesión sigue válida?",
        )
    }

    /** Historias activas del usuario (requiere sesión). */
    fun fetchStories(userId: String, cookie: String): List<IgStoryItem> {
        val (code, body) = Http.get(
            "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$userId",
            mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
                "Cookie" to cookie,
            ),
        )
        if (code == 401 || code == 403) {
            throw FriendlyException(
                "Instagram rechazó la sesión (HTTP $code). Reconecta tu cuenta.",
            )
        }
        if (code != 200 || !body.trimStart().startsWith("{")) {
            throw FriendlyException("Instagram no devolvió las historias (HTTP $code).")
        }
        val items = JSONObject(body).optJSONArray("reels_media")
            ?.optJSONObject(0)?.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<IgStoryItem>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val videoUrl = o.optJSONArray("video_versions")
                ?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
            val imageUrl = bestImage(o.optJSONObject("image_versions2")?.optJSONArray("candidates"))
            if (videoUrl == null && imageUrl == null) continue
            out += IgStoryItem(
                isVideo = videoUrl != null,
                imageUrl = imageUrl,
                videoUrl = videoUrl,
                takenAt = o.optLong("taken_at", 0),
            )
        }
        return out
    }

    fun fetchMedia(shortcode: String, cookie: String): IgMedia {
        val apiUrl = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
        val (code, body) = Http.get(
            apiUrl,
            mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
                "X-IG-App-ID" to APP_ID,
                "Cookie" to cookie,
            ),
        )
        if (code == 404 || code == 403) {
            throw FriendlyException(
                "Instagram no entregó el post (HTTP $code). " +
                    "¿Sesión expirada? Reconecta tu cuenta.",
            )
        }
        if (code != 200 || !body.trimStart().startsWith("{")) {
            throw FriendlyException(
                "Instagram respondió algo inesperado (HTTP $code). " +
                    "Si cambian su sistema, toca actualizar la app.",
            )
        }
        val items = JSONObject(body).optJSONArray("items")
            ?: throw FriendlyException(
                "Instagram no devolvió el post. ¿Es privado o el enlace es inválido?",
            )
        if (items.length() == 0) {
            throw FriendlyException("El post no tiene contenido descargable.")
        }
        val item = items.getJSONObject(0)
        var media = parseItem(shortcode, item)

        // v3.1: si no se detectó música, intentar el endpoint completo
        // /api/v1/media/<pk>/info/ que sí trae clips_metadata.
        // v3.9.3: múltiples métodos por si uno falla.
        if (!media.hasMusic) {
            val pk = item.optString("pk").takeIf { it.isNotBlank() }
                ?: item.optString("id").takeIf { it.isNotBlank() }
            if (pk != null) {
                // Método 1: /api/v1/media/<pk>/info/
                try {
                    val enriched = fetchMediaInfo(pk, cookie)
                    if (enriched.hasMusic) {
                        media = media.copy(
                            musicArtist = enriched.musicArtist,
                            musicTitle = enriched.musicTitle,
                            audioUrl = enriched.audioUrl ?: media.audioUrl,
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.d("InstagramClient", "método 1 falló: ${e.message}")
                }
            }
            // Método 2: scraping del HTML (tiene JSON embebido con la música).
            if (!media.hasMusic) {
                try {
                    val scraped = scrapeMusicFromHtml(shortcode, cookie)
                    if (scraped != null) {
                        media = media.copy(
                            musicArtist = scraped.first,
                            musicTitle = scraped.second,
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.d("InstagramClient", "método 2 falló: ${e.message}")
                }
            }
            // Método 3: oEmbed (a veces trae el título con la música).
            if (!media.hasMusic) {
                try {
                    val oembed = fetchOEmbed(shortcode)
                    if (oembed != null) {
                        media = media.copy(musicTitle = oembed)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("InstagramClient", "método 3 falló: ${e.message}")
                }
            }
        }
        return media
    }

    /**
     * v3.1: Endpoint completo de metadata. Devuelve clips_metadata con
     * la música incluso en fotos, cosa que el endpoint __a=1 no siempre hace.
     */
    fun fetchMediaInfo(pk: String, cookie: String): IgMedia {
        val apiUrl = "https://www.instagram.com/api/v1/media/$pk/info/"
        val (code, body) = Http.get(
            apiUrl,
            mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
                "X-IG-App-ID" to APP_ID,
                "Cookie" to cookie,
            ),
        )
        if (code != 200 || !body.trimStart().startsWith("{")) {
            throw FriendlyException("No se pudo leer la info del post (HTTP $code).")
        }
        val items = JSONObject(body).optJSONArray("items")
            ?: throw FriendlyException("Sin items en la respuesta.")
        if (items.length() == 0) throw FriendlyException("Post vacío.")
        return parseItem(pk, items.getJSONObject(0))
    }

    /**
     * v3.9.3 Método 2: scraping del HTML del post.
     * El HTML tiene JSON embebido con la info de música.
     * Retorna (artista, título) o null.
     */
    private fun scrapeMusicFromHtml(shortcode: String, cookie: String): Pair<String?, String?>? {
        val (code, html) = Http.get(
            "https://www.instagram.com/p/$shortcode/",
            mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html",
                "Cookie" to cookie,
            ),
        )
        if (code != 200) return null
        // Buscar "music_title":"..." y "music_artist":"..." en el JSON embebido.
        val title = Regex(""""music_title"\s*:\s*"([^"]+)"""").find(html)
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val artist = Regex(""""music_artist"\s*:\s*"([^"]+)"""").find(html)
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        // También "song_name" y "artist_name".
        val title2 = title ?: Regex(""""song_name"\s*:\s*"([^"]+)"""").find(html)
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val artist2 = artist ?: Regex(""""artist_name"\s*:\s*"([^"]+)"""").find(html)
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        if (title2 == null && artist2 == null) return null
        return Pair(artist2, title2)
    }

    /**
     * v3.9.3 Método 3: oEmbed.
     * A veces el título del oEmbed menciona la canción.
     * Retorna el título o null.
     */
    private fun fetchOEmbed(shortcode: String): String? {
        val (code, body) = Http.get(
            "https://www.instagram.com/p/$shortcode/embed/",
            mapOf("User-Agent" to UA, "Accept" to "text/html"),
        )
        if (code != 200) return null
        // El embed a veces tiene "music" en el HTML.
        val m = Regex("""(?i)music[^<]{0,100}""").find(body)
        return m?.value?.takeIf { it.length > 10 }
    }

    private fun parseItem(shortcode: String, item: JSONObject): IgMedia {
        val userName = item.optJSONObject("user")?.optString("username").orEmpty()
        val caption = item.optJSONObject("caption")?.optString("text").orEmpty()

        val mediaType = item.optInt("media_type", 1) // 1=foto 2=video 8=carrusel
        val parsedItems = mutableListOf<IgItem>()
        if (mediaType == 8) {
            val carousel = item.optJSONArray("carousel_media") ?: JSONArray()
            for (i in 0 until carousel.length()) {
                parsedItems += parseSingle(carousel.getJSONObject(i))
            }
        } else {
            parsedItems += parseSingle(item)
        }

        val music = item.optJSONObject("clips_music_attribution_info")
        val artistOld = music?.optString("artist_name")?.takeIf { it.isNotBlank() }
        val titleOld = music?.optString("song_name")?.takeIf { it.isNotBlank() }
        // v2.7: formato actual para fotos con música (clips_metadata).
        val assetInfo = item.optJSONObject("clips_metadata")
            ?.optJSONObject("music_info")
            ?.optJSONObject("music_asset_info")
        // v3.9.2: más rutas donde Instagram esconde la música.
        val musicInfoDirect = item.optJSONObject("music_info")
            ?.optJSONObject("music_asset_info")
        val audioInfo = item.optJSONObject("audio")
        val artist = assetInfo?.optString("display_artist")?.takeIf { it.isNotBlank() }
            ?: musicInfoDirect?.optString("display_artist")?.takeIf { it.isNotBlank() }
            ?: audioInfo?.optString("artist")?.takeIf { it.isNotBlank() }
            ?: artistOld
        val title = assetInfo?.optString("title")?.takeIf { it.isNotBlank() }
            ?: musicInfoDirect?.optString("title")?.takeIf { it.isNotBlank() }
            ?: audioInfo?.optString("title")?.takeIf { it.isNotBlank() }
            ?: item.optString("audio_name")?.takeIf { it.isNotBlank() }
            ?: titleOld
        val audioUrl = findAudioUrl(item)

        return IgMedia(
            shortcode = shortcode,
            userName = userName,
            caption = caption,
            items = parsedItems.filter { it.videoUrl != null || it.imageUrl != null },
            musicArtist = artist,
            musicTitle = title,
            audioUrl = audioUrl,
        )
    }

    private fun parseSingle(o: JSONObject): IgItem {
        val videoUrl = o.optJSONArray("video_versions")
            ?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
        // v2.0: image_versions2 es un objeto con "candidates", no un array.
        val imageUrl = bestImage(o.optJSONObject("image_versions2")?.optJSONArray("candidates"))
        return IgItem(
            isVideo = videoUrl != null,
            videoUrl = videoUrl,
            imageUrl = imageUrl,
        )
    }

    /** Elige la imagen de mayor resolución entre las candidatas. */
    private fun bestImage(candidates: JSONArray?): String? {
        if (candidates == null) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            val w = c.optInt("width", 0)
            val url = c.optString("url")
            if (url.startsWith("http") && w > bestW) {
                bestW = w
                best = url
            }
        }
        return best
    }

    /**
     * Busca la URL directa del audio de la música del post.
     * v3.15: prioriza las rutas de anonrode (music_metadata con
     * progressive_download_url) y luego el scan recursivo.
     * Si no aparece se usa el plan B (buscar la canción en YouTube).
     */
    private fun findAudioUrl(root: JSONObject): String? {
        // Ruta directa: music_metadata.progressive_download_url
        root.optJSONObject("music_metadata")
            ?.optString("progressive_download_url")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // music_info.music_asset_info.progressive_download_url
        root.optJSONObject("music_info")
            ?.optJSONObject("music_asset_info")
            ?.optString("progressive_download_url")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // clips_metadata.music_info.music_asset_info.progressive_download_url
        root.optJSONObject("clips_metadata")
            ?.optJSONObject("music_info")
            ?.optJSONObject("music_asset_info")
            ?.optString("progressive_download_url")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // Scan recursivo: cualquier audio_url / progressive_download_url
        // dentro del objeto (incl. clips_music_attribution_info si trae URL).
        val keys = setOf(
            "audio_url", "audio_src", "progressive_url", "progressive_download_url",
            "audio_stream_url",
        )
        fun scan(o: Any?): String? {
            when (o) {
                is JSONObject -> {
                    val names = o.keys()
                    while (names.hasNext()) {
                        val k = names.next()
                        val v = o.opt(k)
                        if (k.lowercase() in keys && v is String && v.startsWith("http")) {
                            return v
                        }
                        scan(v)?.let { return it }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until o.length()) scan(o.opt(i))?.let { return it }
                }
            }
            return null
        }
        return scan(root)
    }
}
