package com.factorio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDriverPicker;
import com.factorio.container.GraphicsDrivers;
import com.factorio.contentdialog.AddEnvVarDialog;
import com.factorio.contentdialog.AudioDriverConfigDialog;
import com.factorio.contentdialog.ContentDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.EnvVars;
import com.factorio.core.LocaleHelper;
import com.factorio.core.StringUtils;
import com.factorio.widget.ColorPickerView;
import com.factorio.widget.EnvVarsView;
import com.factorio.widget.LogView;
import com.factorio.widget.SeekBar;
import com.factorio.xenvironment.RootFSInstaller;

public class SettingsFragment extends Fragment {
    public static final byte APP_THEME_LIGHT = 0;
    public static final byte APP_THEME_DARK = 1;
    public static final byte APP_THEME_DARK1 = 2;
    public static final byte APP_THEME_DARK2 = 3;
    public static final byte APP_THEME_DARK3 = 4;
    public static final byte APP_THEME_DARK4 = 5;
    public static final byte APP_THEME_DARK5 = 6;
    public static final byte APP_THEME_DARK6 = 7;
    public static final byte APP_THEME_DARK7 = 8;
    public static final byte APP_THEME_DARK8 = 9;

    // radio buttons in the order of the APP_THEME_* constants above
    private static final int[] THEME_BUTTON_IDS = {R.id.RBLight, R.id.RBDark, R.id.RBDark1, R.id.RBDark2, R.id.RBDark3,
                                                   R.id.RBDark4, R.id.RBDark5, R.id.RBDark6, R.id.RBDark7, R.id.RBDark8};
    private SharedPreferences preferences;
    private Container container; // the single built-in container (null until the system files are installed)

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.settings);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup root, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_fragment, root, false);
        final Context context = getContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final MainActivity activity = (MainActivity)getActivity();

        // ---- container settings (screen size, drivers, environment variables) ----
        container = (new ContainerManager(context)).getMainContainer();
        final View flContainerSettings = view.findViewById(R.id.FLContainerSettings);
        GraphicsDriverPicker graphicsDriverPicker = null;
        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        final View vAudioDriverConfig = view.findViewById(R.id.BTAudioDriverConfig);
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        if (container != null) {
            loadScreenSizeSpinner(view, container.getScreenSize());
            graphicsDriverPicker = new GraphicsDriverPicker(view.findViewById(R.id.LLGraphicsDriver), container.getGraphicsDriver(), container.getGraphicsDriverConfig());
            ((TextView)view.findViewById(R.id.TVDetectedGpu)).setText(GraphicsDriverPicker.getDetectedGpuText(context));
            AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, container.getAudioDriver());
            vAudioDriverConfig.setTag(container.getAudioDriverConfig());
            vAudioDriverConfig.setOnClickListener((v) -> (new AudioDriverConfigDialog(v)).show());
            envVarsView.setEnvVars(new EnvVars(container.getEnvVars()));
            view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        }
        else flContainerSettings.setVisibility(View.GONE);
        final GraphicsDriverPicker finalGraphicsDriverPicker = graphicsDriverPicker;

        final RadioGroup rgAppTheme = view.findViewById(R.id.RGAppTheme);
        final int oldTheme = Math.max(0, Math.min(THEME_BUTTON_IDS.length - 1, preferences.getInt("app_theme", APP_THEME_DARK6)));
        rgAppTheme.check(THEME_BUTTON_IDS[oldTheme]);

        final CheckBox cbMoveCursorToTouchpoint = view.findViewById(R.id.CBMoveCursorToTouchpoint);
        cbMoveCursorToTouchpoint.setChecked(preferences.getBoolean("move_cursor_to_touchpoint", false));

        final CheckBox cbCapturePointerOnExternalMouse = view.findViewById(R.id.CBCapturePointerOnExternalMouse);
        cbCapturePointerOnExternalMouse.setChecked(preferences.getBoolean("capture_pointer_on_external_mouse", true));

        final CheckBox cbEnableDebugLogs = view.findViewById(R.id.CBEnableDebugLogs);
        cbEnableDebugLogs.setChecked(preferences.getBoolean("enable_debug_logs", false));

        final CheckBox cbSaveLogsToFile = view.findViewById(R.id.CBSaveLogsToFile);
        cbSaveLogsToFile.setChecked(preferences.getBoolean("save_logs_to_file", false));

        final EditText etLogFile = view.findViewById(R.id.ETLogFile);
        final String defaultLogPath = LogView.getLogFile().getPath();
        etLogFile.setText(preferences.getString("log_file", defaultLogPath));
        etLogFile.setVisibility(cbSaveLogsToFile.isChecked() ? View.VISIBLE : View.GONE);
        cbSaveLogsToFile.setOnCheckedChangeListener((buttonView, isChecked) -> etLogFile.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        final SeekBar sbCursorSpeed = view.findViewById(R.id.SBCursorSpeed);
        sbCursorSpeed.setValue(preferences.getFloat("cursor_speed", 1.0f) * 100);

        final SeekBar sbCursorSize = view.findViewById(R.id.SBCursorSize);
        sbCursorSize.setValue(preferences.getFloat("cursor_scale", 1.0f) * 100);

        final ColorPickerView cpvCursorColor = view.findViewById(R.id.CPVCursorColor);
        cpvCursorColor.setPalette(0xffffff, 0x000000, 0x651fff, 0xffea00, 0xff9100, 0xf50057, 0x00b0ff, 0x1de9b6);
        cpvCursorColor.setColor(preferences.getInt("cursor_color", 0xffffff));

        final Spinner sLanguage = view.findViewById(R.id.SLanguage);
        sLanguage.setSelection(LocaleHelper.getLocaleIndex(context));
        final int oldLCIndex = sLanguage.getSelectedItemPosition();

        view.findViewById(R.id.BTReinstallSystemFiles).setOnClickListener((v) -> {
            ContentDialog.confirm(context, R.string.do_you_want_to_reinstall_system_files, () -> RootFSInstaller.install((MainActivity)getActivity()));
        });

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            if (container != null) {
                container.setScreenSize(getScreenSize(view));
                container.setEnvVars(envVarsView.getEnvVars());
                container.setGraphicsDriver(finalGraphicsDriverPicker.getGraphicsDriver());
                container.setGraphicsDriverConfig(finalGraphicsDriverPicker.getGraphicsDriverConfig());
                container.setAudioDriver(StringUtils.parseIdentifier(sAudioDriver.getSelectedItem()));
                container.setAudioDriverConfig(vAudioDriverConfig.getTag().toString());
                container.saveData();
            }

            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("move_cursor_to_touchpoint", cbMoveCursorToTouchpoint.isChecked());
            editor.putBoolean("capture_pointer_on_external_mouse", cbCapturePointerOnExternalMouse.isChecked());
            editor.putFloat("cursor_speed", sbCursorSpeed.getValue() / 100.0f);
            editor.putFloat("cursor_scale", sbCursorSize.getValue() / 100.0f);
            editor.putInt("cursor_color", cpvCursorColor.getColor());
            editor.putBoolean("enable_debug_logs", cbEnableDebugLogs.isChecked());
            editor.putBoolean("save_logs_to_file", cbSaveLogsToFile.isChecked());

            int newTheme = oldTheme;
            int checkedId = rgAppTheme.getCheckedRadioButtonId();
            for (int i = 0; i < THEME_BUTTON_IDS.length; i++) {
                if (THEME_BUTTON_IDS[i] == checkedId) {
                    newTheme = i;
                    break;
                }
            }
            editor.putInt("app_theme", newTheme);

            int newLCIndex = sLanguage.getSelectedItemPosition();
            editor.putInt("lc_index", newLCIndex);
            boolean restartApp = oldLCIndex != newLCIndex || oldTheme != newTheme;

            String logPath = etLogFile.getText().toString().trim();
            if (!logPath.equals(defaultLogPath) && !logPath.isEmpty()) {
                editor.putString("log_file", logPath);
            }
            else editor.remove("log_file");

            if (editor.commit()) {
                if (restartApp) AppUtils.restartActivity(activity); // language / theme
                else if (activity.isOpenedFromGame()) {
                    // the game is running with the old settings
                    ContentDialog.confirm(context, R.string.the_settings_have_been_changed_do_you_want_to_restart_the_app, () -> AppUtils.restartApplication(context));
                }
                else activity.startGame();
            }
        });

        return view;
    }

    private static String getScreenSize(View view) {
        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (sScreenSize.getSelectedItemPosition() == 0) {
            value = Container.DEFAULT_SCREEN_SIZE;
            String strWidth = ((EditText)view.findViewById(R.id.ETScreenWidth)).getText().toString().trim();
            String strHeight = ((EditText)view.findViewById(R.id.ETScreenHeight)).getText().toString().trim();
            if (strWidth.matches("[0-9]+") && strHeight.matches("[0-9]+")) {
                int width = Integer.parseInt(strWidth);
                int height = Integer.parseInt(strHeight);
                if ((width % 2) == 0 && (height % 2) == 0) return width+"x"+height;
            }
        }
        return StringUtils.parseIdentifier(value);
    }

    private static void loadScreenSizeSpinner(View view, String selectedValue) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                llCustomScreenSize.setVisibility(sScreenSize.getSelectedItemPosition() == 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            sScreenSize.setSelection(0);
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }
}
