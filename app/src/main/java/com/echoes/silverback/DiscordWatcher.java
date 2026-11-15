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
    }

    String token() { return prefs.getString("discord_token", ""); }

    String channel() { return prefs.getString("discord_channel", DEFAULT_CHANNEL); }

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
            PlayerService.updateIfRunning(ctx);
        } else if ("MESSAGE_CREATE".equals(type)) {
            if (!channel().equals(d.optString("channel_id"))) return;
            JSONObject author = d.optJSONObject("author");
            if (author != null && author.optString("id").equals(botId)) return;
            String content = d.optString("content", "");
            if (content.isEmpty() && author != null) {
                log("Message from " + author.optString("username")
                        + " had no text — is Message Content Intent on?");
                return;
            }
            if (!content.toLowerCase().contains("suno.")) return;
            log("Link from " + (author != null ? author.optString("username") : "someone"));
            Player.get(ctx).addFromText(content);
        }
    }

    private void closed(int myGen, int code, String reason) {
        if (myGen != gen) return;
        connected = false;
        main.removeCallbacks(heartbeat);
        if (code == 4004) {
            log("Bot token is wrong — reset it in the Developer Portal and paste it again");
            wanted = false;
            return;
        }
        if (code == 4014 || code == 4013) {
            log("Turn on MESSAGE CONTENT INTENT in the Developer Portal (Bot page)");
            wanted = false;
            return;
        }
        if (wanted) {
            log("Connection lost (" + code + " " + reason + ") — retrying in " + backoffMs / 1000 + "s");
            main.postDelayed(this::connect, backoffMs);
            backoffMs = Math.min(backoffMs * 2, 60000);
        }
    }

    private void send(String json) {
        if (ws != null) ws.send(json);
    }

    private void log(String msg) {
        Player.get(ctx).log("Discord: " + msg);
    }
}
