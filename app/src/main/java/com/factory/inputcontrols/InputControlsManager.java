package com.factory.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.JsonReader;

import androidx.preference.PreferenceManager;

import com.factory.core.AppUtils;
import com.factory.core.FileUtils;
import com.factory.core.StreamUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

public class InputControlsManager {
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        if (!profilesLoaded) loadProfiles();
        return profiles;
    }

    /**
     * Only one bundled profile is left ("control", formerly "RTS"). On devices that already have the old
     * bundled profiles they are removed once: the template, the virtual gamepad and FPS (only when they still
     * carry the original id and name, so profiles made by the user are never touched) and RTS is renamed.
     */
    private void removeObsoleteBundledProfiles(File profilesDir, SharedPreferences preferences) {
        if (preferences.getBoolean("inputcontrols_bundled_cleanup_v1", false)) return;

        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                if (profile == null) continue;

                String name = profile.getName();
                boolean obsolete = (profile.id == 2 && "Template (12 buttons)".equals(name)) ||
                                   (profile.id == 3 && "Virtual Gamepad".equals(name)) ||
                                   (profile.id == 4 && "FPS".equals(name));
                if (obsolete) FileUtils.delete(file);
                else if (profile.id == 1 && "RTS".equals(name)) {
                    profile.setName("control");
                    profile.save();
                }
            }
        }
        preferences.edit().putBoolean("inputcontrols_bundled_cleanup_v1", true).apply();
    }

    /**
     * A bundled profile new in this version ("control 2", "control 3"): added unless the player already has one of that
     * name (imported, for example). Its id may be taken by a profile of the player's: then it gets a free one.
     */
    private void addBundledProfile(AssetManager assetManager, String assetPath, ControlsProfile origin) {
        File[] files = getProfilesDir(context).listFiles();   // again: an earlier one may just have been added
        if (files == null) return;
        int maxId = 0;
        boolean idTaken = false;
        for (File file : files) {
            ControlsProfile existing = loadProfile(context, file);
            if (existing == null) continue;
            if (existing.getName().equals(origin.getName())) return;
            if (existing.id == origin.id) idTaken = true;
            maxId = Math.max(maxId, existing.id);
        }
        int id = idTaken ? maxId + 1 : origin.id;
        try (java.io.InputStream in = assetManager.open(assetPath)) {
            org.json.JSONObject data = new org.json.JSONObject(new String(StreamUtils.copyToByteArray(in), java.nio.charset.StandardCharsets.UTF_8));
            data.put("id", id);
            FileUtils.writeString(ControlsProfile.getProfileFile(context, id), data.toString());
        }
        catch (Exception e) {
            android.util.Log.w("InputControls", "Couldn't add the bundled profile "+assetPath, e);
        }
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (FileUtils.isEmpty(profilesDir)) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        removeObsoleteBundledProfiles(profilesDir, preferences);

        int newVersion = AppUtils.getVersionCode(context);
        int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
        if (oldVersion == newVersion) return;
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply();

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (originProfile.id == targetProfile.id && originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    FileUtils.copy(context, assetPath, targetFile);
                }
                else addBundledProfile(assetManager, assetPath, originProfile);
            }
        }
        catch (IOException e) {}
    }

    public void loadProfiles() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("id") || !data.has("name")) return null;
            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            FileUtils.writeString(newFile, data.toString());
            ControlsProfile newProfile = loadProfile(context, newFile);

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile.getName().equals(newProfile.getName())) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloadsDir, "Factorio/profiles/"+profile.getName()+".icp");
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            boolean disableMouseInput = false;
            ControlStyle style = null;
            int fieldsRead = 0;
            final byte numFieldsToBreak = 4;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                    fieldsRead++;
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                    fieldsRead++;
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float)reader.nextDouble();
                    fieldsRead++;
                }
                else if (name.equals("disableMouseInput")) {
                    disableMouseInput = reader.nextBoolean();
                    fieldsRead++;
                }
                else if (name.equals("style")) {
                    style = ControlStyle.read(reader);
                }
                else {
                    if (fieldsRead == numFieldsToBreak) break;
                    reader.skipValue();
                }
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(cursorSpeed);
            profile.setDisableMouseInput(disableMouseInput);
            if (style != null) profile.setStyle(style);
            return profile;
        }
        catch (IOException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
