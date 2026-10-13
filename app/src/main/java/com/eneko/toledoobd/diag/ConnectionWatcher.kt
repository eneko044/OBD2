package com.eneko.toledoobd.diag

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

/**
 * Apunta en [EventLog] todo lo que puede tirar Android Auto inalámbrico: el Bluetooth del móvil
 * (si se reinicia entero caen a la vez la pantalla y el OBD), cada aparato que se conecta o
 * desconecta, el Wi-Fi (Android Auto sin cable va por Wi-Fi), la conexión con Android Auto y la carga.
 */
class ConnectionWatcher(private val context: Context) {

    private val carConnection = CarConnection(context)
    private var lastCar: Int? = null
    private var started = false

    private val carObserver = Observer<Int> { type ->
        if (type == lastCar) return@Observer
        lastCar = type
        EventLog.add(
            "AUTO",
            when (type) {
                CarConnection.CONNECTION_TYPE_PROJECTION -> "Android Auto CONECTADO"
                CarConnection.CONNECTION_TYPE_NATIVE -> "Coche con Android Automotive"
                else -> "Android Auto DESCONECTADO"
            },
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> EventLog.add(
                    "BT", "Bluetooth del móvil: " + adapterState(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)),
                )
                BluetoothDevice.ACTION_ACL_CONNECTED -> EventLog.add("BT", "Enlace CONECTADO con ${device(i)}")
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> EventLog.add("BT", "Pide desconectarse: ${device(i)}")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> EventLog.add("BT", "Enlace PERDIDO con ${device(i)}")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> EventLog.add(
                    "BT", "Emparejamiento ${device(i)}: ${i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)}",
                )
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> EventLog.add(
                    "BT", "Audio (A2DP) ${device(i)}: " + profileState(i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                )
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> EventLog.add(
                    "BT", "Llamadas (HFP) ${device(i)}: " + profileState(i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                )
                WifiManager.WIFI_STATE_CHANGED_ACTION -> EventLog.add(
                    "WIFI", "Wi-Fi del móvil: " + when (i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)) {
                        WifiManager.WIFI_STATE_DISABLING -> "apagándose"
                        WifiManager.WIFI_STATE_DISABLED -> "APAGADO"
                        WifiManager.WIFI_STATE_ENABLING -> "encendiéndose"
                        WifiManager.WIFI_STATE_ENABLED -> "encendido"
                        else -> "desconocido"
                    },
                )
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> EventLog.add("WIFI", "Cambio en Wi-Fi Direct")
                Intent.ACTION_POWER_CONNECTED -> EventLog.add("CARGA", "Cargador CONECTADO")
                Intent.ACTION_POWER_DISCONNECTED -> EventLog.add("CARGA", "Cargador DESCONECTADO")
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = EventLog.add("WIFI", "Red Wi-Fi disponible ($network)")
        override fun onLost(network: Network) = EventLog.add("WIFI", "Red Wi-Fi PERDIDA ($network)")
    }

    fun start() {
        if (started) return
        started = true
        EventLog.add(
            "INFO",
            "Vigilando conexiones · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
                "Android Auto ${packageVersion("com.google.android.projection.gearhead")}",
        )
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback,
            )
        }
        carConnection.type.observeForever(carObserver)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        carConnection.type.removeObserver(carObserver)
    }

    private fun device(i: Intent): String {
        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (d == null) return "?"
        val name = try { d.name } catch (e: SecurityException) { null }
        return "\"${name ?: "sin nombre"}\" (${d.address})"
    }

    private fun adapterState(s: Int) = when (s) {
        BluetoothAdapter.STATE_TURNING_OFF -> "APAGÁNDOSE"
        BluetoothAdapter.STATE_OFF -> "APAGADO"
        BluetoothAdapter.STATE_TURNING_ON -> "encendiéndose"
        BluetoothAdapter.STATE_ON -> "encendido"
        else -> "estado $s"
    }

    private fun profileState(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "conectado"
        BluetoothProfile.STATE_CONNECTING -> "conectando"
        BluetoothProfile.STATE_DISCONNECTING -> "desconectando"
        BluetoothProfile.STATE_DISCONNECTED -> "DESCONECTADO"
        else -> "estado $s"
    }

    private fun packageVersion(pkg: String) = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull() ?: "no visible"
}
