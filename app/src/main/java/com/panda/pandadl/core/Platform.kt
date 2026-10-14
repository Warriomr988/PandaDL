package com.panda.pandadl.core

/** Plataformas soportadas por PandaDL. */
enum class Platform(val label: String, val emoji: String) {
    YOUTUBE("YouTube", "\uD83D\uDD34"),
    INSTAGRAM("Instagram", "\uD83D\uDCF8"),
    FACEBOOK("Facebook", "\uD83D\uDCF1"),
    DIRECT("Enlace directo", "\uD83D\uDD17"),
    UNKNOWN("Desconocido", "❓"),
}

/** Detecta la plataforma a partir de la URL pegada. */
fun detectPlatform(rawUrl: String): Platform {
    val u = rawUrl.trim().lowercase()
    return when {
        "youtube.com" in u || "youtu.be" in u -> Platform.YOUTUBE
        "instagram.com" in u -> Platform.INSTAGRAM
        "facebook.com" in u || "fb.watch" in u -> Platform.FACEBOOK
        Regex(""".*\.(mp4|m4v|webm|mov|jpg|jpeg|png|webp)(\?.*)?$""").matches(u) -> Platform.DIRECT
        else -> Platform.UNKNOWN
    }
}

/** Limpia un texto para usarlo como nombre de archivo. */
fun sanitizeFileName(name: String, maxLen: Int = 60): String {
    var s = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (s.length > maxLen) s = s.substring(0, maxLen).trim()
    return s.ifEmpty { "pandadl" }
}

/** Excepción con mensaje listo para mostrar al usuario (sin tecnicismos). */
class FriendlyException(message: String) : Exception(message)
