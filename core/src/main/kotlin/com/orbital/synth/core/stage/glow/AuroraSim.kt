package com.orbital.synth.core.stage.glow

import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.GuideTarget
import com.orbital.synth.core.stage.LaneRole
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The living surface of the stage. Each scale lane is a luminous string: it leans
 * toward nearby fingers, rings like a plucked string when struck, sheds sparks and
 * rippling light, and glows with the actual audio. Idle fireflies drift and swarm
 * toward touch. A "follow the light" melody ribbon floats above it when a song is
 * being learned.
 *
 * Pure Kotlin with no Android types: the host feeds it normalized (0..1) touch
 * positions plus audio meters, advances it with [step] once per frame and asks it to
 * [draw] through a [GlowCanvas]. Nothing allocates in [step] or [draw] after
 * construction, apart from reconfiguring when the key changes.
 */
class AuroraSim(seed: Long = 7L) {
    enum class Quality { HIGH, LOW }

    /** When true (OS "reduce motion"), keeps clear static highlights but no sparks, sway or ripples. */
    var reducedMotion: Boolean = false

    /** When true the idle surface keeps breathing softly; false keeps it still until touched. */
    var ambient: Boolean = true

    /**
     * 1 = lanes, labels and nebula are always visible; 0 = the surface is pure black until
     * touched, then lights up around the finger (the FOCUS canvas look).
     */
    var idleVisibility: Float = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(0f, 1f) else 1f }

    var quality: Quality = Quality.HIGH
        private set

    private val rng = Random(seed)

    // ---- viewport -------------------------------------------------------------------
    private var w = 1080f
    private var h = 2000f
    private var dp = 2.6f

    fun resize(widthPx: Float, heightPx: Float, density: Float) {
        w = widthPx.coerceAtLeast(1f)
        h = heightPx.coerceAtLeast(1f)
        dp = density.coerceIn(0.5f, 6f)
    }

    // ---- key / lanes -----------------------------------------------------------------
    private var grid: DiatonicGrid? = null
    private var palette: AuroraPalette? = null
    private var laneRgb = IntArray(0)
    private var laneRole = Array(0) { LaneRole.COLOR }
    private var laneName = Array(0) { "" }
    private var laneDegree = IntArray(0)
    private var glow = FloatArray(0)
    private var held = IntArray(0)
    private var pluckAge = FloatArray(0)
    private var pluckAmp = FloatArray(0)
    private var pluckX = FloatArray(0)
    private var flash = FloatArray(0)
    private var flashRgb = IntArray(0)
    private var prox = FloatArray(0)
    private var laneCountValue = 0

    val laneCount: Int get() = laneCountValue

    fun setGrid(newGrid: DiatonicGrid) {
        val old = grid
        grid = newGrid
        val pal = AuroraPalette(newGrid.key)
        palette = pal
        val n = newGrid.laneCount
        laneCountValue = n
        laneRgb = pal.laneColors(newGrid)
        laneRole = Array(n) { newGrid.role(it) }
        laneName = Array(n) { newGrid.noteName(it) }
        laneDegree = IntArray(n) { newGrid.degreeNumber(it) }
        if (old == null || old.laneCount != n) {
            glow = FloatArray(n); held = IntArray(n); pluckAge = FloatArray(n) { 9f }
            pluckAmp = FloatArray(n); pluckX = FloatArray(n); flash = FloatArray(n)
            flashRgb = IntArray(n); prox = FloatArray(n)
        }
        // Fingers already down keep sounding across a grid change: re-seed them on the new
        // lane array so none of their stored lanes can point past it.
        held.fill(0)
        for (f in fingers) {
            if (!f.alive) { f.laneN = 0; continue }
            f.lane = f.lane.coerceIn(0, n - 1)
            f.lanes[0] = f.lane
            f.laneN = 1
            held[f.lane]++
        }
        // A key change is a colour event: bloom every lane once so it is felt.
        if (old != null && old.key != newGrid.key && !reducedMotion) {
            for (i in 0 until n) glow[i] = max(glow[i], 0.5f)
            spawnRipple(w * 0.5f, h * 0.5f, pal.root, 0.8f, 0f)
            spawnRipple(w * 0.5f, h * 0.5f, pal.fifth, 0.6f, 0.14f)
        }
    }

    // ---- fingers --------------------------------------------------------------------
    private class Finger {
        var id = -1L
        var alive = false
        var x = 0f
        var y = 0f
        var lane = 0
        var prevX = 0f
        var prevY = 0f
        var speed = 0f
        var energy = 0f
        var emberAcc = 0f
        var trailAcc = 0f
        val trailX = FloatArray(TRAIL)
        val trailY = FloatArray(TRAIL)
        var trailCount = 0
        var trailHead = 0
        /** Seconds the trail keeps fading after the finger lifts. */
        var ghost = 0f
        var slot = 0
        /** Every lane this finger currently sounds (the touched lane first, then chord tones). */
        val lanes = IntArray(MAX_CHORD)
        var laneN = 0
    }

    private val fingers = Array(MAX_FINGERS) { i -> Finger().also { it.slot = i } }

    private fun releaseHeld(f: Finger) {
        for (k in 0 until f.laneN) {
            val lane = f.lanes[k]
            if (lane in 0 until laneCountValue && held[lane] > 0) held[lane]--
        }
        f.laneN = 0
    }

    private fun fingerFor(id: Long, create: Boolean): Finger? {
        for (f in fingers) if (f.alive && f.id == id) return f
        if (!create) return null
        // Prefer a slot with no fading ghost trail; fall back to recycling a ghost.
        val slot = fingers.firstOrNull { !it.alive && it.ghost <= 0f } ?: fingers.firstOrNull { !it.alive }
        if (slot != null) {
            slot.alive = true; slot.id = id; slot.trailCount = 0; slot.trailHead = 0
            slot.energy = 0f; slot.speed = 0f; slot.emberAcc = 0f; slot.trailAcc = 0f
            slot.ghost = 0f
        }
        return slot
    }

    // ---- transient effects -----------------------------------------------------------
    private val pulseLane = IntArray(PULSES)
    private val pulseX = FloatArray(PULSES)
    private val pulseAge = FloatArray(PULSES) { 9f }
    private val pulseStrength = FloatArray(PULSES)
    private var pulseNext = 0

    private val ripX = FloatArray(RIPPLES)
    private val ripY = FloatArray(RIPPLES)
    private val ripAge = FloatArray(RIPPLES) { 9f }
    private val ripStrength = FloatArray(RIPPLES)
    private val ripRgb = IntArray(RIPPLES)
    private var ripNext = 0

    private val popText = Array(POPUPS) { "" }
    private val popSub = Array(POPUPS) { "" }
    private val popX = FloatArray(POPUPS)
    private val popY = FloatArray(POPUPS)
    private val popAge = FloatArray(POPUPS) { 9f }
    private val popRgb = IntArray(POPUPS)

    // ---- particles (structure of arrays: no per-spark objects) ---------------------------
    private val px = FloatArray(PARTICLES)
    private val py = FloatArray(PARTICLES)
    private val pvx = FloatArray(PARTICLES)
    private val pvy = FloatArray(PARTICLES)
    private val plife = FloatArray(PARTICLES)
    private val pmax = FloatArray(PARTICLES) { 1f }
    private val psize = FloatArray(PARTICLES)
    private val prgb = IntArray(PARTICLES)
    private var pNext = 0

    private val moteX = FloatArray(MOTES) { rng.nextFloat() }
    private val moteY = FloatArray(MOTES) { rng.nextFloat() }
    private val moteVx = FloatArray(MOTES)
    private val moteVy = FloatArray(MOTES)
    private val motePhase = FloatArray(MOTES) { rng.nextFloat() * 6.2831855f }
    private val moteSize = FloatArray(MOTES) { 1.2f + rng.nextFloat() * 2.2f }

    // ---- audio ---------------------------------------------------------------------------
    private var audioRms = 0f
    private var audioLow = 0f
    private var audioMid = 0f
    private var audioHigh = 0f
    private var audioFlash = 0f

    fun audio(rms: Float, peak: Float, transient: Float, low: Float, mid: Float, high: Float) {
        audioRms = fin(rms).coerceIn(0f, 1f)
        audioLow = fin(low).coerceIn(0f, 1f)
        audioMid = fin(mid).coerceIn(0f, 1f)
        audioHigh = fin(high).coerceIn(0f, 1f)
        audioFlash = max(audioFlash, fin(transient).coerceIn(0f, 1f) * 0.6f + fin(peak).coerceIn(0f, 1f) * 0.1f)
    }

    // ---- guide -----------------------------------------------------------------------------
    private val guideLane = IntArray(GUIDE_MAX)
    private val guideBeats = FloatArray(GUIDE_MAX)
    private var guideCount = 0
    private var guideCombo = 0
    private var guideClock = 0f
    private var guideShowRibbon = true

    /** Show the melody ahead: element 0 is the note to play now. Empty clears the guide. */
    fun setGuide(targets: List<GuideTarget>, combo: Int) {
        guideCount = if (laneCountValue == 0) 0 else min(targets.size, GUIDE_MAX)
        for (i in 0 until guideCount) {
            // Never trust the caller: a bad lane or length must not become a bad draw call.
            guideLane[i] = targets[i].lane.coerceIn(0, laneCountValue - 1)
            guideBeats[i] = fin(targets[i].beats).coerceIn(0.05f, 8f)
        }
        guideCombo = combo.coerceAtLeast(0)
    }

    fun clearGuide() {
        guideCount = 0
        guideCombo = 0
    }

    /** Hide or show the flowing contour (the next-note beacon always shows). */
    fun setGuideRibbon(visible: Boolean) { guideShowRibbon = visible }

    fun guideHit(lane: Int, combo: Int) {
        val pal = palette ?: return
        val y = laneY(lane)
        val x = guideAnchorX()
        if (lane in 0 until laneCountValue) {
            flash[lane] = 1f
            flashRgb[lane] = pal.gold
        }
        if (reducedMotion) return
        burst(x, y, pal.gold, if (combo > 0 && combo % 8 == 0) 46 else 20, 230f)
        spawnRipple(x, y, pal.gold, 0.9f, 0f)
        if (combo > 0 && combo % 8 == 0) {
            spawnRipple(x, y, GlowColor.WHITE, 0.7f, 0.12f)
            spawnRipple(w * 0.5f, y, pal.gold, 0.5f, 0.2f)
        }
    }

    fun guideMiss(lane: Int) {
        if (lane in 0 until laneCountValue) {
            flash[lane] = 0.8f
            flashRgb[lane] = MISS_RGB
        }
    }

    private val fireTime = FloatArray(FIREWORKS)
    private val fireX = FloatArray(FIREWORKS)
    private val fireY = FloatArray(FIREWORKS)
    private val fireRgb = IntArray(FIREWORKS)
    private val fireDone = BooleanArray(FIREWORKS)
    private var fireCount = 0

    /** Finale: fireworks scaled by the star rating. */
    fun celebrate(stars: Int) {
        val pal = palette ?: return
        fireCount = min(FIREWORKS, 3 + stars.coerceIn(1, 3) * 3)
        for (i in 0 until fireCount) {
            fireTime[i] = i * 0.28f
            fireDone[i] = false
            fireX[i] = w * (0.15f + rng.nextFloat() * 0.7f)
            fireY[i] = h * (0.15f + rng.nextFloat() * 0.55f)
            fireRgb[i] = when (i % 3) { 0 -> pal.gold; 1 -> pal.root; else -> pal.fifth }
        }
    }

    // ---- input -------------------------------------------------------------------------------
    fun strike(
        pointerId: Long, lane: Int, nx: Float, ny: Float, velocity: Float,
        slid: Boolean, label: String, sub: String, lanes: IntArray = NO_LANES,
    ) {
        if (palette == null || laneCountValue == 0) return
        val l = lane.coerceIn(0, laneCountValue - 1)
        val f = fingerFor(pointerId, create = true) ?: return
        val x = fin(nx).coerceIn(0f, 1f) * w
        val y = fin(ny).coerceIn(0f, 1f) * h
        if (f.trailCount == 0) { f.prevX = x; f.prevY = y }
        // The lanes a finger leaves (by sliding, or by striking again) are no longer held.
        if (f.energy > 0f) releaseHeld(f)
        f.x = x; f.y = y; f.lane = l; f.energy = 1f
        f.lanes[0] = l
        f.laneN = 1
        for (k in lanes.indices) {
            val extra = lanes[k]
            if (f.laneN < MAX_CHORD && extra in 0 until laneCountValue && extra != l) {
                f.lanes[f.laneN++] = extra
            }
        }
        pushTrail(f)
        val v = fin(velocity).coerceIn(0f, 1f)
        val rgb = laneRgb[l]
        for (k in 0 until f.laneN) {
            val sounding = f.lanes[k]
            held[sounding]++
            val lead = k == 0
            glow[sounding] = max(glow[sounding], if (lead) 0.9f else 0.7f)
            if (!reducedMotion) {
                pluckAge[sounding] = 0f
                pluckAmp[sounding] = (0.5f + 0.5f * v) * (if (lead) 15f else 11f) * dp
                pluckX[sounding] = x
                addPulse(sounding, x, (0.6f + 0.4f * v) * (if (lead) 1f else 0.7f))
                if (!lead) burst(x, laneY(sounding), laneRgb[sounding], 4, 120f)
            }
        }
        if (!reducedMotion) {
            // Sympathetic strings: the octave and fifth ring faintly, like a real instrument.
            val g = grid
            if (g != null) {
                sympathetic(l + g.stepsPerOctave, x, v * 0.35f)
                sympathetic(l - g.stepsPerOctave, x, v * 0.35f)
                sympathetic(l + fifthOffset(g), x, v * 0.22f)
            }
            if (!slid) {
                spawnRipple(x, y, rgb, 0.55f + 0.45f * v, 0f)
                spawnRipple(x, y, GlowColor.mix(rgb, GlowColor.WHITE, 0.5f), 0.4f, 0.1f)
            }
            burst(x, laneY(l), rgb, if (slid) 5 else (9 + (13 * v).toInt()), (80f + 190f * v))
        }
        // Sit the label above the whole chord column so it never collides with the tone markers.
        var topY = min(y, laneY(l))
        for (k in 1 until f.laneN) topY = min(topY, laneY(f.lanes[k]))
        setPopup(f.slot, label, sub, x, topY - 62f * dp, GlowColor.mix(rgb, GlowColor.WHITE, 0.35f))
        // Re-use the guide flash for plain lane feedback.
        flash[l] = max(flash[l], 0.4f)
        flashRgb[l] = rgb
    }

    fun move(pointerId: Long, nx: Float, ny: Float) {
        val f = fingerFor(pointerId, create = false) ?: return
        f.x = fin(nx).coerceIn(0f, 1f) * w
        f.y = fin(ny).coerceIn(0f, 1f) * h
    }

    fun release(pointerId: Long) {
        val f = fingerFor(pointerId, create = false) ?: return
        releaseHeld(f)
        f.alive = false
        f.energy = 0f
        f.ghost = if (reducedMotion) 0f else GHOST_SECONDS
        if (reducedMotion) popAge[f.slot % POPUPS] = POPUP_SECONDS + 1f
    }

    fun cancelAll() {
        for (f in fingers) { f.alive = false; f.energy = 0f; f.ghost = 0f; f.laneN = 0 }
        held.fill(0)
    }

    val activeFingerCount: Int get() = fingers.count { it.alive }

    /** True while anything is moving; the host can stop requesting frames when false. */
    val needsFrames: Boolean get() = (ambient && laneCountValue > 0) || busy

    /** Real activity (touch, effects, audio), ignoring the idle ambient breathing. */
    val busy: Boolean
        get() {
            if (laneCountValue == 0) return false
            if (fingers.any { it.alive || it.ghost > 0f } || guideCount > 0 || fireCount > 0) return true
            if (audioRms > 0.004f || audioFlash > 0.01f) return true
            for (i in 0 until laneCountValue) if (glow[i] > 0.01f || flash[i] > 0.01f) return true
            for (i in 0 until PARTICLES) if (plife[i] > 0f) return true
            for (i in 0 until RIPPLES) if (ripAge[i] < RIPPLE_SECONDS) return true
            for (i in 0 until POPUPS) if (popAge[i] < POPUP_SECONDS) return true
            return false
        }

    // ---- simulation -------------------------------------------------------------------------------
    private var t = 0f
    private var slowFrames = 0
    private var fastFrames = 0

    fun step(dtIn: Float) {
        val rawDt = fin(dtIn).coerceIn(0f, 0.25f)
        // Frame-time governor: shed particle load on slow devices, restore it when healthy.
        if (rawDt > 0.022f) { slowFrames++; fastFrames = 0 } else if (rawDt in 0.0001f..0.0185f) {
            fastFrames++; slowFrames = 0
        }
        if (quality == Quality.HIGH && slowFrames > 45) { quality = Quality.LOW; slowFrames = 0 }
        if (quality == Quality.LOW && fastFrames > 360) { quality = Quality.HIGH; fastFrames = 0 }
        val dt = min(rawDt, 0.05f)
        // A zero-length frame (duplicate vsync timestamp) advances nothing; dividing by it
        // would otherwise throw NaN velocities into the particle pool.
        if (dt < 0.0001f) return
        t += dt
        guideClock += dt
        val n = laneCountValue
        if (n == 0) return

        // Reduce-motion means no fades: light snaps on and off with the finger.
        val riseK = if (reducedMotion) 1f else 1f - exp(-18f * dt)
        val fallK = if (reducedMotion) 1f else 1f - exp(-3.4f * dt)
        for (i in 0 until n) {
            val target = if (held[i] > 0) 1f else 0f
            glow[i] += (target - glow[i]) * (if (target > glow[i]) riseK else fallK)
            pluckAge[i] += dt
            flash[i] = max(0f, flash[i] - dt * 2.4f)
        }
        audioFlash = max(0f, audioFlash - dt * 3.2f)
        // Light gathers around fingers: each lane brightens with its closeness to a touch.
        val laneSize = h / n
        for (i in 0 until n) {
            var target = 0f
            val ly = laneY(i)
            for (f in fingers) {
                if (!f.alive) continue
                val d = (f.y - ly) / laneSize
                val value = exp(-d * d * 0.18f)
                if (value > target) target = value
            }
            prox[i] += (target - prox[i]) * (if (target > prox[i]) riseK else fallK)
        }

        for (f in fingers) {
            if (!f.alive) {
                if (f.ghost > 0f) f.ghost = max(0f, f.ghost - dt)
                continue
            }
            val dx = f.x - f.prevX
            val dy = f.y - f.prevY
            val dist = sqrt(dx * dx + dy * dy)
            f.speed += ((if (dt > 0f) dist / dt / (900f * dp / 2.6f) else 0f).coerceIn(0f, 1f) - f.speed) * min(1f, dt * 14f)
            f.prevX = f.x; f.prevY = f.y
            f.trailAcc += dt
            val interval = if (quality == Quality.HIGH) 0.012f else 0.024f
            if (f.trailAcc >= interval) { f.trailAcc = 0f; pushTrail(f) }
            if (!reducedMotion) {
                val rate = if (quality == Quality.HIGH) 1f else 0.5f
                // Sparks thrown off by movement.
                if (dist > 0.5f * dp) {
                    f.emberAcc += dist / (7f * dp) * rate
                    while (f.emberAcc >= 1f) {
                        f.emberAcc -= 1f
                        val l = f.lane.coerceIn(0, n - 1)
                        spawnParticle(
                            f.x + (rng.nextFloat() - 0.5f) * 10f * dp, f.y + (rng.nextFloat() - 0.5f) * 10f * dp,
                            -dx / dt * 0.12f + (rng.nextFloat() - 0.5f) * 60f * dp,
                            -dy / dt * 0.12f + (rng.nextFloat() - 0.5f) * 60f * dp - 18f * dp,
                            0.45f + rng.nextFloat() * 0.6f, (1.1f + rng.nextFloat() * 1.7f) * dp,
                            GlowColor.mix(laneRgb[l], GlowColor.WHITE, rng.nextFloat() * 0.5f),
                        )
                    }
                } else {
                    // Held: embers drift up off the string like heat shimmer.
                    f.emberAcc += dt * 9f * rate
                    while (f.emberAcc >= 1f) {
                        f.emberAcc -= 1f
                        val l = f.lane.coerceIn(0, n - 1)
                        spawnParticle(
                            f.x + (rng.nextFloat() - 0.5f) * 70f * dp, laneY(l),
                            (rng.nextFloat() - 0.5f) * 14f * dp, -(24f + rng.nextFloat() * 36f) * dp,
                            1.0f + rng.nextFloat() * 1.0f, (0.9f + rng.nextFloat() * 1.2f) * dp, laneRgb[l],
                        )
                    }
                }
            }
        }

        updatePulsesAndRipples(dt)
        updateParticles(dt)
        updateMotes(dt)
        updateFireworks(dt)
    }

    private fun updatePulsesAndRipples(dt: Float) {
        for (i in 0 until PULSES) pulseAge[i] += dt
        for (i in 0 until RIPPLES) ripAge[i] += dt
        for (i in 0 until POPUPS) popAge[i] += dt
    }

    private fun updateParticles(dt: Float) {
        val drag = exp(-1.7f * dt)
        for (i in 0 until PARTICLES) {
            if (plife[i] <= 0f) continue
            plife[i] -= dt
            pvx[i] *= drag
            pvy[i] = pvy[i] * drag - 10f * dp * dt
            px[i] += pvx[i] * dt
            py[i] += pvy[i] * dt
        }
    }

    private fun updateMotes(dt: Float) {
        // Fireflies drift on slow noise and are drawn toward fingers.
        val reach = 300f * dp
        for (i in 0 until MOTES) {
            val mx = moteX[i] * w
            val my = moteY[i] * h
            var ax = cos(t * 0.37f + motePhase[i]) * 7f * dp
            var ay = sin(t * 0.29f + motePhase[i] * 1.7f) * 7f * dp - 2f * dp
            for (f in fingers) {
                if (!f.alive) continue
                val dx = f.x - mx
                val dy = f.y - my
                val d2 = dx * dx + dy * dy
                if (d2 < reach * reach && d2 > 1f) {
                    val d = sqrt(d2)
                    val pull = (1f - d / reach) * 140f * dp
                    // Pull plus a swirl so they orbit instead of collapsing onto the finger.
                    ax += dx / d * pull + (-dy / d) * pull * 0.8f
                    ay += dy / d * pull + (dx / d) * pull * 0.8f
                }
            }
            moteVx[i] = (moteVx[i] + ax * dt) * exp(-1.4f * dt)
            moteVy[i] = (moteVy[i] + ay * dt) * exp(-1.4f * dt)
            moteX[i] += moteVx[i] * dt / w
            moteY[i] += moteVy[i] * dt / h
            if (moteX[i] < -0.05f) moteX[i] = 1.05f else if (moteX[i] > 1.05f) moteX[i] = -0.05f
            if (moteY[i] < -0.05f) moteY[i] = 1.05f else if (moteY[i] > 1.05f) moteY[i] = -0.05f
        }
    }

    private fun updateFireworks(dt: Float) {
        if (fireCount == 0) return
        var pending = 0
        for (i in 0 until fireCount) {
            if (fireDone[i]) continue
            fireTime[i] -= dt
            if (fireTime[i] <= 0f) {
                fireDone[i] = true
                burst(fireX[i], fireY[i], fireRgb[i], 52, 330f)
                spawnRipple(fireX[i], fireY[i], fireRgb[i], 0.9f, 0f)
            } else pending++
        }
        if (pending == 0) fireCount = 0
    }

    // ---- spawning --------------------------------------------------------------------------------------
    private fun spawnParticle(x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, rgb: Int) {
        val cap = if (quality == Quality.HIGH) PARTICLES else PARTICLES / 2
        // Ring buffer: the oldest spark is recycled, so the pool never grows.
        val i = pNext % cap
        pNext = (pNext + 1) % PARTICLES
        px[i] = x; py[i] = y; pvx[i] = vx; pvy[i] = vy
        plife[i] = life; pmax[i] = life; psize[i] = size; prgb[i] = rgb
    }

    private fun burst(x: Float, y: Float, rgb: Int, count: Int, speed: Float) {
        val c = if (quality == Quality.HIGH) count else count / 2
        for (k in 0 until c) {
            val angle = rng.nextFloat() * 6.2831855f
            val s = (0.35f + rng.nextFloat() * 0.65f) * speed * dp
            spawnParticle(
                x, y, cos(angle) * s, sin(angle) * s * 0.8f - 30f * dp,
                0.5f + rng.nextFloat() * 0.9f, (1.4f + rng.nextFloat() * 2.4f) * dp,
                if (rng.nextFloat() < 0.25f) GlowColor.mix(rgb, GlowColor.WHITE, 0.7f) else rgb,
            )
        }
    }

    private fun spawnRipple(x: Float, y: Float, rgb: Int, strength: Float, delay: Float) {
        val i = ripNext
        ripNext = (ripNext + 1) % RIPPLES
        ripX[i] = x; ripY[i] = y; ripAge[i] = -delay; ripStrength[i] = strength; ripRgb[i] = rgb
    }

    private fun addPulse(lane: Int, x: Float, strength: Float) {
        val i = pulseNext
        pulseNext = (pulseNext + 1) % PULSES
        pulseLane[i] = lane; pulseX[i] = x; pulseAge[i] = 0f; pulseStrength[i] = strength
    }

    /** One popup per finger: a new strike (or slide) rewrites it in place instead of stacking. */
    private fun setPopup(slot: Int, text: String, sub: String, x: Float, y: Float, rgb: Int) {
        val i = slot % POPUPS
        popText[i] = text; popSub[i] = sub
        popX[i] = x.coerceIn(60f * dp, w - 60f * dp); popY[i] = y.coerceAtLeast(40f * dp)
        popAge[i] = 0f; popRgb[i] = rgb
    }

    private fun sympathetic(lane: Int, x: Float, strength: Float) {
        if (lane !in 0 until laneCountValue) return
        pluckAge[lane] = 0f
        pluckAmp[lane] = max(pluckAmp[lane] * 0.4f, strength * 15f * dp)
        pluckX[lane] = x
        glow[lane] = max(glow[lane], strength * 1.2f)
    }

    private fun fifthOffset(g: DiatonicGrid): Int {
        for (k in 1..g.stepsPerOctave) if (g.midiForStep(k) - g.midiForStep(0) == 7) return k
        return (g.stepsPerOctave * 4 + 3) / 7
    }

    private fun pushTrail(f: Finger) {
        f.trailX[f.trailHead] = f.x
        f.trailY[f.trailHead] = f.y
        f.trailHead = (f.trailHead + 1) % TRAIL
        if (f.trailCount < TRAIL) f.trailCount++
    }

    // ---- geometry helpers -----------------------------------------------------------------------------------
    private fun laneY(lane: Int): Float {
        val g = grid ?: return h * 0.5f
        return h * g.centerY(lane)
    }

    private fun guideAnchorX(): Float = max(w * 0.2f, 92f * dp)

    private fun fin(v: Float): Float = if (v.isFinite()) v else 0f

    // ====================================================================================================
    // Drawing
    // ====================================================================================================
    private val sx = FloatArray(STRING_SAMPLES + 1)
    private val sy = FloatArray(STRING_SAMPLES + 1)
    private val tx = FloatArray(TRAIL)
    private val ty = FloatArray(TRAIL)
    private val gx = FloatArray(GUIDE_MAX * GUIDE_SEGMENT_SAMPLES + 1)
    private val gy = FloatArray(GUIDE_MAX * GUIDE_SEGMENT_SAMPLES + 1)

    fun draw(c: GlowCanvas) {
        val pal = palette ?: return
        val n = laneCountValue
        if (n == 0) return
        val low = quality == Quality.LOW
        val laneH = h / n

        val vis = idleVisibility
        // Faint key-coloured wash that swells with the music.
        val wash = 0.04f * vis + audioRms * 0.12f + audioFlash * 0.08f
        if (wash > 0.004f) c.glow(w * 0.5f, h * 0.62f, max(w, h) * 0.75f, pal.ambient, wash)
        drawNebula(c, pal, vis, low)

        drawLaneBands(c, laneH)
        drawStrings(c, laneH, low)
        drawMotes(c, pal)
        drawGuide(c, pal, laneH)
        drawTrails(c, low)
        drawRipples(c)
        drawParticles(c, low)
        drawFingers(c)
        drawLabels(c)
        drawPopups(c)
    }

    /** Slow key-tinted clouds and aurora curtains: depth behind the strings, and an awake idle. */
    private fun drawNebula(c: GlowCanvas, pal: AuroraPalette, vis: Float, low: Boolean) {
        val lowEnergy = audioLow * 0.5f + audioRms * 0.4f
        val drift = if (reducedMotion) 0f else t
        val a = 0.07f * vis + lowEnergy * 0.10f
        if (a > 0.004f) {
            c.glow(w * (0.5f + 0.30f * sin(drift * 0.045f)), h * (0.34f + 0.10f * cos(drift * 0.037f)),
                w * 0.95f, pal.ambient, a)
            c.glow(w * (0.5f - 0.32f * cos(drift * 0.031f)), h * (0.72f + 0.08f * sin(drift * 0.05f)),
                w * 0.85f, pal.fifth, a * 0.7f)
        }
        if (low) return
        val curtain = (0.032f * vis + audioMid * 0.05f + audioRms * 0.04f)
        if (curtain > 0.004f) {
            for (k in 0 until 3) {
                val cx = w * (0.17f + 0.33f * k + 0.07f * sin(drift * 0.06f + k * 2.1f))
                val rgb = when (k) { 0 -> pal.root; 1 -> pal.fifth; else -> pal.third }
                val breathe = 0.7f + 0.3f * sin(drift * 0.4f + k * 1.7f)
                c.vband(cx, w * 0.2f, 0f, h, rgb, curtain * breathe)
            }
        }
    }

    private fun drawLaneBands(c: GlowCanvas, laneH: Float) {
        for (i in 0 until laneCountValue) {
            val a = glow[i] * 0.16f + flash[i] * 0.22f
            if (a > 0.004f) {
                val rgb = if (flash[i] > glow[i] * 0.7f && flash[i] > 0.05f) flashRgb[i] else laneRgb[i]
                c.band(0f, w, laneY(i), laneH * 0.72f, rgb, a)
            }
        }
        // The lane the guide wants you on breathes gently.
        if (guideCount > 0) {
            val l = guideLane[0]
            val pulse = 0.10f + 0.08f * (0.5f + 0.5f * sin(guideClock * 4.2f))
            val pal = palette
            c.band(0f, w, laneY(l), laneH * 0.85f, pal?.gold ?: GlowColor.GOLD, pulse)
        }
    }

    private fun stringOffset(i: Int, x: Float, y0: Float, laneH: Float): Float {
        var off = 0f
        if (!reducedMotion) {
            val u = x / w
            // Ambient sway: the field is awake even when untouched.
            off += sin(6.2831855f * (0.22f * t + u * 1.15f + i * 0.37f)) * 0.9f * dp
            // Plucked ring-down.
            val age = pluckAge[i]
            if (age < 2.4f && pluckAmp[i] > 0.01f) {
                val env = exp(-3.1f * age)
                val shape = sin(PI.toFloat() * u) + 0.4f * sin(2f * PI.toFloat() * u) * cos(10f * age)
                off += pluckAmp[i] * env * cos(46f * age) * shape * 0.8f
            }
            // Strings lean toward fingers.
            for (f in fingers) {
                if (!f.alive) continue
                val dy = (f.y - y0) / laneH
                if (abs(dy) < 2.2f) {
                    val dx = (x - f.x) / (w * 0.2f)
                    off += (f.y - y0) * 0.5f * exp(-dx * dx) * exp(-dy * dy * 0.8f)
                }
            }
        }
        return off
    }

    private fun drawStrings(c: GlowCanvas, laneH: Float, low: Boolean) {
        val n = laneCountValue
        val samples = if (low) STRING_SAMPLES / 2 else STRING_SAMPLES
        for (i in 0 until n) {
            val y0 = laneY(i)
            val role = laneRole[i]
            val pos = i / (n - 1).coerceAtLeast(1).toFloat()
            val band = audioLow * (1f - pos) * (1f - pos) + audioMid * 4f * pos * (1f - pos) + audioHigh * pos * pos
            val breath = if (ambient && !reducedMotion) 0.88f + 0.12f * sin(t * 0.9f + i * 0.55f) else 1f
            val baseAlpha = when (role) {
                LaneRole.ROOT -> 0.72f
                LaneRole.FIFTH -> 0.52f
                LaneRole.THIRD -> 0.46f
                LaneRole.COLOR -> 0.30f
            } * breath * idleVisibility
            val lit = glow[i]
            val near = prox[i]
            val alpha = (baseAlpha + near * 0.42f + lit * 0.5f +
                band * 0.35f * audioRms.coerceAtLeast(0.2f) * idleVisibility.coerceAtLeast(0.3f)).coerceIn(0f, 1f)
            if (alpha < 0.012f && lit < 0.02f) continue
            val width = (if (role == LaneRole.ROOT) 2.1f else 1.35f) * dp + lit * 1.6f * dp
            val rgb = if (lit > 0.3f) GlowColor.mix(laneRgb[i], GlowColor.WHITE, lit * 0.45f) else laneRgb[i]

            val moving = lit > 0.02f || near > 0.05f || pluckAge[i] < 2.4f || guideCount > 0 && guideLane[0] == i ||
                fingers.any { it.alive && abs(it.y - y0) < laneH * 2.2f }
            if (!moving || reducedMotion && lit < 0.02f) {
                if (!low && (role == LaneRole.ROOT || band > 0.15f)) {
                    c.line(0f, y0, w, y0, rgb, alpha * 0.18f, width * 5f)
                }
                c.line(0f, y0, w, y0, rgb, alpha, width)
                continue
            }
            for (s in 0..samples) {
                val x = w * s / samples
                sx[s] = x
                sy[s] = y0 + stringOffset(i, x, y0, laneH)
            }
            if (!low && lit > 0.03f) {
                c.polyline(sx, sy, samples + 1, rgb, 0.10f * lit + 0.04f, width * 6.5f)
                c.polyline(sx, sy, samples + 1, rgb, 0.22f * lit + 0.05f, width * 2.8f)
            }
            c.polyline(sx, sy, samples + 1, rgb, alpha, width)
        }
        // Light racing along struck strings.
        for (k in 0 until PULSES) {
            val age = pulseAge[k]
            if (age > PULSE_SECONDS || reducedMotion) continue
            val lane = pulseLane[k]
            if (lane !in 0 until n) continue
            val fade = (1f - age / PULSE_SECONDS)
            val reach = age * w * 1.5f
            val yy = laneY(lane)
            val rgb = GlowColor.mix(laneRgb[lane], GlowColor.WHITE, 0.55f)
            for (dir in -1..1 step 2) {
                val xx = pulseX[k] + dir * reach
                if (xx < -40f || xx > w + 40f) continue
                val u = xx / w
                val yAt = yy + stringOffset(lane, xx.coerceIn(0f, w), yy, laneH)
                c.glow(xx, yAt, 34f * dp, rgb, 0.55f * fade * pulseStrength[k])
                if (!low) c.circle(xx, yAt, 3f * dp, GlowColor.WHITE, 0.75f * fade * (0.6f + 0.4f * (1f - u)))
            }
        }
    }

    private fun drawMotes(c: GlowCanvas, pal: AuroraPalette) {
        val count = if (quality == Quality.HIGH) MOTES else MOTES / 3
        val anyFinger = fingers.any { it.alive }
        val presence = if (anyFinger) 1f else idleVisibility
        if (presence < 0.02f) return
        for (i in 0 until count) {
            val tw = 0.5f + 0.5f * sin(t * (0.8f + motePhase[i] * 0.2f) + motePhase[i])
            val a = (if (anyFinger) 0.5f else 0.30f) * (0.4f + 0.6f * tw) * presence
            val rgb = if (i % 3 == 0) pal.gold else if (i % 3 == 1) pal.root else pal.fifth
            val x = moteX[i] * w
            val y = moteY[i] * h
            c.glow(x, y, moteSize[i] * 5f * dp, rgb, a * 0.55f)
            c.circle(x, y, moteSize[i] * 0.55f * dp, GlowColor.WHITE, a)
        }
    }

    private fun drawGuide(c: GlowCanvas, pal: AuroraPalette, laneH: Float) {
        if (guideCount == 0) return
        val ax = guideAnchorX()
        val pulse = 0.5f + 0.5f * sin(guideClock * 5.0f)
        val gold = pal.gold
        // The ribbon: a smooth contour through the next few notes, drawn so the shape of
        // the melody is visible before a single note is played.
        if (guideShowRibbon && guideCount > 1) {
            val right = w * 0.92f
            val spacing = (right - ax) / (guideCount - 1)
            var pts = 0
            for (k in 0 until guideCount - 1) {
                val y0 = laneY(guideLane[k])
                val y1 = laneY(guideLane[k + 1])
                for (s in 0 until GUIDE_SEGMENT_SAMPLES) {
                    val u = s / GUIDE_SEGMENT_SAMPLES.toFloat()
                    val e = u * u * (3f - 2f * u)
                    gx[pts] = ax + (k + u) * spacing
                    gy[pts] = y0 + (y1 - y0) * e
                    pts++
                }
            }
            gx[pts] = right; gy[pts] = laneY(guideLane[guideCount - 1]); pts++
            c.polyline(gx, gy, pts, gold, 0.13f, 12f * dp)
            c.polyline(gx, gy, pts, GlowColor.mix(gold, GlowColor.WHITE, 0.3f), 0.55f, 2.1f * dp)
            for (k in 1 until guideCount) {
                val x = ax + k * spacing
                val y = laneY(guideLane[k])
                val fade = 1f - k.toFloat() / (guideCount + 1)
                val r = (7f + min(guideBeats[k], 2f) * 3f) * dp
                c.glow(x, y, r * 3.4f, gold, 0.42f * fade)
                c.circle(x, y, r * 0.6f, GlowColor.mix(gold, GlowColor.WHITE, 0.4f), 0.7f * fade + 0.2f)
            }
        }
        // The beacon: the note to play now.
        val y = laneY(guideLane[0])
        val r = (16f + 4f * pulse) * dp
        c.glow(ax, y, r * 4.2f, gold, 0.55f)
        c.ring(ax, y, r, gold, 0.85f, 2.2f * dp)
        c.ring(ax, y, r * (1.5f + 0.5f * pulse), gold, 0.35f * (1f - pulse * 0.5f), 1.4f * dp)
        c.circle(ax, y, 5.5f * dp, GlowColor.WHITE, 0.95f)
    }

    private fun drawTrails(c: GlowCanvas, low: Boolean) {
        for (f in fingers) {
            if ((!f.alive && f.ghost <= 0f) || f.trailCount < 2) continue
            val fade = if (f.alive) 1f else f.ghost / GHOST_SECONDS
            val count = f.trailCount
            // Oldest-to-newest into scratch arrays.
            val start = (f.trailHead - count + TRAIL) % TRAIL
            for (k in 0 until count) {
                val idx = (start + k) % TRAIL
                tx[k] = f.trailX[idx]
                ty[k] = f.trailY[idx]
            }
            val rgb = laneRgb[f.lane.coerceIn(0, laneCountValue - 1)]
            val step = if (low) 2 else 1
            var k = 1
            while (k < count) {
                val u = k / (count - 1f)
                val a = u * u * 0.55f * fade
                val width = (1f + 5.5f * u) * dp
                c.line(tx[k - 1], ty[k - 1], tx[k], ty[k], rgb, a * 0.35f, width * 2.6f)
                c.line(tx[k - 1], ty[k - 1], tx[k], ty[k], GlowColor.mix(rgb, GlowColor.WHITE, 0.5f), a, width * 0.7f)
                k += step
            }
        }
    }

    private fun drawRipples(c: GlowCanvas) {
        for (i in 0 until RIPPLES) {
            val age = ripAge[i]
            if (age < 0f || age > RIPPLE_SECONDS) continue
            val u = age / RIPPLE_SECONDS
            val ease = 1f - (1f - u) * (1f - u)
            val a = (1f - u) * (1f - u) * 0.6f * ripStrength[i]
            c.ring(ripX[i], ripY[i], (14f + ease * 230f) * dp, ripRgb[i], a, (2.6f - 1.6f * u) * dp)
        }
    }

    private fun drawParticles(c: GlowCanvas, low: Boolean) {
        for (i in 0 until PARTICLES) {
            val life = plife[i]
            if (life <= 0f) continue
            val u = life / pmax[i]
            val a = if (u > 0.8f) (1f - u) * 5f else u
            val size = psize[i] * (0.4f + 0.6f * u)
            if (!low && size > 2.2f * dp) c.glow(px[i], py[i], size * 3.6f, prgb[i], 0.35f * a)
            c.circle(px[i], py[i], size * 0.7f, prgb[i], a.coerceIn(0f, 1f) * 0.95f)
        }
    }

    private fun drawFingers(c: GlowCanvas) {
        for (f in fingers) {
            if (!f.alive) continue
            val rgb = laneRgb[f.lane.coerceIn(0, laneCountValue - 1)]
            val breathe = 0.5f + 0.5f * sin(t * 6f)
            val radius = (96f + 22f * f.speed + 6f * breathe) * dp
            c.glow(f.x, f.y, radius, rgb, 0.50f)
            c.glow(f.x, f.y, radius * 0.42f, GlowColor.mix(rgb, GlowColor.WHITE, 0.55f), 0.65f)
            c.ring(f.x, f.y, (24f + 3f * breathe) * dp, GlowColor.mix(rgb, GlowColor.WHITE, 0.35f), 0.85f, 1.7f * dp)
            c.circle(f.x, f.y, 5.5f * dp, GlowColor.WHITE, 1f)
            // A fine beam snaps the finger to the lane it is playing.
            val ly = laneY(f.lane)
            if (abs(ly - f.y) > 2f * dp) c.line(f.x, f.y, f.x, ly, rgb, 0.55f, 1.4f * dp)
            c.circle(f.x, ly, 3.2f * dp, GlowColor.WHITE, 0.9f)
            // A chord shows its shape: a thread through every sounding string.
            if (f.laneN > 1) {
                var top = ly
                var bottom = ly
                for (k in 1 until f.laneN) {
                    val y = laneY(f.lanes[k])
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                c.line(f.x, top, f.x, bottom, GlowColor.WHITE, 0.45f, 1.6f * dp)
                for (k in 1 until f.laneN) {
                    val y = laneY(f.lanes[k])
                    val toneRgb = laneRgb[f.lanes[k].coerceIn(0, laneCountValue - 1)]
                    c.glow(f.x, y, 26f * dp, toneRgb, 0.55f)
                    c.ring(f.x, y, 8f * dp, GlowColor.mix(toneRgb, GlowColor.WHITE, 0.4f), 0.85f, 1.5f * dp)
                    c.circle(f.x, y, 3.2f * dp, GlowColor.WHITE, 0.9f)
                }
            }
        }
    }

    private fun drawLabels(c: GlowCanvas) {
        val n = laneCountValue
        val margin = 12f * dp
        for (i in 0 until n) {
            val role = laneRole[i]
            val lit = glow[i]
            val root = role == LaneRole.ROOT
            val size = (if (root) 13.5f else 10.5f) * dp * (1f + 0.22f * lit)
            val alpha = ((when (role) {
                LaneRole.ROOT -> 0.95f
                LaneRole.FIFTH, LaneRole.THIRD -> 0.70f
                LaneRole.COLOR -> 0.50f
            }) * idleVisibility + prox[i] * 0.6f + lit * 0.4f).coerceIn(0f, 1f)
            if (alpha < 0.03f) continue
            val rgb = if (root || lit > 0.2f) GlowColor.mix(laneRgb[i], GlowColor.WHITE, 0.2f + 0.3f * lit) else laneRgb[i]
            val y = laneY(i)
            c.text(laneName[i], margin + 16f * dp, y - 3f * dp, size, rgb, alpha, bold = root || lit > 0.3f)
            c.text(laneDegree[i].toString(), margin, y - 3f * dp, size * 0.72f, GlowColor.WHITE, alpha * 0.45f)
        }
    }

    private fun drawPopups(c: GlowCanvas) {
        for (i in 0 until POPUPS) {
            val age = popAge[i]
            if (age > POPUP_SECONDS) continue
            val u = age / POPUP_SECONDS
            val a = if (u < 0.12f) u / 0.12f else (1f - u) * 1.15f
            val rise = (1f - (1f - u) * (1f - u)) * 34f * dp
            val y = popY[i] - rise
            c.text(popText[i], popX[i], y, 22f * dp, popRgb[i], a.coerceIn(0f, 1f), bold = true, align = 1)
            if (popSub[i].isNotEmpty()) {
                c.text(popSub[i], popX[i], y + 17f * dp, 11.5f * dp, GlowColor.WHITE, (a * 0.7f).coerceIn(0f, 1f), align = 1)
            }
        }
    }

    companion object {
        const val MAX_FINGERS = 10
        const val MAX_CHORD = 6
        private val NO_LANES = IntArray(0)
        const val TRAIL = 34
        const val PULSES = 20
        const val RIPPLES = 24
        const val POPUPS = MAX_FINGERS
        const val PARTICLES = 360
        const val MOTES = 34
        const val GUIDE_MAX = 8
        const val GUIDE_SEGMENT_SAMPLES = 9
        const val FIREWORKS = 12
        const val STRING_SAMPLES = 44
        const val RIPPLE_SECONDS = 1.0f
        const val POPUP_SECONDS = 1.25f
        const val PULSE_SECONDS = 0.8f
        const val GHOST_SECONDS = 0.45f
        private const val MISS_RGB = 0xFF6FA8
    }
}
