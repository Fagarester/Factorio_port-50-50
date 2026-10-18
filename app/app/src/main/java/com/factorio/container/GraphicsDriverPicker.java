package com.factorio.container;

import android.content.Context;
import android.widget.LinearLayout;

import com.factorio.R;
import com.factorio.contentdialog.TurnipConfigDialog;
import com.factorio.contentdialog.ZinkConfigDialog;
import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;
import com.factorio.core.StringUtils;
import com.factorio.widget.TaggedSelectionBox;

/**
 * Two rows: Vulkan (Turnip) and OpenGL (OpenGL / Zink).
 * Turnip builds are installed from a file with the install icon in the Turnip configuration dialog.
 * The saved format (graphicsDriver "turnip,opengl", config "k=v,k=v|k=v") stays the same as before.
 */
public class GraphicsDriverPicker {
    private final LinearLayout container;
    private final TaggedSelectionBox vulkanBox;
    private final TaggedSelectionBox glBox;

    public GraphicsDriverPicker(LinearLayout container, String selectedGraphicsDriver, String graphicsDriverConfig) {
        this.container = container;
        Context context = container.getContext();
        container.removeAllViews();
        String[] identifiers = GraphicsDrivers.parseIdentifiers(selectedGraphicsDriver);
        KeyValueSet[] configs = GraphicsDrivers.parseConfigs(selectedGraphicsDriver, graphicsDriverConfig);

        // ---- Vulkan ----
        vulkanBox = new TaggedSelectionBox(context);
        vulkanBox.setLabel("Vulkan");
        vulkanBox.setItems(GraphicsDrivers.getItems("Vulkan"));
        vulkanBox.setSelectedItem(GraphicsDrivers.getName(GraphicsDrivers.TURNIP));
        vulkanBox.setTag(configs[0].toString());
        vulkanBox.setOnButtonClickListener(() -> (new TurnipConfigDialog(vulkanBox)).show());
        container.addView(vulkanBox);

        // ---- OpenGL ----
        glBox = new TaggedSelectionBox(context);
        glBox.setLabel("OpenGL");
        glBox.setItems(GraphicsDrivers.getItems("OpenGL"));
        glBox.setSelectedItem(GraphicsDrivers.getName(identifiers[1]));
        glBox.setTag(configs[1].toString());
        glBox.setOnButtonClickListener(() -> {
            if (StringUtils.parseIdentifier(glBox.getSelectedItem()).equals(GraphicsDrivers.ZINK)) (new ZinkConfigDialog(glBox)).show();
        });
        container.addView(glBox);
    }

    /** "Detected GPU: Adreno (TM) 830" for the settings screen. */
    public static String getDetectedGpuText(Context context) {
        return context.getString(R.string.detected_gpu)+": "+GPUHelper.glGetRenderer(context);
    }

    public String getGraphicsDriver() {
        StringBuilder graphicsDriver = new StringBuilder();
        for (int i = 0; i < container.getChildCount(); i++) {
            TaggedSelectionBox taggedSelectionBox = (TaggedSelectionBox)container.getChildAt(i);
            if (graphicsDriver.length() > 0) graphicsDriver.append(',');
            if (taggedSelectionBox == vulkanBox) graphicsDriver.append(GraphicsDrivers.TURNIP);
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
