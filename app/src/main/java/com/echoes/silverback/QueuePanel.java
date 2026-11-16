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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Queue panel that slides over the right side of the stage (viewers see it instead of black). */
public class QueuePanel extends LinearLayout implements Player.Listener {

    private static final int RED = Color.parseColor("#FF2430");
    private static final int BG = Color.parseColor("#0E0E10");
    private static final int CARD = Color.parseColor("#1A1A1E");

    private final android.content.Context ctx;
    private final Runnable onClose;

    private Player player;
    private ImageView cover;
    private TextView nowTitle, nowArtist, playBtn, count;
    private RecyclerView list;
    private TextView empty;
    private QueueAdapter adapter;
    private ItemTouchHelper touch;

    public QueuePanel(android.content.Context context, Runnable onClose) {
        super(context);
        this.ctx = context;
        this.onClose = onClose;
        player = Player.get(ctx);

        LinearLayout root = this;
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(238, 10, 6, 8));
        bg.setStroke(dp(2), RED);
        bg.setCornerRadii(new float[]{dp(14), dp(14), 0, 0, 0, 0, dp(14), dp(14)});
        root.setBackground(bg);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setClickable(true); // taps on the panel don't reach the stage

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView header = text("SILVERBACK QUEUE", 13, RED, true);
        header.setLetterSpacing(0.2f);
        head.addView(header, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("✕", 20, Color.WHITE, true);
        close.setPadding(dp(10), dp(2), dp(4), dp(2));
        close.setOnClickListener(v -> onClose.run());
        head.addView(close);

        // Now playing card
        LinearLayout now = new LinearLayout(ctx);
        now.setOrientation(LinearLayout.HORIZONTAL);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setPadding(dp(12), dp(12), dp(12), dp(12));
        now.setBackground(rounded(CARD, RED));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(10);
        root.addView(now, nlp);

        cover = new ImageView(ctx);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.parseColor("#2A0A0C"));
        now.addView(cover, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout nowText = new LinearLayout(ctx);
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
        LinearLayout controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        root.addView(controls, clp);
        playBtn = button("PLAY", v -> player.togglePause());
        controls.addView(playBtn, weight());
        controls.addView(button("SKIP", v -> player.skip()), weight());
        controls.addView(button("PASTE LINK", v -> paste()), weight());
        controls.addView(button("CLOSE", v -> onClose.run()), weight());

        // Music volume (what your stream hears)
        LinearLayout volRow = new LinearLayout(ctx);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = dp(12);
        root.addView(volRow, vlp);
        TextView volLabel = text("VOLUME " + player.volumePercent() + "%", 12, Color.parseColor("#B5B5B5"), true);
        volRow.addView(volLabel, new LinearLayout.LayoutParams(dp(110), -2));
        SeekBar vol = new SeekBar(ctx);
        vol.setMax(Player.MAX_VOLUME_PCT);
        vol.setProgress(player.volumePercent());
        vol.setProgressTintList(android.content.res.ColorStateList.valueOf(RED));
        vol.setThumbTintList(android.content.res.ColorStateList.valueOf(RED));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                player.setVolumePercent(p);
                volLabel.setText("VOLUME " + p + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        volRow.addView(vol, new LinearLayout.LayoutParams(0, -2, 1));

        count = text("", 12, Color.parseColor("#9A9A9A"), true);
        count.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams cnt = new LinearLayout.LayoutParams(-2, -2);
        cnt.topMargin = dp(16);
        cnt.bottomMargin = dp(6);
        root.addView(count, cnt);

        empty = text("Queue is empty. Links from Discord, Share, or Paste Link show up here.",
                14, Color.parseColor("#777777"), false);
        empty.setPadding(0, dp(12), 0, 0);
        root.addView(empty);

        list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx));
        adapter = new QueueAdapter();
        list.setAdapter(adapter);
        touch = new ItemTouchHelper(new DragCallback());
        touch.attachToRecyclerView(list);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView clear = button("CLEAR UP NEXT", v -> player.clearUpNext());
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, -2);
        xlp.topMargin = dp(8);
        root.addView(clear, xlp);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        player.addListener(this);
        lastRendered = "";
        lastNow = null;
        onPlayerChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        player.removeListener(this);
        super.onDetachedFromWindow();
    }

    private String lastRendered = "";
    private boolean dragging;
    private String lastNow = null;

    @Override
    public void onPlayerChanged() {
        // Only touch views when something actually changed (keeps dragging smooth)
        Song s = player.current;
        String nowKey = s == null ? "" : s.id + (s.cover != null ? "+" : "-");
        if (!nowKey.equals(lastNow)) {
            lastNow = nowKey;
            nowTitle.setText(s != null ? s.title : "Nothing playing");
            nowArtist.setText(s != null ? s.artist : "Share or paste a Suno link");
            cover.setImageBitmap(s != null ? s.cover : null);
        }
        String label = player.isPlaying() ? "PAUSE" : "PLAY";
        if (!label.contentEquals(playBtn.getText())) playBtn.setText(label);

        // Rebuild the list only when the queue itself changed (never mid-drag)
        if (dragging) return;
        StringBuilder key = new StringBuilder();
        for (Song q : player.queue) key.append(q.id).append(q.cover != null ? '+' : '-');
        if (key.toString().equals(lastRendered)) return;
        lastRendered = key.toString();

        int n = player.queue.size();
        count.setText("UP NEXT  ·  " + n + (n == 1 ? " SONG" : " SONGS") + "   —   hold ≡ and drag to reorder");
        empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ---- drag-and-drop list ----

    private final class Row extends RecyclerView.ViewHolder {
        final TextView num, title, artist, handle;
        final ImageView art;
        final View play, remove;

        Row(LinearLayout r, TextView num, ImageView art, TextView title, TextView artist,
            View play, View remove, TextView handle) {
            super(r);
            this.num = num;
            this.art = art;
            this.title = title;
            this.artist = artist;
            this.play = play;
            this.remove = remove;
            this.handle = handle;
        }
    }

    private final class QueueAdapter extends RecyclerView.Adapter<Row> {
        @Override
        public int getItemCount() {
            return player.queue.size();
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(4), dp(8), dp(4), dp(8));
            r.setBackground(rounded(CARD, 0));
            RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(-1, -2);
            rlp.bottomMargin = dp(6);
            r.setLayoutParams(rlp);

            TextView handle = text("≡", 24, Color.parseColor("#B5B5B5"), true);
            handle.setGravity(Gravity.CENTER);
            r.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(48)));

            TextView num = text("", 14, RED, true);
            num.setGravity(Gravity.CENTER);
            r.addView(num, new LinearLayout.LayoutParams(dp(22), -2));

            ImageView art = new ImageView(ctx);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackgroundColor(Color.parseColor("#2A0A0C"));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44));
            alp.leftMargin = dp(4);
            r.addView(art, alp);

            LinearLayout t = new LinearLayout(ctx);
            t.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            tlp.leftMargin = dp(10);
            r.addView(t, tlp);
            TextView title = text("", 15, Color.WHITE, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(title);
            TextView artist = text("", 12, Color.parseColor("#9A9A9A"), false);
            artist.setSingleLine(true);
            t.addView(artist);

            TextView up = icon("▲", null, true);
            TextView down = icon("▼", null, true);
            TextView play = icon("▶", null, true);
            TextView remove = icon("✕", null, true);
            r.addView(up);
            r.addView(down);
            r.addView(play);
            r.addView(remove);

            Row h = new Row(r, num, art, title, artist, play, remove, handle);
            up.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p > 0) player.move(p, p - 1);
            });
            down.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION && p < player.queue.size() - 1) player.move(p, p + 1);
            });
            handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) touch.startDrag(h);
                return false;
            });
            play.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) player.playNow(p);
            });
            remove.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) player.remove(p);
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int pos) {
            Song q = player.queue.get(pos);
            h.num.setText(String.valueOf(pos + 1));
            h.title.setText(q.title);
            h.artist.setText(q.artist);
            h.art.setImageBitmap(q.cover);
        }
    }

    private final class DragCallback extends ItemTouchHelper.SimpleCallback {
        DragCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true; // hold anywhere on a song, or grab the ≡ handle
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                              @NonNull RecyclerView.ViewHolder to) {
            int f = from.getBindingAdapterPosition(), t = to.getBindingAdapterPosition();
            if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false;
            if (t < 0 || t >= player.queue.size()) return false;
            player.moveSilently(f, t);
            adapter.notifyItemMoved(f, t);
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
            super.onSelectedChanged(vh, state);
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                dragging = true;
                player.log("Queue: drag started on #" + (vh.getBindingAdapterPosition() + 1));
                vh.itemView.setBackground(rounded(Color.parseColor("#2A0A0C"), RED));
                vh.itemView.setScaleX(1.03f);
                vh.itemView.setScaleY(1.03f);
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            vh.itemView.setBackground(rounded(CARD, 0));
            vh.itemView.setScaleX(1f);
            vh.itemView.setScaleY(1f);
            dragging = false;
            lastRendered = "";
            player.log("Queue: dropped at #" + (vh.getBindingAdapterPosition() + 1));
            player.commitOrder();
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
        }
    }

    private void paste() {
        ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        String text = (clip != null && clip.getItemCount() > 0)
                ? String.valueOf(clip.getItemAt(0).coerceToText(ctx)) : "";
        int found = player.addFromText(text);
        Toast.makeText(ctx, found == 0 ? "No Suno link on clipboard" : "Added to SilverBack",
                Toast.LENGTH_SHORT).show();
    }

    // ---- small UI helpers ----

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
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
        TextView b = text(glyph, 17, enabled ? Color.WHITE : Color.parseColor("#444444"), true);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(44)));
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
