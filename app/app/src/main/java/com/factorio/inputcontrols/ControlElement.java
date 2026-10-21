package com.factorio.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;

import androidx.core.graphics.ColorUtils;

import com.factorio.core.Bitmask;
import com.factorio.core.CubicBezierInterpolator;
import com.factorio.math.Mathf;
import com.factorio.widget.InputControlsView;
import com.factorio.widget.TouchpadView;
import com.factorio.inputcontrols.ScrollWheel.Style;
import com.factorio.inputcontrols.SideDrawer.Layout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 3.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public static final int FLAG_SELECTED = 1<<0;
    public static final int FLAG_PRESSED = 1<<1;
    public static final int FLAG_VISIBLE = 1<<2;
    public static final int FLAG_TOGGLE_SWITCH = 1<<3;
    public static final int FLAG_BOUNDING_BOX_NEEDS_UPDATE = 1<<4;
    public static final int FLAG_MOUSE_MOVE_MODE = 1<<5;
    public static final int FLAG_DRAWER_AUTOCLOSE = 1<<6;
    public static final long RADIAL_HYBRID_TAP_MILLISECONDS = 250;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, RADIAL_MENU, SCROLL_WHEEL, SIDE_DRAWER, CUSTOM_MENU;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }
    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }
    public enum RadialStyle {
        PIE, BUBBLES;

        public static String[] names() {
            RadialStyle[] values = values();
            String[] names = new String[values.length];
            for (int i = 0; i < values.length; i++) names[i] = values[i].name();
            return names;
        }
    }
    public enum RadialTrigger {
        TAP, HOLD, HYBRID;

        public static String[] names() {
            RadialTrigger[] values = values();
            String[] names = new String[values.length];
            for (int i = 0; i < values.length; i++) names[i] = values[i].name();
            return names;
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private float opacity = 1.0f;
    private short x;
    private short y;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private final Bitmask propertyFlags = new Bitmask(new int[]{FLAG_BOUNDING_BOX_NEEDS_UPDATE});
    private String text = "";
    private byte iconId;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;
    private Path[] paths;
    private Style scrollStyle = Style.STRIP;
    private ScrollWheel scrollWheel;
    private RadialStyle radialStyle = RadialStyle.PIE;
    private RadialTrigger radialTrigger = RadialTrigger.TAP;
    private int radialHover = -1;
    private int radialParts = 1;
    private int radialPartMask = 1;
    private byte[] radialPartCounts = {3};
    private float radialRotation = 0;
    private float radialDistance = 1.0f;
    private long radialDownTime;
    private boolean radialOpenedOnTouch;
    private boolean radialClosedOnTouch;
    private byte drawerSide = SideDrawer.SIDE_LEFT;
    private Layout drawerLayout = Layout.VERTICAL;
    private int gridColumns = 3;
    private int gridRows = 3;
    private boolean gridByRows = false;
    private SideDrawer drawer;
    private CustomMenu customMenu;
    private final ControlStyle styleOverrides = new ControlStyle();
    private ControlStyle resolvedStyle;
    private android.graphics.PorterDuffColorFilter iconFilterCache;
    private int iconFilterCacheColor;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        bindings = new Binding[4];
        setBinding(Binding.NONE);
        scroller = null;
        if (scrollWheel != null) scrollWheel.stopFling();
        scrollWheel = null;
        if (drawer != null) drawer.reset();
        drawer = null;
        if (customMenu != null) customMenu.reset();
        customMenu = null;
        text = "";

        switch (type) {
            case D_PAD:
            case STICK:
                bindings[0] = Binding.KEY_W;
                bindings[1] = Binding.KEY_D;
                bindings[2] = Binding.KEY_S;
                bindings[3] = Binding.KEY_A;
                break;
            case TRACKPAD:
                bindings[0] = Binding.MOUSE_MOVE_UP;
                bindings[1] = Binding.MOUSE_MOVE_RIGHT;
                bindings[2] = Binding.MOUSE_MOVE_DOWN;
                bindings[3] = Binding.MOUSE_MOVE_LEFT;
                break;
            case RANGE_BUTTON:
                scroller = new RangeScroller(inputControlsView, this);
                break;
            case RADIAL_MENU:
                radialParts = 1;
                radialPartMask = 1;
                radialPartCounts = new byte[]{3};
                radialRotation = 0;
                radialDistance = 1.0f;
                setBindingCount(3);
                break;
            case SCROLL_WHEEL:
                bindings = new Binding[]{Binding.MOUSE_SCROLL_UP, Binding.MOUSE_SCROLL_DOWN};
                states = new boolean[2];
                orientation = 1;
                scrollWheel = new ScrollWheel(inputControlsView, this);
                break;
            case SIDE_DRAWER:
                bindings = new Binding[4];
                Arrays.fill(bindings, Binding.NONE);
                states = new boolean[4];
                drawer = new SideDrawer(inputControlsView, this);
                break;
            case CUSTOM_MENU:
                shape = Shape.CIRCLE;
                text = "\u2261";
                bindings = new Binding[]{Binding.NONE};
                states = new boolean[1];
                propertyFlags.set(FLAG_DRAWER_AUTOCLOSE, true);
                customMenu = new CustomMenu(inputControlsView, this);
                customMenu.createDefaultNodes();
                break;
        }

        iconId = 0;
        range = null;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public byte getBindingCount() {
        return (byte)bindings.length;
    }

    public byte getFirstBindingIndex() {
        for (byte i = 0; i < bindings.length; i++) if (bindings[i] != Binding.NONE) return i;
        return 0;
    }

    public void setBindingCount(int bindingCount) {
        Binding[] newBindings = new Binding[bindingCount];
        Arrays.fill(newBindings, Binding.NONE);
        System.arraycopy(bindings, 0, newBindings, 0, Math.min(bindings.length, bindingCount));
        bindings = newBindings;
        states = new boolean[bindingCount];
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public boolean isToggleSwitch() {
        return propertyFlags.isSet(FLAG_TOGGLE_SWITCH);
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        propertyFlags.set(FLAG_TOGGLE_SWITCH, toggleSwitch);
    }

    public boolean isMouseMoveMode() {
        return propertyFlags.isSet(FLAG_MOUSE_MOVE_MODE);
    }

    public void setMouseMoveMode(boolean mouseMoveMode) {
        propertyFlags.set(FLAG_MOUSE_MOVE_MODE, mouseMoveMode);
    }

    /** Look overrides of this element; empty fields use the common style of the profile. */
    public ControlStyle getStyle() {
        return styleOverrides;
    }

    private ControlStyle style() {
        if (resolvedStyle != null) return resolvedStyle;
        ControlsProfile profile = inputControlsView.getProfile();
        return ControlStyle.resolve(profile != null ? profile.getStyle() : null, styleOverrides);
    }

    private int styledColor(int rgb, float alpha) {
        float opacity = inputControlsView.isEditMode() ? Math.max(0.15f, this.opacity) : this.opacity;
        float total = Mathf.clamp(alpha * opacity * inputControlsView.getOverlayOpacity(), 0, 1);
        return Color.argb((int)(total * 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    public boolean isOutlineVisible() {
        return style().showOutline;
    }

    /** Color of the shape of a button. A hidden outline stays faintly visible in the editor so it can be found. */
    public int getOutlineColor() {
        ControlStyle s = style();
        float alpha = s.outlineAlpha;
        if (!s.showOutline) alpha = inputControlsView.isEditMode() ? 0.3f : 0.0f;
        return styledColor(s.outlineColor, alpha);
    }

    /** Color of the text / icon inside a button. */
    public int getContentColor() {
        ControlStyle s = style();
        return styledColor(s.contentColor, s.contentAlpha);
    }

    /** Content color while the button is pressed (the shape is filled then, so the content gets dark). */
    public int getPressedContentColor() {
        ControlStyle s = style();
        if (s.showOutline) return getDarkColor();
        return styledColor(s.contentColor, s.contentAlpha * 0.6f);
    }

    public boolean isIconFilterEnabled() {
        return style().iconFilter;
    }

    public Style getScrollStyle() {
        return scrollStyle;
    }

    public void setScrollStyle(Style scrollStyle) {
        this.scrollStyle = scrollStyle;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public RadialStyle getRadialStyle() {
        return radialStyle;
    }

    public void setRadialStyle(RadialStyle radialStyle) {
        this.radialStyle = radialStyle;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public RadialTrigger getRadialTrigger() {
        return radialTrigger;
    }

    public void setRadialTrigger(RadialTrigger radialTrigger) {
        this.radialTrigger = radialTrigger;
    }

    public byte getDrawerSide() {
        return drawerSide;
    }

    public void setDrawerSide(byte drawerSide) {
        this.drawerSide = drawerSide;
    }

    public Layout getDrawerLayout() {
        return drawerLayout;
    }

    public void setDrawerLayout(Layout drawerLayout) {
        this.drawerLayout = drawerLayout;
    }

    public int getGridColumns() {
        return gridColumns;
    }

    /** Sets the number of columns of the grid; the rows are then calculated from the number of buttons. */
    public void setGridColumns(int gridColumns) {
        this.gridColumns = Math.max(1, gridColumns);
        this.gridByRows = false;
    }

    public int getGridRows() {
        return gridRows;
    }

    /** Sets the number of rows of the grid; the columns are then calculated from the number of buttons. */
    public void setGridRows(int gridRows) {
        this.gridRows = Math.max(1, gridRows);
        this.gridByRows = true;
    }

    public boolean isGridByRows() {
        return gridByRows;
    }

    public void setGridByRows(boolean gridByRows) {
        this.gridByRows = gridByRows;
    }

    /** Actual {columns, rows} of the drawer grid for the current number of buttons. */
    public int[] getGridSize() {
        return SideDrawer.computeGrid(getBindingCount(), gridColumns, gridRows, gridByRows);
    }

    public boolean isDrawerAutoClose() {
        return propertyFlags.isSet(FLAG_DRAWER_AUTOCLOSE);
    }

    public void setDrawerAutoClose(boolean autoClose) {
        propertyFlags.set(FLAG_DRAWER_AUTOCLOSE, autoClose);
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
        }
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public boolean isSelected() {
        return propertyFlags.isSet(FLAG_SELECTED);
    }

    public CustomMenu getCustomMenu() {
        return customMenu;
    }

    void setRootPressed(boolean pressed) {
        propertyFlags.set(FLAG_PRESSED, pressed);
        inputControlsView.invalidate();
    }

    boolean containsRootPoint(float x, float y) {
        return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    public void setSelected(boolean selected) {
        if (type == Type.CUSTOM_MENU && customMenu != null && !selected) customMenu.selectNode(null);
        if (type == Type.RADIAL_MENU) propertyFlags.set(FLAG_VISIBLE, selected);
        propertyFlags.set(FLAG_SELECTED, selected);
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte)iconId;
    }

    public Rect getBoundingBox() {
        if (type == Type.SIDE_DRAWER && drawer != null) return drawer.getBoundingBox();
        if (propertyFlags.isSet(FLAG_BOUNDING_BOX_NEEDS_UPDATE)) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
            case CUSTOM_MENU:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            }
            case TRACKPAD:
            case STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
            case RADIAL_MENU:
                halfWidth = snappingSize * 3;
                halfHeight = snappingSize * 3;
                break;
            case SCROLL_WHEEL: {
                int[] extents = ScrollWheel.getHalfExtents(scrollStyle, orientation == 1, snappingSize);
                halfWidth = extents[0];
                halfHeight = extents[1];
                break;
            }
            case SIDE_DRAWER:
                halfWidth = snappingSize * 3;
                halfHeight = snappingSize * 3;
                break;
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        propertyFlags.unset(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
        paths = null;
        return boundingBox;
    }

    String getBindingTextAt(int index) {
        return getBindingText(getBindingAt(index));
    }

    static String getBindingText(Binding binding) {
        String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
        if (text.length() > 7) {
            String[] parts = text.split(" ");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) sb.append(part.charAt(0));
            return (binding.isMouse() ? "M" : "")+sb;
        }
        else return text;
    }

    private String getDisplayText() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        else {
            if (type == Type.BUTTON) {
                StringBuilder sb = new StringBuilder();
                for (byte i = 0; i < bindings.length; i++) {
                    if (bindings[i] != Binding.NONE) {
                        if (sb.length() > 0) sb.append("+");
                        sb.append(getBindingTextAt(i));
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }

            return getBindingTextAt(0);
        }
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static Binding getRangeBindingForIndex(Range range, int index) {
        switch (range) {
            case FROM_A_TO_Z:
                return Binding.valueOf("KEY_"+((char)(65 + index)));
            case FROM_0_TO_9:
                return Binding.valueOf("KEY_"+((index + 1) % 10));
            case FROM_F1_TO_F12:
                return Binding.valueOf("KEY_F"+(index + 1));
            case FROM_NP0_TO_NP9:
                return Binding.valueOf("KEY_KP_"+((index + 1) % 10));
            default:
                return Binding.NONE;
        }
    }

    private static String getRangeTextForIndex(Range range, int index) {
        switch (range) {
            case FROM_A_TO_Z:
                return String.valueOf((char)(65 + index));
            case FROM_0_TO_9:
                return String.valueOf((index + 1) % 10);
            case FROM_F1_TO_F12:
                return  "F"+(index + 1);
            case FROM_NP0_TO_NP9:
                return  "NP"+((index + 1) % 10);
            default:
                return "";
        }
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        ControlsProfile styleProfile = inputControlsView.getProfile();
        resolvedStyle = ControlStyle.resolve(styleProfile != null ? styleProfile.getStyle() : null, styleOverrides);
        int lightColor = (type == Type.BUTTON || type == Type.CUSTOM_MENU) ? getOutlineColor() : getLightColor();

        paint.setColor(propertyFlags.isSet(FLAG_SELECTED) ? getHighlightColor() : lightColor);
        paint.setStyle(Paint.Style.STROKE);
        float strokeWidth = snappingSize * 0.25f;
        paint.setStrokeWidth(strokeWidth);
        Rect boundingBox = getBoundingBox();
        boolean rootLayerSaved = false;

        switch (type) {
            case CUSTOM_MENU:
                if (customMenu != null) {
                    int rootColor = paint.getColor();
                    customMenu.draw(canvas);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(strokeWidth);
                    paint.setColor(rootColor);

                    float rootFade = customMenu.getRootFade();
                    if (rootFade <= 0.01f) break;
                    if (rootFade < 0.99f) {
                        float margin = snappingSize * 2;
                        canvas.saveLayerAlpha(boundingBox.left - margin, boundingBox.top - margin, boundingBox.right + margin, boundingBox.bottom + margin, (int)(rootFade * 255));
                        rootLayerSaved = true;
                    }
                }
                // the root button is drawn like a normal button, on top of its buttons
            case BUTTON: {
                if (propertyFlags.isSet(FLAG_PRESSED)) paint.setStyle(Paint.Style.FILL);

                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();

                switch (shape) {
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                        break;
                    case RECT:
                        canvas.drawRect(boundingBox, paint);
                        break;
                    case ROUND_RECT: {
                        float radius = boundingBox.height() * 0.5f;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        break;
                    }
                    case SQUARE: {
                        float radius = snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        break;
                    }
                }

                if (iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, true);
                }
                else {
                    String text = getDisplayText();
                    paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(propertyFlags.isSet(FLAG_PRESSED) ? getPressedContentColor() : getContentColor());
                    canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                }
                break;
            }
            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float offsetX = snappingSize * 2 * scale;
                float offsetY = snappingSize * 3 * scale;
                float start = snappingSize * scale;

                if (paths == null) {
                    Path path = new Path();
                    path.moveTo(cx, cy - start);
                    path.lineTo(cx - offsetX, cy - offsetY);
                    path.lineTo(cx - offsetX, boundingBox.top);
                    path.lineTo(cx + offsetX, boundingBox.top);
                    path.lineTo(cx + offsetX, cy - offsetY);
                    path.close();

                    path.moveTo(cx - start, cy);
                    path.lineTo(cx - offsetY, cy - offsetX);
                    path.lineTo(boundingBox.left, cy - offsetX);
                    path.lineTo(boundingBox.left, cy + offsetX);
                    path.lineTo(cx - offsetY, cy + offsetX);
                    path.close();

                    path.moveTo(cx, cy + start);
                    path.lineTo(cx - offsetX, cy + offsetY);
                    path.lineTo(cx - offsetX, boundingBox.bottom);
                    path.lineTo(cx + offsetX, boundingBox.bottom);
                    path.lineTo(cx + offsetX, cy + offsetY);
                    path.close();

                    path.moveTo(cx + start, cy);
                    path.lineTo(cx + offsetY, cy - offsetX);
                    path.lineTo(boundingBox.right, cy - offsetX);
                    path.lineTo(boundingBox.right, cy + offsetX);
                    path.lineTo(cx + offsetY, cy + offsetX);
                    path.close();
                    paths = new Path[]{path};
                }

                canvas.drawPath(paths[0], paint);
                break;
            }
            case RANGE_BUTTON: {
                Range range = getRange();
                int oldColor = paint.getColor();
                int darkColor = getDarkColor();

                float radius = snappingSize * 0.75f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Binding selectedBinding = scroller.getBinding();

                boolean wasPressed = false;

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left;
                    canvas.drawRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    if (paths == null) {
                        Path path = new Path();
                        path.addRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                        paths = new Path[]{path};
                    }

                    canvas.save();
                    canvas.clipPath(paths[0]);
                    startX -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);
                        boolean pressed = propertyFlags.isSet(FLAG_PRESSED) && selectedBinding == getRangeBindingForIndex(range, index);

                        if (startX > boundingBox.left && startX  < boundingBox.right && !pressed && !wasPressed) {
                            canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        }
                        String text = getRangeTextForIndex(range, index);

                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);

                            if (pressed) {
                                paint.setColor(lightColor);
                                canvas.drawRect(startX, lineTop, startX + elementSize, lineBottom, paint);
                            }

                            paint.setColor(pressed ? darkColor : lightColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }

                        startX += elementSize;
                        wasPressed = pressed;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top;
                    canvas.drawRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    if (paths == null) {
                        Path path = new Path();
                        path.addRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                        paths = new Path[]{path};
                    }

                    canvas.save();
                    canvas.clipPath(paths[0]);
                    startY -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);
                        boolean pressed = propertyFlags.isSet(FLAG_PRESSED) && selectedBinding == getRangeBindingForIndex(range, index);

                        if (startY > boundingBox.top && startY < boundingBox.bottom && !pressed && !wasPressed) {
                            canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        }
                        String text = getRangeTextForIndex(range, index);

                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);

                            if (pressed) {
                                paint.setColor(lightColor);
                                canvas.drawRect(lineLeft, startY, lineRight, startY + elementSize, paint);
                            }

                            paint.setColor(pressed ? darkColor : lightColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }

                        startY += elementSize;
                        wasPressed = pressed;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                break;
            }
            case SCROLL_WHEEL: {
                if (scrollWheel != null) scrollWheel.draw(canvas, paint, boundingBox, lightColor, propertyFlags.isSet(FLAG_PRESSED));
                break;
            }
            case SIDE_DRAWER: {
                if (drawer != null) drawer.draw(canvas);
                break;
            }
            case STICK: {
                int cx = boundingBox.centerX();
                int cy = boundingBox.centerY();
                int oldColor = paint.getColor();
                canvas.drawCircle(cx, cy, boundingBox.height() * 0.5f, paint);

                float thumbstickX = currentPosition != null ? currentPosition.x : cx;
                float thumbstickY = currentPosition != null ? currentPosition.y : cy;

                short thumbRadius = (short) (snappingSize * 3.5f * scale);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(lightColor, 50));
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(oldColor);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + strokeWidth * 0.5f, paint);
                break;
            }
            case TRACKPAD: {
                float radius = boundingBox.height() * 0.15f;
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                float offset = strokeWidth * 2.5f;
                float innerStrokeWidth = strokeWidth * 2;
                float innerHeight = boundingBox.height() - offset * 2;
                radius = (innerHeight / boundingBox.height()) * radius - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f);
                paint.setStrokeWidth(innerStrokeWidth);
                canvas.drawRoundRect(boundingBox.left + offset, boundingBox.top + offset, boundingBox.right - offset, boundingBox.bottom - offset, radius, radius, paint);
                break;
            }
            case RADIAL_MENU: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float innerRadius = getRadialInnerRadius();
                float outerRadius = getRadialOuterRadius();
                float radius = boundingBox.width() * 0.5f;
                int oldColor = paint.getColor();
                float[][] sectors = getRadialSectors();

                if (paths == null) {
                    // closed state: little spokes that show where the active sectors are
                    Path spokes = new Path();
                    for (float[] sector : sectors) {
                        float middle = (float)Math.toRadians(sector[0] + sector[1] * 0.5f - 90);
                        float endX = (float)(cx + Math.cos(middle) * radius * 0.5f);
                        float endY = (float)(cy + Math.sin(middle) * radius * 0.5f);
                        spokes.moveTo(cx, cy);
                        spokes.lineTo(endX, endY);
                        spokes.addCircle(endX, endY, snappingSize * 0.4f, Path.Direction.CW);
                    }
                    paths = new Path[]{spokes};
                }

                if (propertyFlags.isSet(FLAG_VISIBLE) && radialStyle == RadialStyle.BUBBLES) {
                    drawRadialBubbles(canvas, paint, cx, cy, innerRadius, outerRadius, sectors);
                    drawIcon(canvas, cx, cy, boundingBox.width() * 0.4f, boundingBox.width() * 0.4f, 17, false);
                }
                else if (propertyFlags.isSet(FLAG_VISIBLE)) {
                    float minTextSize = snappingSize * 2 * scale;
                    float touchAreaRadius = (outerRadius - innerRadius) * 0.5f * 1.25f;
                    float touchAreaCenter = (innerRadius + outerRadius) * 0.5f;
                    paint.setTextAlign(Paint.Align.CENTER);

                    for (float[] sector : sectors) {
                        int bindingIndex = (int)sector[2];
                        Path sectorPath = buildRadialSectorPath(cx, cy, innerRadius, outerRadius, sector[0], sector[1]);

                        if (bindingIndex == radialHover) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(ColorUtils.setAlphaComponent(oldColor, 90));
                            canvas.drawPath(sectorPath, paint);
                        }

                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);
                        paint.setStrokeCap(Paint.Cap.SQUARE);
                        canvas.drawPath(sectorPath, paint);
                        paint.setStrokeCap(Paint.Cap.BUTT);

                        float middle = (float)Math.toRadians(sector[0] + sector[1] * 0.5f - 90);
                        canvas.save();
                        canvas.translate((float)(cx + Math.cos(middle) * touchAreaCenter), (float)(cy + Math.sin(middle) * touchAreaCenter));
                        float textAngle = (float)Math.toDegrees(middle + Math.PI * 0.5f) % 360;
                        if (textAngle < 0) textAngle += 360;
                        if (textAngle > 90 && textAngle <= 270) textAngle += 180;
                        canvas.rotate(textAngle);
                        String text = getBindingTextAt(bindingIndex);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, touchAreaRadius * 2), minTextSize));
                        paint.setColor(getLightColor());
                        canvas.drawText(text, 0, -((paint.descent() + paint.ascent()) * 0.5f), paint);
                        canvas.restore();
                    }

                    drawIcon(canvas, cx, cy, boundingBox.width() * 0.4f, boundingBox.width() * 0.4f, 17, false);
                }
                else {
                    paint.setStyle(Paint.Style.FILL_AND_STROKE);
                    paint.setColor(oldColor);
                    canvas.drawPath(paths[0], paint);
                }

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(oldColor);
                canvas.drawCircle(cx, cy, radius, paint);
                break;
            }
        }

        if (rootLayerSaved) canvas.restore();
        resolvedStyle = null;
    }

    /* ------------------------------------------------------------ radial menu geometry */

    private float getRadialBaseInnerRadius() {
        return getBoundingBox().width() * 0.5f + inputControlsView.getSnappingSize() * 0.5f;
    }

    /** Distance of the buttons from the center of the element (scaled by the "distance" setting). */
    private float getRadialInnerRadius() {
        float minimum = getBoundingBox().width() * 0.5f + inputControlsView.getSnappingSize() * 0.25f;
        return Math.max(minimum, getRadialBaseInnerRadius() * radialDistance);
    }

    public float getRadialOuterRadius() {
        Rect box = getBoundingBox();
        float baseOuter = radialStyle == RadialStyle.BUBBLES ? box.width() * 1.5f : box.width() + inputControlsView.getSnappingSize() * scale;
        float thickness = baseOuter - getRadialBaseInnerRadius();
        return getRadialInnerRadius() + thickness;
    }

    /**
     * All active sectors as {startDegrees, sweepDegrees, bindingIndex}. Degrees are measured clockwise from
     * 12 o'clock. The circle is divided into radialParts equal parts (the first one is centered on the top
     * edge, e.g. 1/2 = top half + bottom half); every active part holds its own number of buttons.
     */
    private float[][] getRadialSectors() {
        ArrayList<float[]> list = new ArrayList<>();
        float partSweep = 360.0f / radialParts;
        int offset = 0;

        for (int part = 0; part < radialParts; part++) {
            int count = radialPartCounts[part];
            if ((radialPartMask & (1 << part)) != 0) {
                float partStart = (radialParts == 1 ? 0 : -partSweep * 0.5f + part * partSweep) + radialRotation;
                float sweep = partSweep / count;
                for (int j = 0; j < count; j++) list.add(new float[]{partStart + j * sweep, sweep, offset + j});
            }
            offset += count;
        }
        return list.toArray(new float[0][]);
    }

    /** Returns the binding index of the sector under the point or -1 (center, outside, inactive part). */
    private int getRadialSectorAt(float x, float y) {
        Rect box = getBoundingBox();
        float dx = x - box.centerX();
        float dy = y - box.centerY();
        float distance = (float)Math.hypot(dx, dy);
        if (distance <= getRadialInnerRadius() || distance > getRadialOuterRadius() + inputControlsView.getSnappingSize()) return -1;

        float angle = (float)Math.toDegrees(Math.atan2(dy, dx)) + 90.0f;
        for (float[] sector : getRadialSectors()) {
            float delta = (((angle - sector[0]) % 360) + 360) % 360;
            if (delta < sector[1]) return (int)sector[2];
        }
        return -1;
    }

    private Path buildRadialSectorPath(float cx, float cy, float innerRadius, float outerRadius, float startDegrees, float sweepDegrees) {
        float start = startDegrees - 90;
        float margin = Math.min(2.0f, sweepDegrees * 0.2f);
        float innerMargin = Math.min(margin * outerRadius / innerRadius, sweepDegrees * 0.3f);
        Path path = new Path();
        path.arcTo(new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius), start + margin, sweepDegrees - margin * 2);
        path.arcTo(new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius), start + sweepDegrees - innerMargin, -(sweepDegrees - innerMargin * 2));
        path.close();
        return path;
    }

    private void drawRadialBubbles(Canvas canvas, Paint paint, float cx, float cy, float innerRadius, float outerRadius, float[][] sectors) {
        int snappingSize = inputControlsView.getSnappingSize();
        int lightColor = getLightColor();
        int darkColor = getDarkColor();
        float ringRadius = (innerRadius + outerRadius) * 0.5f;
        float strokeWidth = snappingSize * 0.25f;

        paint.setTextAlign(Paint.Align.CENTER);
        for (float[] sector : sectors) {
            int bindingIndex = (int)sector[2];
            float halfSweep = (float)Math.toRadians(Math.min(sector[1], 180.0f) * 0.5f);
            float bubbleRadius = Math.min((outerRadius - innerRadius) * 0.45f, (float)(ringRadius * Math.sin(halfSweep) * 0.88f));
            float middle = (float)Math.toRadians(sector[0] + sector[1] * 0.5f - 90);
            float bx = (float)(cx + Math.cos(middle) * ringRadius);
            float by = (float)(cy + Math.sin(middle) * ringRadius);
            boolean hovered = bindingIndex == radialHover;

            paint.setStrokeWidth(strokeWidth);
            paint.setStyle(hovered ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setColor(lightColor);
            canvas.drawCircle(bx, by, bubbleRadius, paint);

            String text = getBindingTextAt(bindingIndex);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, bubbleRadius * 1.5f), snappingSize * 2 * scale));
            paint.setColor(hovered ? darkColor : lightColor);
            canvas.drawText(text, bx, by - ((paint.descent() + paint.ascent()) * 0.5f), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(lightColor);
    }

    /* ------------------------------------------------------------ radial menu settings */

    public int getRadialParts() {
        return radialParts;
    }

    public int getRadialPartCount(int part) {
        return part >= 0 && part < radialPartCounts.length ? radialPartCounts[part] : 0;
    }

    public boolean isRadialPartActive(int part) {
        return (radialPartMask & (1 << part)) != 0;
    }

    /** Rotation of the whole radial layout in degrees (0..359), clockwise. */
    public float getRadialRotation() {
        return radialRotation;
    }

    public float getRadialDistance() {
        return radialDistance;
    }

    public int getRadialPartMask() {
        return radialPartMask;
    }

    public byte[] getRadialPartCounts() {
        return radialPartCounts.clone();
    }

    public void setRadialDistance(float distance) {
        this.radialDistance = Mathf.clamp(distance, 0.5f, 2.5f);
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    public void setRadialRotation(float degrees) {
        this.radialRotation = ((degrees % 360) + 360) % 360;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    /** Enables/disables a part. At least one part always stays active; returns false if the change was refused. */
    public boolean setRadialPartActive(int part, boolean active) {
        int mask = active ? (radialPartMask | (1 << part)) : (radialPartMask & ~(1 << part));
        if (mask == 0) return false;
        radialPartMask = mask;
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
        return true;
    }

    /** Changes the number of buttons of one part, keeping the bindings of all the other parts. */
    public void setRadialPartCount(int part, int count) {
        if (part < 0 || part >= radialParts) return;
        byte[] newCounts = radialPartCounts.clone();
        newCounts[part] = (byte)Mathf.clamp(count, 1, 8);
        applyRadialCounts(newCounts);
    }

    /** Divides the circle into 1..8 parts; all parts become active with 3 buttons each (1 part = classic layout). */
    public void setRadialParts(int parts) {
        parts = Mathf.clamp(parts, 1, 8);
        if (parts == radialParts) return;
        byte[] newCounts = new byte[parts];
        Arrays.fill(newCounts, (byte)3);
        radialParts = parts;
        radialPartMask = (1 << parts) - 1;
        radialPartCounts = new byte[parts];
        bindings = new Binding[0];
        applyRadialCounts(newCounts);
    }

    /** Used by the profile loader: sets the complete layout at once. */
    public void setRadialLayout(int parts, int mask, byte[] counts) {
        radialParts = Mathf.clamp(parts, 1, 8);
        radialPartMask = (mask & ((1 << radialParts) - 1)) != 0 ? (mask & ((1 << radialParts) - 1)) : (1 << radialParts) - 1;
        byte[] fixed = new byte[radialParts];
        for (int i = 0; i < radialParts; i++) fixed[i] = (byte)Mathf.clamp(i < counts.length ? counts[i] : 3, 1, 8);
        radialPartCounts = new byte[radialParts];
        bindings = new Binding[0];
        applyRadialCounts(fixed);
    }

    private void applyRadialCounts(byte[] newCounts) {
        int total = 0;
        for (byte c : newCounts) total += c;

        Binding[] newBindings = new Binding[total];
        Arrays.fill(newBindings, Binding.NONE);

        int oldOffset = 0;
        int newOffset = 0;
        for (int part = 0; part < newCounts.length; part++) {
            int oldCount = part < radialPartCounts.length ? radialPartCounts[part] : 0;
            int keep = Math.min(oldCount, newCounts[part]);
            for (int j = 0; j < keep && oldOffset + j < bindings.length; j++) newBindings[newOffset + j] = bindings[oldOffset + j];
            oldOffset += oldCount;
            newOffset += newCounts[part];
        }

        radialPartCounts = newCounts;
        bindings = newBindings;
        states = new boolean[total];
        propertyFlags.set(FLAG_BOUNDING_BOX_NEEDS_UPDATE);
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId, boolean automargin) {
        Paint paint = inputControlsView.getPaint();
        Bitmap icon = inputControlsView.getIcon((byte)iconId);
        int oldPaintColor = paint.getColor();
        boolean darkIcon = propertyFlags.isSet(FLAG_PRESSED) && isOutlineVisible();
        int iconColor = darkIcon ? getDarkColor() : getContentColor();
        if (isIconFilterEnabled()) {
            // the filter (default) paints every icon in one color; without it the icons keep their own colors
            int rgb = darkIcon ? 0x000000 : (iconColor & 0xffffff);
            if (iconFilterCache == null || iconFilterCacheColor != rgb) {
                iconFilterCache = new android.graphics.PorterDuffColorFilter(0xff000000 | rgb, android.graphics.PorterDuff.Mode.SRC_IN);
                iconFilterCacheColor = rgb;
            }
            paint.setColorFilter(iconFilterCache);
        }
        else paint.setColorFilter(null);
        paint.setAlpha(Color.alpha(iconColor));
        float snappingSize = inputControlsView.getSnappingSize();
        int margin = automargin ? (int)(snappingSize * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 2.0f : 1.0f) * scale) : 0;
        int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);

        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        Rect dstRect = new Rect((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        paint.setColorFilter(null);
        paint.setColor(oldPaintColor);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            if (opacity < 1.0f) elementJSONObject.put("opacity", Float.valueOf(opacity));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", propertyFlags.isSet(FLAG_TOGGLE_SWITCH));
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);
            if (!styleOverrides.isEmpty()) elementJSONObject.put("style", styleOverrides.toJSONObject());

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }
            if (propertyFlags.isSet(FLAG_MOUSE_MOVE_MODE)) elementJSONObject.put("mouseMoveMode", true);

            if (type == Type.SCROLL_WHEEL) {
                elementJSONObject.put("scrollStyle", scrollStyle.name());
                elementJSONObject.put("orientation", orientation);
            }
            else if (type == Type.RADIAL_MENU) {
                elementJSONObject.put("radialStyle", radialStyle.name());
                elementJSONObject.put("radialTrigger", radialTrigger.name());
                elementJSONObject.put("radialParts", radialParts);
                elementJSONObject.put("radialPartMask", radialPartMask);
                JSONArray countsJSONArray = new JSONArray();
                for (byte count : radialPartCounts) countsJSONArray.put((int)count);
                elementJSONObject.put("radialPartCounts", countsJSONArray);
                elementJSONObject.put("radialAngle", radialRotation);
                elementJSONObject.put("radialDistance", radialDistance);
            }
            else if (type == Type.CUSTOM_MENU) {
                elementJSONObject.put("radialTrigger", radialTrigger.name());
                elementJSONObject.put("drawerAutoClose", isDrawerAutoClose());
                if (customMenu != null) {
                    elementJSONObject.put("nodes", customMenu.toJSONArray());
                    elementJSONObject.put("rootHideOnPath", customMenu.isRootHideOnPath());
                    elementJSONObject.put("rootAsButton", customMenu.isRootAsButton());
                }
            }
            else if (type == Type.SIDE_DRAWER) {
                elementJSONObject.put("drawerSide", drawerSide);
                elementJSONObject.put("drawerLayout", drawerLayout.name());
                elementJSONObject.put("gridColumns", gridColumns);
                elementJSONObject.put("gridRows", gridRows);
                elementJSONObject.put("gridByRows", gridByRows);
                elementJSONObject.put("drawerAutoClose", isDrawerAutoClose());
            }

            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        if (type == Type.SIDE_DRAWER && drawer != null) return drawer.containsPoint(x, y);
        if (type == Type.CUSTOM_MENU) return customMenu != null && customMenu.containsPoint(x, y);
        if (type == Type.RADIAL_MENU && propertyFlags.isSet(FLAG_VISIBLE)) {
            float outerRadius = getRadialOuterRadius() + (radialStyle == RadialStyle.BUBBLES ? inputControlsView.getSnappingSize() : 0);
            return Mathf.distance(boundingBox.centerX(), boundingBox.centerY(), x, y) < outerRadius;
        }
        else return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !propertyFlags.isSet(FLAG_TOGGLE_SWITCH) && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (type == Type.SIDE_DRAWER) return drawer != null && drawer.handleTouchDown(pointerId, x, y);
        if (type == Type.CUSTOM_MENU) return customMenu != null && customMenu.handleTouchDown(pointerId, x, y);
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            if (type == Type.BUTTON) {
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                if (!propertyFlags.isSet(FLAG_TOGGLE_SWITCH) || !propertyFlags.isSet(FLAG_SELECTED)) inputControlsView.handleInputEvent(bindings, true);
                if (propertyFlags.isSet(FLAG_MOUSE_MOVE_MODE)) inputControlsView.getTouchpadView().mouseMove(x, y, MotionEvent.ACTION_DOWN);

                propertyFlags.set(FLAG_PRESSED);
                inputControlsView.invalidate();
                return true;
            }
            else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchDown(x, y);
                propertyFlags.set(FLAG_PRESSED);
                inputControlsView.invalidate();
                return true;
            }
            else if (type == Type.SCROLL_WHEEL) {
                scrollWheel.handleTouchDown(x, y);
                propertyFlags.set(FLAG_PRESSED);
                inputControlsView.invalidate();
                return true;
            }
            else if (type == Type.RADIAL_MENU) {
                boolean wasOpen = propertyFlags.isSet(FLAG_VISIBLE);
                boolean inCenter = Mathf.distance(boundingBox.centerX(), boundingBox.centerY(), x, y) < (boundingBox.width() * 0.5f);
                radialDownTime = System.currentTimeMillis();
                radialHover = -1;
                radialOpenedOnTouch = false;
                radialClosedOnTouch = false;

                if (wasOpen) {
                    if (inCenter) {
                        propertyFlags.unset(FLAG_VISIBLE);
                        radialClosedOnTouch = true;
                    }
                    else if (radialTrigger != RadialTrigger.TAP) radialHover = getRadialSectorAt(x, y);
                }
                else {
                    propertyFlags.set(FLAG_VISIBLE);
                    radialOpenedOnTouch = true;
                }
                inputControlsView.invalidate();
                return true;
            }
            else {
                if (type == Type.TRACKPAD) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                }
                return handleTouchMove(pointerId, x, y);
            }
        }
        else return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (type == Type.SIDE_DRAWER) return drawer != null && drawer.handleTouchMove(pointerId, x, y);
        if (type == Type.CUSTOM_MENU) return customMenu != null && customMenu.handleTouchMove(pointerId, x, y);
        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView =  inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                float distance = Mathf.lengthSq(radius - localX, radius - localY);
                if (distance > radius * radius) {
                    float angle = (float)Math.atan2(offsetY, offsetX);
                    offsetX = (float)(Math.cos(angle) * radius);
                    offsetY = (float)(Math.sin(angle) * radius);
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }

            if (type == Type.STICK) {
                if (currentPosition == null) currentPosition = new PointF();
                currentPosition.x = boundingBox.left + deltaX * radius + radius;
                currentPosition.y = boundingBox.top + deltaY * radius + radius;
                final boolean[] states = {deltaY <= -STICK_DEAD_ZONE, deltaX >= STICK_DEAD_ZONE, deltaY >= STICK_DEAD_ZONE, deltaX <= -STICK_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        value = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY, -1, 1);
                        inputControlsView.handleInputEvent(binding, true, value);
                        this.states[i] = true;
                    }
                    else {
                        boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                        inputControlsView.handleInputEvent(binding, state, value);
                        this.states[i] = state;
                    }
                }

                inputControlsView.invalidate();
            }
            else if (type == Type.TRACKPAD) {
                final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                int cursorDx = 0;
                int cursorDy = 0;

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3 ? deltaX : deltaY);
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        if (interpolator == null) interpolator = new CubicBezierInterpolator();
                        if (Math.abs(value) > TRACKPAD_ACCELERATION_THRESHOLD) value *= STICK_SENSITIVITY;
                        interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                        float interpolatedValue = interpolator.getInterpolation(Math.min(1.0f, Math.abs(value / TRACKPAD_MAX_SPEED)));
                        inputControlsView.handleInputEvent(binding, true, Mathf.clamp(interpolatedValue * Mathf.sign(value), -1, 1));
                        this.states[i] = true;
                    }
                    else {
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }
                }

                if (cursorDx != 0 || cursorDy != 0) inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
            }
            else {
                final boolean[] states = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }
            }

            return true;
        }
        else if (pointerId == currentPointerId && type == Type.SCROLL_WHEEL) {
            scrollWheel.handleTouchMove(x, y);
            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RADIAL_MENU && radialTrigger != RadialTrigger.TAP) {
            if (propertyFlags.isSet(FLAG_VISIBLE)) {
                int hover = getRadialSectorAt(x, y);
                if (hover != radialHover) {
                    radialHover = hover;
                    inputControlsView.invalidate();
                }
            }
            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            if (scroller.isScrolling()) {
                propertyFlags.unset(FLAG_PRESSED);
                inputControlsView.invalidate();
            }
            return true;
        }
        else if (pointerId == currentPointerId && type == Type.BUTTON && propertyFlags.isSet(FLAG_MOUSE_MOVE_MODE)) {
            inputControlsView.getTouchpadView().mouseMove(x, y, MotionEvent.ACTION_MOVE);
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId, float x, float y) {
        if (type == Type.SIDE_DRAWER) return drawer != null && drawer.handleTouchUp(pointerId, x, y);
        if (type == Type.CUSTOM_MENU) return customMenu != null && customMenu.handleTouchUp(pointerId, x, y);
        if (pointerId == currentPointerId) {
            if (type == Type.BUTTON) {
                boolean selected = propertyFlags.isSet(FLAG_SELECTED);
                if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long)touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) inputControlsView.handleInputEvent(bindings, false);
                    propertyFlags.set(FLAG_SELECTED, selected);
                    touchTime = null;
                }
                else if (!propertyFlags.isSet(FLAG_TOGGLE_SWITCH) || propertyFlags.isSet(FLAG_SELECTED)) {
                    inputControlsView.handleInputEvent(bindings, false);
                }

                if (propertyFlags.isSet(FLAG_TOGGLE_SWITCH)) propertyFlags.set(FLAG_SELECTED, !selected);
                if (propertyFlags.isSet(FLAG_MOUSE_MOVE_MODE)) inputControlsView.getTouchpadView().mouseMove(0, 0, MotionEvent.ACTION_UP);

                propertyFlags.unset(FLAG_PRESSED);
                inputControlsView.invalidate();
            }
            else if (type == Type.SCROLL_WHEEL) {
                scrollWheel.handleTouchUp();
                propertyFlags.unset(FLAG_PRESSED);
                inputControlsView.invalidate();
            }
            else if (type == Type.RADIAL_MENU) {
                handleRadialMenuUp(x, y);
                inputControlsView.invalidate();
            }
            else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD) {
                for (byte i = 0; i < states.length; i++) {
                    if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                    states[i] = false;
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                    propertyFlags.unset(FLAG_PRESSED);
                    inputControlsView.invalidate();
                }
                else if (type == Type.STICK) {
                    inputControlsView.invalidate();
                }

                if (currentPosition != null) currentPosition = null;
            }
            currentPointerId = -1;
            return true;
        }
        return false;
    }

    private void fireRadialBinding(int index) {
        if (index < 0 || index >= bindings.length) return;
        final Binding binding = bindings[index];
        if (binding == Binding.NONE) return;
        propertyFlags.unset(FLAG_VISIBLE);
        inputControlsView.handleInputEvent(binding, true);
        inputControlsView.postDelayed(() -> inputControlsView.handleInputEvent(binding, false), 30);
    }

    private void handleRadialMenuUp(float x, float y) {
        if (!propertyFlags.isSet(FLAG_VISIBLE)) {
            radialHover = -1;
            return;
        }

        int sector = getRadialSectorAt(x, y);
        boolean hasBinding = sector >= 0 && bindings[sector] != Binding.NONE;
        radialHover = -1;

        if (radialTrigger == RadialTrigger.TAP) {
            if (hasBinding) fireRadialBinding(sector);
            return;
        }

        if (radialClosedOnTouch) return;
        if (hasBinding) {
            fireRadialBinding(sector);
        }
        else if (radialTrigger == RadialTrigger.HYBRID) {
            boolean quickTap = System.currentTimeMillis() - radialDownTime < RADIAL_HYBRID_TAP_MILLISECONDS;
            // a quick tap (or a tap on the already opened menu) leaves the menu open, a long hold cancels it
            if (!radialOpenedOnTouch || !quickTap) {
                if (radialOpenedOnTouch) propertyFlags.unset(FLAG_VISIBLE);
            }
        }
        else propertyFlags.unset(FLAG_VISIBLE);
    }

    public int getLightColor() {
        float opacity = inputControlsView.isEditMode() ? Math.max(0.15f, this.opacity) : this.opacity;
        return Color.argb((int)(opacity * inputControlsView.getOverlayOpacity() * 255), 255, 255, 255);
    }

    public int getDarkColor() {
        float opacity = inputControlsView.isEditMode() ? Math.max(0.15f, this.opacity) : this.opacity;
        return Color.argb((int)(opacity * inputControlsView.getOverlayOpacity() * 255), 0, 0, 0);
    }

    public int getHighlightColor() {
        return Color.argb((int)(inputControlsView.getOverlayOpacity() * 255), 2, 119, 189);
    }
}
