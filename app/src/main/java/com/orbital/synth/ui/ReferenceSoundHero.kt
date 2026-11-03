package com.orbital.synth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import kotlin.math.roundToInt

private val HeroObsidian = Color(0xFF01050F)
private val HeroGlass = Color(0xA507142C)

/**
 * Portrait SOUND composition, reconstructed from the *actual original* PNG.
 *
 * One actual GPU plasma orb plus ONE translucent XY ribbon overlays its lower
 * edge, then five legitimate host-driven macro knobs and four honest saved
 * configuration previews. No separate synth, fake FFT, synthetic meter,
 * decorative control that pretends to affect DSP, or duplicate rendering.
 *
 * This reusable block intentionally does not own MainActivity, transport,
 * keypad, patch persistence, tabs, or any other host integration.
 */
@Composable
fun ReferenceSoundHero(
    parameters: Map<ParameterId, Float>,
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    onParameterChange: (ParameterId, Float) -> Unit,
    modifier: Modifier = Modifier,
    audioRms: Float = 0f,
    audioPeak: Float = 0f,
    compact: Boolean = true,
    patchName: String = "AURORA",
    engineName: String = "ORBITAL WT",
    onOpenPatchLibrary: (() -> Unit)? = null,
    onPreviousPatch: (() -> Unit)? = null,
    onNextPatch: (() -> Unit)? = null,
    onOpenEngine: (() -> Unit)? = null,
) {
    val safeMorph = ReferenceSoundUiModel.normalized(parameters[ParameterId.MORPH] ?: .5f)
    val safeMovement = ReferenceSoundUiModel.normalized(parameters[ParameterId.MOVEMENT] ?: .5f)
    val latestChange by rememberUpdatedState(onParameterChange)
    var fingerPressed by remember { mutableStateOf(false) }
    var touchX by remember { mutableFloatStateOf(.5f) }
    var touchY by remember { mutableFloatStateOf(.5f) }
    val height: Dp = if (compact) 230.dp else 278.dp

    fun update(x: Float, y: Float) {
        touchX = ReferenceSoundUiModel.normalized(x)
        touchY = ReferenceSoundUiModel.normalized(y)
        latestChange(ParameterId.MORPH, touchX)
        latestChange(ParameterId.MOVEMENT, touchY)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(HeroObsidian)
            .testTag("referenceSoundHero"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .testTag("referenceSoundHeroOrb"),
        ) {
            OrbitalPlasmaField(
                morph = safeMorph,
                movement = safeMovement,
                audioRms = audioRms,
                audioPeak = audioPeak,
                touchX = touchX,
                touchY = touchY,
                pressed = fingerPressed,
                modifier = Modifier.fillMaxSize(),
            )
            // Above plasma, below real clickable HUD and real interactive XY:
            // any finger on the orb edits the same audible normalized parameters.
            Box(
                Modifier
                    .matchParentSize()
                    .testTag("referenceHeroOrbInput")
                    .semantics {
                        contentDescription = "Touch glowing synth orb to edit morph " +
                            (safeMorph * 100f).roundToInt() +
                            " percent and movement " + (safeMovement * 100f).roundToInt() +
                            " percent. Swipe horizontally and vertically"
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { position ->
                                fingerPressed = true
                                update(
                                    (position.x / size.width).coerceIn(0f, 1f),
                                    (1f - position.y / size.height).coerceIn(0f, 1f),
                                )
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    fingerPressed = false
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { position ->
                                fingerPressed = true
                                update(
                                    (position.x / size.width).coerceIn(0f, 1f),
                                    (1f - position.y / size.height).coerceIn(0f, 1f),
                                )
                            },
                            onDragEnd = { fingerPressed = false },
                            onDragCancel = { fingerPressed = false },
                            onDrag = { change, _ ->
                                update(
                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                    (1f - change.position.y / size.height).coerceIn(0f, 1f),
                                )
                                change.consume()
                            },
                        )
                    },
            )
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 6.dp)
                    .testTag("referenceHeroPatchEngineHud"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        "WAVETABLE / PATCH",
                        color = OrbitalPalette.Muted,
                        fontSize = 8.sp,
                        letterSpacing = 1.1.sp,
                    )
                    Text(
                        patchName,
                        modifier = (if (onOpenPatchLibrary != null) Modifier.clickable(
                            role = Role.Button, onClick = onOpenPatchLibrary,
                        ) else Modifier).testTag("referenceHeroPatchName"),
                        color = OrbitalPalette.Text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (onPreviousPatch != null) {
                            ReferenceHeroArrow("‹", "Previous patch",
                                onPreviousPatch, "referenceHeroPreviousPatch")
                        }
                        if (onNextPatch != null) {
                            ReferenceHeroArrow("›", "Next patch",
                                onNextPatch, "referenceHeroNextPatch")
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        "ENGINE",
                        color = OrbitalPalette.Muted,
                        fontSize = 8.sp,
                        letterSpacing = 1.1.sp,
                    )
                    Text(
                        engineName,
                        modifier = (if (onOpenEngine != null) Modifier.clickable(
                            role = Role.Button, onClick = onOpenEngine,
                        ) else Modifier).testTag("referenceHeroEngineName"),
                        color = OrbitalPalette.Cyan,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ReferenceExpressionRibbon(
                morph = safeMorph,
                movement = safeMovement,
                onChange = { x, y -> update(x, y) },
                audioRms = audioRms,
                transparentBackground = true,
                height = if (compact) 88.dp else 110.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 7.dp)
                    .testTag("referenceHeroXYUnderOrb"),
            )
        }
        // Do not render a second XY card under the orb. All five macros and
        // four tiny previews remain accessible and connected to the host.
        ReferenceSoundControls(
            parameters = parameters,
            lfos = lfos,
            routes = routes,
            onParameterChange = onParameterChange,
            audioRms = audioRms,
            compact = compact,
            showExpressionRibbon = false,
        )
    }
}

@Composable
private fun ReferenceHeroArrow(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    tag: String,
) {
    Box(
        Modifier
            .size(39.dp)
            .background(HeroGlass, RoundedCornerShape(11.dp))
            .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .32f),
                RoundedCornerShape(11.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = OrbitalPalette.Cyan, fontSize = 23.sp)
    }
}
