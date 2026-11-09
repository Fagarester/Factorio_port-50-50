package com.factory.inputcontrols;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every icon a control can show, by id, and the rows of the editor's icon picker:
 *   0 the app's own icons (assets/inputcontrols/icons, ids 0..63)
 *   1..GameIcons.ROWS the game's icons (GameIcons, 64..999), by kind
 *   last: the user's Factorio-style letters (1000 + 0..25 = A..Z) and the key words made of them (1030..)
 */
public final class IconCatalog {
    /** 0 the app's, 1..GameIcons.ROWS the game's, then the letters. */
    public static final int ROWS = GameIcons.ROWS + 2;
    public static final int LETTERS_ROW = GameIcons.ROWS + 1;
    private static final int FIRST_LETTER = 1000;
    private static final int FIRST_WORD = 1030;
    /** Key names drawn in the letters (assets/inputcontrols/words): never reorder, the ids are saved in profiles. */
    private static final String[] WORDS = {"ALT", "CTRL", "SHIFT", "ENTER", "ESC"};

    private IconCatalog() {}

    /** The ids of one picker row; the game's rows are empty until the game is copied. */
    public static int[] row(Context context, int row) {
        if (row == 0) {
            int[] ids = new int[0];
            try {
                String[] names = context.getAssets().list("inputcontrols/icons");
                ids = new int[names.length];
                for (int i = 0; i < names.length; i++) ids[i] = Integer.parseInt(names[i].substring(0, names[i].indexOf('.')));
            }
            catch (IOException | NumberFormatException e) {}
            Arrays.sort(ids);
            return ids;
        }
        if (row < LETTERS_ROW) return GameIcons.available(context, row);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < WORDS.length; i++) ids.add(FIRST_WORD + i);
        for (int i = 0; i < 26; i++) ids.add(FIRST_LETTER + i);
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** The key words are wider than high (the picker gives them a double cell). */
    public static boolean isWide(int id) {
        return id >= FIRST_WORD && id < FIRST_WORD + WORDS.length;
    }

    /** The game's items and alerts, the letters and words keep their colors; the rest takes the button's color. */
    public static boolean keepsColors(int id) {
        return GameIcons.isGameIcon(id) ? GameIcons.keepsColors(id) : id >= FIRST_LETTER;
    }

    /** The icon's picture, or null (no such id, or a game icon before the game is copied). */
    public static Bitmap load(Context context, int id) {
        if (GameIcons.isGameIcon(id)) return GameIcons.load(context, id);
        String asset;
        if (id >= 0 && id < GameIcons.FIRST_ID) asset = "inputcontrols/icons/" + id + ".png";
        else if (id >= FIRST_LETTER && id < FIRST_LETTER + 26) asset = "inputcontrols/letters/" + (char)('A' + id - FIRST_LETTER) + ".png";
        else if (id >= FIRST_WORD && id < FIRST_WORD + WORDS.length) asset = "inputcontrols/words/" + WORDS[id - FIRST_WORD] + ".png";
        else return null;
        try (InputStream in = context.getAssets().open(asset)) {
            return BitmapFactory.decodeStream(in);
        }
        catch (IOException e) {
            return null;
        }
    }
}
