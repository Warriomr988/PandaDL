package com.panda.pandadl.dl

import android.content.Context
import com.panda.pandadl.core.FriendlyException
import com.panda.pandadl.core.Platform
import com.panda.pandadl.fb.FbQuality
import com.panda.pandadl.yt.YouTubeClient
import com.panda.pandadl.yt.YtPlaylistVideo
import com.panda.pandadl.yt.YtStream
import com.panda.pandadl.yt.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Un trabajo para la cola. */
sealed interface EngineJob {
    /** URL suelta: se resuelve con los mejores valores por defecto. */
    data class Url(
        val url: String,
        val audioOnly: Boolean,
        /** v2.2: calidad de YouTube pedida (altura, p. ej. 720) o null = automática. */
        val ytQuality: Int? = null,
    ) : EngineJob

    /** Video de YouTube con calidad elegida a mano. */
    data class YouTubeExplicit(
        val video: YtVideo,
        val stream: YtStream,
        val audioOnly: Boolean,
    ) : EngineJob

    /** Video de Facebook con calidad elegida a mano. */
    data class FacebookExplicit(
        val resolved: ResolvedFacebook,
        val quality: FbQuality,
    ) : EngineJob

    /** Playlist: se expande a sus videos al empezar. */
    data class Playlist(
        val playlistId: String,
        val title: String,
        val videos: List<YtPlaylistVideo>,
        val audioOnly: Boolean,
        /** v2.2: calidad de YouTube pedida (altura) o null = automática. */
        val ytQuality: Int? = null,
    ) : EngineJob
}

data class EngineState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val current: String = "",
    val itemProgress: Float = 0f,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val lastError: String? = null,
)

/**
 * Cola de descargas. Vive en un singleton para que el servicio en
 * segundo plano y la UI compartan el mismo estado.
 */
object DownloadEngine {

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val jobs = mutableListOf<EngineJob>()

    @Volatile
    var cancelRequested = false

    /** El servicio la usa para actualizar la notificación. */
    var onNotify: ((title: String, done: Int, total: Int, progress: Float) -> Unit)? = null

    val isRunning: Boolean get() = _state.value.running

    fun enqueueAndStart(ctx: Context, newJobs: List<EngineJob>) {
        if (newJobs.isEmpty()) return
        jobs.clear()
        jobs.addAll(newJobs)
        cancelRequested = false
        _state.value = EngineState(running = true, total = newJobs.size)
        DownloadService.start(ctx)
    }

    fun cancel() {
        cancelRequested = true
    }

    /** Lo ejecuta el servicio en segundo plano. */
    suspend fun runAll(ctx: Context) = withContext(Dispatchers.IO) {
        val dl = Downloader(ctx)
        // 1. Expandir playlists a videos sueltos.
        val expanded = mutableListOf<EngineJob>()
        for (j in jobs.toList()) {
            if (cancelRequested) break
            when (j) {
                is EngineJob.Playlist -> {
                    for (v in j.videos) {
                        expanded += EngineJob.Url(
                            "https://www.youtube.com/watch?v=${v.id}",
                            j.audioOnly,
                            j.ytQuality,
                        )
                    }
                }
                is EngineJob.Url -> {
                    // Una playlist pegada en la cola se expande aquí.
                    val plId = YouTubeClient.extractPlaylistId(j.url)
                    if (plId != null && YouTubeClient.extractId(j.url) == null) {
                        _state.value = _state.value.copy(current = "Leyendo playlist…")
                        try {
                            val pl = YouTubeClient.fetchPlaylist(plId)
                            for (v in pl.videos) {
                                expanded += EngineJob.Url(
                                    "https://www.youtube.com/watch?v=${v.id}",
                                    j.audioOnly,
                                    j.ytQuality,
                                )
                            }
                        } catch (e: FriendlyException) {
                            _state.value = _state.value.copy(
                                lastError = "Playlist: ${e.message}",
                            )
                        }
                    } else {
                        expanded += j
                    }
                }
                else -> expanded += j
            }
        }
        val total = expanded.size
        var done = 0
        var ok = 0
        var fail = 0

        val statusCb: (String) -> Unit = { s ->
            _state.value = _state.value.copy(current = s)
            onNotify?.invoke("$s ($done/$total)", done, total, _state.value.itemProgress)
        }
        val progressCb: (Float) -> Unit = { p ->
            val old = _state.value.itemProgress
            val np = p.coerceIn(0f, 1f)
            _state.value = _state.value.copy(itemProgress = np)
            if (p >= 1f || (np * 20).toInt() != (old * 20).toInt()) {
                onNotify?.invoke(
                    "${_state.value.current} ($done/$total)",
                    done, total, np,
                )
            }
        }

        // v3.6: Descargas paralelas (3 a la vez) para playlists.
        for (chunk in expanded.chunked(3)) {
            if (cancelRequested) break
            val chunkLabel = chunk.joinToString(", ") { jobLabel(it).take(30) }
            _state.value = _state.value.copy(
                total = total, done = done, current = "Descargando ${chunk.size}: $chunkLabel",
                itemProgress = 0f, lastError = null,
            )
            onNotify?.invoke("Descargando ${chunk.size} ($done/$total)", done, total, 0f)

            // Descargar los 3 en paralelo.
            val results = supervisorScope {
                chunk.map { job ->
                    async(Dispatchers.IO) {
                        try {
                            val res = when (job) {
                                is EngineJob.Url ->
                                    dl.downloadBatchUrl(job.url, job.audioOnly, statusCb, progressCb, job.ytQuality)
                                is EngineJob.YouTubeExplicit -> {
                                    val url = "https://www.youtube.com/watch?v=${job.video.id}"
                                    val height = job.stream.qualityLabel?.removeSuffix("p")?.toIntOrNull()
                                    dl.downloadYouTubeWithYtDlp(
                                        url, job.audioOnly, statusCb, progressCb, ytQuality = height
                                    )
                                }
                                is EngineJob.FacebookExplicit ->
                                    dl.downloadFacebookQuality(job.resolved, job.quality, statusCb, progressCb)
                                is EngineJob.Playlist ->
                                    throw FriendlyException("Error interno: playlist sin expandir.")
                            }
                            writeHistory(ctx, res)
                            true
                        } catch (e: FriendlyException) {
                            _state.value = _state.value.copy(lastError = e.message)
                            false
                        } catch (e: Exception) {
                            _state.value = _state.value.copy(lastError = "Error: ${e.message}")
                            false
                        }
                    }
                }.awaitAll()
            }

            // Actualizar contadores.
            for (success in results) {
                if (success) ok++ else fail++
                done++
            }
            _state.value = _state.value.copy(done = done, okCount = ok, failCount = fail)
        }

        val final = if (cancelRequested) {
            "Detenido: $ok ok, $fail fallos"
        } else {
            "Terminado: $ok ok" + if (fail > 0) ", $fail fallos" else ""
        }
        _state.value = _state.value.copy(running = false, current = final, itemProgress = 1f)
        onNotify?.invoke(final, total, total, 1f)
        jobs.clear()
    }

    private fun jobLabel(job: EngineJob): String = when (job) {
        is EngineJob.Url -> job.url.take(60)
        is EngineJob.YouTubeExplicit ->
            (if (job.audioOnly) "🎵 " else "🎬 ") +
                job.video.title.take(40).ifBlank { "video" }
        is EngineJob.FacebookExplicit -> "📘 Video de Facebook (${job.quality.label})"
        is EngineJob.Playlist -> "🎞 ${job.title} (${job.videos.size})"
    }

    private fun writeHistory(ctx: Context, res: DownloadResult) {
        val now = System.currentTimeMillis()
        for (f in res.files) {
            // La plataforma se deduce de la nota; por defecto YouTube.
            val plat = when {
                res.note.startsWith("Instagram") || res.note.startsWith("Historias") ||
                    res.note.startsWith("🎵") ->
                    Platform.INSTAGRAM
                res.note.startsWith("Facebook") -> Platform.FACEBOOK
                res.note.startsWith("Enlace") -> Platform.DIRECT
                else -> Platform.YOUTUBE
            }
            HistoryStore.add(
                ctx,
                HistoryEntry(f.name, plat.name, now, f.bytes, f.uri.toString(), res.note),
            )
        }
    }
}
