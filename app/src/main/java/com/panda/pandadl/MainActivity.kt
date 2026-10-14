package com.panda.pandadl

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.panda.pandadl.ui.App
import com.panda.pandadl.ui.SharedLink

class MainActivity : ComponentActivity() {

    private var lastClip = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v3.11: Iniciar el logger (archivo en Descargas/PandaDL/logs/).
        com.panda.pandadl.core.AppLogger.init(this)
        // Android 13+: permiso para mostrar la notificación de descarga.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleSharedIntent(intent)
        setContent {
            // v3.17: Un solo MaterialTheme, el de App() (antes había otro
            // anidado aquí con el esquema claro por defecto).
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        // v3.12: URL capturada por la burbuja flotante.
        com.panda.pandadl.bubble.BubbleService.takeUrl(this)?.let { url ->
            com.panda.pandadl.ui.SharedLink.pending.value = url
        }
        magicPaste()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    /** Recibe el enlace cuando el usuario usa "Compartir -> PandaDL". */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = Regex("""https?://[^\s'"]+""").find(text)?.value ?: return
            SharedLink.pending.value = url
        }
    }

    /**
     * Pegado mágico: si al volver a la app el portapapeles trae un
     * enlace (o @usuario), lo pone listo en el campo.
     */
    private fun magicPaste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) return
        val url = Regex("""https?://[^\s'"]+""").find(text)?.value
        val atUser = if (url == null) {
            Regex("""^@?[A-Za-z0-9._]{1,30}$""").find(text.trim())?.value
        } else {
            null
        }
        val candidate = url ?: atUser ?: return
        if (candidate == lastClip || candidate == SharedLink.pending.value) return
        lastClip = candidate
        SharedLink.pending.value = candidate
    }
}
