package com.echoes.silverback;

import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

public final class Song {
    public final String link;
    public final String id;
    public final String title;
    public final String artist;
    public final String imageUrl;
    public volatile Bitmap cover;
    /** Discord user who requested it (empty when you added it yourself). */
    public String requesterId = "";
    public String requesterName = "";
    /** Direct audio file (Rig-Radio etc.). Empty = play through Suno's embed player. */
    public String directAudio = "";

    public Song(String link, String id, String title, String artist, String imageUrl) {
        this.link = link;
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.imageUrl = imageUrl;
    }

    /** Some Rig-Radio songs use a short looping video as their cover. */
    boolean isVideoCover() {
        return imageUrl != null && imageUrl.toLowerCase().matches(".*\\.(mp4|webm|mov|m4v)(\\?.*)?$");
    }

    String embedUrl() {
        return "https://suno.com/embed/" + id;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("link", link);
        o.put("id", id);
        o.put("title", title);
        o.put("artist", artist);
        o.put("image", imageUrl == null ? "" : imageUrl);
        o.put("reqId", requesterId);
        o.put("reqName", requesterName);
        o.put("audio", directAudio);
        return o;
    }

    static Song fromJson(JSONObject o) {
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        String image = o.optString("image", "");
        Song s = new Song(
                o.optString("link", ""),
                id,
                o.optString("title", "Suno track"),
                o.optString("artist", ""),
                image.isEmpty() ? null : image);
        s.requesterId = o.optString("reqId", "");
        s.requesterName = o.optString("reqName", "");
        s.directAudio = o.optString("audio", "");
        return s;
    }
}
