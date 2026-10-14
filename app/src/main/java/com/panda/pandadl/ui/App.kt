package com.panda.pandadl.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panda.pandadl.core.FriendlyException
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.panda.pandadl.core.Platform
import com.panda.pandadl.core.detectPlatform
import androidx.compose.runtime.collectAsState
import com.panda.pandadl.dl.DoneFile
import com.panda.pandadl.dl.DownloadEngine
import com.panda.pandadl.dl.DownloadResult
import com.panda.pandadl.dl.Downloader
import com.panda.pandadl.dl.EngineJob
import com.panda.pandadl.dl.EngineState
import com.panda.pandadl.dl.HistoryEntry
import com.panda.pandadl.dl.HistoryStore
import com.panda.pandadl.dl.ResolvedFacebook
import com.panda.pandadl.dl.ResolvedYouTube
import com.panda.pandadl.fb.FbQuality
import com.panda.pandadl.ig.InstagramClient
import com.panda.pandadl.ig.InstagramSession
import com.panda.pandadl.yt.YouTubeClient
import com.panda.pandadl.yt.YtDlpRunner
import com.panda.pandadl.yt.YtPlaylist
import com.panda.pandadl.yt.YtStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================== ViewModel ====================

/**
 * Enlace compartido desde otra app (Compartir -> PandaDL).
 * MainActivity lo llena; App() lo consume en cuanto aparece.
 */
object SharedLink {
    val pending = mutableStateOf<String?>(null)
}

class DlViewModel(app: Application) : AndroidViewModel(app) {

    var url by mutableStateOf("")
    var status by mutableStateOf("Pega un enlace de YouTube, Instagram o Facebook.")
    var progress by mutableFloatStateOf(0f)
    var working by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<DownloadResult?>(null)
    var log by mutableStateOf("")
    var igConnected by mutableStateOf(false)
    var showLogin by mutableStateOf(false)
    var showHistory by mutableStateOf(false)
    var pendingYouTube by mutableStateOf<ResolvedYouTube?>(null)
    var pendingFacebook by mutableStateOf<ResolvedFacebook?>(null)
    var pendingPlaylist by mutableStateOf<YtPlaylist?>(null)
    /** Para la cola: true = solo audio en los videos de YouTube. */
    var batchAudioOnly by mutableStateOf(false)
    /** v2.1: canción de fondo para fotos de IG (opcional, la escribe el usuario). */
    var musicQuery by mutableStateOf("")
    /** v2.2: calidad de YouTube para la cola (null = automática). */
    var ytQuality by mutableStateOf<Int?>(null)
    val history = mutableStateListOf<HistoryEntry>()
    // v3.12: Modo oscuro (persistido en SharedPreferences).
    private val prefs = app.getSharedPreferences("pandadl", android.content.Context.MODE_PRIVATE)
    private val _darkMode = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getBoolean("dark_mode", false)
    )
    val darkMode: kotlinx.coroutines.flow.StateFlow<Boolean> = _darkMode
    fun toggleDarkMode() {
        val v = !_darkMode.value
        _darkMode.value = v
        prefs.edit().putBoolean("dark_mode", v).apply()
        com.panda.pandadl.core.AppLogger.i("Modo oscuro: $v")
    }
    // v3.12: Burbuja flotante.
    private val _bubbleOn = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getBoolean("bubble_on", false)
    )
    val bubbleOn: kotlinx.coroutines.flow.StateFlow<Boolean> = _bubbleOn
    fun toggleBubble() {
        val ctx = getApplication<android.app.Application>()
        val v = !_bubbleOn.value
        if (v) {
            // Pedir permiso de superposición si falta.
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(i)
                com.panda.pandadl.core.AppLogger.w("Pide permiso de superposición para la burbuja")
                return
            }
            com.panda.pandadl.bubble.BubbleService.start(ctx)
        } else {
            com.panda.pandadl.bubble.BubbleService.stop(ctx)
        }
        _bubbleOn.value = v
        prefs.edit().putBoolean("bubble_on", v).apply()
        com.panda.pandadl.core.AppLogger.i("Burbuja: $v")
    }

    private val scope = viewModelScope
    private val statusCb: (String) -> Unit = { s -> scope.launch { status = s; addLog(s) } }
    private val progressCb: (Float) -> Unit = { p ->
        if (kotlin.math.abs(p - progress) > 0.02f || p >= 1f) {
            scope.launch { progress = p.coerceIn(0f, 1f) }
        }
    }

    init {
        igConnected = InstagramSession(app).isConnected()
        history.addAll(HistoryStore.load(app))
        // v3.11: Conectar el logger al log de la UI (sin loop).
        com.panda.pandadl.core.AppLogger.onLog = { line ->
            scope.launch {
                log = (log + "\n$line").takeLast(4000).trimStart('\n')
            }
        }
        com.panda.pandadl.core.AppLogger.i("App iniciada, sesión IG: $igConnected")
        // v3.0: pre-inicializar yt-dlp en segundo plano.
        viewModelScope.launch(Dispatchers.IO) {
            YtDlpRunner.init(app.applicationContext)
        }
    }

    fun addLog(line: String) {
        // v3.11: va al archivo y a la UI (vía onLog, sin loop).
        com.panda.pandadl.core.AppLogger.i(line)
    }

    /**
     * Un enlace -> flujo interactivo (v1.1). Varios -> cola en
     * segundo plano con los mejores valores por defecto.
     */
    fun startDownload(forceMusic: Boolean = false) {
        if (working || DownloadEngine.isRunning) return
        val items = url.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return
        error = null
        result = null
        if (items.size == 1) {
            startSingle(items[0], forceMusic)
        } else {
            startBatch(items)
        }
    }

    private fun startBatch(items: List<String>) {
        val jobs = items.map { EngineJob.Url(it, batchAudioOnly, ytQuality) }
        DownloadEngine.enqueueAndStart(getApplication(), jobs)
        addLog("📋 Cola iniciada: ${items.size} enlaces (segundo plano)")
        status = "Cola iniciada (${items.size})…"
    }

    /** Un solo enlace: historias, playlist o flujo interactivo normal. */
    private fun startSingle(link: String, forceMusic: Boolean = false) {
        // ¿Historias? (URL /stories/ o @usuario suelto)
        val storyUser = InstagramClient.extractStoriesUsername(link)
            ?: InstagramClient.parseAtUsername(link)
        if (storyUser != null) {
            DownloadEngine.enqueueAndStart(
                getApplication(),
                listOf(EngineJob.Url(link, batchAudioOnly)),
            )
            addLog("📲 Historias de @$storyUser en la cola…")
            status = "Descargando historias de @$storyUser…"
            return
        }
        // ¿Playlist?
        val plId = YouTubeClient.extractPlaylistId(link)
        if (plId != null && YouTubeClient.extractId(link) == null) {
            resolvePlaylist(plId)
            return
        }
        // Flujo interactivo (v1.1).
        working = true
        progress = 0f
        status = "Empezando…"
        scope.launch(Dispatchers.Main) {
            try {
                val dl = Downloader(getApplication())
                when (detectPlatform(link)) {
                    Platform.YOUTUBE -> {
                        val r = dl.resolveYouTube(link, statusCb)
                        pendingYouTube = r
                        status = "Elige la calidad"
                    }
                    Platform.FACEBOOK -> {
                        pendingFacebook = dl.resolveFacebook(link, statusCb)
                        status = "Elige la calidad"
                    }
                    else -> {
                        val res = dl.download(link, statusCb, progressCb, musicQuery, forceMusic)
                        onDone(res, detectPlatform(link))
                    }
                }
            } catch (e: FriendlyException) {
                error = e.message
                addLog("❌ ${e.message}")
            } catch (e: Exception) {
                error = "Error inesperado: ${e.message}"
                addLog("❌ ${e.message}")
            } finally {
                working = false
                musicQuery = "" // v2.1: no reusar la canción en la próxima descarga
            }
        }
    }

    private fun resolvePlaylist(plId: String) {
        working = true
        status = "Leyendo la playlist…"
        scope.launch(Dispatchers.Main) {
            try {
                val pl = withContext(Dispatchers.IO) {
                    YouTubeClient.fetchPlaylist(plId)
                }
                pendingPlaylist = pl
                status = "Elige qué bajar de la playlist"
                addLog("🎞 Playlist: ${pl.title} (${pl.videos.size} videos)")
            } catch (e: FriendlyException) {
                error = e.message
                addLog("❌ ${e.message}")
            } catch (e: Exception) {
                error = "Error inesperado: ${e.message}"
                addLog("❌ ${e.message}")
            } finally {
                working = false
            }
        }
    }

    /** La playlist confirmada entra a la cola en segundo plano. */
    fun confirmPlaylist(audioOnly: Boolean) {
        val pl = pendingPlaylist ?: return
        pendingPlaylist = null
        DownloadEngine.enqueueAndStart(
            getApplication(),
            listOf(EngineJob.Playlist(pl.id, pl.title, pl.videos, audioOnly, ytQuality)),
        )
        addLog("🎞 En la cola: ${pl.title} (${pl.videos.size} videos)")
        status = "Playlist en la cola (${pl.videos.size} videos)…"
    }

    /** Paso 2 (YouTube): descarga con la calidad elegida. */
    fun confirmYouTube(audioOnly: Boolean, stream: YtStream) {
        val r = pendingYouTube ?: return
        pendingYouTube = null
        working = true
        error = null
        result = null
        progress = 0f
        scope.launch(Dispatchers.Main) {
            try {
                val dl = Downloader(getApplication())
                // v3.4.7: yt-dlp empaquetado (adiós 403 del cliente Kotlin).
                val url = "https://www.youtube.com/watch?v=${r.video.id}"
                val height = stream.qualityLabel?.removeSuffix("p")?.toIntOrNull()
                val res = dl.downloadYouTubeWithYtDlp(
                    url, audioOnly, statusCb, progressCb, ytQuality = height
                )
                onDone(res, Platform.YOUTUBE)
            } catch (e: FriendlyException) {
                error = e.message
                addLog("❌ ${e.message}")
            } catch (e: Exception) {
                error = "Error inesperado: ${e.message}"
                addLog("❌ ${e.message}")
            } finally {
                working = false
            }
        }
    }

    /** Paso 2 (Facebook): descarga con la calidad elegida. */
    fun confirmFacebook(quality: FbQuality) {
        val r = pendingFacebook ?: return
        pendingFacebook = null
        working = true
        error = null
        result = null
        progress = 0f
        scope.launch(Dispatchers.Main) {
            try {
                val res = Downloader(getApplication())
                    .downloadFacebookQuality(r, quality, statusCb, progressCb)
                onDone(res, Platform.FACEBOOK)
            } catch (e: FriendlyException) {
                error = e.message
                addLog("❌ ${e.message}")
            } catch (e: Exception) {
                error = "Error inesperado: ${e.message}"
                addLog("❌ ${e.message}")
            } finally {
                working = false
            }
        }
    }

    private fun onDone(res: DownloadResult, platform: Platform) {
        result = res
        addLog("✅ Guardado: ${res.files.joinToString { it.name }} (${res.note})")
        val ctx = getApplication<Application>()
        val now = System.currentTimeMillis()
        for (f in res.files) {
            HistoryStore.add(
                ctx,
                HistoryEntry(f.name, platform.name, now, f.bytes, f.uri.toString(), res.note),
            )
        }
        history.clear()
        history.addAll(HistoryStore.load(ctx))
    }

    fun clearHistory() {
        val ctx = getApplication<Application>()
        HistoryStore.clear(ctx)
        history.clear()
    }

    fun refreshHistory() {
        val ctx = getApplication<Application>()
        history.clear()
        history.addAll(HistoryStore.load(ctx))
    }

    fun onLoginDone() {
        igConnected = true
        showLogin = false
        addLog("✅ Instagram conectado.")
    }

    fun disconnectIg() {
        InstagramSession(getApplication()).clear()
        igConnected = false
        addLog("Instagram desconectado.")
    }
}

// ==================== Pantalla principal ====================

@Composable
fun App() {
    val vm: DlViewModel = viewModel()
    // v3.12: Modo oscuro (persistido).
    val darkMode by vm.darkMode.collectAsState()

    MaterialTheme(
        colorScheme = if (darkMode) androidx.compose.material3.darkColorScheme()
        else androidx.compose.material3.lightColorScheme()
    ) {
    // v3.17: Pintar el fondo con el color del esquema activo. Sin este
    // Surface, MaterialTheme solo provee colores pero no pinta nada y el
    // fondo blanco del tema del Manifest se veía siempre (bug modo oscuro).
    val scheme = MaterialTheme.colorScheme
    val appContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.SideEffect {
        val window = (appContext as? android.app.Activity)?.window
        if (window != null) {
            window.statusBarColor = scheme.background.toArgb()
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkMode
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.background,
    ) {

    // Consume el enlace compartido (llega de MainActivity al abrir o al
    // compartir con la app ya abierta).
    val pending = SharedLink.pending.value
    LaunchedEffect(pending) {
        if (pending != null) {
            vm.url = pending
            SharedLink.pending.value = null
        }
    }

    if (vm.showLogin) {
        InstagramLoginDialog(
            onDone = { vm.onLoginDone() },
            onDismiss = { vm.showLogin = false },
        )
    }
    vm.pendingYouTube?.let { r ->
        YouTubeOptionsDialog(
            resolved = r,
            onConfirm = { audioOnly, stream -> vm.confirmYouTube(audioOnly, stream) },
            onDismiss = { vm.pendingYouTube = null },
        )
    }
    vm.pendingFacebook?.let { r ->
        FacebookOptionsDialog(
            resolved = r,
            onConfirm = { q -> vm.confirmFacebook(q) },
            onDismiss = { vm.pendingFacebook = null },
        )
    }
    vm.pendingPlaylist?.let { pl ->
        PlaylistDialog(
            playlist = pl,
            audioOnlyDefault = vm.batchAudioOnly,
            onConfirm = { audioOnly -> vm.confirmPlaylist(audioOnly) },
            onDismiss = { vm.pendingPlaylist = null },
        )
    }

    // Cuando la cola termina, refresca el historial.
    val eng by DownloadEngine.state.collectAsState()
    var wasRunning by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(eng.running) {
        if (wasRunning && !eng.running) {
            vm.refreshHistory()
            vm.addLog("🏁 ${eng.current}")
            eng.lastError?.let { vm.addLog("⚠️ $it") }
        }
        wasRunning = eng.running
    }

    if (vm.showHistory) {
        HistoryScreen(vm)
    } else {
        MainScreen(vm, eng)
    }
    } // Surface fondo
    } // MaterialTheme dark mode
}

@Composable
private fun MainScreen(vm: DlViewModel, eng: EngineState) {
    val ctx = LocalContext.current
    val firstUrl = vm.url.lines().firstOrNull { it.isNotBlank() }.orEmpty()
    val platform = detectPlatform(firstUrl.trim())
    val urlCount = vm.url.lines().count { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🐼 PandaDL", style = MaterialTheme.typography.headlineMedium)
            Row {
                // v3.10: Botón que abre el navegador integrado.
                var showBrowser by remember { mutableStateOf(false) }
                TextButton(onClick = { showBrowser = true }) {
                    Text("🌐 Navegador")
                }
                if (showBrowser) {
                    BrowserDialog(onDismiss = { showBrowser = false })
                }
                // v3.12: Toggle modo oscuro.
                val darkMode by vm.darkMode.collectAsState()
                TextButton(onClick = { vm.toggleDarkMode() }) {
                    Text(if (darkMode) "☀️" else "🌙")
                }
                // v3.12: Toggle burbuja flotante.
                val bubbleOn by vm.bubbleOn.collectAsState()
                TextButton(onClick = { vm.toggleBubble() }) {
                    Text(if (bubbleOn) "🫧✅" else "🫧")
                }
                TextButton(onClick = { vm.showHistory = true }) {
                    Text("🕘 Historial (${vm.history.size})")
                }
            }
        }
        Text(
            "Baja videos, fotos y audio de YouTube, Instagram y Facebook. " +
                "Pega varios enlaces (uno por línea) para la cola, " +
                "o @usuario para sus historias.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            label = { Text("Pega enlaces (uno por línea) o @usuario") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
        )

        // v2.1: si es un solo enlace de Instagram, ofrecer el campo de música
        // v3.9: Campo de música eliminado. La detección es automática
        // con la sesión de Instagram (login por WebView).

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Detectado: ", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${platform.emoji} ${platform.label}" +
                    if (urlCount > 1) " · $urlCount enlaces" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Para la cola: video o solo audio en YouTube.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("En la cola:", style = MaterialTheme.typography.bodyMedium)
            FilterChip(
                selected = !vm.batchAudioOnly,
                onClick = { vm.batchAudioOnly = false },
                label = { Text("🎬 Video") },
            )
            FilterChip(
                selected = vm.batchAudioOnly,
                onClick = { vm.batchAudioOnly = true },
                label = { Text("🎵 Solo audio") },
            )
        }

        // v2.2: calidad de YouTube para la cola (solo si hay enlaces de YT).
        if (!vm.batchAudioOnly && vm.url.lines().any {
                val l = it.lowercase()
                "youtube.com" in l || "youtu.be" in l
            }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Calidad:", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = vm.ytQuality == null,
                    onClick = { vm.ytQuality = null },
                    label = { Text("Auto") },
                )
                for (q in listOf(2160, 1080, 720, 480, 360)) {
                    FilterChip(
                        selected = vm.ytQuality == q,
                        onClick = { vm.ytQuality = q },
                        label = { Text("${q}p") },
                    )
                }
            }
        }

        Button(
            onClick = { vm.startDownload() },
            enabled = !vm.working && !eng.running && vm.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    vm.working -> "Trabajando…"
                    eng.running -> "Cola en curso…"
                    urlCount > 1 -> "📋 Encolar ($urlCount)"
                    else -> "⬇ Descargar"
                },
            )
        }

        // v3.16: Botón dedicado para foto + música (fuerza el mux aunque
        // la detección automática no haya encontrado canción).
        Button(
            onClick = { vm.startDownload(forceMusic = true) },
            enabled = !vm.working && !eng.running && vm.url.isNotBlank() && urlCount == 1,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("🎵 Descargar con música")
        }

        // Progreso de la descarga individual (v1.1).
        if (vm.working || vm.progress > 0f) {
            LinearProgressIndicator(
                progress = { vm.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Progreso de la cola en segundo plano.
        if (eng.running || (eng.total > 0 && eng.done >= eng.total && eng.current.isNotBlank())) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "📋 Cola: ${eng.done}/${eng.total}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (eng.running) {
                            TextButton(onClick = { DownloadEngine.cancel() }) {
                                Text("⏹ Detener")
                            }
                        }
                    }
                    Text(
                        eng.current.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (eng.total > 0) {
                                (eng.done + eng.itemProgress) / eng.total
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!eng.running) {
                        Text(
                            "✅ ${eng.okCount} ok" +
                                if (eng.failCount > 0) " · ❌ ${eng.failCount} fallos" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Text(vm.status, style = MaterialTheme.typography.bodyMedium)

        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        vm.result?.let { res ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("✅ Guardado en Descargas/PandaDL", style = MaterialTheme.typography.titleSmall)
                    Text(res.note, style = MaterialTheme.typography.bodySmall)
                    res.files.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                f.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { openFile(ctx, f.uri, f.name) }) {
                                Text("Abrir")
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.igConnected) {
                OutlinedButton(onClick = { vm.disconnectIg() }) {
                    Text("📸 IG conectado ✓ (salir)")
                }
            } else {
                OutlinedButton(onClick = { vm.showLogin = true }) {
                    Text("📸 Conectar Instagram")
                }
            }
        }

        Text("Registro", style = MaterialTheme.typography.titleSmall)
        Text(
            vm.log.ifBlank { "(vacío)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ==================== Selector de calidad: YouTube ====================

@Composable
private fun YouTubeOptionsDialog(
    resolved: ResolvedYouTube,
    onConfirm: (audioOnly: Boolean, stream: YtStream) -> Unit,
    onDismiss: () -> Unit,
) {
    var audioOnly by remember { mutableStateOf(resolved.videoQs.isEmpty()) }
    val list = if (audioOnly) resolved.audioQs else resolved.videoQs
    var selected by remember(audioOnly) { mutableStateOf(list.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                resolved.video.title.ifBlank { "Video de YouTube" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !audioOnly,
                        onClick = { audioOnly = false },
                        label = { Text("🎬 Video") },
                        enabled = resolved.videoQs.isNotEmpty(),
                    )
                    FilterChip(
                        selected = audioOnly,
                        onClick = { audioOnly = true },
                        label = { Text("🎵 Solo audio") },
                        enabled = resolved.audioQs.isNotEmpty(),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(list, key = { it.itag }) { s ->
                        val label = if (audioOnly) {
                            YouTubeClient.audioLabel(s)
                        } else {
                            s.qualityLabel ?: "${s.width}p"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = s }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected?.itag == s.itag,
                                onClick = { selected = s },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let { onConfirm(audioOnly, it) } },
                enabled = selected != null,
            ) {
                Text("Descargar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

// ==================== Selector de calidad: Facebook ====================

@Composable
private fun FacebookOptionsDialog(
    resolved: ResolvedFacebook,
    onConfirm: (FbQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(resolved.qualities.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video de Facebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Elige la calidad:")
                resolved.qualities.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = q }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected?.url == q.url,
                            onClick = { selected = q },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (q.label == "HD") "HD (alta calidad)" else "SD (calidad normal)")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let { onConfirm(it) } },
                enabled = selected != null,
            ) {
                Text("Descargar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

// ==================== Playlist de YouTube ====================

@Composable
private fun PlaylistDialog(
    playlist: YtPlaylist,
    audioOnlyDefault: Boolean,
    onConfirm: (audioOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var audioOnly by remember { mutableStateOf(audioOnlyDefault) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                playlist.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${playlist.videos.size} videos en la cola.")
                Text(
                    "Se descargan uno por uno en segundo plano, " +
                        "con la mejor calidad disponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !audioOnly,
                        onClick = { audioOnly = false },
                        label = { Text("🎬 Video") },
                    )
                    FilterChip(
                        selected = audioOnly,
                        onClick = { audioOnly = true },
                        label = { Text("🎵 Solo audio") },
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(playlist.videos.take(50)) { v ->
                        Text(
                            "• ${v.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(audioOnly) }) {
                Text("Encolar (${playlist.videos.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

// ==================== Historial ====================

@Composable
private fun HistoryScreen(vm: DlViewModel) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🕘 Historial", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { vm.showHistory = false }) { Text("Volver") }
        }
        // v3.11: Ruta del archivo de log.
        Text(
            "📝 Log: ${com.panda.pandadl.core.AppLogger.logPath()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.history.isEmpty()) {
            Text(
                "Aún no has descargado nada.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.history) { e ->
                    val plat = runCatching { Platform.valueOf(e.platform) }.getOrNull()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "${plat?.emoji ?: "📦"} ${e.name}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatDate(e.date)} · ${formatBytes(e.size)}" +
                                    if (e.note.isNotBlank()) " · ${e.note}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    runCatching {
                                        openFile(ctx, Uri.parse(e.uri), e.name)
                                    }
                                },
                            ) {
                                Text("Abrir")
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { vm.clearHistory() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Limpiar historial")
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024f / 1024f)
    else "${bytes / 1024} KB"

// ==================== Login de Instagram ====================

// v1.6: spoof de navigator.userAgentData. La página detecta el brand
// "Android WebView" y no pinta nada (el DOM queda completo pero con
// cero píxeles). Se inyecta como PRIMER script del <head> para que
// corra antes que cualquier código de Instagram.
private const val IG_UAD_SPOOF_JS =
    "try{Object.defineProperty(navigator,'userAgentData',{configurable:true," +
        "get:function(){return{brands:[" +
        "{brand:'Chromium',version:'154'}," +
        "{brand:'Google Chrome',version:'154'}," +
        "{brand:'Not-A.Brand',version:'99'}]," +
        "mobile:true,platform:'Android'," +
        "getHighEntropyValues:function(){return Promise.resolve({});}};}});}catch(e){}"

// v1.6: descarga el HTML del login, le inyecta el spoof como primer
// script del <head> (con el nonce de la página para no chocar con el
// CSP) y lo sirve al WebView. Si algo falla, devuelve null y el
// WebView carga la página por su cuenta como antes.
private fun interceptLoginHtml(view: WebView?, url: String): WebResourceResponse? {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.setRequestProperty(
            "User-Agent",
            view?.settings?.userAgentString ?: "",
        )
        conn.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        conn.setRequestProperty("Accept-Encoding", "identity")
        val ck = CookieManager.getInstance().getCookie(url)
        if (!ck.isNullOrBlank()) conn.setRequestProperty("Cookie", ck)
        conn.connect()
        if (conn.responseCode !in 200..299) return null
        conn.headerFields.forEach { (k, v) ->
            if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                v.forEach { CookieManager.getInstance().setCookie(url, it) }
            }
        }
        val raw = conn.inputStream.readBytes()
        val bytes =
            if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                GZIPInputStream(raw.inputStream()).readBytes()
            } else {
                raw
            }
        var html = bytes.toString(Charsets.UTF_8)
        val nonce = Regex("nonce=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
        val tag = if (nonce != null) "<script nonce=\"$nonce\">" else "<script>"
        if ("<head>" in html) {
            html = html.replaceFirst("<head>", "<head>$tag$IG_UAD_SPOOF_JS</script>")
        }
        return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
    } catch (e: Exception) {
        return null
    } finally {
        conn.disconnect()
    }
}

// v1.5: vuelca el DOM real para diagnosticar la página en blanco.
private const val IG_DOM_DUMP_JS =
    "(function(){try{" +
        "var b=document.body||document.documentElement;" +
        "var q=function(s){return document.querySelectorAll(s).length;};" +
        "var uad='none';" +
        "try{if(navigator.userAgentData&&navigator.userAgentData.brands){" +
        "uad=navigator.userAgentData.brands.map(function(x){return x.brand+':'+x.version;}).join(',');}" +
        "else if(navigator.userAgentData){uad='sin-brands';}}catch(e){uad='err';}" +
        "var txt=((b.innerText||'').replace(/\\s+/g,' ')).substring(0,250);" +
        "return 'title='+document.title+'|html='+" +
        "document.documentElement.outerHTML.length+" +
        "'|inputs='+q('input')+'|buttons='+q('button')+" +
        "'|uad=['+uad+']|texto='+txt;" +
        "}catch(e){return 'dump-err:'+e;}})()"

// Diagnóstico visual: compara estilos y zonas de interacción del formulario.
private const val IG_VISIBILITY_DIAG_JS = """
(function() {
  try {
    function inspect(el) {
      if (!el) return null;
      var s = getComputedStyle(el);
      var r = el.getBoundingClientRect();
      return {
        tag: el.tagName,
        type: el.type || "",
        display: s.display,
        visibility: s.visibility,
        opacity: s.opacity,
        pointerEvents: s.pointerEvents,
        position: s.position,
        width: Math.round(r.width),
        height: Math.round(r.height),
        x: Math.round(r.x),
        y: Math.round(r.y),
        disabled: !!el.disabled
      };
    }
    var inputs = Array.prototype.slice.call(document.querySelectorAll("input"))
      .slice(0, 5).map(inspect);
    var forms = Array.prototype.slice.call(document.querySelectorAll("form"))
      .slice(0, 3).map(inspect);
    var b = document.body;
    var r = b ? b.getBoundingClientRect() : null;
    var cx = Math.max(0, Math.min(innerWidth - 1, Math.floor(innerWidth / 2)));
    var cy = Math.max(0, Math.min(innerHeight - 1, Math.floor(innerHeight / 2)));
    var hit = document.elementFromPoint(cx, cy);
    return JSON.stringify({
      readyState: document.readyState,
      viewport: innerWidth + "x" + innerHeight,
      body: inspect(b),
      bodyBackground: b ? getComputedStyle(b).backgroundColor : null,
      inputs: inputs,
      forms: forms,
      elements: Array.from(document.querySelectorAll('body *'))
        .slice(0, 25)
        .map(function(el) {
          var r = el.getBoundingClientRect();
          var s = getComputedStyle(el);
          return {
            tag: el.tagName,
            id: el.id,
            className: String(el.className).slice(0, 100),
            height: Math.round(r.height),
            width: Math.round(r.width),
            display: s.display,
            position: s.position,
            overflow: s.overflow
          };
        }),
      activeElement: document.activeElement
        ? document.activeElement.tagName
        : null,
      documentHeight: document.documentElement.scrollHeight,
      bodyScrollHeight: document.body.scrollHeight,
      centerElement: hit ? hit.tagName + (hit.id ? "#" + hit.id : "") +
        (hit.className && typeof hit.className === "string" ? "." +
        hit.className.trim().replace(/\s+/g, ".").slice(0, 100) : "") : null
    });
  } catch (e) {
    return JSON.stringify({diagnosticError: String(e)});
  }
})()
"""

// v1.4: acepta solo el diálogo de cookies pre-login de Instagram
// (puede quedar invisible sobre el formulario interceptando toques).
private const val IG_CONSENT_JS =
    "(function(){try{var els=document.querySelectorAll('button');" +
        "for(var i=0;i<els.length;i++){" +
        "var t=((els[i].innerText||'')+' '+(els[i].textContent||'')).toLowerCase();" +
        "if(t.indexOf('allow all cookies')>=0||t.indexOf('permitir todas')>=0" +
        "||t.indexOf('aceptar todas')>=0||t.indexOf('allow all')>=0)" +
        "{els[i].click();return 'consent-clicked';}}" +
        "return 'no-consent';}catch(e){return 'err';}})()"

// v1.8: rellena el formulario de login oculto con las credenciales de los
// campos nativos y pulsa "Iniciar sesión". Usa el setter nativo de React
// para que la página detecte los cambios.
// v1.10: login directo por AJAX desde el contexto de la página (mismo
// origen, mismas cookies y fingerprint que el formulario real).
// Devuelve el JSON de Instagram por el puente PandaDL.onAjaxResult.
private fun igAjaxLoginJs(user: String, pass: String): String {
    val u = JSONObject.quote(user)
    val p = JSONObject.quote(pass)
    return "(function(){try{" +
        "function getC(n){var m=document.cookie.match(new RegExp('(^| )'+n+'=([^;]+)'));" +
        "return m?m[2]:'';}" +
        "var csrftoken=getC('csrftoken');" +
        "if(!csrftoken){PandaDL.onAjaxResult('no-csrftoken');return;}" +
        "var ts=Math.floor(Date.now()/1000);" +
        "var body='username='+encodeURIComponent($u)" +
        "+'&enc_password='+encodeURIComponent('#PWD_INSTAGRAM_BROWSER:0:'+ts+':'+$p);" +
        "fetch('/api/v1/web/accounts/login/ajax/',{method:'POST',credentials:'same-origin'," +
        "headers:{'Content-Type':'application/x-www-form-urlencoded'," +
        "'X-CSRFToken':csrftoken,'X-IG-App-ID':'936619743392459'," +
        "'X-Requested-With':'XMLHttpRequest'},body:body})" +
        ".then(function(r){return r.text();})" +
        ".then(function(t){PandaDL.onAjaxResult(t.substring(0,800));})" +
        ".catch(function(e){PandaDL.onAjaxResult('fetch-err:'+e);});" +
        "}catch(e){PandaDL.onAjaxResult('js-err:'+e);}})()"
}

private fun igFillLoginJs(user: String, pass: String): String {
    val u = JSONObject.quote(user)
    val p = JSONObject.quote(pass)
    return "(function(){try{" +
        "function setV(el,v){var pr=Object.getPrototypeOf(el),d=null;" +
        "while(pr&&pr!==Object.prototype){" +
        "d=Object.getOwnPropertyDescriptor(pr,'value');" +
        "if(d&&d.set)break;pr=Object.getPrototypeOf(pr);}" +
        "if(d&&d.set){d.set.call(el,v);}else{el.value=v;}" +
        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "el.dispatchEvent(new Event('change',{bubbles:true}));};" +
        "var ui=document.querySelector('input[name=\"username\"]')||" +
        "document.querySelector('input[type=\"text\"]');" +
        "var pi=document.querySelector('input[name=\"password\"]')||" +
        "document.querySelector('input[type=\"password\"]');" +
        "if(!ui||!pi)return 'no-inputs';" +
        "setV(ui,$u);setV(pi,$p);" +
        "var btn=null;" +
        "var els=document.querySelectorAll('button,div[role=\"button\"],input[type=\"submit\"]');" +
        "for(var i=0;i<els.length;i++){" +
        "var t=((els[i].innerText||'')+' '+(els[i].value||'')).toLowerCase();" +
        "if(t.indexOf('iniciar sesi')>=0||t.indexOf('log in')>=0){btn=els[i];break;}};" +
        "if(!btn)return 'no-button';" +
        "btn.click();return 'clicked';" +
        "}catch(e){return 'err:'+e;}})()"
}

@Composable
private fun InstagramLoginDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    // v3.8: login por WebView (como PkgBrowser/1DM). El AJAX daba HTTP 400.
    // El usuario inicia sesión en la página real de Instagram; detectamos
    // la cookie sessionid automáticamente.
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Cargando Instagram…") }
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Conectar Instagram") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c ->
                        android.webkit.WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            // v3.14: User-agent de ESCRITORIO como IDM.
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                            android.webkit.CookieManager.getInstance()
                                .setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(
                                    view: android.webkit.WebView, url: String?
                                ) {
                                    super.onPageFinished(view, url)
                                    // Revisar si ya hay sesión.
                                    val cookies =
                                        android.webkit.CookieManager.getInstance()
                                            .getCookie("https://www.instagram.com")
                                            .orEmpty()
                                    if (cookies.contains("sessionid=")) {
                                        status = "¡Sesión detectada! Guardando…"
                                        val session =
                                            com.panda.pandadl.ig.InstagramSession(ctx)
                                        if (session.saveFromCookieString(cookies)) {
                                            onDone()
                                        } else {
                                            status = "No se pudo guardar la sesión."
                                        }
                                    } else {
                                        status = "Inicia sesión en Instagram arriba."
                                    }
                                }
                            }
                            loadUrl("https://www.instagram.com/accounts/login/")
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // Verificar manualmente por si la detección automática falló.
                            val cookies = android.webkit.CookieManager.getInstance()
                                .getCookie("https://www.instagram.com").orEmpty()
                            if (cookies.contains("sessionid=")) {
                                val session = com.panda.pandadl.ig.InstagramSession(ctx)
                                if (session.saveFromCookieString(cookies)) {
                                    onDone()
                                } else {
                                    status = "No se pudo guardar la sesión."
                                }
                            } else {
                                status = "Aún no hay sesión. Termina de iniciar sesión arriba."
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("✓ Ya entré") }
                    OutlinedButton(
                        onClick = { webViewRef?.reload() },
                    ) { Text("↻") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancelar") }
        },
    )
}

// ==================== Utilidad: abrir archivo ====================

private fun openFile(ctx: Context, uri: Uri, name: String) {
    val mime = when (name.substringAfterLast(".", "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "webm" -> "audio/webm"
        else -> "video/*"
    }
    val open = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(open, "Abrir con"))
}
