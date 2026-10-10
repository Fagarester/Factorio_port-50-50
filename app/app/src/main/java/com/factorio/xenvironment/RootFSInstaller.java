package com.factorio.xenvironment;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import androidx.appcompat.app.AppCompatActivity;

import com.factorio.MainActivity;
import com.factorio.R;
import com.factorio.SettingsFragment;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.core.AppUtils;
import com.factorio.core.DownloadProgressDialog;
import com.factorio.core.FileUtils;
import com.factorio.core.PreloaderDialog;
import com.factorio.core.TarCompressorUtils;
import com.factorio.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RootFSInstaller {
    public static final byte LATEST_VERSION = 19; // TODO increment it on rootfs update
    public static final byte UPDATE_WINEPREFIX_VERSION = 16; // set it if main wine version change
    public static final String FILENAME = "rootfs.tzst";

    private static void resetContainerRFSVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String rfsVersion = container.getExtra("rfsVersion");
            String wineVersion = container.getWineVersion();
            if (!rfsVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(rfsVersion) <= UPDATE_WINEPREFIX_VERSION) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("rfsVersion", null);
            container.saveData();
        }
    }

    public static void install(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        RootFS rootFS = RootFS.find(activity);
        final File rootDir = rootFS.getRootDir();

        SettingsFragment.resetBox64Version(activity);

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

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home") || name.equals("opt")) {
                            if (name.equals("opt")) clearOptDir(file);
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

    public static void generateCompactContainerPattern(final AppCompatActivity activity) {
        AppUtils.keepScreenOn(activity);
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        Executors.newSingleThreadExecutor().execute(() -> {
            File[] srcFiles, dstFiles;
            File rootDir = RootFS.find(activity).getRootDir();
            File wineSystem32Dir = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
            File wineSysWoW64Dir = new File(rootDir, "/opt/wine/lib/wine/i386-windows");

            File containerPatternDir = new File(activity.getCacheDir(), "container_pattern");
            FileUtils.delete(containerPatternDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "container_pattern.tzst", containerPatternDir);

            File containerSystem32Dir = new File(containerPatternDir, ".wine/drive_c/windows/system32");
            File containerSysWoW64Dir = new File(containerPatternDir, ".wine/drive_c/windows/syswow64");

            dstFiles = containerSystem32Dir.listFiles();
            srcFiles = wineSystem32Dir.listFiles();

            ArrayList<String> system32Files = new ArrayList<>();
            ArrayList<String> syswow64Files = new ArrayList<>();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) system32Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            dstFiles = containerSysWoW64Dir.listFiles();
            srcFiles = wineSysWoW64Dir.listFiles();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) syswow64Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            try {
                JSONObject data = new JSONObject();

                JSONArray system32JSONArray = new JSONArray();
                for (String name : system32Files) {
                    FileUtils.delete(new File(containerSystem32Dir, name));
                    system32JSONArray.put(name);
                }
                data.put("system32", system32JSONArray);

                JSONArray syswow64JSONArray = new JSONArray();
                for (String name : syswow64Files) {
                    FileUtils.delete(new File(containerSysWoW64Dir, name));
                    syswow64JSONArray.put(name);
                }
                data.put("syswow64", syswow64JSONArray);

                FileUtils.writeString(new File(activity.getCacheDir(), "common_dlls.json"), data.toString());

                File outputFile = new File(activity.getCacheDir(), "container_pattern.tzst");
                FileUtils.delete(outputFile);
                TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(containerPatternDir, ".wine"), outputFile, 22);

                FileUtils.delete(containerPatternDir);
                preloaderDialog.closeOnUiThread();
            }
            catch (JSONException e) {}
        });
    }
}
