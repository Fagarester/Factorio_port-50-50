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

    private static boolean loaded;
    private static boolean angle;

    /** Whether the phone has ANGLE in /system (Android 10+ on most phones). */
    public static boolean angleAvailable() {
        for (String name : ANGLE_LIBRARIES) if (!new java.io.File(SYSTEM_LIB, name).isFile()) return false;
        return true;
    }

    /**
     * Loads the server once per process: on ANGLE when [wantAngle] (Settings → Gladio → ANGLE, Termux:X11 display),
     * else on the phone's GLES driver. A later change of the setting needs the app restarted. Returns whether ANGLE
     * is in use; ANGLE that fails to load falls back to the phone's driver.
     */
    public static synchronized boolean load(android.content.Context context, boolean wantAngle) {
        if (loaded) return angle;
        if (context != null && wantAngle && angleAvailable()) {
            try {
                java.io.File dir = new java.io.File(context.getFilesDir(), "angle");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create "+dir);
                for (String name : ANGLE_LIBRARIES) {
                    java.io.File source = new java.io.File(SYSTEM_LIB, name), copy = new java.io.File(dir, name);
                    // a system update brings a new ANGLE: copy again when the size or date differ
                    if (copy.length() != source.length() || copy.lastModified() != source.lastModified()) {
                        java.io.File part = new java.io.File(dir, name+".part");
                        if (!com.factory.core.FileUtils.copy(source, part) || !part.renameTo(copy))
                            throw new java.io.IOException("cannot copy "+source);
                        copy.setLastModified(source.lastModified());
                    }
                    System.load(copy.getPath());
                }
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
