package com.factorio;

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

import java.util.ArrayList;
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
import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.core.AppUtils;
import com.factorio.core.Callback;
import com.factorio.core.EnvVars;
import com.factorio.core.LocaleHelper;
import com.factorio.core.PackagePath;
import com.factorio.core.PreloaderDialog;
import com.factorio.xenvironment.RootFS;
import com.factorio.xenvironment.RootFSInstaller;

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
    private SharedPreferences preferences;
    private Fragment currentFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        if (!PackagePath.isReady()) {
            // the guest files carry a fixed-size path slot (see PackagePath): longer package names cannot be installed
            AppUtils.showToast(this, getString(R.string.package_name_too_long, PackagePath.MAX_PACKAGE_LENGTH));
            finish();
            return;
        }

        Intent startIntent = getIntent();
        boolean openedByShortcutOrApp = startIntent.getBooleanExtra("edit_input_controls", false) ||
            startIntent.getIntExtra("container_id", 0) != 0 ||
            startIntent.getIntExtra("selected_menu_item_id", 0) != 0;
        if (savedInstanceState == null && !openedByShortcutOrApp && tryFastAutoStart()) return;

        // very first start (installing system files): landscape only
        if (savedInstanceState == null && !openedByShortcutOrApp && isFirstStart()) {
            orientationLocked = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        setContentView(R.layout.main_activity);

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
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.menu_item_containers;

            autoStartPending = savedInstanceState == null && intent.getIntExtra("container_id", 0) == 0;
            if (autoStartPending && isFirstStart()) showStartupCover();

            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this);
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                RootFSInstaller.installIfNeeded(this);
            }
            else finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (openFileCallback != null) {
                openFileCallback.call(data.getData());
                openFileCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (currentFragment != null && currentFragment.isVisible() && currentFragment instanceof ContainersFragment) {
            finish();
        }

        showFragment(new ContainersFragment());
    }

    public void setOpenFileCallback(Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.menu_item_add) {
            return super.onOptionsItemSelected(menuItem);
        }
        else {
            if (editInputControls) {
                setResult(RESULT_OK);
                finish();
            }
            else drawerLayout.openDrawer(GravityCompat.START);
            return true;
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.menu_item_containers:
                showFragment(new ContainersFragment());
                break;
            case R.id.menu_item_input_controls:
                showFragment(new InputControlsFragment(selectedProfileId));
                break;
            case R.id.menu_item_settings:
                showFragment(new SettingsFragment());
                break;
        }
        return true;
    }

    // Re-reads containers from disk when the containers list is on screen (used after the default container is created).
    public void reloadContainersList() {
        if (currentFragment instanceof ContainersFragment) showFragment(new ContainersFragment());
    }

    private boolean isFirstStart() {
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) return true;
        return !PreferenceManager.getDefaultSharedPreferences(this).getBoolean("default_container_created", false);
    }

    public void showStartupCover() {
        if (startupCover != null) return;
        startupCover = new View(this);
        startupCover.setBackgroundColor(Color.BLACK);
        startupCover.setClickable(true);
        addContentView(startupCover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    // First container that has NATIVE_EXEC (native game); falls back to the first container.
    private Container findNativeContainer() {
        ArrayList<Container> containers = (new ContainerManager(this)).getContainers();
        for (Container container : containers) {
            String nativeExec = (new EnvVars(container.getEnvVars())).get("NATIVE_EXEC");
            if (nativeExec != null && !nativeExec.isEmpty()) return container;
        }
        return containers.isEmpty() ? null : containers.get(0);
    }

    // Normal app start: if everything is installed, start the game right away and close this screen
    // before any UI is created (no flash of the containers list, nothing underneath when the game exits).
    private boolean tryFastAutoStart() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("auto_start_container", true)) return false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;

        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) return false;

        for (Container container : (new ContainerManager(this)).getContainers()) {
            String nativeExec = (new EnvVars(container.getEnvVars())).get("NATIVE_EXEC");
            if (nativeExec != null && !nativeExec.isEmpty()) {
                Intent intent = new Intent(this, XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                startActivity(intent);
                finish();
                overridePendingTransition(0, 0);
                return true;
            }
        }
        return false;
    }

    // Starts the first native-game container (the one with NATIVE_EXEC) once per fresh app start.
    // Turn it off with the preference "auto_start_container" = false.
    public void autoStartContainer() {
        if (!autoStartPending) {
            hideStartupCover();
            return;
        }
        autoStartPending = false;
        if (!preferences.getBoolean("auto_start_container", true)) {
            hideStartupCover();
            return;
        }

        ContainerManager manager = new ContainerManager(this);
        for (Container container : manager.getContainers()) {
            String nativeExec = (new EnvVars(container.getEnvVars())).get("NATIVE_EXEC");
            if (nativeExec != null && !nativeExec.isEmpty()) {
                Intent intent = new Intent(this, XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                startActivity(intent);
                finish();
                overridePendingTransition(0, 0);
                return;
            }
        }
        hideStartupCover(); // no native container: show the normal containers list
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