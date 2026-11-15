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

    public Song(String link, String id, String title, String artist, String imageUrl) {
        this.link = link;
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.imageUrl = imageUrl;
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
        return o;
    }

    static Song fromJson(JSONObject o) {
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        String image = o.optString("image", "");
        return new Song(
                o.optString("link", ""),
                id,
                o.optString("title", "Suno track"),
                o.optString("artist", ""),
                image.isEmpty() ? null : image);
    }
}
