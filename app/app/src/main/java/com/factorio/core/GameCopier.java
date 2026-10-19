package com.factorio.core;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Copies the game folder and, depending on the chosen mode, shrinks its PNG textures on the way. */
public final class GameCopier {
    private static final String TAG = "GameCopier";
    private static final int MAX_LISTED_SKIPS = 3000;

    public interface Progress {
        void onProgress(int percent);
    }

    /** What happened: the numbers for the toast and the text for compression-report.txt. */
    public static final class Result {
        public boolean ok;
        public int files;
        public int pngTotal, pngShrunk, skipFormat, skipSmall, skipGain, pngErrors;
        public long inBytes, outBytes;
        public String report = "";
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

    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "(no ext)";
    }

    private static void addStat(Map<String, long[]> stats, String key, long size) {
        long[] entry = stats.computeIfAbsent(key, (k) -> new long[2]);
        synchronized (entry) {
            entry[0]++;
            entry[1] += size;
        }
    }

    /**
     * @param mode one of GameCompression.ORIGINAL / LOW / HIGH
     */
    public static Result copy(final File srcDir, final File dstDir, final int mode, final Progress progress) {
        final Result result = new Result();
        final List<File> dirs = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        collect(srcDir, dirs, files);
        result.files = files.size();

        if (!dstDir.exists() && !dstDir.mkdirs()) return result;
        for (File dir : dirs) {
            File dst = target(srcDir, dstDir, dir);
            if (!dst.exists() && !dst.mkdirs()) return result;
        }

        long sum = 0;
        for (File file : files) sum += file.length();
        final long totalBytes = Math.max(1, sum);

        final int ratio = GameCompression.textureRatio(mode);
        final int minColors = GameCompression.textureMinColors(mode);
        final int kmeans = GameCompression.textureKmeans(mode);
        final int dither = GameCompression.textureDither(mode);
        final boolean shrink = ratio > 0 && TextureCompressor.isAvailable();
        int cpus = Runtime.getRuntime().availableProcessors();
        int threads = shrink ? Math.max(2, Math.min(3, cpus / 2)) : 2; // shrinking needs memory: keep few workers

        final AtomicLong doneBytes = new AtomicLong();
        final AtomicLong outBytes = new AtomicLong();
        final AtomicInteger pngTotal = new AtomicInteger(), pngShrunk = new AtomicInteger(), skipFormat = new AtomicInteger();
        final AtomicInteger skipSmall = new AtomicInteger(), skipGain = new AtomicInteger(), pngErrors = new AtomicInteger();
        final AtomicInteger lastPercent = new AtomicInteger(-1);
        final AtomicBoolean failed = new AtomicBoolean();
        final ConcurrentLinkedQueue<String> skipped = new ConcurrentLinkedQueue<>();
        final Map<String, long[]> untouched = new ConcurrentHashMap<>(); // extension -> {count, bytes} of files copied as they are

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (final File src : files) {
            futures.add(pool.submit(() -> {
                if (failed.get()) return;
                File dst = target(srcDir, dstDir, src);
                long size = src.length();

                boolean isPng = src.getName().toLowerCase(Locale.ROOT).endsWith(".png");
                boolean written = false;
                String why = null;
                if (isPng) pngTotal.incrementAndGet();
                if (shrink && isPng) {
                    int rc;
                    try {
                        rc = TextureCompressor.shrinkPng(src.getPath(), dst.getPath(), ratio, minColors, kmeans, dither);
                    }
                    catch (Throwable e) {
                        Log.e(TAG, "Cannot shrink "+src, e);
                        rc = -1;
                    }
                    switch (rc) {
                        case TextureCompressor.OK: written = true; pngShrunk.incrementAndGet(); break;
                        case TextureCompressor.SKIP_FORMAT: why = "format (not 8-bit PNG / interlaced / too big)"; skipFormat.incrementAndGet(); break;
                        case TextureCompressor.SKIP_SMALL: why = "tiny file"; skipSmall.incrementAndGet(); break;
                        case TextureCompressor.SKIP_GAIN: why = "gain too small"; skipGain.incrementAndGet(); break;
                        default: why = "error "+rc; pngErrors.incrementAndGet(); break;
                    }
                }
                if (!written) {
                    if (!FileUtils.copy(src, dst)) failed.set(true);
                    addStat(untouched, extension(src), size);
                    if (why != null && skipped.size() < MAX_LISTED_SKIPS) {
                        skipped.add(why+"\t"+size+"\t"+FileUtils.toRelativePath(srcDir.getPath(), src.getPath()));
                    }
                }

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

        result.ok = !failed.get();
        result.pngTotal = pngTotal.get(); result.pngShrunk = pngShrunk.get();
        result.skipFormat = skipFormat.get(); result.skipSmall = skipSmall.get(); result.skipGain = skipGain.get(); result.pngErrors = pngErrors.get();
        result.inBytes = sum; result.outBytes = outBytes.get();

        StringBuilder sb = new StringBuilder();
        sb.append("Game copy report\n");
        sb.append("mode: ").append(mode == GameCompression.HIGH ? "high (x"+ratio+")" : (mode == GameCompression.LOW ? "low (x"+ratio+")" : "original")).append('\n');
        sb.append("files: ").append(result.files).append(", size: ").append(sum >> 20).append(" MB -> ").append(result.outBytes >> 20).append(" MB\n");
        if (ratio > 0) {
            sb.append("texture compressor available: ").append(shrink).append('\n');
            sb.append("PNG files: ").append(result.pngTotal).append(", shrunk: ").append(result.pngShrunk)
              .append(", kept as they are: format ").append(result.skipFormat).append(", tiny ").append(result.skipSmall)
              .append(", small gain ").append(result.skipGain).append(", errors ").append(result.pngErrors).append('\n');
        }
        sb.append("\nCopied unchanged (extension: files, MB):\n");
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(untouched.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        for (Map.Entry<String, long[]> entry : entries) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()[0]).append(" files, ").append(entry.getValue()[1] >> 20).append(" MB\n");
        }
        if (!skipped.isEmpty()) {
            sb.append("\nPNG files kept as they are (reason, bytes, path):\n");
            for (String line : skipped) sb.append(line).append('\n');
        }
        result.report = sb.toString();

        Log.i(TAG, "game copy: "+result.files+" files, "+(sum >> 20)+" MB -> "+(result.outBytes >> 20)+" MB, png shrunk "+result.pngShrunk+"/"+result.pngTotal);
        return result;
    }
}
