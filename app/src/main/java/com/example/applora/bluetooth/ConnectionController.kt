package com.example.applora.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Contrôleur centralisé de la connexion Bluetooth.
 *
 * Gère le cycle de vie complet : AcceptThread (serveur), ConnectThread (client)
 * et DataTransferThread (communication). Inspiré du [ConnectionController] du projet
 * bluetoothchat, adapté au protocole length-prefix de LoRa-app.
 */
class ConnectionController(
    private val adapter: BluetoothAdapter?,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    companion object {
        private const val TAG = "ConnectionController"
        val APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        const val SERVICE_NAME = "LoRaBluetoothChat"
    }

    // ---- État observable ----

    @Volatile
    var connectionState: ConnectionState = ConnectionState.NOT_CONNECTED
        private set

    @Volatile
    var connectionType: ConnectionType? = null
        private set

    var connectedDeviceName: String? = null
        private set
    var connectedDeviceAddress: String? = null
        private set

    private var lastDeviceAddress: String? = null
    private var lastDeviceName: String? = null
    val lastAddress: String? get() = lastDeviceAddress
    val lastName: String? get() = lastDeviceName

    // ---- Listeners ----

    var connectionListener: OnConnectionListener? = null
    var messageListener: OnMessageListener? = null
    var onStateChanged: ((ConnectionState) -> Unit)? = null

    /**
     * Callback invoqué quand le texte de la notification foreground doit changer.
     * Le [BluetoothConnectionService] l'utilise pour mettre à jour la notification.
     */
    var onForegroundMessage: ((String) -> Unit)? = null

    // ---- Threads ----

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var dataTransferThread: DataTransferThread? = null

    private val writeExecutor = Executors.newSingleThreadExecutor()

    // ---- API publique ----

    /**
     * Passe en mode écoute (serveur). Annule les connexions en cours,
     * puis démarre un [AcceptThread].
     */
    @Synchronized
    fun prepareForAccept() {
        Log.d(TAG, "prepareForAccept()")
        cancelConnections()
        acceptThread = AcceptThread()
        acceptThread?.start()
        setState(ConnectionState.LISTENING)
        handler.post { connectionListener?.onListening() }
    }

    /**
     * Initie une connexion vers l'appareil distant (mode client).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect() -> ${device.address}")

        if (connectionState == ConnectionState.CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        cancelDataTransfer()
        cancelAccept()

        connectThread = ConnectThread(device)
        connectThread?.start()

        setState(ConnectionState.CONNECTING)
        handler.post { connectionListener?.onConnecting() }
    }

    /**
     * Déconnexion propre : ferme les threads, conserve la dernière adresse
     * pour une reconnexion ultérieure.
     */
    @Synchronized
    fun disconnect() {
        Log.d(TAG, "disconnect()")
        saveLastDevice()
        cancelConnections()
        cancelAccept()
        connectedDeviceName = null
        connectedDeviceAddress = null
        setState(ConnectionState.NOT_CONNECTED)
        handler.post { connectionListener?.onDisconnected() }
    }

    /**
     * Arrêt complet : efface aussi les informations du dernier appareil.
     */
    @Synchronized
    fun stop() {
        Log.d(TAG, "stop()")
        cancelConnections()
        cancelAccept()
        connectedDeviceName = null
        connectedDeviceAddress = null
        lastDeviceAddress = null
        lastDeviceName = null
        setState(ConnectionState.NOT_CONNECTED)
    }

    /**
     * Tente une reconnexion au dernier appareil connu.
     * @return true si une tentative a été lancée.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun reconnect(): Boolean {
        val address = lastDeviceAddress ?: return false
        val device = adapter?.bondedDevices?.find { it.address == address } ?: return false
        connect(device)
        return true
    }

    /**
     * Envoie un message texte à l'appareil connecté.
     * L'envoi est effectué en arrière-plan via un [writeExecutor].
     */
    fun sendMessage(message: String) {
        val thread: DataTransferThread?
        synchronized(this) {
            if (connectionState != ConnectionState.CONNECTED) return
            thread = dataTransferThread
        }
        writeExecutor.execute {
            thread?.write(message)
        }
    }

    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED

    // ---- Helpers internes ----

    private fun setState(state: ConnectionState) {
        Log.d(TAG, "setState: $connectionState -> $state")
        connectionState = state
        handler.post { onStateChanged?.invoke(state) }
    }

    private fun cancelConnections() {
        connectThread?.cancel()
        connectThread = null
        cancelDataTransfer()
    }

    private fun cancelDataTransfer() {
        dataTransferThread?.cancel(silent = true)
        dataTransferThread = null
    }

    private fun cancelAccept() {
        acceptThread?.cancel()
        acceptThread = null
    }

    private fun saveLastDevice() {
        if (connectedDeviceAddress != null) {
            lastDeviceAddress = connectedDeviceAddress
            lastDeviceName = connectedDeviceName
        }
    }

    /**
     * Appelé lorsqu'une socket est prête (côté client ou serveur).
     * Annule les threads précédents et démarre le [DataTransferThread].
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun connected(socket: BluetoothSocket, type: ConnectionType) {
        Log.d(TAG, "connected() type=$type")
        cancelConnections()
        cancelAccept()

        connectionType = type
        val device = socket.remoteDevice
        connectedDeviceName = try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: "Appareil inconnu"
        connectedDeviceAddress = device.address

        val transferListener = object : DataTransferThread.EventsListener {
            override fun onConnectionPrepared() {}

            override fun onConnectionLost() {
                this@ConnectionController.connectionLost()
            }

            override fun onMessageReceived(message: String) {
                handler.post {
                    messageListener?.onMessageReceived(
                        message, connectedDeviceName, connectedDeviceAddress
                    )
                }
            }

            override fun onMessageSent(message: String) {
                handler.post { messageListener?.onMessageSent(message) }
            }

            override fun onMessageSendingFailed() {
                handler.post { messageListener?.onMessageSendingFailed() }
            }
        }

        dataTransferThread = DataTransferThread(socket, transferListener).also {
            it.prepare()
            it.start()
        }

        setState(ConnectionState.CONNECTED)
        handler.post { connectionListener?.onConnected(device, type) }
        onForegroundMessage?.invoke("Connecté à $connectedDeviceName")
    }

    private fun connectionFailed() {
        Log.d(TAG, "connectionFailed()")
        saveLastDevice()
        connectedDeviceName = null
        connectedDeviceAddress = null
        setState(ConnectionState.NOT_CONNECTED)
        handler.post { connectionListener?.onConnectionFailed() }
        onForegroundMessage?.invoke("Échec de connexion")
    }

    private fun connectionLost() {
        Log.d(TAG, "connectionLost()")
        saveLastDevice()
        connectedDeviceName = null
        connectedDeviceAddress = null
        setState(ConnectionState.NOT_CONNECTED)
        handler.post { connectionListener?.onConnectionLost() }
        onForegroundMessage?.invoke("Connexion perdue")
    }

    @SuppressLint("MissingPermission")
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            device.createRfcommSocketToServiceRecord(APP_UUID)
        } catch (e: IOException) {
            Log.w(TAG, "createRfcommSocketToServiceRecord failed, trying fallback", e)
            try {
                val method: Method = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback createRfcommSocket(1) failed", e2)
                null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on createRfcomm", e)
            null
        }
    }

    // ================================================================
    //  AcceptThread — écoute des connexions entrantes (mode serveur)
    // ================================================================

    @SuppressLint("MissingPermission")
    private inner class AcceptThread : Thread() {

        private val serverSocket: BluetoothServerSocket? = try {
            adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
        } catch (e: Exception) {
            Log.e(TAG, "AcceptThread: listen() failed", e)
            null
        }

        override fun run() {
            name = "AcceptThread"
            Log.d(TAG, "AcceptThread: BEGIN")

            while (connectionState != ConnectionState.CONNECTED) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    synchronized(this@ConnectionController) {
                        when (connectionState) {
                            ConnectionState.LISTENING -> {
                                connected(socket, ConnectionType.INCOMING)
                            }
                            ConnectionState.CONNECTING -> {
                                connectThread?.cancel()
                                connectThread = null
                                connected(socket, ConnectionType.INCOMING)
                            }
                            else -> {
                                try {
                                    socket.close()
                                } catch (_: IOException) { }
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "AcceptThread: accept() failed", e)
                    break
                }
            }
            Log.d(TAG, "AcceptThread: END")
        }

        fun cancel() {
            Log.d(TAG, "AcceptThread: cancel()")
            try {
                serverSocket?.close()
            } catch (_: IOException) { }
        }
    }

    // ================================================================
    //  ConnectThread — connexion sortante (mode client)
    // ================================================================

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {

        private val socket: BluetoothSocket? = createRfcommSocket(device)

        override fun run() {
            name = "ConnectThread"
            Log.d(TAG, "ConnectThread: BEGIN")

            try {
                adapter?.cancelDiscovery()
            } catch (_: SecurityException) { }

            try {
                socket?.connect()
            } catch (e: Exception) {
                Log.e(TAG, "ConnectThread: connect() failed", e)
                try {
                    socket?.close()
                } catch (_: IOException) { }
                synchronized(this@ConnectionController) {
                    if (connectionState != ConnectionState.CONNECTED) {
                        connectionFailed()
                    } else {
                        connectThread = null
                    }
                }
                return
            }

            synchronized(this@ConnectionController) {
                if (connectionState == ConnectionState.CONNECTED) {
                    try {
                        socket?.close()
                    } catch (_: IOException) { }
                    connectThread = null
                } else {
                    connectThread = null
                    socket?.let { connected(it, ConnectionType.OUTGOING) }
                }
            }
            Log.d(TAG, "ConnectThread: END")
        }

        fun cancel() {
            Log.d(TAG, "ConnectThread: cancel()")
            try {
                socket?.close()
            } catch (_: IOException) { }
        }
    }
}
