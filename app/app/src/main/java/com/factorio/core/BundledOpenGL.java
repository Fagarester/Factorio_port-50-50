package com.factorio.core;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** App-private, versioned Mesa installation. Never replaces rootfs or system graphics libraries. */
public final class BundledOpenGL {
    private static final String ASSET = "graphics_driver/opengl-kgsl-r7.tzst";
    private static final long MAX_FILE_SIZE = 64L * 1024 * 1024;
    private BundledOpenGL() {}

    public static synchronized File prepare(Context context, File root) throws IOException {
        File target = new File(root, "opt/mesa-kgsl-r7");
        File ready = new File(target, ".ready");
        if (ready.isFile() && valid(target)) return target;
        File stage = new File(root, "opt/mesa-kgsl-r7-stage");
        FileUtils.delete(stage);
        if (!stage.mkdirs()) throw new IOException("Cannot create Mesa staging directory");
        try {
            // The asset already contains only lib/* and dri/kgsl_dri.so.
            final String stagePath = stage.getCanonicalPath() + File.separator;
            boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, ASSET, stage, (file, size) -> {
                try {
                    if (!file.getCanonicalPath().startsWith(stagePath)) return null; // invalid archive path
                } catch (IOException e) {
                    return null;
                }
                if (size > MAX_FILE_SIZE) return null; // oversized library; valid() will then fail
                // Share X11/XCB with SDL instead of shadowing rootfs libraries.
                String leaf = file.getName();
                if ((leaf.startsWith("libxcb-") || leaf.startsWith("libX11-xcb") || leaf.startsWith("libXxf86vm") || leaf.startsWith("libarchive"))
                        && new File(root, "usr/lib/" + leaf).isFile()) return null;
                return file;
            });
            if (!ok) throw new IOException("Cannot extract Mesa package");
            if (!valid(stage)) throw new IOException("Incomplete Mesa package");
            if (!new File(stage, ".ready").createNewFile()) throw new IOException("Cannot mark Mesa ready");
            FileUtils.delete(target);
            if (!stage.renameTo(target)) throw new IOException("Cannot activate Mesa package");
            return target;
        } finally {
            FileUtils.delete(stage);
        }
    }

    private static boolean valid(File directory) {
        return new File(directory, "lib/libGL.so.1").length() == 528640
            && new File(directory, "lib/libgallium-26.2.0-devel.so").length() == 22044232
            && new File(directory, "dri/kgsl_dri.so").length() == 133192;
    }
}
