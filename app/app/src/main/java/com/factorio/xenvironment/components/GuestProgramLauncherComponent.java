package com.factorio.xenvironment.components;

import android.os.Process;


import com.factorio.container.Container;
import com.factorio.core.Callback;
import com.factorio.core.EnvVars;
import com.factorio.core.FileUtils;
import com.factorio.core.LocaleHelper;
import com.factorio.core.ProcessHelper;
import com.factorio.widget.LogView;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xenvironment.EnvironmentComponent;
import com.factorio.xenvironment.RootFS;
import com.factorio.xserver.XServer;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private static int pid = -1;
    private EnvVars envVars;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        // NATIVE_EXEC: run the native ARM64 glibc game binary directly (there is no Wine / Box64 in this app).
        // Container Settings -> Environment Variables:
        //   NATIVE_SRC  = /storage/emulated/0/Download/factorio   (game folder on phone storage, optional)
        //   NATIVE_EXEC = /home/xuser/factorio/bin/arm64/factorio (path inside rootfs)
        // NATIVE_SRC is copied once into rootfs/home/xuser/ because binaries cannot
        // be executed from shared storage. The binary is started through the rootfs
        // glibc loader because Android has no /lib/ld-linux-aarch64.so.1.
        String nativeExec = envVars.get("NATIVE_EXEC");
        if (nativeExec == null || nativeExec.isEmpty()) nativeExec = Container.DEFAULT_NATIVE_EXEC;
        String command;
        String nativeSrc = envVars.get("NATIVE_SRC");
        if (nativeSrc != null && !nativeSrc.isEmpty()) {
            File srcDir = new File(nativeSrc);
            File dstDir = new File(rootDir, RootFS.HOME_PATH+"/"+srcDir.getName());
            File doneMark = new File(dstDir, ".copy_done");
            if (srcDir.isDirectory() && !doneMark.exists()) {
                // copy was never finished (or first run): start clean, then mark as done
                FileUtils.delete(dstDir);
                if (FileUtils.copy(srcDir, dstDir)) {
                    try { doneMark.createNewFile(); } catch (Exception e) {}
                }
            }
        }

        File nativeFile = new File(nativeExec.startsWith("/") && !nativeExec.startsWith("/storage") ? rootDir+nativeExec : nativeExec);
        nativeFile.setExecutable(true);
        String loader = rootFS.getLibDir()+"/ld-linux-aarch64.so.1";
        // Because the game is started through the glibc loader, /proc/self/exe points to the
        // loader (rootfs/usr/lib), so Factorio cannot find its data folder by itself.
        // Give it an explicit config with absolute read-data / write-data paths.
        String extraArgs = "";
        File binDir = nativeFile.getParentFile();
        File gameRoot = (binDir != null && binDir.getParentFile() != null) ? binDir.getParentFile().getParentFile() : null;
        if (gameRoot != null && (new File(gameRoot, "data")).isDirectory()) {
            File configFile = new File(gameRoot, "config-native.ini");

            // The config file is kept between launches so in-game graphics settings persist.
            // It is (re)generated when it does not exist, is not a valid config with absolute paths
            // (e.g. after a reset in the game), or when the container variable NATIVE_GFX is set:
            //   NATIVE_GFX=video-memory-usage=high;graphics-quality=medium   (override keys, rewrite the file)
            //   NATIVE_GFX=reset                                              (rewrite the file with defaults)
            String gfxExtra = envVars.get("NATIVE_GFX");
            boolean forceWrite = gfxExtra != null && !gfxExtra.isEmpty();
            boolean keepExisting = false;
            if (!forceWrite && configFile.isFile()) {
                byte[] currentBytes = FileUtils.read(configFile);
                String current = currentBytes != null ? new String(currentBytes, java.nio.charset.StandardCharsets.UTF_8) : null;
                keepExisting = current != null && current.contains("read-data=") && !current.contains("__PATH__");
            }

            if (!keepExisting) {
                java.util.LinkedHashMap<String, String> gfx = new java.util.LinkedHashMap<>();
                gfx.put("max-sprite-loading-threads", "2");
                gfx.put("texture-compression-level", "low-quality");
                gfx.put("gpu-accelerated-compression", "false");
                gfx.put("video-memory-usage", "medium");
                gfx.put("graphics-quality", "medium");

                if (forceWrite && !gfxExtra.trim().equals("reset")) {
                    for (String item : gfxExtra.split(";")) {
                        int eq = item.indexOf('=');
                        if (eq > 0) gfx.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
                    }
                }

                StringBuilder config = new StringBuilder("[path]\nread-data="+gameRoot+"/data\nwrite-data="+gameRoot+"\n[graphics]\n");
                for (java.util.Map.Entry<String, String> entry : gfx.entrySet()) {
                    config.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
                }
                FileUtils.writeString(configFile, config.toString());
            }
            extraArgs = " --config "+configFile.getPath();
        }
        // Factorio saves its fullscreen window size into the very same --config ini file
        // (which we now keep between launches so in-game graphics settings persist), so an
        // old window size silently sticks even after the container's screen resolution is
        // changed later. --window-size is an official CLI flag that always wins over any
        // saved value, without touching (and discarding) the rest of the saved settings.
        XServerComponent xServerComponent = environment.getComponent(XServerComponent.class);
        if (xServerComponent != null) {
            XServer nativeXServer = xServerComponent.getXServer();
            if (nativeXServer != null) {
                extraArgs += " --window-size "+nativeXServer.screenInfo.width+"x"+nativeXServer.screenInfo.height;
            }
        }

        String nativeArgs = envVars.get("NATIVE_ARGS");
        if (nativeArgs != null && !nativeArgs.isEmpty()) extraArgs += " "+nativeArgs;

        // NATIVE_MESA_SRC: use a Mesa build with the Freedreno/KGSL OpenGL driver (direct OpenGL, no Zink).
        //   NATIVE_MESA_SRC = /storage/emulated/0/Download/mesa   (folder that contains lib/ and dri/)
        // The folder is copied once into rootfs/opt/mesa-kgsl and put first in the library path.
        // Variables set by the container (MESA_LOADER_DRIVER_OVERRIDE, LIBGL_DRIVERS_PATH, ...) win over the defaults below.
        String libPath = rootFS.getLibDir().getPath();
        String mesaSrc = envVars.get("NATIVE_MESA_SRC");
        if (mesaSrc != null && !mesaSrc.isEmpty()) {
            File mesaSrcDir = new File(mesaSrc);
            File mesaDir = new File(rootDir, "/opt/mesa-kgsl");
            File mesaMark = new File(mesaDir, ".copy_done");
            if (mesaSrcDir.isDirectory() && !mesaMark.exists()) {
                FileUtils.delete(mesaDir);
                if (FileUtils.copy(mesaSrcDir, mesaDir)) {
                    try { mesaMark.createNewFile(); } catch (Exception e) {}
                }
            }
            if (mesaMark.exists()) {
                libPath = mesaDir+"/lib:"+libPath;
                envVars.put("LD_LIBRARY_PATH", libPath);
                if (!envVars.has("LIBGL_DRIVERS_PATH")) envVars.put("LIBGL_DRIVERS_PATH", mesaDir+"/dri");
                if (!envVars.has("MESA_LOADER_DRIVER_OVERRIDE")) envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
                if (!envVars.has("__GLX_VENDOR_LIBRARY_NAME")) envVars.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                // Zink/Vortek specific settings from the graphics driver setup must not leak into the Freedreno path
                envVars.remove("GALLIUM_DRIVER");
                envVars.remove("ZINK_CONTEXT_THREADED");
                envVars.remove("MESA_GL_VERSION_OVERRIDE");
            }
        }

        // Factorio does not know that it runs through the glibc loader:
        //  - /proc/self/exe points to the loader, so it detects a wrong binaries path (rootfs/usr) and the updater fails
        //    with "File .../usr/arm64/factorio not found"  -> --executable-path tells it where the executable is;
        //  - after an update it restarts itself with execv(<executable-path>), and the real binary cannot be started
        //    directly on Android (its ELF interpreter does not exist).
        // So --executable-path points to a tiny launcher script located next to the real binary
        // (bin/arm64/): it starts the real binary through the loader with the same arguments.
        File launcherScript = new File(nativeFile.getParentFile(), "factorio-start.sh");
        FileUtils.writeString(launcherScript, "#!/system/bin/sh\nexec "+loader+" --library-path "+libPath+" "+nativeFile.getPath()+" \"$@\"\n");
        launcherScript.setExecutable(true, false);
        if (extraArgs.indexOf("--executable-path") < 0) extraArgs += " --executable-path "+launcherScript.getPath();

        // NATIVE_PRELOAD: glibc libraries preloaded into the game only (input interposer + fake libudev for gamepads).
        // --preload is used instead of LD_PRELOAD because LD_PRELOAD would also hit Android's /system/bin/sh
        // (factorio-start.sh) and break it.
        String nativePreload = envVars.get("NATIVE_PRELOAD");
        String preloadArg = nativePreload != null && !nativePreload.isEmpty() ? " --preload "+nativePreload : "";
        command = loader+preloadArg+" --library-path "+libPath+" "+nativeFile.getPath()+extraArgs;
        File workDir = nativeFile.getParentFile();
        if (workDir != null && workDir.isDirectory()) rootDir = workDir;
        envVars.remove("LD_PRELOAD");
        // Factorio's updater restarts through factorio-start.sh, whose interpreter is
        // Android's /system/bin/sh. Do not expose the rootfs glibc directory through
        // LD_LIBRARY_PATH to Android executables: Android's linker would try to load
        // rootfs/usr/lib/libc.so and fail with "bad ELF magic" before the script runs.
        // The game and its restart script already pass the guest libraries explicitly
        // with the glibc loader's --library-path argument.
        envVars.remove("LD_LIBRARY_PATH");

        // Log the game output to Documents/Factorio/factorio-native.log (cheap, works without the debug window)
        File logDir = LogView.getLogFile().getParentFile();
        if (logDir != null && logDir.isDirectory() && logDir.canWrite()) {
            File nativeLogFile = new File(logDir, "factorio-native.log");
            FileUtils.delete(nativeLogFile);
            envVars.put("NATIVE_LOG_FILE", nativeLogFile.getPath());
        }
    

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}