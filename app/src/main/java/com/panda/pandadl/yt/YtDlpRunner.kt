package com.panda.pandadl.yt

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v3.3: yt-dlp vía Chaquopy (Python empaquetado en el APK).
 *
 * Chaquopy instala Python 3.10 + yt-dlp al compilar. No hay binarios
 * externos ni apps separadas.
 *
 * Uso:
 *   YtDlpRunner.init(ctx)   // una vez (idempotente)
 *   YtDlpRunner.download(ctx, url, outDir, quality) { pct, msg -> ... }
 */
object YtDlpRunner {
    private const val TAG = "YtDlpRunner"
    private var ready = false

    /** Inicializa Chaquopy. Llamar una vez al arrancar (o antes de descargar). */
    fun init(ctx: Context): Boolean {
        if (ready) return true
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(ctx.applicationContext))
            }
            // Verificar que yt-dlp esté disponible.
            val py = Python.getInstance()
            py.getModule("yt_dlp")
            ready = true
            Log.i(TAG, "Chaquopy + yt-dlp listos")
            true
        } catch (e: Exception) {
            Log.e(TAG, "init falló", e)
            false
        }
    }

    /**
     * Descarga un video de YouTube con yt-dlp.
     *
     * v3.6: FFmpeg (de youtubedl-android, como ytdlnis) para fusionar.
     * Maneja todos los codecs (VP9, Opus, etc.).
     *
     * @param quality altura preferida (2160/1080/720/480/360) o null = mejor.
     * @param audioOnly si true, solo audio (m4a).
     * @param onProgress callback (porcentaje 0-100, mensaje).
     * @return archivo descargado o null si falló.
     */
    suspend fun download(
        ctx: Context,
        url: String,
        outDir: File,
        quality: Int? = null,
        audioOnly: Boolean = false,
        // v3.7.2: formato de audio preferido (para Muxer: necesita AAC).
        audioFormat: String = "ba",
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        if (!init(ctx)) return@withContext null
        if (!outDir.exists()) outDir.mkdirs()

        try {
            val py = Python.getInstance()
            val ytDlp = py.getModule("yt_dlp")
            val h = quality ?: 2160

            if (audioOnly) {
                // Solo audio: una descarga.
                onProgress(10f, "Descargando audio con yt-dlp…")
                val audioFile = downloadStream(py, ytDlp, url, outDir, audioFormat, "audio")
                    ?: return@withContext null
                onProgress(100f, "Completado")
                return@withContext audioFile
            }

            // Video + audio por separado (sin restricción de codecs, FFmpeg los maneja).
            onProgress(5f, "Descargando video con yt-dlp…")
            // v3.7: Codecs estrictos H.264/AAC para Muxer.
            val videoFile = downloadStream(py, ytDlp, url, outDir,
                "bv[height<=$h][vcodec^=avc][ext=mp4]/bv[height<=$h][ext=mp4]", "video")
                ?: return@withContext null

            onProgress(50f, "Descargando audio con yt-dlp…")
            val audioFile = downloadStream(py, ytDlp, url, outDir,
                "ba[acodec^=mp4a][ext=m4a]/ba[ext=m4a]", "audio2")
                ?: return@withContext null

            // v3.7: Fusionar con Muxer (FFmpeg descartado por fallos en packageDebug).
            // Codecs estrictos H.264/AAC para que no falle.
            onProgress(85f, "Fusionando…")
            val outFile = File(outDir, videoFile.nameWithoutExtension + "_merged.mp4")
            val ok = try {
                com.panda.pandadl.mux.Muxer.remux(videoFile, audioFile, outFile) { pct ->
                    onProgress(85f + pct * 15f, "Fusionando…")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Muxer falló", e)
                false
            }
            if (!ok) {
                Log.e(TAG, "Muxer no pudo fusionar")
                onProgress(0f, "Error fusionando video y audio")
                return@withContext null
            }

            // Limpiar temporales.
            videoFile.delete()
            audioFile.delete()

            onProgress(100f, "Completado")
            Log.i(TAG, "Descargado: ${outFile.absolutePath}")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "download falló", e)
            onProgress(0f, "Error: ${e.message?.take(60)}")
            null
        }
    }

    /**
     * Descarga un solo stream (video o audio) con yt-dlp.
     * Retorna el archivo descargado o null.
     */
    private fun downloadStream(
        py: com.chaquo.python.Python,
        ytDlp: com.chaquo.python.PyObject,
        url: String,
        outDir: File,
        format: String,
        suffix: String,
    ): File? {
        val before = outDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val pyOpts = py.getBuiltins().callAttr("dict")
        val outTemplate = File(outDir, "%(title).60s [%(id)s]_$suffix.%(ext)s").absolutePath
        pyOpts.callAttr("__setitem__", "outtmpl", outTemplate)
        pyOpts.callAttr("__setitem__", "format", format)
        pyOpts.callAttr("__setitem__", "quiet", true)
        pyOpts.callAttr("__setitem__", "no_warnings", true)
        pyOpts.callAttr("__setitem__", "socket_timeout", 15)
        pyOpts.callAttr("__setitem__", "retries", 3)

        return try {
            val ydl = ytDlp.callAttr("YoutubeDL", pyOpts)
            val pyList = py.getBuiltins().callAttr("list")
            pyList.callAttr("append", url)
            ydl.callAttr("download", pyList)

            // El archivo nuevo en outDir.
            outDir.listFiles()
                ?.filter { it.isFile && it.name !in before && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            Log.e(TAG, "downloadStream ($format) falló", e)
            null
        }
    }

    /** Título del video sin descargar (para validar el enlace). */
    suspend fun fetchInfo(ctx: Context, url: String): String? = withContext(Dispatchers.IO) {
        if (!init(ctx)) return@withContext null
        try {
            val py = Python.getInstance()
            val ytDlp = py.getModule("yt_dlp")
            val opts = py.getBuiltins().callAttr("dict")
            opts.callAttr("__setitem__", "quiet", true)
            opts.callAttr("__setitem__", "no_warnings", true)
            opts.callAttr("__setitem__", "skip_download", true)

            val ydl = ytDlp.callAttr("YoutubeDL", opts)
            val info = ydl.callAttr("extract_info", url, false)
            info.callAttr("get", "title")?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "fetchInfo falló", e)
            null
        }
    }
}
