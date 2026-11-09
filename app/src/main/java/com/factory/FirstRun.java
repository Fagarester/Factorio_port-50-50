package com.factory;

import android.content.Context;

import com.factory.core.AppUtils;
import com.factory.core.DownloadProgressDialog;
import com.factory.core.GameSettings;
import com.factory.core.ScreenResolution;
import com.factory.runtime.Drivers;
import com.factory.runtime.Runtime;

import java.util.concurrent.Executors;

/**
 * The very first start: unpacks the runtime (glibc, Mesa, X11 libraries), no questions: the game runs at 720p and its
 * textures are copied as they are (both can be changed in the settings later). The graphics driver is set for the
 * phone's GPU: Turnip with Zink on Adreno, Gladio on the rest (Mali, PowerVR…). A new runtime in an update is
 * unpacked again.
 */
public abstract class FirstRun {
    public static boolean isDone(Context context) {
        return GameSettings.isConfigured(context) && Runtime.INSTANCE.isInstalled(context);
    }

    public static void runIfNeeded(final MainActivity activity) {
        if (isDone(activity)) {
            activity.autoStartContainer();
            return;
        }

        AppUtils.keepScreenOn(activity);
        final boolean firstStart = !GameSettings.isConfigured(activity);
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        dialog.setIndeterminate();

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success;
            // the graphics driver for this phone's GPU: Turnip (Zink) on Qualcomm's Adreno, Gladio on any other
            if (firstStart) Drivers.INSTANCE.chooseForThisGpu(activity);
            try {
                Runtime.INSTANCE.install(activity);
                success = true;
            }
            catch (Throwable e) {
                android.util.Log.e("Factory", "Cannot unpack the runtime", e);
                success = false;
            }

            final boolean ok = success;
            activity.runOnUiThread(() -> {
                dialog.close();
                if (!ok) {
                    AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                    activity.hideStartupCover();
                    return;
                }
                if (firstStart) GameSettings.setScreenSize(activity, ScreenResolution.forMode(activity, ScreenResolution.MODE_720P));
                GameSettings.setConfigured(activity);
                activity.reloadSettings();
                activity.autoStartContainer();
            });
        });
    }
}
