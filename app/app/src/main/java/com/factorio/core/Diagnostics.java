package com.factorio.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;

import com.factorio.MainActivity;
import com.factorio.XServerDisplayActivity;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.widget.LogView;
import com.factorio.widget.XServerView;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Automatic diagnostics. Runs the game with a list of graphics setups one after another (every setup is a fresh start of the
 * game), and records for each one: the game log, logcat of the app, driver / Vortek reports, screenshots and a short result.
 * Everything ends up in one folder + one zip next to the other logs (Documents/Factorio/diagnostics-*.zip).
 *
 *  - "Diagnostics":       SHORT_SECONDS per setup (start-up / driver initialisation matrix)
 *  - "Diagnostics long":  LONG_SECONDS per setup (the game gets to the menu, screenshots every minute)
 *
 * The user's own settings are saved before the run and restored at the end (and at the next app start if the run was interrupted).
 * To add a setup, add one line in buildVariants().
 */
public class Diagnostics {
    public static final int SHORT_SECONDS = 15;
    public static final int LONG_SECONDS = 300;
    private static final String TAG = "Diagnostics";
    private static final String STATE_FILE = "diagnostics-state.json";
    public static final String EXTRA_RUN = "diagnostics_run";
    public static final String EXTRA_DONE = "diagnostics_done";

    public static class Variant {
        public final String id;
        public final String title;
        public final String driver;       // "vortek,zinkold", "turnip,zink", ...
        public final String vortekConfig; // "bcnMode=1,resourceMemoryType=3" (only for Vortek setups)
        public final String nativeGfx;    // value for NATIVE_GFX or null (= variable removed)

        Variant(String id, String title, String driver, String vortekConfig, String nativeGfx) {
            this.id = id;
            this.title = title;
            this.driver = driver;
            this.vortekConfig = vortekConfig;
            this.nativeGfx = nativeGfx;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the list of setups
    // ---------------------------------------------------------------------------------------------------------------
    public static List<Variant> buildVariants(Context context, boolean longMode) {
        ArrayList<Variant> list = new ArrayList<>();
        final String zink = "vortek,zinkold";
        final String gladio = "vortek,gladio";
        final String noCompression = "texture-compression-level=none";
        boolean adreno = GPUHelper.isAdreno(context);

        if (!longMode) {
            list.add(new Variant("01-zink-bcn-cpu", "Vortek + Zink 22.2.5, BCn: CPU decode", zink, "bcnMode=0", null));
            list.add(new Variant("02-zink-bcn-gpu-new", "Vortek + Zink 22.2.5, BCn: GPU layer (new build)", zink, "bcnMode=1", null));
            list.add(new Variant("03-zink-bcn-gpu-astc", "Vortek + Zink 22.2.5, BCn: GPU layer legacy ASTC", zink, "bcnMode=2", null));
            list.add(new Variant("04-zink-bcn-gpu-etc2", "Vortek + Zink 22.2.5, BCn: GPU layer legacy ETC2", zink, "bcnMode=3", null));
            list.add(new Variant("05-gladio-bcn-cpu", "Vortek + Gladio, BCn: CPU decode", gladio, "bcnMode=0", null));
            list.add(new Variant("06-gladio-bcn-gpu-new", "Vortek + Gladio, BCn: GPU layer (new build)", gladio, "bcnMode=1", null));
            list.add(new Variant("07-zink-mem-opaquefd", "Zink, BCn new, Resource Memory: Opaque FD", zink, "bcnMode=1,resourceMemoryType=1", null));
            list.add(new Variant("08-zink-mem-dmabuf", "Zink, BCn new, Resource Memory: DMA Buffer", zink, "bcnMode=1,resourceMemoryType=2", null));
            list.add(new Variant("09-zink-mem-ahb", "Zink, BCn new, Resource Memory: Native Hardware Buffer", zink, "bcnMode=1,resourceMemoryType=3", null));
            list.add(new Variant("10-zink-vk11", "Zink, BCn new, Vulkan 1.1", zink, "bcnMode=1,vkMaxVersion=1.1", null));
            list.add(new Variant("11-zink-vk12", "Zink, BCn new, Vulkan 1.2", zink, "bcnMode=1,vkMaxVersion=1.2", null));
            list.add(new Variant("12-zink-mem4g", "Zink, BCn new, Max device memory 4096 MB", zink, "bcnMode=1,maxDeviceMemory=4096", null));
            list.add(new Variant("13-zink-nocompr", "Zink, BCn new, game: texture compression none", zink, "bcnMode=1", noCompression));
            list.add(new Variant("14-zink-nocompr-cpu", "Zink, BCn CPU, game: texture compression none", zink, "bcnMode=0", noCompression));
            list.add(new Variant("15-zink-tex4096", "Zink, BCn new, game: compression none + max texture 4096", zink, "bcnMode=1", noCompression+";max-texture-size=4096"));
            list.add(new Variant("16-zink-vram-high", "Zink, BCn new, game: video memory usage high", zink, "bcnMode=1", "video-memory-usage=high"));
            list.add(new Variant("17-gladio-nocompr", "Gladio, BCn new, game: texture compression none", gladio, "bcnMode=1", noCompression));
            if (adreno) {
                list.add(new Variant("20-turnip-zink", "Turnip + Zink (current block)", "turnip,zink", null, null));
                list.add(new Variant("21-turnip-opengl", "Turnip + OpenGL native (current block)", "turnip,opengl", null, null));
            }
        }
        else {
            list.add(new Variant("L1-zink-bcn-gpu-new", "Vortek + Zink 22.2.5, BCn: GPU layer (new build)", zink, "bcnMode=1", null));
            list.add(new Variant("L2-zink-bcn-cpu", "Vortek + Zink 22.2.5, BCn: CPU decode", zink, "bcnMode=0", null));
            list.add(new Variant("L3-zink-bcn-gpu-astc", "Vortek + Zink 22.2.5, BCn: GPU layer legacy ASTC", zink, "bcnMode=2", null));
            list.add(new Variant("L4-zink-nocompr", "Zink, BCn new, game: texture compression none", zink, "bcnMode=1", noCompression));
            list.add(new Variant("L5-zink-mem-ahb", "Zink, BCn new, Resource Memory: Native Hardware Buffer", zink, "bcnMode=1,resourceMemoryType=3", null));
            list.add(new Variant("L6-zink-vk11-mem4g", "Zink, BCn new, Vulkan 1.1 + max memory 4096 MB", zink, "bcnMode=1,vkMaxVersion=1.1,maxDeviceMemory=4096", null));
            list.add(new Variant("L7-gladio-bcn-gpu-new", "Vortek + Gladio, BCn: GPU layer (new build)", gladio, "bcnMode=1", null));
            if (adreno) {
                list.add(new Variant("L8-turnip-zink", "Turnip + Zink (current block)", "turnip,zink", null, null));
                list.add(new Variant("L9-turnip-opengl", "Turnip + OpenGL native (current block)", "turnip,opengl", null, null));
            }
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // state (a file, so it survives restarts of the screen)
    // ---------------------------------------------------------------------------------------------------------------
    private static File stateFile(Context context) {
        return new File(context.getFilesDir(), STATE_FILE);
    }

    private static JSONObject readState(Context context) {
        try {
            String text = FileUtils.readString(stateFile(context));
            if (text != null && !text.trim().isEmpty()) return new JSONObject(text);
        }
        catch (Exception e) {}
        return null;
    }

    private static void writeState(Context context, JSONObject state) {
        try {
            FileUtils.writeString(stateFile(context), state.toString());
        }
        catch (Exception e) {
            Log.w(TAG, "unable to write state", e);
        }
    }

    public static boolean isActive(Context context) {
        return stateFile(context).isFile();
    }

    private static File logDir() {
        File dir = LogView.getLogFile().getParentFile();
        if (dir != null) dir.mkdirs();
        return dir;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // start / restore
    // ---------------------------------------------------------------------------------------------------------------
    public static int estimateMinutes(Context context, boolean longMode) {
        int variants = buildVariants(context, longMode).size();
        int perVariant = (longMode ? LONG_SECONDS : SHORT_SECONDS) + 25; // + loading of the app / unpacking / shutting down
        return Math.max(1, variants * perVariant / 60);
    }

    public static void start(Activity activity, boolean longMode) {
        try {
            Container container = (new ContainerManager(activity)).getMainContainer();
            File dir = logDir();
            if (container == null || dir == null) return;

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File runDir = new File(dir, "diagnostics-"+stamp);
            runDir.mkdirs();

            JSONObject snapshot = new JSONObject();
            snapshot.put("graphicsDriver", container.getGraphicsDriver());
            snapshot.put("graphicsDriverConfig", container.getGraphicsDriverConfig());
            snapshot.put("envVars", container.getEnvVars());
            snapshot.put("block1", container.getExtra(GraphicsDrivers.EXTRA_BLOCK_TURNIP));
            snapshot.put("block2", container.getExtra(GraphicsDrivers.EXTRA_BLOCK_VORTEK));
            snapshot.put("config2", container.getExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK));

            JSONObject state = new JSONObject();
            state.put("longMode", longMode);
            state.put("index", 0);
            state.put("runDir", runDir.getPath());
            state.put("startedAt", System.currentTimeMillis());
            state.put("snapshot", snapshot);
            writeState(activity, state);

            appendSummary(runDir, "Diagnostics "+(longMode ? "LONG" : "SHORT")+" started "+new Date()+"\n"+deviceInfo(activity)+"\n");
            launchVariant(activity, runDir.getPath());
        }
        catch (Exception e) {
            Log.e(TAG, "start failed", e);
        }
    }

    private static String deviceInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
          .append(" (").append(android.os.Build.DEVICE).append("), SoC: ").append(android.os.Build.HARDWARE)
          .append(", Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("app version code: ").append(AppUtils.getVersionCode(context)).append("\n");
        try {
            sb.append("gpu: ").append(GPUHelper.glGetRenderer(context)).append("\n");
        }
        catch (Throwable e) {}
        return sb.toString();
    }

    private static void launchVariant(Context context, String runDir) {
        Container container = (new ContainerManager(context)).getMainContainer();
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        if (container != null) intent.putExtra("container_id", container.id);
        intent.putExtra(EXTRA_RUN, runDir);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    /** Puts the user's own settings back and removes the state. Returns the run folder (or null). */
    public static File restoreSnapshot(Context context) {
        JSONObject state = readState(context);
        if (state == null) return null;
        File runDir = null;
        try {
            runDir = new File(state.getString("runDir"));
            JSONObject snapshot = state.getJSONObject("snapshot");
            Container container = (new ContainerManager(context)).getMainContainer();
            if (container != null) {
                container.setGraphicsDriver(snapshot.optString("graphicsDriver"));
                container.setGraphicsDriverConfig(snapshot.optString("graphicsDriverConfig"));
                container.setEnvVars(snapshot.optString("envVars"));
                container.putExtra(GraphicsDrivers.EXTRA_BLOCK_TURNIP, snapshot.optString("block1"));
                container.putExtra(GraphicsDrivers.EXTRA_BLOCK_VORTEK, snapshot.optString("block2"));
                container.putExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK, snapshot.optString("config2"));
                container.putExtra("graphicsDriver", ""); // force re-extraction of the user's driver
                container.saveData();
            }
        }
        catch (Exception e) {
            Log.e(TAG, "restore failed", e);
        }
        stateFile(context).delete();
        return runDir;
    }

    /** Called at app start: if a run was interrupted (app killed / crashed) the user's settings are restored. */
    public static void recoverIfInterrupted(Context context) {
        if (!isActive(context) || XServerDisplayActivity.isRunning()) return;
        File runDir = restoreSnapshot(context);
        if (runDir != null) {
            appendSummary(runDir, "\nRun was interrupted (app restarted). User settings restored. "+new Date()+"\n");
            zip(runDir);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // one setup = one session (lives in XServerDisplayActivity)
    // ---------------------------------------------------------------------------------------------------------------
    public static Session beginVariant(XServerDisplayActivity activity, Container container) {
        if (!isActive(activity)) return null;
        JSONObject state = readState(activity);
        String requested = activity.getIntent().getStringExtra(EXTRA_RUN);
        try {
            if (state == null || requested == null || !requested.equals(state.getString("runDir"))) {
                recoverIfInterrupted(activity); // normal start while a stale run is recorded
                return null;
            }
            boolean longMode = state.getBoolean("longMode");
            int index = state.getInt("index");
            List<Variant> variants = buildVariants(activity, longMode);
            if (index >= variants.size()) {
                finishRun(activity);
                return null;
            }
            Variant variant = variants.get(index);
            JSONObject snapshot = state.getJSONObject("snapshot");

            container.setGraphicsDriver(variant.driver);
            if (variant.vortekConfig != null) container.putExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK, variant.vortekConfig+"|");
            EnvVars envVars = new EnvVars(snapshot.optString("envVars"));
            envVars.remove("NATIVE_GFX");
            if (variant.nativeGfx != null) envVars.put("NATIVE_GFX", variant.nativeGfx);
            container.setEnvVars(envVars.toString());
            container.saveData();

            return new Session(activity, state, variant, index, variants.size(), longMode);
        }
        catch (Exception e) {
            Log.e(TAG, "beginVariant failed", e);
            return null;
        }
    }

    public static class Session {
        private final XServerDisplayActivity activity;
        private final JSONObject state;
        private final Variant variant;
        private final int index, total;
        private final boolean longMode;
        private final File runDir, variantDir;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long createdAt = System.currentTimeMillis();
        private long startedAt = 0;
        private BufferedWriter logWriter;
        private Callback<String> logCallback;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private XServerView xServerView;

        Session(XServerDisplayActivity activity, JSONObject state, Variant variant, int index, int total, boolean longMode) throws Exception {
            this.activity = activity;
            this.state = state;
            this.variant = variant;
            this.index = index;
            this.total = total;
            this.longMode = longMode;
            this.runDir = new File(state.getString("runDir"));
            this.variantDir = new File(runDir, variant.id);
            variantDir.mkdirs();

            FileUtils.writeString(new File(variantDir, "variant.txt"),
                "id: "+variant.id+"\ntitle: "+variant.title+"\ndriver: "+variant.driver+"\nvortek config: "+variant.vortekConfig+
                "\nNATIVE_GFX: "+variant.nativeGfx+"\nstarted: "+new Date()+"\n");

            // everything the game prints goes to this variant's log (replaces the optional debug log of the app)
            logWriter = new BufferedWriter(new FileWriter(new File(variantDir, "log.txt")));
            final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            logCallback = (line) -> {
                if (line == null || line.startsWith("xserver:")) return;
                synchronized (Session.this) {
                    try {
                        if (logWriter != null) logWriter.write("["+time.format(new Date())+"] "+line+"\n");
                    }
                    catch (Exception e) {}
                }
            };
            ProcessHelper.addDebugCallback(logCallback);
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}); // own logcat starts empty for this variant
            }
            catch (Exception e) {}

            long seconds = longMode ? LONG_SECONDS : SHORT_SECONDS;
            // watchdog: the game never started (stuck unpacking, missing files, ...)
            handler.postDelayed(() -> finish("timeout: game environment did not start within "+(seconds + 120)+" s"), (seconds + 120) * 1000);
        }

        /** The game process was started: the timer of this setup starts now. */
        public void onEnvironmentStarted(XServerView view) {
            this.xServerView = view;
            this.startedAt = System.currentTimeMillis();
            final int seconds = longMode ? LONG_SECONDS : SHORT_SECONDS;

            if (longMode) {
                for (int t = 60; t < seconds - 2; t += 60) {
                    final int at = t;
                    handler.postDelayed(() -> screenshot("shot-"+at+"s", null), t * 1000L);
                }
            }
            handler.postDelayed(() -> finish("completed"), seconds * 1000L);
        }

        private void screenshot(final String name, final Runnable done) {
            final AtomicBoolean called = new AtomicBoolean(false);
            final Runnable once = () -> {
                if (called.compareAndSet(false, true) && done != null) done.run();
            };
            try {
                if (xServerView == null || xServerView.getWidth() <= 0 || xServerView.getHeight() <= 0) {
                    once.run();
                    return;
                }
                final Bitmap bitmap = Bitmap.createBitmap(xServerView.getWidth(), xServerView.getHeight(), Bitmap.Config.ARGB_8888);
                handler.postDelayed(once, 2500); // PixelCopy sometimes never answers
                PixelCopy.request(xServerView, bitmap, (result) -> {
                    if (result == PixelCopy.SUCCESS) {
                        try (FileOutputStream out = new FileOutputStream(new File(variantDir, name+".png"))) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        catch (Exception e) {}
                    }
                    else appendLine(new File(variantDir, "result.txt"), "screenshot "+name+": PixelCopy result "+result);
                    once.run();
                }, handler);
            }
            catch (Throwable e) {
                appendLine(new File(variantDir, "result.txt"), "screenshot "+name+" failed: "+e);
                once.run();
            }
        }

        /** Ends this setup (also called when the game exits early) and moves on to the next one. */
        public void finish(final String status) {
            if (!finished.compareAndSet(false, true)) return;
            handler.removeCallbacksAndMessages(null);
            screenshot("final", () -> afterScreenshot(status));
        }

        private void afterScreenshot(String status) {
            try {
                ProcessHelper.removeDebugCallback(logCallback);
                synchronized (this) {
                    if (logWriter != null) logWriter.close();
                    logWriter = null;
                }
            }
            catch (Exception e) {}

            long elapsed = startedAt > 0 ? (System.currentTimeMillis() - startedAt) / 1000 : -1;
            try {
                File dir = logDir();
                if (dir != null) {
                    for (String name : new String[]{"driver-report.txt", "vortek-report.txt", "factorio-native.log"}) {
                        File file = new File(dir, name);
                        if (file.isFile()) copy(file, new File(variantDir, name));
                    }
                }
                saveLogcat(new File(variantDir, "logcat.txt"));

                StringBuilder result = new StringBuilder();
                result.append(variant.id).append("  ").append(variant.title).append("\n");
                result.append("status: ").append(status).append("   game ran: ").append(elapsed).append(" s\n\n");
                result.append("key lines of the game log:\n").append(extractKeyLines(new File(variantDir, "log.txt")));
                appendLine(new File(variantDir, "result.txt"), result.toString());

                appendSummary(runDir, String.format(Locale.US, "[%d/%d] %-26s %-48s -> %s (%d s)\n", index + 1, total, variant.id, variant.title, status, elapsed));
                state.put("index", index + 1);
                writeState(activity, state);
            }
            catch (Exception e) {
                Log.e(TAG, "finish failed", e);
            }

            activity.diagShutdown();
            // give the game, the X server and Vortek time to close before the next start
            handler.postDelayed(() -> {
                if (index + 1 < total) launchVariant(activity, runDir.getPath());
                else finishRun(activity);
            }, 3000);
        }
    }

    private static void finishRun(Context context) {
        File runDir = restoreSnapshot(context);
        String zipPath = "";
        if (runDir != null) {
            appendSummary(runDir, "\nFinished "+new Date()+"\n");
            File zip = zip(runDir);
            if (zip != null) zipPath = zip.getPath();
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(EXTRA_DONE, zipPath);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------
    private static void appendSummary(File runDir, String text) {
        appendLine(new File(runDir, "summary.txt"), text);
    }

    private static void appendLine(File file, String text) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(text.endsWith("\n") ? text : text+"\n");
        }
        catch (Exception e) {}
    }

    private static void copy(File from, File to) {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        catch (Exception e) {}
    }

    private static void saveLogcat(File target) {
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "--pid="+android.os.Process.myPid()});
            try (InputStream in = process.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                long written = 0;
                while ((n = in.read(buffer)) > 0 && written < 4L * 1024 * 1024) {
                    out.write(buffer, 0, n);
                    written += n;
                }
            }
            process.destroy();
        }
        catch (Exception e) {
            appendLine(target, "logcat not available: "+e);
        }
    }

    private static String extractKeyLines(File log) {
        StringBuilder sb = new StringBuilder();
        String text = FileUtils.readString(log);
        if (text == null) return "(no game log)\n";
        int count = 0;
        for (String line : text.split("\n")) {
            String l = line.toLowerCase(Locale.US);
            if (l.contains("initialised opengl") || l.contains("graphics options") || l.contains("texture processor") || l.contains("factorio initialised") ||
                l.contains("sprites loaded") || l.contains("error") || l.contains("zink:") || l.contains("mesa:") || l.contains("warning") ||
                l.contains("vortek") || l.contains("segmentation") || l.contains("signal") || l.contains("cannot") || l.contains("failed") || l.contains("dedicated video memory")) {
                sb.append(line.length() > 260 ? line.substring(0, 260) : line).append('\n');
                if (++count >= 80) break;
            }
        }
        return count == 0 ? "(nothing recognised; see log.txt)\n" : sb.toString();
    }

    private static File zip(File runDir) {
        File zipFile = new File(runDir.getParentFile(), runDir.getName()+".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
            addToZip(out, runDir, runDir.getName());
            return zipFile;
        }
        catch (Exception e) {
            Log.e(TAG, "zip failed", e);
            return null;
        }
    }

    private static void addToZip(ZipOutputStream out, File file, String path) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) addToZip(out, child, path+"/"+child.getName());
        }
        else {
            out.putNextEntry(new ZipEntry(path));
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            out.closeEntry();
        }
    }
}
