package com.factory;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.factory.core.AppUtils;
import com.factory.core.FileUtils;
import com.factory.core.GameSettings;
import com.factory.runtime.Drivers;
import com.factory.runtime.Paths;
import com.factory.runtime.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings → Benchmark: Factorio starts with a small mod (its own --mod-directory, the player's mods stay as they
 * are) that turns the main menu into the 23 vanilla menu scenes, 12 s each, each logging its name when it starts,
 * and draws the menu itself invisible.
 * Factorio picks menu scenes at random, so the mod has a single one whose save is a symlink (scene.zip): the frame
 * timer points it at the next scene's save whenever a scene starts, and the scene's script recognises which save it
 * got (map seed + tick; the five biter base scenes share a save, so they get copies with different daytimes) and
 * runs that vanilla scene's script.
 * runtime/benchmark (LD_PRELOADed) times every frame, measures 10 s of each scene, writes the results and quits
 * the game; the report then goes to Download/Factory-benchmark.
 */
public final class Benchmark {
    private static final class Scene {
        final String name, save;
        final long seed, tick;
        /** The daytime its save copy is patched to (biter base scenes), else NaN: the save as it is. */
        final double daytime;

        Scene(String name, String save, long seed, long tick, double daytime) {
            this.name = name; this.save = save; this.seed = seed; this.tick = tick; this.daytime = daytime;
        }

        Scene(String name, String save, long seed, long tick) {
            this(name, save, seed, tick, Double.NaN);
        }
    }

    /** The biter base save's daytime (Factorio 2.1.19), replaced in its copies. */
    private static final double BITER_BASE_DAYTIME = 0.70672000000000672;

    /**
     * The vanilla menu scenes (data/base/data.lua), in play and report order, with their saves (data/base/menu-simulations)
     * and the map seed and tick each save starts at (measured with Factorio 2.1.19). Space Age's and elevated rails'
     * are left out.
     */
    private static final List<Scene> SCENES = Arrays.asList(
        new Scene("nauvis_solar_power_construction", "menu-simulation-solar-power-construction.zip", 3752266207L, 169),
        new Scene("nauvis_lab", "menu-simulation-lab.zip", 2675748734L, 22093),
        new Scene("nauvis_burner_city", "menu-simulation-burner-city.zip", 2940561389L, 930),
        new Scene("nauvis_mining_defense", "menu-simulation-mining-defense.zip", 4284644858L, 1915),
        new Scene("nauvis_forest_fire", "menu-simulation-forest-fire.zip", 1705827458L, 3691),
        new Scene("nauvis_oil_pumpjacks", "menu-simulation-oil-pumpjacks.zip", 2927396293L, 6748),
        new Scene("nauvis_oil_refinery", "menu-simulation-oil-refinery.zip", 778542701L, 3134),
        new Scene("nauvis_early_smelting", "menu-simulation-early-smelting.zip", 1988098579L, 5037),
        new Scene("nauvis_train_station", "menu-simulation-train-station.zip", 391363348L, 3904),
        new Scene("nauvis_logistic_robots", "menu-simulation-logistic-robots.zip", 1745053806L, 1014),
        new Scene("nauvis_nuclear_power", "menu-simulation-nuclear-power.zip", 2018232804L, 15964),
        new Scene("nauvis_train_junction", "menu-simulation-train-junction.zip", 2224417923L, 886),
        new Scene("nauvis_artillery", "menu-simulation-artillery.zip", 2962657571L, 123326),
        new Scene("nauvis_biter_base_spidertron", "menu-simulation-biter-base.zip", 3839008127L, 168, BITER_BASE_DAYTIME),
        new Scene("nauvis_biter_base_artillery", "menu-simulation-biter-base.zip", 3839008127L, 168, 0.1),
        new Scene("nauvis_biter_base_laser_defense", "menu-simulation-biter-base.zip", 3839008127L, 168, 0.2),
        new Scene("nauvis_biter_base_player_attack", "menu-simulation-biter-base.zip", 3839008127L, 168, 0.3),
        new Scene("nauvis_biter_base_steamrolled", "menu-simulation-biter-base.zip", 3839008127L, 168, 0.4),
        new Scene("nauvis_chase_player", "menu-simulation-chase-player.zip", 868167746L, 12461),
        new Scene("nauvis_big_defense", "menu-simulation-big-defense.zip", 868167746L, 149566),
        new Scene("nauvis_brutal_defeat", "menu-simulation-brutal-defeat.zip", 1682103223L, 62680),
        new Scene("nauvis_spider_ponds", "menu-simulation-spider-ponds.zip", 4224937402L, 20348),
        new Scene("nauvis_uranium_processing", "menu-simulation-uranium-processing.zip", 467461835L, 733064));
    /** Seconds Factorio shows each scene: 1 s warm-up + 10 s measured + a margin before the next one loads. */
    private static final int SCENE_SECONDS = 12;
    private static final String MOD = "factory-benchmark";
    private static final String MOD_VERSION = "1.0.0";
    private static final String ASSET = "benchmark/libfactorybench.so";
    /** The DLC mods that come with the game: enabled or not as the player has them. */
    private static final List<String> BUILT_IN_MODS = Arrays.asList("base", "elevated-rails", "quality", "recycler", "space-age");

    private static final String PREF_STATE = "benchmark_state";
    private static final String PENDING = "pending";
    private static final String RUNNING = "running";

    private Benchmark() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** The next game start is a benchmark. */
    public static void request(Context context) {
        prefs(context).edit().putString(PREF_STATE, PENDING).commit();
    }

    public static boolean isPending(Context context) {
        return PENDING.equals(prefs(context).getString(PREF_STATE, null));
    }

    public static boolean isRunning(Context context) {
        return RUNNING.equals(prefs(context).getString(PREF_STATE, null));
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(PREF_STATE).commit();
    }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), "benchmark");
    }

    private static File modsDir(Context context) {
        return new File(dir(context), "mods");
    }

    private static File outDir(Context context) {
        return new File(dir(context), "out");
    }

    public static List<String> gameArguments(Context context) {
        return Arrays.asList("--mod-directory", modsDir(context).getPath());
    }

    /** Writes the mod and the frame timer; returns the game's extra environment. Marks the benchmark as running. */
    public static Map<String, String> prepare(Context context) throws IOException {
        Paths paths = new Paths(context);
        File mods = modsDir(context);
        File mod = new File(mods, MOD+"_"+MOD_VERSION);
        File out = outDir(context);
        FileUtils.delete(out);
        if (!mod.isDirectory() && !mod.mkdirs()) throw new IOException("Benchmark: cannot create "+mod);
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Benchmark: cannot create "+out);

        try {
            JSONObject info = new JSONObject()
                .put("name", MOD).put("version", MOD_VERSION).put("title", "Factory benchmark")
                .put("author", "Factory").put("factorio_version", "2.1")
                .put("dependencies", new JSONArray().put("base"))
                .put("description", "Plays the vanilla main menu scenes one after another for the Factory benchmark.");
            write(new File(mod, "info.json"), info.toString(2));
            write(new File(mods, "mod-list.json"), modList(new File(paths.getGameData(), "mods/mod-list.json")).toString(2));
        }
        catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        write(new File(mod, "data-final-fixes.lua"), modScript());

        // every scene's save, then the slot Factorio loads, at the first one
        File saves = new File(mod, "saves");
        FileUtils.delete(saves);
        if (!saves.mkdirs()) throw new IOException("Benchmark: cannot create "+saves);
        File vanilla = new File(paths.getGame(), "data/base/menu-simulations");
        List<String> savePaths = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        for (Scene scene : SCENES) {
            File source = new File(vanilla, scene.save);
            if (!source.isFile()) throw new IOException("Benchmark: the game has no "+source);
            File save = new File(saves, scene.name+".zip");
            if (Double.isNaN(scene.daytime) || scene.daytime == BITER_BASE_DAYTIME) FileUtils.symlink(source, save);
            else patchDaytime(source, save, BITER_BASE_DAYTIME, scene.daytime);
            names.add(scene.name);
            savePaths.add(save.getPath());
        }
        File slot = new File(mod, "scene.zip");
        FileUtils.symlink(savePaths.get(0), slot.getPath());

        File library = new File(dir(context), "libfactorybench.so");
        try (InputStream in = context.getAssets().open(ASSET); OutputStream os = new java.io.FileOutputStream(library)) {
            byte[] buffer = new byte[1 << 16];
            int length;
            while ((length = in.read(buffer)) > 0) os.write(buffer, 0, length);
        }

        Map<String, String> env = new HashMap<>();
        env.put("LD_PRELOAD", library.getPath());
        env.put("FACTORY_BENCH_OUT", out.getPath());
        env.put("FACTORY_BENCH_LOG", new File(paths.getGameData(), "factorio-current.log").getPath());
        env.put("FACTORY_BENCH_NAMES", String.join(",", names));
        env.put("FACTORY_BENCH_SAVES", String.join(",", savePaths));
        env.put("FACTORY_BENCH_SLOT", slot.getPath());
        env.put("FACTORY_BENCH_APP_PID", String.valueOf(android.os.Process.myPid()));
        prefs(context).edit().putString(PREF_STATE, RUNNING).commit();
        return env;
    }

    private static void write(File file, String text) throws IOException {
        if (!FileUtils.writeString(file, text)) throw new IOException("Benchmark: cannot write "+file);
    }

    /** The built-in mods as the player has them (enabled when the list doesn't name them), plus the benchmark. */
    private static JSONObject modList(File playerList) throws org.json.JSONException {
        Map<String, Boolean> enabled = new HashMap<>();
        String text = FileUtils.readString(playerList);
        if (text != null) {
            try {
                JSONArray list = new JSONObject(text).getJSONArray("mods");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.getJSONObject(i);
                    enabled.put(entry.getString("name"), entry.optBoolean("enabled", true));
                }
            }
            catch (org.json.JSONException ignored) {}
        }
        JSONArray mods = new JSONArray();
        for (String name : BUILT_IN_MODS) {
            Boolean on = enabled.get(name);
            mods.put(new JSONObject().put("name", name).put("enabled", name.equals("base") || on == null || on));
        }
        mods.put(new JSONObject().put("name", MOD).put("enabled", true));
        return new JSONObject().put("mods", mods);
    }

    /** The save with the double `from` in level.dat (there exactly once) replaced by `to`. */
    private static void patchDaytime(File source, File target, double from, double to) throws IOException {
        byte[] old = littleEndian(from), replacement = littleEndian(to);
        try (java.util.zip.ZipFile in = new java.util.zip.ZipFile(source);
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(target))) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream stream = in.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[1 << 16];
                    int length;
                    while ((length = stream.read(buffer)) > 0) bytes.write(buffer, 0, length);
                    data = bytes.toByteArray();
                }
                if (entry.getName().endsWith("/level.dat")) {
                    int at = indexOf(data, old, 0);
                    if (at < 0 || indexOf(data, old, at + 1) >= 0) throw new IOException("Benchmark: unexpected "+source.getName());
                    System.arraycopy(replacement, 0, data, at, replacement.length);
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
    }

    private static byte[] littleEndian(double value) {
        return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    /**
     * One menu scene, SCENE_SECONDS long, on the slot save: its script finds out which scene's save it got, logs
     * that scene's name (read by the frame timer) and runs the vanilla scene's script.
     */
    private static String modScript() {
        StringBuilder scenes = new StringBuilder();
        for (Scene scene : SCENES)
            scenes.append(String.format(Locale.US, "  {\"%s\", %d, %d, %s},\n", scene.name, scene.seed, scene.tick,
                Double.isNaN(scene.daytime) ? "nil" : String.format(Locale.US, "%.17g", scene.daytime)));
        return "-- Factory benchmark: the vanilla main menu scenes in a fixed order (the app swaps the save under one scene).\n"+
            "local vanilla = require(\"__base__/menu-simulations/menu-simulations\")\n"+
            "-- name, the save's map seed and tick, its daytime (only where scenes share a save)\n"+
            "local scenes =\n{\n"+scenes+"}\n"+
            "local list = {}\n"+
            "for _, scene in ipairs(scenes) do\n"+
            "  list[#list + 1] = string.format(\"{%q, %d, %d, %s}\", scene[1], scene[2], scene[3], scene[4] and string.format(\"%.17g\", scene[4]) or \"nil\")\n"+
            "end\n"+
            "local init =\n"+
            "  \"local surface = game.surfaces.nauvis\\n\" ..\n"+
            "  \"local seed, tick, daytime = surface.map_gen_settings.seed, game.tick, surface.daytime\\n\" ..\n"+
            "  \"local function matches(scene, exact)\\n\" ..\n"+
            "  \"  return scene[2] == seed and (scene[3] == tick or not exact) and (not scene[4] or math.abs(scene[4] - daytime) < 1e-9)\\n\" ..\n"+
            "  \"end\\n\" ..\n"+
            "  \"local scenes = {\" .. table.concat(list, \",\\n\") .. \"}\\n\" ..\n"+
            "  \"factory_bench_scene = 'unknown'\\n\" ..\n"+
            "  \"for _, exact in ipairs({true, false}) do\\n\" ..\n"+
            "  \"  for _, scene in ipairs(scenes) do\\n\" ..\n"+
            "  \"    if factory_bench_scene == 'unknown' and matches(scene, exact) then factory_bench_scene = scene[1] end\\n\" ..\n"+
            "  \"  end\\n\" ..\n"+
            "  \"end\\n\" ..\n"+
            "  \"log('FACTORY_BENCH ' .. factory_bench_scene)\\n\"\n"+
            "local update = \"\"\n"+
            "for _, scene in ipairs(scenes) do\n"+
            "  local simulation = vanilla[scene[1]]\n"+
            "  if simulation and simulation.init then\n"+
            "    init = init .. \"if factory_bench_scene == '\" .. scene[1] .. \"' then\\n\" .. simulation.init .. \"\\nend\\n\"\n"+
            "  end\n"+
            "  if simulation and simulation.update then\n"+
            "    update = update .. \"if factory_bench_scene == '\" .. scene[1] .. \"' then\\n\" .. simulation.update .. \"\\nend\\n\"\n"+
            "  end\n"+
            "end\n"+
            "data.raw[\"utility-constants\"][\"default\"].main_menu_simulations =\n"+
            "{\n"+
            "  factory_benchmark =\n"+
            "  {\n"+
            "    checkboard = false,\n"+
            "    save = \"__"+MOD+"__/scene.zip\",\n"+
            "    length = 60 * "+SCENE_SECONDS+",\n"+
            "    init = init,\n"+
            "    update = update ~= \"\" and update or nil\n"+
            "  }\n"+
            "}\n"+
            "\n"+
            "-- The main menu drawn invisible (it stays there, unseen): every image and text colour of every GUI style,\n"+
            "-- nested ones too (frame titles, headers, footers), plus the ones the styles inherit.\n"+
            "local clear = {r = 0, g = 0, b = 0, a = 0}\n"+
            "local states = {\"default\", \"hovered\", \"clicked\", \"disabled\", \"selected\", \"selected_hovered\", \"selected_clicked\"}\n"+
            "-- (only these image sets: other ones, like speech_bubble's arrow_graphical_set, can't be empty)\n"+
            "local image_sets = {graphical_set = true, background_graphical_set = true}\n"+
            "for _, state in ipairs(states) do image_sets[state .. \"_graphical_set\"] = true end\n"+
            "local function hide(style)\n"+
            "  for key, value in pairs(style) do\n"+
            "    if image_sets[key] then style[key] = {}\n"+
            "    elseif type(key) == \"string\" and key:find(\"font_color$\") then style[key] = clear\n"+
            "    elseif type(value) == \"table\" and value.type and type(value.type) == \"string\" and value.type:find(\"_style$\") then hide(value)\n"+
            "    end\n"+
            "  end\n"+
            "  if style.type == \"button_style\" then\n"+
            "    for _, state in ipairs(states) do\n"+
            "      style[state .. \"_graphical_set\"] = {}\n"+
            "      style[state .. \"_font_color\"] = clear\n"+
            "    end\n"+
            "  elseif style.type == \"label_style\" then\n"+
            "    style.font_color = clear\n"+
            "  elseif style.type == \"frame_style\" or style.type == \"empty_widget_style\" then\n"+
            "    style.graphical_set = {}\n"+
            "  elseif style.type == \"scroll_pane_style\" then\n"+
            "    style.graphical_set = {}\n"+
            "    style.background_graphical_set = {}\n"+
            "  end\n"+
            "end\n"+
            "for _, style in pairs(data.raw[\"gui-style\"][\"default\"]) do\n"+
            "  if type(style) == \"table\" and type(style.type) == \"string\" and style.type:find(\"_style$\") then hide(style) end\n"+
            "end\n";
    }

    /** The game screen's status line: which scene, how many measured; null when there's nothing yet. */
    public static String status(Context context) {
        String text = FileUtils.readString(new File(outDir(context), "progress.txt"));
        String[] parts = text != null ? text.trim().split(" ") : new String[0];
        if (parts.length < 4) return context.getString(R.string.benchmark_status_loading);
        if (parts[2].equals("-")) return context.getString(R.string.benchmark_status_loading);
        String scene = parts[2].replaceFirst("^nauvis_", "").replace('_', ' ');
        return context.getString(parts[3].equals("1") ? R.string.benchmark_status_measuring : R.string.benchmark_status_repeat,
            parts[0], parts[1], scene);
    }

    public static boolean hasResult(Context context) {
        return new File(outDir(context), "result.txt").isFile();
    }

    /** Scenes measured so far ("5/23"), or null. */
    public static String progress(Context context) {
        String text = FileUtils.readString(new File(outDir(context), "progress.txt"));
        return text != null ? text.trim() : null;
    }

    /**
     * After the benchmark run: the report (settings + results) to Download/Factory-benchmark, returned as text;
     * null when the run left no results.
     */
    public static String finish(Context context) {
        clear(context);
        File out = outDir(context);
        String result = FileUtils.readString(new File(out, "result.txt"));
        if (result == null) return null;

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        boolean angle = Drivers.INSTANCE.gladioDirect(context) ? Drivers.INSTANCE.gladioDirectAngle(context) : Drivers.INSTANCE.gladio(context) && com.factory.gladio.GladioServer.usingAngle();
        String driver = driverName(context)+(Drivers.INSTANCE.gladioDirect(context) ? " direct" : "")+(angle ? " ANGLE" : "");
        StringBuilder report = new StringBuilder();
        report.append("Factory benchmark ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append('\n');
        report.append("app: ").append(appVersion(context)).append('\n');
        report.append("phone: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL);
        if (android.os.Build.VERSION.SDK_INT >= 31) report.append(" (").append(android.os.Build.SOC_MANUFACTURER).append(' ').append(android.os.Build.SOC_MODEL).append(')');
        report.append(", Android ").append(android.os.Build.VERSION.RELEASE).append('\n');
        String gpu = com.factory.runtime.Gpu.INSTANCE.getName();
        report.append("gpu: ").append(gpu != null ? gpu : "?").append('\n');
        report.append("driver: ").append(driver);
        if (Drivers.INSTANCE.gladio(context)) report.append(" (display: ").append(Settings.INSTANCE.gladioDisplay(context))
            .append(Drivers.INSTANCE.gladioDirect(context) ? ", phone's driver directly" : "").append(angle ? ", on ANGLE" : "").append(')');
        report.append('\n');
        report.append("resolution: ").append(GameSettings.getScreenSize(context)).append('\n');
        report.append("graphics preset: ").append(Settings.INSTANCE.graphicsPreset(context)).append('\n');
        com.factory.runtime.GraphicsPresets.Preset preset = com.factory.runtime.GraphicsPresets.INSTANCE.find(Settings.INSTANCE.graphicsPreset(context));
        report.append("sprite quality: ").append(preset != null ? preset.getSprites() : Settings.INSTANCE.spriteQuality(context))
              .append(preset != null ? "" : ", light graphics: "+Settings.INSTANCE.lightGraphics(context))
              .append(", fps limit: ").append(Settings.INSTANCE.frameRate(context)).append('\n');
        String envVars = GameSettings.getEnvVars(context);
        if (envVars != null && !envVars.trim().isEmpty()) report.append("variables: ").append(envVars.trim()).append('\n');
        String options = Settings.INSTANCE.driverOptions(context);
        if (!options.trim().isEmpty()) report.append("driver options: ").append(options.trim()).append('\n');
        report.append("each scene: 1 s warm-up, then 10 s measured\n");
        java.util.regex.Matcher score = java.util.regex.Pattern.compile("(?m)^SCORE (.*)$").matcher(result);
        String scoreLine = score.find() ? score.group(1).trim() : null;
        if (scoreLine != null) {
            report.insert(0, "SCORE: "+scoreLine+"\n"+
                "(100 x average over the scenes of 0.6 x avg fps + 0.4 x 1% low; 6000 = a steady 60 fps)\n"+
                (Settings.INSTANCE.frameRate(context) != 60 ? "!! fps limit is not 60: this score can't be compared with others\n" : "")+"\n");
            result = score.replaceFirst("").replaceAll("\n{3,}", "\n\n");
        }
        report.append('\n').append(result);

        File folder = new File(AppUtils.DIRECTORY_DOWNLOADS, "Factory-benchmark");
        folder.mkdirs();
        String base = "benchmark-"+stamp+"-"+driver.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        File reportFile = new File(folder, base+".txt");
        FileUtils.writeString(reportFile, report.toString());
        File frames = new File(out, "frames.csv");
        if (frames.isFile()) FileUtils.copy(frames, new File(folder, base+"-frames.csv"));
        File load = new File(out, "load.csv");
        if (load.isFile()) FileUtils.copy(load, new File(folder, base+"-load.csv"));
        return report+"\n→ "+reportFile.getPath();
    }

    private static String driverName(Context context) {
        String id = Drivers.INSTANCE.chosen(context);
        for (Drivers.Driver driver : Drivers.INSTANCE.all(context)) if (driver.getId().equals(id)) return driver.getName();
        return id;
    }

    private static String appVersion(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName+" ("+info.getLongVersionCode()+")";
        }
        catch (Exception e) {
            return "?";
        }
    }
}
