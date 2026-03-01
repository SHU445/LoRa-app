package com.example.applora.bluetooth

import android.bluetooth.BluetoothDevice

interface OnConnectionListener {
    fun onConnecting()
    fun onConnected(device: BluetoothDevice, type: ConnectionType)
    fun onConnectionFailed()
    fun onConnectionLost()
    fun onDisconnected()
    fun onListening()
}
