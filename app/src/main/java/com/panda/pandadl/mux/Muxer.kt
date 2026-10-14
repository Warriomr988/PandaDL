package com.panda.pandadl.mux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.panda.pandadl.core.FriendlyException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Mezcla de medios con APIs del sistema (sin FFmpeg ni dependencias nativas).
 *
 * - imageWithAudio: foto fija + canción de fondo -> MP4 (para las fotos
 *   de Instagram que traen música). v3.17: primero intenta Media3
 *   Transformer (pipeline probado de Google); si falla, cae al Muxer
 *   propio con MediaCodec directo.
 * - remux: junta un video y un audio ya codificados (mp4/m4a) sin
 *   recodificar, copiando los samples (para YouTube en alta calidad).
 */
object Muxer {

    private data class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)
    private data class Sample(val ptsUs: Long, val data: ByteArray)
    private data class Drained(val format: MediaFormat, val samples: List<Sample>)

    // ==================================================================
    //  FOTO + AUDIO -> MP4
    // ==================================================================

    /**
     * Foto fija + canción de fondo -> MP4.
     * v3.17: intenta primero Media3 Transformer (pipeline de Google);
     * si falla (o no hay Context), cae al Muxer propio con MediaCodec.
     */
    fun imageWithAudio(
        imageBytes: ByteArray,
        audioFile: File,
        outFile: File,
        onProgress: (Float) -> Unit = {},
        context: Context? = null,
    ) {
        if (context != null) {
            try {
                imageWithAudioTransformer(imageBytes, audioFile, outFile, onProgress, context)
                return
            } catch (e: Exception) {
                // Fallback al Muxer propio. Borra salida parcial de Transformer.
                if (outFile.exists()) outFile.delete()
            }
        }
        imageWithAudioLegacy(imageBytes, audioFile, outFile, onProgress)
    }

    /**
     * Foto + audio vía Media3 Transformer: secuencia de video con la
     * imagen (duración = duración del audio) + secuencia de audio.
     * El hilo de Transformer tiene Looper propio; el llamador espera
     * con latch y sondea el progreso.
     */
    private fun imageWithAudioTransformer(
        imageBytes: ByteArray,
        audioFile: File,
        outFile: File,
        onProgress: (Float) -> Unit,
        context: Context,
    ) {
        if (!audioFile.exists() || audioFile.length() < 1024) {
            throw FriendlyException(
                "El audio descargado está vacío o corrupto " +
                    "(${audioFile.length()} bytes). Intenta de nuevo."
            )
        }
        // Duración del audio (la imagen se muestra todo ese tiempo).
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) throw FriendlyException("El audio no tiene duración válida.")
            // La imagen va a archivo temporal (Transformer lee por URI).
            val imgTmp = File.createTempFile("foto_", ".jpg", outFile.parentFile)
            try {
                imgTmp.writeBytes(imageBytes)
                val imageItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(imgTmp))
                    .setImageDurationMs(durationMs)
                    .build()
                val audioItem = MediaItem.fromUri(Uri.fromFile(audioFile))
                val videoSeq = EditedMediaItemSequence.withVideoFrom(
                    listOf(
                        EditedMediaItem.Builder(imageItem)
                            .setRemoveAudio(true).build()
                    )
                )
                val audioSeq = EditedMediaItemSequence.withAudioFrom(
                    listOf(
                        EditedMediaItem.Builder(audioItem)
                            .setRemoveVideo(true).build()
                    )
                )
                val composition = Composition.Builder(videoSeq, audioSeq).build()

                val latch = CountDownLatch(1)
                val errorRef = AtomicReference<Exception?>(null)
                val transformerRef = AtomicReference<Transformer?>(null)
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult,
                    ) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        errorRef.set(exportException)
                        latch.countDown()
                    }
                }
                val ht = HandlerThread("PandaTransformer").apply { start() }
                try {
                    Handler(ht.looper).post {
                        try {
                            val t = Transformer.Builder(context)
                                .addListener(listener).build()
                            transformerRef.set(t)
                            t.start(composition, outFile.absolutePath)
                        } catch (e: Exception) {
                            errorRef.set(e)
                            latch.countDown()
                        }
                    }
                    // Sondeo de progreso mientras esperamos (máx 10 min).
                    val holder = ProgressHolder()
                    val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
                    while (true) {
                        val done = latch.await(200, TimeUnit.MILLISECONDS)
                        if (done) break
                        if (System.currentTimeMillis() > deadline) {
                            transformerRef.get()?.cancel()
                            throw FriendlyException("Media3 Transformer tardó demasiado.")
                        }
                        val p = transformerRef.get()?.getProgress(holder) ?: 0
                        onProgress(0.05f + 0.9f * (p.coerceIn(0, 100) / 100f))
                    }
                    errorRef.get()?.let { throw it }
                    if (!outFile.exists() || outFile.length() < 1024) {
                        throw FriendlyException("Media3 Transformer no produjo el MP4.")
                    }
                    onProgress(1f)
                } finally {
                    ht.quitSafely()
                }
            } finally {
                imgTmp.delete()
            }
        } finally {
            retriever.release()
        }
    }

    /** Muxer propio con MediaCodec directo (fallback de Transformer). */
    private fun imageWithAudioLegacy(
        imageBytes: ByteArray,
        audioFile: File,
        outFile: File,
        onProgress: (Float) -> Unit = {},
    ) {
        // v3.9: Validar el audio antes de intentar muxear.
        if (!audioFile.exists() || audioFile.length() < 1024) {
            throw FriendlyException(
                "El audio descargado está vacío o corrupto " +
                    "(${audioFile.length()} bytes). Intenta de nuevo."
            )
        }
        onProgress(0.03f)
        var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw FriendlyException("No se pudo decodificar la imagen.")
        val (vw, vh) = fitEven(bmp.width, bmp.height, 720)
        if (bmp.width != vw || bmp.height != vh) {
            val scaled = Bitmap.createScaledBitmap(bmp, vw, vh, true)
            bmp.recycle()
            bmp = scaled
        }

        onProgress(0.08f)
        val pcm = decodeToPcm(audioFile)
        if (pcm.data.isEmpty()) throw FriendlyException("El audio llegó vacío.")
        val durationUs = pcm.data.size * 1_000_000L / (pcm.sampleRate * pcm.channels * 2)
        if (durationUs <= 0) throw FriendlyException("El audio no tiene duración válida.")

        // --- encoder de video (imagen fija a 1 fps) ---
        val colorFormat = pickColorFormat(MediaFormat.MIMETYPE_VIDEO_AVC)
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 1)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 600)
        }
        val vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vEnc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        vEnc.start()

        // --- encoder de audio (AAC) ---
        val aFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aEnc.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aEnc.start()

        try {
            // YUV de la imagen (se reutiliza el mismo buffer en cada frame)
            val yuv = ByteArray(vw * vh * 3 / 2)
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                argbToI420(bmp, yuv, vw, vh)
            } else {
                argbToNV12(bmp, yuv, vw, vh)
            }
            bmp.recycle()

            onProgress(0.15f)
            feedPcm(aEnc, pcm)

            val frames = ((durationUs + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
            feedFrames(vEnc, yuv, frames) { f ->
                onProgress(0.15f + 0.55f * f / frames)
            }

            onProgress(0.75f)
            val vd = drain(vEnc)
            val ad = drain(aEnc)
            onProgress(0.9f)
            writeMuxed(outFile, vd, ad)
        } finally {
            vEnc.stop(); vEnc.release()
            aEnc.stop(); aEnc.release()
        }
        onProgress(1f)
    }

    // ==================================================================
    //  VIDEO + AUDIO (ya codificados) -> MP4, sin recodificar
    // ==================================================================

    fun remux(
        videoFile: File,
        audioFile: File,
        outFile: File,
        onProgress: (Float) -> Unit = {},
    ) {
        val ve = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val ae = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        try {
            val vt = selectTrack(ve, "video/")
            val at = selectTrack(ae, "audio/")
            ve.selectTrack(vt)
            ae.selectTrack(at)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val mvt = muxer.addTrack(ve.getTrackFormat(vt))
                val mat = muxer.addTrack(ae.getTrackFormat(at))
                muxer.start()

                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var vEos = false
                var aEos = false
                var written = 0L
                val total = videoFile.length() + audioFile.length()
                while (!vEos || !aEos) {
                    val vTs = if (vEos) Long.MAX_VALUE else ve.sampleTime
                    val aTs = if (aEos) Long.MAX_VALUE else ae.sampleTime
                    buf.clear()
                    if (vTs <= aTs) {
                        val n = ve.readSampleData(buf, 0)
                        if (n < 0) {
                            vEos = true
                        } else {
                            info.set(0, n, ve.sampleTime, ve.sampleFlags)
                            muxer.writeSampleData(mvt, buf, info)
                            ve.advance()
                            written += n
                        }
                    } else {
                        val n = ae.readSampleData(buf, 0)
                        if (n < 0) {
                            aEos = true
                        } else {
                            info.set(0, n, ae.sampleTime, ae.sampleFlags)
                            muxer.writeSampleData(mat, buf, info)
                            ae.advance()
                            written += n
                        }
                    }
                    if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            ve.release()
            ae.release()
        }
        onProgress(1f)
    }

    // ==================================================================
    //  Internos
    // ==================================================================

    private fun selectTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        throw FriendlyException("No se encontró pista $prefix en el archivo.")
    }

    /** Decodifica cualquier audio (mp3/m4a/opus...) a PCM 16-bit. */
    private fun decodeToPcm(file: File): Pcm {
        val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
        try {
            val track = selectTrack(ex, "audio/")
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(mime)
            dec.configure(inFormat, null, null, 0)
            dec.start()
            try {
                val out = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var eos = false
                var idle = 0
                while (!eos && idle < 200) {
                    val inIx = dec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val buf = dec.getInputBuffer(inIx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            dec.queueInputBuffer(inIx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                    when (val outIx = dec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> idle++
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = dec.outputFormat
                            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        else -> {
                            idle = 0
                            if (outIx >= 0) {
                                if (info.size > 0) {
                                    val b = dec.getOutputBuffer(outIx)!!
                                    val chunk = ByteArray(info.size)
                                    b.get(chunk)
                                    out.write(chunk)
                                }
                                dec.releaseOutputBuffer(outIx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    eos = true
                                }
                            }
                        }
                    }
                }
                return Pcm(out.toByteArray(), sampleRate, channels)
            } finally {
                dec.stop(); dec.release()
            }
        } finally {
            ex.release()
        }
    }

    /** Espera un buffer de entrada libre (reintenta, no lo omite). */
    private fun dequeueInput(enc: MediaCodec): Int {
        var tries = 0
        while (true) {
            val ix = enc.dequeueInputBuffer(10_000)
            if (ix >= 0) return ix
            if (++tries > 500) throw FriendlyException("El codificador no aceptó datos.")
        }
    }

    /** Mete el PCM al encoder AAC y le marca fin de stream. */
    private fun feedPcm(enc: MediaCodec, pcm: Pcm) {
        val bytesPerSec = pcm.sampleRate * pcm.channels * 2L
        var fed = 0
        while (fed < pcm.data.size) {
            val ix = dequeueInput(enc)
            val buf = enc.getInputBuffer(ix)!!
            buf.clear()
            val n = minOf(pcm.data.size - fed, buf.remaining())
            buf.put(pcm.data, fed, n)
            val pts = fed * 1_000_000L / bytesPerSec
            enc.queueInputBuffer(ix, 0, n, pts, 0)
            fed += n
        }
        val ix = dequeueInput(enc)
        enc.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    /** Mete N frames idénticos (1 fps) al encoder de video. */
    private fun feedFrames(
        enc: MediaCodec,
        yuv: ByteArray,
        frames: Int,
        onFrame: (Int) -> Unit,
    ) {
        for (i in 0 until frames) {
            val ix = dequeueInput(enc)
            val buf = enc.getInputBuffer(ix)!!
            buf.clear()
            buf.put(yuv)
            enc.queueInputBuffer(ix, 0, yuv.size, i * 1_000_000L, 0)
            onFrame(i + 1)
        }
        val ix = dequeueInput(enc)
        enc.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    /** Vacía un encoder: devuelve su formato de salida y los samples. */
    private fun drain(enc: MediaCodec): Drained {
        val samples = ArrayList<Sample>()
        var format: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var eos = false
        var idle = 0
        while (!eos && idle < 400) {
            when (val ix = enc.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> idle++
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = enc.outputFormat
                else -> {
                    idle = 0
                    if (ix >= 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !isConfig) {
                            val b = enc.getOutputBuffer(ix)!!
                            val chunk = ByteArray(info.size)
                            b.get(chunk)
                            samples += Sample(info.presentationTimeUs, chunk)
                        }
                        enc.releaseOutputBuffer(ix, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }
                }
            }
        }
        val f = format ?: throw FriendlyException("El codificador no entregó su formato.")
        return Drained(f, samples)
    }

    /** Escribe video+audio intercalados por timestamp en el MP4 final. */
    private fun writeMuxed(outFile: File, vd: Drained, ad: Drained) {
        outFile.parentFile?.mkdirs()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vt = muxer.addTrack(vd.format)
            val at = muxer.addTrack(ad.format)
            muxer.start()
            val vs = vd.samples.sortedBy { it.ptsUs }
            val aus = ad.samples.sortedBy { it.ptsUs }
            var vi = 0
            var ai = 0
            val info = MediaCodec.BufferInfo()
            while (vi < vs.size || ai < aus.size) {
                val takeVideo = ai >= aus.size || (vi < vs.size && vs[vi].ptsUs <= aus[ai].ptsUs)
                val s = if (takeVideo) vs[vi++] else aus[ai++]
                info.set(0, s.data.size, s.ptsUs, 0)
                muxer.writeSampleData(if (takeVideo) vt else at, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    // ---------- color ----------

    /** Elige un formato YUV que el encoder del teléfono soporte. */
    private fun pickColorFormat(mime: String): Int {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val types = try {
                info.supportedTypes
            } catch (ignored: Exception) {
                continue
            }
            if (!types.contains(mime)) continue
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (ignored: Exception) {
                continue
            }
            val fmts = caps.colorFormats
            if (fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            }
            if (fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
        }
        throw FriendlyException("Este teléfono no puede codificar video (sin formato YUV).")
    }

    /** Escala manteniendo proporción, tope maxDim, lados pares (lo exige H.264). */
    private fun fitEven(sw: Int, sh: Int, maxDim: Int): Pair<Int, Int> {
        val scale = maxDim / maxOf(sw, sh).toFloat()
        var w = (sw * scale).toInt() / 2 * 2
        var h = (sh * scale).toInt() / 2 * 2
        if (w < 2) w = 2
        if (h < 2) h = 2
        return w to h
    }

    private fun argbToI420(bmp: Bitmap, yuv: ByteArray, w: Int, h: Int) {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val ySize = w * h
        var yi = 0
        var ui = ySize
        var vi = ySize + ySize / 4
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argb[j * w + i]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                yuv[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[ui++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    yuv[vi++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun argbToNV12(bmp: Bitmap, yuv: ByteArray, w: Int, h: Int) {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val ySize = w * h
        var yi = 0
        var uvi = ySize
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argb[j * w + i]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                yuv[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvi++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    yuv[uvi++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
