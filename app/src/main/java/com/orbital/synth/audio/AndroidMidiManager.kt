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
import java.util.concurrent.atomic.AtomicLong

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
    // A device can disappear while openDevice() is still in flight. Do not
    // attach its late callback to an already detached physical controller.
    private val detached = mutableSetOf<Int>()

    // No two port connections share an ID, even across detach/reconnect or
    // distinct AndroidMidiManager instances in the same process.
    private companion object {
        val nextPortSourceId = AtomicLong(1L)
    }

    @Volatile private var started = false
    @Volatile private var disposed = false

    val isRunning: Boolean get() = started
    val isAvailable: Boolean get() = manager != null

    private var generation = 0L
    private var registeredCallback: MidiManager.DeviceCallback? = null

    /** Registers for hot-plug events and connects available MIDI device output ports. */
    fun start(): Boolean {
        val midi = manager ?: return false
        val session: Long
        val callbackForSession: MidiManager.DeviceCallback
        synchronized(lock) {
            if (disposed) return false
            if (started) return true
            session = ++generation
            started = true
            callbackForSession = object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(device: MidiDeviceInfo) {
                    synchronized(lock) { if (generation == session) detached.remove(device.id) }
                    openIfNeeded(device, session)
                }

                override fun onDeviceRemoved(device: MidiDeviceInfo) {
                    // Check the session and detach under one lock. Otherwise
                    // a stale callback could disconnect a *new* start session.
                    val previous = synchronized(lock) {
                        if (!started || generation != session) null else {
                            detached.add(device.id)
                            opening.remove(device.id)
                            connections.remove(device.id)
                        }
                    }
                    previous?.close()
                }
            }
            registeredCallback = callbackForSession
            try {
                midi.registerDeviceCallback(callbackForSession, handler)
            } catch (failure: Exception) {
                started = false
                registeredCallback = null
                generation++
                Log.w("OrbitalMidi", "MIDI callback registration unavailable", failure)
                return false
            }
        }
        try {
            midi.devices.forEach { openIfNeeded(it, session) }
        } catch (failure: Exception) {
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
        val oldCallback: MidiManager.DeviceCallback?
        synchronized(lock) {
            started = false
            generation++ // invalidate in-flight openDevice callbacks from this session
            opening.clear()
            detached.clear()
            oldConnections = connections.values.toList()
            connections.clear()
            oldCallback = registeredCallback
            registeredCallback = null
        }
        if (midi != null && oldCallback != null) {
            runCatching { midi.unregisterDeviceCallback(oldCallback) }
        }
        oldConnections.forEach { it.close() }
    }

    override fun close() {
        synchronized(lock) { disposed = true }
        stop()
    }

    private fun openIfNeeded(info: MidiDeviceInfo, session: Long) {
        val midi = manager ?: return
        if (info.outputPortCount <= 0) return
        synchronized(lock) {
            if (!started || generation != session || info.id in detached ||
                info.id in opening || info.id in connections) return
            opening.add(info.id)
        }
        try {
            midi.openDevice(info, { device -> onDeviceOpened(info, device, session) }, handler)
        } catch (failure: Exception) {
            synchronized(lock) { if (generation == session) opening.remove(info.id) }
            Log.w("OrbitalMidi", "Could not open MIDI device", failure)
        }
    }

    private fun onDeviceOpened(info: MidiDeviceInfo, device: MidiDevice?, session: Long) {
        val shouldConnect = synchronized(lock) {
            if (generation == session) opening.remove(info.id)
            started && generation == session && info.id !in connections && info.id !in detached
        }
        if (!shouldConnect || device == null) {
            runCatching { device?.close() }
            return
        }

        val ports = mutableListOf<PortConnection>()
        for (portNumber in 0 until info.outputPortCount) {
            val port = try {
                device.openOutputPort(portNumber)
            } catch (failure: Exception) {
                Log.w("OrbitalMidi", "Cannot open MIDI output port", failure)
                null
            } ?: continue

            val gate = MidiPortGate()
            val sourceId = nextPortSourceId.getAndIncrement()
            val portSession = MidiPortSession(MidiSourceRouter(sourceId, listener))
            val receiver = object : MidiReceiver() {
                override fun onSend(message: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    // A rejected device, pending attach or detached port must
                    // never synthesize notes through a stale callback.
                    if (!gate.acceptsInput) return
                    try {
                        portSession.feed(message, offset, count)
                    } catch (failure: Exception) {
                        Log.w("OrbitalMidi", "Rejected invalid MIDI callback data", failure)
                    }
                }
            }
            try {
                port.connect(receiver)
                ports.add(PortConnection(port, receiver, portSession, gate))
            } catch (failure: Exception) {
                Log.w("OrbitalMidi", "Could not attach MIDI receiver", failure)
                portSession.close()
                runCatching { port.close() }
            }
        }

        val connection = DeviceConnection(device, ports)
        val accepted = synchronized(lock) {
            if (started && generation == session && info.id !in detached &&
                info.id !in connections && ports.isNotEmpty()) {
                connections[info.id] = connection
                true
            } else false
        }
        if (accepted) ports.forEach { it.activate() } else connection.close()
    }

    private class PortConnection(
        val port: MidiOutputPort,
        val receiver: MidiReceiver,
        val session: MidiPortSession,
        val gate: MidiPortGate
    ) {
        fun activate() { gate.activate() }

        fun close() {
            gate.close() // reject racing and future callbacks before disconnect
            runCatching { port.disconnect(receiver) }
            // Release only pitches held by this port, including unplug while
            // a key remains down, before the device is finally released.
            runCatching { session.close() }
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
