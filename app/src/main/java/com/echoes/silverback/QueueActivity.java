package com.echoes.silverback;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Your private queue screen. Opens beside the mini stage; viewers never see it. */
public class QueueActivity extends Activity implements Player.Listener {

    private static final int RED = Color.parseColor("#FF2430");
    private static final int BG = Color.parseColor("#0E0E10");
    private static final int CARD = Color.parseColor("#1A1A1E");

    private Player player;
    private ImageView cover;
    private TextView nowTitle, nowArtist, playBtn, count;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        player = Player.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));

        TextView header = text("SILVERBACK QUEUE", 13, RED, true);
        header.setLetterSpacing(0.2f);
        root.addView(header);

        // Now playing card
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.HORIZONTAL);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setPadding(dp(12), dp(12), dp(12), dp(12));
        now.setBackground(rounded(CARD, RED));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(10);
        root.addView(now, nlp);

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.parseColor("#2A0A0C"));
        now.addView(cover, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout nowText = new LinearLayout(this);
        nowText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(0, -2, 1);
        ntp.leftMargin = dp(12);
        now.addView(nowText, ntp);
        TextView label = text("NOW PLAYING", 11, RED, true);
        label.setLetterSpacing(0.15f);
        nowText.addView(label);
        nowTitle = text("", 17, Color.WHITE, true);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowText.addView(nowTitle);
        nowArtist = text("", 14, Color.parseColor("#B5B5B5"), false);
        nowArtist.setSingleLine(true);
        nowText.addView(nowArtist);

        // Controls
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        root.addView(controls, clp);
        playBtn = button("PLAY", v -> player.togglePause());
        controls.addView(playBtn, weight());
        controls.addView(button("SKIP", v -> player.playNext()), weight());
        controls.addView(button("PASTE LINK", v -> paste()), weight());
        controls.addView(button("STAGE", v -> backToStage()), weight());

        count = text("", 12, Color.parseColor("#9A9A9A"), true);
        count.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams cnt = new LinearLayout.LayoutParams(-2, -2);
        cnt.topMargin = dp(16);
        cnt.bottomMargin = dp(6);
        root.addView(count, cnt);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView clear = button("CLEAR UP NEXT", v -> player.clearUpNext());
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, -2);
        xlp.topMargin = dp(8);
        root.addView(clear, xlp);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        player.addListener(this);
        onPlayerChanged();
    }

    @Override
    protected void onStop() {
        player.removeListener(this);
        super.onStop();
    }

    private String lastRendered = "";

    @Override
    public void onPlayerChanged() {
        Song s = player.current;
        nowTitle.setText(s != null ? s.title : "Nothing playing");
        nowArtist.setText(s != null ? s.artist : "Share or paste a Suno link");
        cover.setImageBitmap(s != null ? s.cover : null);
        playBtn.setText(player.isPlaying() ? "PAUSE" : "PLAY");

        // Rebuild the list only when the queue itself changed
        StringBuilder key = new StringBuilder();
        for (Song q : player.queue) key.append(q.id).append(q.cover != null ? '+' : '-');
        if (key.toString().equals(lastRendered)) return;
        lastRendered = key.toString();

        int n = player.queue.size();
        count.setText("UP NEXT  ·  " + n + (n == 1 ? " SONG" : " SONGS"));
        list.removeAllViews();
        for (int i = 0; i < n; i++) list.addView(row(i, player.queue.get(i), n));
        if (n == 0) {
            TextView empty = text("Queue is empty. Links from Discord, Share, or Paste Link show up here.",
                    14, Color.parseColor("#777777"), false);
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
        }
    }

    private View row(int i, Song q, int n) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(8), dp(4), dp(8));
        r.setBackground(rounded(CARD, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(6);
        r.setLayoutParams(rlp);

        TextView num = text(String.valueOf(i + 1), 14, RED, true);
        num.setGravity(Gravity.CENTER);
        r.addView(num, new LinearLayout.LayoutParams(dp(24), -2));

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Color.parseColor("#2A0A0C"));
        art.setImageBitmap(q.cover);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44));
        alp.leftMargin = dp(4);
        r.addView(art, alp);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(10);
        r.addView(t, tlp);
        TextView title = text(q.title, 15, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(title);
        TextView artist = text(q.artist, 12, Color.parseColor("#9A9A9A"), false);
        artist.setSingleLine(true);
        t.addView(artist);

        r.addView(icon("▶", v -> player.playNow(i), true));
        r.addView(icon("▲", v -> player.move(i, i - 1), i > 0));
        r.addView(icon("▼", v -> player.move(i, i + 1), i < n - 1));
        r.addView(icon("✕", v -> player.remove(i), true));
        return r;
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        String text = (clip != null && clip.getItemCount() > 0)
                ? String.valueOf(clip.getItemAt(0).coerceToText(this)) : "";
        int found = player.addFromText(text);
        Toast.makeText(this, found == 0 ? "No Suno link on clipboard" : "Added to SilverBack",
                Toast.LENGTH_SHORT).show();
    }

    private void backToStage() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        finish();
    }

    // ---- small UI helpers ----

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView b = text(label, 13, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(12), dp(6), dp(12));
        b.setBackground(rounded(Color.parseColor("#2A0A0C"), RED));
        b.setOnClickListener(l);
        return b;
    }

    private TextView icon(String glyph, View.OnClickListener l, boolean enabled) {
        TextView b = text(glyph, 18, enabled ? Color.WHITE : Color.parseColor("#444444"), true);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(44)));
        if (enabled) b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        return p;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
