package com.example.applora.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Thread dédié aux transferts de données sur une socket Bluetooth RFCOMM.
 *
 * Protocole : chaque message est précédé d'un en-tête de 4 octets (big-endian)
 * indiquant la longueur du corps UTF-8. Cela garantit le respect des frontières
 * de messages même lorsque les lectures TCP/RFCOMM sont fragmentées.
 *
 * Inspiré de [DataTransferThread] du projet bluetoothchat, avec un protocole
 * length-prefix plus robuste.
 */
class DataTransferThread(
    private val socket: BluetoothSocket,
    private val listener: EventsListener
) : Thread() {

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var isPrepared = false

    @Volatile
    private var skipEvents = false

    companion object {
        private const val TAG = "DataTransferThread"
        private const val MAX_MESSAGE_LENGTH = 64 * 1024
        private const val READ_BUFFER_SIZE = 1024
    }

    fun prepare() {
        try {
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            isPrepared = true
            listener.onConnectionPrepared()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to obtain streams", e)
        }
    }

    override fun start() {
        require(isPrepared) { "Call prepare() before start()" }
        super.start()
    }

    override fun run() {
        name = "DataTransferThread"
        Log.d(TAG, "BEGIN")

        val buffer = ByteArray(READ_BUFFER_SIZE)
        val accumulated = ByteArrayOutputStream()

        while (!isInterrupted) {
            try {
                val bytesRead = inputStream?.read(buffer) ?: -1

                if (bytesRead == -1) {
                    Log.d(TAG, "End of stream")
                    if (!skipEvents) listener.onConnectionLost()
                    break
                }

                if (bytesRead > 0) {
                    accumulated.write(buffer, 0, bytesRead)
                    val data = accumulated.toByteArray()
                    var pos = 0

                    while (pos + 4 <= data.size) {
                        val len = ((data[pos].toInt() and 0xFF) shl 24) or
                                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                                (data[pos + 3].toInt() and 0xFF)

                        if (len < 0 || len > MAX_MESSAGE_LENGTH) {
                            Log.w(TAG, "Invalid length prefix $len – discarding buffer")
                            accumulated.reset()
                            pos = data.size
                            break
                        }
                        if (pos + 4 + len > data.size) break

                        val message = String(data, pos + 4, len, StandardCharsets.UTF_8)
                        listener.onMessageReceived(message)
                        pos += 4 + len
                    }

                    accumulated.reset()
                    if (pos < data.size) {
                        accumulated.write(data, pos, data.size - pos)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Disconnected", e)
                if (!skipEvents) listener.onConnectionLost()
                break
            }
        }
        Log.d(TAG, "END")
    }

    /**
     * Écrit un message sur la socket en le préfixant par sa longueur (4 octets big-endian).
     * Peut être appelé depuis n'importe quel thread ; les écritures concurrentes sont
     * sérialisées par le [ConnectionController] via son writeExecutor.
     */
    fun write(message: String): Boolean {
        val payload = message.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_MESSAGE_LENGTH) {
            listener.onMessageSendingFailed()
            return false
        }

        val len = payload.size
        val packet = ByteArray(4 + len).apply {
            this[0] = (len shr 24 and 0xFF).toByte()
            this[1] = (len shr 16 and 0xFF).toByte()
            this[2] = (len shr 8 and 0xFF).toByte()
            this[3] = (len and 0xFF).toByte()
            System.arraycopy(payload, 0, this, 4, len)
        }

        return try {
            outputStream?.write(packet)
            outputStream?.flush()
            listener.onMessageSent(message)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write failed", e)
            listener.onMessageSendingFailed()
            false
        }
    }

    fun cancel(silent: Boolean = false) {
        skipEvents = silent
        isPrepared = false
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "close() failed", e)
        }
    }

    interface EventsListener {
        fun onConnectionPrepared()
        fun onConnectionLost()
        fun onMessageReceived(message: String)
        fun onMessageSent(message: String)
        fun onMessageSendingFailed()
    }
}
