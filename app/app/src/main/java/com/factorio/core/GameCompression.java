package com.factorio.core;

/** How the game folder is shrunk while it is copied into the rootfs (chosen once on the first start). */
public abstract class GameCompression {
    public static final int ORIGINAL = 0;   // plain copy
    public static final int LOW = 1;        // textures about 5x smaller
    public static final int HIGH = 2;       // textures about 10x smaller

    /** Default shared preferences key (int). */
    public static final String PREF_KEY = "game_compression";

    /** Wanted ratio original size / new size for PNG textures, 0 = do not touch. */
    public static int textureRatio(int mode) {
        switch (mode) {
            case LOW: return 5;
            case HIGH: return 10;
            default: return 0;
        }
    }

    /** The texture palette is never smaller than this, even when the ratio is not reached. */
    public static int textureMinColors(int mode) {
        return mode == HIGH ? 16 : 32;
    }
}
