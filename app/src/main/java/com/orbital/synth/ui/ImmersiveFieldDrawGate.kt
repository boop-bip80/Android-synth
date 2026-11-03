package com.orbital.synth.ui

/**
 * A clean sound field still paints its touch / real-PCM afterglow while opacity
 * is fading. Once its parent Compose layer has reached sub-pixel opacity,
 * drawing hundreds of decorative strokes and gradients has no visual effect.
 *
 * Skip only for explicitly opted-in FOCUS; the regular stage keeps its rich
 * ambient artwork unchanged. 1/255 is the 8-bit display alpha quantum.
 */
internal object ImmersiveFieldDrawGate {
    private const val INVISIBLE_ALPHA = 1f / 255f

    fun shouldSkip(cleanCanvas: Boolean, layerAlpha: Float): Boolean =
        cleanCanvas && (!layerAlpha.isFinite() || layerAlpha <= INVISIBLE_ALPHA)
}
