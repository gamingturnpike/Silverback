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
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getId() == R.id.window) {
                c.measure(MeasureSpec.makeMeasureSpec(DESIGN_W, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(DESIGN_H, MeasureSpec.EXACTLY));
            } else if (c.getId() == R.id.art_ui) {
                c.measure(MeasureSpec.makeMeasureSpec((int) ART_W, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec((int) ART_H, MeasureSpec.EXACTLY));
            } else {
                c.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        float scale = Math.min(w / ART_W, h / ART_H);
        int sw = Math.round(ART_W * scale), sh = Math.round(ART_H * scale);
        int ox = (w - sw) / 2, oy = (h - sh) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getId() == R.id.window) {
                // song window, scaled into the art's black screen
                int bx = ox + Math.round(BOX_L * scale), by = oy + Math.round(BOX_T * scale);
                c.layout(bx, by, bx + DESIGN_W, by + DESIGN_H);
                c.setPivotX(0);
                c.setPivotY(0);
                float s = (BOX_W * scale) / DESIGN_W;
                c.setScaleX(s);
                c.setScaleY(s);
            } else if (c.getId() == R.id.art_ui) {
                // controls laid out in the art's own 1672x941 space, scaled with the stage
                c.layout(ox, oy, ox + (int) ART_W, oy + (int) ART_H);
                c.setPivotX(0);
                c.setPivotY(0);
                c.setScaleX(scale);
                c.setScaleY(scale);
            } else {
                c.layout(ox, oy, ox + sw, oy + sh);
            }
        }
    }
}
