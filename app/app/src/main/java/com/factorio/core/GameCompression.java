package com.factorio.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** How the game folder is shrunk while it is copied into the rootfs (chosen once on the first start). */
public abstract class GameCompression {
    public static final int ORIGINAL = 0;   // plain copy
    public static final int LOW = 1;        // textures about 2x smaller (almost no visible loss)
    public static final int HIGH = 2;       // textures about 5x smaller

    /** Default shared preferences key (int). */
    public static final String PREF_KEY = "game_compression";

    /** Wanted ratio original size / new size for PNG textures, 0 = do not touch. */
    public static int textureRatio(int mode) {
        switch (mode) {
            case LOW: return 2;
            case HIGH: return 5;
            default: return 0;
        }
    }

    /** The texture palette is never smaller than this, even when the ratio is not reached. */
    public static int textureMinColors(int mode) {
        return mode == HIGH ? 32 : 128;
    }

    /** Palette refinement passes (k-means), makes colors much closer to the original. */
    public static int textureKmeans(int mode) {
        return 3;
    }

    /** Error diffusion strength 0..100: only where the ratio leaves room for it (the 2x mode). */
    public static int textureDither(int mode) {
        return mode == LOW ? 70 : 0;
    }

    private static final Pattern LUT_NAME = Pattern.compile("(^|[-_/])lut([-_.]|$)");

    /**
     * PNG files that are never shrunk: the menu / GUI atlases (data/core/graphics, anything named "gui"), the color
     * lookup tables (LUT) which must stay exact, menu / logo / background images, and the small icon sheets.
     * The game folder is the root, paths use "/" (e.g. "/data/core/graphics/gui-new.png").
     */
    public static boolean isProtected(String relativePath) {
        String path = relativePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (!path.startsWith("/")) path = "/" + path;
        String name = path.substring(path.lastIndexOf('/') + 1);

        if (path.contains("/core/graphics/")) return true;                 // menu, GUI, cursors, color_luts, shared graphics
        if (path.contains("/color_luts/") || LUT_NAME.matcher(path).find()) return true;
        if (path.contains("/gui") || name.contains("gui")) return true;    // gui, gui-new, space-age gui...
        if (name.contains("menu") || name.contains("background") || name.contains("logo")) return true;
        if (path.contains("/graphics/icons/") || path.contains("/graphics/technology/")) return true;
        return false;
    }
}
