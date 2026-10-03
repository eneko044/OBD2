package com.eneko.toledoobd.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.eneko.toledoobd.MainActivity
import com.eneko.toledoobd.R
import com.eneko.toledoobd.data.overlaySizeDp
import com.eneko.toledoobd.ui.PipView
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.ToledoTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ventana pequeña "sobre otras apps" con el consumo. Se muestra al salir de la app y se quita al
 * volver. Se puede arrastrar (recuerda la posición) y al tocarla abre el panel.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val prefs by lazy { getSharedPreferences("toledo", Context.MODE_PRIVATE) }

    override fun onCreate() {
        savedState.performRestore(null)
        super.onCreate()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("overlayX", (12 * density).toInt())
            y = prefs.getInt("overlayY", (90 * density).toInt())
        }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                val d by OverlayBus.data.collectAsStateWithLifecycle()
                val (s, st) = d ?: return@setContent
                ToledoTheme {
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.fillMaxSize().clip(shape).border(1.dp, Dash.Stroke, shape)) {
                        PipView(s, st)
                    }
                }
            }
        }
        attachDrag(v)
        view = v
        runCatching { wm.addView(v, params) }.onFailure { stopSelf() }

        // Ajusta el tamaño cuando cambian el modo o la escala.
        lifecycleScope.launch {
            OverlayBus.data.collect { d ->
                val st = d?.second ?: return@collect
                val (wDp, hDp) = overlaySizeDp(st.floatMode, st.overlayScale)
                val w = (wDp * density).roundToInt()
                val h = (hDp * density).roundToInt()
                if (w != params.width || h != params.height) {
                    params.width = w
                    params.height = h
                    view?.let { runCatching { wm.updateViewLayout(it, params) } }
                }
            }
        }
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ventana de consumo", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, openAppIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Consumo en pantalla")
            .setContentText("Toca para volver al panel")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        return runCatching { ServiceCompat.startForeground(this, 1, n, type) }.isSuccess
    }

    private fun openAppIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: ComposeView) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt("overlayX", params.x).putInt("overlayY", params.y).apply()
                    } else {
                        startActivity(openAppIntent())
                    }
                }
            }
            true
        }
    }

    override fun onDestroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "overlay"

        fun start(ctx: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
