package com.factorio.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

import com.factorio.R;
import com.factorio.core.AppUtils;
import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;
import com.factorio.core.MaliPresets;
import com.factorio.core.StringUtils;
import com.factorio.widget.MultiSelectionComboBox;
import com.factorio.xenvironment.components.VortekRendererComponent;

public class VortekConfigDialog extends ContentDialog {
    public static final String DEFAULT_VK_MAX_VERSION = GPUHelper.vkVersionMajor(VortekRendererComponent.VK_MAX_VERSION)+"."+GPUHelper.vkVersionMinor(VortekRendererComponent.VK_MAX_VERSION);

    public VortekConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.vortek_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_display_settings);
        setTitle("Vortek "+context.getString(R.string.configuration));

        final Spinner sVkMaxVersion = findViewById(R.id.SVkMaxVersion);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sImageCacheSize = findViewById(R.id.SImageCacheSize);
        final Spinner sResourceMemoryType = findViewById(R.id.SResourceMemoryType);
        final Spinner sBcnMode = findViewById(R.id.SBcnMode);
        final Spinner sMemorySync = findViewById(R.id.SMemorySync);
        final Spinner sMaliPreset = findViewById(R.id.SMaliPreset);
        final TextView tvMaliPresetInfo = findViewById(R.id.TVMaliPresetInfo);
        final MultiSelectionComboBox mscbExposedExtensions = findViewById(R.id.MSCBExposedExtensions);

        final String[] deviceExtensions = GPUHelper.vkGetDeviceExtensions();
        mscbExposedExtensions.setPopupWindowWidth(360);
        mscbExposedExtensions.setDisplayText(context.getString(R.string.multiselection_combobox_display_text));
        mscbExposedExtensions.setItems(deviceExtensions);

        KeyValueSet config = new KeyValueSet(anchor.getTag());

        String exposedDeviceExtensionsVal = config.get("exposedDeviceExtensions", "all");
        if (exposedDeviceExtensionsVal.contains("|")) exposedDeviceExtensionsVal = "all";

        if (exposedDeviceExtensionsVal.equals("all")) {
            mscbExposedExtensions.setSelectedItems(deviceExtensions);
        }
        else if (!exposedDeviceExtensionsVal.isEmpty()) {
            String[] selectedItems = exposedDeviceExtensionsVal.split(":");
            mscbExposedExtensions.setSelectedItems(selectedItems.length > 1 ? selectedItems : deviceExtensions);
        }

        AppUtils.setSpinnerSelectionFromValue(sVkMaxVersion, config.get("vkMaxVersion", DEFAULT_VK_MAX_VERSION));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        AppUtils.setSpinnerSelectionFromNumber(sImageCacheSize, config.get("imageCacheSize", String.valueOf(VortekRendererComponent.IMAGE_CACHE_SIZE)));
        sResourceMemoryType.setSelection(config.getInt("resourceMemoryType"));
        sBcnMode.setSelection(config.getInt("bcnMode"));
        sMemorySync.setSelection(config.getInt("memorySync"));

        // presets: "not selected" first (= the settings below are the user's own); picking one fills the fields below
        final List<MaliPresets.Preset> presets = MaliPresets.all();
        String[] presetTitles = new String[presets.size()];
        int selectedPreset = 0;
        String savedPresetId = config.get("preset", MaliPresets.NONE_ID);
        for (int i = 0; i < presets.size(); i++) {
            presetTitles[i] = presets.get(i).title;
            if (presets.get(i).id.equals(savedPresetId)) selectedPreset = i;
        }
        sMaliPreset.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, presetTitles));
        sMaliPreset.setSelection(selectedPreset, false);
        if (selectedPreset > 0) {
            tvMaliPresetInfo.setText(presets.get(selectedPreset).description);
            tvMaliPresetInfo.setVisibility(View.VISIBLE);
        }

        final boolean[] firstCallback = {true}; // Spinner reports the initial selection too: it must not overwrite the saved fields
        sMaliPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstCallback[0]) {
                    firstCallback[0] = false;
                    return;
                }

                MaliPresets.Preset preset = presets.get(position);
                if (preset.vortekConfig == null) {
                    tvMaliPresetInfo.setVisibility(View.GONE);
                    return;
                }

                tvMaliPresetInfo.setText(preset.description);
                tvMaliPresetInfo.setVisibility(View.VISIBLE);

                KeyValueSet presetConfig = new KeyValueSet(preset.vortekConfig);
                AppUtils.setSpinnerSelectionFromValue(sVkMaxVersion, presetConfig.get("vkMaxVersion", DEFAULT_VK_MAX_VERSION));
                AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, presetConfig.get("maxDeviceMemory", "0"));
                AppUtils.setSpinnerSelectionFromNumber(sImageCacheSize, presetConfig.get("imageCacheSize", String.valueOf(VortekRendererComponent.IMAGE_CACHE_SIZE)));
                sResourceMemoryType.setSelection(presetConfig.getInt("resourceMemoryType"));
                sBcnMode.setSelection(presetConfig.getInt("bcnMode"));
                sMemorySync.setSelection(presetConfig.getInt("memorySync"));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("vkMaxVersion", StringUtils.parseNumber(sVkMaxVersion.getSelectedItem(), "0"));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));
            newConfig.put("imageCacheSize", StringUtils.parseNumber(sImageCacheSize.getSelectedItem()));
            newConfig.put("resourceMemoryType", sResourceMemoryType.getSelectedItemPosition());
            newConfig.put("bcnMode", sBcnMode.getSelectedItemPosition());
            newConfig.put("memorySync", sMemorySync.getSelectedItemPosition());
            MaliPresets.Preset chosenPreset = presets.get(sMaliPreset.getSelectedItemPosition());
            if (chosenPreset.vortekConfig != null) newConfig.put("preset", chosenPreset.id);

            String[] selectedItems = mscbExposedExtensions.getSelectedItems();
            if (selectedItems.length > 0) {
                if (selectedItems.length == deviceExtensions.length) {
                    newConfig.put("exposedDeviceExtensions", "all");
                }
                else newConfig.put("exposedDeviceExtensions", String.join(":", selectedItems));
            }
            anchor.setTag(newConfig.toString());
        });
    }
}
