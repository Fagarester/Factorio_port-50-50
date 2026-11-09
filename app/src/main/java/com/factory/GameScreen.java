package com.factory;

import android.app.Activity;
import android.app.Application;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.factory.contentdialog.ContentDialog;
import com.factory.contentdialog.DebugDialog;
import com.factory.core.GameSettings;
import com.factory.inputcontrols.ControlsProfile;
import com.factory.inputcontrols.ExternalController;
import com.factory.inputcontrols.InputControlsManager;
import com.factory.runtime.Paths;
import com.factory.runtime.ScreenLog;
import com.factory.runtime.Session;
import com.factory.runtime.Settings;
import com.factory.runtime.controller.Gamepads;
import com.factory.widget.InputControlsView;
import com.factory.widget.TouchpadView;
import com.factory.xserver.XServer;
import com.google.android.material.navigation.NavigationView;
import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;

import java.io.File;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

import kotlin.Unit;

/**
 * The game screen: Termux:X11's activity draws the game, this adds our side drawer, on-screen controls and
 * touchpad on top of it (from outside, so Termux:X11's activity stays unchanged).
 */
public final class GameScreen implements Application.ActivityLifecycleCallbacks {
    /** Written by the input controls screen when it closes: the game screen applies it when it comes back. */
    public static final String PREF_PROFILE_ID = "game_selected_profile_id";
    public static final String PREF_CONTROLS_CHANGED = "game_controls_changed";
    /** 1 = "control" (assets/inputcontrols/profiles/controls-1.icp), switched on automatically. */
    private static final int DEFAULT_CONTROLS_PROFILE_ID = 1;

    private static final GameScreen instance = new GameScreen();
    private final WeakHashMap<Activity, Overlay> overlays = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean exiting = false;
    private boolean restarting = false;

    private GameScreen() {}

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(instance);
        Session.INSTANCE.addListener(() -> {
            instance.onSessionChanged(app);
            return Unit.INSTANCE;
        });
    }

    /** Leaves the game: Factorio quits (it keeps its last autosave), then the whole app closes. */
    public static void exit(Activity activity) {
        instance.exiting = true;
        if (Session.INSTANCE.getState() == Session.State.STOPPED) killApp();
        else {
            Session.INSTANCE.quit();
            // a game hung in the driver ignores SIGTERM: Session kills it after 10 s
            instance.main.postDelayed(GameScreen::killApp, 12000);
        }
        activity.finishAndRemoveTask();
    }

    /** Quits the game and starts it again (settings changed while it was running). */
    public static void restart(Activity activity) {
        if (Session.INSTANCE.getState() == Session.State.STOPPED) return;
        instance.restarting = true;
        Session.INSTANCE.quit();
    }

    private static void killApp() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Every 5 s while the game runs: its logs to Documents/Factory. */
    private final Runnable logCopier = new Runnable() {
        @Override
        public void run() {
            if (appContext == null || Session.INSTANCE.getState() == Session.State.STOPPED) return;
            final Context context = appContext;
            new Thread(() -> LogExport.copy(context), "factory-log-export").start();
            main.postDelayed(this, 5000);
        }
    };
    private Context appContext;

    private void onSessionChanged(Application app) {
        appContext = app;
        if (Session.INSTANCE.getState() == Session.State.RUNNING) {
            main.removeCallbacks(logCopier);
            main.postDelayed(logCopier, 5000);
        }
        if (Session.INSTANCE.getState() != Session.State.STOPPED) return;
        main.removeCallbacks(logCopier);
        LogExport.copy(app);
        Integer failure = Session.INSTANCE.getLastFailure();
        // the benchmark quits the game itself when it's done: the launcher shows the report
        // (its results count even if Factorio's exit status after SIGTERM isn't 0)
        boolean benchmark = Benchmark.isRunning(app) && !exiting && !restarting && (failure == null || Benchmark.hasResult(app));
        if (!benchmark && Benchmark.isRunning(app)) Benchmark.clear(app);   // a pending one (restart) stays
        if (benchmark) {
            Intent intent = new Intent(app, com.factory.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("benchmark_done", true);
            app.startActivity(intent);
            for (Activity activity : overlays.keySet().toArray(new Activity[0])) activity.finish();
        }
        else if (restarting && !exiting) {
            restarting = false;
            Intent intent = new Intent(app, com.factory.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            app.startActivity(intent);
            for (Activity activity : overlays.keySet().toArray(new Activity[0])) activity.finish();
        }
        else if (failure != null && !exiting) {
            // the game died (usually before its window opened): the launcher shows why
            Intent intent = new Intent(app, com.factory.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("game_failed", failure);
            intent.putExtra("game_no_window", Session.INSTANCE.getNoWindow());
            app.startActivity(intent);
            for (Activity activity : overlays.keySet().toArray(new Activity[0])) activity.finish();
        }
        else killApp();
    }

    /** Termux:X11's activity or the one with the X server inside the app. */
    private static boolean isGameActivity(Activity activity) {
        return activity instanceof MainActivity || activity instanceof XServerGameActivity;
    }

    @Override
    public void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (activity instanceof XServerGameActivity) {
            overlays.put(activity, new Overlay(activity, null, (XServerGameActivity)activity));
            return;
        }
        if (!(activity instanceof MainActivity)) return;
        // back from Recents after the game ended in the background: the X server went with it
        if (Session.INSTANCE.getState() == Session.State.STOPPED) {
            activity.startActivity(new Intent(activity, com.factory.MainActivity.class));
            activity.finish();
            return;
        }
        overlays.put(activity, new Overlay(activity, (MainActivity)activity, null));
        screenConfigs.put(activity, new android.content.res.Configuration(activity.getResources().getConfiguration()));
        ScreenLog.INSTANCE.add("game screen " + Integer.toHexString(System.identityHashCode(activity)) + ": " + activity.getClass().getSimpleName() +
            (savedInstanceState != null ? ", restored (recreated)" : "") + ", intent flags 0x" + Integer.toHexString(activity.getIntent().getFlags()));
        logConnection((MainActivity)activity);
    }

    /** The game screen's configuration when it opened: what changed when Android closes it (screen.log). */
    private final java.util.Map<Activity, android.content.res.Configuration> screenConfigs = new java.util.WeakHashMap<>();

    private static String screenId(Activity activity) {
        return "game screen " + Integer.toHexString(System.identityHashCode(activity));
    }

    /** screen.log: when the game screen opened and when it joined the X server (the black time between them). */
    private static void logConnection(final MainActivity termux) {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final long deadline = android.os.SystemClock.uptimeMillis() + 30000;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (termux.isDestroyed()) return;
                if (termux.getLorieView().connected()) {
                    ScreenLog.INSTANCE.add(screenId(termux) + " connected to the X server");
                    return;
                }
                if (android.os.SystemClock.uptimeMillis() < deadline) handler.postDelayed(this, 50);
            }
        });
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (isGameActivity(activity)) Session.INSTANCE.resume();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity instanceof MainActivity) ScreenLog.INSTANCE.add(screenId(activity) + " resumed");
        Overlay overlay = overlays.get(activity);
        if (overlay != null) overlay.onResume();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity instanceof MainActivity) ScreenLog.INSTANCE.add(screenId(activity) + " paused");
        Overlay overlay = overlays.get(activity);
        if (overlay != null) overlay.onPause();
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (activity instanceof MainActivity) ScreenLog.INSTANCE.add(screenId(activity) + " stopped");
        if (isGameActivity(activity) && !activity.isInPictureInPictureMode() && Settings.INSTANCE.pauseInBackground(activity)) {
            Session.INSTANCE.pause();
        }
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity instanceof MainActivity) {
            android.content.res.Configuration before = screenConfigs.remove(activity);
            int diff = before != null ? before.diff(activity.getResources().getConfiguration()) : -1;
            ScreenLog.INSTANCE.add(screenId(activity) + " closed: finishing " + activity.isFinishing() + ", changing configurations " +
                activity.isChangingConfigurations() + ", configuration diff 0x" + Integer.toHexString(diff));
        }
        Overlay overlay = overlays.remove(activity);
        if (overlay != null) overlay.onDestroy();
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    /** Everything we add to one game activity. */
    private static final class Overlay implements NavigationView.OnNavigationItemSelectedListener {
        private final Activity activity;
        /** One of the two: Termux:X11's activity, or the X server inside the app. */
        private final MainActivity termux;
        private final XServerGameActivity embedded;
        private final Context context;
        private final SharedPreferences preferences;
        private final DrawerLayout drawerLayout;
        private final XServer xServer;
        private final TouchpadView touchpadView;
        private final InputControlsView inputControlsView;
        private final InputControlsManager inputControlsManager;
        private final Gamepads gamepads;
        private InputManager.InputDeviceListener gamepadListener;
        private boolean controlsHiddenByGamepad = false;
        private boolean showControlsWithGamepad = false;
        private float globalCursorSpeed = 1.0f;
        private boolean benchmark = false;
        private android.widget.TextView benchmarkStatus;
        private final Handler statusHandler = new Handler(Looper.getMainLooper());
        /** Twice a second while the benchmark runs: the scene on screen and how many are measured. */
        private final Runnable benchmarkTicker = new Runnable() {
            @Override
            public void run() {
                if (benchmarkStatus == null || activity.isDestroyed()) return;
                benchmarkStatus.setText(Benchmark.status(activity));
                statusHandler.postDelayed(this, 500);
            }
        };

        Overlay(Activity activity, MainActivity termux, XServerGameActivity embedded) {
            this.activity = activity;
            this.termux = termux;
            this.embedded = embedded;
            this.context = new ContextThemeWrapper(activity, R.style.AppTheme);
            preferences = PreferenceManager.getDefaultSharedPreferences(activity);

            View root = LayoutInflater.from(context).inflate(R.layout.xserver_display_activity, null);
            activity.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            drawerLayout = root.findViewById(R.id.DrawerLayout);
            drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

            NavigationView navigationView = root.findViewById(R.id.NavigationView);
            Menu menu = navigationView.getMenu();
            menu.findItem(R.id.menu_item_logs).setVisible(preferences.getBoolean("enable_debug_logs", false));
            navigationView.setNavigationItemSelectedListener(this);
            root.findViewById(R.id.LLExit).setOnClickListener((v) -> GameScreen.exit(activity));

            int[] size = GameSettings.getScreenSizeValues(activity);
            xServer = new XServer(new XServer.ScreenInfo(size[0], size[1]));
            if (termux != null) xServer.setLorieView(termux.getLorieView());
            else xServer.setEmbedded(embedded.getXServer());
            xServer.setRelativeMouseMovement(preferences.getBoolean("relative_mouse_movement", false));

            FrameLayout display = root.findViewById(R.id.FLXServerDisplay);
            globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
            touchpadView = new TouchpadView(context, xServer, false);
            // the keyboard focus stays on Termux:X11's view: its key handling sends the keys to the game
            touchpadView.setFocusable(false);
            touchpadView.setSensitivity(globalCursorSpeed);
            touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
            touchpadView.setFourFingersTapCallback(() -> {
                if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
            });
            display.addView(touchpadView);

            inputControlsManager = new InputControlsManager(context);
            inputControlsView = new InputControlsView(context);
            inputControlsView.setFocusable(false);
            inputControlsView.setFocusableInTouchMode(false);
            inputControlsView.setShowTouchscreenControls(preferences.getBoolean("show_touchscreen_controls", true));
            inputControlsView.setTouchpadView(touchpadView);
            inputControlsView.setXServer(xServer);
            inputControlsView.setVisibility(View.GONE);
            display.addView(inputControlsView);

            int profileId = preferences.getInt(PREF_PROFILE_ID, DEFAULT_CONTROLS_PROFILE_ID);
            ControlsProfile profile = profileId > 0 ? inputControlsManager.getProfile(profileId) : null;
            // the benchmark: no on-screen controls (nothing to press), a status line on top instead
            benchmark = Benchmark.isRunning(activity) || Benchmark.isPending(activity);
            if (profile != null && !benchmark) showInputControls(profile);
            if (benchmark) {
                benchmarkStatus = new android.widget.TextView(context);
                benchmarkStatus.setTextColor(0xffffffff);
                benchmarkStatus.setTextSize(14);
                benchmarkStatus.setBackgroundColor(0xaa000000);
                int pad = (int)(6 * activity.getResources().getDisplayMetrics().density);
                benchmarkStatus.setPadding(2 * pad, pad, 2 * pad, pad);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                params.topMargin = pad;
                display.addView(benchmarkStatus, params);
                benchmarkTicker.run();
            }

            gamepads = new Gamepads(activity, () -> {
                toggleDrawer();
                return Unit.INSTANCE;
            }, (inUse) -> Unit.INSTANCE);
            if (termux != null) takeKeysFirst();
            Window window = activity.getWindow();
            window.setCallback(new WindowCallback(window.getCallback()));
            setupGamepadAutoControls();
        }

        /** Termux:X11 sends keys to the X server before the window sees them (LorieView's pre-IME listener). */
        private void takeKeysFirst() {
            try {
                Field field = MainActivity.class.getDeclaredField("mLorieKeyListener");
                field.setAccessible(true);
                final View.OnKeyListener keys = (View.OnKeyListener)field.get(termux);
                View.OnKeyListener ours = (view, keyCode, event) -> onKey(event) || keys.onKey(view, keyCode, event);
                field.set(termux, ours);
                termux.getLorieView().setOnKeyListener(ours);
            }
            catch (ReflectiveOperationException | RuntimeException e) {
                android.util.Log.w("Factory", "Controller bindings may reach Termux:X11 as keys", e);
            }
        }

        /** Back opens the side drawer; controller buttons go to the controls profile's bindings or the game's pad. */
        private boolean onKey(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) toggleDrawer();
                return true;
            }
            if (inputControlsView.getVisibility() == View.VISIBLE && inputControlsView.onKeyEvent(event)) return true;
            return gamepads.key(event);
        }

        private boolean onGenericMotion(MotionEvent event) {
            if (inputControlsView.getVisibility() == View.VISIBLE && inputControlsView.onGenericMotionEvent(event)) return true;
            if (gamepads.motion(event)) return true;
            return touchpadView.onExternalMouseEvent(event);
        }

        private void toggleDrawer() {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawers();
            else drawerLayout.openDrawer(GravityCompat.START);
        }

        void onResume() {
            if (preferences.getBoolean(PREF_CONTROLS_CHANGED, false)) {
                preferences.edit().putBoolean(PREF_CONTROLS_CHANGED, false).apply();
                applyInputControlsScreenResult();
            }
        }

        void onPause() {
            gamepads.release();
        }

        void onDestroy() {
            if (gamepadListener != null) {
                InputManager inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
                if (inputManager != null) inputManager.unregisterInputDeviceListener(gamepadListener);
                gamepadListener = null;
            }
            gamepads.close();
        }

        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.menu_item_keyboard) {
                if (termux != null) termux.getLorieView().toggleKeyboardVisible();
                else {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.toggleSoftInput(0, 0);
                }
            }
            else if (id == R.id.menu_item_input_controls) {
                ControlsProfile activeProfile = inputControlsView.getProfile();
                Intent intent = new Intent(activity, com.factory.MainActivity.class);
                intent.putExtra("edit_input_controls", true);
                intent.putExtra("from_game", true);
                intent.putExtra("selected_profile_id", activeProfile != null ? activeProfile.id : 0);
                activity.startActivity(intent);
            }
            else if (id == R.id.menu_item_pip_mode) {
                int[] size = GameSettings.getScreenSizeValues(activity);
                activity.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(size[0], size[1])).build());
            }
            else if (id == R.id.menu_item_logs) {
                showLogs();
            }
            else if (id == R.id.menu_item_touchpad_help) {
                ContentDialog dialog = new ContentDialog(context, R.layout.touchpad_help_dialog);
                dialog.setTitle(R.string.touchpad_help);
                dialog.setIcon(R.drawable.icon_help);
                dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
                dialog.show();
                return true;
            }
            else if (id == R.id.menu_item_settings) {
                // the settings live in the launcher; the game keeps running underneath
                Intent intent = new Intent(activity, com.factory.MainActivity.class);
                intent.putExtra("open_settings", true);
                intent.putExtra("from_game", true);
                activity.startActivity(intent);
            }
            drawerLayout.closeDrawers();
            return true;
        }

        /** The last lines of the game's and the X server's logs. */
        private void showLogs() {
            DebugDialog dialog = new DebugDialog(context);
            File logs = new Paths(activity).getLogs();
            for (String name : new String[]{"game.log", "x11.log"}) {
                String text = com.factory.core.FileUtils.readString(new File(logs, name));
                if (text == null) continue;
                String[] lines = text.split("\n");
                dialog.call("==== "+name);
                for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) dialog.call(lines[i]);
            }
            dialog.show();
        }

        private void applyInputControlsScreenResult() {
            globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
            touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));

            // the profile may have been edited (or imported / duplicated) there: read everything from the files again
            inputControlsManager.loadProfiles();
            int profileId = preferences.getInt(PREF_PROFILE_ID, 0);
            ControlsProfile profile = profileId > 0 ? inputControlsManager.getProfile(profileId) : null;
            if (profile != null) showInputControls(profile);
            else hideInputControls();

            xServer.setRelativeMouseMovement(preferences.getBoolean("relative_mouse_movement", false));
            showControlsWithGamepad = preferences.getBoolean("show_controls_with_gamepad", false);
            inputControlsView.setShowTouchscreenControls(preferences.getBoolean("show_touchscreen_controls", true));
            updateControlsForGamepad(-1);
        }

        private void showInputControls(ControlsProfile profile) {
            inputControlsView.setVisibility(View.VISIBLE);
            inputControlsView.setProfile(profile);

            // one cursor speed for everything: the common one (the profile's own value is kept in sync for the stick-driven cursor)
            profile.setCursorSpeed(globalCursorSpeed);
            touchpadView.setSensitivity(globalCursorSpeed);
            touchpadView.setPointerButtonRightEnabled(false);
            touchpadView.setEnabled(!profile.isDisableMouseInput());
            inputControlsView.invalidate();
        }

        private void hideInputControls() {
            controlsHiddenByGamepad = false;
            inputControlsView.setShowTouchscreenControls(true);
            inputControlsView.setVisibility(View.GONE);
            inputControlsView.setProfile(null);

            touchpadView.setSensitivity(globalCursorSpeed);
            touchpadView.setPointerButtonLeftEnabled(true);
            touchpadView.setPointerButtonRightEnabled(true);
            touchpadView.setEnabled(true);
            inputControlsView.invalidate();
        }

        // ------------------------------------------------------------------ gamepad <-> on-screen controls

        private void setupGamepadAutoControls() {
            showControlsWithGamepad = preferences.getBoolean("show_controls_with_gamepad", false);

            InputManager inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null && gamepadListener == null) {
                gamepadListener = new InputManager.InputDeviceListener() {
                    @Override public void onInputDeviceAdded(int deviceId) { updateControlsForGamepad(-1); }
                    @Override public void onInputDeviceRemoved(int deviceId) { updateControlsForGamepad(deviceId); }
                    @Override public void onInputDeviceChanged(int deviceId) {}
                };
                inputManager.registerInputDeviceListener(gamepadListener, new Handler(Looper.getMainLooper()));
            }
            updateControlsForGamepad(-1);
        }

        private static boolean isGamepadConnected(int ignoredDeviceId) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                if (deviceId == ignoredDeviceId) continue; // the device that is being removed may still be in the list
                if (ExternalController.isGameController(InputDevice.getDevice(deviceId))) return true;
            }
            return false;
        }

        /**
         * Gamepad connected    -> the on-screen controls are hidden (the screen works as a plain touchpad).
         * Gamepad disconnected -> the controls come back.
         * "Show controls with gamepad" in the input controls dialog turns the hiding off.
         */
        private void updateControlsForGamepad(int ignoredDeviceId) {
            boolean hide = isGamepadConnected(ignoredDeviceId) && !showControlsWithGamepad;
            if (hide) {
                if (inputControlsView.getVisibility() == View.VISIBLE && inputControlsView.getProfile() != null) {
                    controlsHiddenByGamepad = true;
                    inputControlsView.setVisibility(View.GONE);

                    touchpadView.setSensitivity(globalCursorSpeed);
                    touchpadView.setPointerButtonLeftEnabled(true);
                    touchpadView.setPointerButtonRightEnabled(true);
                    touchpadView.setEnabled(true);
                }
            }
            else if (controlsHiddenByGamepad) {
                controlsHiddenByGamepad = false;
                ControlsProfile profile = inputControlsView.getProfile();
                if (profile != null) showInputControls(profile);
            }
        }

        /** Mouse wheels, sticks and triggers: our handling first, Termux:X11's for the rest. */
        private final class WindowCallback extends WindowCallbackWrapper {
            WindowCallback(Window.Callback target) {
                super(target);
            }

            @Override
            public boolean dispatchGenericMotionEvent(MotionEvent event) {
                return onGenericMotion(event) || super.dispatchGenericMotionEvent(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // keys that reach the window (the focus is not on Termux:X11's view, e.g. while the drawer is open);
                // with the X server inside the app every key comes here and its keyboard turns it into an X key
                if (onKey(event)) return true;
                if (embedded != null && !drawerLayout.isDrawerOpen(GravityCompat.START) && embedded.getXServer().keyboard.onKeyEvent(event)) return true;
                return super.dispatchKeyEvent(event);
            }
        }
    }
}
