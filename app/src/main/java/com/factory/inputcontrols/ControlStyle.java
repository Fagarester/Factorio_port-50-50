package com.factory.inputcontrols;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Look of the buttons: outline (the shape of a button), its color and opacity, color and opacity of the text
 * or icon inside, whether icons are tinted with the content color or shown in black and white, and a soft spot of
 * color under the icon (its color and strength).
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
    public Boolean iconFilter;        // true: icons are tinted with the content color (keeping their light and shade)
    public Boolean iconGray;          // true: icons in black and white (shades of grey); not together with iconFilter
    public Integer glowColor;         // 0xRRGGBB, the soft spot under the icon / text
    public Float glowStrength;        // 0..1, 0 = no spot

    public static ControlStyle defaults() {
        ControlStyle style = new ControlStyle();
        style.showOutline = true;
        style.outlineColor = 0xffffff;
        style.outlineAlpha = 1.0f;
        style.contentColor = 0xffffff;
        style.contentAlpha = 1.0f;
        style.iconFilter = true;
        style.iconGray = false;
        style.glowColor = 0x000000;
        style.glowStrength = 0f;
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
        result.iconGray = pick(overrides != null ? overrides.iconGray : null, common != null ? common.iconGray : null, defaults.iconGray);
        result.glowColor = pick(overrides != null ? overrides.glowColor : null, common != null ? common.glowColor : null, defaults.glowColor);
        result.glowStrength = pick(overrides != null ? overrides.glowStrength : null, common != null ? common.glowStrength : null, defaults.glowStrength);
        return result;
    }

    private static <T> T pick(T first, T second, T third) {
        return first != null ? first : (second != null ? second : third);
    }

    public boolean isEmpty() {
        return showOutline == null && outlineColor == null && outlineAlpha == null && contentColor == null && contentAlpha == null && iconFilter == null &&
            iconGray == null && glowColor == null && glowStrength == null;
    }

    public void clear() {
        showOutline = null;
        outlineColor = null;
        outlineAlpha = null;
        contentColor = null;
        contentAlpha = null;
        iconFilter = null;
        iconGray = null;
        glowColor = null;
        glowStrength = null;
    }

    public void copyFrom(ControlStyle other) {
        showOutline = other.showOutline;
        outlineColor = other.outlineColor;
        outlineAlpha = other.outlineAlpha;
        contentColor = other.contentColor;
        contentAlpha = other.contentAlpha;
        iconFilter = other.iconFilter;
        iconGray = other.iconGray;
        glowColor = other.glowColor;
        glowStrength = other.glowStrength;
    }

    public JSONObject toJSONObject() throws JSONException {
        JSONObject object = new JSONObject();
        if (showOutline != null) object.put("showOutline", showOutline);
        if (outlineColor != null) object.put("outlineColor", outlineColor);
        if (outlineAlpha != null) object.put("outlineAlpha", outlineAlpha.doubleValue());
        if (contentColor != null) object.put("contentColor", contentColor);
        if (contentAlpha != null) object.put("contentAlpha", contentAlpha.doubleValue());
        if (iconFilter != null) object.put("iconFilter", iconFilter);
        if (iconGray != null) object.put("iconGray", iconGray);
        if (glowColor != null) object.put("glowColor", glowColor);
        if (glowStrength != null) object.put("glowStrength", glowStrength.doubleValue());
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
        if (object.has("iconGray")) iconGray = object.optBoolean("iconGray", false);
        if (object.has("glowColor")) glowColor = object.optInt("glowColor", 0) & 0xffffff;
        if (object.has("glowStrength")) glowStrength = (float)object.optDouble("glowStrength", 0);
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
                case "iconGray":
                    style.iconGray = reader.nextBoolean();
                    break;
                case "glowColor":
                    style.glowColor = reader.nextInt() & 0xffffff;
                    break;
                case "glowStrength":
                    style.glowStrength = (float)reader.nextDouble();
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
