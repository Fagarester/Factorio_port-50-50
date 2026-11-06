package com.factory.runtime

/**
 * Settings → graphics preset: Factorio's sprite resolution and effects written into its config on every start,
 * whatever the driver (Turnip/Zink, Freedreno, Gladio on X11 or the X server). "custom" leaves them to the game's
 * own menu (and to the driver defaults: sprite quality, light graphics).
 */
object GraphicsPresets {
    const val CUSTOM = "custom"

    /** The ids in menu order, lowest first after "custom". */
    val IDS = listOf(CUSTOM, "minimum", "low", "medium", "high", "maximum")

    class Preset(val sprites: String, val on: Set<String>)

    /** Every Factorio [graphics] switch a preset sets: on when in the preset's set, else off. */
    val KEYS = listOf(
        "show-smoke", "show-particles", "show-clouds", "show-fog", "show-space-dust", "show-decoratives",
        "show-item-shadows", "show-inserter-shadows", "elevated-rail-shadows", "additional-terrain-effects",
        "high-quality-terrain", "show-animated-water", "show-tree-distortion", "show-animated-ghosts",
        "high-quality-animations", "show-fullscreen-flash")

    private val LOW = setOf("show-decoratives", "show-item-shadows", "show-inserter-shadows", "elevated-rail-shadows",
                            "show-fullscreen-flash")
    // smoke and particles (explosions, dying biters, fire) are the medium preset's cost
    private val MEDIUM = LOW + setOf("show-smoke", "show-particles", "high-quality-terrain", "show-animated-water",
                                     "show-tree-distortion", "show-animated-ghosts")
    private val HIGH = MEDIUM + setOf("show-clouds", "show-fog", "show-space-dust", "additional-terrain-effects")

    private val PRESETS = mapOf(
        "minimum" to Preset("medium", emptySet()),
        "low" to Preset("medium", LOW),
        "medium" to Preset("medium", MEDIUM),
        "high" to Preset("high", HIGH),
        "maximum" to Preset("high", KEYS.toSet()))

    /** null for "custom" (or an unknown id). */
    fun find(id: String): Preset? = PRESETS[id]
}
