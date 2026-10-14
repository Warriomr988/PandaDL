package com.panda.pandadl.core

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.11: Logger que registra todo en un archivo en Descargas/PandaDL/logs/.
 * Además mantiene el log en memoria para mostrarlo en la UI.
 */
object AppLogger {
    private const val TAG = "PandaDL"
    private var logFile: File? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Callback para la UI (el ViewModel lo conecta a su `log`). */
    var onLog: ((String) -> Unit)? = null

    /** Inicializar al arrancar la app. Crea el archivo de log del día. */
    fun init(ctx: Context) {
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val dir = File(downloads, "PandaDL/logs").apply { mkdirs() }
            val name = "pandadl_${fileFmt.format(Date())}.log"
            logFile = File(dir, name)
            // Limpiar logs de más de 7 días.
            val week = 7L * 24 * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach {
                if (now - it.lastModified() > week) it.delete()
            }
            i("=== PandaDL iniciado ===")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el archivo de log", e)
        }
    }

    /** Ruta del archivo de log actual (para mostrarla en la UI). */
    fun logPath(): String = logFile?.absolutePath ?: "No disponible"

    private fun write(level: String, msg: String, e: Throwable? = null) {
        val line = "[${dateFmt.format(Date())}] $level: $msg" +
            (e?.let { " | ${it.message}" } ?: "")
        // 1. Logcat.
        when (level) {
            "E" -> Log.e(TAG, msg, e)
            "W" -> Log.w(TAG, msg, e)
            else -> Log.i(TAG, msg, e)
        }
        // 2. Archivo.
        try {
            logFile?.appendText(line + "\n")
            e?.stackTraceToString()?.let { logFile?.appendText(it + "\n") }
        } catch (_: Exception) {
        }
        // 3. UI.
        try {
            onLog?.invoke(line)
        } catch (_: Exception) {
        }
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, e: Throwable? = null) = write("E", msg, e)
    fun d(msg: String) = write("D", msg)
}
