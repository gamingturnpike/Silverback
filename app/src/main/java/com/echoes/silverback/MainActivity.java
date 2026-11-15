package com.echoes.silverback;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.util.Rational;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements Player.Listener {

    private Player player;
    private View nowGroup, controls;
    private ImageView cover;
    private TextView title, artist, elapsed, total, upNext, empty, btnPlay;
    private ProgressBar progress;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };
    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        player = Player.get(this);
        nowGroup = findViewById(R.id.now_group);
        controls = findViewById(R.id.controls);
        cover = findViewById(R.id.cover);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        elapsed = findViewById(R.id.elapsed);
        total = findViewById(R.id.total);
        upNext = findViewById(R.id.up_next);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);
        btnPlay = findViewById(R.id.btn_play);

        findViewById(R.id.stage).setOnClickListener(v -> toggleControls());
        btnPlay.setOnClickListener(v -> { player.togglePause(); bumpControls(); });
        findViewById(R.id.btn_skip).setOnClickListener(v -> { player.playNext(); bumpControls(); });
        findViewById(R.id.btn_mini).setOnClickListener(v -> goMini());
        findViewById(R.id.btn_paste).setOnClickListener(v -> { pasteLink(); bumpControls(); });
        findViewById(R.id.btn_clear).setOnClickListener(v -> { player.clearUpNext(); bumpControls(); });

        attachEngine();
        hideSystemBars();
        setPictureInPictureParams(pipParams());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (state == null) handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        i.setAction(null);
        int found = player.addFromText(text);
        Toast.makeText(this, found == 0 ? "No Suno link found"
                : found == 1 ? "Added to SilverBack" : "Added " + found + " songs to SilverBack",
                Toast.LENGTH_SHORT).show();
    }

    private void pasteLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        String text = (clip != null && clip.getItemCount() > 0)
                ? String.valueOf(clip.getItemAt(0).coerceToText(this)) : "";
        int found = player.addFromText(text);
        Toast.makeText(this, found == 0 ? "No Suno link on clipboard" : "Added to SilverBack",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        player.addListener(this);
        onPlayerChanged();
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        player.removeListener(this);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    /** Suno's player must sit in a real on-screen window to play reliably; it hides behind the stage. */
    private void attachEngine() {
        WebView w = player.engineView();
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        ((FrameLayout) findViewById(R.id.engine_host)).addView(w,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onDestroy() {
        WebView w = player.engineView();
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        super.onDestroy();
    }

    private void goMini() {
        try {
            enterPictureInPictureMode(pipParams());
        } catch (Exception e) {
            Toast.makeText(this, "Turn on Picture-in-picture for SilverBack in Settings", Toast.LENGTH_LONG).show();
        }
    }

    /** Mini window keeps the whole stage at the overlay's shape. */
    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1672, 941));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(true);
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31) {
            try {
                enterPictureInPictureMode(pipParams());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (inPip) {
            handler.removeCallbacks(hideControls);
            controls.setVisibility(View.GONE);
        } else {
            hideSystemBars();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onPlayerChanged() {
        Song s = player.current;
        boolean has = s != null;
        nowGroup.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        btnPlay.setText(player.isPlaying() ? "PAUSE" : "PLAY");
        if (has) {
            title.setText(s.title);
            artist.setText(s.artist);
            artist.setVisibility(s.artist.isEmpty() ? View.GONE : View.VISIBLE);
            cover.setImageBitmap(s.cover);
        }
        StringBuilder sb = new StringBuilder();
        int n = player.queue.size();
        for (int i = 0; i < Math.min(2, n); i++) {
            Song q = player.queue.get(i);
            if (sb.length() > 0) sb.append('\n');
            sb.append(i + 1).append("   ").append(q.title);
            if (!q.artist.isEmpty()) sb.append("  —  ").append(q.artist);
        }
        if (n > 2) sb.append("\n+").append(n - 2).append(" more");
        upNext.setText(n == 0 ? "—" : sb.toString());
        updateProgress();
    }

    private void updateProgress() {
        int dur = player.durationMs(), pos = player.positionMs();
        progress.setMax(Math.max(1, dur));
        progress.setProgress(pos);
        elapsed.setText(fmt(pos));
        total.setText(dur > 0 ? fmt(dur) : "--:--");
        btnPlay.setText(player.isPlaying() ? "PAUSE" : "PLAY");
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void toggleControls() {
        if (isInPictureInPictureMode()) return;
        if (controls.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideControls);
            controls.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
            bumpControls();
        }
    }

    private void bumpControls() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 4000);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
