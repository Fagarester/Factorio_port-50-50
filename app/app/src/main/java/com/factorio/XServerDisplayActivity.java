package com.factorio;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.factorio.alsaserver.ALSAClient;
import com.factorio.container.AudioDrivers;
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDrivers;
import com.factorio.contentdialog.AudioDriverConfigDialog;
import com.factorio.contentdialog.ContentDialog;
import com.factorio.contentdialog.DebugDialog;
import com.factorio.contentdialog.ScreenEffectDialog;
import com.factorio.contentdialog.TurnipConfigDialog;
import com.factorio.contentdialog.VirGLConfigDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.DefaultVersion;
import com.factorio.core.EnvVars;
import com.factorio.core.FileUtils;
import com.factorio.core.GeneralComponents;
import com.factorio.core.KeyValueSet;
import com.factorio.core.LocaleHelper;
import com.factorio.core.PreloaderDialog;
import com.factorio.core.ProcessHelper;
import com.factorio.core.TarCompressorUtils;
import com.factorio.inputcontrols.ControlsProfile;
import com.factorio.inputcontrols.ExternalController;
import com.factorio.inputcontrols.InputControlsManager;
import com.factorio.math.Mathf;
import com.factorio.renderer.GLRenderer;
import com.factorio.widget.InputControlsView;
import com.factorio.widget.MagnifierView;
import com.factorio.widget.TouchpadView;
import com.factorio.widget.XServerView;
import com.factorio.winhandler.GamepadHandler;
import com.factorio.winhandler.WinHandler;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xenvironment.RootFS;
import com.factorio.xenvironment.XEnvironment;
import com.factorio.xenvironment.components.ALSAServerComponent;
import com.factorio.xenvironment.components.GuestProgramLauncherComponent;
import com.factorio.xenvironment.components.NetworkInfoUpdateComponent;
import com.factorio.xenvironment.components.PulseAudioComponent;
import com.factorio.xenvironment.components.SysVSharedMemoryComponent;
import com.factorio.xenvironment.components.VirGLRendererComponent;
import com.factorio.xenvironment.components.VortekRendererComponent;
import com.factorio.xenvironment.components.XServerComponent;
import com.factorio.xserver.ScreenInfo;
import com.factorio.xserver.Window;
import com.factorio.xserver.WindowManager;
import com.factorio.xserver.XServer;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private Runnable editInputControlsCallback;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] graphicsDriverConfig;
    private KeyValueSet audioDriverConfig;
    private final EnvVars envVars = new EnvVars();
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private String screenEffectProfile;
    private static boolean running = false;

    // on-screen controls follow the gamepad: connected -> hidden, disconnected -> shown again
    private InputManager.InputDeviceListener gamepadListener;
    private boolean controlsHiddenByGamepad = false;
    private boolean showControlsWithGamepad = false; // user opt-in (input controls dialog)

    // true while the game screen exists (MainActivity uses it to know that settings changes need a restart)
    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        running = true;
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_debug_logs", false);
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);
        findViewById(R.id.LLExit).setOnClickListener((v) -> exit()); // "Exit" is pinned to the bottom of the drawer

        rootFS = RootFS.find(this);

        ContainerManager containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
        if (container == null) container = containerManager.getMainContainer();
        if (container == null) {
            finish();
            return;
        }
        containerManager.activateContainer(container);

        if (!checkNativeGame()) return;

        String graphicsDriver = container.getGraphicsDriver();
        audioDriver = container.getAudioDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
        screenInfo = new ScreenInfo(container.getScreenSize());

        this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
        this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        final boolean[] flags = {false};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {}

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;
                }
            }

            @Override
            public void onUnmapWindow(Window window) {}
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            setupSystemFiles();
            extractGraphicsDriverFiles();
            setupXEnvironment();
        });
    }


    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && capturePointerOnExternalMouse) touchpadView.requestPointerCapture();
    }


    @Override
    public void onResume() {
        super.onResume();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }


    @Override
    protected void onDestroy() {
        running = false;
        if (gamepadListener != null) {
            InputManager inputManager = (InputManager)getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) inputManager.unregisterInputDeviceListener(gamepadListener);
            gamepadListener = null;
        }
        winHandler.gamepadHandler.stopBridge();
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        super.onDestroy();
    }


    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }


    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_settings:
                // unified settings (container + app) live in MainActivity; the game keeps running underneath
                Intent settingsIntent = new Intent(this, MainActivity.class);
                settingsIntent.putExtra("open_settings", true);
                settingsIntent.putExtra("from_game", true);
                startActivity(settingsIntent);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }


    public SharedPreferences getPreferences() {
        return preferences;
    }


    private void exit() {
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        // "Exit" closes the whole app. It must NOT restart the app, otherwise MainActivity starts again
        // and the auto-start launches the game again (endless loop).
        finishAndRemoveTask();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    private void setupSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
            container.putExtra("graphicsDriver", null); // forces the graphics driver files to be extracted again
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            container.saveData();
        }
    }


    // Native game containers (NATIVE_EXEC set): the game must be already copied into the rootfs
    // or be present in NATIVE_SRC (phone storage). Otherwise show an error and close.
    private boolean checkNativeGame() {
        if (container == null) return true;
        EnvVars containerVars = new EnvVars(container.getEnvVars());

        String nativeSrc = containerVars.get("NATIVE_SRC");
        boolean alreadyCopied = false;
        if (nativeSrc != null && !nativeSrc.isEmpty()) {
            File dstDir = new File(rootFS.getRootDir(), RootFS.HOME_PATH+"/"+(new File(nativeSrc)).getName());
            alreadyCopied = (new File(dstDir, ".copy_done")).exists();
        }
        if (alreadyCopied) return true;

        if (nativeSrc != null && !nativeSrc.isEmpty() && (new File(nativeSrc)).isDirectory()) {
            android.widget.Toast.makeText(this, "Копирование игры, подождите...", android.widget.Toast.LENGTH_LONG).show();
            return true;
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("Игра не найдена")
            .setMessage("Положите папку с игрой сюда и запустите снова:\n"+(nativeSrc != null ? nativeSrc : "(NATIVE_SRC не задан)"))
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
            .show();
        return false;
    }


    // The native game (Factorio) cannot use the Windows xinput/dinput path. Instead the Selkies
    // input interposer (LD_PRELOAD) gives the game a virtual "Xbox 360 pad" and we feed it from GamepadHandler.
    // Needs assets/input_interposer/{selkies_input_interposer.so, libudev.so.1} built for aarch64 glibc.
    // Disable with container env var NATIVE_GAMEPAD=0.
    private void setupNativeGamepadBridge() {
        try {
            String nativeExec = envVars.get("NATIVE_EXEC");
            if (nativeExec == null || nativeExec.isEmpty() || "0".equals(envVars.get("NATIVE_GAMEPAD"))) return;

            String[] assets = getAssets().list("input_interposer");
            boolean hasLibs = false;
            if (assets != null) {
                boolean a = false, b = false;
                for (String name : assets) {
                    if (name.equals("selkies_input_interposer.so")) a = true;
                    else if (name.equals("libudev.so.1")) b = true;
                }
                hasLibs = a && b;
            }
            if (!hasLibs) {
                Log.w("NativeGamepad", "assets/input_interposer/*.so not found, gamepad bridge disabled");
                return;
            }

            File rootDir = rootFS.getRootDir();
            File libDir = new File(rootDir, "opt/input_interposer");
            FileUtils.copy(this, "input_interposer/selkies_input_interposer.so", new File(libDir, "selkies_input_interposer.so"));
            FileUtils.copy(this, "input_interposer/libudev.so.1", new File(libDir, "libudev.so.1"));

            File socketDir = new File(rootDir, "tmp/selkies-js");
            FileUtils.delete(socketDir);
            socketDir.mkdirs();

            envVars.put("NATIVE_PRELOAD", libDir.getPath()+"/selkies_input_interposer.so:"+libDir.getPath()+"/libudev.so.1");
            envVars.put("SELKIES_JS_SOCKET_PATH", socketDir.getPath());
            envVars.put("SELKIES_REAL_LIBUDEV", "none");

            winHandler.gamepadHandler.startBridge(socketDir);
        }
        catch (Exception e) {
            Log.e("NativeGamepad", "bridge setup failed: "+e);
        }
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        envVars.putAll(container.getEnvVars());

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);

        setupNativeGamepadBridge();
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        // The RTS controls profile is enabled automatically on start.
        if (isNativeGameContainer()) {
            ControlsProfile defaultProfile = inputControlsManager.getProfile(DEFAULT_NATIVE_CONTROLS_PROFILE_ID);
            if (defaultProfile != null) showInputControls(defaultProfile);
        }
        setupGamepadAutoControls();

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowControlsWithGamepad = dialog.findViewById(R.id.CBShowControlsWithGamepad);
        cbShowControlsWithGamepad.setChecked(showControlsWithGamepad);

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            showControlsWithGamepad = cbShowControlsWithGamepad.isChecked();
            preferences.edit().putBoolean("show_controls_with_gamepad", showControlsWithGamepad).apply();
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
            updateControlsForGamepad(-1);
        });

        dialog.show();
    }

    // id of the profile that is switched on automatically for native games:
    // 1 = RTS (assets/inputcontrols/profiles/controls-1.icp), 2 = Template (12 buttons), 3 = Virtual Gamepad, 4 = FPS
    private static final int DEFAULT_NATIVE_CONTROLS_PROFILE_ID = 1;

    private boolean isNativeGameContainer() {
        return container != null; // every container of this app runs the native game
    }


    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

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

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    // ------------------------------------------------------------------ gamepad <-> on-screen controls

    private void setupGamepadAutoControls() {
        showControlsWithGamepad = preferences.getBoolean("show_controls_with_gamepad", false);

        InputManager inputManager = (InputManager)getSystemService(Context.INPUT_SERVICE);
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
        if (inputControlsView == null || touchpadView == null) return;

        boolean hide = isGamepadConnected(ignoredDeviceId) && !showControlsWithGamepad;
        if (hide) {
            if (inputControlsView.getVisibility() == View.VISIBLE && inputControlsView.getProfile() != null) {
                controlsHiddenByGamepad = true;
                inputControlsView.setVisibility(View.GONE);

                touchpadView.setSensitivity(globalCursorSpeed);
                touchpadView.setPointerButtonLeftEnabled(true);
                touchpadView.setPointerButtonRightEnabled(true);
                touchpadView.setEnabled(true);
                xServerView.getRenderer().setCursorVisible(true);
            }
        }
        else if (controlsHiddenByGamepad) {
            controlsHiddenByGamepad = false;
            ControlsProfile profile = inputControlsView.getProfile();
            if (profile != null) showInputControls(profile);
        }
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        boolean changed = !cacheId.equals(container.getExtra("graphicsDriver"));
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            container.putExtra("graphicsDriver", cacheId);
            container.saveData();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || MainActivity.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
            case GraphicsDrivers.OPENGL:
                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/opengl-"+DefaultVersion.OPENGL+".tzst", rootDir);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Native game with the gamepad bridge: pad buttons go to the game first, not to cursor/keyboard bindings
        if (winHandler.gamepadHandler.isBridgeActive() && event.getKeyCode() != KeyEvent.KEYCODE_BACK && ExternalController.isGameController(event.getDevice())) {
            winHandler.onKeyEvent(event);
            return true; // never let pad buttons navigate the Android UI while a native game runs
        }
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }
}
