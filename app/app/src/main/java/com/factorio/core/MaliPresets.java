package com.factorio.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made setups for Mali / MediaTek GPUs (Vortek + Zink 22.2.5). The first entry is always "not selected" = the user's own settings.
 * A preset is a Vortek config (bcnMode, memory type, memory sync ...) plus optional game graphics keys (NATIVE_GFX, applied at launch only
 * while the container has no NATIVE_GFX of its own). The list is built from what the diagnostics showed on a Mali-G610 (MT6886):
 * CPU decode of BCn hangs the game, Native Hardware Buffer crashes, Opaque FD gives no GL context, so none of the presets uses them.
 * To add a preset, add one line in all().
 */
public class MaliPresets {
    public static final String NONE_ID = "";

    public static class Preset {
        public final String id;
        public final String title;
        public final String description;
        public final String vortekConfig; // for example bcnMode=1,memorySync=1 (null for the "not selected" entry)
        public final String nativeGfx;    // value for NATIVE_GFX or null

        Preset(String id, String title, String description, String vortekConfig, String nativeGfx) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.vortekConfig = vortekConfig;
            this.nativeGfx = nativeGfx;
        }
    }

    public static List<Preset> all() {
        ArrayList<Preset> list = new ArrayList<>();
        list.add(new Preset(NONE_ID, "Не выбрано", "Свои настройки", null, null));
        list.add(new Preset("mali-balanced", "Mali: сбалансированный",
            "BCn на GPU (новая сборка слоя) + сброс кэша CPU при flush/invalidate. Первый вариант для проверки.",
            "bcnMode=1,memorySync=1", null));
        list.add(new Preset("mali-safe", "Mali: надёжный (без сжатия текстур)",
            "Игра без сжатых текстур (меньше зависит от BCn) + сброс кэша перед каждой отправкой команд. Медленнее, но проще всего даёт чистую картинку.",
            "bcnMode=1,memorySync=2,resourceMemoryType=2", "texture-compression-level=none"));
        list.add(new Preset("mali-astc", "Mali: BCn через ASTC (старый слой)",
            "Старая сборка слоя BCn с ASTC; в диагностике картинка была чище всего. Плюс сброс кэша.",
            "bcnMode=2,memorySync=1", null));
        list.add(new Preset("mali-lowmem", "Mali: мало памяти",
            "Как «сбалансированный», но лимит памяти устройства 4096 МБ и Vulkan 1.1, кэш образов 128 МБ.",
            "bcnMode=1,memorySync=1,maxDeviceMemory=4096,vkMaxVersion=1.1,imageCacheSize=128", null));
        return list;
    }

    public static Preset find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Preset preset : all()) if (preset.id.equals(id)) return preset;
        return null;
    }
}
