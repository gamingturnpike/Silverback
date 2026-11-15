package com.echoes.silverback;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/** 🍌 Bananas fall from the top of the stage whenever a song gets added. */
public class BananaRainView extends View {

    private static final int COUNT = 36;
    private static final long SPAWN_MS = 1600;   // bananas keep starting for this long
    private static final long FRAME_MS = 16;

    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] x = new float[COUNT], y = new float[COUNT], vy = new float[COUNT],
            rot = new float[COUNT], spin = new float[COUNT], size = new float[COUNT],
            sway = new float[COUNT], phase = new float[COUNT];
    private final long[] startAt = new long[COUNT];
    private boolean active;
    private long last;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (active) postDelayed(this, FRAME_MS);
        }
    };

    public BananaRainView(Context c) { super(c); init(); }

    public BananaRainView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        paint.setTextAlign(Paint.Align.CENTER);
        setClickable(false);
        setFocusable(false);
    }

    /** Start (or restart) a banana shower. */
    public void drop() {
        if (getWidth() == 0) return;
        long now = SystemClock.uptimeMillis();
        float h = getHeight();
        for (int i = 0; i < COUNT; i++) {
            size[i] = h * (0.06f + rnd.nextFloat() * 0.05f);
            x[i] = rnd.nextFloat() * getWidth();
            y[i] = -size[i] * (1 + rnd.nextFloat());
            vy[i] = h * (0.35f + rnd.nextFloat() * 0.35f); // px per second
            rot[i] = rnd.nextFloat() * 360f;
            spin[i] = (rnd.nextFloat() - 0.5f) * 360f;
            sway[i] = size[i] * (0.3f + rnd.nextFloat() * 0.6f);
            phase[i] = rnd.nextFloat() * 6.28f;
            startAt[i] = now + (long) (rnd.nextFloat() * SPAWN_MS);
        }
        last = now;
        active = true;
        removeCallbacks(tick);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!active) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;
        boolean any = false;
        float t = now / 1000f;
        for (int i = 0; i < COUNT; i++) {
            if (now < startAt[i]) {
                any = true;
                continue;
            }
            vy[i] += getHeight() * 0.25f * dt; // a little gravity
            y[i] += vy[i] * dt;
            rot[i] += spin[i] * dt;
            if (y[i] - size[i] > getHeight()) continue;
            any = true;
            float sx = x[i] + sway[i] * (float) Math.sin(t * 2.2f + phase[i]);
            paint.setTextSize(size[i]);
            c.save();
            c.rotate(rot[i], sx, y[i]);
            c.drawText("🍌", sx, y[i] + size[i] * 0.35f, paint);
            c.restore();
        }
        if (!any) active = false;
    }
}
