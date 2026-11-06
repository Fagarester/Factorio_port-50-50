package com.factory;

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

import com.factory.contentdialog.AddEnvVarDialog;
import com.factory.contentdialog.ContentDialog;
import com.factory.core.AppUtils;
import com.factory.core.EnvVars;
import com.factory.core.GameSettings;
import com.factory.core.LocaleHelper;
import com.factory.core.ScreenResolution;
import com.factory.core.StringUtils;
import com.factory.widget.ColorPickerView;
import com.factory.widget.EnvVarsView;
import com.factory.widget.LogView;
import com.factory.widget.SeekBar;

public class SettingsFragment extends Fragment {
    private SharedPreferences preferences;

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

        // ---- game settings (screen size, environment variables) ----
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        loadScreenSizeSpinner(view, GameSettings.getScreenSize(context));
        setupResolutionButtons(view, context);
        envVarsView.setEnvVars(new EnvVars(GameSettings.getEnvVars(context)));
        final GraphicsDriverPicker graphicsDriverPicker = new GraphicsDriverPicker(activity, view.findViewById(R.id.LLGraphicsDriver));
        ((TextView)view.findViewById(R.id.TVDetectedGpu)).setText(GraphicsDriverPicker.getDetectedGpuText(context));
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());

        // Graphics preset: sprites + effects for every driver, saved at once (applied on the next game start)
        final Spinner sGraphicsPreset = view.findViewById(R.id.SGraphicsPreset);
        final TextView tvGraphicsPresetHint = view.findViewById(R.id.TVGraphicsPresetHint);
        final String[] presetHints = getResources().getStringArray(R.array.graphics_preset_hints);
        int presetIndex = com.factory.runtime.GraphicsPresets.INSTANCE.getIDS().indexOf(com.factory.runtime.Settings.INSTANCE.graphicsPreset(context));
        sGraphicsPreset.setSelection(Math.max(presetIndex, 0));
        sGraphicsPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                com.factory.runtime.Settings.INSTANCE.setGraphicsPreset(context, com.factory.runtime.GraphicsPresets.INSTANCE.getIDS().get(position));
                tvGraphicsPresetHint.setText(position < presetHints.length ? presetHints[position] : "");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Benchmark: the settings on screen (driver, resolution, variables) are saved, then the game starts with
        // the vanilla menu scenes and the frame timer (Benchmark)
        view.findViewById(R.id.BTBenchmark).setOnClickListener((v) -> ContentDialog.confirm(context, R.string.benchmark_confirm, () -> {
            GameSettings.setScreenSize(context, getScreenSize(view));
            GameSettings.setEnvVars(context, envVarsView.getEnvVars());
            graphicsDriverPicker.save();
            Benchmark.request(context);
            if (activity.isOpenedFromGame() && com.factory.runtime.Session.INSTANCE.getState() != com.factory.runtime.Session.State.STOPPED) {
                GameScreen.restart(activity);
                activity.finish();
            }
            else activity.startGame();
        }));

        // ---- bottom section "variables and logging": folded until the header is tapped ----
        final View llAdvancedBody = view.findViewById(R.id.LLAdvancedBody);
        final TextView tvAdvancedArrow = view.findViewById(R.id.TVAdvancedArrow);
        view.findViewById(R.id.LLAdvancedHeader).setOnClickListener((v) -> {
            boolean expand = llAdvancedBody.getVisibility() != View.VISIBLE;
            android.transition.TransitionManager.beginDelayedTransition((ViewGroup)llAdvancedBody.getParent());
            llAdvancedBody.setVisibility(expand ? View.VISIBLE : View.GONE);
            tvAdvancedArrow.setText(expand ? "\u25B2" : "\u25BC");
        });

        final CheckBox cbEnableDebugLogs = view.findViewById(R.id.CBEnableDebugLogs);
        cbEnableDebugLogs.setChecked(preferences.getBoolean("enable_debug_logs", false));

        final CheckBox cbSaveLogsToFile = view.findViewById(R.id.CBSaveLogsToFile);
        cbSaveLogsToFile.setChecked(preferences.getBoolean("save_logs_to_file", false));

        final EditText etLogFile = view.findViewById(R.id.ETLogFile);
        final String defaultLogPath = LogView.getLogFile().getPath();
        etLogFile.setText(preferences.getString("log_file", defaultLogPath));
        etLogFile.setVisibility(cbSaveLogsToFile.isChecked() ? View.VISIBLE : View.GONE);
        cbSaveLogsToFile.setOnCheckedChangeListener((buttonView, isChecked) -> etLogFile.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        final Spinner sLanguage = view.findViewById(R.id.SLanguage);
        sLanguage.setSelection(LocaleHelper.getLocaleIndex(context));
        final int oldLCIndex = sLanguage.getSelectedItemPosition();

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            GameSettings.setScreenSize(context, getScreenSize(view));
            GameSettings.setEnvVars(context, envVarsView.getEnvVars());
            graphicsDriverPicker.save();

            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("enable_debug_logs", cbEnableDebugLogs.isChecked());
            editor.putBoolean("save_logs_to_file", cbSaveLogsToFile.isChecked());

            int newLCIndex = sLanguage.getSelectedItemPosition();
            editor.putInt("lc_index", newLCIndex);
            boolean restartApp = oldLCIndex != newLCIndex;

            String logPath = etLogFile.getText().toString().trim();
            if (!logPath.equals(defaultLogPath) && !logPath.isEmpty()) {
                editor.putString("log_file", logPath);
            }
            else editor.remove("log_file");

            if (editor.commit()) {
                if (restartApp) AppUtils.restartActivity(activity); // language
                else if (activity.isOpenedFromGame()) {
                    // the game is running with the old settings
                    ContentDialog.confirm(context, R.string.the_settings_have_been_changed_do_you_want_to_restart_the_app, () -> { GameScreen.restart(activity); activity.finish(); });
                }
                else activity.startGame();
            }
        });

        return view;
    }

    /** 720p / 1080p / native: puts the computed size into the custom width x height fields. */
    private static void setupResolutionButtons(final View view, final Context context) {
        final int[] ids = {R.id.BTSetRes720, R.id.BTSetRes1080, R.id.BTSetResNative};
        for (int i = 0; i < ids.length; i++) {
            final int mode = i;
            view.findViewById(ids[i]).setOnClickListener((v) -> {
                String[] size = ScreenResolution.forMode(context, mode).split("x");
                ((Spinner)view.findViewById(R.id.SScreenSize)).setSelection(0); // "Custom"
                view.findViewById(R.id.LLCustomScreenSize).setVisibility(View.VISIBLE);
                ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(size[0]);
                ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(size[1]);
            });
        }
    }

    private static String getScreenSize(View view) {
        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (sScreenSize.getSelectedItemPosition() == 0) {
            value = GameSettings.DEFAULT_SCREEN_SIZE;
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
