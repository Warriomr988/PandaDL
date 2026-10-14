package com.panda.pandadl.yt

import com.panda.pandadl.core.FriendlyException
import com.panda.pandadl.net.Http
import org.json.JSONObject

/** Stream individual con URL directa (ya descifrada por el cliente ANDROID). */
data class YtStream(
    val itag: Int,
    val url: String,
    val mime: String,
    val qualityLabel: String?,
    val bitrate: Int,
    val width: Int = 0,
)

/** Video dentro de una playlist. */
data class YtPlaylistVideo(val id: String, val title: String)

/** Playlist resuelta con sus videos. */
data class YtPlaylist(
    val id: String,
    val title: String,
    val videos: List<YtPlaylistVideo>,
)

data class YtVideo(
    val id: String,
    val title: String,
    val author: String,
    /** video+audio juntos (mp4). Ideal: descargar directo. */
    val progressive: List<YtStream>,
    /** solo video (se mezcla con audio después). */
    val videoOnly: List<YtStream>,
    /** solo audio. */
    val audioOnly: List<YtStream>,
)

/**
 * Motor de YouTube sin dependencias: habla directo con la API interna
 * (youtubei) usando el cliente ANDROID, que entrega URLs ya descifradas.
 *
 * NOTA DE MANTENIMIENTO: si YouTube deja de responder, lo primero que hay
 * que probar es subir CLIENT_VERSION a una versión actual de la app.
 */
object YouTubeClient {

    const val CLIENT_VERSION = "20.10.38"

    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val UA = "com.google.android.youtube/$CLIENT_VERSION (Linux; U; Android 14) gzip"

    private val ID_RES = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})"""),
        Regex("""music\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})"""),
    )

    fun extractId(url: String): String? =
        ID_RES.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    fun extractPlaylistId(url: String): String? =
        Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

    /**
     * Lee una playlist (browse "VL"+ID). Devuelve sus videos con
     * continuación automática (tope maxVideos). Lanza FriendlyException.
     */
    fun fetchPlaylist(playlistId: String, maxVideos: Int = 200): YtPlaylist {
        if (playlistId.startsWith("RD")) {
            throw FriendlyException(
                "Las listas 'Mix' automáticas no se pueden leer. " +
                    "Usa una playlist normal.",
            )
        }
        val ctxJson = """"context":{"client":{""" +
            """"clientName":"ANDROID","clientVersion":"$CLIENT_VERSION",""" +
            """"androidSdkVersion":34,"hl":"es","gl":"CO"}}"""
        val videos = mutableListOf<YtPlaylistVideo>()
        var title: String? = null
        var continuation: String? = null
        var first = true
        while (videos.size < maxVideos) {
            val body = if (first) {
                """{$ctxJson,"browseId":"VL$playlistId"}"""
            } else {
                """{$ctxJson,"continuation":"$continuation"}"""
            }
            first = false
            val (code, resp) = Http.postJson(
                "https://www.youtube.com/youtubei/v1/browse?key=$API_KEY&prettyPrint=false",
                body,
                mapOf("User-Agent" to UA, "Content-Type" to "application/json"),
            )
            if (code != 200) {
                throw FriendlyException("YouTube no abrió la playlist (HTTP $code).")
            }
            val root = JSONObject(resp)
            if ((root.optJSONArray("alerts")?.length() ?: 0) > 0) {
                throw FriendlyException(
                    "YouTube no permite ver esta playlist. " +
                        "¿Es privada o fue eliminada?",
                )
            }
            collectPlaylistItems(root, videos)
            if (title == null) title = findPlaylistTitle(root)
            continuation = findPlaylistContinuation(root)
            if (continuation == null) break
        }
        if (videos.isEmpty()) {
            throw FriendlyException("La playlist no trajo videos (¿está vacía o es privada?).")
        }
        return YtPlaylist(
            id = playlistId,
            title = title ?: "Playlist",
            videos = videos.take(maxVideos),
        )
    }

    private fun collectPlaylistItems(root: JSONObject, out: MutableList<YtPlaylistVideo>) {
        fun scan(o: Any?) {
            when (o) {
                is JSONObject -> {
                    val pvr = o.optJSONObject("playlistVideoRenderer")
                    if (pvr != null) {
                        val id = pvr.optString("videoId")
                        val t = pvr.optJSONObject("title")
                        val name = t?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: t?.optString("simpleText") ?: "Video"
                        if (id.isNotEmpty() && out.none { it.id == id }) {
                            out += YtPlaylistVideo(id, name)
                        }
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) scan(o.opt(keys.next()))
                }
                is org.json.JSONArray -> {
                    for (i in 0 until o.length()) scan(o.opt(i))
                }
            }
        }
        scan(root)
    }

    private fun findPlaylistTitle(root: JSONObject): String? {
        var found: String? = null
        fun scan(o: Any?) {
            if (found != null) return
            when (o) {
                is JSONObject -> {
                    val h = o.optJSONObject("playlistHeaderRenderer")
                    if (h != null) {
                        val t = h.optJSONObject("title")
                        found = t?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        if (found == null) {
                            found = t?.optJSONArray("runs")?.optJSONObject(0)
                                ?.optString("text")?.takeIf { it.isNotBlank() }
                        }
                        return
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) scan(o.opt(keys.next()))
                }
                is org.json.JSONArray -> {
                    for (i in 0 until o.length()) scan(o.opt(i))
                }
            }
        }
        scan(root)
        return found
    }

    private fun findPlaylistContinuation(root: JSONObject): String? {
        var found: String? = null
        fun scan(o: Any?, insideList: Boolean) {
            if (found != null) return
            when (o) {
                is JSONObject -> {
                    val nowInside = insideList || o.has("playlistVideoListRenderer")
                    if (nowInside) {
                        val conts = o.optJSONArray("continuations")
                        val tok = conts?.optJSONObject(0)
                            ?.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                        if (!tok.isNullOrEmpty()) {
                            found = tok
                            return
                        }
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) scan(o.opt(keys.next()), nowInside)
                }
                is org.json.JSONArray -> {
                    for (i in 0 until o.length()) scan(o.opt(i), insideList)
                }
            }
        }
        scan(root, false)
        return found
    }

    /** Un cliente de YouTube para la cascada (v2.4, estilo yt-dlp). */
    private data class YtClient(
        val name: String,
        val version: String,
        val ua: String,
        /** JSON extra dentro de "client" (p. ej. deviceModel para iOS). */
        val extraJson: String = "",
    )

    private val YT_CLIENTS = listOf(
        // v2.5: iOS primero (como yt-dlp/ytdlnis); sus URLs toleran mejor la descarga.
        YtClient(
            "IOS", "20.10.38",
            "com.google.ios.youtube/20.10.38 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X)",
            """"deviceModel":"iPhone16,2","osName":"iOS","osVersion":"18.2.1.22C161",""",
        ),
        YtClient(
            "ANDROID", CLIENT_VERSION,
            "com.google.android.youtube/$CLIENT_VERSION (Linux; U; Android 14) gzip",
            """"androidSdkVersion":34,""",
        ),
        YtClient(
            "MWEB", "2.20241201.00",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        ),
    )

    /**
     * Resuelve el video probando clientes en cascada (IOS → ANDROID → MWEB,
     * como yt-dlp). Para cada cliente verifica con un Range ligero que los
     * streams descarguen de verdad; usa el primero que funcione.
     */
    fun fetchVideo(id: String): YtVideo {
        var lastErr: Exception? = null
        for (client in YT_CLIENTS) {
            try {
                val video = fetchVideoWith(id, client)
                if (verifyStreams(video)) return video
                lastErr = FriendlyException(
                    "YouTube bloqueó los streams del cliente ${client.name}.",
                )
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: FriendlyException("YouTube no devolvió el video.")
    }

    /** Pide los streams con un cliente específico. */
    private fun fetchVideoWith(id: String, client: YtClient): YtVideo {
        val body = """{"context":{"client":{""" +
            """"clientName":"${client.name}","clientVersion":"${client.version}",""" +
            client.extraJson +
            """"hl":"es","gl":"CO"}},"videoId":"$id"}"""
        val (code, resp) = Http.postJson(
            "https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false",
            body,
            mapOf("User-Agent" to client.ua),
        )
        if (code != 200) {
            throw FriendlyException("YouTube no respondió (HTTP $code). Revisa tu conexión.")
        }
        val root = JSONObject(resp)
        val status = root.optJSONObject("playabilityStatus")
        if (status?.optString("status") != "OK") {
            val reason = status?.optString("reason")?.takeIf { it.isNotBlank() }
                ?: "video no disponible"
            throw FriendlyException("YouTube dice: $reason")
        }
        val sd = root.optJSONObject("streamingData")
            ?: throw FriendlyException("YouTube no entregó streams para este video.")

        fun parse(arr: org.json.JSONArray?): List<YtStream> {
            val out = mutableListOf<YtStream>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val url = f.optString("url")
                if (!url.startsWith("http")) continue // sin descifrar: se omite
                out += YtStream(
                    itag = f.optInt("itag"),
                    url = url,
                    mime = f.optString("mimeType"),
                    qualityLabel = f.optString("qualityLabel").takeIf { it.isNotEmpty() },
                    bitrate = f.optInt("bitrate"),
                    width = f.optInt("width"),
                )
            }
            return out
        }

        val progressive = parse(sd.optJSONArray("formats"))
        val adaptive = parse(sd.optJSONArray("adaptiveFormats"))
        val details = root.optJSONObject("videoDetails")

        return YtVideo(
            id = id,
            title = details?.optString("title")?.takeIf { it.isNotBlank() } ?: "video_$id",
            author = details?.optString("author").orEmpty(),
            progressive = progressive,
            videoOnly = adaptive.filter { it.mime.startsWith("video/") },
            audioOnly = adaptive.filter { it.mime.startsWith("audio/") },
        )
    }

    /**
     * Verifica con un Range de 1 KB que el mejor stream descargue de verdad
     * (206/200 = OK, 403 = bloqueado).
     */
    private fun verifyStreams(video: YtVideo): Boolean {
        val test = videoQualities(video).firstOrNull()
            ?: audioQualities(video).firstOrNull()
            ?: return false
        return try {
            val (code, _) = Http.get(
                test.url,
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
                    "Range" to "bytes=0-1023",
                ),
            )
            code == 206 || code == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Calidades de video únicas (mejor primero). Mezcla progresivos
     * (traen audio incluido) y adaptativos mp4, sin repetir etiqueta.
     */
    fun videoQualities(v: YtVideo): List<YtStream> {
        val byLabel = linkedMapOf<String, YtStream>()
        for (s in v.progressive.sortedByDescending { it.width }) {
            byLabel.putIfAbsent(s.qualityLabel ?: "${s.width}p", s)
        }
        for (s in v.videoOnly.filter { "video/mp4" in it.mime }.sortedByDescending { it.width }) {
            byLabel.putIfAbsent(s.qualityLabel ?: "${s.width}p", s)
        }
        if (byLabel.isEmpty()) {
            // Sin mp4: acepta lo que haya (p.ej. webm/vp9).
            for (s in v.videoOnly.sortedByDescending { it.width }) {
                byLabel.putIfAbsent(s.qualityLabel ?: "${s.width}p", s)
            }
        }
        return byLabel.values.toList()
    }

    /** Audios ordenados por bitrate (mejor primero). */
    fun audioQualities(v: YtVideo): List<YtStream> =
        v.audioOnly.sortedByDescending { it.bitrate }

    /** Etiqueta legible para un audio: "128 kbps · m4a". */
    fun audioLabel(s: YtStream): String {
        val kbps = if (s.bitrate > 0) "${s.bitrate / 1000} kbps" else "audio"
        val fmt = if ("audio/mp4" in s.mime) "m4a" else "opus"
        return "$kbps · $fmt"
    }

    /** ¿El stream trae video+audio juntos (descarga directa)? */
    fun isProgressive(v: YtVideo, s: YtStream): Boolean =
        v.progressive.any { it.itag == s.itag }

    /** Mejor stream progresivo mp4 (video+audio juntos), o null. */
    fun bestProgressiveMp4(v: YtVideo): YtStream? {
        val mp4 = v.progressive.filter { "video/mp4" in it.mime }
        // itag 22 = 720p, itag 18 = 360p; si no, el de mayor bitrate.
        return mp4.firstOrNull { it.itag == 22 }
            ?: mp4.firstOrNull { it.itag == 18 }
            ?: mp4.maxByOrNull { it.bitrate }
            ?: v.progressive.maxByOrNull { it.bitrate }
    }

    /** Mejor video mp4 solo (para mezclar con audio). */
    fun bestVideoMp4(v: YtVideo): YtStream? =
        v.videoOnly.filter { "video/mp4" in it.mime }.maxByOrNull { it.width }
            ?: v.videoOnly.maxByOrNull { it.width }

    /** Mejor audio m4a (para mezclar o para música de fondo). */
    fun bestAudioM4a(v: YtVideo): YtStream? {
        val m4a = v.audioOnly.filter { "audio/mp4" in it.mime }
        return m4a.firstOrNull { it.itag == 140 } // ~128k AAC
            ?: m4a.maxByOrNull { it.bitrate }
            ?: v.audioOnly.maxByOrNull { it.bitrate }
    }

    /**
     * Busca en YouTube (cliente WEB) y devuelve el videoId del primer
     * resultado. Se usa para encontrar la canción de una foto de Instagram.
     */
    fun searchFirstVideoId(query: String): String? {
        return searchBestVideoId(query, null, null)
    }

    /**
     * v3.9.4: Busca el video que mejor coincida con artista/título.
     * Si encuentra la canción exacta, la devuelve. Si no, el más aproximado.
     */
    fun searchBestVideoId(query: String, artist: String?, title: String?): String? {
        val q = JSONObject.quote(query)
        val body = """{"context":{"client":{""" +
            """"clientName":"WEB","clientVersion":"2.20241201.00",""" +
            """"hl":"es","gl":"CO"}},"query":$q}"""
        val (code, resp) = Http.postJson(
            "https://www.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false",
            body,
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"),
        )
        if (code != 200) return null
        val candidates = findVideoCandidates(JSONObject(resp), 8)
        if (candidates.isEmpty()) return null
        if (artist.isNullOrBlank() && title.isNullOrBlank()) {
            return candidates[0].second // Sin info: el primero.
        }
        // Scoring: buscar la coincidencia exacta.
        var best: Pair<String, String>? = null
        var bestScore = -1
        for ((vidTitle, vidId) in candidates) {
            val score = scoreMatch(vidTitle, artist, title)
            if (score > bestScore) {
                bestScore = score
                best = Pair(vidTitle, vidId)
            }
        }
        // Si el mejor score es muy bajo, usar el primero (más aproximado).
        return if (bestScore >= 2) best!!.second else candidates[0].second
    }

    /** Puntaje de coincidencia: artista +2, título +2, ambos +3 extra, oficial +1. */
    private fun scoreMatch(vidTitle: String, artist: String?, title: String?): Int {
        val vt = vidTitle.lowercase()
        var score = 0
        val a = artist?.lowercase()?.trim().orEmpty()
        val t = title?.lowercase()?.trim().orEmpty()
        val hasArtist = a.isNotEmpty() && vt.contains(a)
        val hasTitle = t.isNotEmpty() && vt.contains(t)
        if (hasArtist) score += 2
        if (hasTitle) score += 2
        if (hasArtist && hasTitle) score += 3 // Coincidencia exacta.
        if (vt.contains("official")) score += 1
        if (vt.contains("cover") && !t.contains("cover")) score -= 2
        if (vt.contains("remix") && !t.contains("remix")) score -= 1
        if (vt.contains("karaoke")) score -= 3
        return score
    }

    /** Extrae hasta [limit] candidatos (título, videoId) de los resultados. */
    private fun findVideoCandidates(o: Any?, limit: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun recurse(x: Any?) {
            if (out.size >= limit) return
            when (x) {
                is JSONObject -> {
                    if (x.has("videoRenderer")) {
                        val vr = x.getJSONObject("videoRenderer")
                        val id = vr.optString("videoId")
                        val title = vr.optJSONObject("title")
                            ?.optJSONArray("runs")?.optJSONObject(0)
                            ?.optString("text").orEmpty()
                        if (id.length == 11 && title.isNotBlank()) {
                            out.add(Pair(title, id))
                        }
                    }
                    val names = x.keys()
                    while (names.hasNext() && out.size < limit) {
                        recurse(x.opt(names.next()))
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until x.length()) {
                        if (out.size >= limit) break
                        recurse(x.opt(i))
                    }
                }
            }
        }
        recurse(o)
        return out
    }

    private fun findFirstVideoId(o: Any?): String? {
        when (o) {
            is JSONObject -> {
                if (o.has("videoRenderer")) {
                    val id = o.getJSONObject("videoRenderer").optString("videoId")
                    if (id.length == 11) return id
                }
                val names = o.keys()
                while (names.hasNext()) {
                    findFirstVideoId(o.opt(names.next()))?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until o.length()) {
                    findFirstVideoId(o.opt(i))?.let { return it }
                }
            }
        }
        return null
    }
}
