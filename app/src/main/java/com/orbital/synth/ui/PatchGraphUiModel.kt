package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import kotlin.math.hypot

/** The exact immutable nodes that exist in the current audio graph. */
internal enum class PatchNodeKind {
    CORE_OSC, OSC_LAYER, VOICE_MIX, FILTER, VOICE_AMP_PAN, MASTER_FX,
    FX_SLOT, AUDIO_OUT, LFO, STEP, MOD_BUS,
}

internal data class PatchGraphNode(
    val id: String,
    val kind: PatchNodeKind,
    val label: String,
    val x: Float,
    val y: Float,
    val active: Boolean = true,
    val slot: Int = -1,
    val source: ModSource? = null,
    val destination: ModDestination? = null,
    val fxType: FxModuleType? = null,
)

/** A mobile graph exposes one nonoverlapping real DSP target bank at a time. */
internal enum class PatchGraphTargetBank(val label: String, val oscillatorIndex: Int = -1) {
    GLOBAL("GLOBAL"),
    OSC1("OSC 1", 0), OSC2("OSC 2", 1), OSC3("OSC 3", 2), OSC4("OSC 4", 3),
    OSC5("OSC 5", 4), OSC6("OSC 6", 5), OSC7("OSC 7", 6), OSC8("OSC 8", 7),
}

internal enum class PatchCableKind { FIXED_AUDIO, MODULATION }

internal data class PatchGraphCable(
    val from: PatchGraphNode,
    val to: PatchGraphNode,
    val kind: PatchCableKind,
    val active: Boolean,
    /** -1 means fixed audio signal wiring, never a user-patchable port. */
    val routeSlot: Int = -1,
)

internal data class PatchGraphSnapshot(
    val nodes: List<PatchGraphNode>,
    val fixedAudio: List<PatchGraphCable>,
    /** Existing LFO/STEP live-target routes, including dormant configured source paths. */
    val patchCables: List<PatchGraphCable>,
    val liveRouteCount: Int,
    val dormantLfoRouteCount: Int,
    val externalSourceRouteCount: Int,
    val reservedRouteCount: Int,
    val stepRouteCount: Int = 0,
    val dormantStepRouteCount: Int = 0,
    /** Configured live routes whose typed destination belongs to another visible bank. */
    val hiddenLiveRouteCount: Int = 0,
    /** Live oscillator destinations remain patchable but silent while their layer is bypassed. */
    val dormantOscillatorTargetRouteCount: Int = 0,
)

/**
 * Portable, deterministic geometry + topology. Pure JVM tests can assert every
 * connection and hit target before any Compose/Canvas render occurs.
 *
 * Actual engine wiring is fixed:
 * BASE CORE OSC + optional OSC1..8 -> voice mix -> voice filter -> amp/pan ->
 * existing master FX -> ordered modular FX (including dry bypass slots) -> OUT.
 * The six global buses plus 16 independently audible OSC gain/pitch ports
 * are live. Banked target views show only resolvable, nonoverlapping endpoints.
 * DRIVE/FX/etc remain reserved metadata, never fabricated PCM cables.
 */
internal object PatchGraphUiModel {
    val lfoSources: List<ModSource> = listOf(
        ModSource.LFO1, ModSource.LFO2, ModSource.LFO3, ModSource.LFO4,
        ModSource.LFO5, ModSource.LFO6, ModSource.LFO7, ModSource.LFO8,
    )

    val liveTargets: List<ModDestination> = ModulationConstellationModel.liveDestinations
    private val globalTargets = listOf(
        ModDestination.PITCH, ModDestination.MORPH,
        ModDestination.CUTOFF, ModDestination.RESONANCE,
        ModDestination.AMP, ModDestination.PAN,
    )
    private val oscillatorGainTargets = listOf(
        ModDestination.OSC1_GAIN, ModDestination.OSC2_GAIN,
        ModDestination.OSC3_GAIN, ModDestination.OSC4_GAIN,
        ModDestination.OSC5_GAIN, ModDestination.OSC6_GAIN,
        ModDestination.OSC7_GAIN, ModDestination.OSC8_GAIN,
    )
    private val oscillatorPitchTargets = listOf(
        ModDestination.OSC1_PITCH, ModDestination.OSC2_PITCH,
        ModDestination.OSC3_PITCH, ModDestination.OSC4_PITCH,
        ModDestination.OSC5_PITCH, ModDestination.OSC6_PITCH,
        ModDestination.OSC7_PITCH, ModDestination.OSC8_PITCH,
    )

    /** Every bank lists only real typed destinations; never a saved-only reserved ID. */
    fun visibleTargets(bank: PatchGraphTargetBank): List<ModDestination> =
        if (bank.oscillatorIndex < 0) globalTargets else listOf(
            oscillatorGainTargets[bank.oscillatorIndex],
            oscillatorPitchTargets[bank.oscillatorIndex],
        )

    fun oscillatorTargetIndex(target: ModDestination): Int {
        val gain = oscillatorGainTargets.indexOf(target)
        return if (gain >= 0) gain else oscillatorPitchTargets.indexOf(target)
    }

    fun targetLabel(target: ModDestination): String =
        if (oscillatorTargetIndex(target) >= 0) target.name.substringAfter('_')
        else target.name

    fun bankForTarget(target: ModDestination): PatchGraphTargetBank {
        val oscillatorIndex = oscillatorTargetIndex(target)
        return PatchGraphTargetBank.entries.firstOrNull {
            it.oscillatorIndex == oscillatorIndex
        } ?: PatchGraphTargetBank.GLOBAL
    }

    fun lfoSource(index: Int): ModSource = lfoSources[index.coerceIn(0, 7)]

    fun lfoIndex(source: ModSource): Int = lfoSources.indexOf(source)

    fun firstEmptyRoute(routes: List<ModulationRouteConfig>): Int =
        routes.take(64).indexOfFirst { it == ModulationRouteConfig() }

    fun safeDepth(depth: Float): Float =
        if (depth.isFinite()) depth.coerceIn(-4f, 4f) else 0f

    /** A tap on the same cable selects that route without layering a duplicate. */
    fun findOrCreateLiveRoute(
        routes: List<ModulationRouteConfig>,
        source: ModSource,
        destination: ModDestination,
    ): Pair<Int, ModulationRouteConfig>? {
        if ((source !in lfoSources && source != ModSource.STEP) || destination !in liveTargets) return null
        val patch = routes.take(64)
        val existing = patch.indexOfFirst {
            it.enabled && it.source == source && it.destination == destination
        }
        if (existing >= 0) return existing to patch[existing]
        val free = firstEmptyRoute(routes)
        if (free < 0) return null
        return free to ModulationRouteConfig(
            enabled = true,
            source = source,
            destination = destination,
            depth = .35f,
        )
    }

    fun destinationEndpoint(destination: ModDestination): String? = when (destination) {
        ModDestination.PITCH, ModDestination.MORPH -> "CORE"
        ModDestination.CUTOFF, ModDestination.RESONANCE -> "FILTER"
        ModDestination.AMP, ModDestination.PAN -> "AMP"
        else -> oscillatorTargetIndex(destination).takeIf { it >= 0 }?.let { layer ->
            "OSC " + (layer + 1) + " " + targetLabel(destination)
        }
    }

    fun slotName(type: FxModuleType): String = when (type) {
        FxModuleType.SATURATION -> "DRIVE"
        FxModuleType.TREMOLO -> "TREM"
        FxModuleType.CHORUS -> "CHORUS"
        FxModuleType.DELAY -> "DELAY"
        FxModuleType.REVERB -> "REVERB"
        FxModuleType.PHASER -> "PHASE"
        FxModuleType.BITCRUSH -> "CRUSH"
        else -> type.name.take(6)
    }

    fun build(
        oscillators: List<OscillatorSlot>,
        lfos: List<ModulationLfoConfig>,
        routes: List<ModulationRouteConfig>,
        fxSlots: List<FxModuleConfig>,
        stepConfig: ModulationStepConfig? = null,
        bank: PatchGraphTargetBank = PatchGraphTargetBank.GLOBAL,
    ): PatchGraphSnapshot {
        val nodes = ArrayList<PatchGraphNode>(36)
        fun node(
            id: String, kind: PatchNodeKind, label: String,
            x: Float, y: Float, active: Boolean = true, slot: Int = -1,
            source: ModSource? = null, dest: ModDestination? = null,
            fxType: FxModuleType? = null,
        ): PatchGraphNode = PatchGraphNode(
            id, kind, label, x, y, active, slot, source, dest, fxType,
        ).also(nodes::add)

        val core = node("CORE", PatchNodeKind.CORE_OSC, "CORE", .5f, .06f)
        val layers = List(8) { i ->
            val config = oscillators.getOrNull(i) ?: OscillatorSlot()
            node(
                "OSC" + i, PatchNodeKind.OSC_LAYER, "O" + (i + 1),
                .125f + (i % 4) * .25f,
                if (i < 4) .145f else .215f,
                config.enabled && config.level > 0f, i,
            )
        }
        val mix = node("MIX", PatchNodeKind.VOICE_MIX, "MIX", .5f, .30f)
        val filter = node("FILTER", PatchNodeKind.FILTER, "FILTER", .25f, .385f)
        val amp = node("AMP", PatchNodeKind.VOICE_AMP_PAN, "AMP/PAN", .75f, .385f)
        val master = node("MASTER", PatchNodeKind.MASTER_FX, "MASTER", .5f, .47f)

        // Six FX slots are live in OrbitalSynth today; the standalone DSP supports
        // 1..8. Draw only host-supplied slots to avoid inventing extra processors.
        val count = fxSlots.take(8).size
        val fx = List(count) { i ->
            val config = fxSlots[i]
            val configured = config != FxModuleConfig()
            node(
                "FX" + i, PatchNodeKind.FX_SLOT, (i + 1).toString() +
                    if (configured) " " + slotName(config.type) else " DRY",
                if (count > 6) {
                    // Snake eight real ordered FX stages over two touch-safe rows.
                    .125f + (if (i < 4) i else 7 - i) * .25f
                } else (i + 1).toFloat() / (count + 1),
                if (count > 6) (if (i < 4) .52f else .59f) else .55f,
                configured && config.enabled && config.wet > 0f, i,
                fxType = if (configured) config.type else null,
            )
        }
        val out = node("OUT", PatchNodeKind.AUDIO_OUT, "OUT", .5f, .63f)
        // STEP is a real audio-clocked modulation source only when the host
        // supplies its immutable config. Disabled sequences stay visible/dim.
        val stepNode = stepConfig?.let { config ->
            node("STEP", PatchNodeKind.STEP, "STEP", .08f, .665f,
                active = config.enabled, source = ModSource.STEP)
        }
        val lfoNodes = List(8) { i ->
            node(
                "LFO" + i, PatchNodeKind.LFO, "LFO " + (i + 1),
                .125f + (i % 4) * .25f,
                if (i < 4) .735f else .81f,
                lfos.getOrNull(i)?.enabled == true, i,
                source = lfoSources[i],
            )
        }
        // At 350dp, drawing 22 terminals at y=.89/.96 creates 19
        // unreachable overlaps. Show six global targets OR one layer's
        // independent GAIN/PITCH ports; other valid routes remain stored and
        // are honestly counted as hidden-in-another-bank, never reserved.
        val targetNodes = visibleTargets(bank).mapIndexed { i, dest ->
            val oscIndex = oscillatorTargetIndex(dest)
            node(
                "BUS" + dest.name, PatchNodeKind.MOD_BUS, targetLabel(dest),
                if (bank == PatchGraphTargetBank.GLOBAL) .18f + (i % 3) * .32f
                else if (i == 0) .28f else .72f,
                if (bank == PatchGraphTargetBank.GLOBAL) {
                    if (i < 3) .89f else .96f
                } else .92f,
                active = oscIndex < 0 || oscillators.getOrNull(oscIndex)?.enabled == true,
                dest = dest,
            )
        }

        val fixed = ArrayList<PatchGraphCable>(24)
        fun audio(from: PatchGraphNode, to: PatchGraphNode, active: Boolean = true) {
            fixed += PatchGraphCable(from, to, PatchCableKind.FIXED_AUDIO, active)
        }
        audio(core, mix)
        layers.forEach { audio(it, mix, active = it.active) }
        audio(mix, filter)
        audio(filter, amp)
        audio(amp, master)
        if (fx.isNotEmpty()) {
            audio(master, fx.first())
            fx.zipWithNext { previous, following -> audio(previous, following) }
            audio(fx.last(), out)
        } else {
            audio(master, out)
        }

        val patch = ArrayList<PatchGraphCable>(32)
        var live = 0
        var dormant = 0
        var external = 0
        var reserved = 0
        var stepRoutes = 0
        var dormantSteps = 0
        var hiddenLive = 0
        var dormantOscillatorTarget = 0
        val visiblePorts = targetNodes.associateBy { it.destination }

        routes.take(64).forEachIndexed { index, route ->
            if (!route.enabled) return@forEachIndexed
            if (route.destination !in liveTargets) {
                reserved++
                return@forEachIndexed
            }

            live++
            val to = visiblePorts[route.destination]
            if (to == null) hiddenLive++
            val oscIndex = oscillatorTargetIndex(route.destination)
            val layerEnabled = oscIndex < 0 ||
                oscillators.getOrNull(oscIndex)?.enabled == true
            if (!layerEnabled) dormantOscillatorTarget++
            val usableDepth = route.depth.isFinite() && route.depth != 0f

            if (route.source == ModSource.STEP) {
                stepRoutes++
                if (stepNode == null || !stepNode.active) dormantSteps++
                if (stepNode != null && to != null) {
                    patch += PatchGraphCable(
                        from = stepNode, to = to,
                        kind = PatchCableKind.MODULATION,
                        active = stepNode.active && layerEnabled && usableDepth,
                        routeSlot = index,
                    )
                }
                return@forEachIndexed
            }
            val lfoIndex = lfoIndex(route.source)
            if (lfoIndex < 0) {
                external++
                return@forEachIndexed
            }
            val activeSource = lfoNodes[lfoIndex].active
            if (!activeSource) dormant++
            if (to != null) {
                patch += PatchGraphCable(
                    from = lfoNodes[lfoIndex], to = to,
                    kind = PatchCableKind.MODULATION,
                    active = activeSource && layerEnabled && usableDepth,
                    routeSlot = index,
                )
            }
        }

        return PatchGraphSnapshot(
            nodes, fixed, patch,
            liveRouteCount = live,
            dormantLfoRouteCount = dormant,
            externalSourceRouteCount = external,
            reservedRouteCount = reserved,
            stepRouteCount = stepRoutes,
            dormantStepRouteCount = dormantSteps,
            hiddenLiveRouteCount = hiddenLive,
            dormantOscillatorTargetRouteCount = dormantOscillatorTarget,
        )
    }

    /** 48dp physical hit targets, even when the normalized graph is tall. */
    fun hitNode(
        snapshot: PatchGraphSnapshot,
        normalizedX: Float,
        normalizedY: Float,
        widthPx: Float,
        heightPx: Float,
        touchDiameterPx: Float,
    ): PatchGraphNode? {
        // Reject off-canvas or malformed pointer geometry before nearest
        // node matching, even with unusually large accessibility hit zones.
        if (!normalizedX.isFinite() || !normalizedY.isFinite() ||
            normalizedX !in 0f..1f || normalizedY !in 0f..1f ||
            !widthPx.isFinite() || !heightPx.isFinite() ||
            !touchDiameterPx.isFinite() ||
            widthPx <= 0f || heightPx <= 0f || touchDiameterPx <= 0f) return null
        val x = normalizedX * widthPx
        val y = normalizedY * heightPx
        var closest: PatchGraphNode? = null
        var best = touchDiameterPx * .5f
        for (node in snapshot.nodes) {
            val distance = hypot(x - node.x * widthPx, y - node.y * heightPx)
            if (distance < best) {
                closest = node
                best = distance
            }
        }
        return closest
    }
}
