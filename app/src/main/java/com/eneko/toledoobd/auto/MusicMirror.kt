package com.eneko.toledoobd.auto

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * No hace nada por sí solo: Android exige un "acceso a notificaciones" para dejar a una app
 * ver qué suena en otras (Spotify…). Con este permiso la tarjeta de Android Auto junta música y consumo.
 */
class MediaAccessService : NotificationListenerService()

/** Sigue la app de música que esté sonando para copiar su canción y mandarle play/pausa/siguiente. */
class MusicMirror(private val context: Context, private val onChange: () -> Unit) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaAccessService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false

    var controller: MediaController? = null
        private set

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Si esta se para y suena otra app, pasamos a esa.
            refresh()
            onChange()
        }
        override fun onSessionDestroyed() = refresh()
    }

    val title: String? get() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    val artist: String? get() = controller?.metadata?.let {
        it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    }
    val art: Bitmap? get() = controller?.metadata?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }
    val playing: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Engancha (o reengancha) la sesión de música. Sin permiso no hace nada y se reintenta luego. */
    fun refresh() {
        if (!granted(context)) {
            release()
            return
        }
        runCatching {
            if (!listening) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                listening = true
            }
            pick(manager.getActiveSessions(component))
        }
    }

    private fun pick(sessions: List<MediaController>?) {
        val others = sessions.orEmpty().filter { it.packageName != context.packageName }
        val next = others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: others.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, handler)
        onChange()
    }

    fun play() = controller?.transportControls?.play()
    fun pause() = controller?.transportControls?.pause()
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    fun release() {
        controller?.unregisterCallback(callback)
        controller = null
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
    }

    companion object {
        fun granted(context: Context) =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
