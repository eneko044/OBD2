package com.eneko.toledoobd.overlay

import com.eneko.toledoobd.DashState
import com.eneko.toledoobd.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** Último estado del panel, para que la ventana superpuesta lo pinte con la app en segundo plano. */
object OverlayBus {
    val data = MutableStateFlow<Pair<DashState, Settings>?>(null)
}
