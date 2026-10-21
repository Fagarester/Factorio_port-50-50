package com.factorio;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.factorio.core.LocaleHelper;
import com.factorio.inputcontrols.Binding;
import com.factorio.inputcontrols.ControlElement;
import com.factorio.inputcontrols.ControlsProfile;
import com.factorio.inputcontrols.CustomMenu;
import com.factorio.inputcontrols.InputControlsManager;
import com.factorio.inputcontrols.ScrollWheel;
import com.factorio.inputcontrols.SideDrawer;
import com.factorio.math.Mathf;
import com.factorio.core.AppUtils;
import com.factorio.core.FileUtils;
import com.factorio.core.UnitUtils;
import com.factorio.widget.InputControlsView;
import com.factorio.widget.NumberPicker;
import com.factorio.widget.SeekBar;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private View toolbox;

    @Override
    public void onCreate(Bundle bundle) {
        AppUtils.setActivityTheme(this);
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        ((TextView)findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);

        toolbox = container.findViewById(R.id.Toolbox);

        final PointF startPoint = new PointF();
        final boolean[] isActionDown = {false};
        container.findViewById(R.id.BTMove).setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPoint.x = event.getX();
                    startPoint.y = event.getY();
                    isActionDown[0] = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isActionDown[0]) {
                        float newX = toolbox.getX() + (event.getX() - startPoint.x);
                        float newY = toolbox.getY() + (event.getY() - startPoint.y);
                        moveToolbox(newX, newY);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    isActionDown[0] = false;
                    break;
            }
            return true;
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    private void moveToolbox(float x, float y) {
        final int padding = (int)UnitUtils.dpToPx(8);
        ViewGroup parent = (ViewGroup)toolbox.getParent();
        int width = toolbox.getWidth();
        int height = toolbox.getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        x = Mathf.clamp(x, padding, parentWidth - padding - width);
        y = Mathf.clamp(y, padding, parentHeight - padding - height);
        toolbox.setX(x);
        toolbox.setY(y);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                if (!inputControlsView.addElement()) {
                    AppUtils.showToast(this, R.string.no_profile_selected);
                }
                break;
            case R.id.BTRemoveElement:
                if (!inputControlsView.removeElement()) {
                    AppUtils.showToast(this, R.string.no_control_element_selected);
                }
                break;
            case R.id.BTElementSettings:
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                if (selectedElement != null) {
                    showControlElementSettings(v);
                }
                else AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        final View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable[] radialRefresh = {null};
        final Runnable[] drawerGridSync = {null};
        final Runnable[] customRefresh = {null};
        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();
            view.findViewById(R.id.LLShape).setVisibility(View.GONE);
            view.findViewById(R.id.CBToggleSwitch).setVisibility(View.GONE);
            view.findViewById(R.id.CBMouseMoveMode).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.GONE);
            view.findViewById(R.id.LLRangeOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLRadialMenuOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLOrientation).setVisibility(View.GONE);
            view.findViewById(R.id.LLScrollOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLDrawerOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomOptions).setVisibility(View.GONE);

            switch (type) {
                case BUTTON:
                    view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.CBToggleSwitch).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.CBMouseMoveMode).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                    break;
                case RANGE_BUTTON:
                    view.findViewById(R.id.LLRangeOptions).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLOrientation).setVisibility(View.VISIBLE);
                    break;
                case SCROLL_WHEEL:
                    ((RadioGroup)view.findViewById(R.id.RGOrientation)).check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
                    view.findViewById(R.id.LLScrollOptions).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLOrientation).setVisibility(View.VISIBLE);
                    break;
                case CUSTOM_MENU:
                    view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLCustomOptions).setVisibility(View.VISIBLE);
                    if (customRefresh[0] != null) customRefresh[0].run();
                    break;
                case SIDE_DRAWER:
                    ((NumberPicker)view.findViewById(R.id.NPDrawerCount)).setValue(element.getBindingCount());
                    if (drawerGridSync[0] != null) drawerGridSync[0].run();
                    view.findViewById(R.id.LLDrawerOptions).setVisibility(View.VISIBLE);
                    break;
                case RADIAL_MENU:
                    if (radialRefresh[0] != null) radialRefresh[0].run();
                    view.findViewById(R.id.LLRadialMenuOptions).setVisibility(View.VISIBLE);
                    break;
            }

            loadBindingSpinners(element, view);
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));

        loadIndexSpinner(view.findViewById(R.id.SScrollStyle), ScrollWheel.Style.names(), element.getScrollStyle().ordinal(), (index) -> {
            element.setScrollStyle(ScrollWheel.Style.values()[index]);
            profile.save();
            inputControlsView.invalidate();
        });
        loadIndexSpinner(view.findViewById(R.id.SRadialStyle), ControlElement.RadialStyle.names(), element.getRadialStyle().ordinal(), (index) -> {
            element.setRadialStyle(ControlElement.RadialStyle.values()[index]);
            profile.save();
            inputControlsView.invalidate();
        });
        loadIndexSpinner(view.findViewById(R.id.SRadialTrigger), ControlElement.RadialTrigger.names(), element.getRadialTrigger().ordinal(), (index) -> {
            element.setRadialTrigger(ControlElement.RadialTrigger.values()[index]);
            profile.save();
        });
        loadIndexSpinner(view.findViewById(R.id.SDrawerLayout), SideDrawer.Layout.names(), element.getDrawerLayout().ordinal(), (index) -> {
            element.setDrawerLayout(SideDrawer.Layout.values()[index]);
            if (drawerGridSync[0] != null) drawerGridSync[0].run();
            profile.save();
            inputControlsView.invalidate();
        });

        radialRefresh[0] = () -> {
            int parts = element.getRadialParts();
            view.findViewById(R.id.LLRadialSingleCount).setVisibility(parts == 1 ? View.VISIBLE : View.GONE);
            view.findViewById(R.id.LLRadialPartsBox).setVisibility(parts > 1 ? View.VISIBLE : View.GONE);
            ((NumberPicker)view.findViewById(R.id.NPBindings)).setValue(element.getRadialPartCount(0));

            LinearLayout list = view.findViewById(R.id.LLRadialParts);
            list.removeAllViews();
            if (parts > 1) {
                for (int i = 0; i < parts; i++) {
                    final int part = i;
                    View row = LayoutInflater.from(this).inflate(R.layout.radial_part_row, list, false);
                    CheckBox cbActive = row.findViewById(R.id.CBPartActive);
                    cbActive.setText(getString(R.string.radial_part, part + 1, parts));
                    cbActive.setChecked(element.isRadialPartActive(part));
                    cbActive.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        if (!element.setRadialPartActive(part, isChecked)) {
                            buttonView.setChecked(true);    // at least one part has to stay active
                            return;
                        }
                        profile.save();
                        loadBindingSpinners(element, view);
                        inputControlsView.invalidate();
                    });

                    NumberPicker npPartCount = row.findViewById(R.id.NPPartCount);
                    npPartCount.setValue(element.getRadialPartCount(part));
                    npPartCount.setOnValueChangeListener((numberPicker, value) -> {
                        element.setRadialPartCount(part, value);
                        profile.save();
                        loadBindingSpinners(element, view);
                        inputControlsView.invalidate();
                    });
                    list.addView(row);
                }
            }
            loadBindingSpinners(element, view);
        };

        final String[] partNames = new String[8];
        for (int i = 0; i < partNames.length; i++) partNames[i] = "1/" + (i + 1);
        loadIndexSpinner(view.findViewById(R.id.SRadialParts), partNames, element.getRadialParts() - 1, (index) -> {
            if (element.getRadialParts() == index + 1) return;
            element.setRadialParts(index + 1);
            profile.save();
            radialRefresh[0].run();
            inputControlsView.invalidate();
        });
        SeekBar sbRadialRotation = view.findViewById(R.id.SBRadialRotation);
        sbRadialRotation.setOnValueChangeListener((seekBar, value) -> {
            element.setRadialRotation(value);
            profile.save();
            inputControlsView.invalidate();
        });
        sbRadialRotation.setValue(element.getRadialRotation());
        SeekBar sbRadialDistance = view.findViewById(R.id.SBRadialDistance);
        sbRadialDistance.setOnValueChangeListener((seekBar, value) -> {
            element.setRadialDistance(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbRadialDistance.setValue(element.getRadialDistance() * 100);

        loadIndexSpinner(view.findViewById(R.id.SCustomTrigger), ControlElement.RadialTrigger.names(), element.getRadialTrigger().ordinal(), (index) -> {
            element.setRadialTrigger(ControlElement.RadialTrigger.values()[index]);
            profile.save();
        });

        CheckBox cbCustomAutoClose = view.findViewById(R.id.CBCustomAutoClose);
        cbCustomAutoClose.setChecked(element.isDrawerAutoClose());
        cbCustomAutoClose.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setDrawerAutoClose(isChecked);
            profile.save();
        });

        final EditText etNodeText = view.findViewById(R.id.ETNodeText);
        final boolean[] customUpdating = {false};
        etNodeText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable editable) {
                if (customUpdating[0] || element.getCustomMenu() == null) return;
                CustomMenu.Node node = element.getCustomMenu().getSelectedNode();
                if (node != null) {
                    node.text = editable.toString().trim();
                    profile.save();
                    inputControlsView.invalidate();
                }
            }
        });

        final CheckBox cbNodeHidePath = view.findViewById(R.id.CBNodeHidePath);
        cbNodeHidePath.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (customUpdating[0] || element.getCustomMenu() == null) return;
            CustomMenu.Node node = element.getCustomMenu().getSelectedNode();
            if (node != null) {
                node.hideOnPath = isChecked;
                profile.save();
            }
        });

        final SeekBar sbNodeSize = view.findViewById(R.id.SBNodeSize);
        sbNodeSize.setOnValueChangeListener((seekBar, value) -> {
            if (customUpdating[0] || element.getCustomMenu() == null) return;
            CustomMenu.Node node = element.getCustomMenu().getSelectedNode();
            if (node != null) {
                element.getCustomMenu().setNodeSize(node, value / 100.0f * 2.5f);
                profile.save();
            }
        });

        final Spinner sNodeShape = view.findViewById(R.id.SNodeShape);
        sNodeShape.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        sNodeShape.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (customUpdating[0] || element.getCustomMenu() == null) return;
                CustomMenu.Node node = element.getCustomMenu().getSelectedNode();
                if (node != null && node.shape != ControlElement.Shape.values()[position]) {
                    node.shape = ControlElement.Shape.values()[position];
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        customRefresh[0] = () -> {
            CustomMenu menu = element.getCustomMenu();
            if (menu == null) return;
            CustomMenu.Node node = menu.getSelectedNode();
            customUpdating[0] = true;

            TextView tvSelected = view.findViewById(R.id.TVCustomSelected);
            LinearLayout llNode = view.findViewById(R.id.LLCustomNode);
            LinearLayout llNodeBinding = view.findViewById(R.id.LLCustomNodeBinding);
            llNodeBinding.removeAllViews();

            if (node == null) {
                tvSelected.setText(R.string.custom_selected_root);
                llNode.setVisibility(View.GONE);
            }
            else {
                tvSelected.setText(getString(R.string.custom_selected_node, menu.getNodePath(node), menu.getNodeCount()));
                llNode.setVisibility(View.VISIBLE);
                loadNodeBindingSpinner(llNodeBinding, node);
                sbNodeSize.setValue(node.size / 2.5f * 100.0f);
                sNodeShape.setSelection(node.shape.ordinal(), false);
                etNodeText.setText(node.text);
                cbNodeHidePath.setChecked(node.hideOnPath);
            }
            customUpdating[0] = false;
        };

        view.findViewById(R.id.BTCustomAdd).setOnClickListener((v) -> {
            CustomMenu menu = element.getCustomMenu();
            if (menu == null) return;
            CustomMenu.Node added = menu.addChild(menu.getSelectedNode());
            menu.selectNode(added);
            profile.save();
            customRefresh[0].run();
            inputControlsView.invalidate();
        });

        view.findViewById(R.id.BTCustomRemove).setOnClickListener((v) -> {
            CustomMenu menu = element.getCustomMenu();
            if (menu == null || menu.getSelectedNode() == null) return;
            CustomMenu.Node parent = menu.getSelectedNode().parent;
            menu.removeNode(menu.getSelectedNode());
            menu.selectNode(parent != null && !parent.isRoot() ? parent : null);
            profile.save();
            customRefresh[0].run();
            inputControlsView.invalidate();
        });

        loadIndexSpinner(view.findViewById(R.id.SDrawerSide), new String[]{getString(R.string.left), getString(R.string.right), getString(R.string.top), getString(R.string.bottom)}, element.getDrawerSide(), (index) -> {
            element.setDrawerSide((byte)index);
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npDrawerCount = view.findViewById(R.id.NPDrawerCount);
        npDrawerCount.setValue(element.getBindingCount());
        npDrawerCount.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            drawerGridSync[0].run();
            loadBindingSpinners(element, view);
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npDrawerColumns = view.findViewById(R.id.NPDrawerColumns);
        NumberPicker npDrawerRows = view.findViewById(R.id.NPDrawerRows);
        drawerGridSync[0] = () -> {
            view.findViewById(R.id.LLDrawerGrid).setVisibility(element.getDrawerLayout() == SideDrawer.Layout.GRID ? View.VISIBLE : View.GONE);
            int[] grid = element.getGridSize();     // the value that was not chosen is calculated from the number of buttons
            npDrawerColumns.setValue(grid[0]);
            npDrawerRows.setValue(grid[1]);
        };
        npDrawerColumns.setOnValueChangeListener((numberPicker, value) -> {
            element.setGridColumns(value);
            drawerGridSync[0].run();
            profile.save();
            inputControlsView.invalidate();
        });
        npDrawerRows.setOnValueChangeListener((numberPicker, value) -> {
            element.setGridRows(value);
            drawerGridSync[0].run();
            profile.save();
            inputControlsView.invalidate();
        });
        drawerGridSync[0].run();

        CheckBox cbDrawerAutoClose = view.findViewById(R.id.CBDrawerAutoClose);
        cbDrawerAutoClose.setChecked(element.isDrawerAutoClose());
        cbDrawerAutoClose.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setDrawerAutoClose(isChecked);
            profile.save();
        });

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
        rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
            element.setOrientation((byte)(checkedId == R.id.RBVertical ? 1 : 0));
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        npColumns.setValue(element.getBindingCount());
        npColumns.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npBindings = view.findViewById(R.id.NPBindings);
        npBindings.setValue(element.getRadialPartCount(0));
        npBindings.setOnValueChangeListener((numberPicker, value) -> {
            element.setRadialPartCount(0, value);
            loadBindingSpinners(element, view);
            profile.save();
            inputControlsView.invalidate();
        });

        SeekBar sbScale = view.findViewById(R.id.SBScale);
        sbScale.setOnValueChangeListener((seekBar, value) -> {
            element.setScale(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbScale.setValue(element.getScale() * 100);

        SeekBar sbOpacity = view.findViewById(R.id.SBOpacity);
        sbOpacity.setOnValueChangeListener((seekBar, value) -> {
            element.setOpacity(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbOpacity.setValue(element.getOpacity() * 100);

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        CheckBox cbMouseMoveMode = view.findViewById(R.id.CBMouseMoveMode);
        cbMouseMoveMode.setChecked(element.isMouseMoveMode());
        cbMouseMoveMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setMouseMoveMode(isChecked);
            profile.save();
        });

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        etCustomText.setText(element.getText());
        final LinearLayout llIconList = view.findViewById(R.id.LLIconList);
        loadIcons(llIconList, element.getIconId());

        if (element.getCustomMenu() != null) element.getCustomMenu().setOnSelectionChanged(() -> {
            if (customRefresh[0] != null) customRefresh[0].run();
        });

        updateLayout.run();

        PopupWindow popupWindow = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        popupWindow.setOnDismissListener(() -> {
            byte iconId = 0;
            if (element.getCustomMenu() != null) element.getCustomMenu().setOnSelectionChanged(null);
            if (element.getType() == ControlElement.Type.BUTTON || element.getType() == ControlElement.Type.CUSTOM_MENU) {
                for (int i = 0; i < llIconList.getChildCount(); i++) {
                    View child = llIconList.getChildAt(i);
                    if (child.isSelected()) {
                        iconId = (byte)child.getTag();
                        break;
                    }
                }

                String text = etCustomText.getText().toString().trim();
                element.setText(text);
            }

            element.setIconId(iconId);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, final Runnable callback) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ControlElement.Type newType = ControlElement.Type.values()[position];
                if (newType == element.getType()) return;
                element.setType(newType);
                profile.save();
                callback.run();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            byte first = element.getFirstBindingIndex();
            for (byte i = 0, count = 0; i < element.getBindingCount(); i++) {
                if (i <= first || element.getBindingAt(i) != Binding.NONE) {
                    loadBindingSpinner(element, container, i, count++ == 0 ? R.string.binding : 0);
                }
            }
        }
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
        else if (type == ControlElement.Type.SIDE_DRAWER) {
            for (byte i = 0; i < element.getBindingCount(); i++) loadBindingSpinner(element, container, i, 0);
        }
        else if (type == ControlElement.Type.RADIAL_MENU) {
            int parts = element.getRadialParts();
            int offset = 0;
            for (int part = 0; part < parts; part++) {
                int count = element.getRadialPartCount(part);
                if (element.isRadialPartActive(part)) {
                    for (int j = 0; j < count; j++) {
                        loadBindingSpinner(element, container, offset + j, 0);
                        if (j == 0 && parts > 1) {
                            View field = container.getChildAt(container.getChildCount() - 1);
                            field.findViewById(R.id.LLTitleBar).setVisibility(View.VISIBLE);
                            ((TextView)field.findViewById(R.id.TVTitle)).setText(getString(R.string.radial_part, part + 1, parts));
                        }
                    }
                }
                offset += count;
            }
        }
        else if (type == ControlElement.Type.SCROLL_WHEEL) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_down);
        }
    }

    private void loadIndexSpinner(Spinner spinner, String[] names, int selected, final java.util.function.IntConsumer onSelected) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        spinner.setSelection(selected, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onSelected.accept(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinner(final ControlElement element, final LinearLayout container, final int index, int titleResId) {
        View view = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);

        LinearLayout titleBar = view.findViewById(R.id.LLTitleBar);
        if (titleResId > 0) {
            titleBar.setVisibility(View.VISIBLE);
            ((TextView)view.findViewById(R.id.TVTitle)).setText(titleResId);
        }
        else titleBar.setVisibility(View.GONE);

        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            ImageButton addButton = view.findViewById(R.id.BTAdd);
            addButton.setVisibility(View.VISIBLE);
            addButton.setOnClickListener((v) -> {
                int nextIndex = container.getChildCount();
                if (nextIndex < element.getBindingCount()) loadBindingSpinner(element, container, nextIndex, 0);
            });
        }

        Runnable update = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0:
                    bindingEntries = Binding.keyboardBindingLabels();
                    break;
                case 1:
                    bindingEntries = Binding.mouseBindingLabels();
                    break;
                case 2:
                    bindingEntries = Binding.gamepadBindingLabels();
                    break;
            }

            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) {
            sBindingType.setSelection(0, false);
        }
        else if (selectedBinding.isMouse()) {
            sBindingType.setSelection(1, false);
        }
        else if (selectedBinding.isGamepad()) {
            sBindingType.setSelection(2, false);
        }

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0:
                        binding = Binding.keyboardBindingValues()[position];
                        break;
                    case 1:
                        binding = Binding.mouseBindingValues()[position];
                        break;
                    case 2:
                        binding = Binding.gamepadBindingValues()[position];
                        break;
                }

                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        update.run();
        container.addView(view);
    }

    private void loadNodeBindingSpinner(final LinearLayout container, final CustomMenu.Node node) {
        View view = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);
        view.findViewById(R.id.LLTitleBar).setVisibility(View.GONE);

        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        Runnable update = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0:
                    bindingEntries = Binding.keyboardBindingLabels();
                    break;
                case 1:
                    bindingEntries = Binding.mouseBindingLabels();
                    break;
                case 2:
                    bindingEntries = Binding.gamepadBindingLabels();
                    break;
            }

            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(sBinding, node.binding.toString());
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (node.binding.isKeyboard()) sBindingType.setSelection(0, false);
        else if (node.binding.isMouse()) sBindingType.setSelection(1, false);
        else if (node.binding.isGamepad()) sBindingType.setSelection(2, false);

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0:
                        binding = Binding.keyboardBindingValues()[position];
                        break;
                    case 1:
                        binding = Binding.mouseBindingValues()[position];
                        break;
                    case 2:
                        binding = Binding.gamepadBindingValues()[position];
                        break;
                }

                if (binding != node.binding) {
                    node.binding = binding;
                    profile.save();
                    inputControlsView.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        update.run();
        container.addView(view);
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadIcons(final LinearLayout parent, byte selectedId) {
        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        }
        catch (IOException e) {}

        Arrays.sort(iconIds);

        int size = (int)UnitUtils.dpToPx(40);
        int margin = (int)UnitUtils.dpToPx(2);
        int padding = (int)UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        for (final byte id : iconIds) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(id);
            imageView.setSelected(id == selectedId);
            imageView.setOnClickListener((v) -> {
                for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                imageView.setSelected(true);
            });

            try (InputStream is = getAssets().open("inputcontrols/icons/"+id+".png")) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
            }
            catch (IOException e) {}

            parent.addView(imageView);
        }
    }
}
