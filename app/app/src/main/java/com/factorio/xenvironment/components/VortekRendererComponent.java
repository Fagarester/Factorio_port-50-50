package com.factorio.xenvironment.components;

import com.factorio.core.TarCompressorUtils;
import java.io.File;
import android.util.Log;
import android.system.Os;
import android.system.ErrnoException;
import android.content.Context;

import androidx.annotation.Keep;

import com.factorio.contentdialog.VortekConfigDialog;
import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;
import com.factorio.renderer.GPUImage;
import com.factorio.xconnector.ConnectedClient;
import com.factorio.xconnector.ConnectionHandler;
import com.factorio.xconnector.RequestHandler;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xconnector.XConnectorEpoll;
import com.factorio.xconnector.XInputStream;
import com.factorio.xenvironment.EnvironmentComponent;
import com.factorio.xserver.Drawable;
import com.factorio.xserver.Window;
import com.factorio.xserver.XServer;

import java.io.IOException;

public class VortekRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private static final byte REQUEST_CODE_CREATE_CONTEXT = 1;
    private static final byte REQUEST_CODE_SEND_EXTRA_DATA = 2;
    public static final short IMAGE_CACHE_SIZE = 256;
    public static final int VK_MAX_VERSION = GPUHelper.vkMakeVersion(1, 3, 128);
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    private final Options options;

    static {
        System.loadLibrary("vortekrenderer");
    }

    public static class Options {
        public int bcnMode = 0; // not read by native code
        public int vkMaxVersion = VK_MAX_VERSION;
        public short maxDeviceMemory = 0;
        public short imageCacheSize = IMAGE_CACHE_SIZE;
        public byte resourceMemoryType = 0;
        public String[] exposedDeviceExtensions = null;
        public String libvulkanPath = null;

        public static Options fromKeyValueSet(Context context, KeyValueSet config) {
            if (config == null || config.isEmpty()) return new Options();

            Options options = new Options();
            String exposedDeviceExtensions = config.get("exposedDeviceExtensions", "all");
            if (!exposedDeviceExtensions.isEmpty() && !exposedDeviceExtensions.equals("all")) {
                options.exposedDeviceExtensions = exposedDeviceExtensions.split("\\|");
            }

            String vkMaxVersion = config.get("vkMaxVersion", VortekConfigDialog.DEFAULT_VK_MAX_VERSION);
            if (!vkMaxVersion.equals(VortekConfigDialog.DEFAULT_VK_MAX_VERSION)) {
                String[] parts = vkMaxVersion.split("\\.");
                options.vkMaxVersion = GPUHelper.vkMakeVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 128);
            }

            options.maxDeviceMemory = (short)config.getInt("maxDeviceMemory");
            options.imageCacheSize = (short)config.getInt("imageCacheSize", VortekRendererComponent.IMAGE_CACHE_SIZE);
            options.resourceMemoryType = (byte)config.getInt("resourceMemoryType");
            options.bcnMode = config.getInt("bcnMode");

            // custom (adrenotools) Vulkan drivers are not used in this fork: Vortek always runs on the system driver
            options.libvulkanPath = null;
            return options;
        }
    }

    public VortekRendererComponent(XServer xServer, UnixSocketConfig socketConfig, Options options) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
        this.options = options;

        String nativeLibraryDir = xServer.activity.getApplicationInfo().nativeLibraryDir;
        initVulkanWrapper(nativeLibraryDir, options.libvulkanPath);

        // optional GPU BCn emulation (Mali etc.); on any problem Vortek simply keeps decoding BC textures on the CPU
        String bcnLayerPath = prepareBcnLayer(xServer.activity, options.bcnMode);
        if (bcnLayerPath != null && !initBcnLayer(bcnLayerPath)) Log.w("VortekRenderer", "BCn layer not loaded, using CPU decoding");
    }

    /** 0 = CPU decode, 1 = GPU layer (new build), 2 = legacy build -> ASTC, 3 = legacy build -> ETC2. Returns the layer path or null. */
    private static String prepareBcnLayer(Context context, int mode) {
        if (mode <= 0 || mode > 3) return null;
        if (GPUHelper.isAdreno(context)) return null; // Adreno/Turnip has native BC support, the layer is not needed

        boolean legacy = mode != 1;
        String name = legacy ? "leegao_bcn_legacy" : "leegao_bcn";
        File dir = new File(context.getFilesDir(), "bcn_layer/"+(legacy ? "legacy" : "current"));
        File layerFile = new File(dir, "usr/lib/libbcn_layer.so");
        if (!layerFile.isFile()) {
            dir.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/"+name+".tzst", dir);
        }
        if (!layerFile.isFile()) return null;

        try {
            Os.setenv("BCN_DISABLE_DISK_CACHE", "0", true);
            if (legacy) {
                Os.setenv("BCN_COMPUTE_AUTO", "1", true);
                Os.setenv(mode == 3 ? "BCN_TRANSCODE_TO_ETC2" : "BCN_TRANSCODE_TO_ASTC", "1", true);
            }
        }
        catch (ErrnoException e) {
            Log.w("VortekRenderer", "unable to set BCn layer environment", e);
        }
        return layerFile.getPath();
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.setInitialInputBufferCapacity(8);
        connector.setInitialOutputBufferCapacity(0);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }

    @Keep
    private int getWindowWidth(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getWidth() : 0;
    }

    @Keep
    private int getWindowHeight(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getHeight() : 0;
    }

    @Keep
    private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window != null) {
            GPUImage texture = GPUImage.createOrObtain(xServer, window.getContent(), false, useHALPixelFormatBGRA8888);
            return texture.getHardwareBufferPtr();
        }
        return 0;
    }

    @Keep
    private void updateWindowContent(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window != null) {
            Drawable drawable = window.getContent();
            synchronized (drawable.renderLock) {
                drawable.forceUpdate();
            }
        }
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        if (client.getTag() != null) {
            long contextPtr = (long)client.getTag();
            destroyVkContext(contextPtr);
        }
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {}

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        XInputStream inputStream = client.getInputStream();
        if (inputStream.available() < 8) return false;
        int requestCode = inputStream.readInt();
        int requestLength = inputStream.readInt();

        if (requestCode == REQUEST_CODE_CREATE_CONTEXT) {
            long contextPtr = createVkContext(client.fd, options);
            if (contextPtr > 0) {
                client.setTag(contextPtr);
            }
            else connector.killConnection(client);
        }
        else if (requestCode > Short.MAX_VALUE && (requestCode >> 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
            int requestId = requestCode & 0xffff;
            long contextPtr = (long)client.getTag();
            boolean success = handleExtraDataRequest(contextPtr, requestId, requestLength);
            if (!success) throw new IOException("Failed to handle extra data request.");
        }

        return true;
    }

    private native long createVkContext(int clientFd, Options options);

    private native void destroyVkContext(long contextPtr);

    private native void initVulkanWrapper(String nativeLibraryDir, String libvulkanPath);

    private native boolean initBcnLayer(String layerPath);

    private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);
}
