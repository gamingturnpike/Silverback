package com.echoes.silverback;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns Suno share links into playable songs. No Suno sign-in is used. */
final class SunoResolver {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

    private static final Pattern LINK = Pattern.compile(
            "https?://(?:[a-z0-9-]+\\.)*suno\\.(?:com|ai)/[^\\s<>\"'()\\[\\]]+",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SONG_PATH = Pattern.compile(
            "/song/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DISPLAY_NAME = Pattern.compile(
            "\\\\?\"display_name\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]+)");

    private SunoResolver() {}

    /** Every Suno link found in a block of shared text, in order, without duplicates. */
    static List<String> extractLinks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[.,!?;:]+$", "");
            if (!out.contains(url)) out.add(url);
        }
        return out;
    }

    static Song resolve(String link) {
        try {
            return resolveOrThrow(link);
        } catch (Exception e) {
            return null;
        }
    }

    private static Song resolveOrThrow(String link) throws IOException {
        String id = null, title = null, artist = null, image = null, audio = null;

        if (link.toLowerCase().contains(".mp3")) {
            audio = link;
            Matcher m = UUID.matcher(link);
            if (m.find()) id = m.group();
        } else {
            HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html");
            String html;
            String finalUrl;
            try {
                html = new String(read(c.getInputStream(), 3_000_000), StandardCharsets.UTF_8);
                finalUrl = c.getURL().toString();
            } finally {
                c.disconnect();
            }

            Matcher m = SONG_PATH.matcher(finalUrl);
            if (m.find()) id = m.group(1);
            if (id == null) {
                String ogUrl = meta(html, "og:url");
                if (ogUrl != null) {
                    Matcher u = SONG_PATH.matcher(ogUrl);
                    if (u.find()) id = u.group(1);
                }
            }
            if (id == null) {
                Matcher h = SONG_PATH.matcher(html);
                if (h.find()) id = h.group(1);
            }

            title = clean(meta(html, "og:title"));
            image = meta(html, "og:image");
            audio = meta(html, "og:audio");
            if (audio == null) audio = meta(html, "og:audio:url");

            if (title != null) {
                title = title.replaceAll("\\s*[|\\-–]\\s*Suno.*$", "").trim();
                int by = title.lastIndexOf(" by ");
                if (by > 0) {
                    artist = title.substring(by + 4).trim();
                    title = title.substring(0, by).trim();
                }
            }
            if (artist == null) {
                String desc = clean(meta(html, "og:description"));
                if (desc != null) {
                    Matcher d = Pattern.compile("\\bby\\s+(@?[^.|,]+)").matcher(desc);
                    if (d.find()) artist = d.group(1).trim();
                }
            }
            if (artist == null) {
                Matcher d = DISPLAY_NAME.matcher(html);
                if (d.find()) artist = d.group(1).trim();
            }
        }

        if (id == null && audio == null) return null;
        if (audio == null) audio = "https://cdn1.suno.ai/" + id + ".mp3";
        if (image == null && id != null) image = "https://cdn2.suno.ai/image_" + id + ".jpeg";
        if (title == null || title.isEmpty()) title = "Suno track";
        if (artist == null) artist = "";
        return new Song(link, title, artist, audio, image);
    }

    static Bitmap loadBitmap(String url) {
        if (url == null) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            byte[] data;
            try {
                data = read(c.getInputStream(), 15_000_000);
            } finally {
                c.disconnect();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 700) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static String meta(String html, String prop) {
        String p = Pattern.quote(prop);
        Pattern a = Pattern.compile(
                "<meta[^>]*?(?:property|name)=[\"']" + p + "[\"'][^>]*?content=\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE);
        Pattern b = Pattern.compile(
                "<meta[^>]*?content=\"([^\"]*)\"[^>]*?(?:property|name)=[\"']" + p + "[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = a.matcher(html);
        if (m.find()) return emptyToNull(m.group(1));
        m = b.matcher(html);
        if (m.find()) return emptyToNull(m.group(1));
        return null;
    }

    private static String clean(String s) {
        if (s == null) return null;
        return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > max) break;
            }
            return out.toByteArray();
        }
    }
}
