package com.panda.pandadl.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.panda.pandadl.MainActivity
import com.panda.pandadl.core.AppLogger

/**
 * v3.12: Burbuja flotante estilo Messenger.
 * Aparece cuando copias un enlace de video; al tocarla abre PandaDL.
 */
class BubbleService : Service() {

    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var lastUrl = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        if (!Settings.canDrawOverlays(this)) {
            AppLogger.w("Sin permiso de superposición, burbuja desactivada")
            stopSelf()
            return
        }
        showBubble()
        watchClipboard()
    }

    private fun startForeground() {
        val chId = "bubble"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "Burbuja", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("PandaDL Burbuja activa")
            .setContentText("Toca la burbuja para descargar")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        startForeground(1, notif)
    }

    private fun showBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val iv = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download_done)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                // Abrir PandaDL con el enlace.
                val i = Intent(this@BubbleService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
                hideBubble()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0; y = 200
        }
        bubble = iv
        try {
            wm?.addView(iv, params)
            bubble?.visibility = View.GONE // Oculta hasta detectar URL.
        } catch (e: Exception) {
            AppLogger.e("No se pudo mostrar la burbuja", e)
        }
    }

    private fun hideBubble() {
        bubble?.visibility = View.GONE
        lastUrl = ""
    }

    /** Vigila el portapapeles: si hay URL de video, muestra la burbuja. */
    private fun watchClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            try {
                val text = cm.primaryClip?.getItemAt(0)
                    ?.coerceToText(this)?.toString().orEmpty()
                val url = Regex("""https?://[^\s'"]+""").find(text)?.value
                if (url != null && url != lastUrl && isVideoUrl(url)) {
                    lastUrl = url
                    // Guardar para que MainActivity lo tome.
                    getSharedPreferences("pandadl", MODE_PRIVATE)
                        .edit().putString("bubble_url", url).apply()
                    bubble?.visibility = View.VISIBLE
                    AppLogger.i("Burbuja: URL detectada $url")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com") || u.contains("youtu.be") ||
            u.contains("instagram.com") || u.contains("facebook.com") ||
            u.contains("fb.watch") || u.contains("tiktok.com")
    }

    override fun onDestroy() {
        try {
            bubble?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BubbleService::class.java))
        }
        /** URL capturada por la burbuja (la lee MainActivity). */
        fun takeUrl(ctx: Context): String? {
            val p = ctx.getSharedPreferences("pandadl", MODE_PRIVATE)
            val u = p.getString("bubble_url", null)
            if (u != null) p.edit().remove("bubble_url").apply()
            return u
        }
    }
}
