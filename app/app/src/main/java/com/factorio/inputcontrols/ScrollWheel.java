package com.factorio.inputcontrols;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import com.factorio.math.Mathf;
import com.factorio.widget.InputControlsView;

/**
 * Mouse-wheel emulation for touch screens. Three interchangeable styles are provided so they can be
 * compared in the game and only one kept afterwards:
 *  - STRIP:   a ribbon with notches, drag along it (with a flick/inertia)
 *  - DIAL:    a round knob, drag around it (with a flick/inertia)
 *  - BUTTONS: two halves "up" / "down", hold to repeat with acceleration
 *
 * Bindings: index 0 = scroll up, index 1 = scroll down (default MOUSE_SCROLL_UP / MOUSE_SCROLL_DOWN,
 * but any key/mouse/gamepad binding is allowed).
 */
public class ScrollWheel {
    public enum Style {
        STRIP, DIAL, BUTTONS;

        public static String[] names() {
            Style[] styles = values();
            String[] names = new String[styles.length];
            for (int i = 0; i < styles.length; i++) names[i] = styles[i].name().replace("_", " ");
            return names;
        }
    }

    private static final int MAX_TICKS_PER_EVENT = 12;
    private static final float DIAL_TICK_RADIANS = (float)Math.toRadians(15);
    private static final float DIAL_DEAD_ZONE = 0.25f;     // fraction of the radius with no angle tracking
    private static final long REPEAT_START_DELAY = 350;    // ms before BUTTONS style starts repeating
    private static final long REPEAT_SLOW_INTERVAL = 130;  // ms
    private static final long REPEAT_FAST_INTERVAL = 40;   // ms
    private static final long KEY_RELEASE_DELAY = 25;      // ms, for non-wheel bindings

    private final InputControlsView view;
    private final ControlElement element;
    private final RectF rectF = new RectF();
    private final Path clipPath = new Path();

    private boolean touching = false;
    private float lastPosition;      // STRIP: px along the axis, DIAL: angle in radians
    private float accumulator;       // same unit as lastPosition
    private float phase;             // visual offset, same unit
    private long lastMoveTime;
    private float velocity;          // units per second
    private boolean flinging = false;
    private long lastFlingTime;
    private int holdDirection = 0;   // BUTTONS: +1 up, -1 down, 0 none
    private int repeatCount = 0;

    private final Runnable flingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!flinging) return;
            long now = System.nanoTime() / 1000000L;
            float dt = Math.min(0.05f, (now - lastFlingTime) / 1000.0f);
            lastFlingTime = now;

            applyDelta(velocity * dt);
            velocity *= (float)Math.pow(0.04, dt);   // ~96% of the speed is lost per second
            if (Math.abs(velocity) < getMinFlingVelocity() * 0.4f) {
                flinging = false;
                velocity = 0;
            }
            else view.postOnAnimation(this);
            view.invalidate();
        }
    };

    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!touching || holdDirection == 0) return;
            tick(holdDirection > 0);
            repeatCount++;
            float t = Mathf.clamp(repeatCount / 14.0f, 0, 1);
            long interval = (long)(REPEAT_SLOW_INTERVAL + (REPEAT_FAST_INTERVAL - REPEAT_SLOW_INTERVAL) * t);
            view.postDelayed(this, interval);
        }
    };

    public ScrollWheel(InputControlsView view, ControlElement element) {
        this.view = view;
        this.element = element;
    }

    private boolean isVertical() {
        return element.getOrientation() == 1;
    }

    /** Half width / half height of the element for the bounding box (before applying the scale). */
    public static int[] getHalfExtents(Style style, boolean vertical, int snappingSize) {
        int halfWidth, halfHeight;
        switch (style) {
            case DIAL:
                halfWidth = halfHeight = (int)(snappingSize * 5.5f);
                break;
            case BUTTONS:
                halfWidth = (int)(snappingSize * 3.0f);
                halfHeight = (int)(snappingSize * 6.0f);
                if (!vertical) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            case STRIP:
            default:
                halfWidth = (int)(snappingSize * 2.5f);
                halfHeight = snappingSize * 8;
                if (!vertical) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
        }
        return new int[]{halfWidth, halfHeight};
    }

    private float getNotchSize() {
        return Math.max(6, view.getSnappingSize() * 1.6f * element.getScale());
    }

    private float getMinFlingVelocity() {
        return element.getScrollStyle() == Style.DIAL ? 3.0f : 350.0f;
    }

    private float getMaxFlingVelocity() {
        return element.getScrollStyle() == Style.DIAL ? 22.0f : 2600.0f;
    }

    /* ---------------------------------------------------------------- output */

    private void tick(boolean up) {
        Binding binding = element.getBindingAt(up ? 0 : 1);
        if (binding == Binding.NONE) return;

        if (binding.getPointerButton() != null && (binding == Binding.MOUSE_SCROLL_UP || binding == Binding.MOUSE_SCROLL_DOWN)) {
            // The X server ignores a second press of an already pressed button, so press and release right away.
            view.handleInputEvent(binding, true);
            view.handleInputEvent(binding, false);
        }
        else {
            view.handleInputEvent(binding, true);
            final Binding finalBinding = binding;
            view.postDelayed(() -> view.handleInputEvent(finalBinding, false), KEY_RELEASE_DELAY);
        }
    }

    /** Moves the wheel by delta (px for STRIP, radians for DIAL) and emits ticks. Positive = up. */
    private void applyDelta(float delta) {
        Style style = element.getScrollStyle();
        float notch = style == Style.DIAL ? DIAL_TICK_RADIANS : getNotchSize();

        phase = (phase + delta) % notch;
        accumulator += delta;

        int ticks = (int)(accumulator / notch);
        if (ticks != 0) {
            accumulator -= ticks * notch;
            int count = Math.min(Math.abs(ticks), MAX_TICKS_PER_EVENT);
            for (int i = 0; i < count; i++) tick(ticks > 0);
        }
    }

    /* ----------------------------------------------------------------- touch */

    public boolean isTouching() {
        return touching;
    }

    public void handleTouchDown(float x, float y) {
        stopFling();
        touching = true;
        accumulator = 0;
        velocity = 0;
        lastMoveTime = System.nanoTime() / 1000000L;

        switch (element.getScrollStyle()) {
            case STRIP:
                lastPosition = isVertical() ? y : x;
                break;
            case DIAL: {
                Rect box = element.getBoundingBox();
                lastPosition = (float)Math.atan2(y - box.centerY(), x - box.centerX());
                break;
            }
            case BUTTONS: {
                Rect box = element.getBoundingBox();
                boolean up = isVertical() ? y < box.centerY() : x > box.centerX();
                holdDirection = up ? 1 : -1;
                repeatCount = 0;
                tick(up);
                view.removeCallbacks(repeatRunnable);
                view.postDelayed(repeatRunnable, REPEAT_START_DELAY);
                break;
            }
        }
    }

    public void handleTouchMove(float x, float y) {
        if (!touching) return;
        long now = System.nanoTime() / 1000000L;
        float dt = Math.max(1, now - lastMoveTime) / 1000.0f;
        lastMoveTime = now;

        switch (element.getScrollStyle()) {
            case STRIP: {
                float position = isVertical() ? y : x;
                // finger up on a vertical ribbon = scroll up, finger right on a horizontal one = scroll up
                float delta = isVertical() ? lastPosition - position : position - lastPosition;
                lastPosition = position;
                updateVelocity(delta, dt);
                applyDelta(delta);
                break;
            }
            case DIAL: {
                Rect box = element.getBoundingBox();
                float dx = x - box.centerX();
                float dy = y - box.centerY();
                if (Math.hypot(dx, dy) < box.width() * 0.5f * DIAL_DEAD_ZONE) break;

                float angle = (float)Math.atan2(dy, dx);
                float delta = angle - lastPosition;
                while (delta > Math.PI) delta -= (float)(Math.PI * 2);
                while (delta < -Math.PI) delta += (float)(Math.PI * 2);
                lastPosition = angle;
                // clockwise = scroll up
                updateVelocity(delta, dt);
                applyDelta(delta);
                break;
            }
            case BUTTONS: {
                // sliding onto the other half switches the direction
                Rect box = element.getBoundingBox();
                int direction = (isVertical() ? y < box.centerY() : x > box.centerX()) ? 1 : -1;
                if (direction != holdDirection) {
                    holdDirection = direction;
                    repeatCount = 0;
                }
                break;
            }
        }
        view.invalidate();
    }

    private void updateVelocity(float delta, float dt) {
        float instant = delta / dt;
        velocity = velocity * 0.6f + instant * 0.4f;
    }

    public void handleTouchUp() {
        if (!touching) return;
        touching = false;
        view.removeCallbacks(repeatRunnable);
        holdDirection = 0;

        Style style = element.getScrollStyle();
        if (style != Style.BUTTONS) {
            long idle = System.nanoTime() / 1000000L - lastMoveTime;
            float speed = Math.abs(velocity);
            if (idle < 90 && speed >= getMinFlingVelocity()) {
                velocity = Mathf.clamp(velocity, -getMaxFlingVelocity(), getMaxFlingVelocity());
                flinging = true;
                lastFlingTime = System.nanoTime() / 1000000L;
                view.postOnAnimation(flingRunnable);
            }
        }
        view.invalidate();
    }

    /** Stops timers and inertia, used when the element is removed or its type changes. */
    public void stopFling() {
        flinging = false;
        velocity = 0;
        view.removeCallbacks(flingRunnable);
        view.removeCallbacks(repeatRunnable);
    }

    /* ------------------------------------------------------------------ draw */

    private static int withAlpha(int color, float factor) {
        int alpha = (int)(((color >>> 24) & 0xff) * Mathf.clamp(factor, 0, 1));
        return (color & 0x00ffffff) | (alpha << 24);
    }

    public void draw(Canvas canvas, Paint paint, Rect box, int lightColor, boolean pressed) {
        switch (element.getScrollStyle()) {
            case DIAL:
                drawDial(canvas, paint, box, lightColor, pressed);
                break;
            case BUTTONS:
                drawButtons(canvas, paint, box, lightColor, pressed);
                break;
            case STRIP:
            default:
                drawStrip(canvas, paint, box, lightColor, pressed);
                break;
        }
    }

    private void drawArrow(Canvas canvas, Paint paint, float cx, float cy, float size, int direction) {
        // direction: 0 up, 1 right, 2 down, 3 left
        Path path = new Path();
        switch (direction) {
            case 0:
                path.moveTo(cx, cy - size);
                path.lineTo(cx - size, cy + size * 0.6f);
                path.lineTo(cx + size, cy + size * 0.6f);
                break;
            case 1:
                path.moveTo(cx + size, cy);
                path.lineTo(cx - size * 0.6f, cy - size);
                path.lineTo(cx - size * 0.6f, cy + size);
                break;
            case 2:
                path.moveTo(cx, cy + size);
                path.lineTo(cx - size, cy - size * 0.6f);
                path.lineTo(cx + size, cy - size * 0.6f);
                break;
            default:
                path.moveTo(cx - size, cy);
                path.lineTo(cx + size * 0.6f, cy - size);
                path.lineTo(cx + size * 0.6f, cy + size);
                break;
        }
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawStrip(Canvas canvas, Paint paint, Rect box, int lightColor, boolean pressed) {
        int snap = view.getSnappingSize();
        float scale = element.getScale();
        boolean vertical = isVertical();
        float radius = snap * 1.0f * scale;
        float notch = getNotchSize();

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(lightColor);
        rectF.set(box.left, box.top, box.right, box.bottom);
        canvas.drawRoundRect(rectF, radius, radius, paint);

        if (pressed || flinging) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(lightColor, 0.18f));
            canvas.drawRoundRect(rectF, radius, radius, paint);
        }

        clipPath.reset();
        clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);

        float length = vertical ? box.height() : box.width();
        float thickness = vertical ? box.width() : box.height();
        float centerAlong = vertical ? box.centerY() : box.centerX();
        float start = (vertical ? box.top : box.left);

        paint.setStyle(Paint.Style.STROKE);
        float oldStroke = paint.getStrokeWidth();
        paint.setStrokeWidth(Math.max(2, snap * 0.18f));

        // phase > 0 means content moved "up", i.e. towards the top (vertical) or to the right (horizontal)
        float shift = vertical ? -phase : phase;
        int count = (int)(length / notch) + 3;
        for (int i = -1; i < count; i++) {
            float pos = start + i * notch + shift;
            float d = Math.abs(pos - centerAlong) / (length * 0.5f);     // 0 center .. 1 edge
            float fade = Mathf.clamp(1.0f - d * d * 0.85f, 0.12f, 1.0f);
            float inset = thickness * (0.18f + 0.32f * d);              // lines shorten towards the edges (barrel look)
            paint.setColor(withAlpha(lightColor, fade));
            if (vertical) canvas.drawLine(box.left + inset, pos, box.right - inset, pos, paint);
            else canvas.drawLine(pos, box.top + inset, pos, box.bottom - inset, paint);
        }
        paint.setStrokeWidth(oldStroke);
        canvas.restore();

        // direction hints at both ends
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(lightColor, 0.9f));
        float size = snap * 0.6f * scale;
        float margin = snap * 1.2f * scale;
        if (vertical) {
            drawArrow(canvas, paint, box.centerX(), box.top + margin, size, 0);
            drawArrow(canvas, paint, box.centerX(), box.bottom - margin, size, 2);
        }
        else {
            drawArrow(canvas, paint, box.left + margin, box.centerY(), size, 3);
            drawArrow(canvas, paint, box.right - margin, box.centerY(), size, 1);
        }
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawDial(Canvas canvas, Paint paint, Rect box, int lightColor, boolean pressed) {
        int snap = view.getSnappingSize();
        float scale = element.getScale();
        float cx = box.centerX();
        float cy = box.centerY();
        float radius = box.width() * 0.5f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(lightColor);
        canvas.drawCircle(cx, cy, radius, paint);

        if (pressed || flinging) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(lightColor, 0.18f));
            canvas.drawCircle(cx, cy, radius, paint);
        }

        // rotating notches along the rim
        float oldStroke = paint.getStrokeWidth();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2, snap * 0.18f));
        paint.setColor(withAlpha(lightColor, 0.9f));
        float outer = radius - snap * 0.5f * scale;
        float inner = radius - snap * 1.7f * scale;
        int notches = Math.round((float)(Math.PI * 2 / DIAL_TICK_RADIANS));
        for (int i = 0; i < notches; i++) {
            float angle = i * DIAL_TICK_RADIANS + phase;
            float cos = (float)Math.cos(angle);
            float sin = (float)Math.sin(angle);
            canvas.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * outer, cy + sin * outer, paint);
        }
        paint.setStrokeWidth(oldStroke);

        // inner cap with a marker that follows the finger
        float capRadius = radius * 0.5f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(withAlpha(lightColor, 0.7f));
        canvas.drawCircle(cx, cy, capRadius, paint);

        float markerAngle = phase - (float)(Math.PI * 0.5);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(lightColor, 0.95f));
        canvas.drawCircle(cx + (float)Math.cos(markerAngle) * capRadius * 0.62f, cy + (float)Math.sin(markerAngle) * capRadius * 0.62f, snap * 0.45f * scale, paint);

        // "+" / "-" hints in the cap
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(snap * 1.6f * scale);
        paint.setColor(withAlpha(lightColor, 0.8f));
        canvas.drawText("\u21BB", cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawButtons(Canvas canvas, Paint paint, Rect box, int lightColor, boolean pressed) {
        int snap = view.getSnappingSize();
        float scale = element.getScale();
        boolean vertical = isVertical();
        float radius = snap * 1.0f * scale;

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(lightColor);
        rectF.set(box.left, box.top, box.right, box.bottom);
        canvas.drawRoundRect(rectF, radius, radius, paint);

        // divider
        if (vertical) canvas.drawLine(box.left, box.centerY(), box.right, box.centerY(), paint);
        else canvas.drawLine(box.centerX(), box.top, box.centerX(), box.bottom, paint);

        float size = snap * 1.1f * scale;
        for (int half = 0; half < 2; half++) {
            // half 0 = "up" cell, half 1 = "down" cell
            boolean up = half == 0;
            float hx, hy;
            if (vertical) {
                hx = box.centerX();
                hy = up ? (box.top + box.centerY()) * 0.5f : (box.centerY() + box.bottom) * 0.5f;
            }
            else {
                hy = box.centerY();
                hx = up ? (box.centerX() + box.right) * 0.5f : (box.left + box.centerX()) * 0.5f;
            }

            boolean active = touching && holdDirection == (up ? 1 : -1);
            if (active) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(lightColor, 0.35f));
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                if (vertical) {
                    if (up) canvas.drawRect(box.left, box.top, box.right, box.centerY(), paint);
                    else canvas.drawRect(box.left, box.centerY(), box.right, box.bottom, paint);
                }
                else {
                    if (up) canvas.drawRect(box.centerX(), box.top, box.right, box.bottom, paint);
                    else canvas.drawRect(box.left, box.top, box.centerX(), box.bottom, paint);
                }
                canvas.restore();
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(lightColor, active ? 1.0f : 0.85f));
            int direction = vertical ? (up ? 0 : 2) : (up ? 1 : 3);
            drawArrow(canvas, paint, hx, hy, size, direction);
        }
        paint.setStyle(Paint.Style.STROKE);
    }
}
