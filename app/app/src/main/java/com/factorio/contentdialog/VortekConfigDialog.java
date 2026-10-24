package com.factorio.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.factorio.R;
import com.factorio.core.AppUtils;
import com.factorio.core.GPUHelper;
import com.factorio.core.KeyValueSet;
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

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("vkMaxVersion", StringUtils.parseNumber(sVkMaxVersion.getSelectedItem(), "0"));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));
            newConfig.put("imageCacheSize", StringUtils.parseNumber(sImageCacheSize.getSelectedItem()));
            newConfig.put("resourceMemoryType", sResourceMemoryType.getSelectedItemPosition());
            newConfig.put("bcnMode", sBcnMode.getSelectedItemPosition());

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
