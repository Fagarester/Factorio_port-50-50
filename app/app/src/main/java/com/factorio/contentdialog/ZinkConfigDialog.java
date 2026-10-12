package com.factorio.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.factorio.R;
import com.factorio.core.DefaultVersion;
import com.factorio.core.GeneralComponents;
import com.factorio.core.KeyValueSet;

/** Zink version: the built-in build is the known-good one, builds imported from a file are listed next to it. */
public class ZinkConfigDialog extends ContentDialog {
    public ZinkConfigDialog(final View anchor) {
        this(anchor, null);
    }

    public ZinkConfigDialog(final View anchor, final Runnable onConfirmed) {
        super(anchor.getContext(), R.layout.zink_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_display_settings);
        setTitle("Zink "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        KeyValueSet config = new KeyValueSet(anchor.getTag());
        GeneralComponents.initViews(GeneralComponents.Type.ZINK, findViewById(R.id.ZinkToolbox), sVersion, config.get("version"), DefaultVersion.ZINK);

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", String.valueOf(sVersion.getSelectedItem()));
            anchor.setTag(newConfig.toString());
            if (onConfirmed != null) onConfirmed.run();
        });
    }
}
