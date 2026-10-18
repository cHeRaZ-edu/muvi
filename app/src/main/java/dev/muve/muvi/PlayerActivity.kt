package dev.muve.muvi

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import dev.muve.muvi.data.MuveError
import dev.muve.muvi.data.Video
import dev.muve.muvi.data.fmtTime
import dev.muve.muvi.data.subtitleLanguage

/**
 * Reproductor a pantalla completa. Recibe una lista de rutas del drive y el índice inicial.
 * Si el video está en el teléfono suena directo del archivo; si no, del servidor local del SDK
 * (descifra mientras baja y deja adelantar). Recuerda dónde te quedaste en cada video.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val repo get() = (application as MuviApp).repo
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var errorView: TextView
    private var paths: List<String> = emptyList()
    private var currentPath: String? = null
    private var manualOrientation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(buildLayout())
        val p = buildPlayer()
        player = p
        view.player = p
        if (!load(intent)) finish()
    }

    /** Otro video desde la biblioteca mientras este sigue abierto (o en la ventanita). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        savePosition()
        setIntent(intent)
        manualOrientation = false
        load(intent)
    }

    private fun load(intent: Intent): Boolean {
        val p = player ?: return false
        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        if (paths.isEmpty()) return false
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, paths.size - 1)
        val items = paths.mapNotNull { path -> runCatching { mediaItem(path) }.getOrNull() }
        if (items.size != paths.size) showError("No se pudieron preparar algunos videos. Revisa la configuración.")
        if (items.isEmpty()) return true
        val startIndex = items.indexOfFirst { it.mediaId == paths[start] }.coerceAtLeast(0)
        val resume = repo.positions.get(items[startIndex].mediaId)
        currentPath = items[startIndex].mediaId
        p.setMediaItems(items, startIndex, resume)
        p.prepare()
        p.playWhenReady = true
        if (resume > 0) toast("Sigues en ${fmtTime(resume)}")
        return true
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            keepScreenOn = true
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowSubtitleButton(true)
            setShowShuffleButton(false)
            setFullscreenButtonClickListener { full -> setLandscape(full); manualOrientation = true }
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v })
        }
        root.addView(view)

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Atrás"
            setOnClickListener { finish() }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(24), dp(16), dp(8))
            addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        root.addView(topBar)

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { setMargins(dp(24), 0, dp(24), 0) }
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(errorView)
        return root
    }

    private fun buildPlayer(): ExoPlayer {
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
            .apply { addListener(listener) }
    }

    /** MediaItem con sus subtítulos (archivos .srt/.vtt/.ass con el mismo nombre en el drive). */
    private fun mediaItem(path: String): MediaItem {
        val video = repo.catalog.videos.firstOrNull { it.path == path } ?: Video(path, 0, 0, false, "")
        val subs = repo.catalog.subtitlesFor(video).mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(repo.playUri(s.path))
                .setMimeType(subtitleMime(s.ext))
                .setLanguage(subtitleLanguage(video, s))
                .setLabel(subtitleLanguage(video, s) ?: s.fileName)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(repo.playUri(path))
            .setMediaId(path)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).build())
            .setSubtitleConfigurations(subs)
            .build()
    }

    private fun subtitleMime(ext: String) = when (ext) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (old.mediaItemIndex == new.mediaItemIndex) return
            val oldPath = old.mediaItem?.mediaId ?: return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                repo.positions.clear(oldPath) // se termino solo
            } else {
                repo.positions.save(oldPath, old.positionMs, durationOf(old.mediaItemIndex))
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val p = player ?: return
            currentPath = item?.mediaId
            titleView.text = item?.mediaMetadata?.title ?: ""
            errorView.visibility = View.GONE
            if (!manualOrientation) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val saved = item?.mediaId?.let { repo.positions.get(it) } ?: 0L
                if (saved > 0) { p.seekTo(saved); toast("Sigues en ${fmtTime(saved)}") }
            }
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            // video horizontal: girar la pantalla solo (el boton de pantalla completa lo cambia a mano)
            if (!manualOrientation && size.width > 0 && size.height > 0) setLandscape(size.width > size.height)
            updatePip()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) currentPath?.let { repo.positions.clear(it) }
        }

        override fun onPlayerError(error: PlaybackException) = showError(errorText(error))
    }

    private fun durationOf(index: Int): Long {
        val t = player?.currentTimeline ?: return 0L
        if (index < 0 || index >= t.windowCount) return 0L
        val d = t.getWindow(index, androidx.media3.common.Timeline.Window()).durationMs
        return if (d == C.TIME_UNSET) 0L else d
    }

    /** Guarda la posicion del video actual. */
    private fun savePosition() {
        val p = player ?: return
        val path = currentPath ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        repo.positions.save(path, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }

    private fun errorText(e: PlaybackException): String {
        val cause = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull { it.contains('[') }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "Este teléfono no puede reproducir este video (formato o códec). " +
                    "Conviértelo en la PC a MP4 y súbelo de nuevo:\nffmpeg -i video -c:v libx264 -c:a aac video.mp4"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Sin conexión. Los videos descargados al teléfono sí se ven sin internet."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "No se pudo leer el video del drive." + (cause?.let { "\n" + MuveError.from(Exception(it)).friendly } ?: "")
            else -> "No se pudo reproducir: ${e.errorCodeName}"
        } + "\n\nToca para cerrar este aviso."
    }

    private fun showError(text: String) {
        errorView.text = text
        errorView.visibility = View.VISIBLE
        view.showController()
    }

    private fun setLandscape(landscape: Boolean) {
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // ---------- imagen en imagen (seguir viendo mientras usas otra app) ----------

    private fun pipParams(): PictureInPictureParams? {
        val v = player?.videoSize ?: return null
        val b = PictureInPictureParams.Builder()
        if (v.width > 0 && v.height > 0) {
            // Android solo acepta proporciones entre 1:2.39 y 2.39:1
            val r = (v.width.toFloat() / v.height).coerceIn(1 / 2.38f, 2.38f)
            b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(player?.isPlaying == true)
        return b.build()
    }

    private fun updatePip() {
        if (!hasPip()) return
        pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    private fun hasPip() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && hasPip() && player?.isPlaying == true) {
            pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) topBar.visibility = View.GONE
    }

    // ---------- ciclo de vida ----------

    override fun onPause() {
        super.onPause()
        savePosition()
        updatePip()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        // al cerrar la ventanita de imagen en imagen también se detiene
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        savePosition()
        player?.removeListener(listener)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_INDEX = "index"

        fun intent(ctx: Context, paths: List<String>, index: Int): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                .putExtra(EXTRA_INDEX, index)
    }
}
