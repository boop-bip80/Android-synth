package com.orbital.synth.audio

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/**
 * Optional Android MIDI discovery and input. Android's device *output* ports are
 * opened, because they send messages to this synth's receiver.
 *
 * Each device-port connection owns its own running-status decoder. A callback
 * arriving after stop() is closed without reviving an inactive session.
 */
class AndroidMidiManager(
    context: Context,
    private val listener: MidiMessageListener
) : Closeable {
    private val manager = context.applicationContext.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val connections = mutableMapOf<Int, DeviceConnection>()
    private val opening = mutableSetOf<Int>()

    @Volatile private var started = false
    @Volatile private var disposed = false

    val isRunning: Boolean get() = started
    val isAvailable: Boolean get() = manager != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            openIfNeeded(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            disconnect(device.id)
        }
    }

    /** Registers for hot-plug events and connects available MIDI device output ports. */
    fun start(): Boolean {
        val midi = manager ?: return false
        synchronized(lock) {
            if (disposed) return false
            if (started) return true
            started = true
        }
        try {
            midi.registerDeviceCallback(callback, handler)
            midi.devices.forEach { openIfNeeded(it) }
        } catch (failure: RuntimeException) {
            Log.w("OrbitalMidi", "MIDI enumeration unavailable", failure)
            stop()
            return false
        }
        return true
    }

    /** Unregisters notifications and safely closes all existing ports/devices. */
    fun stop() {
        val midi = manager
        val oldConnections: List<DeviceConnection>
        synchronized(lock) {
            started = false
            opening.clear()
            oldConnections = connections.values.toList()
            connections.clear()
        }
        if (midi != null) runCatching { midi.unregisterDeviceCallback(callback) }
        oldConnections.forEach { it.close() }
    }

    override fun close() {
        synchronized(lock) { disposed = true }
        stop()
    }

    private fun openIfNeeded(info: MidiDeviceInfo) {
        val midi = manager ?: return
        if (info.outputPortCount <= 0) return
        synchronized(lock) {
            if (!started || info.id in opening || info.id in connections) return
            opening.add(info.id)
        }
        try {
            midi.openDevice(info, { device -> onDeviceOpened(info, device) }, handler)
        } catch (failure: RuntimeException) {
            synchronized(lock) { opening.remove(info.id) }
            Log.w("OrbitalMidi", "Could not open MIDI device", failure)
        }
    }

    private fun onDeviceOpened(info: MidiDeviceInfo, device: MidiDevice?) {
        val shouldConnect = synchronized(lock) {
            opening.remove(info.id)
            started && info.id !in connections
        }
        if (!shouldConnect || device == null) {
            runCatching { device?.close() }
            return
        }

        val ports = mutableListOf<PortConnection>()
        for (portNumber in 0 until info.outputPortCount) {
            val port = try {
                device.openOutputPort(portNumber)
            } catch (failure: RuntimeException) {
                Log.w("OrbitalMidi", "Cannot open MIDI output port", failure)
                null
            } ?: continue

            val decoder = MidiStreamDecoder(listener)
            val receiver = object : MidiReceiver() {
                override fun onSend(message: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    try {
                        decoder.feed(message, offset, count)
                    } catch (failure: RuntimeException) {
                        Log.w("OrbitalMidi", "Rejected invalid MIDI callback data", failure)
                    }
                }
            }
            try {
                port.connect(receiver)
                ports.add(PortConnection(port, receiver))
            } catch (failure: RuntimeException) {
                Log.w("OrbitalMidi", "Could not attach MIDI receiver", failure)
                runCatching { port.close() }
            }
        }

        val connection = DeviceConnection(device, ports)
        val accepted = synchronized(lock) {
            if (started && info.id !in connections && ports.isNotEmpty()) {
                connections[info.id] = connection
                true
            } else false
        }
        if (!accepted) connection.close()
    }

    private fun disconnect(id: Int) {
        val previous = synchronized(lock) {
            opening.remove(id)
            connections.remove(id)
        }
        previous?.close()
    }

    private class PortConnection(val port: MidiOutputPort, val receiver: MidiReceiver) {
        fun close() {
            runCatching { port.disconnect(receiver) }
            runCatching { port.close() }
        }
    }

    private class DeviceConnection(
        val device: MidiDevice,
        val ports: List<PortConnection>
    ) {
        fun close() {
            ports.forEach { it.close() }
            runCatching { device.close() }
        }
    }
}
