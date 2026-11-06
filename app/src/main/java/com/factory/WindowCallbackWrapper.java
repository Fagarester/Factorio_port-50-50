package com.factory;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/** Passes everything to another Window.Callback; subclasses take the events they need first. */
class WindowCallbackWrapper implements Window.Callback {
    private final Window.Callback target;

    WindowCallbackWrapper(Window.Callback target) {
        this.target = target;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) { return target.dispatchKeyEvent(event); }
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return target.dispatchKeyShortcutEvent(event); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return target.dispatchTouchEvent(event); }
    @Override public boolean dispatchTrackballEvent(MotionEvent event) { return target.dispatchTrackballEvent(event); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return target.dispatchGenericMotionEvent(event); }
    @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) { return target.dispatchPopulateAccessibilityEvent(event); }
    @Override public View onCreatePanelView(int featureId) { return target.onCreatePanelView(featureId); }
    @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return target.onCreatePanelMenu(featureId, menu); }
    @Override public boolean onPreparePanel(int featureId, View view, Menu menu) { return target.onPreparePanel(featureId, view, menu); }
    @Override public boolean onMenuOpened(int featureId, Menu menu) { return target.onMenuOpened(featureId, menu); }
    @Override public boolean onMenuItemSelected(int featureId, MenuItem item) { return target.onMenuItemSelected(featureId, item); }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) { target.onWindowAttributesChanged(attrs); }
    @Override public void onContentChanged() { target.onContentChanged(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { target.onWindowFocusChanged(hasFocus); }
    @Override public void onAttachedToWindow() { target.onAttachedToWindow(); }
    @Override public void onDetachedFromWindow() { target.onDetachedFromWindow(); }
    @Override public void onPanelClosed(int featureId, Menu menu) { target.onPanelClosed(featureId, menu); }
    @Override public boolean onSearchRequested() { return target.onSearchRequested(); }
    @Override public boolean onSearchRequested(SearchEvent searchEvent) { return target.onSearchRequested(searchEvent); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return target.onWindowStartingActionMode(callback); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return target.onWindowStartingActionMode(callback, type); }
    @Override public void onActionModeStarted(ActionMode mode) { target.onActionModeStarted(mode); }
    @Override public void onActionModeFinished(ActionMode mode) { target.onActionModeFinished(mode); }
    @Override public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) { target.onProvideKeyboardShortcuts(data, menu, deviceId); }
    @Override public void onPointerCaptureChanged(boolean hasCapture) { target.onPointerCaptureChanged(hasCapture); }
}
