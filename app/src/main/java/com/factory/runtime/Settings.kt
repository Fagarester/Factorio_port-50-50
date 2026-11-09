package com.factory.runtime

import android.content.Context
import kotlin.math.roundToInt

object Settings {
    const val PREFS = "pocketfoundry"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun x11(context: Context) = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    fun pauseInBackground(context: Context) = prefs(context).getBoolean("pauseInBackground", true)
    fun setPauseInBackground(context: Context, on: Boolean) = prefs(context).edit().putBoolean("pauseInBackground", on).apply()

    fun vibration(context: Context) = prefs(context).getBoolean("vibration", true)
    fun setVibration(context: Context, on: Boolean) = prefs(context).edit().putBoolean("vibration", on).apply()

    /** The release whose What's new already showed on start: it shows once. */
    fun updateShown(context: Context) = prefs(context).getString("updateShown", "")!!
    fun setUpdateShown(context: Context, version: String) = prefs(context).edit().putString("updateShown", version).apply()

    /** Whether Pocket Foundry asks GitHub for a newer version when it opens. */
    fun checkForUpdates(context: Context) = prefs(context).getBoolean("checkForUpdates", true)
    fun setCheckForUpdates(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("checkForUpdates", enabled).apply()

    fun fpsCounter(context: Context) = prefs(context).getBoolean("fpsCounter", false)
    fun setFpsCounter(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fpsCounter", on).apply()

    /** 60 or 30 fps; our SDL (runtime/sdl) paces the frames, so a change applies at once. */
    fun frameRate(context: Context) = prefs(context).getInt("frameRate", 60)
    fun setFrameRate(context: Context, fps: Int) {
        prefs(context).edit().putInt("frameRate", fps).apply()
        writeFrameRate(context)
    }
    fun writeFrameRate(context: Context) {
        val file = Paths(context).frameRate
        if (file.parentFile?.isDirectory == true) file.writeText("${frameRate(context)}\n")
    }

    /** Termux:X11's scale (game size = panel × 100 / scale) that gives 720 lines per 384 dp on any phone. */
    fun naturalResolutionScale(context: Context) = context.resources.displayMetrics.densityDpi / 3f
    fun defaultResolutionScale(context: Context) = naturalResolutionScale(context).roundToInt().let { if (it < 115) 100 else it }
    /** Two below the default, for the CPU driver: our SDL scales the game down with them, so the view stays the same. */
    fun resolutionScales(context: Context): List<Int> {
        val default = defaultResolutionScale(context)
        return listOf(100, 125, 150).filter { it <= default - 15 } + default + default * 3 / 2 + default * 2
    }
    fun startingResolutionScale(context: Context) = defaultResolutionScale(context) * if (Drivers.cpu(context)) 2 else 1
    fun resolutionScale(context: Context) = x11(context).getInt("displayScale", startingResolutionScale(context))
    fun setResolutionScale(context: Context, scale: Int) =
        x11(context).edit().putString("displayResolutionMode", "scaled").putInt("displayScale", scale).apply()

    val BORDERS = listOf(0, 10, 20)
    fun border(context: Context) = prefs(context).getInt("border", 0)
    fun setBorder(context: Context, dp: Int) = prefs(context).edit().putInt("border", dp).apply()

    /** Factorio's graphics-quality, missing from its menus: "medium" halves the sprites, ~600 MB less memory. */
    fun spriteQuality(context: Context) = prefs(context).getString("spriteQuality", null) ?: if (Drivers.cpu(context) || Drivers.gladio(context)) "medium" else "high" // Gladio: full-size sprites cost ~2.2 GB of atlases
    fun setSpriteQuality(context: Context, quality: String) = prefs(context).edit().putString("spriteQuality", quality).apply()

    /** Factorio's extra effects off (smoke, clouds, decoratives, shadows, terrain effects…): fewer pixels to draw. */
    fun lightGraphics(context: Context) = prefs(context).getBoolean("lightGraphics", Drivers.cpu(context))
    fun setLightGraphics(context: Context, on: Boolean) = prefs(context).edit().putBoolean("lightGraphics", on).apply()
    /** Whether the game's config has them off, so turning Light off can put them back once. */
    fun lightApplied(context: Context) = prefs(context).getBoolean("lightApplied", false)
    fun setLightApplied(context: Context, on: Boolean) = prefs(context).edit().putBoolean("lightApplied", on).apply()

    /** GraphicsPresets id ("custom": the game's own settings), for every driver. */
    fun graphicsPreset(context: Context) = prefs(context).getString("graphicsPreset", null) ?: GraphicsPresets.CUSTOM
    fun setGraphicsPreset(context: Context, id: String) = prefs(context).edit().putString("graphicsPreset", id).apply()

    /** Whether the config has Factorio's textures uncompressed (v42's phone driver on Mali), to put the default back once. */
    fun uncompressedApplied(context: Context) = prefs(context).getBoolean("uncompressedApplied", false)
    fun setUncompressedApplied(context: Context, on: Boolean) = prefs(context).edit().putBoolean("uncompressedApplied", on).apply()

    /** Until the player picks one: the driver for the phone's GPU (Drivers.forThisGpu). */
    fun driver(context: Context) = prefs(context).getString("driver", null) ?: Drivers.forThisGpu()
    fun hasDriver(context: Context) = prefs(context).contains("driver")
    fun setDriver(context: Context, id: String) = prefs(context).edit().putString("driver", id).apply()
    fun clearDriver(context: Context) = prefs(context).edit().remove("driver").apply()

    /** Moving to or from the CPU driver: the sprites, effects and resolution that suit it, or the GPU's. */
    fun applyDriverDefaults(context: Context) {
        val cpu = Drivers.cpu(context)
        setSpriteQuality(context, if (cpu) "medium" else "high")
        setLightGraphics(context, cpu)
        setResolutionScale(context, startingResolutionScale(context))
    }

    /** The Turnip block's own choice (Zink over a Vulkan driver, or Freedreno), kept while the Gladio block is active. */
    fun turnipBlockDriver(context: Context) = prefs(context).getString("turnipBlockDriver", null) ?: Drivers.ZINK
    fun setTurnipBlockDriver(context: Context, id: String) = prefs(context).edit().putString("turnipBlockDriver", id).apply()

    /** Where Gladio shows the game: "x11" = Termux:X11, "xserver" = the X server inside the app (as in r34). */
    fun gladioDisplay(context: Context) = prefs(context).getString("gladioDisplay", "x11")!!
    fun setGladioDisplay(context: Context, display: String) = prefs(context).edit().putString("gladioDisplay", display).apply()

    /** Gladio's server on the phone's ANGLE (GLES on Vulkan) instead of its GLES driver; Termux:X11 display only. */
    fun gladioAngle(context: Context) = prefs(context).getBoolean("gladioAngle", false)
    fun setGladioAngle(context: Context, on: Boolean) = prefs(context).edit().putBoolean("gladioAngle", on).apply()
    /** Test: Gladio's server as a program beside the game, on the phone's GLES driver through libhybris (Termux:X11 only). */
    fun gladioDirect(context: Context) = prefs(context).getBoolean("gladioDirect", false)
    fun setGladioDirect(context: Context, on: Boolean) = prefs(context).edit().putBoolean("gladioDirect", on).apply()

    /** How Gladio's frames reach the screen: "auto" (Adreno: AHardwareBuffers, other GPUs: shared memory), "dri3", "shm". */
    fun gladioPresent(context: Context) = prefs(context).getString("gladioPresent", "auto")!!
    fun setGladioPresent(context: Context, mode: String) = prefs(context).edit().putString("gladioPresent", mode).apply()

    fun driverOptions(context: Context) = prefs(context).getString("driverOptions", "")!!
    fun setDriverOptions(context: Context, options: String) = prefs(context).edit().putString("driverOptions", options).apply()

    fun requestLayoutEditor(context: Context) = prefs(context).edit().putBoolean("editLayout", true).apply()
    fun takeLayoutEditorRequest(context: Context): Boolean {
        if (!prefs(context).getBoolean("editLayout", false)) return false
        prefs(context).edit().putBoolean("editLayout", false).apply()
        return true
    }
}
