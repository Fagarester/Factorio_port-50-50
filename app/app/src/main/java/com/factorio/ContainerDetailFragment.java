package com.factorio;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.factorio.container.Container;
import com.factorio.container.ContainerManager;
import com.factorio.container.GraphicsDriverPicker;
import com.factorio.container.GraphicsDrivers;
import com.factorio.contentdialog.AddEnvVarDialog;
import com.factorio.contentdialog.AudioDriverConfigDialog;
import com.factorio.contentdialog.ContentDialog;
import com.factorio.contentdialog.VortekConfigDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.EnvVars;
import com.factorio.core.PreloaderDialog;
import com.factorio.core.StringUtils;
import com.factorio.widget.EnvVarsView;

import org.json.JSONException;
import org.json.JSONObject;

public class ContainerDetailFragment extends Fragment {
    private ContainerManager manager;
    private final int containerId;
    private Container container;
    private PreloaderDialog preloaderDialog;

    public ContainerDetailFragment() {
        this(0);
    }

    public ContainerDetailFragment(int containerId) {
        this.containerId = containerId;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(isEditMode() ? R.string.edit_container : R.string.new_container);
    }

    public boolean isEditMode() {
        return container != null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup root, @Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        final View view = inflater.inflate(R.layout.container_detail_fragment, root, false);
        manager = new ContainerManager(context);
        container = containerId > 0 ? manager.getContainerById(containerId) : null;

        loadScreenSizeSpinner(view, isEditMode() ? container.getScreenSize() : Container.DEFAULT_SCREEN_SIZE);

        final String oldGraphicsDriverConfig = isEditMode() ? container.getGraphicsDriverConfig() : "";
        String selectedGraphicsDriver = isEditMode() ? container.getGraphicsDriver() : GraphicsDrivers.getDefaultDriver(context);
        GraphicsDriverPicker graphicsDriverPicker = new GraphicsDriverPicker(view.findViewById(R.id.LLGraphicsDriver), selectedGraphicsDriver, oldGraphicsDriverConfig);

        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, isEditMode() ? container.getAudioDriver() : Container.DEFAULT_AUDIO_DRIVER);

        final View vAudioDriverConfig = view.findViewById(R.id.BTAudioDriverConfig);
        vAudioDriverConfig.setTag(isEditMode() ? container.getAudioDriverConfig() : "");
        vAudioDriverConfig.setOnClickListener((v) -> (new AudioDriverConfigDialog(v)).show());

        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        envVarsView.setEnvVars(new EnvVars(isEditMode() ? container.getEnvVars() : Container.DEFAULT_ENV_VARS));
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            try {
                String screenSize = getScreenSize(view);
                String envVars = envVarsView.getEnvVars();
                String graphicsDriver = graphicsDriverPicker.getGraphicsDriver();
                String graphicsDriverConfig = graphicsDriverPicker.getGraphicsDriverConfig();
                String audioDriverConfig = vAudioDriverConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());

                if (isEditMode()) {
                    container.setScreenSize(screenSize);
                    container.setEnvVars(envVars);
                    container.setGraphicsDriver(graphicsDriver);
                    container.setGraphicsDriverConfig(graphicsDriverConfig);
                    container.setAudioDriver(audioDriver);
                    container.setAudioDriverConfig(audioDriverConfig);
                    container.saveData();

                    boolean requireRestart = graphicsDriver.equals(GraphicsDrivers.VORTEK) && VortekConfigDialog.isRequireRestart(oldGraphicsDriverConfig, graphicsDriverConfig);
                    if (requireRestart) ContentDialog.confirm(context, R.string.the_settings_have_been_changed_do_you_want_to_restart_the_app, () -> AppUtils.restartApplication(context));

                    getActivity().onBackPressed();
                }
                else {
                    JSONObject data = new JSONObject();
                    data.put("name", getString(R.string.container)+"-"+manager.getNextContainerId());
                    data.put("screenSize", screenSize);
                    data.put("envVars", envVars);
                    data.put("graphicsDriver", graphicsDriver);
                    data.put("graphicsDriverConfig", graphicsDriverConfig);
                    data.put("audioDriver", audioDriver);
                    data.put("audioDriverConfig", audioDriverConfig);

                    preloaderDialog.show(R.string.creating_container);
                    manager.createContainerAsync(data, (container) -> {
                        if (container != null) this.container = container;
                        preloaderDialog.close();
                        getActivity().onBackPressed();
                    });
                }
            }
            catch (JSONException e) {}
        });
        return view;
    }

    public static String getScreenSize(View view) {
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

    public static void loadScreenSizeSpinner(View view, String selectedValue) {
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
