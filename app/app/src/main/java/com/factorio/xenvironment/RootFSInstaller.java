package com.factorio.xenvironment;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;


import com.factorio.MainActivity;
import com.factorio.R;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.core.AppUtils;
import com.factorio.core.DownloadProgressDialog;
import com.factorio.core.FileUtils;
import com.factorio.core.TarCompressorUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RootFSInstaller {
    public static final byte LATEST_VERSION = 20; // TODO increment it on rootfs update (20: package-independent paths, no Wine)
    public static final String FILENAME = "rootfs.tzst";

    private static void resetContainerRFSVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            container.putExtra("rfsVersion", null);
            container.saveData();
        }
    }

    public static void install(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        RootFS rootFS = RootFS.find(activity);
        final File rootDir = rootFS.getRootDir();

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir);
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                rootFS.createRFSVersionFile(LATEST_VERSION);
                resetContainerRFSVersions(activity);
                activity.runOnUiThread(() -> createDefaultContainerIfNeeded(activity));
            }
            else {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                activity.runOnUiThread(activity::hideStartupCover);
            }

            dialog.closeOnUiThread();
        });
    }

    public static void installIfNeeded(final MainActivity activity) {
        RootFS rootFS = RootFS.find(activity);
        if (!rootFS.isValid() || rootFS.getVersion() < LATEST_VERSION) install(activity);
        else createDefaultContainerIfNeeded(activity);
    }

    // Creates one ready-to-run container (Turnip + Zink + Factorio variables) on the very first start.
    // It is created only once: if the user later deletes all containers, it is not recreated.
    public static void createDefaultContainerIfNeeded(final MainActivity activity) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (preferences.getBoolean("default_container_created", false)) {
            activity.autoStartContainer();
            return;
        }

        final ContainerManager manager = new ContainerManager(activity);
        if (!manager.getContainers().isEmpty()) {
            preferences.edit().putBoolean("default_container_created", true).apply();
            activity.autoStartContainer();
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", "Factorio");
            data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(activity));
            manager.createContainerAsync(data, (container) -> {
                if (container != null) {
                    preferences.edit().putBoolean("default_container_created", true).apply();
                    activity.reloadContainersList(); // show the new container in the list right away
                    activity.autoStartContainer();   // and start it
                }
                else activity.hideStartupCover();
            });
        }
        catch (JSONException e) {
            activity.hideStartupCover();
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) continue; // containers (and the game inside) are kept
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }
}
