package com.factory;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.factory.core.AppUtils;
import com.factory.core.Callback;
import com.factory.core.LocaleHelper;
import com.factory.core.GameSettings;
import com.factory.core.PreloaderDialog;
import com.factory.runtime.Session;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final boolean DEBUG_MODE = false; // FIXME change to false
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private View startupCover; // black overlay: hides the containers list during the very first start
    private boolean orientationLocked = false; // landscape is forced only during the very first start
    private boolean autoStartPending = false; // true only on a fresh app start (not on rotation / returning from the game)
    private int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private Runnable permissionCallback;
    private SharedPreferences preferences;
    private Fragment currentFragment;
    private boolean openedFromGame = false; // Settings were opened from the in-game side drawer (the game is running underneath)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        Intent startIntent = getIntent();
        // screen.log (while a game runs): the launcher opening over the game screen would close or cover it
        com.factory.runtime.ScreenLog.INSTANCE.add("launcher opened" + (savedInstanceState != null ? " (recreated)" : "") +
            ", extras " + (startIntent.getExtras() != null ? startIntent.getExtras().keySet() : "none"));
        openedFromGame = startIntent.getBooleanExtra("from_game", false) || Session.INSTANCE.getState() != Session.State.STOPPED;
        boolean openedByShortcutOrApp = startIntent.getBooleanExtra("edit_input_controls", false) ||
            startIntent.getBooleanExtra("open_settings", false) ||
            startIntent.hasExtra("game_failed") ||
            startIntent.getBooleanExtra("benchmark_done", false) ||
            startIntent.getIntExtra("selected_menu_item_id", 0) != 0;
        if (savedInstanceState == null && !openedByShortcutOrApp && tryFastAutoStart()) return;

        // very first start (installing system files): landscape only
        if (savedInstanceState == null && !openedByShortcutOrApp && isFirstStart()) {
            orientationLocked = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        setContentView(R.layout.launcher_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.menu_item_input_controls));
            navigationView.setCheckedItem(R.id.menu_item_input_controls);
        }
        else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.menu_item_settings;

            autoStartPending = savedInstanceState == null && !openedByShortcutOrApp; // opened for settings: do not start the game
            // the settings stay hidden while the game is being found or installed (first start, or no game yet)
            if (autoStartPending && (isFirstStart() || !GameLauncher.isGameInstalled(this))) showStartupCover();

            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            if (!requestAppPermissions()) FirstRun.runIfNeeded(this);
            if (intent.hasExtra("game_failed")) GameLauncher.showGameFailed(this, intent.getIntExtra("game_failed", 0), intent.getBooleanExtra("game_no_window", false));
            if (intent.getBooleanExtra("benchmark_done", false)) showBenchmarkReport();
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            // not needed: refused, the game is picked with the system's file picker
            Runnable then = permissionCallback;
            permissionCallback = null;
            if (then != null) then.run();
            else FirstRun.runIfNeeded(this);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // a file (OPEN_FILE) or a folder (OPEN_DIRECTORY, the game folder on the first start): its uri to the callback
        if ((requestCode == MainActivity.OPEN_FILE_REQUEST_CODE || requestCode == MainActivity.OPEN_DIRECTORY_REQUEST_CODE) && resultCode == Activity.RESULT_OK && data != null) {
            if (openFileCallback != null) {
                openFileCallback.call(data.getData());
                openFileCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        // file manager: the back gesture goes up one folder, it leaves only from a root ("Game" / "Download")
        if (currentFragment instanceof FileManagerFragment && ((FileManagerFragment)currentFragment).handleBack()) return;

        if (editInputControls) {
            finishInputControlsEditing();
            return;
        }

        if (currentFragment instanceof SettingsFragment) {
            finish();
            return;
        }

        showFragment(new SettingsFragment());
    }

    public boolean isOpenedFromGame() {
        return openedFromGame;
    }

    public void setOpenFileCallback(Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    /** Reading Download (the game folder, the search): Android 13+ names it "photos, videos, music and audio". */
    public boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asked once, on the first start, and not needed: without it the game search is skipped
     * and the player picks the archive with the system's file picker (a game folder needs it, GameLauncher asks again).
     */
    private boolean requestAppPermissions() {
        if (preferences.getBoolean("storage_permission_asked", false)) return false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            hasStoragePermission()) return false;
        preferences.edit().putBoolean("storage_permission_asked", true).apply();
        requestStoragePermission(null);
        return true;
    }

    /** Writing into Download (the demo's archive). */
    public boolean canWriteDownload() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Asks for the storage permission; afterwards [then] runs (null: the first start goes on). */
    public void requestStoragePermission(Runnable then) {
        permissionCallback = then;
        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
    }

    /** Whether Android still shows its permission question (not after the second "Deny": then only its settings). */
    public boolean canAskStoragePermission() {
        return !preferences.getBoolean("storage_permission_asked", false) ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (editInputControls) finishInputControlsEditing();
        else drawerLayout.openDrawer(GravityCompat.START);
        return true;
    }

    /** Leaves the input controls screen and tells the game which profile is selected, so that it applies it. */
    private void finishInputControlsEditing() {
        Intent result = new Intent();
        int profileId = currentFragment instanceof InputControlsFragment ? ((InputControlsFragment)currentFragment).getCurrentProfileId() : 0;
        result.putExtra("selected_profile_id", profileId);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putInt(GameScreen.PREF_PROFILE_ID, profileId)
            .putBoolean(GameScreen.PREF_CONTROLS_CHANGED, true)
            .apply();
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        int id = item.getItemId();
        if (id == R.id.menu_item_play) startGame();
        else if (id == R.id.menu_item_input_controls) showFragment(new InputControlsFragment(selectedProfileId, editInputControls && openedFromGame));
        else if (id == R.id.menu_item_files) showFragment(new FileManagerFragment());
        else if (id == R.id.menu_item_settings) showFragment(new SettingsFragment());
        return true;
    }

    // Re-reads the container from disk when the settings are on screen (used after the default container is created).
    public void reloadSettings() {
        if (currentFragment instanceof SettingsFragment) showFragment(new SettingsFragment());
    }

    private boolean isFirstStart() {
        return !FirstRun.isDone(this);
    }

    public void showStartupCover() {
        if (startupCover != null) return;
        startupCover = new View(this);
        // like the loading screens: the moments between them don't flash
        startupCover.setBackgroundResource(R.drawable.loading_background);
        startupCover.setClickable(true);
        addContentView(startupCover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Finding, copying or unpacking the game: landscape like the loading screen and the game. */
    public void lockLandscape() {
        orientationLocked = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    public void unlockOrientation() {
        orientationLocked = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR); // back to the normal (manifest) behaviour
    }

    public void hideStartupCover() {
        if (orientationLocked) {
            orientationLocked = false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR); // back to the normal (manifest) behaviour
        }
        if (startupCover == null) return;
        ViewGroup parent = (ViewGroup)startupCover.getParent();
        if (parent != null) parent.removeView(startupCover);
        startupCover = null;
    }

    /** After a benchmark run: its report (also saved to Download/Factory-benchmark). */
    private void showBenchmarkReport() {
        new Thread(() -> {
            final String report = Benchmark.finish(this);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                android.widget.TextView text = new android.widget.TextView(this);
                text.setText(report != null ? report : getString(R.string.benchmark_no_results));
                text.setTypeface(android.graphics.Typeface.MONOSPACE);
                text.setTextSize(10);
                text.setTextIsSelectable(true);
                int pad = (int)(12 * getResources().getDisplayMetrics().density);
                text.setPadding(pad, pad, pad, pad);
                android.widget.HorizontalScrollView wide = new android.widget.HorizontalScrollView(this);
                wide.addView(text);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(wide);
                java.util.regex.Matcher score = java.util.regex.Pattern.compile("^SCORE: (\\d+)").matcher(report != null ? report : "");
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(score.find() ? getString(R.string.benchmark_score_title, score.group(1)) : getString(R.string.benchmark))
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        }, "factory-benchmark-report").start();
    }

    // Starts the game (asks for the game folder or archive first if there is no game yet) and closes this screen.
    public void startGame() {
        if (!FirstRun.isDone(this)) return;
        GameLauncher.start(this);
    }

    // Normal app start: if everything is installed, start the game right away and close this screen
    // before any UI is created (nothing flashes on the screen, nothing underneath when the game exits).
    private boolean tryFastAutoStart() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("auto_start_container", true)) return false;

        if (!FirstRun.isDone(this) || !GameLauncher.isGameInstalled(this)) return false;
        if (Session.INSTANCE.getState() != Session.State.STOPPED) {
            // the game is already running (e.g. the launcher icon was tapped again): back to it
            GameLauncher.showGame(this);
            finish();
            return true;
        }

        startGame();
        return true;
    }

    // Starts the game once per fresh app start (called when the system files and the container are ready).
    // Turn it off with the preference "auto_start_container" = false.
    public void autoStartContainer() {
        if (!autoStartPending) {
            hideStartupCover();
            return;
        }
        autoStartPending = false;
        if (!preferences.getBoolean("auto_start_container", true) || !FirstRun.isDone(this)) {
            hideStartupCover();
            return;
        }

        startGame();
    }

    public void showFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
            .replace(R.id.FLFragmentContainer, fragment)
            .commit();

        drawerLayout.closeDrawer(GravityCompat.START);
        currentFragment = fragment;
    }
}