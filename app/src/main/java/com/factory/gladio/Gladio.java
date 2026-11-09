package com.factory.gladio;

import android.content.Context;

import com.factory.core.FileUtils;
import com.factory.runtime.Paths;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Gladio: the game's OpenGL runs on the phone's own GLES driver. The client (assets/gladio/libGL.so.1, built from
 * runtime/gladio) replaces Mesa's libGL in the game; it sends the GL calls to the server here (libgladioserver.so)
 * and puts the finished frames into its Termux:X11 window.
 */
public final class Gladio {
    private static final String ASSET = "gladio/libGL.so.1";
    private static final String DIRECT_ASSETS = "gladio-direct";

    private Gladio() {}

    public static File socket(Context context) {
        return new File(new Paths(context).getTmp(), "gladio");
    }

    public static File libraryDir(Context context) {
        return new File(context.getFilesDir(), "gladio");
    }

    /** The server as a glibc program with libhybris (assets/gladio-direct, tools/build-gladio-direct.sh). */
    public static File directDir(Context context) {
        return new File(context.getFilesDir(), "gladio-direct");
    }

    /** libhybris reads Android's properties from this file (name=value lines): on its own it sees only build.prop's. */
    public static File directProperties(Context context) {
        return new File(directDir(context), "android.prop");
    }

    private static void installDirect(Context context, String installed) throws java.io.IOException {
        File dir = directDir(context);
        File stamp = new File(dir, ".installed");
        if (!installed.equals(FileUtils.readString(stamp))) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Gladio: cannot create "+dir);
            String[] names = context.getAssets().list(DIRECT_ASSETS);
            if (names == null || names.length == 0) throw new java.io.IOException("Gladio: no "+DIRECT_ASSETS+" in the app");
            for (String name : names) {
                File target = new File(dir, name), part = new File(dir, name+".part");
                try (InputStream in = context.getAssets().open(DIRECT_ASSETS+"/"+name); OutputStream out = new java.io.FileOutputStream(part)) {
                    byte[] buffer = new byte[1 << 16];
                    int length;
                    while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
                }
                target.delete();
                if (!part.renameTo(target)) throw new java.io.IOException("Gladio: cannot install "+target);
            }
            FileUtils.writeString(stamp, installed);
        }
        // every start: a system update changes them
        StringBuilder properties = new StringBuilder();
        Process getprop = new ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(getprop.getInputStream()))) {
            java.util.regex.Pattern line = java.util.regex.Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$");
            String text;
            while ((text = reader.readLine()) != null) {
                java.util.regex.Matcher m = line.matcher(text);
                if (m.matches()) properties.append(m.group(1)).append('=').append(m.group(2)).append('\n');
            }
        }
        try {
            getprop.waitFor();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        FileUtils.writeString(directProperties(context), properties.toString());
    }

    /** Copies the client after every app update, then starts the server; throws with the reason when it can't. */
    public static void prepare(Context context) throws java.io.IOException {
        File dir = libraryDir(context);
        File library = new File(dir, "libGL.so.1");
        // a new APK may carry a new client: copied again after every app update
        File stamp = new File(dir, ".installed");
        String installed;
        try {
            installed = String.valueOf(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        }
        catch (Exception e) {
            installed = "?";
        }
        if (!library.isFile() || !installed.equals(FileUtils.readString(stamp))) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Gladio: cannot create "+dir);
            File part = new File(dir, "libGL.so.1.part");
            try (InputStream in = context.getAssets().open(ASSET); OutputStream out = new java.io.FileOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int length;
                while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
            }
            library.delete();
            if (!part.renameTo(library)) throw new java.io.IOException("Gladio: cannot install "+library);
            FileUtils.symlink("libGL.so.1", new File(dir, "libGL.so").getPath());
            FileUtils.writeString(stamp, installed);
        }
        File socket = socket(context);
        File socketDir = socket.getParentFile();
        if (socketDir != null) socketDir.mkdirs();
        // the phone's driver directly (test): the server is in the game's process (libgladio-direct.so) or, with
        // GLADIO_DIRECT_PROCESS=1, a program beside it (Session starts it); not in here
        if (com.factory.runtime.Drivers.INSTANCE.gladioDirect(context)) {
            installDirect(context, installed);
            // ANGLE outside /system/lib64: libhybris loads the copies in files/angle by their full path
            if (com.factory.runtime.Drivers.INSTANCE.gladioDirectAngle(context) && !GladioServer.angleInSystemLib(context))
                GladioServer.installAngle(context);
            return;
        }
        String error;
        try {
            // ANGLE only on Termux:X11: on the app's X server Gladio shares the renderer's context (the phone's driver)
            boolean angle = com.factory.runtime.Settings.INSTANCE.gladioAngle(context) &&
                !"xserver".equals(com.factory.runtime.Settings.INSTANCE.gladioDisplay(context));
            if (GladioServer.load(context, angle) != angle) android.util.Log.i("Gladio", "ANGLE: wanted "+angle+", in use "+GladioServer.usingAngle());
            error = GladioServer.start(socket.getPath());
        }
        catch (Throwable e) {
            error = e.toString();
        }
        if (error != null) throw new java.io.IOException("Gladio server: "+error);
    }
}
