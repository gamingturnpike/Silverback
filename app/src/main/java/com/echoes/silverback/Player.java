package com.echoes.silverback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Queue + playback. Songs play through Suno's own public embed player
 * (suno.com/embed/ID) running in a hidden WebView — no Suno sign-in.
 */
public final class Player {

    public interface Listener {
        void onPlayerChanged();
    }

    // Keeps Suno's page from pausing itself when SilverBack is in the background.
    private static final String KEEP_VISIBLE_JS =
            "try{Object.defineProperty(document,'hidden',{get:function(){return false}});"
                    + "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});"
                    + "document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation()},true);"
                    + "window.addEventListener('pagehide',function(e){e.stopImmediatePropagation()},true);"
                    + "window.addEventListener('blur',function(e){e.stopImmediatePropagation()},true);}catch(e){}";

    private static final String POLL_JS =
            "(function(){var a=document.querySelector('audio');if(!a)return '';"
                    + "return a.currentTime+','+a.duration+','+(a.paused?1:0)+','+(a.ended?1:0);})()";

    private static Player instance;

    public static synchronized Player get(Context c) {
        if (instance == null) instance = new Player(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final SharedPreferences prefs;

    final List<Song> queue = new ArrayList<>();
    Song current;

    private WebView web;
    private int generation;
    private boolean started;      // audio has actually begun
    private boolean userPaused;
    private boolean audioPaused = true;
    private double posSec, durSec;
    private long loadStartedAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (current == null || web == null) return;
            final int gen = generation;
            web.evaluateJavascript(POLL_JS, v -> onPoll(gen, v));
            main.postDelayed(this, 1000);
        }
    };

    // ---- DEBUG log ----
    private final ArrayDeque<String> debug = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private String lastState = "";

    void log(String msg) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> log(msg));
            return;
        }
        debug.addLast(clock.format(new Date()) + "  " + msg);
        while (debug.size() > 40) debug.removeFirst();
        for (Listener l : listeners) l.onPlayerChanged();
    }

    String debugText() {
        StringBuilder sb = new StringBuilder();
        sb.append("SilverBack ").append(BuildConfig.VERSION_NAME)
                .append("  |  WebView ").append(webViewVersion()).append('\n');
        for (String line : debug) sb.append(line).append('\n');
        return sb.toString();
    }

    private String webViewVersion() {
        try {
            android.content.pm.PackageInfo pi = WebViewCompat.getCurrentWebViewPackage(ctx);
            return pi == null ? "none" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private Player(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("silverback", Context.MODE_PRIVATE);
        restore();
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** Pulls every Suno link out of shared/pasted text and queues it. Returns how many were found. */
    public int addFromText(String text) {
        List<String> links = SunoResolver.extractLinks(text);
        log(links.isEmpty() ? "No Suno link in: " + (text == null ? "(nothing)" : text.trim())
                : "Link found: " + String.join(" ", links));
        for (String link : links) add(link);
        return links.size();
    }

    public void add(String link) {
        io.execute(() -> {
            log("Loading song info…");
            Song s = SunoResolver.resolve(link);
            if (s != null) {
                log("Song: " + s.title + " — " + s.artist + "  [" + s.id + "]");
                s.cover = SunoResolver.loadBitmap(s.imageUrl);
                log(s.cover != null ? "Cover art loaded" : "Cover art failed: " + s.imageUrl);
            } else {
                log("Song info FAILED: " + SunoResolver.lastError);
            }
            main.post(() -> {
                if (s == null) {
                    toast("Couldn't load that Suno link");
                    return;
                }
                queue.add(s);
                save();
                if (current == null) playNext();
                else changed();
            });
        });
    }

    /** Skip button. */
    public void skip() {
        log("Skip pressed — " + (queue.isEmpty() ? "nothing up next, stopping" : "next: " + queue.get(0).title));
        playNext();
    }

    private static final String STOP_JS =
            "document.querySelectorAll('audio,video').forEach(function(a){try{a.pause();a.removeAttribute('src');a.load();}catch(e){}})";

    public void playNext() {
        main.removeCallbacks(poll);
        generation++;
        if (web != null) web.evaluateJavascript(STOP_JS, null);
        if (queue.isEmpty()) {
            current = null;
            if (web != null) web.loadUrl("about:blank");
            save();
            changed();
            if (DiscordWatcher.get(ctx).isRunning()) PlayerService.updateIfRunning(ctx);
            else PlayerService.stop(ctx);
            return;
        }
        current = queue.remove(0);
        save();
        start(current);
    }

    /** The WebView running Suno's player, so the stage can keep it attached on screen. */
    WebView engineView() {
        return engine();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView engine() {
        if (web != null) return web;
        WebView w = new WebView(ctx);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        w.addJavascriptInterface(new Bridge(), "SilverBack");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, KEEP_VISIBLE_JS,
                    Collections.singleton("https://suno.com"));
        }
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String t = m.message();
                    log("Page error: " + (t.length() > 140 ? t.substring(0, 140) + "…" : t));
                }
                return true;
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) log("Suno player load error: " + err.getDescription());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse res) {
                if (req.isForMainFrame()) log("Suno player HTTP " + res.getStatusCode());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("/embed/")) {
                    log("Suno player opened" + (view.isAttachedToWindow() ? " (on screen)" : " (NOT on screen)"));
                    view.evaluateJavascript(KEEP_VISIBLE_JS, null);
                    view.evaluateJavascript(startJs(generation), null);
                }
            }
        });
        web = w;
        return w;
    }

    /** Clicks the embed's play button until audio is running, and reports when the song ends. */
    private static String startJs(int gen) {
        return "(function(){if(window.__sb===" + gen + ")return;window.__sb=" + gen + ";var n=0;"
                + "var iv=setInterval(function(){var a=document.querySelector('audio');"
                + "var b=document.querySelector('button');"
                + "if(a&&!a.__sbHooked){a.__sbHooked=1;a.addEventListener('ended',function(){SilverBack.ended(" + gen + ");});"
                + "a.addEventListener('error',function(){SilverBack.log('Audio error code '+(a.error?a.error.code+' '+(a.error.message||''):'?'));});}"
                + "if(a&&!a.paused&&a.currentTime>0){SilverBack.log('Audio playing');clearInterval(iv);return;}"
                + "if(n%6===0){if(b&&(!a||a.paused)){b.click();SilverBack.log('Pressed play ('+(a?'audio ready':'no audio yet')+')');}"
                + "else if(!b){SilverBack.log('Play button not found yet');}"
                + "else if(a){SilverBack.log('Waiting: paused='+a.paused+' t='+a.currentTime.toFixed(1)+' ready='+a.readyState+' net='+a.networkState);}}"
                + "n++;if(n>60){SilverBack.log('Gave up pressing play after 30s');clearInterval(iv);}},500);})()";
    }

    private void start(Song s) {
        if (s.cover == null) {
            io.execute(() -> {
                Bitmap b = SunoResolver.loadBitmap(s.imageUrl);
                main.post(() -> {
                    s.cover = b;
                    changed();
                });
            });
        }
        started = false;
        userPaused = false;
        audioPaused = true;
        posSec = 0;
        durSec = 0;
        lastState = "";
        loadStartedAt = System.currentTimeMillis();
        log("Opening Suno player: " + s.embedUrl());
        engine().loadUrl(s.embedUrl());
        PlayerService.startOrUpdate(ctx);
        main.removeCallbacks(poll);
        main.postDelayed(poll, 1000);
        changed();
    }

    private void onPoll(int gen, String raw) {
        if (gen != generation || current == null) return;
        String v = raw == null ? "" : raw.replace("\"", "");
        if (v.isEmpty() || v.equals("null")) {
            checkStuck();
            return;
        }
        String[] p = v.split(",");
        if (p.length < 4) return;
        try {
            posSec = Double.parseDouble(p[0]);
            double d = Double.parseDouble(p[1]);
            durSec = (Double.isNaN(d) || Double.isInfinite(d)) ? 0 : d;
        } catch (NumberFormatException ignored) {
        }
        audioPaused = p[2].equals("1");
        boolean ended = p[3].equals("1");
        String state = (audioPaused ? "paused" : "playing") + (posSec > 0 ? "" : " (0:00)");
        if (!state.equals(lastState)) {
            lastState = state;
            log("Audio " + state);
        }
        if (posSec > 0) started = true;
        if (ended && started) {
            playNext();
            return;
        }
        if (!started) checkStuck();
        changed();
    }

    private void checkStuck() {
        if (!started && System.currentTimeMillis() - loadStartedAt > 30000) {
            log("No sound after 30s — skipping " + current.title);
            toast("Couldn't play " + current.title);
            playNext();
        }
    }

    // Uses Suno's own play/pause button (keeps its player in sync); falls back to the audio element.
    private static final String TOGGLE_JS =
            "(function(){var a=document.querySelector('audio');var b=document.querySelector('button');"
                    + "var was=a?a.paused:null;if(b){b.click();}else if(a){if(a.paused)a.play();else a.pause();}"
                    + "setTimeout(function(){var a2=document.querySelector('audio');"
                    + "if(a2&&was!==null&&a2.paused===was){if(was)a2.play();else a2.pause();"
                    + "SilverBack.log('Button did not respond, used audio '+(was?'play':'pause'));}"
                    + "SilverBack.log('Now '+(a2?(a2.paused?'paused':'playing'):'no audio'));},800);})()";

    public void togglePause() {
        if (current == null) {
            log("Play pressed — " + (queue.isEmpty() ? "queue is empty" : "starting queue"));
            if (!queue.isEmpty()) playNext();
            return;
        }
        if (web == null) {
            log("Play/Pause pressed but player isn't ready");
            return;
        }
        log((audioPaused ? "Play" : "Pause") + " pressed");
        userPaused = !audioPaused;
        web.evaluateJavascript(TOGGLE_JS, null);
        audioPaused = userPaused;
        changed();
    }

    public void move(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size()) return;
        queue.add(to, queue.remove(from));
        save();
        changed();
    }

    /** Used while dragging: reorders without redrawing the screen. */
    public void moveSilently(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size()) return;
        queue.add(to, queue.remove(from));
    }

    /** Called when you let go of a dragged song. */
    public void commitOrder() {
        save();
        changed();
    }

    public void remove(int i) {
        if (i < 0 || i >= queue.size()) return;
        queue.remove(i);
        save();
        changed();
    }

    /** Jumps straight to this song. */
    public void playNow(int i) {
        if (i < 0 || i >= queue.size()) return;
        queue.add(0, queue.remove(i));
        playNext();
    }

    public void clearUpNext() {
        queue.clear();
        save();
        changed();
    }

    public boolean isPlaying() {
        return current != null && started && !audioPaused;
    }

    public int positionMs() {
        return (int) (posSec * 1000);
    }

    public int durationMs() {
        return (int) (durSec * 1000);
    }

    private final class Bridge {
        @JavascriptInterface
        public void log(String msg) {
            Player.this.log("Player: " + msg);
        }

        @JavascriptInterface
        public void ended(int gen) {
            main.post(() -> {
                if (gen == generation && current != null) playNext();
            });
        }
    }

    private void changed() {
        for (Listener l : listeners) l.onPlayerChanged();
        if (current != null) PlayerService.updateIfRunning(ctx);
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            if (current != null) a.put(current.toJson());
            for (Song s : queue) a.put(s.toJson());
            prefs.edit().putString("queue", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void restore() {
        try {
            JSONArray a = new JSONArray(prefs.getString("queue", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Song s = Song.fromJson(a.getJSONObject(i));
                if (s != null) queue.add(s);
            }
        } catch (Exception ignored) {
        }
    }
}
