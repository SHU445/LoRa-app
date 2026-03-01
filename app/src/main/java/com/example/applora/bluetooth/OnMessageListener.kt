package com.example.applora.bluetooth

interface OnMessageListener {
    fun onMessageReceived(message: String, deviceName: String?, deviceAddress: String?)
    fun onMessageSent(message: String)
    fun onMessageSendingFailed()
}
