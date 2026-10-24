package com.factorio.container;

import android.content.Context;

import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;

/**
 * Two completely independent graphics blocks (exactly one is active):
 *   block 1 (current):  Turnip + OpenGL / Zink
 *   block 2 (original Winlator stack):  Vortek + Gladio / Zink 22.2.5
 * The saved format "vulkan,opengl" and "k=v,k=v|k=v" is unchanged. The active block is the one whose Vulkan identifier is
 * saved; the choices and the settings of the inactive block live in the container extras.
 */
public abstract class GraphicsDrivers {
    public static final String TURNIP = "turnip";
    public static final String ZINK = "zink";
    public static final String OPENGL = "opengl";
    // block 2
    public static final String VORTEK = "vortek";
    public static final String GLADIO = "gladio";
    public static final String ZINK_LEGACY = "zinkold";

    public static final String DEFAULT_VULKAN_DRIVER = TURNIP;
    public static final String DEFAULT_OPENGL_DRIVER = OPENGL;

    public static final String EXTRA_BLOCK_TURNIP = "graphicsBlockTurnip";   // "turnip,zink"
    public static final String EXTRA_BLOCK_VORTEK = "graphicsBlockVortek";   // "vortek,gladio"
    public static final String EXTRA_CONFIG_VORTEK = "graphicsConfigVortek"; // "vortekCfg|glCfg"

    public static String getName(String identifier) {
        switch (identifier) {
            case TURNIP: return "Turnip";
            case ZINK: return "Zink";
            case OPENGL: return "OpenGL";
            case VORTEK: return "Vortek";
            case GLADIO: return "Gladio";
            case ZINK_LEGACY: return "Zink 22.2.5";
            default: return "None";
        }
    }

    /** Inverse of {@link #getName}. */
    public static String identifierOf(String name) {
        for (String id : new String[]{TURNIP, ZINK, OPENGL, VORTEK, GLADIO, ZINK_LEGACY}) {
            if (getName(id).equals(name)) return id;
        }
        return null;
    }

    public static boolean isVulkanDriver(String identifier) {
        return identifier != null && (identifier.equals(TURNIP) || identifier.equals(VORTEK));
    }

    public static boolean isOpenGLDriver(String identifier) {
        return identifier != null && (identifier.equals(ZINK) || identifier.equals(OPENGL) || isLegacyOpenGL(identifier));
    }

    private static boolean isLegacyOpenGL(String identifier) {
        return GLADIO.equals(identifier) || ZINK_LEGACY.equals(identifier);
    }

    /** true when the Vortek + Gladio / old Zink block is the active one. */
    public static boolean isLegacyMode(String[] identifiers) {
        return identifiers != null && identifiers.length > 0 && VORTEK.equals(identifiers[0]);
    }

    public static String[] getItems(String apiName) {
        if (apiName.equalsIgnoreCase("VULKAN")) {
            return new String[]{getName(TURNIP)};
        }
        else if (apiName.equalsIgnoreCase("OPENGL")) {
            return new String[]{getName(OPENGL), getName(ZINK)};
        }
        else return new String[0];
    }

    public static String[] getLegacyItems(String apiName) {
        if (apiName.equalsIgnoreCase("VULKAN")) {
            return new String[]{getName(VORTEK)};
        }
        else if (apiName.equalsIgnoreCase("OPENGL")) {
            return new String[]{getName(GLADIO), getName(ZINK_LEGACY)};
        }
        else return new String[0];
    }

    /** Forces both identifiers to belong to one block. */
    public static String[] normalizeBlock(String[] identifiers, boolean legacy) {
        String gl = identifiers != null && identifiers.length > 1 ? identifiers[1] : null;
        if (legacy) {
            return new String[]{VORTEK, isLegacyOpenGL(gl) ? gl : GLADIO};
        }
        boolean ok = ZINK.equals(gl) || OPENGL.equals(gl);
        return new String[]{TURNIP, ok ? gl : DEFAULT_OPENGL_DRIVER};
    }

    public static String[] parseIdentifiers(String graphicsDriver) {
        String[] identifiers;
        if (graphicsDriver == null || graphicsDriver.isEmpty()) identifiers = new String[]{DEFAULT_VULKAN_DRIVER, DEFAULT_OPENGL_DRIVER};
        else if (graphicsDriver.contains(",")) {
            identifiers = graphicsDriver.split(",");
        }
        else {
            if (isVulkanDriver(graphicsDriver)) {
                identifiers = new String[]{graphicsDriver, DEFAULT_OPENGL_DRIVER};
            }
            else if (isOpenGLDriver(graphicsDriver)) {
                identifiers = new String[]{DEFAULT_VULKAN_DRIVER, graphicsDriver};
            }
            else identifiers = new String[]{DEFAULT_VULKAN_DRIVER, DEFAULT_OPENGL_DRIVER};
        }

        String vulkan = identifiers[0];
        if (!isVulkanDriver(vulkan)) vulkan = DEFAULT_VULKAN_DRIVER; // unknown / removed identifiers become Turnip
        String gl = identifiers.length > 1 ? identifiers[1] : null;
        return normalizeBlock(new String[]{vulkan, gl}, VORTEK.equals(vulkan));
    }

    public static KeyValueSet[] parseConfigs(String graphicsDriver, String graphicsDriverConfig) {
        if (graphicsDriverConfig == null || graphicsDriverConfig.isEmpty()) return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet()};
        int separator = graphicsDriverConfig.indexOf("|");
        if (separator != -1) {
            String first = graphicsDriverConfig.substring(0, separator);
            String second = graphicsDriverConfig.substring(separator + 1);
            return new KeyValueSet[]{new KeyValueSet(first), new KeyValueSet(second)};
        }
        else {
            if (isVulkanDriver(graphicsDriver)) {
                return new KeyValueSet[]{new KeyValueSet(graphicsDriverConfig), new KeyValueSet()};
            }
            else if (isOpenGLDriver(graphicsDriver)) {
                return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet(graphicsDriverConfig)};
            }
            else return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet()};
        }
    }

    /** Settings of the ACTIVE block: block 1 reads graphicsDriverConfig, block 2 its own extra. */
    public static KeyValueSet[] getActiveConfigs(Container container) {
        String[] identifiers = parseIdentifiers(container.getGraphicsDriver());
        if (isLegacyMode(identifiers)) return parseConfigs(VORTEK, container.getExtra(EXTRA_CONFIG_VORTEK));
        return parseConfigs(container.getGraphicsDriver(), container.getGraphicsDriverConfig());
    }

    public static String getDefaultDriver(Context context) {
        return TURNIP+","+(GPUHelper.isAdreno(context) ? DEFAULT_OPENGL_DRIVER : ZINK);
    }
}
