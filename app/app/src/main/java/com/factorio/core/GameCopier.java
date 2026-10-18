package com.factorio.core;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Copies the game folder and, depending on the chosen mode, shrinks its PNG textures on the way. */
public final class GameCopier {
    private static final String TAG = "GameCopier";

    public interface Progress {
        void onProgress(int percent);
    }

    private GameCopier() {}

    private static void collect(File dir, List<File> dirs, List<File> files) {
        String[] names = dir.list();
        if (names == null) return;
        for (String name : names) {
            File file = new File(dir, name);
            if (FileUtils.isSymlink(file)) continue; // like FileUtils.copy: symlinks are skipped
            if (file.isDirectory()) {
                dirs.add(file);
                collect(file, dirs, files);
            }
            else files.add(file);
        }
    }

    private static File target(File srcDir, File dstDir, File file) {
        String relative = file.getPath().substring(srcDir.getPath().length());
        while (relative.startsWith("/")) relative = relative.substring(1);
        return new File(dstDir, relative);
    }

    /**
     * @param mode one of GameCompression.ORIGINAL / LOW / HIGH
     * @return true when every file was copied
     */
    public static boolean copy(final File srcDir, final File dstDir, int mode, final Progress progress) {
        final List<File> dirs = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        collect(srcDir, dirs, files);

        if (!dstDir.exists() && !dstDir.mkdirs()) return false;
        for (File dir : dirs) {
            File dst = target(srcDir, dstDir, dir);
            if (!dst.exists() && !dst.mkdirs()) return false;
        }

        long sum = 0;
        for (File file : files) sum += file.length();
        final long totalBytes = Math.max(1, sum);

        final int ratio = GameCompression.textureRatio(mode);
        final int minColors = GameCompression.textureMinColors(mode);
        final boolean shrink = ratio > 0 && TextureCompressor.isAvailable();
        int cpus = Runtime.getRuntime().availableProcessors();
        int threads = shrink ? Math.max(2, Math.min(3, cpus / 2)) : 2; // shrinking needs memory: keep few workers

        final AtomicLong doneBytes = new AtomicLong();
        final AtomicLong outBytes = new AtomicLong();
        final AtomicInteger shrunk = new AtomicInteger();
        final AtomicInteger lastPercent = new AtomicInteger(-1);
        final AtomicBoolean failed = new AtomicBoolean();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (final File src : files) {
            futures.add(pool.submit(() -> {
                if (failed.get()) return;
                File dst = target(srcDir, dstDir, src);
                long size = src.length();

                boolean written = false;
                if (shrink && src.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    try {
                        written = TextureCompressor.shrinkPng(src.getPath(), dst.getPath(), ratio, minColors) == 0;
                        if (written) shrunk.incrementAndGet();
                    }
                    catch (Throwable e) {
                        Log.e(TAG, "Cannot shrink "+src, e);
                    }
                }
                if (!written && !FileUtils.copy(src, dst)) failed.set(true);

                outBytes.addAndGet(dst.length());
                int percent = (int)(doneBytes.addAndGet(size) * 100 / totalBytes);
                if (progress != null && percent != lastPercent.getAndSet(percent)) progress.onProgress(Math.min(percent, 100));
            }));
        }
        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            }
            catch (Exception e) {
                Log.e(TAG, "Copy task failed", e);
                failed.set(true);
            }
        }

        Log.i(TAG, "game copy: "+files.size()+" files, "+(sum >> 20)+" MB -> "+(outBytes.get() >> 20)+" MB, textures shrunk: "+shrunk.get());
        return !failed.get();
    }
}
