package com.factorio.container;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.factorio.R;
import com.factorio.contentdialog.TurnipConfigDialog;
import com.factorio.contentdialog.VortekConfigDialog;
import com.factorio.contentdialog.ZinkConfigDialog;
import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;
import com.factorio.core.StringUtils;
import com.factorio.widget.TaggedSelectionBox;

/**
 * Two independent blocks, one active (check box):
 *   [x] Turnip + OpenGL / Zink                (Vulkan: Turnip, OpenGL: OpenGL | Zink)
 *   [ ] Vortek + Gladio / Zink 22.2.5         (Vulkan: Vortek, OpenGL: Gladio | Zink 22.2.5)
 * The saved format (graphicsDriver "turnip,opengl", config "k=v,k=v|k=v") of block 1 stays the same as before;
 * block 2 keeps its choices and settings in container extras (see GraphicsDrivers), so switching never loses either.
 */
public class GraphicsDriverPicker {
    private final TaggedSelectionBox vulkanBoxA, glBoxA, vulkanBoxB, glBoxB;
    private final CheckBox cbTurnip, cbVortek;
    private final View blockA, blockB;
    private boolean legacyActive;
    private boolean updating = false;

    public GraphicsDriverPicker(LinearLayout container, Container data) {
        Context context = container.getContext();
        container.removeAllViews();

        String[] active = GraphicsDrivers.parseIdentifiers(data.getGraphicsDriver());
        legacyActive = GraphicsDrivers.isLegacyMode(active);
        String[] idsA = legacyActive ? GraphicsDrivers.normalizeBlock(GraphicsDrivers.parseIdentifiers(data.getExtra(GraphicsDrivers.EXTRA_BLOCK_TURNIP)), false) : active;
        String[] idsB = legacyActive ? active : GraphicsDrivers.normalizeBlock(splitOrNull(data.getExtra(GraphicsDrivers.EXTRA_BLOCK_VORTEK)), true);
        KeyValueSet[] configsA = GraphicsDrivers.parseConfigs(GraphicsDrivers.TURNIP, data.getGraphicsDriverConfig());
        KeyValueSet[] configsB = GraphicsDrivers.parseConfigs(GraphicsDrivers.VORTEK, data.getExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK));

        // ---- block 1: Turnip + OpenGL / Zink ----
        vulkanBoxA = new TaggedSelectionBox(context);
        vulkanBoxA.setLabel("Vulkan");
        vulkanBoxA.setItems(GraphicsDrivers.getItems("Vulkan"));
        vulkanBoxA.setSelectedItem(GraphicsDrivers.getName(GraphicsDrivers.TURNIP));
        vulkanBoxA.setTag(configsA[0].toString());
        vulkanBoxA.setOnButtonClickListener(() -> (new TurnipConfigDialog(vulkanBoxA)).show());

        glBoxA = new TaggedSelectionBox(context);
        glBoxA.setLabel("OpenGL");
        glBoxA.setItems(GraphicsDrivers.getItems("OpenGL"));
        glBoxA.setSelectedItem(GraphicsDrivers.getName(idsA[1]));
        glBoxA.setTag(configsA[1].toString());
        glBoxA.setOnButtonClickListener(() -> {
            if (StringUtils.parseIdentifier(glBoxA.getSelectedItem()).equals(GraphicsDrivers.ZINK)) (new ZinkConfigDialog(glBoxA)).show();
        });

        // ---- block 2: Vortek + Gladio / Zink 22.2.5 ----
        vulkanBoxB = new TaggedSelectionBox(context);
        vulkanBoxB.setLabel("Vulkan");
        vulkanBoxB.setItems(GraphicsDrivers.getLegacyItems("Vulkan"));
        vulkanBoxB.setSelectedItem(GraphicsDrivers.getName(GraphicsDrivers.VORTEK));
        vulkanBoxB.setTag(configsB[0].toString());
        vulkanBoxB.setOnButtonClickListener(() -> (new VortekConfigDialog(vulkanBoxB)).show());

        glBoxB = new TaggedSelectionBox(context);
        glBoxB.setLabel("OpenGL");
        glBoxB.setItems(GraphicsDrivers.getLegacyItems("OpenGL"));
        glBoxB.setSelectedItem(GraphicsDrivers.getName(idsB[1]));
        glBoxB.setTag(configsB[1].toString());
        // Gladio and Zink 22.2.5 have no settings

        cbTurnip = new CheckBox(context);
        cbTurnip.setText(R.string.graphics_block_turnip);
        cbVortek = new CheckBox(context);
        cbVortek.setText(R.string.graphics_block_vortek);
        blockA = createBlock(context, cbTurnip, vulkanBoxA, glBoxA);
        blockB = createBlock(context, cbVortek, vulkanBoxB, glBoxB);
        container.addView(blockA);
        container.addView(blockB);

        cbTurnip.setOnCheckedChangeListener((button, checked) -> onBlockChecked(false, checked));
        cbVortek.setOnCheckedChangeListener((button, checked) -> onBlockChecked(true, checked));
        refreshBlocks();
    }

    private static String[] splitOrNull(String value) {
        return value == null || value.isEmpty() ? null : value.split(",");
    }

    private static LinearLayout createBlock(Context context, CheckBox checkBox, TaggedSelectionBox vulkanBox, TaggedSelectionBox glBox) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(vulkanBox);
        row.addView(glBox);
        block.addView(checkBox);
        block.addView(row);
        return block;
    }

    private void onBlockChecked(boolean legacy, boolean checked) {
        if (updating) return;
        if (checked) legacyActive = legacy;
        // unchecking the active block is not possible: one block is always selected
        refreshBlocks();
    }

    private void refreshBlocks() {
        updating = true;
        cbTurnip.setChecked(!legacyActive);
        cbVortek.setChecked(legacyActive);
        blockA.setAlpha(legacyActive ? 0.5f : 1f);
        blockB.setAlpha(legacyActive ? 1f : 0.5f);
        updating = false;
    }

    /** "Detected GPU: Adreno (TM) 830" for the settings screen. */
    public static String getDetectedGpuText(Context context) {
        return context.getString(R.string.detected_gpu)+": "+GPUHelper.glGetRenderer(context);
    }

    private String idOf(TaggedSelectionBox box) {
        return GraphicsDrivers.identifierOf(box.getSelectedItem());
    }

    /** Writes both blocks into the container: the active one becomes graphicsDriver, the other stays in the extras. */
    public void applyTo(Container data) {
        String idsA = GraphicsDrivers.TURNIP+","+idOf(glBoxA);
        String idsB = GraphicsDrivers.VORTEK+","+idOf(glBoxB);
        data.putExtra(GraphicsDrivers.EXTRA_BLOCK_TURNIP, idsA);
        data.putExtra(GraphicsDrivers.EXTRA_BLOCK_VORTEK, idsB);
        data.putExtra(GraphicsDrivers.EXTRA_CONFIG_VORTEK, vulkanBoxB.getTag()+"|"+glBoxB.getTag());
        data.setGraphicsDriver(legacyActive ? idsB : idsA);
        data.setGraphicsDriverConfig(vulkanBoxA.getTag()+"|"+glBoxA.getTag());
    }
}
