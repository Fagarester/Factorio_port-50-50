package com.factorio.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.factorio.R;

/**
 * Loading animation: Factorio assembling machine 3.
 * "base" is the static machine, "anim" is an 8x8 sheet of 64 frames (140x160 each) that is drawn on top of it.
 * The frame sits at (31, -21) relative to the base (exact pixel match: frame 0 == the base interior),
 * so the canvas is 196x213 with the base shifted down by 21 px.
 */
public class AssemblerLoaderView extends View {
    private static final int COLUMNS = 8;
    private static final int FRAME_COUNT = 64;
    private static final int FRAME_W = 140;
    private static final int FRAME_H = 160;
    private static final int BASE_W = 196;
    private static final int BASE_H = 192;
    private static final int FRAME_X = 31;
    private static final int BASE_Y = 21;
    private static final int CANVAS_W = 196;
    private static final int CANVAS_H = 213;
    private static final float FPS = 30.0f; // animation speed, change to taste

    private Bitmap baseBitmap;
    private Bitmap[] frames;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private long startTime;
    private boolean running = false;

    public AssemblerLoaderView(Context context) {
        this(context, null);
    }

    public AssemblerLoaderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AssemblerLoaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        loadBitmaps();
    }

    private void loadBitmaps() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        baseBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.assembling_machine_3_base, options);
        Bitmap sheet = BitmapFactory.decodeResource(getResources(), R.drawable.assembling_machine_3_anim, options);
        if (sheet == null) return;

        // cut the sheet into separate frames (no bleeding between neighbour frames when scaled)
        frames = new Bitmap[FRAME_COUNT];
        for (int i = 0; i < FRAME_COUNT; i++) {
            frames[i] = Bitmap.createBitmap(sheet, (i % COLUMNS) * FRAME_W, (i / COLUMNS) * FRAME_H, FRAME_W, FRAME_H);
        }
        sheet.recycle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        startTime = System.nanoTime();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseBitmap == null || frames == null) return;

        float scale = Math.min(getWidth() / (float)CANVAS_W, getHeight() / (float)CANVAS_H);
        float offsetX = (getWidth() - CANVAS_W * scale) * 0.5f;
        float offsetY = (getHeight() - CANVAS_H * scale) * 0.5f;

        dst.set(offsetX, offsetY + BASE_Y * scale, offsetX + BASE_W * scale, offsetY + (BASE_Y + BASE_H) * scale);
        canvas.drawBitmap(baseBitmap, null, dst, paint);

        double seconds = (System.nanoTime() - startTime) / 1.0e9;
        int index = ((int)(seconds * FPS)) % FRAME_COUNT;
        dst.set(offsetX + FRAME_X * scale, offsetY, offsetX + (FRAME_X + FRAME_W) * scale, offsetY + FRAME_H * scale);
        canvas.drawBitmap(frames[index], null, dst, paint);

        if (running) postInvalidateOnAnimation();
    }
}
