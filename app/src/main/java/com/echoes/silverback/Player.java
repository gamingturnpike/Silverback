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

    // ---- music volume (only SilverBack's music; this is what the stream hears) ----
    private float volume = 1f;

    public int volumePercent() {
        return Math.round(volume * 100);
    }

    public void setVolumePercent(int pct) {
        volume = Math.max(0, Math.min(100, pct)) / 100f;
        prefs.edit().putFloat("volume", volume).apply();
        if (web != null) web.evaluateJavascript(volumeJs(), null);
        changed();
    }

    private String volumeJs() {
        return "document.querySelectorAll('audio').forEach(function(a){if(Math.abs(a.volume-" + volume + ")>0.001)a.volume=" + volume + ";});";
    }

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
            web.evaluateJavascript(volumeJs() + POLL_JS, v -> onPoll(gen, v));
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

    /** Last few lines that mention Discord, newest last. */
    String discordLog() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        java.util.Iterator<String> it = debug.descendingIterator();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        while (it.hasNext() && n < 6) {
            String l = it.next();
            if (l.contains("Discord") || l.contains("Link from") || l.contains("Skipped link")) {
                lines.add(0, l);
                n++;
            }
        }
        for (String l : lines) sb.append(l).append('\n');
        return sb.length() == 0 ? "(nothing yet)" : sb.toString().trim();
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
        this.volume = prefs.getFloat("volume", 1f);
        restore();
    }

    public void addListener(Listener l) { listeners.add(l); }

    /** Called (main thread) every time a song lands in the queue. */
    public interface AddListener {
        void onSongAdded(Song s);
    }

    private final CopyOnWriteArraySet<AddListener> addListeners = new CopyOnWriteArraySet<>();

    public void addAddListener(AddListener l) { addListeners.add(l); }

    public void removeAddListener(AddListener l) { addListeners.remove(l); }

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
        add(link, "", "");
    }

    // Discord users' links still loading (user id -> how many)
    private final java.util.Map<String, Integer> pendingRequesters = new java.util.HashMap<>();

    /** How many songs each person may have playing/queued at once (set in the DISCORD box). */
    public int perPersonLimit() {
        return Math.max(1, prefs.getInt("per_person", 1));
    }

    public void setPerPersonLimit(int n) {
        prefs.edit().putInt("per_person", Math.max(1, Math.min(20, n))).apply();
    }

    /** Songs this Discord user has playing, queued, or loading. */
    private int songsFor(String userId) {
        int n = pendingRequesters.getOrDefault(userId, 0);
        if (current != null && userId.equals(current.requesterId)) n++;
        for (Song q : queue) if (userId.equals(q.requesterId)) n++;
        return n;
    }

    /** What happened to a Discord request (called on the main thread). */
    public interface DiscordResult {
        void added(Song s, int extraLinks);
        void busy();
        void failed();
    }

    /** Links posted in Discord, limited to N songs per person at a time. */
    public void addFromDiscord(String text, String userId, String userName, DiscordResult result) {
        List<String> links = SunoResolver.extractLinks(text);
        if (links.isEmpty()) return;
        int room = perPersonLimit() - songsFor(userId);
        if (room <= 0) {
            log("Skipped link from " + userName + " — already has " + perPersonLimit() + " in the queue");
            if (result != null) result.busy();
            return;
        }
        int take = Math.min(room, links.size());
        int extra = links.size() - take;
        if (extra > 0) log(userName + " posted " + links.size() + " links — using " + take);
        for (int i = 0; i < take; i++) {
            log("Link from " + userName + ": " + links.get(i));
            pendingRequesters.merge(userId, 1, Integer::sum);
            add(links.get(i), userId, userName, result, i == 0 ? extra : 0);
        }
    }

    private void add(String link, String reqId, String reqName) {
        add(link, reqId, reqName, null, 0);
    }

    private void add(String link, String reqId, String reqName, DiscordResult result, int extra) {
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
                if (!reqId.isEmpty()) {
                    Integer left = pendingRequesters.get(reqId);
                    if (left == null || left <= 1) pendingRequesters.remove(reqId);
                    else pendingRequesters.put(reqId, left - 1);
                }
                if (s == null) {
                    toast("Couldn't load that Suno link");
                    if (result != null) result.failed();
                    return;
                }
                s.requesterId = reqId;
                s.requesterName = reqName;
                queue.add(s);
                for (AddListener l : addListeners) l.onSongAdded(s);
                if (result != null) result.added(s, extra);
                save();
                if (current == null) playNext();
                else changed();
            });
        });
    }

    /** Stops the music and keeps the current song at the top of the queue for next time. */
    public void shutdown() {
        main.removeCallbacks(poll);
        generation++;
        if (web != null) {
            web.evaluateJavascript(STOP_JS, null);
            web.loadUrl("about:blank");
        }
        if (current != null) queue.add(0, current);
        current = null;
        started = false;
        audioPaused = true;
        save();
        log("SilverBack closed");
        changed();
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
                if (url != null && (url.contains("/embed/") || url.contains("/silverback"))) {
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
                + "if(window.__sbUser){clearInterval(iv);return;}"
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
        if (!s.directAudio.isEmpty()) {
            // Direct MP3 (e.g. Rig-Radio): a tiny page with an <audio> and a play/pause button,
            // so pause, skip, volume and song-end work exactly like the Suno player.
            log("Opening audio: " + s.directAudio);
            String src = android.text.TextUtils.htmlEncode(s.directAudio);
            String html = "<!doctype html><html><body style='background:#000'>"
                    + "<audio id='a' src='" + src + "' preload='auto' autoplay></audio>"
                    + "<button onclick=\"var a=document.getElementById('a');if(a.paused)a.play();else a.pause();\">play</button>"
                    + "</body></html>";
            engine().loadDataWithBaseURL("https://rig-radio.ai/silverback", html, "text/html", "utf-8", "https://rig-radio.ai/silverback");
        } else {
            log("Opening Suno player: " + s.embedUrl());
            engine().loadUrl(s.embedUrl());
        }
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

    /**
     * Makes the player reach a wanted state (play or pause) instead of blindly toggling,
     * so the button always does what its label says. Uses Suno's own button first,
     * then sets the audio directly if Suno's button hasn't done it within 2 seconds.
     */
    private static String wantJs(boolean play) {
        String w = play ? "true" : "false";
        return "(function(want){window.__sbUser=1;clearTimeout(window.__sbT);"
                + "function ok(){var x=document.querySelector('audio');return x&&(want?!x.paused:x.paused);}"
                + "if(ok()){SilverBack.log('Already '+(want?'playing':'paused'));return;}"
                + "var a=document.querySelector('audio');var b=document.querySelector('button');"
                + "if(b){b.click();}else if(a){if(want)a.play();else a.pause();}"
                + "clearTimeout(window.__sbT);window.__sbT=setTimeout(function(){var x=document.querySelector('audio');"
                + "if(x&&!ok()){if(want)x.play();else x.pause();SilverBack.log('Suno button slow, set audio directly');}"
                + "SilverBack.log('Now '+(x?(x.paused?'paused':'playing'):'no audio'));},2000);})(" + w + ")";
    }

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
        boolean wantPlay = !isPlaying();
        log((wantPlay ? "Play" : "Pause") + " pressed");
        web.evaluateJavascript(wantJs(wantPlay), null);
        audioPaused = !wantPlay;
        if (wantPlay) started = started || posSec > 0;
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
