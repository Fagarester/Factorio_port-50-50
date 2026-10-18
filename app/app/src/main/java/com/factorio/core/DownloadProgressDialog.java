package com.factorio.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.factorio.R;
import com.factorio.math.Mathf;

import java.util.function.IntConsumer;

public class DownloadProgressDialog {
    private final Activity activity;
    private Dialog dialog;

    public DownloadProgressDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.download_progress_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public void show() {
        show(null);
    }

    public void show(int textResId) {
        show(textResId, null);
    }

    public void show(Runnable onCancelCallback) {
        show(0, onCancelCallback);
    }

    public void show(int textResId, final Runnable onCancelCallback) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();

        if (textResId > 0) ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);

        setProgress(0);
        if (onCancelCallback != null) {
            dialog.findViewById(R.id.BTCancel).setOnClickListener((v) -> onCancelCallback.run());
            dialog.findViewById(R.id.LLBottomBar).setVisibility(View.VISIBLE);
        }
        dialog.show();
    }

    public void setProgress(int progress) {
        if (dialog == null) return;
        progress = Mathf.clamp(progress, 0, 100);
        ((CircularProgressIndicator)dialog.findViewById(R.id.CircularProgressIndicator)).setProgress(progress);
        ((TextView)dialog.findViewById(R.id.TVProgress)).setText(progress+"%");
    }

    /**
     * Shows three choices (720p / 1080p / native) above the progress text.
     * The labels are already filled with the real resolutions; onPick gets the index 0..2.
     */
    public void showResolutionPicker(String[] labels, IntConsumer onPick) {
        if (dialog == null) return;
        final int[] buttonIds = {R.id.BTRes720, R.id.BTRes1080, R.id.BTResNative};
        final Button[] buttons = new Button[buttonIds.length];
        for (int i = 0; i < buttonIds.length; i++) {
            final int index = i;
            buttons[i] = dialog.findViewById(buttonIds[i]);
            buttons[i].setText(labels[i]);
            buttons[i].setOnClickListener((v) -> {
                for (int j = 0; j < buttons.length; j++) buttons[j].setAlpha(j == index ? 1.0f : 0.45f);
                onPick.accept(index);
            });
        }
        dialog.findViewById(R.id.LLResolutionPicker).setVisibility(View.VISIBLE);
    }

    public void setResolutionHint(int textResId) {
        if (dialog == null) return;
        ((TextView)dialog.findViewById(R.id.TVResolutionTitle)).setText(textResId);
    }

    public void hideResolutionPicker() {
        if (dialog != null) dialog.findViewById(R.id.LLResolutionPicker).setVisibility(View.GONE);
    }

    public void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
