package com.factorio.core;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/** Container resolution presets that follow the real screen aspect ratio of the device. */
public abstract class ScreenResolution {
    public static final int MODE_720P = 0;
    public static final int MODE_1080P = 1;
    public static final int MODE_NATIVE = 2;

    /** Preference key (default shared preferences) with the mode chosen on the first start. */
    public static final String PREF_FIRST_RUN_MODE = "first_run_resolution";

    /** Real display size in landscape orientation: {width, height}. */
    public static int[] getNativeSize(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{Math.max(metrics.widthPixels, metrics.heightPixels), Math.min(metrics.widthPixels, metrics.heightPixels)};
    }

    /** "WxH" for the given mode. Both numbers are even; the device resolution is never upscaled. */
    public static String forMode(Context context, int mode) {
        int[] nativeSize = getNativeSize(context);
        int width = nativeSize[0];
        int height = nativeSize[1];

        int targetHeight = mode == MODE_720P ? 720 : (mode == MODE_1080P ? 1080 : height);
        if (targetHeight < height) {
            width = Math.round((float)targetHeight * nativeSize[0] / nativeSize[1]);
            height = targetHeight;
        }
        return even(width)+"x"+even(height);
    }

    private static int even(int value) {
        return Math.max(2, value & ~1);
    }
}
