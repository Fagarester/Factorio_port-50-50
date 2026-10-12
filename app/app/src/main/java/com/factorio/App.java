package com.factorio;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import com.factorio.core.PackagePath;

import java.util.Collections;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // package-independent paths (see PackagePath): must run before any archive is extracted
        PackagePath.init(this);
        publishSettingsShortcut();
    }

    // Long press on the app icon -> "Settings". It is a dynamic shortcut, so it uses the real package name
    // at run time (a static shortcuts.xml would break when the package name is changed).
    private void publishSettingsShortcut() {
        try {
            ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
            if (shortcutManager == null) return;

            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("open_settings", true);

            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "settings")
                .setShortLabel(getString(R.string.settings))
                .setLongLabel(getString(R.string.settings))
                .setIcon(Icon.createWithResource(this, R.drawable.icon_settings))
                .setIntent(intent)
                .build();
            shortcutManager.setDynamicShortcuts(Collections.singletonList(shortcut));
        }
        catch (Exception e) {}
    }
}
