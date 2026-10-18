# muvi

Videos para Android desde tu **muve drive**. Los videos viven cifrados en tu nube (Backblaze B2);
la app los sube, los reproduce en línea o los baja al teléfono, y los descifra aquí.

- **Kotlin + Jetpack Compose**, Android 8+.
- **Media3 (ExoPlayer + PlayerView)**: pantalla completa, gira sola con videos horizontales,
  adelantar/atrasar 10 s, velocidad, pistas de audio y subtítulos, imagen en imagen.
- **Formatos**: MP4, M4V, MKV, WebM, MOV, 3GP, AVI, TS, FLV, MPG, OGV. WMV, ASF, VOB y M2TS se suben
  igual pero el teléfono quizá no los reproduzca (la app lo avisa). Si un códec no lo soporta tu
  teléfono, conviértelo en la PC:
  ```sh
  ffmpeg -i video.wmv -c:v libx264 -c:a aac video.mp4
  ```
- **Subtítulos**: `.srt`, `.vtt`, `.ass`, `.ssa` con el mismo nombre del video (`peli.srt`, `peli.es.srt`)
  se cargan solos. Súbelos junto al video.
- **Sigue donde ibas**: recuerda el minuto de cada video.
- **Subidas y descargas en segundo plano**: siguen aunque salgas de la app o se apague la pantalla,
  con avance y botón de cancelar en la notificación.
- **SDK de muve en Go** (`app/libs/muve-android.aar`):
  - Lo descargado se reproduce directo del archivo, sin internet.
  - Lo demás sale del servidor local del SDK (`127.0.0.1`, con token): descifra mientras baja y deja adelantar.
- **La llave y el secreto** se guardan cifrados con el Android Keystore.

## Configurar

En el panel del drive crea una app para los videos (por ejemplo `muvi`) y una llave para el teléfono
con permisos de subir, descargar y eliminar. En la app pon el servidor, esa llave y tu secreto de cifrado.

## Instalar

Baja `muvi-X.Y.Z.apk` del [último release](../../releases/latest) y ábrelo en el teléfono.
Las siguientes versiones se instalan encima y conservan todo, porque van firmadas con la misma llave.

La versión de Android Studio se instala aparte como **muvi dev** (`dev.muve.muvi.debug`), así no choca
con la del release.

## Publicar una versión

1. Sube `versionName` y `versionCode` en `app/build.gradle.kts`.
2. Crea el tag: `git tag v0.2.0 && git push origin v0.2.0`.
3. El workflow `release` compila, firma y publica el APK con su `checksums.txt`.

Necesita estos secretos del repo (**Settings → Secrets and variables → Actions**):

| Secreto | Valor |
|---|---|
| `MUVI_KEYSTORE_B64` | `base64 -w0 muvi-release.jks` |
| `MUVI_KEYSTORE_PASSWORD` | contraseña del almacén |
| `MUVI_KEY_ALIAS` | `muvi` |
| `MUVI_KEY_PASSWORD` | contraseña de la llave |

Guarda el `.jks` en un lugar seguro: si se pierde, las versiones nuevas ya no se instalan encima
(habría que desinstalar y volver a configurar).

## Actualizar el SDK

```sh
sh scripts/actualizar-muve.sh 0.3.0   # baja muve-android.aar de ese release y revisa su checksum
```

Después haz commit de `app/libs/`.
