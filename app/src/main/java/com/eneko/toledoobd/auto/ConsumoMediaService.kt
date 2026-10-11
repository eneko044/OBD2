package com.eneko.toledoobd.auto

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.eneko.toledoobd.ConnState
import com.eneko.toledoobd.MainActivity
import com.eneko.toledoobd.overlay.OverlayBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android Auto no deja a otras apps pintar encima de Google Maps. Lo que sí deja es que una app
 * de música ponga título, subtítulo y carátula en la tarjeta de "lo que suena" que sale junto al
 * mapa. Este servicio se hace pasar por fuente multimedia y en esa tarjeta pone el consumo.
 * Los datos los lee del panel (la app tiene que estar abierta en el móvil con el OBD conectado).
 *
 * Si se da acceso a las notificaciones, además copia la canción de Spotify (o la app que suene)
 * y le pasa play/pausa/siguiente: así en la misma tarjeta salen la música y el consumo.
 */
class ConsumoMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var art: ConsumoArt
    private lateinit var music: MusicMirror
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Play/pausa propio, para cuando no hay música que copiar. */
    private var ownPlaying = true
    private var shownState: String? = null
    private var lastKey: String? = null
    private var lastArt: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        art = ConsumoArt(this)
        music = MusicMirror(this) { publish() }
        session = MediaSessionCompat(this, "ToledoConsumo").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (music.controller != null) {
                        music.play()
                        return
                    }
                    setPlaying(true)
                    // Si aún no hay OBD conectado, abre la app en el móvil para que se conecte sola.
                    if (OverlayBus.data.value?.first?.conn !is ConnState.Connected) openApp()
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = onPlay()
                override fun onPause() {
                    if (music.controller != null) music.pause() else setPlaying(false)
                }
                override fun onStop() = onPause()
                override fun onSkipToNext() { music.next() }
                override fun onSkipToPrevious() { music.previous() }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        setPlaying(true)

        // Una vez por segundo basta: el ELM327 por línea K no da más de 2 lecturas por segundo.
        scope.launch {
            var tick = 0
            while (isActive) {
                // El permiso de música se puede dar con el servicio ya en marcha: se mira cada 5 s.
                if (tick++ % 5 == 0) music.refresh()
                publish()
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        music.release()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT) {
            result.sendResult(mutableListOf())
            return
        }
        val item = MediaDescriptionCompat.Builder()
            .setMediaId(ITEM)
            .setTitle("Consumo en directo")
            .setSubtitle("L/100 km del OBD")
            .setIconBitmap(lastArt)
            .build()
        result.sendResult(mutableListOf(MediaBrowserCompat.MediaItem(item, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)))
    }

    private fun setPlaying(on: Boolean) {
        ownPlaying = on
        applyState()
    }

    /** Estado de la tarjeta: el de la música si la hay, si no el propio. */
    private fun applyState() {
        val hasMusic = music.controller != null
        val on = if (hasMusic) music.playing else ownPlaying
        val key = "$on|$hasMusic"
        if (key == shownState) return
        shownState = key
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        if (hasMusic) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (on) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )
    }

    /** Pasa el último estado del panel a la tarjeta de Android Auto (solo si ha cambiado algo). */
    private fun publish() {
        val data = OverlayBus.data.value
        val s = data?.first
        val v = data?.second?.vehicle
        val connected = s != null && s.conn is ConnState.Connected && s.ecuResponding

        var title: String
        var subtitle: String
        val value: Float?
        val unit: String
        val moving = s?.instL100 != null
        val avg = s?.trip?.avgL100
        if (!connected || v == null) {
            title = "Sin conexión OBD"
            subtitle = if (s == null) "Abre Toledo OBD en el móvil y conecta" else "Conecta el OBD en el móvil"
            value = null
            unit = ""
        } else {
            // El consumo de ahora ya va en grande en la imagen: el texto lleva la media y lo demás.
            value = if (moving) s!!.instL100 else s!!.instLph
            unit = if (moving) "L/100" else "L/h"
            title = "Media " + (avg?.let(ConsumoArt::fmt) ?: "--") + " L/100 · ${s.live.speed.toInt()} km/h"
            subtitle = buildList {
                s.gear?.let { add("${it}ª marcha") }
                if (s.trip.distanceKm >= 0.1) add(String.format(java.util.Locale.getDefault(), "%.1f km", s.trip.distanceKm))
                if (s.trip.fuelL >= 0.01) add(String.format(java.util.Locale.getDefault(), "%.2f L", s.trip.fuelL))
            }.joinToString(" · ").ifEmpty { v.shortTitle }
        }

        // Con música: debajo va la canción (sin emojis, que la pantalla del coche pinta como "?").
        val song = music.controller?.let { plain(listOfNotNull(music.title, music.artist).joinToString(" — ")) }
        if (!song.isNullOrBlank()) {
            if (!connected) title = "Sin OBD · abre Toledo OBD en el móvil"
            subtitle = song
        }
        applyState()

        val key = "$title|$subtitle|${value?.let(ConsumoArt::fmt)}|$moving"
        if (key == lastKey) return
        lastKey = key

        val bmp = art.draw(
            value = value,
            unit = unit,
            average = avg,
            moving = moving,
            ecoGood = v?.ecoGood ?: 5.5f,
            ecoBad = v?.ecoBad ?: 9f,
        )
        lastArt = bmp
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ITEM)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, v?.shortTitle ?: "Toledo OBD")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bmp)
                .build(),
        )
    }

    /** Quita emojis y símbolos que la pantalla de Android Auto no sabe pintar. */
    private fun plain(t: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val type = Character.getType(cp)
            val skip = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
            if (!skip) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("\\s+"), " ").replace(Regex("\\(\\s+"), "(").trim()
    }

    private fun openApp() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val ROOT = "root"
        const val ITEM = "consumo"
    }
}
