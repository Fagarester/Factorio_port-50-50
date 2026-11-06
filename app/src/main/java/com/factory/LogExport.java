package com.factory;

import android.content.Context;
import android.os.Environment;

import com.factory.runtime.Paths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The game's logs (files/logs: game.log, x11.log…) copied to Documents/Factory while the game runs and when it ends,
 * so they can be read or sent without the app. The original build writes to Documents/Factorio: kept apart on purpose.
 */
public final class LogExport {
    private LogExport() {}

    public static File directory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Factory");
    }

    public static synchronized void copy(Context context) {
        File[] logs = new Paths(context).getLogs().listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".txt"));
        if (logs == null || logs.length == 0) return;
        File target = directory();
        if (!target.isDirectory() && !target.mkdirs()) return;
        byte[] buffer = new byte[1 << 16];
        for (File log : logs) {
            File out = new File(target, log.getName());
            if (out.isFile() && out.length() == log.length() && out.lastModified() >= log.lastModified()) continue;
            try (InputStream in = new FileInputStream(log); OutputStream os = new FileOutputStream(out)) {
                int length;
                while ((length = in.read(buffer)) > 0) os.write(buffer, 0, length);
            }
            catch (Exception e) {
                // storage not available: the logs stay in the app's files (side drawer -> Logs)
            }
        }
    }
}
