package com.factorio.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Makes the app independent of its package name.
 *
 * The prebuilt guest files (glibc, Mesa, drivers...) contain absolute paths that were baked at build time.
 * Instead of "/data/data/com.some.package/files/rootfs" they carry the fixed 36-byte {@link #MARKER}.
 * While an archive is extracted, every MARKER is replaced (same length, so binaries stay valid) with
 *
 *     /data/data/<package>/r////...      (padded with '/' up to 36 bytes, repeated slashes are harmless)
 *
 * where "<dataDir>/r" is a symlink to the real rootfs directory created by {@link #init(Context)}.
 * Because of this the package name can be changed at any time (MT Manager, apktool, applicationId...),
 * as long as it is at most {@link #MAX_PACKAGE_LENGTH} characters long. Existing rootfs installed under
 * another package name does not need to be changed: it is simply installed again on first start.
 */
public abstract class PackagePath {
    /** Exactly 36 bytes: the length of "/data/data/com.factorio/files/rootfs". */
    public static final String MARKER = "/data/data/@@@@@@@@@@@@@@@@@@@@@@@@@";
    public static final String LINK_NAME = "r";
    public static final int MAX_PACKAGE_LENGTH = MARKER.length() - "/data/data/".length() - 1 - LINK_NAME.length();

    // Old hardcoded paths that are accepted too (upstream Winlator components, builds made for com.factorio).
    // All of them have the same length as MARKER, so they can be replaced in place.
    private static final String[] MARKERS = {
        MARKER,
        "/data/data/com.winlator/files/rootfs",
        "/data/data/com.factorio/files/rootfs"
    };
    private static final byte[][] MARKER_BYTES = new byte[MARKERS.length][];
    private static final int MARKER_LENGTH = MARKER.length();

    static {
        for (int i = 0; i < MARKERS.length; i++) {
            if (MARKERS[i].length() != MARKER_LENGTH) throw new IllegalStateException("marker length mismatch: "+MARKERS[i]);
            MARKER_BYTES[i] = MARKERS[i].getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static volatile byte[] replacementBytes;
    private static volatile String replacement;

    /** Call once on app start (before any archive is extracted). Returns false if the package name is too long. */
    public static synchronized boolean init(Context context) {
        String packageName = context.getPackageName();
        if (packageName.length() > MAX_PACKAGE_LENGTH) {
            replacement = null;
            replacementBytes = null;
            return false;
        }

        StringBuilder sb = new StringBuilder("/data/data/").append(packageName).append('/').append(LINK_NAME);
        while (sb.length() < MARKER.length()) sb.append('/');
        replacement = sb.toString();
        replacementBytes = replacement.getBytes(StandardCharsets.US_ASCII);

        File rootDir = new File(context.getFilesDir(), "rootfs");
        File link = new File(context.getFilesDir().getParentFile(), LINK_NAME);
        String linkTarget = FileUtils.readSymlink(link);
        if (!rootDir.getPath().equals(linkTarget)) FileUtils.symlink(rootDir.getPath(), link.getPath());
        return true;
    }

    public static boolean isReady() {
        return replacementBytes != null;
    }

    /** Replaces the marker in a string (symlink targets, text). */
    public static String rewrite(String value) {
        if (value == null || replacement == null) return value;
        for (String marker : MARKERS) value = value.replace(marker, replacement);
        return value;
    }

    /** Replaces every marker inside buf[0, length) in place. */
    private static void rewrite(byte[] buf, int length) {
        final byte[] repl = replacementBytes;
        for (int start = 0; start + MARKER_LENGTH <= length; start++) {
            if (buf[start] != (byte)'/') continue;

            for (byte[] marker : MARKER_BYTES) {
                boolean match = true;
                for (int i = 1; i < MARKER_LENGTH; i++) {
                    if (buf[start + i] != marker[i]) {
                        match = false;
                        break;
                    }
                }

                if (match) {
                    System.arraycopy(repl, 0, buf, start, MARKER_LENGTH);
                    start += MARKER_LENGTH - 1;
                    break;
                }
            }
        }
    }

    /** Copies a stream and replaces markers on the fly (also when a marker is split between two chunks). */
    public static boolean copy(InputStream in, OutputStream out) {
        if (replacementBytes == null) return false;
        final int keepMax = MARKER_LENGTH - 1;
        final int chunkSize = 64 * 1024;
        byte[] buf = new byte[chunkSize + MARKER_LENGTH];
        int carry = 0;

        try {
            while (true) {
                int count = in.read(buf, carry, chunkSize);
                if (count < 0) {
                    if (carry > 0) {
                        rewrite(buf, carry);
                        out.write(buf, 0, carry);
                    }
                    return true;
                }

                int length = carry + count;
                rewrite(buf, length);

                carry = Math.min(length, keepMax);
                out.write(buf, 0, length - carry);
                System.arraycopy(buf, length - carry, buf, 0, carry);
            }
        }
        catch (IOException e) {
            return false;
        }
    }
}
