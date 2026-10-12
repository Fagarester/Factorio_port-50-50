package com.factorio.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One place that knows about every installable graphics driver and decides what a file IS by its content,
 * not by the screen it was imported from:
 *
 *  - zip with meta.json (+ libraryName)  -> "Adrenotools" package: a bionic driver (Mali PanVK, Adreno, Xclipse, ...)
 *                                           loaded by the Vortek renderer inside the app process.
 *  - zstd tar (.tzst)                    -> Mesa/Turnip package for the proot (glibc) rootfs.
 *
 * Which GPU a package is meant for is read from its meta.json (explicit "gpuFamily"/"gpuFamilies", or guessed from
 * backend/name/library). It is only a HINT: any driver can be selected on any device, the app just warns and
 * the "Auto" choice picks the best match for the detected GPU.
 */
public abstract class DriverPackages {
    private static final String TAG = "DriverPackages";
    public static final String SYSTEM = "System";
    public static final String AUTO = "Auto";

    public enum GpuFamily {
        ADRENO("Adreno"), MALI("Mali"), XCLIPSE("Xclipse"), POWERVR("PowerVR"), UNKNOWN("Any");

        public final String title;

        GpuFamily(String title) {
            this.title = title;
        }

        public static GpuFamily fromRenderer(String renderer) {
            String value = renderer != null ? renderer.toLowerCase(Locale.ENGLISH) : "";
            if (value.contains("adreno")) return ADRENO;
            if (value.contains("mali") || value.contains("immortalis")) return MALI;
            if (value.contains("xclipse")) return XCLIPSE;
            if (value.contains("powervr")) return POWERVR;
            return UNKNOWN;
        }

        public static GpuFamily detect(Context context) {
            try {
                return fromRenderer(GPUHelper.glGetRenderer(context));
            }
            catch (Throwable e) {
                return UNKNOWN;
            }
        }

        static GpuFamily fromHint(String hint) {
            String value = hint.toLowerCase(Locale.ENGLISH);
            if (value.contains("panvk") || value.contains("panfrost") || value.contains("mali") || value.contains("immortalis") || value.contains("kbase")) return MALI;
            if (value.contains("turnip") || value.contains("freedreno") || value.contains("adreno") || value.contains("kgsl")) return ADRENO;
            if (value.contains("xclipse") || value.contains("radv") || value.contains("rdna")) return XCLIPSE;
            if (value.contains("powervr") || value.contains("imagination")) return POWERVR;
            return UNKNOWN;
        }

        static GpuFamily fromName(String name) {
            for (GpuFamily family : values()) if (family.name().equalsIgnoreCase(name) || family.title.equalsIgnoreCase(name)) return family;
            return null;
        }
    }

    public enum Kind {ADRENOTOOLS, TURNIP}

    public static class Info {
        public String id;
        public String name;
        public String version = "";
        public String description = "";
        public String libraryName;
        public String backend = "";
        public int minApi = 0;
        public GpuFamily family = GpuFamily.UNKNOWN;
        public File dir;
        public File library;

        public boolean isSupportedByOs() {
            return Build.VERSION.SDK_INT >= minApi;
        }

        /** Never blocks anything, only used for hints. */
        public boolean matchesDevice(GpuFamily device) {
            return family == GpuFamily.UNKNOWN || device == GpuFamily.UNKNOWN || family == device;
        }

        public String label() {
            return family != GpuFamily.UNKNOWN ? name+" ["+family.title+"]" : name;
        }
    }

    public static class InstallResult {
        public final Kind kind;
        public final String id;
        public final Info info; // only for ADRENOTOOLS

        InstallResult(Kind kind, String id, Info info) {
            this.kind = kind;
            this.id = id;
            this.info = info;
        }
    }

    public static File getRoot(Context context) {
        return GeneralComponents.getComponentDir(GeneralComponents.Type.ADRENOTOOLS_DRIVER, context);
    }

    // ---------------------------------------------------------------- reading installed packages

    public static ArrayList<Info> list(Context context) {
        ArrayList<Info> result = new ArrayList<>();
        File[] dirs = getRoot(context).listFiles();
        if (dirs == null) return result;
        Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) continue;
            Info info = parse(dir);
            if (info != null) result.add(info);
        }
        return result;
    }

    public static Info get(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        File dir = new File(getRoot(context), id);
        return dir.isDirectory() ? parse(dir) : null;
    }

    /** Best installed driver for the GPU of this device (exact family first, then generic ones, newest wins). */
    public static Info autoSelect(Context context) {
        GpuFamily device = GpuFamily.detect(context);
        Info best = null;
        for (Info info : list(context)) {
            if (!info.isSupportedByOs()) continue;
            if (info.family != device && info.family != GpuFamily.UNKNOWN) continue;
            if (best == null) best = info;
            else {
                boolean exact = info.family == device, bestExact = best.family == device;
                if ((exact && !bestExact) || (exact == bestExact && info.dir.lastModified() > best.dir.lastModified())) best = info;
            }
        }
        return best;
    }

    /**
     * Path of the driver library for the Vortek renderer.
     * null = use the system Vulkan driver (also when a package is missing or needs a newer Android).
     */
    public static String resolveLibraryPath(Context context, String identifier) {
        if (identifier == null || identifier.isEmpty() || identifier.equalsIgnoreCase(SYSTEM)) return null;

        Info info = identifier.equalsIgnoreCase(AUTO) ? autoSelect(context) : get(context, identifier);
        if (info == null) {
            Log.w(TAG, "Driver '"+identifier+"' not found, using the system driver");
            return null;
        }
        if (!info.isSupportedByOs()) {
            Log.w(TAG, "Driver '"+info.name+"' needs Android API "+info.minApi+" (this device: "+Build.VERSION.SDK_INT+"), using the system driver");
            return null;
        }
        return info.library.isFile() ? info.library.getPath() : null;
    }

    public static boolean remove(Context context, String id) {
        File dir = new File(getRoot(context), id);
        return dir.isDirectory() && FileUtils.delete(dir);
    }

    private static File findManifest(File dir) {
        File meta = new File(dir, "meta.json");
        if (meta.isFile()) return meta;
        File[] jsons = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (jsons == null) return null;
        Arrays.sort(jsons);
        for (File json : jsons) {
            try {
                if (!new JSONObject(FileUtils.readString(json)).optString("libraryName", "").isEmpty()) return json;
            }
            catch (Exception e) {}
        }
        return null;
    }

    private static Info parse(File dir) {
        try {
            File manifest = findManifest(dir);
            if (manifest == null) return null;
            JSONObject json = new JSONObject(FileUtils.readString(manifest));

            Info info = new Info();
            info.dir = dir;
            info.id = dir.getName();
            info.libraryName = json.optString("libraryName", "");
            if (info.libraryName.isEmpty()) return null;
            info.name = json.optString("name", info.id);
            info.version = json.optString("driverVersion", json.optString("packageVersion", ""));
            info.description = json.optString("description", "");
            info.backend = json.optString("backend", "");
            info.minApi = json.optInt("minApi", 0);
            info.library = new File(dir, info.libraryName);

            // 1) explicit declaration in meta.json: "gpuFamily": "mali"  or  "gpuFamilies": ["mali", "adreno"]
            GpuFamily family = null;
            String explicit = json.optString("gpuFamily", "");
            if (!explicit.isEmpty()) family = GpuFamily.fromName(explicit);
            JSONArray families = json.optJSONArray("gpuFamilies");
            if (family == null && families != null && families.length() == 1) family = GpuFamily.fromName(families.optString(0));

            // 2) otherwise guess from what the driver says about itself
            if (family == null) {
                family = GpuFamily.fromHint(info.backend+" "+info.name+" "+info.libraryName+" "+json.optString("profile", "")+" "+json.optString("kernelInterface", "")+" "+info.description);
            }
            info.family = family;
            return info;
        }
        catch (Exception e) {
            Log.w(TAG, "Bad driver package "+dir+": "+e);
            return null;
        }
    }

    // ---------------------------------------------------------------- installing

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

    /** Detects the format from the first bytes, so the same "Install" button works for every driver type. */
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

            if (count == 4 && magic[0] == 0x50 && magic[1] == 0x4B) return installZip(context, in);
            if (count == 4 && (magic[0] & 0xff) == 0x28 && (magic[1] & 0xff) == 0xB5 && (magic[2] & 0xff) == 0x2F && (magic[3] & 0xff) == 0xFD) {
                return installTurnipArchive(context, in, displayName);
            }
            throw new IOException("Unknown driver format (expected .zip with meta.json or .tzst)");
        }
    }

    private static InstallResult installZip(Context context, InputStream in) throws IOException {
        File root = getRoot(context);
        File tmp = new File(root, ".tmp-install");
        FileUtils.delete(tmp);
        tmp.mkdirs();

        try {
            String tmpPath = tmp.getCanonicalPath()+File.separator;
            try (ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                byte[] buffer = new byte[1 << 16];
                while ((entry = zis.getNextEntry()) != null) {
                    File target = new File(tmp, entry.getName().replace('\\', '/'));
                    if (!target.getCanonicalPath().startsWith(tmpPath)) throw new IOException("Bad path in archive: "+entry.getName());
                    if (entry.isDirectory()) {
                        target.mkdirs();
                        continue;
                    }
                    target.getParentFile().mkdirs();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int length;
                        while ((length = zis.read(buffer)) > 0) out.write(buffer, 0, length);
                    }
                }
            }

            // the manifest may be in the root of the archive or inside a single top-level folder
            File base = null;
            if (findManifest(tmp) != null) base = tmp;
            else {
                File[] children = tmp.listFiles();
                if (children != null) for (File child : children) if (child.isDirectory() && findManifest(child) != null) { base = child; break; }
            }
            if (base == null) throw new IOException("meta.json with libraryName was not found in the archive");

            Info info = parse(base);
            if (info == null) throw new IOException("meta.json is invalid");
            if (!info.library.isFile()) throw new IOException("Library "+info.libraryName+" is missing in the archive");

            String id = sanitizeId(info.name);
            if (id.isEmpty()) throw new IOException("The driver has no name");
            File destination = new File(root, id);
            if (destination.exists()) FileUtils.delete(destination); // re-install = update
            if (!base.renameTo(destination)) throw new IOException("Cannot move the driver to "+destination);
            destination.setLastModified(System.currentTimeMillis());
            return new InstallResult(Kind.ADRENOTOOLS, id, parse(destination));
        }
        finally {
            FileUtils.delete(tmp);
        }
    }

    private static InstallResult installTurnipArchive(Context context, InputStream in, String displayName) throws IOException {
        String base = displayName != null ? displayName : "";
        base = base.replaceAll("(?i)(\\.tar)?\\.(tzst|zst)$", "");

        String id;
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)+)").matcher(base);
        if (matcher.find()) id = matcher.group(1);
        else {
            id = sanitizeId(base.replaceAll("(?i)^turnip-?", ""));
            if (id.isEmpty()) id = "custom-"+(System.currentTimeMillis() / 1000);
        }

        File dir = GeneralComponents.getComponentDir(GeneralComponents.Type.TURNIP, context);
        File destination = new File(dir, "turnip-"+id+".tzst");
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
        return new InstallResult(Kind.TURNIP, id, null);
    }
}
