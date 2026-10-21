package com.factorio.inputcontrols;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Look of the buttons: outline (the shape of a button), its color and opacity, color and opacity of the text
 * or icon inside, and whether icons are tinted with the content color.
 *
 * The profile owns the common style (all fields set). Every element can hold overrides: a field that is
 * null is taken from the profile style.
 */
public class ControlStyle {
    public Boolean showOutline;
    public Integer outlineColor;      // 0xRRGGBB
    public Float outlineAlpha;        // 0..1
    public Integer contentColor;      // 0xRRGGBB, text and icons
    public Float contentAlpha;        // 0..1
    public Boolean iconFilter;        // true: icons are drawn in the content color, false: original colors

    public static ControlStyle defaults() {
        ControlStyle style = new ControlStyle();
        style.showOutline = true;
        style.outlineColor = 0xffffff;
        style.outlineAlpha = 1.0f;
        style.contentColor = 0xffffff;
        style.contentAlpha = 1.0f;
        style.iconFilter = true;
        return style;
    }

    /** All fields of the result are set: overrides first, then the common style, then the defaults. */
    public static ControlStyle resolve(ControlStyle common, ControlStyle overrides) {
        ControlStyle defaults = defaults();
        ControlStyle result = new ControlStyle();
        result.showOutline = pick(overrides != null ? overrides.showOutline : null, common != null ? common.showOutline : null, defaults.showOutline);
        result.outlineColor = pick(overrides != null ? overrides.outlineColor : null, common != null ? common.outlineColor : null, defaults.outlineColor);
        result.outlineAlpha = pick(overrides != null ? overrides.outlineAlpha : null, common != null ? common.outlineAlpha : null, defaults.outlineAlpha);
        result.contentColor = pick(overrides != null ? overrides.contentColor : null, common != null ? common.contentColor : null, defaults.contentColor);
        result.contentAlpha = pick(overrides != null ? overrides.contentAlpha : null, common != null ? common.contentAlpha : null, defaults.contentAlpha);
        result.iconFilter = pick(overrides != null ? overrides.iconFilter : null, common != null ? common.iconFilter : null, defaults.iconFilter);
        return result;
    }

    private static <T> T pick(T first, T second, T third) {
        return first != null ? first : (second != null ? second : third);
    }

    public boolean isEmpty() {
        return showOutline == null && outlineColor == null && outlineAlpha == null && contentColor == null && contentAlpha == null && iconFilter == null;
    }

    public void clear() {
        showOutline = null;
        outlineColor = null;
        outlineAlpha = null;
        contentColor = null;
        contentAlpha = null;
        iconFilter = null;
    }

    public void copyFrom(ControlStyle other) {
        showOutline = other.showOutline;
        outlineColor = other.outlineColor;
        outlineAlpha = other.outlineAlpha;
        contentColor = other.contentColor;
        contentAlpha = other.contentAlpha;
        iconFilter = other.iconFilter;
    }

    public JSONObject toJSONObject() throws JSONException {
        JSONObject object = new JSONObject();
        if (showOutline != null) object.put("showOutline", showOutline);
        if (outlineColor != null) object.put("outlineColor", outlineColor);
        if (outlineAlpha != null) object.put("outlineAlpha", outlineAlpha.doubleValue());
        if (contentColor != null) object.put("contentColor", contentColor);
        if (contentAlpha != null) object.put("contentAlpha", contentAlpha.doubleValue());
        if (iconFilter != null) object.put("iconFilter", iconFilter);
        return object;
    }

    public void loadFrom(JSONObject object) {
        clear();
        if (object.has("showOutline")) showOutline = object.optBoolean("showOutline", true);
        if (object.has("outlineColor")) outlineColor = object.optInt("outlineColor", 0xffffff) & 0xffffff;
        if (object.has("outlineAlpha")) outlineAlpha = (float)object.optDouble("outlineAlpha", 1.0);
        if (object.has("contentColor")) contentColor = object.optInt("contentColor", 0xffffff) & 0xffffff;
        if (object.has("contentAlpha")) contentAlpha = (float)object.optDouble("contentAlpha", 1.0);
        if (object.has("iconFilter")) iconFilter = object.optBoolean("iconFilter", true);
    }

    /** Streaming variant used by the profile reader. */
    public static ControlStyle read(JsonReader reader) throws IOException {
        ControlStyle style = new ControlStyle();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.skipValue();
                continue;
            }
            switch (name) {
                case "showOutline":
                    style.showOutline = reader.nextBoolean();
                    break;
                case "outlineColor":
                    style.outlineColor = reader.nextInt() & 0xffffff;
                    break;
                case "outlineAlpha":
                    style.outlineAlpha = (float)reader.nextDouble();
                    break;
                case "contentColor":
                    style.contentColor = reader.nextInt() & 0xffffff;
                    break;
                case "contentAlpha":
                    style.contentAlpha = (float)reader.nextDouble();
                    break;
                case "iconFilter":
                    style.iconFilter = reader.nextBoolean();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return style;
    }
}
