package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiSourcePitchBendOwnershipTest {
    private class Harness {
        val applied = mutableListOf<Int>()
        val notes = mutableListOf<String>()
        private val owner = MidiSourcePitchBendOwnership { applied += it }
        private val listener = object : SourceAwareMidiMessageListener {
            override fun onSourceNoteOn(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                notes += "on:" + sourceId + ":" + channel + ":" + note
            }

            override fun onSourceNoteOff(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                notes += "off:" + sourceId + ":" + channel + ":" + note
            }

            override fun onSourcePitchBend(sourceId: Long, channel: Int, value: Int) {
                owner.onBend(sourceId, channel, value)
            }

            override fun onSourceControlChange(sourceId: Long, channel: Int, controller: Int, value: Int) {
                if (controller == 121) owner.onControllerReset(sourceId, channel)
                onControlChange(channel, controller, value)
            }

            override fun onSourceDisconnected(sourceId: Long) {
                owner.onDisconnect(sourceId)
            }
        }

        fun port(id: Long): MidiPortSession = MidiPortSession(MidiSourceRouter(id, listener))
    }

    private fun bend(port: MidiPortSession, channel: Int, value: Int) {
        require(channel in 0..15 && value in 0..16_383)
        port.feed(byteArrayOf(
            (0xE0 + channel).toByte(),
            (value and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
        ))
    }

    @Test
    fun disconnectOfCurrentControllerRestoresLastSurvivingChannelBendAndItsNote() {
        val h = Harness()
        val usb = h.port(101L)
        val bluetooth = h.port(102L)
        bluetooth.feed(byteArrayOf(0x90.toByte(), 60, 100))
        bend(usb, 0, 14_000)
        bend(bluetooth, 1, 6_000)
        bend(usb, 2, 11_000) // Last writer wins GLOBAL synth expression.
        assertEquals(listOf(14_000, 6_000, 11_000), h.applied)

        usb.close()
        // A's last expression must disappear, but B's note and expression survive.
        assertEquals(listOf(14_000, 6_000, 11_000, 6_000), h.applied)
        assertEquals(listOf("on:102:0:60"), h.notes)
        bluetooth.feed(byteArrayOf(0x80.toByte(), 60, 0))
        assertEquals(listOf("on:102:0:60", "off:102:0:60"), h.notes)
        bluetooth.close()
        assertEquals(8_192, h.applied.last())
    }

    @Test
    fun removingNonwinningPortCannotResetTheOtherControllersBend() {
        val h = Harness()
        val first = h.port(201L)
        val second = h.port(202L)
        bend(first, 0, 3_000)
        bend(second, 0, 12_000)
        first.close()
        first.close() // Idempotent disconnect notification.
        assertEquals(listOf(3_000, 12_000), h.applied)
        bend(second, 0, 9_000)
        second.close()
        assertEquals(listOf(3_000, 12_000, 9_000, 8_192), h.applied)
    }

    @Test
    fun lastPortDisconnectRestoresCenterButUnbentPortNeverChangesWinner() {
        val h = Harness()
        val neverBent = h.port(301L)
        val bent = h.port(302L)
        bend(bent, 0, 16_383)
        neverBent.close()
        assertEquals(listOf(16_383), h.applied)
        bent.close()
        assertEquals(listOf(16_383, 8_192), h.applied)
    }

    @Test
    fun reconnectGetsFreshIdentityAndLateOldPacketsCannotRetakeExpression() {
        val h = Harness()
        val old = h.port(401L)
        bend(old, 0, 1_000)
        old.close()
        bend(old, 0, 16_000) // A late MIDI receiver packet after disconnect.
        assertEquals(listOf(1_000, 8_192), h.applied)

        val newPort = h.port(402L)
        bend(newPort, 0, 10_000)
        old.close()
        assertEquals(listOf(1_000, 8_192, 10_000), h.applied)
        newPort.close()
        assertEquals(8_192, h.applied.last())
    }

    @Test
    fun sourceAwareDefaultAndLegacyListenerPreserveOneBendCallback() {
        val fallbackBends = mutableListOf<Int>()
        val aware = object : SourceAwareMidiMessageListener {
            override fun onPitchBend(channel: Int, value: Int) {
                fallbackBends += value
            }
        }
        val sourceAwarePort = MidiPortSession(MidiSourceRouter(501L, aware))
        bend(sourceAwarePort, 3, 7_000)
        sourceAwarePort.close()
        assertEquals(listOf(7_000), fallbackBends)

        val legacyBends = mutableListOf<Int>()
        val legacy = object : MidiMessageListener {
            override fun onPitchBend(channel: Int, value: Int) {
                legacyBends += value
            }
        }
        val legacyPort = MidiPortSession(MidiSourceRouter(502L, legacy))
        bend(legacyPort, 4, 9_000)
        legacyPort.close()
        assertEquals(listOf(9_000), legacyBends)
    }


    @Test
    fun cc121CentersWinningPortChannelWhilePreservingSurvivingControllerBend() {
        val h = Harness()
        val usb = h.port(601L)
        val bluetooth = h.port(602L)
        bend(bluetooth, 1, 6_000)
        bend(usb, 2, 14_000)
        // MIDI channel 3 Reset All Controllers must center only this source.
        usb.feed(byteArrayOf(0xB2.toByte(), 121, 0))
        assertEquals(listOf(6_000, 14_000, 8_192), h.applied)
        usb.close()
        assertEquals(6_000, h.applied.last()) // B's last expression survives A.
        bluetooth.close()
        assertEquals(8_192, h.applied.last())
    }

    @Test
    fun cc121FromPreviouslyUnbentPortWinsGloballyButDoesNotEraseOtherPortState() {
        val h = Harness()
        val other = h.port(701L)
        val resetter = h.port(702L)
        bend(other, 0, 15_000)
        resetter.feed(byteArrayOf(0xB0.toByte(), 121, 0))
        assertEquals(listOf(15_000, 8_192), h.applied)
        resetter.close()
        assertEquals(15_000, h.applied.last())
        other.close()
        assertEquals(8_192, h.applied.last())
    }

    @Test
    fun cc121AfterDisconnectIsIgnoredAndLegacyControlCallbacksRemainSingle() {
        val h = Harness()
        val port = h.port(801L)
        bend(port, 3, 3_000)
        port.close()
        port.feed(byteArrayOf(0xB3.toByte(), 121, 0))
        assertEquals(listOf(3_000, 8_192), h.applied)

        val controls = mutableListOf<String>()
        val awareDefault = object : SourceAwareMidiMessageListener {
            override fun onControlChange(channel: Int, controller: Int, value: Int) {
                controls += "aware:$channel:$controller:$value"
            }
        }
        val legacy = object : MidiMessageListener {
            override fun onControlChange(channel: Int, controller: Int, value: Int) {
                controls += "legacy:$channel:$controller:$value"
            }
        }
        val awarePort = MidiPortSession(MidiSourceRouter(802L, awareDefault))
        val legacyPort = MidiPortSession(MidiSourceRouter(803L, legacy))
        awarePort.feed(byteArrayOf(0xB2.toByte(), 121, 0))
        legacyPort.feed(byteArrayOf(0xB1.toByte(), 121, 0))
        assertEquals(listOf("aware:2:121:0", "legacy:1:121:0"), controls)
        awarePort.close()
        legacyPort.close()
    }
}
