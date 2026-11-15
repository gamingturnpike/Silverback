package com.echoes.silverback;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Animated effects drawn over the Silverback After Dark art.
 * Everything is placed in the art's own 1672x941 coordinates.
 */
public class EffectsView extends View {

    private static final float W = 1672f, H = 941f;
    private static final long FRAME_MS = 33; // ~30 fps

    private final Random rnd = new Random();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cover = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    // Glow spots: x, y, radius
    private static final float[] LANTERN = {38, 345, 95};
    private static final float[] TORCH_A = {1448, 630, 62};
    private static final float[] TORCH_B = {1580, 556, 72};
    private static final float[] EYE_L = {1455, 272, 30};
    private static final float[] EYE_R = {1570, 270, 30};
    private static final float[] MOON = {1345, 64, 115};

    // Waveform panels (the red bars beside "Welcome to the Den")
    private static final float[][] BARS = {{218, 742, 430, 818}, {1022, 742, 1248, 818}};
    private static final int BAR_COUNT = 30;
    private final float[][] barLevel = new float[2][BAR_COUNT];

    // Embers
    private static final int EMBERS = 46;
    private final float[] ex = new float[EMBERS], ey = new float[EMBERS], evx = new float[EMBERS],
            evy = new float[EMBERS], elife = new float[EMBERS], emax = new float[EMBERS],
            esize = new float[EMBERS], ephase = new float[EMBERS];

    // Fog blobs
    private static final int FOG = 7;
    private final float[] fx = new float[FOG], fy = new float[FOG], fr = new float[FOG], fv = new float[FOG];

    // Waterfall streaks
    private static final int STREAKS = 9;
    private final float[] sx = new float[STREAKS], sy = new float[STREAKS], sl = new float[STREAKS], sv = new float[STREAKS];

    private RadialGradient warm, red, moonGlow, fogGlow, emberGlow;
    private long last;
    private float lanternFlick = 1, torchAFlick = 1, torchBFlick = 1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public EffectsView(Context c) { super(c); init(); }

    public EffectsView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        // Unit-radius gradients, scaled per use
        warm = new RadialGradient(0, 0, 1, new int[]{Color.argb(255, 255, 190, 90),
                Color.argb(120, 255, 110, 30), Color.argb(0, 255, 80, 0)}, new float[]{0, 0.35f, 1}, Shader.TileMode.CLAMP);
        red = new RadialGradient(0, 0, 1, new int[]{Color.argb(255, 255, 60, 60),
                Color.argb(110, 255, 0, 20), Color.argb(0, 255, 0, 0)}, new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP);
        moonGlow = new RadialGradient(0, 0, 1, new int[]{Color.argb(150, 220, 230, 255),
                Color.argb(60, 170, 180, 255), Color.argb(0, 150, 150, 255)}, new float[]{0.35f, 0.55f, 1}, Shader.TileMode.CLAMP);
        fogGlow = new RadialGradient(0, 0, 1, new int[]{Color.argb(70, 210, 185, 215),
                Color.argb(0, 200, 170, 210)}, null, Shader.TileMode.CLAMP);
        emberGlow = new RadialGradient(0, 0, 1, new int[]{Color.argb(255, 255, 210, 120),
                Color.argb(160, 255, 90, 20), Color.argb(0, 255, 40, 0)}, new float[]{0, 0.3f, 1}, Shader.TileMode.CLAMP);

        glow.setBlendMode(BlendMode.PLUS);
        dot.setBlendMode(BlendMode.PLUS);
        dot.setShader(emberGlow);
        barGlow.setBlendMode(BlendMode.PLUS);
        barGlow.setMaskFilter(new BlurMaskFilter(5, BlurMaskFilter.Blur.NORMAL));
        barGlow.setColor(Color.argb(150, 255, 20, 50));
        bar.setColor(Color.rgb(255, 40, 70));
        cover.setColor(Color.argb(240, 4, 0, 0));
        cover.setMaskFilter(new BlurMaskFilter(4, BlurMaskFilter.Blur.NORMAL));
        streak.setBlendMode(BlendMode.PLUS);
        streak.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < EMBERS; i++) spawnEmber(i, true);
        for (int i = 0; i < FOG; i++) {
            fx[i] = rnd.nextFloat() * W;
            fy[i] = 835 + rnd.nextFloat() * 90;
            fr[i] = 150 + rnd.nextFloat() * 140;
            fv[i] = (6 + rnd.nextFloat() * 14) * (rnd.nextBoolean() ? 1 : -1);
        }
        for (int i = 0; i < STREAKS; i++) spawnStreak(i, true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = SystemClock.uptimeMillis();
        removeCallbacks(tick);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void spawnEmber(int i, boolean anywhere) {
        int src = rnd.nextInt(10);
        if (src < 3) {            // from torch A / B
            float[] t = src == 0 ? TORCH_A : TORCH_B;
            ex[i] = t[0] + (rnd.nextFloat() - 0.5f) * 14;
            ey[i] = t[1] - 10;
        } else if (src == 3) {    // from lantern
            ex[i] = LANTERN[0] + (rnd.nextFloat() - 0.5f) * 20;
            ey[i] = LANTERN[1];
        } else {                  // from the ground
            ex[i] = rnd.nextFloat() * W;
            ey[i] = H - rnd.nextFloat() * 80;
        }
        evx[i] = (rnd.nextFloat() - 0.5f) * 14;
        evy[i] = -(18 + rnd.nextFloat() * 42);
        emax[i] = 3 + rnd.nextFloat() * 4;
        elife[i] = anywhere ? rnd.nextFloat() * emax[i] : 0;
        esize[i] = 1.6f + rnd.nextFloat() * 2.6f;
        ephase[i] = rnd.nextFloat() * 6.28f;
    }

    private void spawnStreak(int i, boolean anywhere) {
        sx[i] = 1410 + rnd.nextFloat() * 66;
        sl[i] = 22 + rnd.nextFloat() * 40;
        sv[i] = 110 + rnd.nextFloat() * 90;
        sy[i] = anywhere ? 440 + rnd.nextFloat() * 160 : 430 - sl[i];
    }

    private float flicker(float current) {
        float target = 0.72f + rnd.nextFloat() * 0.38f;
        return current + (target - current) * 0.35f;
    }

    private void glowAt(Canvas c, float[] spot, RadialGradient g, float strength, float scale) {
        glow.setShader(g);
        glow.setAlpha((int) (255 * Math.max(0, Math.min(1, strength))));
        c.save();
        c.translate(spot[0], spot[1]);
        float r = spot[2] * scale;
        c.scale(r, r);
        c.drawCircle(0, 0, 1, glow);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float t = now / 1000f;
        boolean playing = Player.get(getContext()).isPlaying();

        c.save();
        c.scale(getWidth() / W, getHeight() / H);

        // 7. Moon glow
        glowAt(c, MOON, moonGlow, 0.55f + 0.25f * (float) Math.sin(t * 0.8f), 1);

        // 3. Gorilla eyes pulsing
        float eye = 0.45f + 0.45f * (float) Math.sin(t * 1.7f);
        glowAt(c, EYE_L, red, eye, 1);
        glowAt(c, EYE_R, red, eye, 1);

        // 8. Waterfall shimmer
        c.save();
        c.clipRect(1405, 440, 1482, 605);
        for (int i = 0; i < STREAKS; i++) {
            sy[i] += sv[i] * dt;
            if (sy[i] > 605) spawnStreak(i, false);
            streak.setStrokeWidth(2.2f);
            streak.setColor(Color.argb(70, 230, 235, 255));
            c.drawLine(sx[i], sy[i], sx[i], sy[i] + sl[i], streak);
        }
        c.restore();

        // 1 & 2. Torches and lantern flicker
        lanternFlick = flicker(lanternFlick);
        torchAFlick = flicker(torchAFlick);
        torchBFlick = flicker(torchBFlick);
        glowAt(c, LANTERN, warm, 0.55f * lanternFlick, 0.9f + 0.1f * lanternFlick);
        glowAt(c, TORCH_A, warm, 0.75f * torchAFlick, 0.85f + 0.15f * torchAFlick);
        glowAt(c, TORCH_B, warm, 0.75f * torchBFlick, 0.85f + 0.15f * torchBFlick);

        // 4. Waveform bars (bounce to a steady beat while music plays)
        float beat = (float) Math.pow(Math.max(0, Math.sin(t * Math.PI * 2 * 2.0)), 3); // ~120 BPM
        for (int p = 0; p < 2; p++) {
            float[] b = BARS[p];
            tmp.set(b[0] - 2, b[1], b[2] + 2, b[3]);
            c.drawRoundRect(tmp, 6, 6, cover);
            float cy = (b[1] + b[3]) / 2f, maxH = (b[3] - b[1]) / 2f - 3;
            float step = (b[2] - b[0]) / BAR_COUNT;
            for (int i = 0; i < BAR_COUNT; i++) {
                float centre = 1f - Math.abs(i - (BAR_COUNT - 1) / 2f) / (BAR_COUNT / 2f); // taller in the middle
                float target = playing
                        ? (0.15f + 0.85f * centre) * (0.35f + 0.4f * beat + 0.45f * rnd.nextFloat())
                        : 0.06f + 0.04f * (float) Math.sin(t * 2 + i);
                barLevel[p][i] += (target - barLevel[p][i]) * (target > barLevel[p][i] ? 0.55f : 0.18f);
                float h = Math.max(2, Math.min(1, barLevel[p][i]) * maxH);
                float x = b[0] + step * (i + 0.5f);
                tmp.set(x - 1.6f, cy - h, x + 1.6f, cy + h);
                c.drawRect(tmp, barGlow);
                c.drawRoundRect(tmp, 1.5f, 1.5f, bar);
            }
        }

        // 5. Fog drifting across the bottom
        glow.setShader(fogGlow);
        glow.setAlpha(255);
        for (int i = 0; i < FOG; i++) {
            fx[i] += fv[i] * dt;
            if (fx[i] > W + fr[i] * 2) fx[i] = -fr[i] * 2;
            if (fx[i] < -fr[i] * 2) fx[i] = W + fr[i] * 2;
            c.save();
            c.translate(fx[i], fy[i] + 6 * (float) Math.sin(t * 0.4f + i));
            c.scale(fr[i] * 2.2f, fr[i] * 0.55f);
            c.drawCircle(0, 0, 1, glow);
            c.restore();
        }

        // 6. Embers floating up
        for (int i = 0; i < EMBERS; i++) {
            elife[i] += dt;
            if (elife[i] > emax[i] || ey[i] < 0) {
                spawnEmber(i, false);
                continue;
            }
            ex[i] += (evx[i] + 10 * (float) Math.sin(t * 1.3f + ephase[i])) * dt;
            ey[i] += evy[i] * dt;
            float life = elife[i] / emax[i];
            float fade = life < 0.15f ? life / 0.15f : 1f - (life - 0.15f) / 0.85f;
            float twinkle = 0.75f + 0.25f * (float) Math.sin(t * 9 + ephase[i]);
            dot.setAlpha((int) (255 * Math.max(0, fade) * twinkle));
            c.save();
            c.translate(ex[i], ey[i]);
            float r = esize[i] * 3.2f;
            c.scale(r, r);
            c.drawCircle(0, 0, 1, dot);
            c.restore();
        }

        c.restore();
    }
}
