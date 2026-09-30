package eu.ottop.yamlauncher.tasks

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.scale
import androidx.core.net.toUri
import eu.ottop.yamlauncher.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tracks the active media session and exposes a compact "Now Playing" state.
 *
 * Built around framework callbacks only: there is no polling. Between [start] and
 * [stop] the only resident cost is the sessions-changed listener plus one
 * [MediaController.Callback] per active session. Outside of that window nothing
 * is registered, so a stopped launcher costs nothing.
 *
 * Requires notification listener access (shared with [NotificationListener]);
 * without it [start] is a no-op that reports "nothing playing".
 */
class NowPlayingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val artSizePx: Int,
    private val listener: Listener,
) {

    interface Listener {
        /** Called on the main thread. `null` means the widget should be hidden. */
        fun onNowPlayingChanged(info: NowPlayingInfo?)
    }

    data class NowPlayingInfo(
        val packageName: String,
        val title: CharSequence,
        val artist: CharSequence?,
        val albumArt: Bitmap?,
        val isPlaying: Boolean,
        val canPlayPause: Boolean,
        val canSkipToNext: Boolean,
        val canSkipToPrevious: Boolean,
    )

    private val logger = Logger.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, NotificationListener::class.java)

    private var isStarted = false
    private var controllers: List<MediaController> = emptyList()
    private var activeController: MediaController? = null
    private var lastInfo: NowPlayingInfo? = null

    // Album art is cached per track so playback-state churn never re-decodes it.
    private var artTrackKey: String? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null
    private var artUriFailed = false

    // Session the user swiped away. It stays hidden until it either disappears or
    // starts playing again after having been paused/stopped (i.e. a fresh play).
    private var dismissedToken: MediaSession.Token? = null
    private var dismissedSeenInactive = false

    private val pausedTimeout = Runnable { evaluate() }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        updateControllers(sessions.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
        override fun onSessionDestroyed() = refreshSessions()
    }

    fun start() {
        if (isStarted) return
        if (!NotificationListener.isEnabled(context)) {
            publish(null)
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            isStarted = true
            updateControllers(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            logger.w("NowPlayingManager", "Media session access denied: ${e.message}")
            publish(null)
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
        activeController = null
        handler.removeCallbacks(pausedTimeout)
        clearArt()
        publish(null)
    }

    fun playPause() {
        val controller = activeController ?: return
        val playing = controller.playbackState?.let { isActiveState(it.state) } == true
        runCatching {
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }.onFailure { logger.w("NowPlayingManager", "playPause failed: ${it.message}") }
    }

    fun skipToNext() {
        runCatching { activeController?.transportControls?.skipToNext() }
            .onFailure { logger.w("NowPlayingManager", "skipToNext failed: ${it.message}") }
    }

    fun skipToPrevious() {
        runCatching { activeController?.transportControls?.skipToPrevious() }
            .onFailure { logger.w("NowPlayingManager", "skipToPrevious failed: ${it.message}") }
    }

    /** Opens the player screen the session advertises, else the app's launcher activity. */
    fun openApp() {
        val controller = activeController ?: return
        val sentSessionActivity = controller.sessionActivity?.let { pendingIntent ->
            runCatching { pendingIntent.send(context, 0, null, null, null, null, allowBackgroundStartOptions()) }
                .onFailure { logger.w("NowPlayingManager", "sessionActivity failed: ${it.message}") }
                .isSuccess
        } == true
        if (sentSessionActivity) return

        val launchIntent = context.packageManager.getLaunchIntentForPackage(controller.packageName) ?: return
        runCatching { context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { logger.w("NowPlayingManager", "Launching ${controller.packageName} failed: ${it.message}") }
    }

    // Since Android 14 the sender must opt in to lend its foreground state to another app's PendingIntent.
    @Suppress("DEPRECATION")
    private fun allowBackgroundStartOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    }

    /**
     * Mirrors the close button on the system media player: the session is asked to
     * stop and its (clearable) notification is cancelled. The widget hides at once
     * and does not come back for this session unless it is started anew.
     */
    fun dismiss() {
        val controller = activeController ?: return
        dismissedToken = controller.sessionToken
        dismissedSeenInactive = false
        runCatching { controller.transportControls.stop() }
            .onFailure { logger.w("NowPlayingManager", "stop failed: ${it.message}") }
        NotificationListener.getInstance()?.cancelMediaNotification(controller.sessionToken)
        evaluate()
    }

    private fun refreshSessions() {
        if (!isStarted) return
        try {
            updateControllers(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            logger.w("NowPlayingManager", "Lost media session access: ${e.message}")
            stop()
        }
    }

    private fun updateControllers(sessions: List<MediaController>) {
        if (!isStarted) return
        val existing = controllers.associateBy { it.sessionToken }
        val tokens = sessions.map { it.sessionToken }.toHashSet()

        controllers.filter { it.sessionToken !in tokens }
            .forEach { it.unregisterCallback(controllerCallback) }

        // Reuse controllers we already listen to; the framework hands out fresh objects.
        controllers = sessions.map { fresh ->
            existing[fresh.sessionToken] ?: fresh.also { it.registerCallback(controllerCallback, handler) }
        }

        if (dismissedToken != null && dismissedToken !in tokens) {
            dismissedToken = null
        }
        evaluate()
    }

    private fun evaluate() {
        if (!isStarted) return
        handler.removeCallbacks(pausedTimeout)
        trackDismissedSession()

        val now = SystemClock.elapsedRealtime()
        var pausedCandidate: MediaController? = null
        var nextTimeoutIn = Long.MAX_VALUE
        var playingCandidate: MediaController? = null

        // Sessions are already in system priority order (most recently active first).
        for (controller in controllers) {
            if (controller.sessionToken == dismissedToken) continue
            val state = controller.playbackState ?: continue
            when {
                isActiveState(state.state) -> {
                    playingCandidate = controller
                }
                state.state == PlaybackState.STATE_PAUSED && pausedCandidate == null -> {
                    val remaining = PAUSED_TIMEOUT_MS - (now - state.lastPositionUpdateTime)
                    if (remaining > 0) {
                        pausedCandidate = controller
                        nextTimeoutIn = remaining
                    }
                }
            }
            if (playingCandidate != null) break
        }

        val chosen = playingCandidate ?: pausedCandidate
        if (chosen == null) {
            activeController = null
            clearArt()
            publish(null)
            return
        }
        if (playingCandidate == null) {
            // Hide exactly when the paused grace period ends, no polling needed.
            handler.postDelayed(pausedTimeout, nextTimeoutIn)
        }
        activeController = chosen
        publish(buildInfo(chosen))
    }

    private fun trackDismissedSession() {
        val token = dismissedToken ?: return
        val controller = controllers.firstOrNull { it.sessionToken == token } ?: return
        val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        if (!isActiveState(state)) {
            dismissedSeenInactive = true
        } else if (dismissedSeenInactive) {
            dismissedToken = null
        }
    }

    private fun buildInfo(controller: MediaController): NowPlayingInfo {
        val metadata = controller.metadata
        val state = controller.playbackState
        val actions = state?.actions ?: 0L

        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(controller.packageName)
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

        updateArt(metadata, "${controller.packageName}|$title|$artist")

        return NowPlayingInfo(
            packageName = controller.packageName,
            title = title,
            artist = artist?.takeIf { it.isNotBlank() },
            albumArt = art,
            isPlaying = state?.let { isActiveState(it.state) } == true,
            canPlayPause = actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
            canSkipToNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipToPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        )
    }

    private fun updateArt(metadata: MediaMetadata?, trackKey: String) {
        if (trackKey != artTrackKey) {
            clearArt()
            artTrackKey = trackKey
        }
        if (art != null || metadata == null) return

        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            art = downscale(bitmap)
            return
        }

        // One URI attempt per track: don't restart or retry on every state change.
        if (artUriFailed || artJob?.isActive == true) return
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return
        artJob = scope.launch(Dispatchers.IO) {
            val loaded = loadArtFromUri(uri)
            withContext(Dispatchers.Main) {
                if (!isStarted || artTrackKey != trackKey) return@withContext
                if (loaded == null) {
                    artUriFailed = true
                } else {
                    art = loaded
                    activeController?.let { publish(buildInfo(it)) }
                }
            }
        }
    }

    private fun loadArtFromUri(uri: String): Bitmap? {
        return try {
            val parsed = uri.toUri()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return null
            var sample = 1
            while (largest / (sample * 2) >= artSizePx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            logger.d("NowPlayingManager", "Album art URI not loadable: ${e.message}")
            null
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= artSizePx * 2 || artSizePx <= 0) return bitmap
        val scale = artSizePx * 2f / largest
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun clearArt() {
        artJob?.cancel()
        artJob = null
        art = null
        artTrackKey = null
        artUriFailed = false
    }

    private fun appLabel(packageName: String): CharSequence {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
        } catch (_: Exception) {
            packageName
        }
    }

    private fun publish(info: NowPlayingInfo?) {
        if (info == lastInfo) return
        lastInfo = info
        listener.onNowPlayingChanged(info)
    }

    private fun isActiveState(state: Int): Boolean = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private companion object {
        /**
         * How long a paused session keeps the widget visible so Play is still reachable.
         * Sessions that stop, error out or vanish hide the widget immediately.
         */
        const val PAUSED_TIMEOUT_MS = 5 * 60_000L
    }
}
