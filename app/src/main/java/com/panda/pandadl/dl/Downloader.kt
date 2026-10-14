package com.panda.pandadl.dl

import android.content.Context
import android.net.Uri
import com.panda.pandadl.core.FriendlyException
import com.panda.pandadl.core.Platform
import com.panda.pandadl.core.detectPlatform
import com.panda.pandadl.core.sanitizeFileName
import com.panda.pandadl.fb.FacebookClient
import com.panda.pandadl.fb.FbQuality
import com.panda.pandadl.ig.IgMedia
import com.panda.pandadl.ig.InstagramClient
import com.panda.pandadl.ig.InstagramPublic
import com.panda.pandadl.ig.InstagramSession
import com.panda.pandadl.mux.Muxer
import com.panda.pandadl.net.Http
import com.panda.pandadl.yt.YouTubeClient
import com.panda.pandadl.yt.YtDlpRunner
import com.panda.pandadl.yt.YtStream
import com.panda.pandadl.yt.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DoneFile(val name: String, val uri: Uri, val bytes: Long)
data class DownloadResult(val files: List<DoneFile>, val note: String)

/** YouTube resuelto: el video y sus calidades para elegir. */
data class ResolvedYouTube(
    val video: YtVideo,
    val videoQs: List<YtStream>,
    val audioQs: List<YtStream>,
)

/** Facebook resuelto: calidades encontradas para elegir. */
data class ResolvedFacebook(
    val pageUrl: String,
    val qualities: List<FbQuality>,
)

/**
 * Orquesta todo el flujo: detectar plataforma -> resolver ->
 * descargar partes -> mezclar si hace falta -> guardar en Descargas.
 *
 * YouTube y Facebook van en dos pasos (resolver -> elegir calidad);
 * Instagram y enlaces directos se descargan de una vez.
 */
class Downloader(private val ctx: Context) {

    /** Descarga directa (Instagram y enlaces directos). */
    suspend fun download(
        rawUrl: String,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        musicQuery: String? = null,
        forceMusic: Boolean = false,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (url.isEmpty()) throw FriendlyException("Pega primero el enlace.")
        onProgress(0f)
        when (detectPlatform(url)) {
            Platform.INSTAGRAM -> downloadInstagram(url, onStatus, onProgress, musicQuery, forceMusic)
            Platform.DIRECT -> downloadDirect(url, onStatus, onProgress)
            Platform.YOUTUBE, Platform.FACEBOOK ->
                throw FriendlyException("Esta plataforma usa el selector de calidad.")
            Platform.UNKNOWN ->
                throw FriendlyException("No reconozco ese enlace (¿es de YouTube, Instagram o Facebook?).")
        }
    }

    // ==================== YouTube ====================

    suspend fun resolveYouTube(
        rawUrl: String,
        onStatus: (String) -> Unit,
    ): ResolvedYouTube = withContext(Dispatchers.IO) {
        onStatus("Buscando el video en YouTube…")
        val id = YouTubeClient.extractId(rawUrl.trim())
            ?: throw FriendlyException("No encontré el ID del video en el enlace.")
        val v = YouTubeClient.fetchVideo(id)
        val vq = YouTubeClient.videoQualities(v)
        val aq = YouTubeClient.audioQualities(v)
        if (vq.isEmpty() && aq.isEmpty()) {
            throw FriendlyException("YouTube no entregó calidades para este video.")
        }
        ResolvedYouTube(v, vq, aq)
    }

    /** Descarga un video en la calidad elegida (mezcla audio si hace falta). */
    suspend fun downloadYouTubeVideo(
        video: YtVideo,
        stream: YtStream,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val base = sanitizeFileName(video.title.ifBlank { "video_${video.id}" })
        val cache = cacheDir()
        val label = stream.qualityLabel ?: "${stream.width}p"
        if (YouTubeClient.isProgressive(video, stream)) {
            onStatus("Descargando video ($label)…")
            val tmp = File(cache, "$base.mp4")
            getToFile(stream.url, tmp) { r, t -> onProgress(frac(r, t)) }
            val uri = MediaStoreSaver.saveFile(ctx, tmp, "$base.mp4", "video/mp4", "YouTube")
            onProgress(1f)
            return@withContext DownloadResult(
                listOf(DoneFile("$base.mp4", uri, tmp.length())),
                "YouTube · $label",
            )
        }
        val aa = YouTubeClient.bestAudioM4a(video)
            ?: throw FriendlyException("YouTube no entregó audio descargable.")
        onStatus("Descargando video ($label)…")
        val vTmp = File(cache, "v.mp4")
        getToFile(stream.url, vTmp) { r, t -> onProgress(0.45f * frac(r, t)) }
        onStatus("Descargando audio…")
        val aTmp = File(cache, "a.m4a")
        getToFile(aa.url, aTmp) { r, t -> onProgress(0.45f + 0.35f * frac(r, t)) }
        onStatus("Mezclando video y audio…")
        val out = File(cache, "$base.mp4")
        Muxer.remux(vTmp, aTmp, out) { p -> onProgress(0.8f + 0.2f * p) }
        val uri = MediaStoreSaver.saveFile(ctx, out, "$base.mp4", "video/mp4", "YouTube")
        onProgress(1f)
        DownloadResult(
            listOf(DoneFile("$base.mp4", uri, out.length())),
            "YouTube · $label",
        )
    }

    /** Descarga solo el audio en la calidad elegida. */
    suspend fun downloadYouTubeAudio(
        video: YtVideo,
        stream: YtStream,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val base = sanitizeFileName(video.title.ifBlank { "audio_${video.id}" })
        val isM4a = "audio/mp4" in stream.mime
        val ext = if (isM4a) "m4a" else "webm"
        val mime = if (isM4a) "audio/mp4" else "audio/webm"
        onStatus("Descargando audio (${YouTubeClient.audioLabel(stream)})…")
        val tmp = File(cacheDir(), "$base.$ext")
        getToFile(stream.url, tmp) { r, t -> onProgress(frac(r, t)) }
        val uri = MediaStoreSaver.saveFile(ctx, tmp, "$base.$ext", mime, "YouTube")
        onProgress(1f)
        DownloadResult(
            listOf(DoneFile("$base.$ext", uri, tmp.length())),
            "YouTube · solo audio",
        )
    }

    // ==================== Facebook ====================

    suspend fun resolveFacebook(
        rawUrl: String,
        onStatus: (String) -> Unit,
    ): ResolvedFacebook = withContext(Dispatchers.IO) {
        onStatus("Buscando calidades en Facebook…")
        val u = rawUrl.trim()
        ResolvedFacebook(u, FacebookClient.fetchVideoUrls(u))
    }

    suspend fun downloadFacebookQuality(
        resolved: ResolvedFacebook,
        quality: FbQuality,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val id = FacebookClient.extractId(resolved.pageUrl) ?: "video"
        val name = "fb_${sanitizeFileName(id)}.mp4"  // carpeta Facebook abajo
        onStatus("Descargando video (${quality.label})…")
        val tmp = File(cacheDir(), name)
        getToFile(quality.url, tmp) { r, t -> onProgress(frac(r, t)) }
        val uri = MediaStoreSaver.saveFile(ctx, tmp, name, "video/mp4", "Facebook")
        onProgress(1f)
        DownloadResult(
            listOf(DoneFile(name, uri, tmp.length())),
            "Facebook · ${quality.label}",
        )
    }

    // ==================== Lote (cola) ====================

    /** YouTube con la mejor calidad (o el mejor audio si audioOnly). */
    /**
     * v3.0: Descarga YouTube con yt-dlp empaquetado.
     * Ya no usamos el cliente Kotlin liviano (los 403 eran inquebrantables).
     */
    suspend fun downloadYouTubeWithYtDlp(
        url: String,
        audioOnly: Boolean,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        ytQuality: Int? = null,
    ): DownloadResult = withContext(Dispatchers.IO) {
        onStatus("Iniciando yt-dlp…")
        val outDir = File(cacheDir(), "ytdlp").apply { if (!exists()) mkdirs() }

        val file = YtDlpRunner.download(
            ctx, url, outDir,
            quality = ytQuality,
            audioOnly = audioOnly,
            onProgress = { pct, msg ->
                onProgress(pct / 100f)
                if (msg.isNotBlank()) onStatus(msg.take(60))
            }
        ) ?: throw FriendlyException("yt-dlp no pudo descargar el video.")

        onStatus("Guardando…")
        val mime = when {
            file.extension.equals("m4a", true) -> "audio/mp4"
            file.extension.equals("webm", true) -> "video/webm"
            file.extension.equals("mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }
        val uri = MediaStoreSaver.saveFile(ctx, file, file.name, mime, "YouTube")
        onProgress(1f)
        val kind = if (audioOnly) "Audio" else "Video"
        DownloadResult(listOf(DoneFile(file.name, uri, file.length())), "$kind · YouTube (yt-dlp)")
    }

    suspend fun downloadYouTubeBest(
        video: YtVideo,
        audioOnly: Boolean,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        ytQuality: Int? = null,
    ): DownloadResult {
        return if (audioOnly) {
            val a = YouTubeClient.audioQualities(video).firstOrNull()
                ?: throw FriendlyException("El video no trajo audio descargable.")
            downloadYouTubeAudio(video, a, onStatus, onProgress)
        } else {
            val quals = YouTubeClient.videoQualities(video)
            // v2.2: si pidieron calidad, la etiqueta más cercana por debajo
            // (720p = altura 720, no el ancho).
            val v = if (ytQuality != null) {
                pickQuality(quals, ytQuality)
            } else {
                quals.firstOrNull()
            } ?: throw FriendlyException("El video no trajo calidades descargables.")
            downloadYouTubeVideo(video, v, onStatus, onProgress)
        }
    }

    /** Altura de la etiqueta ("720p" -> 720); si no hay etiqueta, usa el ancho. */
    private fun streamHeight(s: YtStream): Int =
        s.qualityLabel?.removeSuffix("p")?.toIntOrNull() ?: s.width

    /** Calidad pedida o la más cercana por debajo (si no hay, la menor). */
    private fun pickQuality(quals: List<YtStream>, wantHeight: Int): YtStream? =
        quals.filter { streamHeight(it) in 1..wantHeight }.maxByOrNull { streamHeight(it) }
            ?: quals.minByOrNull { streamHeight(it) }

    /** Facebook con la mejor calidad encontrada (HD primero). */
    suspend fun downloadFacebookBest(
        resolved: ResolvedFacebook,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): DownloadResult {
        val q = resolved.qualities.firstOrNull()
            ?: throw FriendlyException("Facebook no entregó calidades.")
        return downloadFacebookQuality(resolved, q, onStatus, onProgress)
    }

    /** Historias activas de @usuario (requiere sesión de Instagram). */
    suspend fun downloadStories(
        username: String,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val clean = username.removePrefix("@")
        val cookie = InstagramSession(ctx).cookieHeader()
            ?: throw FriendlyException("Conecta tu Instagram primero (botón 📸 Conectar).")
        onStatus("Buscando a @$clean…")
        val userId = InstagramClient.userIdFromUsername(clean, cookie)
        onStatus("Leyendo historias de @$clean…")
        val stories = InstagramClient.fetchStories(userId, cookie)
        if (stories.isEmpty()) {
            throw FriendlyException("@$clean no tiene historias ahora mismo.")
        }
        val cache = cacheDir()
        val files = mutableListOf<DoneFile>()
        stories.forEachIndexed { i, st ->
            if (i > 0) onProgress(i.toFloat() / stories.size)
            val isVideo = st.isVideo && st.videoUrl != null
            val srcUrl = if (isVideo) st.videoUrl!! else st.imageUrl!!
            val ext = if (isVideo) "mp4" else "jpg"
            val mime = if (isVideo) "video/mp4" else "image/jpeg"
            val name = "story_${clean}_${st.takenAt}.$ext"
            onStatus("Descargando historia ${i + 1}/${stories.size}…")
            val tmp = File(cache, name)
            getToFile(srcUrl, tmp) { _, _ -> }
            val uri = MediaStoreSaver.saveFile(ctx, tmp, name, mime, "Instagram")
            files += DoneFile(name, uri, tmp.length())
        }
        onProgress(1f)
        DownloadResult(files, "Historias · @$clean (${files.size})")
    }

    /**
     * Un enlace suelto para la cola: detecta el tipo y usa los mejores
     * valores por defecto. Las playlists se expanden en el motor, no aquí.
     */
    suspend fun downloadBatchUrl(
        rawUrl: String,
        audioOnly: Boolean,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        ytQuality: Int? = null,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val u = rawUrl.trim()
        if (u.isEmpty()) throw FriendlyException("Enlace vacío.")
        // ¿Historias? (URL /stories/ o @usuario suelto)
        val storyUser = InstagramClient.extractStoriesUsername(u)
            ?: InstagramClient.parseAtUsername(u)
        if (storyUser != null) {
            return@withContext downloadStories(storyUser, onStatus, onProgress)
        }
        // Nota: las playlists puras las expande el motor antes de llegar aquí.
        // Un watch?v=..&list=.. descarga solo ese video.
        when (detectPlatform(u)) {
            Platform.YOUTUBE -> {
                // v3.0: yt-dlp empaquetado (adiós 403).
                downloadYouTubeWithYtDlp(u, audioOnly, onStatus, onProgress, ytQuality)
            }
            Platform.FACEBOOK -> {
                downloadFacebookBest(resolveFacebook(u, onStatus), onStatus, onProgress)
            }
            else -> download(u, onStatus, onProgress)
        }
    }

    // ==================== Instagram ====================

    private suspend fun downloadInstagram(
        url: String,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        musicQuery: String? = null,
        forceMusic: Boolean = false,
    ): DownloadResult {
        val sc = InstagramClient.extractShortcode(url)
            ?: throw FriendlyException("No encontré el código del post en el enlace.")
        onStatus("Leyendo el post de Instagram…")
        // v2.7: si hay sesión, se usa el método autenticado primero
        // (trae la música automáticamente). Si no, el público.
        val session = InstagramSession(ctx)
        val cookie = session.cookieHeader()
        val media = if (cookie != null) {
            try {
                InstagramClient.fetchMedia(sc, cookie)
            } catch (_: FriendlyException) {
                InstagramPublic.fetchMedia(sc)
            }
        } else {
            try {
                InstagramPublic.fetchMedia(sc)
            } catch (publicErr: FriendlyException) {
                throw FriendlyException(
                    "${publicErr.message} Si es de una cuenta privada, " +
                        "conecta tu Instagram (botón 📸).",
                )
            }
        }
        val cache = cacheDir()
        val files = mutableListOf<DoneFile>()

        media.items.forEachIndexed { i, item ->
            val suffix = if (media.items.size > 1) "_${i + 1}" else ""
            when {
                item.isVideo && item.videoUrl != null -> {
                    onStatus("Descargando video…")
                    val name = "ig_${sc}${suffix}.mp4"
                    val tmp = File(cache, name)
                    getToFile(item.videoUrl, tmp) { r, t -> onProgress(frac(r, t)) }
                    val uri = MediaStoreSaver.saveFile(ctx, tmp, name, "video/mp4", "Instagram")
                    files += DoneFile(name, uri, tmp.length())
                }
                item.imageUrl != null -> {
                    // v3.16: Si forceMusic (botón "🎵 con música"), fuerza el flujo
                    // de música aunque la detección no haya encontrado canción.
                    val useMusic = media.items.size == 1 && (media.hasMusic || forceMusic)
                    if (useMusic) {
                        files += downloadPhotoWithMusic(media, item.imageUrl, sc, cache, onStatus, onProgress, cookie)
                    } else {
                        onStatus("Descargando foto…")
                        val name = "ig_${sc}${suffix}.jpg"
                        val tmp = File(cache, name)
                        getToFile(item.imageUrl, tmp) { r, t -> onProgress(frac(r, t)) }
                        val uri = MediaStoreSaver.saveFile(ctx, tmp, name, "image/jpeg", "Instagram")
                        files += DoneFile(name, uri, tmp.length())
                    }
                }
            }
        }
        if (files.isEmpty()) throw FriendlyException("El post no trajo nada descargable.")
        onProgress(1f)
        val note = if (media.hasMusic && media.items.size == 1 && !media.items[0].isVideo) {
            "🎵 ${media.musicLabel}"
        } else {
            "Instagram · @${media.userName}"
        }
        return DownloadResult(files, note)
    }

    /**
     * La foto trae música: descarga la imagen y el audio, y genera un MP4
     * con la foto fija y la canción de fondo.
     */
    private suspend fun downloadPhotoWithMusic(
        media: IgMedia,
        imageUrl: String,
        shortcode: String,
        cache: File,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        cookie: String? = null,
    ): DoneFile {
        onStatus("La foto trae música: ${media.musicLabel}")
        val imgTmp = File(cache, "foto.jpg")
        getToFile(imageUrl, imgTmp) { r, t -> onProgress(0.2f * frac(r, t)) }
        val imgBytes = imgTmp.readBytes()

        onStatus("Buscando el audio de «${media.musicLabel}»…")
        val audioTmp = File(cache, "audio.m4a")
        val direct = media.audioUrl
        if (direct != null) {
            // v3.15: descarga directa del audio de Instagram (sin YouTube).
            // Con la Cookie de sesión para que el CDN no dé 403.
            onStatus("Descargando el audio original…")
            getToFile(direct, audioTmp, cookie = cookie) { r, t -> onProgress(0.2f + 0.3f * frac(r, t)) }
        } else {
            // Plan B: buscar la canción en YouTube por título + artista.
            // v3.5.5: yt-dlp en vez del cliente Kotlin (adiós 403).
            // v3.9.4: búsqueda inteligente (exacta primero, aproximada si no).
            // v3.16: si no hay info de música (forceMusic), usa el caption.
            val query = if (media.hasMusic) media.musicLabel else media.caption.ifBlank { "música" }
            onStatus("Buscando «$query» en YouTube…")
            val vid = YouTubeClient.searchBestVideoId(query, media.musicArtist, media.musicTitle)
                ?: throw FriendlyException("No encontré la canción «$query» para ponerla de fondo. Instagram no devolvió info de música para este post.")
            val ytUrl = "https://www.youtube.com/watch?v=$vid"
            onStatus("Descargando la canción con yt-dlp…")
            val dlAudio = com.panda.pandadl.yt.YtDlpRunner.download(
                ctx, ytUrl, cache, audioOnly = true,
                // v3.9.1: itag 140 = M4A AAC 128k garantizado (no Opus).
                audioFormat = "140/ba[acodec=mp4a]",
            ) { pct, _ -> onProgress(0.2f + 0.3f * (pct / 100f)) }
                ?: throw FriendlyException("No se pudo descargar la canción.")
            dlAudio.copyTo(audioTmp, overwrite = true)
            dlAudio.delete()
        }

        onStatus("Mezclando foto + música…")
        val base = sanitizeFileName(media.musicLabel.ifBlank { "ig_$shortcode" })
        val out = File(cache, "$base.mp4")
        // v3.17: Muxer (Media3 Transformer con fallback al propio).
        com.panda.pandadl.mux.Muxer.imageWithAudio(imgBytes, audioTmp, out, { p ->
            onProgress(0.5f + 0.5f * p)
        }, ctx)
        val uri = MediaStoreSaver.saveFile(ctx, out, "$base.mp4", "video/mp4", "Instagram")
        return DoneFile("$base.mp4", uri, out.length())
    }

    // ==================== Enlace directo ====================

    private fun downloadDirect(
        url: String,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): DownloadResult {
        var name = url.substringAfterLast("/").substringBefore("?")
            .ifBlank { "archivo" }
        name = sanitizeFileName(name)
        if (!name.contains(".")) name += ".mp4"
        val mime = when (name.substringAfterLast(".", "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
        onStatus("Descargando archivo…")
        val tmp = File(cacheDir(), name)
        getToFile(url, tmp) { r, t -> onProgress(frac(r, t)) }
        val uri = MediaStoreSaver.saveFile(ctx, tmp, name, mime)
        onProgress(1f)
        return DownloadResult(listOf(DoneFile(name, uri, tmp.length())), "Enlace directo")
    }

    // ==================== Utilidades ====================

    private fun cacheDir(): File =
        File(ctx.cacheDir, "pandadl").apply {
            mkdirs()
            // Limpia descargas viejas para no llenar el caché.
            listFiles()?.forEach { if (it.isFile) it.delete() }
        }

    private fun getToFile(
        url: String,
        dest: File,
        cookie: String? = null,
        onProgress: (received: Long, total: Long) -> Unit,
    ) {
        try {
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
            )
            // v3.15: Cookie de sesión para descargas directas de Instagram.
            if (cookie != null) headers["Cookie"] = cookie
            // v2.5: YouTube bloquea (403) los Range grandes/abiertos en
            // adaptativos; descargar por chunks de 1 MB sí funciona.
            if ("googlevideo.com" in url) {
                Http.downloadChunked(url, dest, headers, onProgress = onProgress)
            } else {
                Http.downloadToFile(url, dest, headers, onProgress)
            }
        } catch (e: Exception) {
            throw FriendlyException("Falló la descarga: ${e.message}")
        }
        if (!dest.exists() || dest.length() == 0L) {
            throw FriendlyException("La descarga llegó vacía.")
        }
    }

    private fun frac(received: Long, total: Long): Float =
        if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0.15f
}
