package com.orbital.synth.ui.aurora

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.jazz.JazzStandard
import com.orbital.synth.core.music.MusicalScales
import com.orbital.synth.core.stage.ChordAssist
import com.orbital.synth.core.stage.GuideResult
import com.orbital.synth.core.stage.NoteSpelling
import com.orbital.synth.core.stage.Song
import com.orbital.synth.core.stage.StageKey
import com.orbital.synth.core.stage.StageScales
import com.orbital.synth.core.stage.glow.AuroraPalette
import com.orbital.synth.ui.TouchScale

internal fun rgbColor(rgb: Int, alpha: Float = 1f): Color =
    Color(0xFF000000.toInt() or (rgb and 0xFFFFFF)).copy(alpha = alpha)

/** The modes offered on the stage, in the order a learner meets them. */
internal val AuroraModes: List<TouchScale> =
    StageScales.OFFERED.map { TouchScale.fromScaleId(it.id) }

internal fun auroraKeyOf(tonic: Int, scale: TouchScale): StageKey =
    StageKey(tonic.coerceIn(0, 11), MusicalScales.byId(scale.scaleId) ?: MusicalScales.MAJOR)

internal fun ordinalLabel(n: Int): String = when (n) {
    1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th"
}

/** Frosted-glass button: the common shape of every stage control. */
@Composable
internal fun GlassPill(
    text: String,
    chosen: Boolean,
    accent: Color,
    tag: String,
    description: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    minWidth: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val pal = LocalStagePalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(
                if (chosen) Brush.verticalGradient(listOf(accent.copy(alpha = .34f), accent.copy(alpha = .14f)))
                else Brush.verticalGradient(listOf(pal.glass.copy(alpha = .92f), pal.ink.copy(alpha = .88f))),
                shape,
            )
            .border(1.dp, accent.copy(alpha = if (chosen) .95f else .30f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = description; selected = chosen; role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (chosen) pal.text else pal.soft,
            fontSize = fontSize,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = .9.sp,
            maxLines = 1,
        )
    }
}

/**
 * The top of the stage. Row one: exit, the current mode with step arrows, focus and
 * configuration. Row two: the twelve keys, each tinted with its own colour.
 */
@Composable
internal fun AuroraHeader(
    tonic: Int,
    scale: TouchScale,
    accent: Color,
    onTonic: (Int) -> Unit,
    onScale: (TouchScale) -> Unit,
    onExit: () -> Unit,
    onFocus: () -> Unit,
    onConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalStagePalette.current
    val key = auroraKeyOf(tonic, scale)
    val modeIndex = AuroraModes.indexOf(scale).coerceAtLeast(0)
    val coreScale = key.scale
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassPill(
                "‹ EXIT", false, accent, "immersiveExit", "Exit immersive performance",
                fontSize = 11.sp, onClick = onExit,
            )
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                        onScale(AuroraModes[(modeIndex + AuroraModes.size - 1) % AuroraModes.size])
                    }.testTag("auroraModePrev").semantics { contentDescription = "Previous mode" },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Column(
                    Modifier.weight(1f).testTag("auroraModeLabel").semantics {
                        contentDescription = "Key ${key.tonicName} ${StageScales.shortName(coreScale)}. " +
                            StageScales.mood(coreScale)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        key.displayName, color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        StageScales.mood(coreScale).uppercase(), color = accent.copy(alpha = .85f),
                        fontSize = 8.5.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                        onScale(AuroraModes[(modeIndex + 1) % AuroraModes.size])
                    }.testTag("auroraModeNext").semantics { contentDescription = "Next mode" },
                    contentAlignment = Alignment.Center,
                ) { Text("›", color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
            GlassPill(
                "◉", false, accent, "stageFocusEnter",
                "Enter clean canvas focus mode. Android Back reveals controls",
                fontSize = 15.sp, minWidth = 48.dp, onClick = onFocus,
            )
            GlassPill(
                "◈", false, accent, "stageConfigOpen", "Configure touch performance stage",
                fontSize = 15.sp, minWidth = 48.dp, onClick = onConfig,
            )
        }
        KeyRow(tonic, coreScale.id, accent, onTonic)
    }
}

@Composable
private fun KeyRow(tonic: Int, scaleId: String, accent: Color, onTonic: (Int) -> Unit) {
    val pal = LocalStagePalette.current
    val scroll = rememberScrollState()
    val chip = 48.dp
    val gap = 6.dp
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewportPx = with(density) { maxWidth.toPx() }
        val stepPx = with(density) { (chip + gap).toPx() }
        // Keep the chosen key centred so it is never hidden off-screen.
        LaunchedEffect(tonic, viewportPx) {
            val target = (tonic * stepPx - viewportPx / 2f + stepPx / 2f).toInt().coerceAtLeast(0)
            scroll.animateScrollTo(target)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            val coreScale = MusicalScales.byId(scaleId) ?: MusicalScales.MAJOR
            for (i in 0..11) {
                val color = pal.tint(AuroraPalette(StageKey(i, coreScale)).root)
                val name = NoteSpelling.tonicName(i)
                val picked = i == tonic
                Box(
                    Modifier
                        .size(chip)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (picked) Brush.verticalGradient(listOf(color.copy(alpha = .45f), color.copy(alpha = .16f)))
                            else Brush.verticalGradient(listOf(pal.glass.copy(alpha = .92f), pal.ink.copy(alpha = .88f))),
                            RoundedCornerShape(16.dp),
                        )
                        .border(if (picked) 1.6.dp else 1.dp, color.copy(alpha = if (picked) 1f else .38f), RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { onTonic(i) }
                        .testTag("auroraKey-$i")
                        .semantics { contentDescription = "Key $name"; selected = picked; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            name, color = if (picked) pal.text else color.copy(alpha = .95f),
                            fontSize = 15.sp, fontWeight = if (picked) FontWeight.ExtraBold else FontWeight.SemiBold,
                        )
                        Box(Modifier.padding(top = 2.dp).size(width = if (picked) 18.dp else 8.dp, height = 3.dp)
                            .clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = if (picked) 1f else .5f)))
                    }
                }
            }
        }
    }
}

/** Live status of a guided song for the footer. */
internal class GuideHud(
    val title: String,
    val progress: Float,
    val combo: Int,
    val listening: Boolean,
)

/** Bottom tools: songs, harmony and register, or the live guide readout while learning. */
@Composable
internal fun AuroraFooter(
    accent: Color,
    chord: ChordAssist,
    bassOctave: Int,
    guide: GuideHud?,
    onSongs: () -> Unit,
    onChord: () -> Unit,
    onOctave: (Int) -> Unit,
    onListen: () -> Unit,
    onEndGuide: () -> Unit,
    modifier: Modifier = Modifier,
    /** Patch name and stepping; when the host provides them a voice stepper replaces the octave pair. */
    voiceName: String = "",
    onPreviousVoice: (() -> Unit)? = null,
    onNextVoice: (() -> Unit)? = null,
) {
    val pal = LocalStagePalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (guide != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).testTag("auroraGuideHud").semantics {
                    contentDescription = "Learning ${guide.title}, ${(guide.progress * 100).toInt()} percent, combo ${guide.combo}"
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            guide.title.uppercase(), color = pal.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (guide.combo >= 2) {
                            Text("×${guide.combo}", color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(pal.hairline)) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(guide.progress.coerceIn(0.01f, 1f)).clip(RoundedCornerShape(3.dp))
                                .background(Brush.horizontalGradient(listOf(accent, pal.gold))),
                        )
                    }
                }
                GlassPill(
                    if (guide.listening) "♪ …" else "▶ LISTEN", false, pal.gold, "auroraListen",
                    "Hear this song played on the stage", fontSize = 10.sp, onClick = onListen,
                )
                GlassPill("✕", false, accent, "auroraEndGuide", "End this lesson", fontSize = 14.sp, onClick = onEndGuide)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val voiceStepping = onPreviousVoice != null && onNextVoice != null
                GlassPill(
                    "♪ SONGS", false, pal.gold, "auroraSongs", "Open songs to learn",
                    modifier = Modifier.weight(1f), onClick = onSongs,
                )
                GlassPill(
                    chord.label, chord != ChordAssist.OFF, accent, "auroraChord",
                    "Harmony ${chord.label.lowercase()}: ${chord.blurb}. Tap to change",
                    modifier = Modifier.weight(1f), onClick = onChord,
                )
                if (voiceStepping) {
                    VoiceStepper(
                        voiceName, accent, { onPreviousVoice?.invoke() }, { onNextVoice?.invoke() },
                        Modifier.weight(1.8f),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GlassPill("−", false, accent, "auroraOctaveDown", "Lower the bass register", fontSize = 16.sp, onClick = { onOctave(bassOctave - 1) })
                        Text(
                            "C$bassOctave", color = pal.soft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(30.dp).testTag("auroraOctaveLabel"),
                            textAlign = TextAlign.Center, maxLines = 1,
                        )
                        GlassPill("+", false, accent, "auroraOctaveUp", "Raise the bass register", fontSize = 16.sp, onClick = { onOctave(bassOctave + 1) })
                    }
                }
            }
        }
    }
}

/** `‹ NAME ›`: step through the instrument's voices without leaving the stage. */
@Composable
private fun VoiceStepper(
    name: String,
    accent: Color,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalStagePalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(pal.glass.copy(alpha = .92f), pal.ink.copy(alpha = .88f))), shape)
            .border(1.dp, accent.copy(alpha = .35f), shape)
            .testTag("auroraVoice")
            .semantics { contentDescription = "Voice $name" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clickable(role = Role.Button, onClick = onPrevious)
                .testTag("auroraVoicePrev").semantics { contentDescription = "Previous voice" },
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text(
            name.uppercase(), color = pal.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = .8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(46.dp).clickable(role = Role.Button, onClick = onNext)
                .testTag("auroraVoiceNext").semantics { contentDescription = "Next voice" },
            contentAlignment = Alignment.Center,
        ) { Text("›", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

/**
 * A sheet of songs: LEARN lists the follow-the-light lessons; JAZZ lists the Real Book
 * catalogue (when [jazz] is supplied). Tapping LEARN starts a lesson; tapping PLAY on a jazz
 * standard loads its sound and key onto the stage.
 */
@Composable
internal fun AuroraSongSheet(
    songs: List<Song>,
    bestStars: (String) -> Int,
    accent: Color,
    onLearn: (Song) -> Unit,
    onListen: (Song) -> Unit,
    onClose: () -> Unit,
    jazz: List<JazzStandard> = emptyList(),
    onPlayJazz: ((JazzStandard) -> Unit)? = null,
    startOnJazz: Boolean = false,
    themeMode: StageThemeMode = StageThemeMode.DARK,
    onTheme: (() -> Unit)? = null,
) {
    val pal = LocalStagePalette.current
    val hasJazz = jazz.isNotEmpty() && onPlayJazz != null
    var showJazz by remember { mutableStateOf(startOnJazz && hasJazz) }
    val jazzTab = hasJazz && showJazz
    Box(
        Modifier.fillMaxSize().background(pal.scrim)
            .clickable(onClick = onClose).testTag("auroraSongScrim"),
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.78f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(pal.sheetTop, pal.ink)))
                .border(1.dp, pal.gold.copy(alpha = .35f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag("auroraSongSheet")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (jazzTab) "JAZZ STANDARDS" else "LEARN A SONG",
                        color = pal.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    )
                    Text(
                        if (jazzTab) "The Real Book, Vol. 1. PLAY loads the tune's own sound and key."
                        else "Follow the golden light. Wrong notes never stop the music.",
                        color = pal.soft, fontSize = 11.sp,
                    )
                }
                if (onTheme != null) {
                    GlassPill(
                        themeMode.glyph, false, accent, "auroraTheme",
                        "Look: ${themeMode.label}. Tap to change between dark, normal and automatic",
                        fontSize = 15.sp, minWidth = 48.dp, onClick = onTheme,
                    )
                }
                GlassPill("DONE", true, accent, "auroraSongClose", "Close the song list", fontSize = 10.sp, onClick = onClose)
            }
            if (hasJazz) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        "LEARN", !jazzTab, pal.gold, "auroraTabLearn", "Show songs to learn",
                        modifier = Modifier.weight(1f), fontSize = 11.sp, onClick = { showJazz = false },
                    )
                    GlassPill(
                        "JAZZ", jazzTab, pal.gold, "auroraTabJazz", "Show jazz standards from the Real Book",
                        modifier = Modifier.weight(1f), fontSize = 11.sp, onClick = { showJazz = true },
                    )
                }
            }
            if (jazzTab) {
                JazzCatalogueList(
                    tunes = jazz, accent = accent, onPlay = { onPlayJazz?.invoke(it) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (song in songs) SongRow(song, bestStars(song.id), accent, onLearn, onListen)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song, stars: Int, accent: Color,
    onLearn: (Song) -> Unit, onListen: (Song) -> Unit,
) {
    val pal = LocalStagePalette.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(pal.glass, pal.ink.copy(alpha = .9f))), shape)
            .border(1.dp, pal.hairline, shape)
            .testTag("auroraSong-${song.id}")
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(song.title, color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.origin, color = pal.soft.copy(alpha = .75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "●".repeat(song.difficulty) + "○".repeat(3 - song.difficulty),
                    color = accent, fontSize = 8.sp, letterSpacing = 1.sp,
                    modifier = Modifier.semantics { contentDescription = "Difficulty ${song.difficulty} of 3" },
                )
                Text("${song.noteCount} notes", color = pal.soft.copy(alpha = .7f), fontSize = 9.sp)
                Text(
                    "★".repeat(stars) + "☆".repeat(3 - stars), color = pal.gold, fontSize = 11.sp,
                    modifier = Modifier.semantics { contentDescription = "Best result $stars of 3 stars" },
                )
            }
        }
        GlassPill("♪", false, pal.soft, "auroraListenSong-${song.id}", "Listen to ${song.title}", fontSize = 15.sp, onClick = { onListen(song) })
        GlassPill("LEARN", true, pal.gold, "auroraLearn-${song.id}", "Learn ${song.title}", fontSize = 10.sp, onClick = { onLearn(song) })
    }
}

/** Shown when the last note of a lesson is played. */
@Composable
internal fun AuroraResultCard(
    song: Song,
    result: GuideResult,
    isBest: Boolean,
    onAgain: () -> Unit,
    onSongs: () -> Unit,
    onClose: () -> Unit,
) {
    val pal = LocalStagePalette.current
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(shape)
                .background(Brush.verticalGradient(listOf(pal.sheetTop.copy(alpha = .92f), pal.ink.copy(alpha = .94f))), shape)
                .border(1.dp, pal.gold.copy(alpha = .6f), shape)
                .testTag("auroraResult")
                .semantics { contentDescription = "Lesson complete: ${result.stars} of 3 stars" }
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("★".repeat(result.stars) + "☆".repeat(3 - result.stars), color = pal.gold, fontSize = 38.sp)
            Text(
                when (result.stars) { 3 -> "FLAWLESS"; 2 -> "BEAUTIFULLY PLAYED"; else -> "YOU DID IT" },
                color = pal.text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
            )
            Text(song.title, color = pal.soft, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(
                "${(result.accuracy * 100).toInt()}% accuracy  ·  best streak ×${result.bestCombo}",
                color = pal.soft.copy(alpha = .85f), fontSize = 11.sp,
            )
            if (isBest) Text("NEW PERSONAL BEST", color = pal.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("AGAIN", true, pal.gold, "auroraAgain", "Play this song again", fontSize = 10.sp, onClick = onAgain)
                GlassPill("SONGS", false, pal.gold, "auroraMoreSongs", "Choose another song", fontSize = 10.sp, onClick = onSongs)
                GlassPill("DONE", false, pal.soft, "auroraResultClose", "Close results", fontSize = 10.sp, onClick = onClose)
            }
        }
    }
}
