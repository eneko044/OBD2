package com.eneko.toledoobd.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Observer
import com.eneko.toledoobd.ConnState
import com.eneko.toledoobd.DashState
import com.eneko.toledoobd.R
import com.eneko.toledoobd.data.Settings
import java.util.Locale

/**
 * Experimento para Android Auto: el consumo como "mensaje" en la tarjeta de mensajes que sale
 * encima de la de música (donde aparece WhatsApp). Android Auto solo enseña ahí notificaciones
 * de mensajería, así que la app se hace pasar por una: el contacto se llama "5,4 L/100 · media 6,5",
 * su foto es el número, y al tocar la tarjeta Android Auto lee el consumo en voz alta.
 *
 * Solo publica con el móvil conectado al coche, para no llenar el móvil de notificaciones.
 */
class ConsumoMessenger(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)
    private val art = ConsumoArt(context)
    private val carConnection = CarConnection(context)
    private var inCar = false
    private var lastTitle: String? = null
    private var lastPostAt = 0L
    private var latest: Pair<DashState, Settings>? = null

    private val carObserver = Observer<Int> { type ->
        inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION
        latest?.let { update(it.first, it.second) }
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Consumo en Android Auto", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tarjeta de consumo encima de la música en Android Auto"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        carConnection.type.observeForever(carObserver)
    }

    fun stop() {
        carConnection.type.removeObserver(carObserver)
        cancel()
    }

    /** Se llama con cada estado del panel; publica como mucho cada 3 s y solo si cambia el dato. */
    fun update(s: DashState, st: Settings) {
        latest = s to st
        val connected = s.conn is ConnState.Connected && s.ecuResponding
        if (!st.aaMessage || !inCar || !connected || !canPost()) {
            cancel()
            return
        }
        val moving = s.instL100 != null
        val value = s.instL100 ?: s.instLph
        val avg = s.trip.avgL100
        val media = "media " + (avg?.let(::fmt) ?: "--")
        val title = if (moving) "${fmt(value)} L/100 · $media" else "Parado ${fmt(value)} L/h · $media"
        val now = System.currentTimeMillis()
        if (title == lastTitle || now - lastPostAt < 3000) return
        lastTitle = title
        lastPostAt = now

        val v = st.vehicle
        val avatar = art.avatar(value, moving, v.ecoGood, v.ecoBad)
        val me = Person.Builder().setName("Yo").setKey("me").build()
        val car = Person.Builder()
            .setName(title)
            .setKey("consumo")
            .setIcon(IconCompat.createWithBitmap(avatar))
            .build()
        // Lo que Android Auto lee en voz alta al tocar la tarjeta
        val spoken = buildString {
            append(if (moving) "Consumo ahora ${speak(value)} litros a los cien" else "Parado, ${speak(value)} litros por hora")
            avg?.let { append(". Media ${speak(it)}") }
            append(". ${s.live.speed.toInt()} kilómetros por hora")
        }
        val style = NotificationCompat.MessagingStyle(me)
            .setGroupConversation(false)
            .addMessage(spoken, now, car)

        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(avatar)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(replyAction())
            .addInvisibleAction(markReadAction())
            .build()
        runCatching { nm.notify(ID, n) }
    }

    private fun cancel() {
        if (lastTitle != null) runCatching { nm.cancel(ID) }
        lastTitle = null
    }

    private fun canPost() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Android Auto solo acepta mensajes con "responder" y "marcar como leído"; aquí no hacen nada.
    private fun replyAction(): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, MessageActionReceiver::class.java).setAction("reply"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, "Responder", pi)
            .addRemoteInput(RemoteInput.Builder("reply").setLabel("Responder").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, 2, Intent(context, MessageActionReceiver::class.java).setAction("read"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, "Leído", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun fmt(v: Float) = String.format(Locale.getDefault(), "%.1f", v)
    private fun speak(v: Float) = fmt(v).replace(",", " coma ").replace(".", " coma ")

    private companion object {
        const val CHANNEL = "aa_consumo"
        const val ID = 42
    }
}

/** Recibe "responder" y "leído" desde Android Auto. No hay nada que hacer: el próximo dato vuelve a salir. */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
