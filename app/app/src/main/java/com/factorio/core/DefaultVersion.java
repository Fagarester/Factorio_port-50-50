package com.factorio.core;

import java.util.Locale;

public abstract class DefaultVersion {
    public static final String TURNIP = "26.2.0";
    public static final String ZINK = "26.2.0";
    public static final String OPENGL = "1.0";
    // legacy block (original Winlator stack)
    public static final String VORTEK = "2.1";
    public static final String GLADIO = "1.2"; // asset graphics_driver/gladio-1.2.tzst (CI: build-gladio.yml; the first file is the old 1.1 with a patched glXQueryExtension)
    public static final String ZINK_LEGACY = "22.2.5";

    public static String valueOf(String name) {
        switch (name.toUpperCase(Locale.ENGLISH)) {
            case "TURNIP": return TURNIP;
            case "ZINK": return ZINK;
            case "OPENGL": return OPENGL;
            case "VORTEK": return VORTEK;
            case "GLADIO": return GLADIO;
            case "ZINKOLD": return ZINK_LEGACY;
            default: return "0.0";
        }
    }
}
