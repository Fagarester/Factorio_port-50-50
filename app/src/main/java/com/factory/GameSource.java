package com.factory;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import com.factory.core.AppUtils;
import com.factory.core.FileUtils;
import com.factory.core.QuickHttps;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the game comes from on the first start: a folder with the unpacked Linux arm64 build, or the archive as
 * factorio.com gives it (factorio-space-age_linux-arm64_2.1.24.tar.xz). A quick search offers what it finds.
 */
public abstract class GameSource {
    public static final String DOWNLOAD_PAGE = "https://www.factorio.com/download";

    /** factorio_linux-arm64_2.1.24.tar.xz, factorio-space-age_linux-arm64_2.1.24.tar.xz… (not the headless server). */
    private static final Pattern ARCHIVE = Pattern.compile("(?i)factorio.*linux.*arm64.*\\.tar\\.xz");
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)\\.tar\\.xz$", Pattern.CASE_INSENSITIVE);

    /** The light search: Download a few folders deep, the storage's top two levels, a few seconds at most. */
    private static final int DOWNLOAD_DEPTH = 3;
    private static final int STORAGE_DEPTH = 2;
    private static final int MAX_FOLDERS = 4000;
    private static final long MAX_SEARCH_MS = 4000;
    /** Folders full of photos, music or other apps' data: the game is not there. */
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList("android", "dcim", "pictures", "movies", "music", "ringtones",
        "alarms", "notifications", "podcasts", "audiobooks", "recordings", "screenshots", "telegram", "whatsapp"));

    /** The game's own logos, shown on the copying screen as on the game's loading screen (the app has no game art). */
    public static final String LOGO = "data/core/graphics/background-image-logo.png";
    public static final String SPACE_AGE_LOGO = "data/space-age/graphics/space-age-logo-spritesheet.png";
    /** The Space Age logo's animation: frames one under another, played in a loop as on the game's loading screen. */
    private static final int SPACE_AGE_FRAMES = 16;

    /**
     * The columns from the first to the last with more than the drop shadow (dark) or nothing, or null: cut to them,
     * the letters of both logos fill the same width, as on the game's screen (Space Age's shadow tail is ~7 %).
     */
    private static int[] letterColumns(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] column = new int[height];
        int left = -1, right = -1;
        for (int x = 0; x < width; x++) {
            bitmap.getPixels(column, 0, 1, x, 0, 1, height);
            for (int pixel : column) {
                int alpha = pixel >>> 24, bright = Math.max((pixel >> 16) & 0xFF, Math.max((pixel >> 8) & 0xFF, pixel & 0xFF));
                if (alpha > 20 && bright > 60) {
                    if (left < 0) left = x;
                    right = x;
                    break;
                }
            }
        }
        return left < 0 ? null : new int[]{left, right};
    }

    private static Bitmap crop(Bitmap bitmap, int[] columns) {
        if (columns == null || (columns[0] == 0 && columns[1] == bitmap.getWidth() - 1)) return bitmap;
        return Bitmap.createBitmap(bitmap, columns[0], 0, columns[1] - columns[0] + 1, bitmap.getHeight());
    }

    public static Bitmap loadLogo(File file) {
        try {
            if (!file.isFile()) return null;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
            return bitmap != null ? crop(bitmap, letterColumns(bitmap)) : null;
        }
        catch (Throwable e) {
            return null;
        }
    }

    /** All the Space Age logo's frames, cut alike (the columns any frame uses), or null. */
    public static Bitmap[] loadSpaceAgeFrames(File file) {
        if (!file.isFile()) return null;
        try {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(file.getPath(), false);
            try {
                int width = decoder.getWidth(), height = decoder.getHeight() / SPACE_AGE_FRAMES;
                Bitmap[] frames = new Bitmap[SPACE_AGE_FRAMES];
                int[] columns = null;
                for (int i = 0; i < SPACE_AGE_FRAMES; i++) {
                    frames[i] = decoder.decodeRegion(new Rect(0, i * height, width, (i + 1) * height), null);
                    if (frames[i] == null) return null;
                    int[] used = letterColumns(frames[i]);
                    if (used != null) columns = columns == null ? used : new int[]{Math.min(columns[0], used[0]), Math.max(columns[1], used[1])};
                }
                for (int i = 0; i < SPACE_AGE_FRAMES; i++) frames[i] = crop(frames[i], columns);
                return frames;
            }
            finally {
                decoder.recycle();
            }
        }
        catch (Throwable e) {
            return null;
        }
    }

    /** factorio.com's logos: Factorio alone, and Factorio with Space Age in one picture. */
    private static final String WEB_LOGO = "https://cdn.factorio.com/assets/img/web/factorio-logo2.png";
    private static final String WEB_SPACE_AGE_LOGO = "https://cdn.factorio.com/assets/img/web/space-age-logo.png";

    /** Downloaded once per app start: the demo's download and then its unpacking show the same one. */
    private static final Bitmap[] webLogos = new Bitmap[2];

    public static synchronized Bitmap downloadedLogo(boolean spaceAge) {
        return webLogos[spaceAge ? 1 : 0];
    }

    /** The logo from factorio.com (the app carries no game art), or null without internet. */
    public static Bitmap downloadLogo(boolean spaceAge) {
        Bitmap known = downloadedLogo(spaceAge);
        if (known != null) return known;
        try {
            // all addresses at once: on a Wi-Fi with a broken IPv6 one by one took 30 s
            byte[] data = QuickHttps.get(spaceAge ? WEB_SPACE_AGE_LOGO : WEB_LOGO, 5000, 10000, 4 << 20);
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap != null) bitmap = crop(bitmap, letterColumns(bitmap));
            synchronized (GameSource.class) {
                webLogos[spaceAge ? 1 : 0] = bitmap;
            }
            return bitmap;
        }
        catch (Throwable e) {
            return null;
        }
    }

    /** factorio-space-age_linux-arm64_….tar.xz has the DLC, factorio_linux-arm64_….tar.xz not; no name: likely it has. */
    public static boolean isSpaceAgeArchive(String name) {
        return name == null || name.toLowerCase(Locale.ROOT).contains("space-age") || name.toLowerCase(Locale.ROOT).contains("space_age");
    }

    public static File downloads() {
        return new File(AppUtils.DIRECTORY_DOWNLOADS);
    }

    /** The unpacked game: its Linux arm64 program and its data. */
    public static boolean isGameFolder(File dir) {
        return new File(dir, "bin/arm64/factorio").isFile() && new File(dir, "data/core").isDirectory();
    }

    /** The folder itself or one of the folders right in it (the player picked the folder around "factorio"). */
    public static File gameFolderIn(File dir) {
        if (dir == null) return null;
        if (isGameFolder(dir)) return dir;
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) for (File child : children) if (isGameFolder(child)) return child;
        return null;
    }

    /** The newest game archive right in Download (no subfolders), or null. */
    public static File findArchive() {
        File[] files = downloads().listFiles((dir, name) -> ARCHIVE.matcher(name).matches() && !name.toLowerCase(Locale.ROOT).contains("headless"));
        if (files == null) return null;
        File best = null;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (best == null) best = file;
            else {
                // the full game before the demo, then the newest
                int order = Boolean.compare(isDemo(best.getName()), isDemo(file.getName()));
                if (order == 0) order = compareVersions(version(file.getName()), version(best.getName()));
                if (order > 0 || (order == 0 && file.lastModified() > best.lastModified())) best = file;
            }
        }
        return best;
    }

    /** The free demo from factorio.com: factorio-demo_linux-arm64_….tar.xz */
    public static final String DEMO_DOWNLOAD = "https://www.factorio.com/get-download/stable/demo/linux-arm64";

    public static boolean isDemo(String name) {
        return name.toLowerCase(Locale.ROOT).contains("demo");
    }

    private static int[] version(String name) {
        Matcher m = VERSION.matcher(name);
        if (!m.find()) return new int[0];
        String[] parts = m.group(1).split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            }
            catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * The game folder: Download/factorio first, then any folder a few levels into Download (named in any language),
     * then the storage's top two levels. Breadth first, so the shallowest one wins. Null if none.
     */
    public static File findFolder() {
        File usual = new File(downloads(), "factorio");
        if (isGameFolder(usual)) return usual;
        long deadline = SystemClock.uptimeMillis() + MAX_SEARCH_MS;
        int[] budget = {MAX_FOLDERS};
        File found = search(downloads(), DOWNLOAD_DEPTH, deadline, budget, null);
        if (found == null) found = search(Environment.getExternalStorageDirectory(), STORAGE_DEPTH, deadline, budget, downloads());
        return found;
    }

    private static File search(File root, int maxDepth, long deadline, int[] budget, File skip) {
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            int depth = depths.poll();
            if (budget[0]-- <= 0 || SystemClock.uptimeMillis() > deadline) return null;
            if (depth > 0 && isGameFolder(dir)) return dir;
            if (depth >= maxDepth) continue;
            File[] children = dir.listFiles(File::isDirectory);
            if (children == null) continue;
            Arrays.sort(children);
            for (File child : children) {
                String name = child.getName();
                if (name.startsWith(".") || SKIPPED.contains(name.toLowerCase(Locale.ROOT)) || child.equals(skip)) continue;
                if (com.factory.core.FileUtils.isSymlink(child)) continue;
                queue.add(child);
                depths.add(depth + 1);
            }
        }
        return null;
    }

    /**
     * The path of a folder picked with the system's folder picker ("primary:Download/factorio", "1234-ABCD:Games",
     * "raw:/storage/…"); the app reads the phone's storage as files, so it needs the path. Null if it has none.
     */
    public static File folderFromTree(Uri uri) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(uri);
        }
        catch (Exception e) {
            return null;
        }
        if (id == null) return null;
        if (id.startsWith("raw:")) return new File(id.substring(4));
        if (id.equals("downloads")) return downloads();
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon), path = id.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume) ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
        return path.isEmpty() ? base : new File(base, path);
    }
}
