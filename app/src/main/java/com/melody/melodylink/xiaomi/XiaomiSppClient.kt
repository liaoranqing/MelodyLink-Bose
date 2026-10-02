package com.melody.melodylink.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/** RFCOMM transport matching the channel probing used by mibudstest. */
class XiaomiSppClient(private val onLog: (String) -> Unit = {}) {
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private val writeLock = Any()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var generation = 0L

    val notifications = incoming.asSharedFlow()
    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            close()
            val request = ++generation
            var lastError: Throwable? = null
            for ((uuid, name) in CHANNELS) {
                for (insecure in listOf(false, true)) {
                    if (request != generation) error("Xiaomi SPP connection cancelled")
                    val label = "$name${if (insecure) " insecure" else ""}"
                    onLog("Xiaomi SPP attempting $label")
                    val candidate = try {
                        if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid)
                        else device.createRfcommSocketToServiceRecord(uuid)
                    } catch (error: Throwable) {
                        lastError = error
                        onLog("Xiaomi SPP socket creation failed for $label: ${error.javaClass.simpleName}")
                        continue
                    }
                    try {
                        connectWithTimeout(candidate)
                        if (request != generation) {
                            candidate.close()
                            error("Xiaomi SPP connection cancelled")
                        }
                        socket = candidate
                        onLog("Xiaomi SPP connected via $label")
                        startReader(candidate, request)
                        return@runCatching Unit
                    } catch (error: Throwable) {
                        lastError = error
                        runCatching { candidate.close() }
                        onLog("Xiaomi SPP failed for $label: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
            throw IllegalStateException("Xiaomi SPP has no usable channel", lastError)
        }
    }

    suspend fun write(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = socket?.takeIf { it.isConnected } ?: error("Xiaomi SPP socket unavailable")
            synchronized(writeLock) {
                active.outputStream.write(data)
                active.outputStream.flush()
            }
        }
    }

    fun close() {
        generation++
        val active = socket
        socket = null
        runCatching { active?.close() }
    }

    private fun connectWithTimeout(candidate: BluetoothSocket) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<Unit> { candidate.connect() }.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw (error.cause ?: error)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun startReader(active: BluetoothSocket, request: Long) {
        Executors.newSingleThreadExecutor().execute {
            val buffer = ByteArray(1024)
            try {
                val input = active.inputStream
                while (request == generation && active.isConnected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) incoming.tryEmit(buffer.copyOf(count))
                }
            } catch (_: IOException) {
                // Closing the socket is the normal cancellation path.
            } finally {
                if (socket === active) socket = null
                runCatching { active.close() }
                if (request == generation) onLog("Xiaomi SPP disconnected")
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        val CHANNELS = listOf(
            UUID.fromString("0000fd2d-0000-1000-8000-00805f9b34fb") to "Xiaomi FD2D",
            UUID.fromString("00001101-0000-1000-8000-008584d01810") to "Xiaomi compatibility SPP",
        )
    }
}
