package com.echoes.silverback;

import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

public final class Song {
    public final String link;
    public final String title;
    public final String artist;
    public final String audioUrl;
    public final String imageUrl;
    public volatile Bitmap cover;

    public Song(String link, String title, String artist, String audioUrl, String imageUrl) {
        this.link = link;
        this.title = title;
        this.artist = artist;
        this.audioUrl = audioUrl;
        this.imageUrl = imageUrl;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("link", link);
        o.put("title", title);
        o.put("artist", artist);
        o.put("audio", audioUrl);
        o.put("image", imageUrl == null ? "" : imageUrl);
        return o;
    }

    static Song fromJson(JSONObject o) {
        String image = o.optString("image", "");
        return new Song(
                o.optString("link", ""),
                o.optString("title", "Suno track"),
                o.optString("artist", ""),
                o.optString("audio", ""),
                image.isEmpty() ? null : image);
    }
}
