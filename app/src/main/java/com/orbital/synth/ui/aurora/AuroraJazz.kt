package com.orbital.synth.ui.aurora

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.jazz.JazzCatalogue
import com.orbital.synth.core.jazz.JazzStandard

/** One line of the catalogue list: an A-Z heading or a tune. */
internal sealed interface JazzListRow {
    data class Heading(val letter: Char) : JazzListRow
    data class Tune(val tune: JazzStandard) : JazzListRow
}

/** Inserts a heading wherever the book's own section changes. Order is preserved. */
internal fun jazzRows(tunes: List<JazzStandard>): List<JazzListRow> {
    val rows = ArrayList<JazzListRow>(tunes.size + 27)
    var section: Char? = null
    for (tune in tunes) {
        if (tune.section != section) {
            section = tune.section
            rows += JazzListRow.Heading(tune.section)
        }
        rows += JazzListRow.Tune(tune)
    }
    return rows
}

/** What the screen reader says for a tune's PLAY button. */
internal fun jazzPlayDescription(tune: JazzStandard): String = buildString {
    append("Play ").append(tune.title)
    tune.key?.let { append(" in ").append(it.label) }
    append(" with ").append(tune.voice.label)
}

/**
 * The searchable jazz catalogue. Each PLAY loads the tune's own default sound and, when the
 * catalogue records one, its home key. The catalogue carries no melodies or charts: the player
 * supplies the music on the stage.
 */
@Composable
internal fun JazzCatalogueList(
    tunes: List<JazzStandard>,
    accent: Color,
    onPlay: (JazzStandard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalStagePalette.current
    var query by remember { mutableStateOf("") }
    val results = remember(query, tunes) { JazzCatalogue.search(query, tunes) }
    val rows = remember(results) { jazzRows(results) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        JazzSearchField(query, accent) { query = it }
        Text(
            if (query.isBlank()) "${tunes.size} tunes" else "${results.size} of ${tunes.size} tunes",
            color = pal.soft.copy(alpha = .8f), fontSize = 10.sp, letterSpacing = 1.sp,
            modifier = Modifier.testTag("auroraJazzCount"),
        )
        if (rows.isEmpty()) {
            Text(
                "No tune matches “${query.trim()}”.",
                color = pal.soft, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp).testTag("auroraJazzEmpty"),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("auroraJazzList"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = rows,
                    key = { row ->
                        when (row) {
                            is JazzListRow.Heading -> "heading-${row.letter}"
                            is JazzListRow.Tune -> row.tune.id
                        }
                    },
                ) { row ->
                    when (row) {
                        is JazzListRow.Heading -> Text(
                            row.letter.toString(), color = pal.gold, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                                .testTag("auroraJazzSection-${row.letter}")
                                .semantics { contentDescription = "Section ${row.letter}" },
                        )
                        is JazzListRow.Tune -> JazzRow(row.tune, accent, onPlay)
                    }
                }
            }
        }
    }
}

@Composable
private fun JazzSearchField(query: String, accent: Color, onChange: (String) -> Unit) {
    val pal = LocalStagePalette.current
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = pal.text, fontSize = 14.sp),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(shape)
            .background(pal.glass, shape)
            .border(1.dp, accent.copy(alpha = .35f), shape)
            .testTag("auroraJazzSearch")
            .semantics { contentDescription = "Search jazz standards by title, composer or key" },
        decorationBox = { inner ->
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) {
                        Text("Search title, composer, key…", color = pal.soft.copy(alpha = .7f), fontSize = 14.sp)
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { onChange("") }
                            .testTag("auroraJazzSearchClear")
                            .semantics { contentDescription = "Clear search" },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
        },
    )
}

@Composable
private fun JazzRow(tune: JazzStandard, accent: Color, onPlay: (JazzStandard) -> Unit) {
    val pal = LocalStagePalette.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(pal.glass, pal.ink.copy(alpha = .9f))), shape)
            .border(1.dp, pal.hairline, shape)
            .testTag("auroraJazz-${tune.id}")
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tune.title, color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (tune.composer.isNotBlank()) {
                Text(
                    tune.composer, color = pal.soft.copy(alpha = .85f), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOf(tune.summary, tune.voice.label).filter { it.isNotEmpty() }.joinToString("  ·  "),
                color = accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        GlassPill(
            "PLAY", true, pal.gold, "auroraJazzPlay-${tune.id}", jazzPlayDescription(tune),
            fontSize = 10.sp, onClick = { onPlay(tune) },
        )
    }
}
