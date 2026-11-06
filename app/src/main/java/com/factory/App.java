package com.factory;

import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import com.termux.x11.CmdEntryPoint;
import com.termux.x11.LorieApp;
import com.termux.x11.MainActivity;
import com.factory.runtime.ScreenLog;

import java.util.Collections;

/** Termux:X11's activity and receivers expect their Application class (LorieApp). */
public class App extends LorieApp {
    @Override
    public void onCreate() {
        super.onCreate();
        GameScreen.install(this);
        publishSettingsShortcut();
    }

    // Keeps Termux:X11 from posting its own notification: GameService shows ours.
    @Override
    public void onActivityResumed(MainActivity activity) {}

    @Override
    public void onBroadcastReceive(Intent intent) {
        if (CmdEntryPoint.ACTION_START.equals(intent.getAction())) ScreenLog.INSTANCE.announcement();
        super.onBroadcastReceive(intent);
    }

    // Long press on the app icon -> "Settings". It is a dynamic shortcut, so it uses the real package name
    // at run time (a static shortcuts.xml would break when the package name is changed).
    private void publishSettingsShortcut() {
        try {
            ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
            if (shortcutManager == null) return;

            Intent intent = new Intent(this, com.factory.MainActivity.class);
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
