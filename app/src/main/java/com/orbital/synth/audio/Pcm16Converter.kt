package com.orbital.synth.audio

/**
 * Sanitized, allocation-free conversion used when a device cannot open a float AudioTrack.
 * AudioTrack's short[] API expects interleaved signed PCM16 values.
 */
object Pcm16Converter {
    fun convert(source: FloatArray, destination: ShortArray, sampleCount: Int) {
        require(sampleCount >= 0 && sampleCount <= source.size && sampleCount <= destination.size)
        for (index in 0 until sampleCount) {
            val sample = source[index]
            val bounded = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            destination[index] = if (bounded <= -1f) Short.MIN_VALUE
                else (bounded * 32767f).toInt().toShort()
        }
    }
}
