package com.factory.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** The game's own settings: X screen size and extra environment variables for the game process. */
public final class GameSettings {
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    private static final String KEY_SCREEN_SIZE = "game_screen_size";
    private static final String KEY_ENV_VARS = "game_env_vars";
    /** Set once the first start (runtime + resolution + compression) is done. */
    private static final String KEY_CONFIGURED = "default_container_created";

    private GameSettings() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isConfigured(Context context) {
        return prefs(context).getBoolean(KEY_CONFIGURED, false);
    }

    public static void setConfigured(Context context) {
        prefs(context).edit().putBoolean(KEY_CONFIGURED, true).apply();
    }

    public static String getScreenSize(Context context) {
        String value = prefs(context).getString(KEY_SCREEN_SIZE, null);
        return value != null && value.matches("[0-9]+x[0-9]+") ? value : ScreenResolution.forMode(context, ScreenResolution.MODE_720P);
    }

    public static void setScreenSize(Context context, String screenSize) {
        prefs(context).edit().putString(KEY_SCREEN_SIZE, screenSize).apply();
    }

    /** {width, height} */
    public static int[] getScreenSizeValues(Context context) {
        String[] parts = getScreenSize(context).split("x");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    public static String getEnvVars(Context context) {
        return prefs(context).getString(KEY_ENV_VARS, "");
    }

    public static void setEnvVars(Context context, String envVars) {
        prefs(context).edit().putString(KEY_ENV_VARS, envVars).apply();
    }
}
