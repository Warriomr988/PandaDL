package com.panda.pandadl.dl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Una descarga guardada en el historial. */
data class HistoryEntry(
    val name: String,
    val platform: String, // nombre del enum Platform
    val date: Long, // epoch millis
    val size: Long, // bytes
    val uri: String, // content Uri como texto
    val note: String,
)

/**
 * Historial de descargas en un JSON local (sin base de datos).
 * Las Uri de MediaStore siguen válidas entre reinicios.
 */
object HistoryStore {

    private const val FILE = "pandadl_history.json"
    private const val MAX = 100

    fun load(ctx: Context): List<HistoryEntry> {
        return try {
            val text = ctx.openFileInput(FILE).bufferedReader().readText()
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    name = o.optString("name"),
                    platform = o.optString("platform"),
                    date = o.optLong("date"),
                    size = o.optLong("size"),
                    uri = o.optString("uri"),
                    note = o.optString("note"),
                )
            }
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, entry: HistoryEntry) {
        save(ctx, (listOf(entry) + load(ctx)).take(MAX))
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("platform", e.platform)
                        put("date", e.date)
                        put("size", e.size)
                        put("uri", e.uri)
                        put("note", e.note)
                    },
                )
            }
            ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(arr.toString().toByteArray())
            }
        } catch (ignored: Exception) {
            // El historial es secundario: nunca debe romper una descarga.
        }
    }
}
