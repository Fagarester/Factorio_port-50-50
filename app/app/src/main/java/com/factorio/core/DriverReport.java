package com.factorio.core;

import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * Ground truth about the driver libraries that are really on disk (and therefore really loaded):
 * size, SHA-256 and the "Mesa x.y.z (git-...)" string that Mesa compiles into every driver.
 * Written to Documents/Factorio/driver-report.txt at every start, so "is this the old driver or the new one?"
 * can be answered from the file, not guessed.
 */
public abstract class DriverReport {
    private static final String TAG = "DriverReport";

    public static String describe(File file) {
        try {
            File real = file.getCanonicalFile();
            if (!real.isFile()) return "  "+file.getName()+": MISSING";

            byte[] data = new byte[(int)real.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(real))) {
                in.readFully(data);
            }

            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b));

            return "  "+file.getName()+" -> "+real.getName()+"\n"
                + "    size: "+data.length+" bytes\n"
                + "    sha256: "+hex.substring(0, 16)+"...\n"
                + "    built from: "+findMesaVersion(data);
        }
        catch (Exception e) {
            return "  "+file.getName()+": cannot read ("+e+")";
        }
    }

    /** Finds "Mesa <digit>..." in the binary, e.g. "Mesa 26.2.4 (git-9f0a761020)". */
    private static String findMesaVersion(byte[] data) {
        final byte[] key = {'M', 'e', 's', 'a', ' '};
        for (int i = 0; i + key.length + 1 < data.length; i++) {
            if (data[i] != 'M') continue;
            boolean match = true;
            for (int k = 1; k < key.length; k++) if (data[i + k] != key[k]) { match = false; break; }
            if (!match) continue;

            byte next = data[i + key.length];
            if (next < '0' || next > '9') continue;

            StringBuilder sb = new StringBuilder();
            for (int j = i; j < data.length && j < i + 80; j++) {
                int c = data[j] & 0xff;
                if (c < 32 || c > 126) break;
                sb.append((char)c);
            }
            if (sb.length() >= 11) return sb.toString();
        }
        return "(no Mesa version string found)";
    }

    public static void write(File dir, String text) {
        ;
        try {
            if (dir != null && dir.isDirectory() && dir.canWrite()) FileUtils.writeString(new File(dir, "driver-report.txt"), text);
        }
        catch (Exception e) {}
    }
}
