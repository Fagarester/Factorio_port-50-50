package com.factory;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.factorio.renderer.GLRenderer;
import com.factorio.widget.XServerView;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xconnector.XConnectorEpoll;
import com.factorio.xserver.ScreenInfo;
import com.factorio.xserver.XClientConnectionHandler;
import com.factorio.xserver.XClientRequestHandler;
import com.factorio.xserver.XServer;
import com.factory.core.AppUtils;
import com.factory.core.GameSettings;
import com.factory.gladio.GladioServer;
import com.factory.runtime.Paths;
import com.factory.runtime.Session;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The game on the X server inside the app (Gladio's "X server" display, as in r34): the Winlator X server listens on
 * the game's X socket and draws its windows in a GLSurfaceView; Gladio's server copies every finished frame straight
 * into the window's texture. GameScreen adds the side drawer, on-screen controls and touchpad on top, as it does on
 * Termux:X11's activity.
 */
public class XServerGameActivity extends Activity {
    private XServer xServer;
    private XServerView xServerView;
    private XConnectorEpoll connector;

    /** The game's extra environment (the settings' variables), set by GameLauncher before it opens this screen. */
    static volatile Map<String, String> gameEnvironment = Collections.emptyMap();
    /** The game's extra arguments (the benchmark's mod folder), set with gameEnvironment. */
    static volatile List<String> gameArguments = Collections.emptyList();
    /** While this screen exists: the launcher goes back to it instead of Termux:X11's. */
    static volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        running = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        int[] size = GameSettings.getScreenSizeValues(this);
        xServer = new XServer(new ScreenInfo(size[0], size[1]));
        xServerView = new XServerView(this, xServer);
        GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(true);
        xServer.setRenderer(renderer);

        FrameLayout root = new FrameLayout(this);
        root.addView(xServerView);
        setContentView(root);
        hideSystemBars(); // after setContentView: before it the window has no decor view yet

        GladioServer.setXServer(xServer);
        connector = new XConnectorEpoll(UnixSocketConfig.at(new Paths(this).getXSocket()), new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();

        if (Session.INSTANCE.getState() == Session.State.STOPPED) startGame();
    }

    /** After the renderer has its EGL context: the game's GL contexts (made by Gladio's server) must share it. */
    private void startGame() {
        final Map<String, String> env = gameEnvironment;
        final List<String> args = gameArguments;
        new Thread(() -> {
            String failure = null;
            try {
                if (!GladioServer.awaitRenderer(10000)) throw new IllegalStateException("The X server's renderer did not start");
                Session.INSTANCE.start(this, args, env);
            }
            catch (Throwable e) {
                android.util.Log.e("Factory", "The game didn't start", e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            if (failure == null) return;
            final String message = failure;
            runOnUiThread(() -> {
                AppUtils.showToast(this, message);
                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        }, "factory-game-start").start();
    }

    public XServer getXServer() {
        return xServer;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    private void hideSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        xServerView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        xServerView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
        GladioServer.setXServer(null);
        running = false;
        super.onDestroy();
    }
}
