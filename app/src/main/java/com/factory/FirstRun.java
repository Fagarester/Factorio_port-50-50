package com.factory;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.factory.core.AppUtils;
import com.factory.core.DownloadProgressDialog;
import com.factory.core.GameCompression;
import com.factory.core.GameSettings;
import com.factory.core.ScreenResolution;
import com.factory.runtime.Runtime;

import java.util.concurrent.Executors;

/**
 * The very first start: unpacks the runtime (glibc, Mesa, X11 libraries) while the player picks
 * the game resolution (720p / 1080p / native) and the texture compression for the game copy.
 * A new runtime in an update is unpacked again, without the questions.
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
        final boolean ask = !GameSettings.isConfigured(activity);
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);

        // UI-thread state: the runtime is unpacked in the background while the player picks the resolution, then the compression.
        final int[] resolutionChoice = {-1};
        final int[] compressionChoice = {-1};
        final boolean[] installed = {false};
        final Runnable finish = () -> {
            dialog.hideChoice();
            dialog.close();
            GameSettings.setConfigured(activity);
            activity.reloadSettings();
            activity.autoStartContainer();
        };

        if (ask) {
            // order on screen: High, Low, Original (button index 0..2 -> GameCompression.HIGH..ORIGINAL)
            final String[] compressionLabels = {
                activity.getString(R.string.compression_high) + "\nx5",
                activity.getString(R.string.compression_low) + "\nx2",
                activity.getString(R.string.compression_original) + "\n" + activity.getString(R.string.compression_original_hint)
            };
            final Runnable askCompression = () -> dialog.showChoice(R.string.choose_compression, compressionLabels, (index) -> {
                int mode = GameCompression.HIGH - index;
                compressionChoice[0] = mode;
                PreferenceManager.getDefaultSharedPreferences(activity).edit().putInt(GameCompression.PREF_KEY, mode).apply();
                if (installed[0]) finish.run();
            });

            String[] labels = {
                "720p\n" + ScreenResolution.forMode(activity, ScreenResolution.MODE_720P),
                "1080p\n" + ScreenResolution.forMode(activity, ScreenResolution.MODE_1080P),
                activity.getString(R.string.resolution_native) + "\n" + ScreenResolution.forMode(activity, ScreenResolution.MODE_NATIVE)
            };
            dialog.showChoice(R.string.choose_resolution, labels, (mode) -> {
                resolutionChoice[0] = mode;
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
                preferences.edit().putInt(ScreenResolution.PREF_FIRST_RUN_MODE, mode).apply();
                GameSettings.setScreenSize(activity, ScreenResolution.forMode(activity, mode));
                askCompression.run(); // step 2
            });
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success;
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
                if (!ok) {
                    AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                    activity.hideStartupCover();
                    dialog.close();
                    return;
                }
                installed[0] = true;
                dialog.setProgress(100);
                if (!ask || compressionChoice[0] >= 0) finish.run();
                else dialog.setChoiceHint(resolutionChoice[0] >= 0 ? R.string.choose_compression_waiting : R.string.choose_resolution_waiting); // wait for the taps
            });
        });
    }
}
