package com.factorio.container;

import android.content.Context;

import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;

public abstract class GraphicsDrivers {
    public static final String TURNIP = "turnip";
    public static final String ZINK = "zink";
    public static final String OPENGL = "opengl";
    public static final String DEFAULT_VULKAN_DRIVER = TURNIP;
    public static final String DEFAULT_OPENGL_DRIVER = OPENGL;

    public static String getName(String identifier) {
        switch (identifier) {
            case TURNIP: return "Turnip";
            case ZINK: return "Zink";
            case OPENGL: return "OpenGL";
            default: return "None";
        }
    }

    public static boolean isVulkanDriver(String identifier) {
        return identifier != null && identifier.equals(TURNIP);
    }

    public static boolean isOpenGLDriver(String identifier) {
        return identifier != null && (identifier.equals(ZINK) || identifier.equals(OPENGL));
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

        // Normalize removed driver identifiers (old settings with "vortek" become Turnip).
        if (!isVulkanDriver(identifiers[0])) identifiers[0] = DEFAULT_VULKAN_DRIVER;
        if (identifiers.length > 1 && !isOpenGLDriver(identifiers[1])) identifiers[1] = DEFAULT_OPENGL_DRIVER;
        return identifiers;
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

    public static String getDefaultDriver(Context context) {
        return TURNIP+","+(GPUHelper.isAdreno(context) ? DEFAULT_OPENGL_DRIVER : ZINK);
    }
}
