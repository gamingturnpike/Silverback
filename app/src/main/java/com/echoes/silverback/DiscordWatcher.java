package com.echoes.silverback;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Signs in to Discord as the user's own bot and queues every Suno link
 * posted in one channel. Runs on the phone — no server needed.
 */
final class DiscordWatcher {

    static final String DEFAULT_CHANNEL = "1552537210799132735";
    private static final String GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final int INTENTS = 1 | (1 << 9) | (1 << 15); // GUILDS, GUILD_MESSAGES, MESSAGE_CONTENT

    private static DiscordWatcher instance;

    static synchronized DiscordWatcher get(Context c) {
        if (instance == null) instance = new DiscordWatcher(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private WebSocket ws;
    private int gen;
    private boolean wanted;
    private boolean connected;
    private long heartbeatMs;
    private Long seq;
    private String botId;
    private long backoffMs = 5000;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            send("{\"op\":1,\"d\":" + (seq == null ? "null" : seq) + "}");
            main.postDelayed(this, heartbeatMs);
        }
    };

    private DiscordWatcher(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("silverback", Context.MODE_PRIVATE);
        Player.get(ctx).addStartListener(this::postNowPlaying);
    }

    /** Posts "Now Playing" in the watched channel when a song starts. */
    private void postNowPlaying(Song s) {
        if (!connected) return;
        StringBuilder msg = new StringBuilder("🎶 **Now Playing:** ").append(s.title);
        if (!s.artist.isEmpty()) msg.append(" — ").append(s.artist);
        if (s.link != null && !s.link.isEmpty()) msg.append("\n").append(s.link);
        send(channel(), msg.toString());
    }

    private void send(String channelId, String text) {
        try {
            JSONObject body = new JSONObject()
                    .put("content", text)
                    .put("allowed_mentions", new JSONObject().put("parse", new org.json.JSONArray()));
            Request r = new Request.Builder()
                    .url("https://discord.com/api/v10/channels/" + channelId + "/messages")
                    .header("Authorization", "Bot " + token())
                    .post(okhttp3.RequestBody.create(body.toString(), JSON))
                    .build();
            http.newCall(r).enqueue(restCallback("reply"));
        } catch (Exception ex) {
            log("Now Playing post failed: " + ex.getMessage());
        }
    }

    String token() { return prefs.getString("discord_token", ""); }

    String channel() { return prefs.getString("discord_channel", DEFAULT_CHANNEL); }

    /** Discord usernames that can drop as many songs as they want (comma separated). */
    String unlimitedNames() { return prefs.getString("unlimited_names", ""); }

    void saveUnlimitedNames(String names) { prefs.edit().putString("unlimited_names", names.trim()).apply(); }

    // The bot's owner (whoever created it on the developer site) is always unlimited.
    private final java.util.Set<String> ownerIds = new java.util.HashSet<>();

    private boolean isUnlimited(String uid, String username) {
        if (ownerIds.contains(uid)) return true;
        for (String n : unlimitedNames().split(",")) {
            String t = n.trim().replace("@", "");
            if (!t.isEmpty() && t.equalsIgnoreCase(username)) return true;
        }
        return false;
    }

    private void loadOwner() {
        Request r = new Request.Builder()
                .url("https://discord.com/api/v10/oauth2/applications/@me")
                .header("Authorization", "Bot " + token())
                .build();
        http.newCall(r).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
            }

            @Override
            public void onResponse(okhttp3.Call call, Response resp) {
                try {
                    JSONObject app = new JSONObject(resp.body().string());
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    JSONObject owner = app.optJSONObject("owner");
                    if (owner != null) ids.add(owner.optString("id"));
                    JSONObject team = app.optJSONObject("team");
                    if (team != null && team.optJSONArray("members") != null) {
                        for (int i = 0; i < team.getJSONArray("members").length(); i++) {
                            JSONObject u = team.getJSONArray("members").getJSONObject(i).optJSONObject("user");
                            if (u != null) ids.add(u.optString("id"));
                        }
                    }
                    String ownerName = owner != null ? owner.optString("username") : "?";
                    main.post(() -> {
                        ownerIds.clear();
                        ownerIds.addAll(ids);
                        log("Bot owner " + ownerName + " has no song limit");
                    });
                } catch (Exception ignored) {
                } finally {
                    resp.close();
                }
            }
        });
    }

    boolean isRunning() { return wanted; }

    boolean isConnected() { return connected; }

    void save(String token, String channel) {
        prefs.edit().putString("discord_token", token).putString("discord_channel", channel).apply();
    }

    /** Called on app launch: reconnects if the watcher was left on. */
    void startIfEnabled() {
        if (prefs.getBoolean("discord_on", false) && !wanted) start();
    }

    void start() {
        if (token().isEmpty()) {
            log("No bot token saved");
            toast("Discord: paste the bot token first");
            return;
        }
        prefs.edit().putBoolean("discord_on", true).apply();
        wanted = true;
        backoffMs = 5000;
        connect();
        PlayerService.startOrUpdate(ctx);
    }

    void turnOff() {
        prefs.edit().putBoolean("discord_on", false).apply();
        wanted = false;
        disconnect();
        log("Turned off");
        if (Player.get(ctx).current == null) PlayerService.stop(ctx);
        else PlayerService.updateIfRunning(ctx);
    }

    /** Closing the app: disconnect but remember it was on, so it reconnects next launch. */
    void pauseForExit() {
        if (!wanted) return;
        wanted = false;
        disconnect();
    }

    private void disconnect() {
        gen++;
        connected = false;
        main.removeCallbacks(heartbeat);
        if (ws != null) ws.close(1000, "bye");
        ws = null;
    }

    private void connect() {
        disconnect();
        if (!wanted) return;
        log("Connecting…");
        final int myGen = gen;
        seq = null;
        ws = http.newWebSocket(new Request.Builder().url(GATEWAY).build(), new WebSocketListener() {
            @Override
            public void onMessage(WebSocket s, String text) {
                main.post(() -> {
                    if (myGen == gen) handle(text);
                });
            }

            @Override
            public void onClosing(WebSocket s, int code, String reason) {
                s.close(code, null);
                main.post(() -> closed(myGen, code, reason));
            }

            @Override
            public void onFailure(WebSocket s, Throwable t, Response r) {
                main.post(() -> closed(myGen, -1, t.getClass().getSimpleName() + " " + t.getMessage()));
            }
        });
    }

    private void handle(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (!o.isNull("s")) seq = o.getLong("s");
            int op = o.getInt("op");
            switch (op) {
                case 10: // Hello
                    heartbeatMs = o.getJSONObject("d").getLong("heartbeat_interval");
                    main.removeCallbacks(heartbeat);
                    main.postDelayed(heartbeat, (long) (heartbeatMs * Math.random()));
                    identify();
                    break;
                case 1: // Server asks for a heartbeat now
                    send("{\"op\":1,\"d\":" + (seq == null ? "null" : seq) + "}");
                    break;
                case 7: // Reconnect
                case 9: // Invalid session
                    log("Discord asked to reconnect");
                    main.postDelayed(this::connect, 3000);
                    break;
                case 0:
                    dispatch(o.getString("t"), o.optJSONObject("d"));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log("Bad message: " + e.getMessage());
        }
    }

    private void identify() throws Exception {
        JSONObject props = new JSONObject()
                .put("os", "android").put("browser", "SilverBack").put("device", "SilverBack");
        JSONObject d = new JSONObject()
                .put("token", token()).put("intents", INTENTS).put("properties", props);
        send(new JSONObject().put("op", 2).put("d", d).toString());
    }

    private void dispatch(String type, JSONObject d) {
        if (d == null) return;
        if ("READY".equals(type)) {
            JSONObject user = d.optJSONObject("user");
            botId = user != null ? user.optString("id") : null;
            connected = true;
            backoffMs = 5000;
            log("Connected as " + (user != null ? user.optString("username") : "bot")
                    + " — watching channel " + channel());
            toast("Discord connected as " + (user != null ? user.optString("username") : "bot"));
            loadOwner();
            PlayerService.updateIfRunning(ctx);
        } else if ("MESSAGE_CREATE".equals(type)) {
            String ch = d.optString("channel_id");
            if (!channel().equals(ch)) {
                if (!SunoResolver.extractLinks(d.optString("content", "")).isEmpty()) {
                    log("Song link seen in a different channel (" + ch + ") — not the one SilverBack watches");
                }
                return;
            }
            JSONObject author = d.optJSONObject("author");
            if (author != null && author.optString("id").equals(botId)) return;
            String content = d.optString("content", "");
            if (content.isEmpty() && author != null) {
                log("Message from " + author.optString("username")
                        + " had no text — is Message Content Intent on?");
                return;
            }
            if (SunoResolver.extractLinks(content).isEmpty()) return;
            String uid = author != null ? author.optString("id") : "unknown";
            String name = author != null ? author.optString("username") : "someone";
            String msgId = d.optString("id");
            if (!Player.get(ctx).requestsOpen() && !isUnlimited(uid, name)) {
                log("Requests are OFF — ignored link from " + name);
                reply(ch, msgId, uid, "<@" + uid + "> 🚫 requests are closed right now.");
                return;
            }
            Player.get(ctx).addFromDiscord(content, uid, name, isUnlimited(uid, name), new Player.DiscordResult() {
                @Override
                public void added(Song song, int extraLinks) {
                    react(ch, msgId, "✅");
                    if (extraLinks > 0) {
                        int lim = Player.get(ctx).perPersonLimit();
                        reply(ch, msgId, uid, "<@" + uid + "> " + limitText(lim) + " — I added "
                                + (lim == 1 ? "your first link (**" + song.title + "**)" : "what fits")
                                + ". Drop more after yours play!");
                    }
                }

                @Override
                public void busy() {
                    int lim = Player.get(ctx).perPersonLimit();
                    reply(ch, msgId, uid, "<@" + uid + "> " + limitText(lim) + "! You already have "
                            + (lim == 1 ? "a song" : lim + " songs") + " in the queue — drop your next one after one plays.");
                }

                @Override
                public void failed() {
                    react(ch, msgId, "❌");
                }
            });
        }
    }

    private void closed(int myGen, int code, String reason) {
        if (myGen != gen) return;
        connected = false;
        main.removeCallbacks(heartbeat);
        if (code == 4004) {
            log("Bot token is wrong — reset it in the Developer Portal and paste it again");
            toast("Discord: bot token is wrong");
            wanted = false;
            return;
        }
        if (code == 4014 || code == 4013) {
            log("Turn on MESSAGE CONTENT INTENT in the Developer Portal (Bot page)");
            toast("Discord: turn on MESSAGE CONTENT INTENT on the bot's page, then Save Changes");
            wanted = false;
            return;
        }
        if (wanted) {
            log("Connection lost (" + code + " " + reason + ") — retrying in " + backoffMs / 1000 + "s");
            if (backoffMs == 5000) toast("Discord: can't connect (" + code + " " + reason + ")");
            main.postDelayed(this::connect, backoffMs);
            backoffMs = Math.min(backoffMs * 2, 60000);
        }
    }

    private static String limitText(int lim) {
        return lim == 1 ? "one song at a time" : lim + " songs at a time";
    }

    // ---- talking back in the channel (needs Add Reactions + Send Messages) ----
    private static final okhttp3.MediaType JSON = okhttp3.MediaType.get("application/json; charset=utf-8");

    private void react(String channelId, String msgId, String emoji) {
        try {
            String e = java.net.URLEncoder.encode(emoji, "UTF-8");
            Request r = new Request.Builder()
                    .url("https://discord.com/api/v10/channels/" + channelId + "/messages/" + msgId + "/reactions/" + e + "/@me")
                    .header("Authorization", "Bot " + token())
                    .put(okhttp3.RequestBody.create(new byte[0], null))
                    .build();
            http.newCall(r).enqueue(restCallback("react"));
        } catch (Exception ex) {
            log("React failed: " + ex.getMessage());
        }
    }

    private void reply(String channelId, String msgId, String userId, String text) {
        try {
            JSONObject body = new JSONObject()
                    .put("content", text)
                    .put("message_reference", new JSONObject().put("message_id", msgId).put("fail_if_not_exists", false))
                    .put("allowed_mentions", new JSONObject()
                            .put("users", new org.json.JSONArray().put(userId))
                            .put("replied_user", true));
            Request r = new Request.Builder()
                    .url("https://discord.com/api/v10/channels/" + channelId + "/messages")
                    .header("Authorization", "Bot " + token())
                    .post(okhttp3.RequestBody.create(body.toString(), JSON))
                    .build();
            http.newCall(r).enqueue(restCallback("reply"));
        } catch (Exception ex) {
            log("Reply failed: " + ex.getMessage());
        }
    }

    private okhttp3.Callback restCallback(String what) {
        return new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                log("Couldn't " + what + ": " + e.getMessage());
            }

            @Override
            public void onResponse(okhttp3.Call call, Response resp) {
                int code = resp.code();
                resp.close();
                if (code == 403) {
                    log("Couldn't " + what + " — bot needs " + (what.equals("react") ? "Add Reactions" : "Send Messages")
                            + " permission in that channel");
                } else if (code >= 300) {
                    log("Couldn't " + what + " (HTTP " + code + ")");
                }
            }
        };
    }

    private void send(String json) {
        if (ws != null) ws.send(json);
    }

    private void toast(String msg) {
        main.post(() -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show());
    }

    private void log(String msg) {
        Player.get(ctx).log("Discord: " + msg);
    }
}
