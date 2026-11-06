package com.factory;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.factory.core.AppUtils;
import com.factory.runtime.Drivers;
import com.factory.runtime.Gpu;
import com.factory.runtime.Settings;
import com.factory.widget.TaggedSelectionBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Two blocks, one active (check box), as in r34 with Turnip and Vortek:
 *   [x] Turnip     (Turnip, Freedreno or an installed driver | OpenGL follows it: Zink for Vulkan drivers, OpenGL for Freedreno)
 *   [ ] Gladio     (OpenGL: Gladio = the phone's GLES driver | Display: X11 = Termux:X11, X server = the one in the app)
 *                  with X11 only: [ ] the phone's driver directly, [ ] ANGLE (each alone or both: direct on ANGLE)
 * The active block is saved as the runtime's one driver choice (Settings.driver); the Turnip block keeps its own choice
 * (Settings.turnipBlockDriver), so switching to Gladio and back loses nothing.
 */
public class GraphicsDriverPicker {
    public static final String GL_ZINK = "Zink";
    public static final String GL_OPENGL = "OpenGL";
    public static final String GL_GLADIO = "Gladio";
    private static final String VULKAN_TURNIP = "Turnip";
    private static final String VULKAN_FREEDRENO = "Freedreno";
    private static final String DISPLAY_X11 = "X11";
    private static final String DISPLAY_XSERVER = "X server";

    private final MainActivity activity;
    private final TaggedSelectionBox vulkanBox;
    private final TaggedSelectionBox glBox;
    private final TaggedSelectionBox gladioBox;
    private final TaggedSelectionBox displayBox;
    private final CheckBox cbTurnip, cbGladio, cbAngle, cbDirect;
    private final View blockTurnip, blockGladio;
    private final List<Drivers.Driver> vulkanDrivers = new ArrayList<>();
    private boolean gladioActive;
    private boolean updating = false;

    public GraphicsDriverPicker(MainActivity activity, LinearLayout container) {
        this.activity = activity;
        Context context = container.getContext();
        container.removeAllViews();

        String chosen = Drivers.INSTANCE.chosen(context);
        gladioActive = Drivers.GLADIO.equals(chosen);
        String turnipChoice = gladioActive ? Settings.INSTANCE.turnipBlockDriver(context) : chosen;

        // ---- block 1: Turnip ----
        vulkanBox = new TaggedSelectionBox(context);
        vulkanBox.setLabel("Vulkan");
        loadVulkanItems(context);
        Drivers.Driver chosenVulkan = findVulkan(turnipChoice);
        vulkanBox.setSelectedItem(Drivers.FREEDRENO.equals(turnipChoice) ? VULKAN_FREEDRENO : chosenVulkan != null ? chosenVulkan.getName() : VULKAN_TURNIP);
        vulkanBox.setOnButtonClickListener(this::showVulkanMenu);

        // OpenGL is not chosen on its own: Zink on a Vulkan driver, Freedreno's own OpenGL with Freedreno
        glBox = new TaggedSelectionBox(context);
        glBox.setLabel("OpenGL");
        glBox.setOnButtonClickListener(() -> AppUtils.showToast(activity, R.string.no_settings_yet));
        vulkanBox.setOnItemSelectedListener(item -> updateGlBox());
        updateGlBox();

        // ---- block 2: Gladio ----
        gladioBox = new TaggedSelectionBox(context);
        gladioBox.setLabel("OpenGL");
        gladioBox.setItems(GL_GLADIO);
        gladioBox.setSelectedItem(GL_GLADIO);
        gladioBox.setOnButtonClickListener(this::showGladioOptions);

        displayBox = new TaggedSelectionBox(context);
        displayBox.setLabel(context.getString(R.string.gladio_display));
        displayBox.setItems(DISPLAY_X11, DISPLAY_XSERVER);
        displayBox.setSelectedItem("xserver".equals(Settings.INSTANCE.gladioDisplay(context)) ? DISPLAY_XSERVER : DISPLAY_X11);
        displayBox.setOnButtonClickListener(() -> AppUtils.showToast(activity, R.string.gladio_display_hint));
        displayBox.setOnItemSelectedListener(item -> updateX11Options());

        cbTurnip = new CheckBox(context);
        cbTurnip.setText(R.string.graphics_block_turnip);
        cbGladio = new CheckBox(context);
        cbGladio.setText(R.string.graphics_block_gladio);
        blockTurnip = createBlock(context, cbTurnip, vulkanBox, glBox);
        blockGladio = createBlock(context, cbGladio, gladioBox, displayBox);
        // both only with the X11 display (shown then): the phone's driver directly first, ANGLE below it
        // test: the server in the game's process on the phone's driver through libhybris, for the GPUs other than Adreno
        cbDirect = new CheckBox(context);
        cbDirect.setText(R.string.gladio_direct);
        cbDirect.setChecked(Settings.INSTANCE.gladioDirect(context));
        cbDirect.setTextSize(13);
        ((LinearLayout)blockGladio).addView(cbDirect);
        // ANGLE (experimental): Gladio's server on GLES over Vulkan (the phone's own ANGLE), for GLES drivers that are slow or wrong
        cbAngle = new CheckBox(context);
        boolean angleAvailable = com.factory.gladio.GladioServer.angleAvailable();
        cbAngle.setText(angleAvailable ? context.getString(R.string.gladio_angle) : context.getString(R.string.gladio_angle)+" "+context.getString(R.string.gladio_angle_missing));
        cbAngle.setEnabled(angleAvailable);
        cbAngle.setChecked(angleAvailable && Settings.INSTANCE.gladioAngle(context));
        cbAngle.setTextSize(13);
        ((LinearLayout)blockGladio).addView(cbAngle);
        updateX11Options();
        container.addView(blockTurnip);
        container.addView(blockGladio);

        cbTurnip.setOnCheckedChangeListener((button, checked) -> onBlockChecked(false, checked));
        cbGladio.setOnCheckedChangeListener((button, checked) -> onBlockChecked(true, checked));
        refreshBlocks();
    }

    private static LinearLayout createBlock(Context context, CheckBox checkBox, TaggedSelectionBox left, TaggedSelectionBox right) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(left);
        row.addView(right);
        block.addView(checkBox);
        block.addView(row);
        return block;
    }

    private void onBlockChecked(boolean gladio, boolean checked) {
        if (updating) return;
        if (checked) gladioActive = gladio;
        // unchecking the active block is not possible: one block is always selected
        refreshBlocks();
    }

    private void refreshBlocks() {
        updating = true;
        cbTurnip.setChecked(!gladioActive);
        cbGladio.setChecked(gladioActive);
        blockTurnip.setAlpha(gladioActive ? 0.5f : 1f);
        blockGladio.setAlpha(gladioActive ? 1f : 0.5f);
        updating = false;
    }

    private boolean x11Display() {
        return !DISPLAY_XSERVER.equals(displayBox.getSelectedItem());
    }

    /** The phone's driver directly and ANGLE only work on Termux:X11: shown with the X11 display only. */
    private void updateX11Options() {
        int visibility = x11Display() ? View.VISIBLE : View.GONE;
        cbDirect.setVisibility(visibility);
        cbAngle.setVisibility(visibility);
    }

    private boolean freedrenoSelected() {
        return VULKAN_FREEDRENO.equals(vulkanBox.getSelectedItem());
    }

    private void updateGlBox() {
        String gl = freedrenoSelected() ? GL_OPENGL : GL_ZINK;
        glBox.setItems(gl);
        glBox.setSelectedItem(gl);
    }

    private void loadVulkanItems(Context context) {
        vulkanDrivers.clear();
        List<String> names = new ArrayList<>();
        names.add(VULKAN_TURNIP);
        names.add(VULKAN_FREEDRENO);
        for (Drivers.Driver driver : Drivers.INSTANCE.all(context)) {
            String id = driver.getId();
            if (id.equals(Drivers.ZINK) || id.equals(Drivers.FREEDRENO) || id.equals(Drivers.CPU) || id.equals(Drivers.GLADIO)) continue;
            vulkanDrivers.add(driver);
            names.add(driver.getName());
        }
        vulkanBox.setItems(names.toArray(new String[0]));
    }

    private Drivers.Driver findVulkan(String id) {
        for (Drivers.Driver driver : vulkanDrivers) if (driver.getId().equals(id)) return driver;
        return null;
    }

    private String selectedVulkanId() {
        String name = vulkanBox.getSelectedItem();
        if (VULKAN_FREEDRENO.equals(name)) return Drivers.FREEDRENO;
        for (Drivers.Driver driver : vulkanDrivers) if (driver.getName().equals(name)) return driver.getId();
        return Drivers.ZINK;
    }

    /** "Detected GPU: Adreno (TM) 830" for the settings screen. */
    public static String getDetectedGpuText(Context context) {
        String name = Gpu.INSTANCE.getName();
        return context.getString(R.string.detected_gpu)+": "+(name != null ? name : "?");
    }

    /** Saves the choice; moving to or from the CPU driver also picks the sprites, effects and resolution that suit it. */
    public void save() {
        String turnipId = selectedVulkanId();
        Settings.INSTANCE.setTurnipBlockDriver(activity, turnipId);
        Settings.INSTANCE.setGladioDisplay(activity, DISPLAY_XSERVER.equals(displayBox.getSelectedItem()) ? "xserver" : "x11");
        boolean angleWas = Settings.INSTANCE.gladioAngle(activity);
        // hidden with the X server display: off then (the direct server would otherwise take Termux:X11 instead)
        boolean angle = x11Display() && cbAngle.isChecked();
        Settings.INSTANCE.setGladioAngle(activity, angle);
        // the app's server is loaded once per app process: a change shows after a restart of the app (the direct
        // server lives in the game's process and takes it at the next start)
        boolean direct = x11Display() && cbDirect.isChecked();
        if (gladioActive && angleWas != angle && !direct) AppUtils.showToast(activity, R.string.gladio_angle_restart);
        Settings.INSTANCE.setGladioDirect(activity, direct);
        String id = gladioActive ? Drivers.GLADIO : turnipId;
        boolean wasCpu = Drivers.INSTANCE.cpu(activity);
        Settings.INSTANCE.setDriver(activity, id);
        if (wasCpu != Drivers.CPU.equals(id)) Settings.INSTANCE.applyDriverDefaults(activity);
    }

    // ------------------------------------------------------------------ Vulkan: install / remove a driver

    private void showVulkanMenu() {
        final String selectedId = selectedVulkanId();
        final boolean imported = !selectedId.equals(Drivers.ZINK) && !selectedId.equals(Drivers.FREEDRENO);
        String[] items = imported
            ? new String[]{activity.getString(R.string.install_driver_from_file), activity.getString(R.string.remove_driver, vulkanBox.getSelectedItem())}
            : new String[]{activity.getString(R.string.install_driver_from_file)};
        new AlertDialog.Builder(activity)
            .setTitle("Vulkan")
            .setItems(items, (dialog, which) -> {
                if (which == 0) pickDriverFile();
                else {
                    Drivers.INSTANCE.remove(activity, selectedId);
                    loadVulkanItems(activity);
                    vulkanBox.setSelectedItem(VULKAN_TURNIP);
                    updateGlBox();
                }
            })
            .show();
    }

    /** A Linux (glibc) build of a Mesa Vulkan driver: .tar.gz, .tar.xz, .zip or the library itself. */
    private void pickDriverFile() {
        activity.setOpenFileCallback((uri) -> {
            AppUtils.showToast(activity, R.string.installing_driver);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Drivers.Driver driver = Drivers.INSTANCE.importDriver(activity, uri);
                    activity.runOnUiThread(() -> {
                        loadVulkanItems(activity);
                        vulkanBox.setSelectedItem(driver.getName());
                        updateGlBox();
                        AppUtils.showToast(activity, activity.getString(R.string.driver_installed, driver.getName()));
                    });
                }
                catch (Throwable e) {
                    final String message = e.getMessage() != null ? e.getMessage() : e.toString();
                    activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle(R.string.install_driver_from_file)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
                }
            });
        });
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    /** Gladio: how its frames reach Termux:X11. */
    private void showGladioOptions() {
        final String[] modes = {"auto", "dri3", "shm"};
        String[] labels = {activity.getString(R.string.gladio_present_auto), activity.getString(R.string.gladio_present_dri3), activity.getString(R.string.gladio_present_shm)};
        String current = Settings.INSTANCE.gladioPresent(activity);
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(current)) checked = i;
        new AlertDialog.Builder(activity)
            .setTitle(R.string.gladio_present_title)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                Settings.INSTANCE.setGladioPresent(activity, modes[which]);
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
