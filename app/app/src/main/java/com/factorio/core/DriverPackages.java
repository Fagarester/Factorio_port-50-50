package com.factorio.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs a driver from a file picked by the user and decides what it IS by its content (not by its name):
 * a zstd tar (.tzst) with usr/lib/libvulkan_freedreno.so is Turnip, with usr/lib/libGL.so* it is Zink,
 * or Gladio when that libGL contains the Gladio marker string.
 */
public abstract class DriverPackages {
    public enum Kind {TURNIP, ZINK, GLADIO}

    public static class InstallResult {
        public final Kind kind;
        public final String id;

        InstallResult(Kind kind, String id) {
            this.kind = kind;
            this.id = id;
        }
    }

    public static String sanitizeId(String name) {
        String id = name.trim().replaceAll("[/\\\\,=|:*?\"<>\\p{Cntrl}]", "_");
        if (id.length() > 80) id = id.substring(0, 80).trim();
        while (id.startsWith(".")) id = id.substring(1);
        return id;
    }

    public static String queryDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        catch (Exception e) {}
        String path = uri.getLastPathSegment();
        return path != null ? path.substring(path.lastIndexOf('/')+1) : "";
    }

    /** Checks the zstd signature and installs the archive as Turnip or Zink. */
    public static InstallResult install(Context context, Uri uri) throws IOException {
        String displayName = queryDisplayName(context, uri);
        try (InputStream rawStream = context.getContentResolver().openInputStream(uri)) {
            if (rawStream == null) throw new IOException("Cannot open the file");
            BufferedInputStream in = new BufferedInputStream(rawStream, 1 << 16);
            in.mark(16);
            byte[] magic = new byte[4];
            int count = 0, read;
            while (count < 4 && (read = in.read(magic, count, 4 - count)) > 0) count += read;
            in.reset();

            if (count == 4 && (magic[0] & 0xff) == 0x28 && (magic[1] & 0xff) == 0xB5 && (magic[2] & 0xff) == 0x2F && (magic[3] & 0xff) == 0xFD) {
                return installGlibcArchive(context, uri, in, displayName);
            }
            throw new IOException("Unknown driver format (expected .tzst)");
        }
    }

    /**
     * glibc packages for the rootfs are told apart by the files inside:
     *   usr/lib/libvulkan_freedreno.so -> Turnip,   usr/lib/libGL.so* -> Zink (OpenGL on top of Vulkan).
     */
    // Gladio's libGL reads this variable (Mesa / Zink never has it)
    private static final byte[] GLADIO_MARKER = "GLADIO_NO_ERROR".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Streams the current tar entry and looks for the marker bytes (works across read-buffer boundaries). */
    private static boolean containsMarker(InputStream in, byte[] marker) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int matched = 0, length;
        while ((length = in.read(buffer)) > 0) {
            for (int i = 0; i < length; i++) {
                if (buffer[i] == marker[matched]) {
                    if (++matched == marker.length) return true;
                }
                else matched = buffer[i] == marker[0] ? 1 : 0;
            }
        }
        return false;
    }

    private static Kind sniffGlibcArchive(Context context, Uri uri) throws IOException {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Cannot open the file");
            try (InputStream zstd = new ZstdCompressorInputStream(new BufferedInputStream(raw, 1 << 16));
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                TarArchiveEntry entry;
                boolean hasGL = false, isGladio = false;
                while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                    String name = entry.getName();
                    name = name.substring(name.lastIndexOf('/') + 1);
                    if (name.equals("libvulkan_freedreno.so")) return Kind.TURNIP;
                    if (name.startsWith("libGL.so") && entry.isFile()) {
                        hasGL = true;
                        if (containsMarker(tar, GLADIO_MARKER)) isGladio = true;
                    }
                }
                if (isGladio) return Kind.GLADIO;
                if (hasGL) return Kind.ZINK;
            }
        }
        throw new IOException("The archive has neither libvulkan_freedreno.so (Turnip) nor libGL.so (Zink / Gladio)");
    }

    private static InstallResult installGlibcArchive(Context context, Uri uri, InputStream in, String displayName) throws IOException {
        Kind kind = sniffGlibcArchive(context, uri);
        GeneralComponents.Type type = GeneralComponents.typeOf(kind);
        String prefix = type.name().toLowerCase(java.util.Locale.ENGLISH);

        String base = displayName != null ? displayName : "";
        base = base.replaceAll("(?i)(\\.tar)?\\.(tzst|zst)$", "");

        String id;
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)+(?:-[A-Za-z0-9.]+)*)").matcher(base);
        if (matcher.find()) id = matcher.group(1);
        else {
            id = sanitizeId(base.replaceAll("(?i)^"+prefix+"-?", ""));
            if (id.isEmpty()) id = "custom-"+(System.currentTimeMillis() / 1000);
        }

        // the built-in versions (e.g. Turnip 26.2.0 / 26.2.4 / 26.3.0-beta, Zink 26.2.0) are known-good builds and always win
        // over a file with the same name, so a rebuilt driver would be ignored
        if (GeneralComponents.isBuiltinComponent(type, id)) id += "-custom";

        File dir = GeneralComponents.getComponentDir(type, context);
        File destination = new File(dir, prefix+"-"+id+".tzst");
        File tmp = new File(dir, ".tmp-"+destination.getName());
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buffer = new byte[1 << 16];
            int length;
            while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
        }
        if (destination.exists()) destination.delete();
        if (!tmp.renameTo(destination)) {
            tmp.delete();
            throw new IOException("Cannot save "+destination.getName());
        }
        return new InstallResult(kind, id);
    }
}
