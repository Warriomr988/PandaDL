package com.panda.pandadl.ui

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * v3.10: Navegador integrado estilo 1DM.
 * Botón que abre el navegador, pestañas, y menú con opciones.
 */
data class BrowserTab(
    val id: Long = System.currentTimeMillis(),
    var title: String = "Nueva pestaña",
    var url: String = "https://www.google.com",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserDialog(onDismiss: () -> Unit) {
    val tabs = remember { mutableStateListOf(BrowserTab()) }
    var activeTabId by remember { mutableStateOf(tabs[0].id) }
    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf(tabs[0].url) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs[0]

    // Drawer de pestañas.
    if (showTabs) {
        AlertDialog(
            onDismissRequest = { showTabs = false },
            title = { Text("Pestañas") },
            text = {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(tabs, key = { it.id }) { tab ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeTabId = tab.id
                                    urlText = tab.url
                                    showTabs = false
                                    webViewRef?.loadUrl(tab.url)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tab.title.take(30),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = {
                                if (tabs.size > 1) {
                                    tabs.remove(tab)
                                    if (tab.id == activeTabId) {
                                        activeTabId = tabs[0].id
                                        urlText = tabs[0].url
                                        webViewRef?.loadUrl(tabs[0].url)
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Close, "Cerrar")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newTab = BrowserTab()
                    tabs.add(newTab)
                    activeTabId = newTab.id
                    urlText = newTab.url
                    showTabs = false
                    webViewRef?.loadUrl(newTab.url)
                }) { Text("+ Nueva pestaña") }
            },
            dismissButton = {
                TextButton(onClick = { showTabs = false }) { Text("Cerrar") }
            },
        )
    }

    // v3.13: Diálogo a pantalla completa (como el navegador de 1DM).
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                // v3.13.1: Una sola barra como 1DM (sin título separado).
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Barra de navegación.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                    ) { Icon(Icons.Default.ArrowBack, "Atrás") }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                    ) { Icon(Icons.Default.ArrowForward, "Adelante") }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, "Recargar")
                    }
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Buscar") },
                    )
                    IconButton(onClick = {
                        var u = urlText.trim()
                        if (!u.startsWith("http")) u = "https://$u"
                        activeTab.url = u
                        webViewRef?.loadUrl(u)
                    }) { Icon(Icons.Default.Search, "Ir") }
                    IconButton(onClick = { showTabs = true }) {
                        BadgedBox(
                            badge = { Badge { Text("${tabs.size}") } }
                        ) { Icon(Icons.Default.List, "Pestañas") }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Menú")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Nueva pestaña") },
                                onClick = {
                                    showMenu = false
                                    val newTab = BrowserTab()
                                    tabs.add(newTab)
                                    activeTabId = newTab.id
                                    urlText = newTab.url
                                    webViewRef?.loadUrl(newTab.url)
                                },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Volver a PandaDL") },
                                onClick = {
                                    showMenu = false
                                    onDismiss()
                                },
                                leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Salir del navegador") },
                                onClick = {
                                    showMenu = false
                                    onDismiss()
                                },
                                leadingIcon = { Icon(Icons.Default.ExitToApp, null) },
                            )
                        }
                    }
                }
                // WebView.
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            // v3.14: User-agent de ESCRITORIO como el navegador de IDM
                            // (Instagram bloquea el UA móvil del WebView).
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            // v3.13: Cookies de terceros (Instagram las necesita).
                            cm.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?, url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    url?.let {
                                        urlText = it
                                        activeTab.url = it
                                        canGoBack = view?.canGoBack() == true
                                        canGoForward = view?.canGoForward() == true
                                    }
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.title?.let {
                                        if (it.isNotBlank()) activeTab.title = it
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }
                            }
                            loadUrl(activeTab.url)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                } // Column contenido
            } // Column principal
        } // Surface
    } // Dialog
}
