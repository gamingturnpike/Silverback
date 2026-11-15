package com.echoes.silverback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Fits the SilverBack overlay (1672x941) on screen and drops the song window
 * exactly into the overlay's black screen area.
 * Every child except the last fills the whole stage (overlay art, effects);
 * the last child is the song window (designed at 1095x462 px, scaled to fit).
 */
public class StageLayout extends ViewGroup {

    private static final float ART_W = 1672f, ART_H = 941f;
    private static final float BOX_L = 158f, BOX_T = 227f, BOX_W = 1095f, BOX_H = 462f;
    static final int DESIGN_W = 1095, DESIGN_H = 462;

    public StageLayout(Context c) { super(c); }

    public StageLayout(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        float scale = Math.min(w / ART_W, h / ART_H);
        int sw = Math.round(ART_W * scale), sh = Math.round(ART_H * scale);
        int n = getChildCount();
        for (int i = 0; i < n - 1; i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY));
        }
        getChildAt(n - 1).measure(MeasureSpec.makeMeasureSpec(DESIGN_W, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(DESIGN_H, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        float scale = Math.min(w / ART_W, h / ART_H);
        int sw = Math.round(ART_W * scale), sh = Math.round(ART_H * scale);
        int ox = (w - sw) / 2, oy = (h - sh) / 2;
        int n = getChildCount();
        for (int i = 0; i < n - 1; i++) getChildAt(i).layout(ox, oy, ox + sw, oy + sh);

        View win = getChildAt(n - 1);
        int bx = ox + Math.round(BOX_L * scale), by = oy + Math.round(BOX_T * scale);
        win.layout(bx, by, bx + DESIGN_W, by + DESIGN_H);
        win.setPivotX(0);
        win.setPivotY(0);
        float s = (BOX_W * scale) / DESIGN_W;
        win.setScaleX(s);
        win.setScaleY(s);
    }
}
