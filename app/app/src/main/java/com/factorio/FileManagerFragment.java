package com.factorio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;

import com.factorio.contentdialog.ContentDialog;
import com.factorio.core.AppUtils;
import com.factorio.core.DriverPackages;
import com.factorio.core.FileUtils;
import com.factorio.xenvironment.RootFS;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small file manager with two roots only: the game's home folder (copied game, mods, config, saves)
 * and the Download folder. Navigation can never leave the selected root.
 */
public class FileManagerFragment extends Fragment {
    private static final int REQUEST_ADD_FILES = 4101;
    private static final long MAX_EDIT_SIZE = 512 * 1024;
    private static final List<String> EDITABLE = Arrays.asList("ini", "json", "cfg", "txt", "lua", "log", "md", "xml", "csv", "conf", "properties", "sh", "toml", "yml", "yaml", "locale");

    private static class Entry {
        final File file;
        final boolean parent;

        Entry(File file, boolean parent) {
            this.file = file;
            this.parent = parent;
        }
    }

    private File gameRoot;
    private File downloadRoot;
    private File root;
    private File current;
    private String rootLabel = "";

    private ListView listView;
    private TextView pathView;
    private TextView emptyView;
    private Button pasteButton;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private EntryAdapter adapter;

    // clipboard: survives switching between the two roots while this screen is open
    private File clipFile;
    private boolean clipMove;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private class EntryAdapter extends ArrayAdapter<Entry> {
        EntryAdapter(Context context, List<Entry> items) {
            super(context, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = convertView != null ? convertView : LayoutInflater.from(getContext()).inflate(R.layout.file_manager_item, parent, false);
            Entry entry = getItem(position);
            TextView name = view.findViewById(R.id.TVFileName);
            TextView info = view.findViewById(R.id.TVFileInfo);
            if (entry.parent) {
                name.setText("\u2B06  ..");
                info.setText("");
            }
            else {
                boolean directory = entry.file.isDirectory();
                name.setText((directory ? "\uD83D\uDCC1  " : "\uD83D\uDCC4  ") + entry.file.getName());
                String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.file.lastModified()));
                info.setText(directory ? date : Formatter.formatFileSize(getContext(), entry.file.length()) + "   " + date);
            }
            return view;
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.file_manager_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Context context = getContext();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.files);

        gameRoot = new File(RootFS.find(context).getRootDir(), RootFS.HOME_PATH);
        downloadRoot = new File(AppUtils.DIRECTORY_DOWNLOADS);

        listView = view.findViewById(R.id.LVFiles);
        pathView = view.findViewById(R.id.TVPath);
        emptyView = view.findViewById(R.id.TVEmpty);
        pasteButton = view.findViewById(R.id.BTPaste);
        adapter = new EntryAdapter(context, entries);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, itemView, position, id) -> {
            Entry entry = entries.get(position);
            if (entry.parent) goUp();
            else if (entry.file.isDirectory()) openDirectory(entry.file);
            else if (isEditable(entry.file)) editFile(entry.file);
            else showActions(itemView, entry);
        });
        listView.setOnItemLongClickListener((parent, itemView, position, id) -> {
            Entry entry = entries.get(position);
            if (!entry.parent) showActions(itemView, entry);
            return true;
        });

        view.findViewById(R.id.BTRootGame).setOnClickListener((v) -> selectRoot(gameRoot, getString(R.string.files_root_game)));
        view.findViewById(R.id.BTRootDownload).setOnClickListener((v) -> selectRoot(downloadRoot, getString(R.string.files_root_download)));
        pasteButton.setOnClickListener((v) -> paste());
        view.findViewById(R.id.BTNewFolder).setOnClickListener((v) -> newFolder());
        view.findViewById(R.id.BTAddFiles).setOnClickListener((v) -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_ADD_FILES);
        });

        selectRoot(gameRoot, getString(R.string.files_root_game));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    // ------------------------------------------------------------------ navigation

    private static boolean isInside(File base, File file) {
        try {
            String basePath = base.getCanonicalPath();
            String path = file.getCanonicalPath();
            return path.equals(basePath) || path.startsWith(basePath + File.separator);
        }
        catch (IOException e) {
            return false;
        }
    }

    private boolean isAtRoot() {
        return current == null || current.getPath().equals(root.getPath());
    }

    private void selectRoot(File newRoot, String label) {
        root = newRoot;
        rootLabel = label;
        current = newRoot;
        refresh();
    }

    private void openDirectory(File directory) {
        if (!isInside(root, directory)) return;
        current = directory;
        refresh();
    }

    private void goUp() {
        if (isAtRoot()) return;
        File parent = current.getParentFile();
        if (parent == null || !isInside(root, parent)) parent = root;
        current = parent;
        refresh();
    }

    private void refresh() {
        entries.clear();
        File[] files = current.isDirectory() ? current.listFiles() : null;
        if (!isAtRoot()) entries.add(new Entry(current, true));
        if (files != null) {
            ArrayList<File> sorted = new ArrayList<>(Arrays.asList(files));
            Collections.sort(sorted, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File file : sorted) entries.add(new Entry(file, false));
        }
        adapter.notifyDataSetChanged();

        String relative = current.getPath().substring(Math.min(current.getPath().length(), root.getPath().length()));
        pathView.setText(rootLabel + (relative.isEmpty() ? "" : relative));

        boolean missing = !current.isDirectory();
        boolean empty = files == null || files.length == 0;
        emptyView.setVisibility(missing || empty ? View.VISIBLE : View.GONE);
        emptyView.setText(missing && root == gameRoot ? R.string.files_game_missing : R.string.files_empty);
        pasteButton.setVisibility(clipFile != null ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ actions

    private static boolean isEditable(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || file.length() > MAX_EDIT_SIZE) return false;
        return EDITABLE.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isValidName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    private void showActions(View anchor, final Entry entry) {
        PopupMenu popup = new PopupMenu(getContext(), anchor);
        if (!entry.file.isDirectory() && isEditable(entry.file)) popup.getMenu().add(0, 1, 0, R.string.files_edit);
        popup.getMenu().add(0, 2, 1, R.string.files_copy);
        popup.getMenu().add(0, 3, 2, R.string.files_move);
        popup.getMenu().add(0, 4, 3, R.string.files_rename);
        popup.getMenu().add(0, 5, 4, R.string.files_delete);
        popup.setOnMenuItemClickListener((item) -> {
            switch (item.getItemId()) {
                case 1: editFile(entry.file); break;
                case 2: setClipboard(entry.file, false); break;
                case 3: setClipboard(entry.file, true); break;
                case 4: rename(entry.file); break;
                case 5: delete(entry.file); break;
            }
            return true;
        });
        popup.show();
    }

    private void setClipboard(File file, boolean move) {
        clipFile = file;
        clipMove = move;
        pasteButton.setVisibility(View.VISIBLE);
        AppUtils.showToast(getContext(), getString(R.string.files_in_clipboard, file.getName()));
    }

    private void editFile(final File file) {
        if (!isEditable(file)) {
            AppUtils.showToast(getContext(), R.string.files_too_big_to_edit);
            return;
        }
        final ContentDialog dialog = new ContentDialog(getContext(), R.layout.file_editor_dialog);
        dialog.setTitle(file.getName());
        final EditText editor = dialog.findViewById(R.id.ETFileContent);
        editor.setText(FileUtils.readString(file));
        dialog.setOnConfirmCallback(() -> {
            boolean saved = FileUtils.writeString(file, editor.getText().toString());
            AppUtils.showToast(getContext(), saved ? R.string.files_saved : R.string.files_save_failed);
            refresh();
        });
        dialog.show();
    }

    private void rename(final File file) {
        ContentDialog.prompt(getContext(), R.string.files_rename, file.getName(), (name) -> {
            if (!isValidName(name)) {
                AppUtils.showToast(getContext(), R.string.files_bad_name);
                return;
            }
            File target = new File(file.getParentFile(), name);
            if (target.exists()) {
                AppUtils.showToast(getContext(), R.string.files_name_exists);
                return;
            }
            if (!file.renameTo(target)) AppUtils.showToast(getContext(), R.string.files_operation_failed);
            refresh();
        });
    }

    private void delete(final File file) {
        ContentDialog dialog = new ContentDialog(getContext());
        dialog.setCancelable(false);
        dialog.setMessage(getString(R.string.files_delete_confirm, file.getName()), R.drawable.content_dialog_type_confirm);
        dialog.setOnConfirmCallback(() -> runBusy(() -> FileUtils.delete(file)));
        dialog.show();
    }

    private void newFolder() {
        ContentDialog.prompt(getContext(), R.string.files_new_folder, "", (name) -> {
            if (!isValidName(name)) {
                AppUtils.showToast(getContext(), R.string.files_bad_name);
                return;
            }
            File folder = new File(current, name);
            if (folder.exists()) AppUtils.showToast(getContext(), R.string.files_name_exists);
            else if (!folder.mkdirs()) AppUtils.showToast(getContext(), R.string.files_operation_failed);
            refresh();
        });
    }

    private void paste() {
        if (clipFile == null || !clipFile.exists()) {
            clipFile = null;
            refresh();
            return;
        }
        final File source = clipFile;
        final boolean move = clipMove;
        final File target = new File(current, source.getName());
        if (!isInside(root, target)) return;

        if (source.isDirectory() && isInside(source, target)) {
            AppUtils.showToast(getContext(), R.string.files_paste_into_itself);
            return;
        }
        if (target.exists()) {
            ContentDialog dialog = new ContentDialog(getContext());
            dialog.setCancelable(false);
            dialog.setMessage(getString(R.string.files_overwrite_confirm, source.getName()), R.drawable.content_dialog_type_confirm);
            dialog.setOnConfirmCallback(() -> runPaste(source, target, move));
            dialog.show();
        }
        else runPaste(source, target, move);
    }

    private void runPaste(final File source, final File target, final boolean move) {
        runBusy(() -> {
            if (target.exists() && !target.getPath().equals(source.getPath())) FileUtils.delete(target);
            if (target.getPath().equals(source.getPath())) return;
            boolean done = move && source.renameTo(target);
            if (!done) {
                done = FileUtils.copy(source, target);
                if (done && move) FileUtils.delete(source);
            }
            if (!done) showToastLater(R.string.files_operation_failed);
            else if (move) clipFile = null;
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ADD_FILES || resultCode != Activity.RESULT_OK || data == null) return;

        final ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        }
        else if (data.getData() != null) uris.add(data.getData());

        final Context context = getContext();
        final File destination = current;
        runBusy(() -> {
            boolean failed = false;
            for (Uri uri : uris) {
                String name = DriverPackages.queryDisplayName(context, uri);
                if (name == null || !isValidName(name)) name = "file-" + System.currentTimeMillis();
                File target = new File(destination, name);
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(target)) {
                    if (in == null) throw new IOException("Cannot open "+uri);
                    byte[] buffer = new byte[1 << 16];
                    int length;
                    while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
                }
                catch (IOException e) {
                    failed = true;
                }
            }
            if (failed) showToastLater(R.string.files_operation_failed);
        });
    }

    // ------------------------------------------------------------------ helpers

    private void showToastLater(final int resId) {
        final Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(() -> AppUtils.showToast(activity, resId));
    }

    /** Runs the work in the background behind a "please wait" dialog and refreshes the list afterwards. */
    private void runBusy(final Runnable work) {
        final Activity activity = getActivity();
        if (activity == null) return;
        final ContentDialog dialog = new ContentDialog(activity);
        dialog.setCancelable(false);
        dialog.setMessage(R.string.files_busy);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        dialog.show();

        worker.execute(() -> {
            try {
                work.run();
            }
            finally {
                activity.runOnUiThread(() -> {
                    try {
                        dialog.dismiss();
                    }
                    catch (Exception e) {}
                    if (isAdded()) refresh();
                });
            }
        });
    }
}
