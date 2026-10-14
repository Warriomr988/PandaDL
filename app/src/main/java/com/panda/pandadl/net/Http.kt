package com.panda.pandadl.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mini cliente HTTP sin dependencias (HttpURLConnection).
 * Todo pasa por aquí: YouTube, Instagram, Facebook y descargas.
 */
object Http {

    const val TIMEOUT_MS = 25_000

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        return conn
    }

    /** GET que devuelve (código, cuerpo como texto). */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = open(url, headers)
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /** POST con cuerpo JSON. Devuelve (código, cuerpo). */
    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val conn = open(url, headers)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Descarga una URL a un archivo, con progreso.
     * onProgress(recibidos, total) — total puede ser -1 si el servidor no lo dice.
     */
    fun downloadToFile(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val conn = open(url, headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code al descargar")
            }
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            dest.parentFile?.mkdirs()
            var received = 0L
            val buf = ByteArray(256 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            if (total > 0 && received < total) {
                throw java.io.IOException("Descarga incompleta ($received de $total bytes)")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Descarga por chunks de 1 MB con Range acotado (v2.5).
     * YouTube bloquea (403) los Range grandes o abiertos en streams
     * adaptativos, pero permite chunks de hasta ~1 MB.
     * v2.6: reintentos con backoff ante 403 (rate-limit de YouTube).
     */
    fun downloadChunked(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        chunkSize: Long = 1024 * 1024,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        dest.parentFile?.mkdirs()
        var start = 0L
        var total = -1L
        val buf = ByteArray(256 * 1024)
        FileOutputStream(dest).use { out ->
            while (true) {
                val end = start + chunkSize - 1
                val range = if (total > 0) {
                    "bytes=$start-${minOf(end, total - 1)}"
                } else {
                    "bytes=$start-$end"
                }
                val h = headers.toMutableMap()
                h["Range"] = range

                // v2.6: reintentar el chunk ante 403 (YouTube limita por ritmo).
                var chunkBytes = 0L
                var code = 0
                var lastErr: Exception? = null
                var ok = false
                for (attempt in 0..3) {
                    if (attempt > 0) {
                        // Backoff: 2s, 4s, 8s.
                        try { Thread.sleep(2000L * (1L shl (attempt - 1))) } catch (_: InterruptedException) {}
                    }
                    val conn = open(url, h)
                    try {
                        code = conn.responseCode
                        if (code != 206 && code != 200) {
                            lastErr = java.io.IOException("HTTP $code en chunk $start")
                            continue // reintentar
                        }
                        if (total < 0) {
                            // Content-Range: bytes 0-1048575/12345678
                            val cr = conn.getHeaderField("Content-Range")
                            total = cr?.substringAfter("/")?.toLongOrNull() ?: -1L
                            if (total < 0) {
                                total = Regex("[?&]clen=(\\d+)").find(url)
                                    ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                            }
                        }
                        chunkBytes = 0L
                        conn.inputStream.use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                chunkBytes += n
                                onProgress(start + chunkBytes, total)
                            }
                        }
                        ok = true
                        break
                    } catch (e: Exception) {
                        lastErr = e
                    } finally {
                        conn.disconnect()
                    }
                    // Pequeña pausa entre chunks para no disparar el límite.
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                }
                if (!ok) {
                    throw lastErr ?: java.io.IOException("HTTP $code al descargar (chunk $start)")
                }
                if (code == 200) break // El servidor ignoró el Range: ya tenemos todo.
                if (chunkBytes == 0L) break // Sin más datos.
                start += chunkBytes
                if (total > 0 && start >= total) break
                // Si el chunk vino corto, probablemente era el último.
                if (chunkBytes < chunkSize) break
            }
        }
        if (total > 0 && dest.length() < total) {
            throw java.io.IOException("Descarga incompleta (${dest.length()} de $total bytes)")
        }
    }
}
