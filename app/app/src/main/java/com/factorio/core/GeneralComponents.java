package com.factorio.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.factorio.MainActivity;
import com.factorio.R;
import com.factorio.contentdialog.ContentDialog;
import com.factorio.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public abstract class GeneralComponents {
    public enum InstallMode {DOWNLOAD, FILE}
    // Components are installed through TarCompressorUtils, which replaces the package placeholder (see PackagePath),
    // so archives from /installable_components (and the old upstream ones) work under any package name.
    // Change this URL if you host the components in your own repository.
    private static final String INSTALLABLE_COMPONENTS_URL = "https://raw.githubusercontent.com/brunodev85/winlator/main/installable_components/%s";

    public enum Type {
        TURNIP, ZINK, ADRENOTOOLS_DRIVER;

        private String lowerName() {
            return name().toLowerCase(Locale.ENGLISH);
        }

        private String title() {
            switch (this) {
                case TURNIP:
                    return "Turnip";
                case ZINK:
                    return "Zink";
                case ADRENOTOOLS_DRIVER:
                    return "Adrenotools Driver";
            }

            return "";
        }

        private String assetFolder() {
            return (this == TURNIP || this == ZINK) ? "graphics_driver" : "";
        }

        private File getSource(Context context, String identifier) {
            File componentDir = getComponentDir(this, context);
            if (this == ADRENOTOOLS_DRIVER) return new File(componentDir, identifier);
            return new File(componentDir, lowerName()+"-"+identifier+".tzst");
        }

        public File getDestination(Context context) {
            return RootFS.find(context).getRootDir();
        }

        private InstallMode getInstallMode() {
            return (this == ADRENOTOOLS_DRIVER || this == ZINK) ? InstallMode.FILE : InstallMode.DOWNLOAD; // only Turnip has a download list
        }

        private boolean isVersioned() {
            return this == TURNIP || this == ZINK;
        }
    }

    public static ArrayList<String> getBuiltinComponentNames(Type type) {
        String[] items = new String[0];

        switch (type) {
            case TURNIP:
                items = new String[]{DefaultVersion.TURNIP};
                break;
            case ZINK:
                items = new String[]{DefaultVersion.ZINK};
                break;
            case ADRENOTOOLS_DRIVER:
                items = new String[]{DriverPackages.SYSTEM, DriverPackages.AUTO};
                break;
        }

        return new ArrayList<>(Arrays.asList(items));
    }

    public static File getComponentDir(Type type, Context context) {
        File file = new File(context.getFilesDir(), "/installed_components/"+type.lowerName());
        if (!file.isDirectory()) file.mkdirs();
        return file;
    }

    public static ArrayList<String> getInstalledComponentNames(Type type, Context context) {
        File componentDir = getComponentDir(type, context);
        ArrayList<String> result = new ArrayList<>();

        if (type == Type.ADRENOTOOLS_DRIVER) {
            for (DriverPackages.Info info : DriverPackages.list(context)) result.add(info.id);
            return result;
        }

        String[] names;
        if (componentDir.isDirectory() && (names = componentDir.list()) != null) {
            for (String name : names) {
                if (!name.startsWith(".")) result.add(parseDisplayText(type, name));
            }
        }
        return result;
    }

    public static boolean isBuiltinComponent(Type type, String identifier) {
        for (String builtinComponentName : getBuiltinComponentNames(type)) {
            if (builtinComponentName.equalsIgnoreCase(identifier)) return true;
        }
        return false;
    }

    public static String getDefinitivePath(Type type, Context context, String identifier) {
        if (identifier.isEmpty()) return null;
        // "System" -> null, "Auto" -> best installed package for this GPU, otherwise the package library (see DriverPackages)
        if (type == Type.ADRENOTOOLS_DRIVER) return DriverPackages.resolveLibraryPath(context, identifier);

        return type.getSource(context, identifier).getPath();
    }

    public static void extractFile(Type type, Context context, String identifier, String defaultVersion) {
        extractFile(type, context, identifier, defaultVersion, null);
    }

    public static void extractFile(Type type, Context context, String identifier, String defaultVersion, TarCompressorUtils.OnExtractFileListener onExtractFileListener) {
        File destination = type.getDestination(context);

        if (isBuiltinComponent(type, identifier)) {
            String sourcePath = type.assetFolder()+"/"+type.lowerName()+"-"+identifier+".tzst";
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, sourcePath, destination, onExtractFileListener);
        }
        else {
            File componentDir = getComponentDir(type, context);
            File source = new File(componentDir, type.lowerName()+"-"+identifier+".tzst");
            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, destination, onExtractFileListener);
            if (!success) {
                String sourcePath = type.assetFolder()+"/"+type.lowerName()+"-"+defaultVersion+".tzst";
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, sourcePath, destination, onExtractFileListener);
            }
        }
    }

    private static String parseDisplayText(Type type, String filename) {
        return filename.replace(type.lowerName()+"-", "").replace(".tzst", "");
    }

    private static void downloadComponentFile(final Type type, final String filename, final Spinner spinner, final String defaultItem) {
        final Activity activity = (Activity)spinner.getContext();
        File destination = new File(getComponentDir(type, activity), filename);
        if (destination.isFile()) destination.delete();
        HttpUtils.download(activity, String.format(INSTALLABLE_COMPONENTS_URL, type.lowerName()+"/"+filename), destination, (success) -> {
            if (success) {
                loadSpinner(type, spinner, parseDisplayText(type, filename), defaultItem);
            }
            else AppUtils.showToast(activity, R.string.a_network_error_occurred);
        });
    }

    private static void openFileForInstall(final MainActivity activity, final Type type, final Spinner spinner, final String defaultItem) {
        activity.setOpenFileCallback((uri) -> installFromUri(activity, uri, (result) -> {
            // the type is detected from the file; if it is not the type of this dialog, only tell where it went
            if (typeOf(result.kind) == type) {
                loadSpinner(type, spinner, result.id, defaultItem);
                AppUtils.showToast(activity, describe(activity, result));
            }
            else AppUtils.showToast(activity, describe(activity, result)+" — "+activity.getString(R.string.driver_pick_in_its_list));
        }));

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    public static Type typeOf(DriverPackages.Kind kind) {
        switch (kind) {
            case TURNIP: return Type.TURNIP;
            case ZINK: return Type.ZINK;
            default: return Type.ADRENOTOOLS_DRIVER;
        }
    }

    public static String describe(Context context, DriverPackages.InstallResult result) {
        if (result.kind == DriverPackages.Kind.TURNIP) return context.getString(R.string.driver_installed_as_turnip, result.id);
        if (result.kind == DriverPackages.Kind.ZINK) return context.getString(R.string.driver_installed_as_zink, result.id);
        String gpu = result.info.family == DriverPackages.GpuFamily.UNKNOWN ? "" : " ("+result.info.family.title+")";
        return context.getString(R.string.driver_installed_as_vortek, result.info.name+gpu);
    }

    /** Reads the picked file directly from its Uri (works for Downloads/Telegram/Drive, not only for primary storage). */
    public static void installFromUri(final Activity activity, final Uri uri, final Callback<DriverPackages.InstallResult> callback) {
        if (uri == null) return;
        final PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        new Thread(() -> {
            DriverPackages.InstallResult result = null;
            String error = null;
            try {
                result = DriverPackages.install(activity, uri);
            }
            catch (Exception e) {
                error = e.getMessage();
            }

            final DriverPackages.InstallResult finalResult = result;
            final String finalError = error;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (finalResult != null) callback.call(finalResult);
                else AppUtils.showToast(activity, activity.getString(R.string.driver_install_failed)+(finalError != null ? ": "+finalError : ""));
            });
        }).start();
    }

    private static void showDownloadableListDialog(Type type, final Spinner spinner, final String defaultItem) {
        final Activity activity = (Activity)spinner.getContext();
        final PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        HttpUtils.download(String.format(INSTALLABLE_COMPONENTS_URL, type.lowerName()+"/index.txt"), (content) -> activity.runOnUiThread(() -> {
            preloaderDialog.close();
            if (content != null) {
                if (content.isEmpty()) {
                    AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                    return;
                }
                final String[] filenames = content.split("\n");
                final String[] items = filenames.clone();
                for (int i = 0; i < items.length; i++) {
                    items[i] = type.title()+" "+parseDisplayText(type, items[i]);
                }

                ContentDialog.showSelectionList(activity, R.string.install_component, items, false, (positions) -> {
                    if (!positions.isEmpty()) downloadComponentFile(type, filenames[positions.get(0)], spinner, defaultItem);
                });
            }
            else AppUtils.showToast(activity, R.string.a_network_error_occurred);
        }));
    }

    public static void initViews(final Type type, View toolbox, final Spinner spinner, final String selectedItem, final String defaultItem) {
        final Context context = spinner.getContext();
        toolbox.findViewWithTag("install").setOnClickListener((v) -> {
            InstallMode installMode = type.getInstallMode();
            switch (installMode) {
                case DOWNLOAD:
                    // Turnip can be downloaded OR replaced with any build the user has (.tzst), the type is detected from the file
                    final String[] sources = new String[]{context.getString(R.string.download_from_repository), context.getString(R.string.install_from_file)};
                    ContentDialog.showSelectionList(context, R.string.install_component, sources, false, (positions) -> {
                        if (positions.isEmpty()) return;
                        if (positions.get(0) == 0) showDownloadableListDialog(type, spinner, defaultItem);
                        else openFileForInstall((MainActivity)context, type, spinner, defaultItem);
                    });
                    break;
                case FILE:
                    openFileForInstall((MainActivity)context, type, spinner, defaultItem);
                    break;
            }
        });

        toolbox.findViewWithTag("remove").setOnClickListener((v) -> {
            String identifier = spinner.getSelectedItem().toString();

            if (isBuiltinComponent(type, identifier)) {
                AppUtils.showToast(context, R.string.you_cannot_remove_this_component_version);
                return;
            }

            File source = type.getSource(context, identifier);
            if (source.exists()) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_component_version, () -> {
                    FileUtils.delete(source);
                    loadSpinner(type, spinner, selectedItem, defaultItem);
                });
            }
        });

        loadSpinner(type, spinner, selectedItem, defaultItem);
    }

    private static void loadSpinner(Type type, Spinner spinner, String selectedItem, String defaultItem) {
        ArrayList<String> items = getBuiltinComponentNames(type);
        items.addAll(getInstalledComponentNames(type, spinner.getContext()));

        if (type.isVersioned()) {
            items.sort((o1, o2) -> Integer.compare(GPUHelper.vkMakeVersion(o1), GPUHelper.vkMakeVersion(o2)));
        }

        spinner.setAdapter(new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, items));

        if (selectedItem == null || selectedItem.isEmpty() || !AppUtils.setSpinnerSelectionFromValue(spinner, selectedItem)) {
            AppUtils.setSpinnerSelectionFromValue(spinner, defaultItem);
        }
    }
}