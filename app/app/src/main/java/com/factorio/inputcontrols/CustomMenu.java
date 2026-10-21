package com.factorio.inputcontrols;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.SparseArray;

import com.factorio.inputcontrols.ControlElement.RadialTrigger;
import com.factorio.inputcontrols.ControlElement.Shape;
import com.factorio.math.Mathf;
import com.factorio.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * "Custom" menu: a root button; when it is opened the buttons fly out of its center to the places the user
 * put them in the editor (position, size and shape of every button are free). Every button can have its own
 * buttons that fly out of it in the same way, so a menu is a tree.
 *
 * The thread between a button and its parent is only drawn in the editor.
 *
 * Opening: TAP (tap the root to open/close), HOLD (hold the root, slide to a button and release) or
 * HYBRID (a short tap leaves the menu open, a long hold works like HOLD). The trigger is shared with the
 * radial menu (ControlElement.getRadialTrigger()).
 */
public class CustomMenu {
    public static class Node {
        public Binding binding = Binding.NONE;
        public float dx;                      // distance from the parent's center, in snap units
        public float dy;
        public float size = 2.5f;             // half size, in snap units
        public Shape shape = Shape.CIRCLE;
        public String text = "";
        public boolean hideOnPath = false;    // hide this button while one of its sub-buttons is being chosen
        public final ArrayList<Node> children = new ArrayList<>();
        public Node parent;

        // runtime state
        boolean open;
        float progress;
        float target;
        float delay;
        float fade = 1;
        float fadeTarget = 1;
        boolean pressed;
        boolean hover;

        public boolean isRoot() {
            return parent == null;
        }
    }

    private static final float OPEN_DURATION = 230.0f;       // ms
    private static final float STAGGER = 35.0f;              // ms between sibling buttons
    private static final float VISIBLE_PROGRESS = 0.55f;
    private static final float FADE_DURATION = 160.0f;       // ms
    private static final long HOVER_DWELL = 140;             // ms a finger has to rest on a button (HOLD) to open its sub-buttons
    private static final float POSITION_STEP = 0.5f;         // snap units, used when dragging in the editor
    private static final float MIN_SIZE = 1.0f;
    private static final float MAX_SIZE = 8.0f;

    private final InputControlsView view;
    private final ControlElement element;
    private final Node root = new Node();
    private Node selectedNode;
    private Runnable onSelectionChanged;

    private boolean animating = false;
    private long lastFrameTime;
    private int holdPointerId = -1;
    private long holdDownTime;
    private boolean openedOnThisTouch;
    private boolean closedOnThisTouch;
    private Node hoverNode;
    private Node pendingNode;
    private final SparseArray<Node> pointerNodes = new SparseArray<>();

    private final PointF tmpPoint = new PointF();
    private final RectF tmpRect = new RectF();

    private final Runnable commitRunnable = new Runnable() {
        @Override
        public void run() {
            Node node = pendingNode;
            pendingNode = null;
            if (node != null && node == hoverNode && !node.open) setOpen(node, true);
        }
    };

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime() / 1000000L;
            float dt = Math.min(50, now - lastFrameTime);
            lastFrameTime = now;
            boolean busy = step(root, dt);
            view.invalidate();
            if (busy) view.postOnAnimation(this);
            else animating = false;
        }
    };

    public CustomMenu(InputControlsView view, ControlElement element) {
        this.view = view;
        this.element = element;
    }

    /* ------------------------------------------------------------------ model */

    public Node getRoot() {
        return root;
    }

    public void createDefaultNodes() {
        root.children.clear();
        float[][] positions = {{-5, -5}, {0, -7}, {5, -5}};
        for (float[] position : positions) {
            Node node = new Node();
            node.dx = position[0];
            node.dy = position[1];
            node.parent = root;
            root.children.add(node);
        }
    }

    public Node addChild(Node parent) {
        if (parent == null) parent = root;
        Node node = new Node();
        node.parent = parent;

        // put the new button on a free spot around its parent (clockwise, starting at the top)
        int index = parent.children.size();
        double angle = -Math.PI * 0.5 + index * (Math.PI / 3.0);
        float distance = parent.isRoot() ? 6.5f : 6.0f;
        node.dx = Math.round(Math.cos(angle) * distance / POSITION_STEP) * POSITION_STEP;
        node.dy = Math.round(Math.sin(angle) * distance / POSITION_STEP) * POSITION_STEP;

        parent.children.add(node);
        parent.open = true;
        node.progress = node.target = 1;
        view.invalidate();
        return node;
    }

    public void removeNode(Node node) {
        if (node == null || node.parent == null) return;
        node.parent.children.remove(node);
        if (selectedNode != null && isInSubtree(selectedNode, node)) selectNode(null);
        view.invalidate();
    }

    private boolean isInSubtree(Node candidate, Node subtreeRoot) {
        for (Node n = candidate; n != null; n = n.parent) {
            if (n == subtreeRoot) return true;
        }
        return false;
    }

    public int getNodeCount() {
        return countNodes(root) - 1;
    }

    private int countNodes(Node node) {
        int count = 1;
        for (Node child : node.children) count += countNodes(child);
        return count;
    }

    public Node getSelectedNode() {
        return selectedNode;
    }

    public void selectNode(Node node) {
        if (selectedNode == node) return;
        selectedNode = node;
        if (onSelectionChanged != null) onSelectionChanged.run();
        view.invalidate();
    }

    public void setOnSelectionChanged(Runnable listener) {
        this.onSelectionChanged = listener;
    }

    /** "Menu root" or the index path of the selected button, e.g. "2.1". */
    public String getNodePath(Node node) {
        if (node == null || node.parent == null) return "";
        String parentPath = getNodePath(node.parent);
        String own = String.valueOf(node.parent.children.indexOf(node) + 1);
        return parentPath.isEmpty() ? own : parentPath + "." + own;
    }

    private float getUnit() {
        return view.getSnappingSize() * element.getScale();
    }

    public void setNodeSize(Node node, float size) {
        node.size = Mathf.clamp(size, MIN_SIZE, MAX_SIZE);
        view.invalidate();
    }

    /* -------------------------------------------------------------- geometry */

    private static float easeOutBack(float t) {
        t = Mathf.clamp(t, 0, 1);
        final float c1 = 1.35f;
        final float c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    private float getProgress(Node node) {
        return view.isEditMode() ? 1.0f : node.progress;
    }

    private float getFade(Node node) {
        return view.isEditMode() ? 1.0f : node.fade;
    }

    private void getCenter(Node node, PointF out) {
        if (node.parent == null) {
            android.graphics.Rect box = element.getBoundingBox();
            out.set(box.centerX(), box.centerY());
            return;
        }
        getCenter(node.parent, out);
        float unit = getUnit();
        float e = easeOutBack(getProgress(node));
        out.x += node.dx * unit * e;
        out.y += node.dy * unit * e;
    }

    private float getHalfWidth(Node node) {
        float unit = getUnit();
        float scale = Mathf.clamp(getProgress(node), 0, 1);
        switch (node.shape) {
            case RECT:
            case ROUND_RECT:
                return node.size * unit * 1.4f * scale;
            default:
                return node.size * unit * scale;
        }
    }

    private float getHalfHeight(Node node) {
        float unit = getUnit();
        float scale = Mathf.clamp(getProgress(node), 0, 1);
        switch (node.shape) {
            case RECT:
            case ROUND_RECT:
                return node.size * unit * 0.9f * scale;
            default:
                return node.size * unit * scale;
        }
    }

    private boolean nodeContains(Node node, float x, float y) {
        getCenter(node, tmpPoint);
        float slop = getUnit() * 0.3f;
        float hw = getHalfWidth(node) + slop;
        float hh = getHalfHeight(node) + slop;
        if (node.shape == Shape.CIRCLE) {
            return Mathf.distance(tmpPoint.x, tmpPoint.y, x, y) <= hw;
        }
        return Math.abs(x - tmpPoint.x) <= hw && Math.abs(y - tmpPoint.y) <= hh;
    }

    private void collect(Node node, ArrayList<Node> out) {
        for (Node child : node.children) {
            out.add(child);
            collect(child, out);
        }
    }

    private Node hitNode(float x, float y, boolean all) {
        ArrayList<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node node = nodes.get(i);
            if (!all && (node.progress < VISIBLE_PROGRESS || node.fade < 0.5f || !node.parent.open)) continue;
            if (nodeContains(node, x, y)) return node;
        }
        return null;
    }

    /** Editor: the button under the point (all buttons are shown there), or null for the root / nothing. */
    public Node hitNodeEdit(float x, float y) {
        return hitNode(x, y, true);
    }

    public boolean containsPoint(float x, float y) {
        if (element.containsRootPoint(x, y)) return true;
        return hitNode(x, y, view.isEditMode()) != null;
    }

    public PointF getNodeCenter(Node node) {
        PointF point = new PointF();
        getCenter(node, point);
        return point;
    }

    /** Editor: moves the button so that its center is at the given absolute position (snapped to a half step). */
    public void moveNodeTo(Node node, float absoluteX, float absoluteY) {
        if (node.parent == null) return;
        PointF parentCenter = new PointF();
        getCenter(node.parent, parentCenter);
        float unit = Math.max(1, getUnit());
        node.dx = Math.round((absoluteX - parentCenter.x) / unit / POSITION_STEP) * POSITION_STEP;
        node.dy = Math.round((absoluteY - parentCenter.y) / unit / POSITION_STEP) * POSITION_STEP;
        view.invalidate();
    }

    /* ------------------------------------------------------------- animation */

    private boolean step(Node node, float dt) {
        boolean busy = false;
        for (Node child : node.children) {
            if (child.delay > 0) {
                child.delay -= dt;
                busy = true;
            }
            else if (child.progress != child.target) {
                float delta = dt / OPEN_DURATION;
                if (child.target > child.progress) child.progress = Math.min(child.target, child.progress + delta);
                else child.progress = Math.max(child.target, child.progress - delta * 1.3f);
                busy = true;
            }
            if (child.fade != child.fadeTarget) {
                float delta = dt / FADE_DURATION;
                if (child.fadeTarget > child.fade) child.fade = Math.min(child.fadeTarget, child.fade + delta);
                else child.fade = Math.max(child.fadeTarget, child.fade - delta);
                busy = true;
            }
            if (step(child, dt)) busy = true;
        }
        return busy;
    }

    private void startAnimation() {
        if (animating) return;
        animating = true;
        lastFrameTime = System.nanoTime() / 1000000L;
        view.postOnAnimation(frameRunnable);
    }

    private void closeSubtree(Node node) {
        for (Node child : node.children) {
            child.target = 0;
            child.delay = 0;
            child.open = false;
            child.hover = false;
            child.fadeTarget = 1;
            closeSubtree(child);
        }
    }

    public void setOpen(Node node, boolean open) {
        if (node.children.isEmpty() && !node.isRoot()) {
            node.open = false;
            return;
        }
        node.open = open;
        node.fadeTarget = open && node.hideOnPath ? 0 : 1;

        if (node.parent != null) {
            // once a direction is chosen the other buttons of the same level fly back; they return when it is closed
            int position = 0;
            for (Node sibling : node.parent.children) {
                if (sibling != node) {
                    if (open) {
                        sibling.target = 0;
                        sibling.delay = 0;
                        sibling.open = false;
                        sibling.hover = false;
                        sibling.fadeTarget = 1;
                        closeSubtree(sibling);
                    }
                    else if (node.parent.open) {
                        sibling.target = 1;
                        sibling.delay = position * STAGGER;
                    }
                }
                position++;
            }
        }

        if (open) {
            int index = 0;
            for (Node child : node.children) {
                child.target = 1;
                child.delay = index++ * STAGGER;
            }
        }
        else closeSubtree(node);
        startAnimation();
    }

    public boolean isOpen() {
        return root.open;
    }

    public void closeAll() {
        setOpen(root, false);
        hoverNode = null;
    }

    /** Called when the profile is closed / the element is replaced. */
    public void reset() {
        for (int i = 0; i < pointerNodes.size(); i++) {
            Node node = pointerNodes.valueAt(i);
            if (node.binding != Binding.NONE) view.handleInputEvent(node.binding, false);
            node.pressed = false;
        }
        pointerNodes.clear();
        holdPointerId = -1;
        view.removeCallbacks(frameRunnable);
        view.removeCallbacks(commitRunnable);
        pendingNode = null;
        hoverNode = null;
        animating = false;
        resetRuntime(root);
    }

    private void resetRuntime(Node node) {
        node.open = false;
        node.progress = node.target = 0;
        node.fade = node.fadeTarget = 1;
        node.delay = 0;
        node.pressed = false;
        node.hover = false;
        for (Node child : node.children) resetRuntime(child);
    }

    /* ----------------------------------------------------------------- touch */

    private boolean isAutoClose() {
        return element.isDrawerAutoClose();
    }

    private void pulse(final Binding binding) {
        if (binding == Binding.NONE) return;
        view.handleInputEvent(binding, true);
        view.postDelayed(() -> view.handleInputEvent(binding, false), 30);
    }

    /**
     * HOLD mode: the finger moves over the buttons. Resting on a button that has sub-buttons opens them (and the
     * other directions disappear); moving back to a button of an upper level closes the deeper levels again.
     * Empty space between the buttons changes nothing, so the finger can travel to a sub-button.
     */
    private void setHover(Node node) {
        if (hoverNode == node) return;
        if (hoverNode != null) hoverNode.hover = false;
        hoverNode = node;
        view.removeCallbacks(commitRunnable);
        pendingNode = null;

        if (node != null) {
            node.hover = true;
            ArrayList<Node> nodes = new ArrayList<>();
            collect(root, nodes);
            for (Node other : nodes) {
                if (other.open && !isInSubtree(node, other)) setOpen(other, false);
            }
            if (!node.children.isEmpty() && !node.open) {
                pendingNode = node;
                view.postDelayed(commitRunnable, HOVER_DWELL);
            }
        }
        view.invalidate();
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        RadialTrigger trigger = element.getRadialTrigger();

        Node node = hitNode(x, y, false);
        if (node != null) {
            node.pressed = true;
            pointerNodes.put(pointerId, node);
            if (node.binding != Binding.NONE) view.handleInputEvent(node.binding, true);
            if (!node.children.isEmpty()) setOpen(node, !node.open);
            view.invalidate();
            return true;
        }

        if (holdPointerId == -1 && element.containsRootPoint(x, y)) {
            holdPointerId = pointerId;
            holdDownTime = System.currentTimeMillis();
            openedOnThisTouch = false;
            closedOnThisTouch = false;
            element.setRootPressed(true);

            if (trigger == RadialTrigger.TAP) {
                if (root.open) {
                    closeAll();
                    closedOnThisTouch = true;
                }
                else setOpen(root, true);
            }
            else if (root.open) {
                // HYBRID: the menu was left open by a short tap, the next tap on the root closes it
                closeAll();
                closedOnThisTouch = true;
            }
            else {
                setOpen(root, true);
                openedOnThisTouch = true;
            }
            view.invalidate();
            return true;
        }
        return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == holdPointerId) {
            RadialTrigger trigger = element.getRadialTrigger();
            if (trigger != RadialTrigger.TAP && openedOnThisTouch) setHover(hitNode(x, y, false));
            return true;
        }
        return pointerNodes.indexOfKey(pointerId) >= 0;
    }

    public boolean handleTouchUp(int pointerId, float x, float y) {
        int index = pointerNodes.indexOfKey(pointerId);
        if (index >= 0) {
            Node node = pointerNodes.valueAt(index);
            pointerNodes.removeAt(index);
            node.pressed = false;
            if (node.binding != Binding.NONE) view.handleInputEvent(node.binding, false);
            if (isAutoClose() && node.children.isEmpty()) closeAll();
            view.invalidate();
            return true;
        }

        if (pointerId == holdPointerId) {
            holdPointerId = -1;
            element.setRootPressed(false);
            RadialTrigger trigger = element.getRadialTrigger();

            if (trigger != RadialTrigger.TAP && openedOnThisTouch && !closedOnThisTouch) {
                Node chosen = hoverNode;
                if (chosen != null) {
                    chosen.hover = false;
                    hoverNode = null;
                    pulse(chosen.binding);
                    closeAll();
                }
                else {
                    boolean quickTap = System.currentTimeMillis() - holdDownTime < ControlElement.RADIAL_HYBRID_TAP_MILLISECONDS;
                    if (!(trigger == RadialTrigger.HYBRID && quickTap)) closeAll();
                }
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

    /** Draws the threads (editor only) and all visible buttons. The root button is drawn by the element. */
    public void draw(Canvas canvas) {
        Paint paint = view.getPaint();
        boolean edit = view.isEditMode();
        int snap = view.getSnappingSize();
        float unit = getUnit();
        int lightColor = element.getLightColor();
        int darkColor = element.getDarkColor();
        float strokeWidth = snap * 0.25f;

        ArrayList<Node> nodes = new ArrayList<>();
        collect(root, nodes);

        if (edit) {
            // thread: a slightly sagging line between a button and its parent
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, snap * 0.12f));
            paint.setColor(withAlpha(lightColor, 0.65f));
            Path path = new Path();
            PointF from = new PointF();
            PointF to = new PointF();
            for (Node node : nodes) {
                getCenter(node.parent, from);
                getCenter(node, to);
                float mx = (from.x + to.x) * 0.5f;
                float my = (from.y + to.y) * 0.5f;
                float length = Mathf.distance(from.x, from.y, to.x, to.y);
                float sag = length * 0.1f;
                path.reset();
                path.moveTo(from.x, from.y);
                path.quadTo(mx, my + sag, to.x, to.y);
                canvas.drawPath(path, paint);
            }
        }

        paint.setTextAlign(Paint.Align.CENTER);
        for (Node node : nodes) {
            float p = getProgress(node);
            if (p <= 0.001f) continue;

            getCenter(node, tmpPoint);
            float cx = tmpPoint.x;
            float cy = tmpPoint.y;
            float hw = getHalfWidth(node);
            float hh = getHalfHeight(node);
            float fade = getFade(node);
            if (fade <= 0.01f) continue;
            float alpha = Mathf.clamp(p * 1.4f, 0, 1) * fade;
            boolean filled = node.pressed || node.hover;
            boolean selected = edit && node == selectedNode;

            int outline = selected ? element.getHighlightColor() : lightColor;
            paint.setStrokeWidth(selected ? strokeWidth * 1.6f : strokeWidth);
            paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setColor(withAlpha(outline, alpha));

            switch (node.shape) {
                case CIRCLE:
                    canvas.drawCircle(cx, cy, hw, paint);
                    break;
                case RECT:
                    canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint);
                    break;
                case ROUND_RECT:
                    canvas.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, hh, hh, paint);
                    break;
                case SQUARE:
                default: {
                    float radius = snap * 0.75f * element.getScale();
                    canvas.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, radius, radius, paint);
                    break;
                }
            }

            // open sub-menu indicator: a small dot on the rim
            if (!node.children.isEmpty()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(outline, alpha));
                canvas.drawCircle(cx + hw * 0.72f, cy - hh * 0.72f, Math.max(3, snap * 0.35f), paint);
            }

            String text = node.text != null && !node.text.isEmpty() ? node.text : ControlElement.getBindingText(node.binding);
            if (!text.isEmpty() && p > 0.3f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, hw * 2 - strokeWidth * 3), snap * 2 * element.getScale()) * Mathf.clamp(p, 0, 1));
                paint.setColor(withAlpha(filled ? darkColor : lightColor, alpha));
                canvas.drawText(text, cx, cy - ((paint.descent() + paint.ascent()) * 0.5f), paint);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(lightColor);
    }

    /* ------------------------------------------------------------------ JSON */

    public JSONArray toJSONArray() throws JSONException {
        return nodesToJSON(root);
    }

    private JSONArray nodesToJSON(Node parent) throws JSONException {
        JSONArray array = new JSONArray();
        for (Node node : parent.children) {
            JSONObject object = new JSONObject();
            object.put("binding", node.binding.name());
            object.put("dx", node.dx);
            object.put("dy", node.dy);
            object.put("size", node.size);
            object.put("shape", node.shape.name());
            if (node.text != null && !node.text.isEmpty()) object.put("text", node.text);
            if (node.hideOnPath) object.put("hideOnPath", true);
            if (!node.children.isEmpty()) object.put("nodes", nodesToJSON(node));
            array.put(object);
        }
        return array;
    }

    public void loadFromJSON(JSONArray array) throws JSONException {
        root.children.clear();
        loadNodes(root, array);
    }

    private void loadNodes(Node parent, JSONArray array) throws JSONException {
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            Node node = new Node();
            node.parent = parent;
            node.binding = Binding.fromString(object.optString("binding", "NONE"));
            node.dx = (float)object.optDouble("dx", 0);
            node.dy = (float)object.optDouble("dy", 0);
            node.size = Mathf.clamp((float)object.optDouble("size", 2.5), MIN_SIZE, MAX_SIZE);
            try {
                node.shape = Shape.valueOf(object.optString("shape", "CIRCLE"));
            }
            catch (IllegalArgumentException e) {
                node.shape = Shape.CIRCLE;
            }
            node.text = object.optString("text", "");
            node.hideOnPath = object.optBoolean("hideOnPath", false);
            parent.children.add(node);
            if (object.has("nodes")) loadNodes(node, object.getJSONArray("nodes"));
        }
    }
}
