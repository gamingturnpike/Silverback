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
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(s != null ? s.title : "SilverBack")
                .setContentText(s != null ? s.artist
                        : DiscordWatcher.get(c).isRunning() ? "Watching Discord for Suno links" : "")
                .setContentIntent(pi)
                .setOngoing(true)
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
        return START_NOT_STICKY;
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
