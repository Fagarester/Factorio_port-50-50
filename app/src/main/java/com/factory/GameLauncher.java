package com.factory;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.TextUtils;
import android.graphics.Bitmap;
import android.widget.TextView;
import android.app.Dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.factory.core.AppUtils;
import com.factory.core.DownloadProgressDialog;
import com.factory.core.EnvVars;
import com.factory.core.FileUtils;
import com.factory.core.GameCompression;
import com.factory.core.GameCopier;
import com.factory.core.GameSettings;
import com.factory.core.QuickHttps;
import com.factory.core.ScreenResolution;
import com.factory.gladio.Gladio;
import com.factory.runtime.ArchivesKt;
import com.factory.runtime.CountingInputStream;
import com.factory.runtime.Drivers;
import com.factory.runtime.Paths;
import com.factory.runtime.Session;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** Starts the game: on the first start copies it from a game folder or unpacks the archive, then the X server and Factorio. */
public abstract class GameLauncher {
    public static boolean isGameInstalled(Context context) {
        return new Paths(context).getGameBinary().isFile();
    }

    public static void start(final MainActivity activity) {
        if (isGameInstalled(activity)) {
            launch(activity);
            return;
        }
        findGame(activity);
    }

    /**
     * No game yet: a quick search (GameSource) and the copy at once, without questions. A game folder comes first; with
     * none, the archive from factorio.com in Download is unpacked. With neither, the player picks one by hand.
     */
    private static void findGame(final MainActivity activity) {
        // without the storage permission Download can't be searched: the player picks the archive
        if (!activity.hasStoragePermission()) {
            showGameSource(activity);
            return;
        }
        activity.lockLandscape();
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.game_source_searching);
        dialog.setIndeterminate();
        AppUtils.keepScreenOn(activity);
        Executors.newSingleThreadExecutor().execute(() -> {
            final File folder = GameSource.findFolder();
            final File archive = folder == null ? GameSource.findArchive() : null;
            activity.runOnUiThread(() -> {
                if (folder != null) copyGame(activity, dialog, folder);
                else if (archive != null) unpackGame(activity, dialog, () -> new FileInputStream(archive), archive.length(), GameSource.isSpaceAgeArchive(archive.getName()));
                else {
                    dialog.close();
                    showGameSource(activity);
                }
            });
        });
    }

    /** Nothing found: the player picks the game folder or the archive, or follows the link to factorio.com. */
    private static void showGameSource(final MainActivity activity) {
        // landscape like the loading screen and the game; free again only with back (the settings)
        activity.lockLandscape();
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.game_source_dialog);
        dialog.setCancelable(true);
        if (activity.isOpenedForSettings()) {
            // opened for the settings (the shortcut): back to them; the cover goes once this screen is drawn
            dialog.setOnShowListener((d) -> new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                activity.hideStartupCover();
                activity.lockLandscape();
            }, 300));
            dialog.setOnCancelListener((d) -> activity.unlockOrientation());
        }
        else {
            // a normal start: no settings before the game is installed (the shortcut's hold opens them); back closes the app
            dialog.setOnCancelListener((d) -> activity.finish());
        }

        dialog.findViewById(R.id.BTPickFolder).setOnClickListener((v) -> {
            if (!activity.hasStoragePermission()) {
                askStoragePermission(activity, dialog);
                return;
            }
            activity.setOpenFileCallback((uri) -> {
                File picked = GameSource.folderFromTree(uri);
                File game = GameSource.gameFolderIn(picked);
                if (game == null) {
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.game_not_found)
                        .setMessage(picked == null ? activity.getString(R.string.game_source_folder_unreadable)
                                                   : activity.getString(R.string.game_source_not_game_folder, picked.getPath()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                dialog.dismiss();
                copyGame(activity, null, game);
            });
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOAD_DOCUMENT);
            startPicker(activity, intent, MainActivity.OPEN_DIRECTORY_REQUEST_CODE);
        });

        dialog.findViewById(R.id.BTPickArchive).setOnClickListener((v) -> {
            activity.setOpenFileCallback((uri) -> {
                dialog.dismiss();
                unpackGame(activity, null, () -> activity.getContentResolver().openInputStream(uri), contentSize(activity, uri),
                    GameSource.isSpaceAgeArchive(displayName(activity, uri)));
            });
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOAD_DOCUMENT);
            startPicker(activity, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        });

        // the logo from factorio.com (its place stays empty without internet); an intro the first time
        final android.widget.ImageView logoView = dialog.findViewById(R.id.IVDemoLogo);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        final int logoWidth = Math.round(Math.max(metrics.widthPixels, metrics.heightPixels) * 0.38f);
        logoView.getLayoutParams().width = logoWidth;
        logoView.getLayoutParams().height = Math.round(logoWidth * 153f / 921f);
        final SourceIntro intro = new SourceIntro(dialog, !sourceIntroShown);
        sourceIntroShown = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap logo = GameSource.downloadLogo(false);
            activity.runOnUiThread(() -> intro.logoReady(logo));
        });
        // back from the browser (or the file manager): the archive downloaded into Download meanwhile is taken at once
        final boolean[] looking = {false};
        dialog.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener((hasFocus) -> {
            if (!hasFocus || looking[0] || !dialog.isShowing() || !activity.hasStoragePermission()) return;
            looking[0] = true;
            Executors.newSingleThreadExecutor().execute(() -> {
                File found = GameSource.findArchive();
                // a browser still writing it: the size must hold still
                if (found != null) {
                    long size = found.length();
                    try {
                        Thread.sleep(1500);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (found.length() != size || System.currentTimeMillis() - found.lastModified() < 3000) found = null;
                }
                final File archive = found;
                activity.runOnUiThread(() -> {
                    looking[0] = false;
                    if (archive == null || !dialog.isShowing()) return;
                    dialog.dismiss();
                    unpackGame(activity, null, () -> new FileInputStream(archive), archive.length(), GameSource.isSpaceAgeArchive(archive.getName()));
                });
            });
        });

        dialog.findViewById(R.id.BTDownloadDemo).setOnClickListener((v) -> {
            dialog.dismiss();
            // the archive goes to Download like a browser's download; without the permission into the app's own folder
            if (activity.canWriteDownload() || !activity.canAskStoragePermission()) downloadDemo(activity);
            else activity.requestStoragePermission(() -> downloadDemo(activity));
        });

        TextView link = dialog.findViewById(R.id.TVDownloadLink);
        link.setText(Html.fromHtml("<u>"+TextUtils.htmlEncode(activity.getString(R.string.game_source_buy))+"</u>", Html.FROM_HTML_MODE_LEGACY));
        link.setOnClickListener((v) -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GameSource.DOWNLOAD_PAGE)));
            }
            catch (Exception e) {
                AppUtils.showToast(activity, GameSource.DOWNLOAD_PAGE);
            }
        });
        dialog.show();
    }

    /** The intro plays once per app start; the screen shown again (after a failed copy…) opens at once. */
    private static boolean sourceIntroShown;

    /**
     * The "where is the game" screen's entrance. Intro: the logo alone in the middle, then the words under it; a tap
     * anywhere lifts both up, the words turn into the question and the choices rise from below. Without the intro the
     * choices rise at once.
     */
    private static class SourceIntro {
        private static final int LOGO_TO_WORDS_MS = 900, NO_LOGO_WAIT_MS = 2500, TAP_HINT_AFTER_MS = 2200;
        private final android.view.View content, top, introWords, title, link, tapHint;
        private final android.widget.ImageView logo;
        private final android.view.ViewGroup cards;
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final float rise;
        private boolean opened, wordsShown;
        private android.animation.ObjectAnimator pulse;

        SourceIntro(Dialog dialog, boolean playIntro) {
            content = dialog.findViewById(R.id.LLSourceContent);
            top = dialog.findViewById(R.id.LLSourceTop);
            introWords = dialog.findViewById(R.id.TVSourceIntro);
            title = dialog.findViewById(R.id.LLSourceTitle);
            link = dialog.findViewById(R.id.LLSourceLink);
            tapHint = dialog.findViewById(R.id.TVSourceTap);
            logo = dialog.findViewById(R.id.IVDemoLogo);
            cards = dialog.findViewById(R.id.LLSourceCards);
            rise = 28 * content.getResources().getDisplayMetrics().density;
            dialog.setOnDismissListener((d) -> {
                handler.removeCallbacksAndMessages(null);
                if (pulse != null) pulse.cancel();
            });

            introWords.setAlpha(0f);
            title.setAlpha(0f);
            link.setAlpha(0f);
            for (int i = 0; i < cards.getChildCount(); i++) cards.getChildAt(i).setVisibility(android.view.View.INVISIBLE);
            link.setVisibility(android.view.View.INVISIBLE);
            if (!playIntro) {
                open();
                return;
            }
            // the logo and the words in the middle of the screen until the tap
            top.setVisibility(android.view.View.INVISIBLE);
            content.post(() -> {
                top.setTranslationY(content.getHeight() / 2f - (top.getTop() + top.getHeight() / 2f));
                top.setVisibility(android.view.View.VISIBLE);
            });
            content.setOnClickListener((v) -> open());
            // no logo (no internet, slow): the words come anyway
            handler.postDelayed(this::showWords, NO_LOGO_WAIT_MS);
        }

        void logoReady(Bitmap bitmap) {
            if (bitmap != null) {
                logo.setImageBitmap(bitmap);
                DownloadProgressDialog.fadeIn(logo);
            }
            if (!opened && !wordsShown) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(this::showWords, bitmap != null ? LOGO_TO_WORDS_MS : 0);
            }
        }

        private void showWords() {
            if (opened || wordsShown) return;
            wordsShown = true;
            introWords.animate().alpha(1f).setDuration(900).start();
            handler.postDelayed(() -> {
                if (opened) return;
                pulse = android.animation.ObjectAnimator.ofFloat(tapHint, "alpha", 0f, 0.85f, 0.3f);
                pulse.setDuration(1800);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.start();
            }, TAP_HINT_AFTER_MS);
        }

        private void open() {
            if (opened) return;
            opened = true;
            handler.removeCallbacksAndMessages(null);
            content.setOnClickListener(null);
            content.setClickable(false);
            if (pulse != null) pulse.cancel();
            tapHint.animate().alpha(0f).setDuration(250).start();

            android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator();
            top.animate().translationY(0f).setDuration(700).setInterpolator(ease).start();
            introWords.animate().alpha(0f).setDuration(300).start();
            title.animate().alpha(1f).setStartDelay(300).setDuration(600).start();
            for (int i = 0; i < cards.getChildCount(); i++) {
                android.view.View card = cards.getChildAt(i);
                card.setVisibility(android.view.View.VISIBLE);
                card.setAlpha(0f);
                card.setTranslationY(rise);
                card.animate().alpha(1f).translationY(0f).setStartDelay(400 + 110L * i).setDuration(550).setInterpolator(ease).start();
            }
            link.setVisibility(android.view.View.VISIBLE);
            link.animate().alpha(1f).setStartDelay(850).setDuration(600).start();
        }
    }

    /** A game folder is read as files: that needs the storage permission (an archive doesn't). */
    private static void askStoragePermission(final MainActivity activity, final Dialog sourceDialog) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.game_source_pick_folder)
            .setMessage(R.string.storage_permission_for_folder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.storage_permission_allow, (d, w) -> {
                if (activity.canAskStoragePermission()) {
                    activity.requestStoragePermission(() -> {
                        // allowed: the search can now find the folder by itself
                        if (activity.hasStoragePermission()) {
                            sourceDialog.dismiss();
                            findGame(activity);
                        }
                    });
                }
                else {
                    // Android no longer asks after the second "Deny": its settings for the app
                    try {
                        activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.getPackageName(), null)));
                    }
                    catch (Exception e) {
                        AppUtils.showToast(activity, R.string.storage_permission_for_folder);
                    }
                }
            })
            .show();
    }

    /** The system's pickers open in Download. */
    private static final Uri DOWNLOAD_DOCUMENT = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download");

    private static void startPicker(MainActivity activity, Intent intent, int requestCode) {
        try {
            activity.startActivityForResult(intent, requestCode);
        }
        catch (Exception e) {
            AppUtils.showToast(activity, String.valueOf(e.getMessage()));
        }
    }

    private static long contentSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        }
        catch (Exception e) {
            // unknown size: the progress stays at 0 % until the end
        }
        return -1;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        catch (Exception e) {
            // no name: taken as the Space Age archive (GameSource.isSpaceAgeArchive)
        }
        return null;
    }

    private interface StreamOpener {
        InputStream open() throws IOException;
    }

    /** The loading screen: the one the search showed, or a new one. */
    private static DownloadProgressDialog progressScreen(MainActivity activity, DownloadProgressDialog dialog, int textResId) {
        activity.lockLandscape();
        if (dialog == null) {
            dialog = new DownloadProgressDialog(activity);
            dialog.show(textResId);
        }
        else {
            dialog.setText(textResId);
            dialog.hideCancel();
        }
        dialog.setProgress(0);
        AppUtils.keepScreenOn(activity);
        return dialog;
    }

    /**
     * The archive from factorio.com, unpacked into the app's files (game.unpacking) and moved into place.
     */
    private static void unpackGame(final MainActivity activity, DownloadProgressDialog screen, final StreamOpener opener, final long size, boolean spaceAge) {
        final Paths paths = new Paths(activity);
        final DownloadProgressDialog dialog = progressScreen(activity, screen, R.string.unpacking_game);
        prestartX(activity);
        // the logo from factorio.com, chosen by the archive's name (the game's own are deep inside the archive)
        dialog.reserveWebLogo(spaceAge);
        dialog.setWebLogo(GameSource.downloadedLogo(spaceAge));
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap webLogo = GameSource.downloadLogo(spaceAge);
            if (webLogo != null) activity.runOnUiThread(() -> dialog.setWebLogo(webLogo));
        });

        Executors.newSingleThreadExecutor().execute(() -> {
            File unpacked = new File(paths.getFiles(), "game.unpacking");
            FileUtils.delete(unpacked);
            String failure = null;
            File game = null;
            try {
                if (!unpacked.mkdirs()) throw new IOException("cannot create "+unpacked);
                final int[] shown = {-1};
                try (InputStream input = new CountingInputStream(opener.open(), (read) -> {
                    int percent = size > 0 ? (int)(read * 100 / size) : 0;
                    if (percent != shown[0]) {
                        shown[0] = percent;
                        activity.runOnUiThread(() -> dialog.setProgress(percent));
                    }
                    return kotlin.Unit.INSTANCE;
                })) {
                    ArchivesKt.untarXz(input, unpacked);
                }
                game = GameSource.gameFolderIn(unpacked);
                if (game == null) throw new IOException(activity.getString(R.string.game_source_archive_no_game));
            }
            catch (Throwable e) {
                android.util.Log.e("Factory", "Cannot unpack the game", e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
                FileUtils.delete(unpacked);
            }

            if (failure != null) {
                final String message = failure;
                activity.runOnUiThread(() -> {
                    dialog.close();
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.game_unpack_failed)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> showGameSource(activity))
                        .setOnCancelListener((d) -> showGameSource(activity))
                        .show();
                });
                return;
            }

            // the unpacked folder is the game: no second copy
            boolean ok = install(paths, game);
            FileUtils.delete(unpacked);
            activity.runOnUiThread(() -> finishInstall(activity, dialog, ok));
        });
    }

    /** Stops the demo's download (Cancel on the loading screen); the part already loaded stays for the next try. */
    private static class DownloadCancelled extends IOException {}

    /**
     * The free demo from factorio.com into Download (or the app's own folder without the permission), then unpacked
     * like any archive. A cut download goes on from where it stopped (file.part).
     */
    private static void downloadDemo(final MainActivity activity) {
        activity.lockLandscape();
        AppUtils.keepScreenOn(activity);
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.downloading_demo, () -> cancelled.set(true));
        dialog.reserveWebLogo(false);
        dialog.setWebLogo(GameSource.downloadedLogo(false));
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap webLogo = GameSource.downloadLogo(false);
            if (webLogo != null) activity.runOnUiThread(() -> dialog.setWebLogo(webLogo));
        });

        final File folder = activity.canWriteDownload() ? GameSource.downloads()
                                                        : activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        Executors.newSingleThreadExecutor().execute(() -> {
            File archive = null;
            String failure = null;
            try {
                archive = fetchDemo(folder, cancelled, (percent) -> activity.runOnUiThread(() -> dialog.setProgress(percent)));
            }
            catch (DownloadCancelled e) {
                activity.runOnUiThread(() -> {
                    dialog.close();
                    showGameSource(activity);
                });
                return;
            }
            catch (Throwable e) {
                android.util.Log.e("Factory", "Cannot download the demo", e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            final File ready = archive;
            final String message = failure;
            activity.runOnUiThread(() -> {
                if (message != null) {
                    dialog.close();
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.demo_download_failed)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> showGameSource(activity))
                        .setOnCancelListener((d) -> showGameSource(activity))
                        .show();
                    return;
                }
                unpackGame(activity, dialog, () -> new FileInputStream(ready), ready.length(), false);
            });
        });
    }

    private interface PercentListener {
        void onPercent(int percent);
    }

    /** Downloads the demo's archive into [folder]; returns it once complete. */
    private static File fetchDemo(File folder, java.util.concurrent.atomic.AtomicBoolean cancelled, PercentListener listener) throws IOException {
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new IOException("cannot create "+folder);
        QuickHttps.Response response = QuickHttps.open(GameSource.DEMO_DOWNLOAD, null, 5000, 30000);
        try {
            if (response.status != 200) throw new IOException("factorio.com: answer "+response.status);
            String name = response.fileName();
            // the name comes from the server: only a plain file name of a game archive
            if (!name.matches("[A-Za-z0-9._-]+\\.tar\\.xz")) name = "factorio-demo_linux-arm64.tar.xz";
            File target = new File(folder, name);
            long total = response.length();
            if (total <= 0) throw new IOException("factorio.com didn't tell the size");
            if (target.isFile() && target.length() == total) return target;

            File part = new File(folder, name+".part");
            long done = part.isFile() ? part.length() : 0;
            if (done >= total) {
                FileUtils.delete(part);
                done = 0;
            }
            if (done > 0) {
                // what's there already: only the rest (same name = same version)
                response.close();
                response = QuickHttps.open(response.url.toString(), done, 5000, 30000);
                String range = response.headers.get("content-range");
                if (response.status != 206 || range == null || !range.startsWith("bytes "+done+"-") || !range.endsWith("/"+total)) {
                    response.close();
                    done = 0;
                    response = QuickHttps.open(GameSource.DEMO_DOWNLOAD, null, 5000, 30000);
                    if (response.status != 200 || response.length() != total) throw new IOException("factorio.com: answer "+response.status);
                }
            }

            // the archive and the game unpacked from it (about twice its size) must fit
            long needed = (total - done) + 2 * total;
            if (folder.getUsableSpace() < needed) {
                throw new IOException(String.format(Locale.ROOT, "not enough space: %.1f GB needed, %.1f GB free",
                    needed / 1e9, folder.getUsableSpace() / 1e9));
            }

            byte[] buffer = new byte[1 << 16];
            int shown = -1;
            try (java.io.OutputStream out = new java.io.FileOutputStream(part, done > 0)) {
                while (done < total) {
                    if (cancelled.get()) throw new DownloadCancelled();
                    int n = response.body.read(buffer);
                    if (n < 0) throw new IOException("the download stopped at "+done+" of "+total+" bytes; try again to go on");
                    out.write(buffer, 0, n);
                    done += n;
                    int percent = (int)(done * 100 / total);
                    if (percent != shown) {
                        shown = percent;
                        listener.onPercent(percent);
                    }
                }
            }
            FileUtils.delete(target);
            if (!part.renameTo(target)) throw new IOException("cannot rename "+part);
            return target;
        }
        finally {
            response.close();
        }
    }

    /** One-time copy of a game folder into the app's files, textures as they are (Settings can compress them later). */
    private static void copyGame(final MainActivity activity, DownloadProgressDialog screen, final File source) {
        final Paths paths = new Paths(activity);
        final DownloadProgressDialog dialog = progressScreen(activity, screen, R.string.copying_game);
        dialog.reserveLogos(new File(source, "data/space-age").isDirectory());
        prestartX(activity);
        // the logos first (16 frames of Space Age take a moment), beside the copy
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap logo = GameSource.loadLogo(new File(source, GameSource.LOGO));
            Bitmap[] spaceAge = GameSource.loadSpaceAgeFrames(new File(source, GameSource.SPACE_AGE_LOGO));
            activity.runOnUiThread(() -> dialog.setLogos(logo, spaceAge));
        });

        Executors.newSingleThreadExecutor().execute(() -> {
            File staging = new File(paths.getFiles(), "game.copying");
            FileUtils.delete(staging);
            GameCopier.Result result = GameCopier.copy(source, staging, GameCompression.ORIGINAL,
                (percent) -> activity.runOnUiThread(() -> dialog.setProgress(percent)));

            boolean ok = result.ok && install(paths, staging);
            if (!ok) FileUtils.delete(staging);
            activity.runOnUiThread(() -> finishInstall(activity, dialog, ok));
        });
    }

    /** Warm-up: the X server starts beside the copy, so the game screen isn't black for seconds after it. */
    private static void prestartX(final MainActivity activity) {
        final android.content.Context app = activity.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Session.INSTANCE.prestartX(app);
            }
            catch (Throwable e) {
                android.util.Log.w("Factory", "The X server didn't start early; it starts with the game", e);
            }
        });
    }

    /** Moves the ready game folder into place; saves that came with it go where Factorio writes its saves. */
    private static boolean install(Paths paths, File ready) {
        if (!new File(ready, "bin/arm64/factorio").isFile()) return false;
        FileUtils.delete(paths.getGame());
        if (!ready.renameTo(paths.getGame())) return false;
        File saves = new File(paths.getGame(), "saves");
        File gameSaves = new File(paths.getGameData(), "saves");
        if (saves.isDirectory() && !gameSaves.exists()) {
            paths.getGameData().mkdirs();
            if (!saves.renameTo(gameSaves)) FileUtils.copy(saves, gameSaves);
        }
        return true;
    }

    private static void finishInstall(MainActivity activity, DownloadProgressDialog dialog, boolean success) {
        if (!success) {
            dialog.close();
            AppUtils.showToast(activity, R.string.game_copy_failed);
            showGameSource(activity);
            return;
        }
        // a fresh copy has the original textures (Settings → texture compression)
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putInt(GameCompression.PREF_KEY, GameCompression.ORIGINAL).apply();
        dialog.close();
        launch(activity);
    }

    private static void launch(final MainActivity activity) {
        applyDisplayPrefs(activity);
        final Map<String, String> env = new HashMap<>();
        EnvVars envVars = new EnvVars(GameSettings.getEnvVars(activity));
        for (String name : envVars) env.put(name, envVars.get(name));
        // Settings → Benchmark: this start plays the menu scenes with the frame timer, then the report is shown
        List<String> args = Collections.emptyList();
        if (Benchmark.isPending(activity)) {
            try {
                env.putAll(Benchmark.prepare(activity));
                args = Benchmark.gameArguments(activity);
                AppUtils.showToast(activity, R.string.benchmark_started);
            }
            catch (Exception e) {
                Benchmark.clear(activity);
                AppUtils.showToast(activity, activity.getString(R.string.benchmark_failed, String.valueOf(e.getMessage())));
            }
        }
        else if (Benchmark.isRunning(activity)) Benchmark.clear(activity); // left over from a run the app didn't see end
        final List<String> gameArgs = args;

        // Gladio on the X server inside the app (r34 display): that screen starts the game once its renderer is up
        if (Drivers.INSTANCE.embeddedXServer(activity)) {
            Executors.newSingleThreadExecutor().execute(() -> {
                String failure = null;
                try {
                    Gladio.prepare(activity);
                }
                catch (Throwable e) {
                    failure = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String message = failure;
                activity.runOnUiThread(() -> {
                    if (message != null) {
                        activity.hideStartupCover();
                        new AlertDialog.Builder(activity)
                            .setTitle(R.string.game_exited_early)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                        return;
                    }
                    XServerGameActivity.gameEnvironment = env;
                    XServerGameActivity.gameArguments = gameArgs;
                    activity.startActivity(new Intent(activity, XServerGameActivity.class));
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                });
            });
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            String failure = null;
            try {
                // Gladio: its server runs in this process and must listen before the game asks for OpenGL
                if (Drivers.INSTANCE.gladio(activity)) Gladio.prepare(activity);
                Session.INSTANCE.start(activity, gameArgs, env);
            }
            catch (Throwable e) {
                android.util.Log.e("Factory", "The game didn't start", e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            final String message = failure;
            activity.runOnUiThread(() -> {
                if (message != null) {
                    activity.hideStartupCover();
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.game_exited_early)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                showGame(activity);
                activity.finish();
                activity.overridePendingTransition(0, 0);
            });
        });
    }

    public static void showGame(Activity activity) {
        if (XServerGameActivity.running) {
            activity.startActivity(new Intent(activity, XServerGameActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        activity.startActivity(new Intent(activity, com.termux.x11.MainActivity.class));
    }

    /**
     * Termux:X11's preferences (the app's default preferences): the X screen is the resolution from the settings;
     * the rest is set once so it can still be changed.
     */
    private static void applyDisplayPrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int[] size = GameSettings.getScreenSizeValues(context);
        int[] nativeSize = ScreenResolution.getNativeSize(context);
        SharedPreferences.Editor editor = prefs.edit()
            .putString("displayResolutionMode", "custom")
            .putString("displayResolutionCustom", size[0]+"x"+size[1])
            // the game's UI scale follows it (Session.uiScale): the UI keeps its size on screen at any resolution
            .putInt("displayScale", Math.max(100, Math.round(nativeSize[1] * 100f / size[1])));
        if (!prefs.getBoolean("factoryDisplayDefaults", false)) {
            editor.putString("forceOrientation", "landscape")
                .putBoolean("fullscreen", true)
                .putBoolean("hideCutout", true)
                .putBoolean("showAdditionalKbd", false)
                .putBoolean("additionalKbdVisible", false)
                // without it the phone's keyboard app takes a hardware keyboard's keys and types them as text
                .putBoolean("enforceCharBasedInput", true)
                .putString("screenIdleTimeout", "never")
                .putBoolean("factoryDisplayDefaults", true);
        }
        editor.apply();
    }

    /** The game died, usually before its window opened: the end of its log says why. */
    public static void showGameFailed(Activity activity, int status, boolean noWindow) {
        String tail = "";
        String log = FileUtils.readString(new File(new Paths(activity).getLogs(), "game.log"));
        if (log != null && !log.trim().isEmpty()) {
            String[] lines = log.trim().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) sb.append(lines[i].length() > 220 ? lines[i].substring(0, 220) : lines[i]).append('\n');
            tail = "\n\n"+sb;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.game_exited_early)
            .setMessage(activity.getString(R.string.game_exited_early_message, status)+tail)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
