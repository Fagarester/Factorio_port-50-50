package com.factorio.inputcontrols;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import com.factorio.math.Mathf;
import com.factorio.widget.InputControlsView;

/**
 * A panel of buttons attached to the left or right screen edge. A small handle tab stays visible on the
 * edge; tapping it slides the panel out (and back) with an animation. The buttons inside can be laid out
 * in a vertical column, a horizontal row or a grid.
 *
 * The element's bindings are the buttons of the panel; the element's Y is the vertical position.
 */
public class SideDrawer {
    public enum Layout {
        VERTICAL, HORIZONTAL, GRID;

        public static String[] names() {
            Layout[] layouts = values();
            String[] names = new String[layouts.length];
            for (int i = 0; i < layouts.length; i++) names[i] = layouts[i].name().replace("_", " ");
            return names;
        }
    }

    public static final byte SIDE_LEFT = 0;
    public static final byte SIDE_RIGHT = 1;
    public static final byte SIDE_TOP = 2;
    public static final byte SIDE_BOTTOM = 3;
    private static final long OPEN_DURATION = 260;
    private static final long CLOSE_DURATION = 200;
    private static final float CELL_PRESS_MIN_PROGRESS = 0.85f;

    private final InputControlsView view;
    private final ControlElement element;
    private final Rect boundingBox = new Rect();
    private final Rect panelRect = new Rect();
    private final Rect handleRect = new Rect();
    private final RectF rectF = new RectF();
    private final SparseIntArray pointerCells = new SparseIntArray();   // pointerId -> cell index
    private int handlePointerId = -1;

    private float progress = 0;   // 0 closed .. 1 opened
    private boolean open = false;
    private ValueAnimator animator;

    // computed by computeLayout()
    private int columns = 1;
    private int rows = 1;
    private float cellSize;
    private float gap;
    private float padding;

    public SideDrawer(InputControlsView view, ControlElement element) {
        this.view = view;
        this.element = element;
    }

    public boolean isOpen() {
        return open;
    }

    private float getProgress() {
        return view.isEditMode() ? 1.0f : progress;
    }

    /* -------------------------------------------------------------- geometry */

    private void computeLayout() {
        int snap = view.getSnappingSize();
        float scale = element.getScale();
        int count = Math.max(1, element.getBindingCount());
        int height = view.getMaxHeight();
        int width = view.getMaxWidth();

        switch (element.getDrawerLayout()) {
            case HORIZONTAL:
                columns = count;
                rows = 1;
                break;
            case GRID:
                int[] grid = element.getGridSize();
                columns = grid[0];
                rows = grid[1];
                break;
            case VERTICAL:
            default:
                columns = 1;
                rows = count;
                break;
        }

        gap = snap * 0.5f * scale;
        padding = snap * 0.75f * scale;
        cellSize = snap * 5.0f * scale;

        // shrink the cells so that the panel always fits on the screen
        boolean horizontalEdge = isHorizontalEdge();
        float maxHeight = horizontalEdge ? height * 0.7f : height - snap * 2;
        float maxWidth = horizontalEdge ? width - snap * 2 : width * 0.7f;
        cellSize = Math.min(cellSize, (maxHeight - padding * 2 - gap * (rows - 1)) / rows);
        cellSize = Math.min(cellSize, (maxWidth - padding * 2 - gap * (columns - 1)) / columns);
        cellSize = Math.max(cellSize, snap * 2);
    }

    /** Returns {columns, rows}: either the columns or the rows are fixed, the other value is calculated. */
    public static int[] computeGrid(int count, int columns, int rows, boolean byRows) {
        count = Math.max(1, count);
        int c, r;
        if (byRows) {
            r = Mathf.clamp(rows, 1, count);
            c = (count + r - 1) / r;
            r = (count + c - 1) / c;
        }
        else {
            c = Mathf.clamp(columns, 1, count);
            r = (count + c - 1) / c;
        }
        return new int[]{c, r};
    }

    private boolean isHorizontalEdge() {
        byte side = element.getDrawerSide();
        return side == SIDE_TOP || side == SIDE_BOTTOM;
    }

    /** Recomputes the panel/handle rectangles for the current animation progress. */
    private void computeRects() {
        computeLayout();
        int snap = view.getSnappingSize();
        float scale = element.getScale();
        int width = view.getMaxWidth();
        int height = view.getMaxHeight();

        int panelWidth = (int)(columns * cellSize + (columns - 1) * gap + padding * 2);
        int panelHeight = (int)(rows * cellSize + (rows - 1) * gap + padding * 2);
        int handleThickness = (int)(snap * 2.4f * scale);
        int handleLength = (int)Math.max(snap * 7 * scale, snap * 4);
        int margin = snap;
        float p = getProgress();

        if (isHorizontalEdge()) {
            // the panel hangs from the top edge or rises from the bottom edge, X is the position along the edge
            int extent = Math.max(panelWidth, handleLength);
            int cx = Mathf.clamp(element.getX(), extent / 2 + margin, Math.max(extent / 2 + margin, width - extent / 2 - margin));
            if (element.getDrawerSide() == SIDE_BOTTOM) {
                int panelTop = (int)(height - panelHeight * p);
                panelRect.set(cx - panelWidth / 2, panelTop, cx + panelWidth / 2, panelTop + panelHeight);
                handleRect.set(cx - handleLength / 2, panelTop - handleThickness, cx + handleLength / 2, panelTop);
            }
            else {
                int panelBottom = (int)(panelHeight * p);
                panelRect.set(cx - panelWidth / 2, panelBottom - panelHeight, cx + panelWidth / 2, panelBottom);
                handleRect.set(cx - handleLength / 2, panelBottom, cx + handleLength / 2, panelBottom + handleThickness);
            }
        }
        else {
            int extent = Math.max(panelHeight, handleLength);
            int cy = Mathf.clamp(element.getY(), extent / 2 + margin, Math.max(extent / 2 + margin, height - extent / 2 - margin));
            if (element.getDrawerSide() == SIDE_RIGHT) {
                int panelLeft = (int)(width - panelWidth * p);
                panelRect.set(panelLeft, cy - panelHeight / 2, panelLeft + panelWidth, cy + panelHeight / 2);
                handleRect.set(panelLeft - handleThickness, cy - handleLength / 2, panelLeft, cy + handleLength / 2);
            }
            else {
                int panelRight = (int)(panelWidth * p);
                panelRect.set(panelRight - panelWidth, cy - panelHeight / 2, panelRight, cy + panelHeight / 2);
                handleRect.set(panelRight, cy - handleLength / 2, panelRight + handleThickness, cy + handleLength / 2);
            }
        }

        boundingBox.set(handleRect);
        if (p > 0.001f) boundingBox.union(panelRect);
    }

    public Rect getBoundingBox() {
        computeRects();
        return boundingBox;
    }

    private Rect getCellRect(int index, Rect out) {
        int col, row;
        switch (element.getDrawerLayout()) {
            case HORIZONTAL:
                col = index;
                row = 0;
                break;
            case GRID:
                col = index % columns;
                row = index / columns;
                break;
            case VERTICAL:
            default:
                col = 0;
                row = index;
                break;
        }
        int left = (int)(panelRect.left + padding + col * (cellSize + gap));
        int top = (int)(panelRect.top + padding + row * (cellSize + gap));
        out.set(left, top, (int)(left + cellSize), (int)(top + cellSize));
        return out;
    }

    private int getCellAt(float x, float y) {
        Rect cell = new Rect();
        int count = element.getBindingCount();
        // slightly enlarged hit area so that the gaps between buttons are not dead
        int slop = (int)(gap * 0.5f);
        for (int i = 0; i < count; i++) {
            getCellRect(i, cell);
            cell.inset(-slop, -slop);
            if (cell.contains((int)x, (int)y)) return i;
        }
        return -1;
    }

    /* ------------------------------------------------------------ open/close */

    public void setOpen(boolean open) {
        if (this.open == open && animator != null) return;
        this.open = open;

        if (animator != null) animator.cancel();
        float target = open ? 1.0f : 0.0f;
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration((long)((open ? OPEN_DURATION : CLOSE_DURATION) * Math.max(0.25f, Math.abs(target - progress))));
        animator.setInterpolator(open ? new DecelerateInterpolator(1.8f) : new AccelerateInterpolator(1.4f));
        animator.addUpdateListener((a) -> {
            progress = (float)a.getAnimatedValue();
            view.invalidate();
        });
        animator.start();
    }

    public void toggle() {
        setOpen(!open);
    }

    /** Releases everything that is pressed and snaps the panel closed (profile change, leaving the game...). */
    public void reset() {
        if (animator != null) animator.cancel();
        releaseAll();
        open = false;
        progress = 0;
    }

    private void releaseAll() {
        for (int i = 0; i < pointerCells.size(); i++) {
            view.handleInputEvent(element.getBindingAt(pointerCells.valueAt(i)), false);
        }
        pointerCells.clear();
        handlePointerId = -1;
    }

    /* ----------------------------------------------------------------- touch */

    public boolean containsPoint(float x, float y) {
        Rect box = getBoundingBox();
        return box.contains((int)x, (int)y);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        computeRects();

        if (handleRect.contains((int)x, (int)y)) {
            handlePointerId = pointerId;
            toggle();
            view.invalidate();
            return true;
        }

        if (progress > 0.001f && panelRect.contains((int)x, (int)y)) {
            // the panel swallows touches so that they do not leak to the game behind it
            if (progress >= CELL_PRESS_MIN_PROGRESS && open) {
                int cell = getCellAt(x, y);
                if (cell >= 0) {
                    Binding binding = element.getBindingAt(cell);
                    if (binding != Binding.NONE) {
                        pointerCells.put(pointerId, cell);
                        view.handleInputEvent(binding, true);
                    }
                    else pointerCells.put(pointerId, -1);
                }
                else pointerCells.put(pointerId, -1);
            }
            else pointerCells.put(pointerId, -1);
            view.invalidate();
            return true;
        }
        return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        return pointerId == handlePointerId || pointerCells.indexOfKey(pointerId) >= 0;
    }

    public boolean handleTouchUp(int pointerId, float x, float y) {
        if (pointerId == handlePointerId) {
            handlePointerId = -1;
            return true;
        }

        int index = pointerCells.indexOfKey(pointerId);
        if (index >= 0) {
            int cell = pointerCells.valueAt(index);
            pointerCells.removeAt(index);
            if (cell >= 0) {
                view.handleInputEvent(element.getBindingAt(cell), false);
                if (element.isDrawerAutoClose() && pointerCells.size() == 0) setOpen(false);
            }
            view.invalidate();
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ draw */

    private static int withAlpha(int color, float factor) {
        int alpha = (int)(((color >>> 24) & 0xff) * Mathf.clamp(factor, 0, 1));
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        float measured = paint.measureText(text);
        return measured > 0 ? testTextSize * desiredWidth / measured : testTextSize;
    }

    public void draw(Canvas canvas) {
        computeRects();
        Paint paint = view.getPaint();
        int snap = view.getSnappingSize();
        float scale = element.getScale();
        int lightColor = element.getLightColor();
        int darkColor = element.getDarkColor();
        float strokeWidth = snap * 0.25f;
        float cornerRadius = snap * 1.0f * scale;
        float p = getProgress();

        int backgroundColor = withAlpha(Color.argb(255, 12, 12, 12), Math.min(1.0f, view.getOverlayOpacity() * element.getOpacity() * 1.6f));
        int baseColor = element.isSelected() ? element.getHighlightColor() : lightColor;

        paint.setStrokeWidth(strokeWidth);

        // ---- panel
        if (p > 0.001f) {
            rectF.set(panelRect);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(backgroundColor);
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(baseColor);
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);

            Rect cell = new Rect();
            int count = element.getBindingCount();
            float cellRadius = snap * 0.75f * scale;
            paint.setTextAlign(Paint.Align.CENTER);

            for (int i = 0; i < count; i++) {
                getCellRect(i, cell);
                rectF.set(cell);
                boolean pressed = false;
                for (int j = 0; j < pointerCells.size(); j++) {
                    if (pointerCells.valueAt(j) == i) {
                        pressed = true;
                        break;
                    }
                }

                paint.setStyle(pressed ? Paint.Style.FILL : Paint.Style.STROKE);
                paint.setColor(baseColor);
                canvas.drawRoundRect(rectF, cellRadius, cellRadius, paint);

                String text = element.getBindingTextAt(i);
                if (text != null && !text.isEmpty()) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, cell.width() - strokeWidth * 3), cellSize * 0.42f));
                    paint.setColor(pressed ? darkColor : lightColor);
                    canvas.drawText(text, cell.centerX(), cell.centerY() - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                }
            }
        }

        // ---- handle tab (its outer side is flat against the panel, the inner side is rounded)
        rectF.set(handleRect);
        float handleRadius = Math.min(cornerRadius * 1.5f, handleRect.width() * 0.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(backgroundColor);
        canvas.drawRoundRect(rectF, handleRadius, handleRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(baseColor);
        canvas.drawRoundRect(rectF, handleRadius, handleRadius, paint);

        // chevron: points towards the screen centre while closed, towards the edge while open
        float cx = handleRect.centerX();
        float cy = handleRect.centerY();
        float arm = snap * 0.7f * scale;
        float dirX = 0, dirY = 0;
        switch (element.getDrawerSide()) {
            case SIDE_RIGHT: dirX = -1; break;
            case SIDE_TOP: dirY = 1; break;
            case SIDE_BOTTOM: dirY = -1; break;
            default: dirX = 1; break;
        }
        if (p >= 0.5f) {
            dirX = -dirX;
            dirY = -dirY;
        }
        float perpX = -dirY;
        float perpY = dirX;
        Path chevron = new Path();
        chevron.moveTo(cx - dirX * arm * 0.5f + perpX * arm, cy - dirY * arm * 0.5f + perpY * arm);
        chevron.lineTo(cx + dirX * arm * 0.5f, cy + dirY * arm * 0.5f);
        chevron.lineTo(cx - dirX * arm * 0.5f - perpX * arm, cy - dirY * arm * 0.5f - perpY * arm);
        paint.setColor(lightColor);
        paint.setStrokeWidth(strokeWidth * 1.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(chevron, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(strokeWidth);
    }
}
