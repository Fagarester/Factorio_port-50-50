package com.factorio.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.factorio.R;
import com.factorio.core.DefaultVersion;
import com.factorio.core.GeneralComponents;
import com.factorio.core.KeyValueSet;

/**
 * Gladio version: the built-in build is the known-good one, builds imported from a .tzst file are listed next to it
 * (same as Turnip / Zink). A new build can be added without rebuilding the app.
 */
public class GladioConfigDialog extends ContentDialog {
    public GladioConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.gladio_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_display_settings);
        setTitle("Gladio "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        KeyValueSet config = new KeyValueSet(anchor.getTag());
        GeneralComponents.initViews(GeneralComponents.Type.GLADIO, findViewById(R.id.GladioToolbox), sVersion, config.get("version"), DefaultVersion.GLADIO);

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", String.valueOf(sVersion.getSelectedItem()));
            anchor.setTag(newConfig.toString());
        });
    }
}
