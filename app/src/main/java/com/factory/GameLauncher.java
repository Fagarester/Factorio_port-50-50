package com.factory;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.factory.core.AppUtils;
import com.factory.core.DownloadProgressDialog;
import com.factory.core.EnvVars;
import com.factory.core.FileUtils;
import com.factory.core.GameCompression;
import com.factory.core.GameCopier;
import com.factory.core.GameSettings;
import com.factory.core.ScreenResolution;
import com.factory.gladio.Gladio;
import com.factory.runtime.Drivers;
import com.factory.runtime.Paths;
import com.factory.runtime.Session;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Starts the game: copies it from Download/factorio on the first start, then the X server and Factorio. */
public abstract class GameLauncher {
    /** The Linux arm64 build of Factorio, unpacked by the player. */
    public static final File GAME_SOURCE = new File(AppUtils.DIRECTORY_DOWNLOADS, "factorio");

    public static boolean isGameInstalled(Context context) {
        return new Paths(context).getGameBinary().isFile();
    }

    public static void start(final MainActivity activity) {
        if (isGameInstalled(activity)) {
            launch(activity);
            return;
        }
        if (!new File(GAME_SOURCE, "bin/arm64/factorio").isFile()) {
            activity.hideStartupCover();
            new AlertDialog.Builder(activity)
                .setTitle(R.string.game_not_found)
                .setMessage(activity.getString(R.string.game_not_found_message, GAME_SOURCE.getPath()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        copyGame(activity);
    }

    /** One-time copy (with the texture compression chosen on the first start) into the app's files. */
    private static void copyGame(final MainActivity activity) {
        final Paths paths = new Paths(activity);
        final int compression = PreferenceManager.getDefaultSharedPreferences(activity).getInt(GameCompression.PREF_KEY, GameCompression.ORIGINAL);
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.copying_game);
        AppUtils.keepScreenOn(activity);

        Executors.newSingleThreadExecutor().execute(() -> {
            File staging = new File(paths.getFiles(), "game.copying");
            FileUtils.delete(staging);
            GameCopier.Result result = GameCopier.copy(GAME_SOURCE, staging, compression,
                (percent) -> activity.runOnUiThread(() -> dialog.setProgress(percent)));

            boolean ok = result.ok && new File(staging, "bin/arm64/factorio").isFile();
            if (ok) {
                FileUtils.delete(paths.getGame());
                ok = staging.renameTo(paths.getGame());
            }
            if (ok) {
                // saves that came with the game folder go where Factorio writes its saves
                File saves = new File(paths.getGame(), "saves");
                File gameSaves = new File(paths.getGameData(), "saves");
                if (saves.isDirectory() && !gameSaves.exists()) {
                    paths.getGameData().mkdirs();
                    if (!saves.renameTo(gameSaves)) FileUtils.copy(saves, gameSaves);
                }
                FileUtils.writeString(new File(paths.getLogs(), "compression-report.txt"), result.report);
            }
            else FileUtils.delete(staging);

            final boolean success = ok;
            activity.runOnUiThread(() -> {
                dialog.close();
                if (!success) {
                    activity.hideStartupCover();
                    AppUtils.showToast(activity, R.string.game_copy_failed);
                    return;
                }
                if (compression != GameCompression.ORIGINAL) {
                    AppUtils.showToast(activity, activity.getString(R.string.compression_done, result.pngShrunk, result.pngTotal,
                        (int)(result.inBytes / 1048576), (int)(result.outBytes / 1048576)));
                }
                launch(activity);
            });
        });
    }

    private static void launch(final MainActivity activity) {
        applyDisplayPrefs(activity);
        final Map<String, String> env = new HashMap<>();
        EnvVars envVars = new EnvVars(GameSettings.getEnvVars(activity));
        for (String name : envVars) env.put(name, envVars.get(name));
        // Settings → Benchmark: this start plays the menu scenes with the frame timer, then the report is shown
        List<String> args = Collections.emptyList();
        if (Benchmark.isPending(activity)) {
            try {
                env.putAll(Benchmark.prepare(activity));
                args = Benchmark.gameArguments(activity);
                AppUtils.showToast(activity, R.string.benchmark_started);
            }
            catch (Exception e) {
                Benchmark.clear(activity);
                AppUtils.showToast(activity, activity.getString(R.string.benchmark_failed, String.valueOf(e.getMessage())));
            }
        }
        else if (Benchmark.isRunning(activity)) Benchmark.clear(activity); // left over from a run the app didn't see end
        final List<String> gameArgs = args;

        // Gladio on the X server inside the app (r34 display): that screen starts the game once its renderer is up
        if (Drivers.INSTANCE.embeddedXServer(activity)) {
            Executors.newSingleThreadExecutor().execute(() -> {
                String failure = null;
                try {
                    Gladio.prepare(activity);
                }
                catch (Throwable e) {
                    failure = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String message = failure;
                activity.runOnUiThread(() -> {
                    if (message != null) {
                        activity.hideStartupCover();
                        new AlertDialog.Builder(activity)
                            .setTitle(R.string.game_exited_early)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                        return;
                    }
                    XServerGameActivity.gameEnvironment = env;
                    XServerGameActivity.gameArguments = gameArgs;
                    activity.startActivity(new Intent(activity, XServerGameActivity.class));
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                });
            });
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            String failure = null;
            try {
                // Gladio: its server runs in this process and must listen before the game asks for OpenGL
                if (Drivers.INSTANCE.gladio(activity)) Gladio.prepare(activity);
                Session.INSTANCE.start(activity, gameArgs, env);
            }
            catch (Throwable e) {
                android.util.Log.e("Factory", "The game didn't start", e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            final String message = failure;
            activity.runOnUiThread(() -> {
                if (message != null) {
                    activity.hideStartupCover();
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.game_exited_early)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                showGame(activity);
                activity.finish();
                activity.overridePendingTransition(0, 0);
            });
        });
    }

    public static void showGame(Activity activity) {
        if (XServerGameActivity.running) {
            activity.startActivity(new Intent(activity, XServerGameActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        activity.startActivity(new Intent(activity, com.termux.x11.MainActivity.class));
    }

    /**
     * Termux:X11's preferences (the app's default preferences): the X screen is the resolution from the settings;
     * the rest is set once so it can still be changed.
     */
    private static void applyDisplayPrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int[] size = GameSettings.getScreenSizeValues(context);
        int[] nativeSize = ScreenResolution.getNativeSize(context);
        SharedPreferences.Editor editor = prefs.edit()
            .putString("displayResolutionMode", "custom")
            .putString("displayResolutionCustom", size[0]+"x"+size[1])
            // the game's UI scale follows it (Session.uiScale): the UI keeps its size on screen at any resolution
            .putInt("displayScale", Math.max(100, Math.round(nativeSize[1] * 100f / size[1])));
        if (!prefs.getBoolean("factoryDisplayDefaults", false)) {
            editor.putString("forceOrientation", "landscape")
                .putBoolean("fullscreen", true)
                .putBoolean("hideCutout", true)
                .putBoolean("showAdditionalKbd", false)
                .putBoolean("additionalKbdVisible", false)
                // without it the phone's keyboard app takes a hardware keyboard's keys and types them as text
                .putBoolean("enforceCharBasedInput", true)
                .putString("screenIdleTimeout", "never")
                .putBoolean("factoryDisplayDefaults", true);
        }
        editor.apply();
    }

    /** The game died, usually before its window opened: the end of its log says why. */
    public static void showGameFailed(Activity activity, int status, boolean noWindow) {
        String tail = "";
        String log = FileUtils.readString(new File(new Paths(activity).getLogs(), "game.log"));
        if (log != null && !log.trim().isEmpty()) {
            String[] lines = log.trim().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) sb.append(lines[i].length() > 220 ? lines[i].substring(0, 220) : lines[i]).append('\n');
            tail = "\n\n"+sb;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.game_exited_early)
            .setMessage(activity.getString(R.string.game_exited_early_message, status)+tail)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
