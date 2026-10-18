package com.factorio.container;

import com.factorio.core.FileUtils;
import com.factorio.core.EnvVars;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

public class Container {
    public static final String BASE_ENV_VARS = "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true TU_DEBUG=noconform";
    // Native ARM64 game (Factorio): the game folder is copied from NATIVE_SRC into the rootfs on first start.
    public static final String DEFAULT_NATIVE_EXEC = "/home/xuser/factorio/bin/arm64/factorio";
    public static final String NATIVE_ENV_VARS = "NATIVE_EXEC="+DEFAULT_NATIVE_EXEC+" NATIVE_SRC=/storage/emulated/0/Download/factorio";
    public static final String DEFAULT_ENV_VARS = BASE_ENV_VARS+" "+NATIVE_ENV_VARS;
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_AUDIO_DRIVER = AudioDrivers.ALSA;
    public final int id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = GraphicsDrivers.DEFAULT_VULKAN_DRIVER+","+ GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
    private String graphicsDriverConfig = "";
    private String audioDriverConfig = "";
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private File rootDir;
    private JSONObject extraData;

    public Container(int id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getGraphicsDriverConfig() {
        return graphicsDriverConfig;
    }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) {
        this.graphicsDriverConfig = graphicsDriverConfig != null ? graphicsDriverConfig : "";
    }

    public String getAudioDriverConfig() {
        return audioDriverConfig;
    }

    public void setAudioDriverConfig(String audioDriverConfig) {
        this.audioDriverConfig = audioDriverConfig != null ? audioDriverConfig : "";
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public void saveData() {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("graphicsDriver", graphicsDriver);
            if (!graphicsDriverConfig.isEmpty()) data.put("graphicsDriverConfig", graphicsDriverConfig);
            if (!audioDriverConfig.isEmpty()) data.put("audioDriverConfig", audioDriverConfig);
            data.put("audioDriver", audioDriver);
            data.put("extraData", extraData);
            FileUtils.writeString(getConfigFile(), data.toString());
        }
        catch (JSONException e) {}
    }

    public void loadData(JSONObject data) throws JSONException {
        graphicsDriverConfig = "";
        audioDriverConfig = "";

        // unknown keys (left by older versions: dxwrapper, wincomponents, drives, box64Preset...) are ignored
        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                case "audioDriverConfig" :
                    setAudioDriverConfig(data.getString(key));
                    break;
                case "extraData" :
                    setExtraData(data.getJSONObject(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
            }
        }
        if (!"1".equals(getExtra("nativeMesaSelectionV34"))) {
            String src = new EnvVars(envVars).get("NATIVE_MESA_SRC");
            if (src != null && !src.isEmpty()) {
                String[] drivers = GraphicsDrivers.parseIdentifiers(graphicsDriver);
                graphicsDriver = drivers[0]+","+GraphicsDrivers.OPENGL;
            }
            putExtra("nativeMesaSelectionV34", "1");
        }
    }
}
