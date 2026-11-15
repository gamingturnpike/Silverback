package com.echoes.silverback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the music alive while you're in Discord grabbing links. */
public class PlayerService extends Service {

    private static final String CHANNEL = "silverback_playback";
    private static final int ID = 1;
    static volatile boolean running;

    static void startOrUpdate(Context c) {
        if (running) {
            updateIfRunning(c);
            return;
        }
        try {
            c.startForegroundService(new Intent(c, PlayerService.class));
        } catch (Exception ignored) {
            // Android blocks starting it from the background; playback still continues.
        }
    }

    static void updateIfRunning(Context c) {
        if (!running) return;
        c.getSystemService(NotificationManager.class).notify(ID, build(c));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PlayerService.class));
    }

    static final String ACT_PLAY = "com.echoes.silverback.PLAYPAUSE";
    static final String ACT_SKIP = "com.echoes.silverback.SKIP";

    private static PendingIntent action(Context c, String act, int code) {
        return PendingIntent.getService(c, code, new Intent(c, PlayerService.class).setAction(act),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Stops music, the Discord watcher and this notification. Queue is kept for next time. */
    static void shutdown(Context c) {
        Player.get(c).shutdown();
        DiscordWatcher.get(c).pauseForExit();
        stop(c);
    }

    private static Notification build(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "SilverBack playback",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Song s = Player.get(c).current;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Player pl = Player.get(c);
        Notification.Action playPause = new Notification.Action.Builder(null,
                pl.isPlaying() ? "Pause" : "Play", action(c, ACT_PLAY, 1)).build();
        Notification.Action skip = new Notification.Action.Builder(null, "Skip", action(c, ACT_SKIP, 2)).build();
        PendingIntent exit = PendingIntent.getActivity(c, 3,
                new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_EXIT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action close = new Notification.Action.Builder(null, "Close SilverBack", exit).build();
        return new Notification.Builder(c, CHANNEL)
                .addAction(playPause)
                .addAction(skip)
                .addAction(close)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(s != null ? s.title : "SilverBack")
                .setContentText(s != null ? s.artist
                        : DiscordWatcher.get(c).isRunning() ? "Watching Discord for Suno links" : "")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        String act = intent != null ? intent.getAction() : null;
        if (ACT_PLAY.equals(act)) Player.get(this).togglePause();
        else if (ACT_SKIP.equals(act)) Player.get(this).skip();
        return START_NOT_STICKY;
    }

    /** Swiping SilverBack away in recent apps closes everything. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        boolean stage = rootIntent != null && rootIntent.getComponent() != null
                && MainActivity.class.getName().equals(rootIntent.getComponent().getClassName());
        if (stage || rootIntent == null) {
            Player.get(this).shutdown();
            DiscordWatcher.get(this).pauseForExit();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
