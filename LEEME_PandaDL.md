# PandaDL 🐼 v1.0

Descargador de videos e imágenes para Android: **YouTube, Instagram y Facebook**.

## Lo que hace

- **YouTube**: pega el enlace (video, short, youtu.be) → baja el MP4 en la mejor
  calidad disponible.
- **Instagram**: reels, videos, fotos y carruseles. Requiere conectar tu cuenta
  una vez (botón "📸 Conectar Instagram") porque Instagram no entrega los posts
  sin sesión.
- **Fotos con música**: si la foto de Instagram trae canción, la app detecta
  cuál es (título + artista), descarga el audio y genera un MP4 con la foto
  fija y la canción de fondo. Todo en el teléfono, sin dependencias externas.
- **Facebook**: videos públicos (HD cuando se encuentra).
- **Compartir → PandaDL**: desde Instagram/YouTube/Facebook usa "Compartir" y
  elige PandaDL para no pegar el enlace a mano.

Los archivos quedan en **Descargas/PandaDL**.

## Notas técnicas

- Sin FFmpeg ni binarios nativos: la mezcla foto+audio y video+audio se hace
  con MediaCodec + MediaMuxer del propio Android (`mux/Muxer.kt`).
- YouTube se resuelve con la API interna (youtubei, cliente ANDROID); si algún
  día deja de responder, subir `YouTubeClient.CLIENT_VERSION` a una versión
  actual de la app suele arreglarlo.
- Instagram se lee con tu cookie de sesión (`?__a=1&__d=dis`); si Instagram
  cambia el endpoint, la app lo dice en el registro en vez de fallar en silencio.
- Uso personal: descarga solo contenido público o del que tengas permiso.

## Compilar en Termux (con Panda)

```
# (los comandos exactos llegan con el anuncio de la versión)
python3 panda.py projects/PandaDL
compilar
```

Paquete: `com.panda.pandadl` · compileSdk/targetSdk 34 · minSdk 23.
