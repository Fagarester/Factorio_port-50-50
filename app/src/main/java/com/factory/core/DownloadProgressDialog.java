package com.factory.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.factory.R;
import com.factory.math.Mathf;


public class DownloadProgressDialog {
    private final Activity activity;
    private Dialog dialog;
    private int loadingWidth;

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
        // the logos and the bar take 46 % of the screen's width, as on the game's loading screen
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int screenWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
        // held in landscape (MainActivity.lockLandscape): sized for it even if the turn isn't done yet
        boolean landscape = activity.getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        int availableWidth = landscape ? screenWidth : metrics.widthPixels;
        loadingWidth = Math.min(Math.round(screenWidth * 0.46f), availableWidth - Math.round(32 * metrics.density));
        dialog.findViewById(R.id.LLLoading).getLayoutParams().width = loadingWidth;
        // a bit above the middle, as in the game (its logo starts at ~22 % of the height, ours did at ~29 %)
        dialog.findViewById(R.id.LLLoading).setTranslationY(-0.065f * Math.min(metrics.widthPixels, metrics.heightPixels));

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
        ProgressBar bar = dialog.findViewById(R.id.ProgressBar);
        bar.setIndeterminate(false);
        bar.setProgress(progress);
        ((TextView)dialog.findViewById(R.id.TVProgress)).setText(progress+"%");
    }

    /** Work of unknown length (unpacking the system files): the bar runs without a percent. */
    public void setIndeterminate() {
        if (dialog == null) return;
        ((ProgressBar)dialog.findViewById(R.id.ProgressBar)).setIndeterminate(true);
        ((TextView)dialog.findViewById(R.id.TVProgress)).setText("");
    }

    /** The work can't be stopped any more (the download is done, the unpacking goes on). */
    public void hideCancel() {
        if (dialog != null) dialog.findViewById(R.id.LLBottomBar).setVisibility(View.GONE);
    }

    public void setText(int textResId) {
        if (dialog != null) ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
    }

    /** Space Age's frame (1711 x 184 once cut) at the logos' width. */
    private int spaceAgeHeight() {
        return Math.round(loadingWidth * 184f / 1711f);
    }

    /**
     * Before the logos arrive: with the Space Age DLC its row is kept free (the bar doesn't move when it shows up,
     * halfway through an archive); without it the Factorio logo alone sits in the middle of both rows, as in the
     * game without the DLC.
     */
    public void reserveLogos(boolean spaceAge) {
        if (dialog == null) return;
        ImageView first = dialog.findViewById(R.id.IVLogo), second = dialog.findViewById(R.id.IVLogo2);
        second.getLayoutParams().height = spaceAgeHeight();
        second.setVisibility(View.INVISIBLE);
        first.setTranslationY(spaceAge ? 0 : spaceAgeHeight() / 2f);
    }

    /**
     * Unpacking an archive: the logo comes from factorio.com (GameSource.downloadLogo), its place is kept free so the
     * bar doesn't move when it arrives. With Space Age the site's picture has both names (590 x 152), without it
     * Factorio's alone (921 x 153).
     */
    public void reserveWebLogo(boolean spaceAge) {
        if (dialog == null) return;
        ImageView web = dialog.findViewById(R.id.IVWebLogo);
        web.getLayoutParams().height = Math.round(loadingWidth * (spaceAge ? 152f / 590f : 153f / 921f));
        // the demo's download goes on into its unpacking: the logo already there stays (no second fade)
        if (web.getVisibility() != View.VISIBLE) web.setVisibility(View.INVISIBLE);
        dialog.findViewById(R.id.IVLogo).setVisibility(View.GONE);
        dialog.findViewById(R.id.IVLogo2).setVisibility(View.GONE);
    }

    public void setWebLogo(Bitmap logo) {
        if (dialog == null || logo == null) return;
        ImageView web = dialog.findViewById(R.id.IVWebLogo);
        boolean appears = web.getVisibility() != View.VISIBLE;
        web.setImageBitmap(logo);
        if (appears) fadeIn(web);
    }

    /** How long a logo takes to appear. */
    private static final int FADE_IN_MS = 1400;

    /** Shows [view] from transparent to full. */
    public static void fadeIn(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(1f).setDuration(FADE_IN_MS).start();
    }

    /** Space Age's animation speed: the game's own isn't in its files, this looks close to it. */
    private static final int SPACE_AGE_FRAME_MS = 66;

    /**
     * The game's own logos (from the player's game files), above the bar like on the game's loading screen; the
     * Space Age one plays its frames in a loop.
     */
    public void setLogos(Bitmap logo, Bitmap[] logo2) {
        if (dialog == null) return;
        ImageView first = dialog.findViewById(R.id.IVLogo), second = dialog.findViewById(R.id.IVLogo2);
        if (logo != null) {
            boolean appears = first.getVisibility() != View.VISIBLE;
            first.setImageBitmap(logo);
            if (appears) fadeIn(first);
        }
        if (logo2 != null && logo2.length > 0) {
            first.setTranslationY(0);
            AnimationDrawable animation = new AnimationDrawable();
            for (Bitmap frame : logo2) animation.addFrame(new BitmapDrawable(activity.getResources(), frame), SPACE_AGE_FRAME_MS);
            animation.setOneShot(false);
            second.setImageDrawable(animation);
            fadeIn(second);
            animation.start();
        }
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
