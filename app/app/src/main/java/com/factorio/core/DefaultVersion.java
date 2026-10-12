package com.factorio.core;

import java.util.Locale;

public abstract class DefaultVersion {
    public static final String TURNIP = "26.2.0";
    public static final String VORTEK = "2.1";
    public static final String ZINK = "22.2.5";
    public static final String VIRGL = "23.1.9";
    public static final String GLADIO = "1.0";
    public static final String OPENGL = "1.0";

    public static String valueOf(String name) {
        switch (name.toUpperCase(Locale.ENGLISH)) {
            case "TURNIP": return TURNIP;
            case "VORTEK": return VORTEK;
            case "ZINK": return ZINK;
            case "VIRGL": return VIRGL;
            case "GLADIO": return GLADIO;
            case "OPENGL": return OPENGL;
            default: return "0.0";
        }
    }
}
