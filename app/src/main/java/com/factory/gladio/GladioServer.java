package com.factory.gladio;

import com.factorio.renderer.Texture;
import com.factorio.xserver.Drawable;
import com.factorio.xserver.Window;
import com.factorio.xserver.XServer;

/** The native Gladio server (cpp/gladio): one socket for every game process, GL contexts on the app's EGL. */
public final class GladioServer {
    /** The phone's own ANGLE (GLES on Vulkan), which apps may not load from /system: copied to files/angle first. */
    private static final String[] ANGLE_LIBRARIES = {"libGLESv2_angle.so", "libEGL_angle.so"};   // in load order
    private static final java.io.File SYSTEM_LIB = new java.io.File("/system/lib64");
    /** Other library folders some phones (MediaTek ones among them) keep ANGLE in. */
    private static final String[] ANGLE_DIRS = {"/system/lib64", "/system_ext/lib64", "/product/lib64", "/vendor/lib64"};
    /** ANGLE as an app (Android 10–14 on many phones): the libraries are inside its APK, lib/arm64-v8a/. */
    private static final String[] ANGLE_PACKAGES = {"com.android.angle", "com.google.android.angle"};

    private static boolean loaded;
    private static boolean angle;
    private static boolean angleSearched;
    private static java.io.File angleSource;   // a library folder or an APK; null without ANGLE

    /** Where the phone keeps ANGLE (searched once per process), or null. */
    public static synchronized java.io.File angleSource(android.content.Context context) {
        if (angleSearched) return angleSource;
        angleSearched = true;
        for (String path : ANGLE_DIRS) {
            java.io.File dir = new java.io.File(path);
            boolean all = true;
            for (String name : ANGLE_LIBRARIES) all &= new java.io.File(dir, name).isFile();
            if (all) return angleSource = dir;
        }
        for (String name : ANGLE_PACKAGES) {
            try {
                java.io.File apk = new java.io.File(context.getPackageManager().getApplicationInfo(name, 0).sourceDir);
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
                    boolean all = true;
                    for (String library : ANGLE_LIBRARIES) all &= zip.getEntry("lib/arm64-v8a/"+library) != null;
                    if (all) return angleSource = apk;
                }
            }
            catch (Exception e) {
                // not installed, or its APK can't be read
            }
        }
        return null;
    }

    /** Whether the phone has ANGLE: in a system library folder or as the ANGLE app. */
    public static boolean angleAvailable(android.content.Context context) {
        return angleSource(context) != null;
    }

    /** Whether ANGLE is in /system/lib64, where libhybris finds it by name. */
    public static boolean angleInSystemLib(android.content.Context context) {
        return SYSTEM_LIB.equals(angleSource(context));
    }

    /** files/angle: the copies of ANGLE's libraries, which the app and the game may load. */
    public static java.io.File angleDir(android.content.Context context) {
        return new java.io.File(context.getFilesDir(), "angle");
    }

    /** Copies ANGLE into files/angle again when the system's copy changed (a system update). */
    public static synchronized java.io.File installAngle(android.content.Context context) throws java.io.IOException {
        java.io.File source = angleSource(context);
        if (source == null) throw new java.io.IOException("no ANGLE on this phone");
        java.io.File dir = angleDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create "+dir);
        StringBuilder stamp = new StringBuilder(source.getPath());
        if (source.isDirectory()) {
            for (String name : ANGLE_LIBRARIES) {
                java.io.File file = new java.io.File(source, name);
                stamp.append(' ').append(file.length()).append(' ').append(file.lastModified());
            }
        }
        else stamp.append(' ').append(source.length()).append(' ').append(source.lastModified());
        java.io.File stampFile = new java.io.File(dir, ".source");
        boolean copied = true;
        for (String name : ANGLE_LIBRARIES) copied &= new java.io.File(dir, name).isFile();
        if (copied && stamp.toString().equals(com.factory.core.FileUtils.readString(stampFile))) return dir;

        java.util.zip.ZipFile zip = source.isDirectory() ? null : new java.util.zip.ZipFile(source);
        try {
            for (String name : ANGLE_LIBRARIES) {
                java.io.File copy = new java.io.File(dir, name), part = new java.io.File(dir, name+".part");
                if (zip == null) {
                    if (!com.factory.core.FileUtils.copy(new java.io.File(source, name), part)) throw new java.io.IOException("cannot copy "+name);
                }
                else {
                    try (java.io.InputStream in = zip.getInputStream(zip.getEntry("lib/arm64-v8a/"+name));
                         java.io.OutputStream out = new java.io.FileOutputStream(part)) {
                        byte[] buffer = new byte[1 << 16];
                        int length;
                        while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
                    }
                }
                copy.delete();
                if (!part.renameTo(copy)) throw new java.io.IOException("cannot copy "+name);
            }
        }
        finally {
            if (zip != null) zip.close();
        }
        com.factory.core.FileUtils.writeString(stampFile, stamp.toString());
        return dir;
    }

    /**
     * Loads the server once per process: on ANGLE when [wantAngle] (Settings → Gladio → ANGLE, Termux:X11 display),
     * else on the phone's GLES driver. A later change of the setting needs the app restarted. Returns whether ANGLE
     * is in use; ANGLE that fails to load falls back to the phone's driver.
     */
    public static synchronized boolean load(android.content.Context context, boolean wantAngle) {
        if (loaded) return angle;
        if (context != null && wantAngle && angleAvailable(context)) {
            try {
                java.io.File dir = installAngle(context);
                for (String name : ANGLE_LIBRARIES) System.load(new java.io.File(dir, name).getPath());
                System.loadLibrary("gladioserver_angle");
                angle = true;
            }
            catch (Throwable e) {
                android.util.Log.w("Gladio", "ANGLE didn't load, the phone's GLES driver serves instead", e);
            }
        }
        if (!angle) System.loadLibrary("gladioserver");
        loaded = true;
        return angle;
    }

    public static synchronized boolean usingAngle() {
        return loaded && angle;
    }

    /** The X server inside the app while the game runs on it (Gladio "X server" display), else null. */
    private static volatile XServer xServer;

    private GladioServer() {}

    /** Listens on the given socket path (once per app process); null, or why it can't. */
    static native String start(String socketPath);

    /** On the X server renderer's GL thread: Gladio's contexts share this EGL context, so frames go into its textures. */
    private static native void shareCurrentContext();

    private static volatile java.util.concurrent.CountDownLatch rendererReady = new java.util.concurrent.CountDownLatch(1);

    /** The X server's renderer has its EGL context (GL thread): from now on Gladio's contexts are created sharing it. */
    public static void onRendererReady() {
        load(null, false);    // the app's X server: its renderer is on the phone's driver, so Gladio is too
        shareCurrentContext();
        rendererReady.countDown();
    }

    /** Before the game starts on the app's X server: its OpenGL contexts must be created after the renderer's. */
    public static boolean awaitRenderer(long millis) throws InterruptedException {
        return rendererReady.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static void setXServer(XServer server) {
        xServer = server;
        if (server == null) rendererReady = new java.util.concurrent.CountDownLatch(1);
    }

    /** Called by the server (its GL thread): width << 16 | height of an X window, 0 = unknown. */
    @SuppressWarnings("unused")
    private static int windowSize(int windowId) {
        XServer server = xServer;
        if (server == null) return 0;
        Window window = server.windowManager.getWindow(windowId);
        Drawable drawable = window != null ? window.getContent() : server.drawableManager.getDrawable(windowId);
        if (drawable == null) return 0;
        return (drawable.width & 0xffff) << 16 | (drawable.height & 0xffff);
    }

    /**
     * Called by the server at a swap, on its GL thread with the game's frame in the read framebuffer: the GPU copies it
     * into the window's texture (as Winlator's LegacyGLXExtension). false = the window changed size, set it up again.
     */
    @SuppressWarnings("unused")
    private static boolean updateWindowContent(int drawableId, short width, short height) {
        XServer server = xServer;
        if (server == null) return true;
        Drawable drawable = server.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            Window window = server.windowManager.getWindow(drawableId);
            drawable = window != null ? window.getContent() : null;
        }
        if (drawable == null) return true;

        synchronized (drawable.renderLock) {
            if (drawable.width != width || drawable.height != height) return false;
            drawable.setData(null);
            Texture texture = drawable.getTexture();
            texture.setFlipY(true);
            texture.copyFromReadBuffer(width, height);
            Runnable onDrawListener = drawable.getOnDrawListener();
            if (onDrawListener != null) onDrawListener.run();
        }
        return true;
    }
}
