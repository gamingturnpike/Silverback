package com.echoes.silverback;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Queue + playback. Lives for the whole app process so music keeps going when you leave the stage. */
public final class Player {

    public interface Listener {
        void onPlayerChanged();
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
    private MediaPlayer mp;
    private boolean prepared;
    private boolean paused;

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
        for (String link : links) add(link);
        return links.size();
    }

    public void add(String link) {
        io.execute(() -> {
            Song s = SunoResolver.resolve(link);
            if (s != null) s.cover = SunoResolver.loadBitmap(s.imageUrl);
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

    public void playNext() {
        release();
        if (queue.isEmpty()) {
            current = null;
            save();
            changed();
            PlayerService.stop(ctx);
            return;
        }
        current = queue.remove(0);
        save();
        start(current);
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
        MediaPlayer m = new MediaPlayer();
        mp = m;
        prepared = false;
        paused = false;
        m.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        m.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
        m.setOnPreparedListener(x -> {
            if (m != mp) return;
            prepared = true;
            if (!paused) m.start();
            changed();
        });
        m.setOnCompletionListener(x -> {
            if (m == mp) playNext();
        });
        m.setOnErrorListener((x, what, extra) -> {
            if (m == mp) {
                toast("Couldn't play " + s.title);
                main.post(this::playNext);
            }
            return true;
        });
        try {
            m.setDataSource(s.audioUrl);
            m.prepareAsync();
        } catch (Exception e) {
            toast("Couldn't play " + s.title);
            main.post(this::playNext);
            return;
        }
        PlayerService.startOrUpdate(ctx);
        changed();
    }

    public void togglePause() {
        if (mp == null) {
            if (!queue.isEmpty()) playNext();
            return;
        }
        if (!prepared) {
            paused = !paused;
        } else if (mp.isPlaying()) {
            mp.pause();
            paused = true;
        } else {
            mp.start();
            paused = false;
        }
        changed();
    }

    public void clearUpNext() {
        queue.clear();
        save();
        changed();
    }

    public boolean isPlaying() {
        return mp != null && prepared && mp.isPlaying();
    }

    public int positionMs() {
        return (mp != null && prepared) ? mp.getCurrentPosition() : 0;
    }

    public int durationMs() {
        return (mp != null && prepared) ? Math.max(0, mp.getDuration()) : 0;
    }

    private void release() {
        if (mp != null) {
            MediaPlayer m = mp;
            mp = null;
            prepared = false;
            try {
                m.stop();
            } catch (Exception ignored) {
            }
            m.release();
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
            for (int i = 0; i < a.length(); i++) queue.add(Song.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }
}
