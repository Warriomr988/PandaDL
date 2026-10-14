package com.panda.pandadl.dl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.panda.pandadl.core.FriendlyException
import java.io.File

/**
 * Guarda el archivo final en Descargas/PandaDL para que aparezca
 * en la galería y el explorador de archivos. Devuelve su Uri.
 */
object MediaStoreSaver {

    /**
     * Guarda en Descargas/PandaDL[/subcarpeta]. La subcarpeta organiza
     * por plataforma ("YouTube", "Instagram", "Facebook").
     */
    fun saveFile(
        ctx: Context,
        src: File,
        displayName: String,
        mime: String,
        subfolder: String? = null,
    ): Uri {
        val resolver = ctx.contentResolver
        val collection: Uri
        val relDir = Environment.DIRECTORY_DOWNLOADS + "/PandaDL" +
            (subfolder?.let { "/$it" } ?: "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            collection =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
        } else {
            collection = MediaStore.Files.getContentUri("external")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PandaDL" + (subfolder?.let { "/$it" } ?: ""),
            ).apply { mkdirs() }
            values.put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
        }
        val uri = resolver.insert(collection, values)
            ?: throw FriendlyException("No se pudo crear el archivo en Descargas.")
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw FriendlyException("No se pudo escribir en Descargas.")
        return uri
    }
}
