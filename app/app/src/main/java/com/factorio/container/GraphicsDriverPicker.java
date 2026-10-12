package com.factorio.container;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import com.factorio.MainActivity;
import com.factorio.R;
import com.factorio.contentdialog.TurnipConfigDialog;
import com.factorio.contentdialog.VirGLConfigDialog;
import com.factorio.contentdialog.VortekConfigDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.DriverPackages;
import com.factorio.core.GPUHelper;
import com.factorio.core.GeneralComponents;
import com.factorio.core.KeyValueSet;
import com.factorio.core.StringUtils;
import com.factorio.widget.TaggedSelectionBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Vulkan row: Turnip, Vortek (system driver), Auto (best installed driver for this GPU) and every driver package
 * the user has imported. Picking a package configures everything by itself:
 *   package (bionic: Mali PanVK, Adreno, Xclipse...) -> "vortek" renderer + adrenotoolsDriver=<package>
 *   Turnip build (.tzst)                             -> "turnip" + version=<build>
 * so the saved format (graphicsDriver "vortek,zink", config "k=v,k=v|k=v") stays the same as before.
 */
public class GraphicsDriverPicker {
    private static final String ITEM_TURNIP = "Turnip";
    private static final String ITEM_VORTEK = "Vortek";
    private static final String ITEM_AUTO = "Auto";

    private final LinearLayout container;
    private final Context context;
    private final TaggedSelectionBox vulkanBox;
    private final LinkedHashMap<String, DriverPackages.Info> packages = new LinkedHashMap<>();
    private String currentLabel = ITEM_VORTEK;

    public GraphicsDriverPicker(LinearLayout container, String selectedGraphicsDriver, String graphicsDriverConfig) {
        this.container = container;
        this.context = container.getContext();
        container.removeAllViews();
        String[] identifiers = GraphicsDrivers.parseIdentifiers(selectedGraphicsDriver);
        KeyValueSet[] configs = GraphicsDrivers.parseConfigs(selectedGraphicsDriver, graphicsDriverConfig);

        // ---- Vulkan ----
        vulkanBox = new TaggedSelectionBox(context);
        vulkanBox.setLabel("Vulkan");
        reloadVulkanItems();
        currentLabel = labelFor(identifiers[0], configs[0]);
        vulkanBox.setSelectedItem(currentLabel);
        vulkanBox.setTag(configs[0].toString());
        vulkanBox.setOnItemSelectedListener(this::onVulkanItemSelected);
        vulkanBox.setOnButtonClickListener(() -> {
            if (currentLabel.equals(ITEM_TURNIP)) (new TurnipConfigDialog(vulkanBox)).show();
            else (new VortekConfigDialog(vulkanBox, this::syncVulkanSelection)).show();
        });
        container.addView(vulkanBox);

        // ---- OpenGL ----
        final TaggedSelectionBox glBox = new TaggedSelectionBox(context);
        glBox.setLabel("OpenGL");
        glBox.setItems(GraphicsDrivers.getItems("OpenGL"));
        glBox.setSelectedItem(GraphicsDrivers.getName(identifiers[1]));
        glBox.setTag(configs[1].toString());
        glBox.setOnButtonClickListener(() -> {
            if (StringUtils.parseIdentifier(glBox.getSelectedItem()).equals(GraphicsDrivers.VIRGL)) (new VirGLConfigDialog(glBox)).show();
        });
        container.addView(glBox);
    }

    // ------------------------------------------------------------------ Vulkan list

    private void reloadVulkanItems() {
        packages.clear();
        ArrayList<String> items = new ArrayList<>();
        items.add(ITEM_TURNIP);
        items.add(ITEM_VORTEK);
        items.add(ITEM_AUTO);
        for (DriverPackages.Info info : DriverPackages.list(context)) {
            String label = info.label();
            if (items.contains(label)) label = label+" ("+info.id+")";
            packages.put(label, info);
            items.add(label);
        }
        vulkanBox.setItems(items.toArray(new String[0]));
    }

    private String labelFor(String identifier, KeyValueSet config) {
        if (identifier.equals(GraphicsDrivers.TURNIP)) return ITEM_TURNIP;
        String driver = config.get("adrenotoolsDriver");
        if (driver.equalsIgnoreCase(DriverPackages.AUTO)) return ITEM_AUTO;
        if (!driver.isEmpty() && !driver.equalsIgnoreCase(DriverPackages.SYSTEM)) {
            for (java.util.Map.Entry<String, DriverPackages.Info> entry : packages.entrySet()) {
                if (entry.getValue().id.equals(driver)) return entry.getKey();
            }
        }
        return ITEM_VORTEK;
    }

    private void onVulkanItemSelected(String label) {
        boolean oldIsTurnip = currentLabel.equals(ITEM_TURNIP);
        boolean newIsTurnip = label.equals(ITEM_TURNIP);
        KeyValueSet config = oldIsTurnip == newIsTurnip ? new KeyValueSet(vulkanBox.getTag()) : new KeyValueSet();

        if (!newIsTurnip) {
            DriverPackages.Info info = packages.get(label);
            if (info != null) {
                config.put("adrenotoolsDriver", info.id);
                DriverPackages.GpuFamily device = DriverPackages.GpuFamily.detect(context);
                if (!info.matchesDevice(device)) {
                    AppUtils.showToast(context, context.getString(R.string.driver_other_gpu_warning, info.family.title, device.title));
                }
                if (!info.isSupportedByOs()) AppUtils.showToast(context, context.getString(R.string.driver_needs_newer_android, info.minApi));
            }
            else config.put("adrenotoolsDriver", label.equals(ITEM_AUTO) ? DriverPackages.AUTO : DriverPackages.SYSTEM);
        }

        currentLabel = label;
        vulkanBox.setTag(config.toString());
    }

    /** The Vortek dialog can change the driver too: show the same choice in the list. */
    private void syncVulkanSelection() {
        currentLabel = labelFor(GraphicsDrivers.VORTEK, new KeyValueSet(vulkanBox.getTag()));
        vulkanBox.setSelectedItem(currentLabel);
    }

    // ------------------------------------------------------------------ install from file

    /** One button for every driver type: the file is analysed and the matching driver is selected automatically. */
    public void attachInstallButton(View button, final MainActivity activity) {
        button.setOnClickListener((v) -> {
            activity.setOpenFileCallback((uri) -> GeneralComponents.installFromUri(activity, uri, this::onDriverInstalled));
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        });
    }

    private void onDriverInstalled(DriverPackages.InstallResult result) {
        reloadVulkanItems();

        KeyValueSet config;
        String label;
        if (result.kind == DriverPackages.Kind.TURNIP) {
            config = currentLabel.equals(ITEM_TURNIP) ? new KeyValueSet(vulkanBox.getTag()) : new KeyValueSet();
            config.put("version", result.id);
            label = ITEM_TURNIP;
        }
        else {
            config = currentLabel.equals(ITEM_TURNIP) ? new KeyValueSet() : new KeyValueSet(vulkanBox.getTag());
            config.put("adrenotoolsDriver", result.info.id);
            label = labelFor(GraphicsDrivers.VORTEK, config);
        }

        currentLabel = label;
        vulkanBox.setTag(config.toString());
        vulkanBox.setSelectedItem(label);
        AppUtils.showToast(context, GeneralComponents.describe(context, result)+" — "+context.getString(R.string.driver_selected_automatically));
    }

    /** "Mali-G615 → Mali" for the settings screen. */
    public static String getDetectedGpuText(Context context) {
        String renderer = GPUHelper.glGetRenderer(context);
        DriverPackages.GpuFamily family = DriverPackages.GpuFamily.fromRenderer(renderer);
        return context.getString(R.string.detected_gpu)+": "+renderer+(family != DriverPackages.GpuFamily.UNKNOWN ? " ("+family.title+")" : "");
    }

    // ------------------------------------------------------------------ result

    public String getGraphicsDriver() {
        StringBuilder graphicsDriver = new StringBuilder();
        for (int i = 0; i < container.getChildCount(); i++) {
            TaggedSelectionBox taggedSelectionBox = (TaggedSelectionBox)container.getChildAt(i);
            if (graphicsDriver.length() > 0) graphicsDriver.append(',');
            if (taggedSelectionBox == vulkanBox) graphicsDriver.append(currentLabel.equals(ITEM_TURNIP) ? GraphicsDrivers.TURNIP : GraphicsDrivers.VORTEK);
            else graphicsDriver.append(StringUtils.parseIdentifier(taggedSelectionBox.getSelectedItem()));
        }
        return graphicsDriver.toString();
    }

    public String getGraphicsDriverConfig() {
        StringBuilder graphicsDriverConfig = new StringBuilder();
        for (int i = 0; i < container.getChildCount(); i++) {
            TaggedSelectionBox taggedSelectionBox = (TaggedSelectionBox)container.getChildAt(i);
            if (graphicsDriverConfig.length() > 0) graphicsDriverConfig.append('|');
            graphicsDriverConfig.append(taggedSelectionBox.getTag().toString());
        }
        return graphicsDriverConfig.toString();
    }
}
