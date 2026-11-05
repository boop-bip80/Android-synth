package com.orbital.synth.ui.aurora

import android.content.Context
import android.content.SharedPreferences
import com.orbital.synth.core.stage.ChordAssist

/**
 * Small personal preferences for the Aurora stage that are not part of the validated
 * stage-settings file: harmony mode, whether the melody ribbon shows, the look (dark, normal or
 * automatic) and the best star rating the player has earned for each song. Nothing here is
 * personal data.
 */
internal class AuroraPrefs private constructor(private val prefs: SharedPreferences?) {
    // A companion function (not a lambda): a delegating constructor call must not touch `this`.
    constructor(context: Context) : this(openPrefs(context))

    /** Preferences that keep nothing; used by previews and tests. */
    constructor() : this(null as SharedPreferences?)

    private var memoryChord = ChordAssist.OFF
    private var memoryRibbon = true
    private var memoryTheme = StageThemeMode.DARK
    private val memoryStars = HashMap<String, Int>()

    var chord: ChordAssist
        get() {
            val stored = prefs?.getString(KEY_CHORD, null) ?: return memoryChord
            return ChordAssist.entries.firstOrNull { it.name == stored } ?: ChordAssist.OFF
        }
        set(value) {
            memoryChord = value
            prefs?.edit()?.putString(KEY_CHORD, value.name)?.apply()
        }

    var ribbonVisible: Boolean
        get() = prefs?.getBoolean(KEY_RIBBON, true) ?: memoryRibbon
        set(value) {
            memoryRibbon = value
            prefs?.edit()?.putBoolean(KEY_RIBBON, value)?.apply()
        }

    /** DARK by default, so the stage looks exactly as it always has until the player chooses. */
    var themeMode: StageThemeMode
        get() = prefs?.getString(KEY_THEME, null)?.let { StageThemeMode.fromName(it) } ?: memoryTheme
        set(value) {
            memoryTheme = value
            prefs?.edit()?.putString(KEY_THEME, value.name)?.apply()
        }

    fun bestStars(songId: String): Int =
        (prefs?.getInt(KEY_STARS + songId, 0) ?: memoryStars[songId] ?: 0).coerceIn(0, 3)

    /** Records a result; only an improvement is kept. Returns true when it is a new best. */
    fun recordStars(songId: String, stars: Int): Boolean {
        val value = stars.coerceIn(0, 3)
        if (value <= bestStars(songId)) return false
        memoryStars[songId] = value
        prefs?.edit()?.putInt(KEY_STARS + songId, value)?.apply()
        return true
    }

    private companion object {
        fun openPrefs(context: Context): SharedPreferences? = try {
            context.applicationContext.getSharedPreferences("orbital_aurora", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }

        const val KEY_CHORD = "chord"
        const val KEY_RIBBON = "ribbon"
        const val KEY_STARS = "stars_"
        const val KEY_THEME = "theme"
    }
}
