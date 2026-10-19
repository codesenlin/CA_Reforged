package com.huangyx.ca;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameBridgeService extends Service implements Handler.Callback {
    public static WeakReference<GameBridgeService> instance = new WeakReference<>(null);
    private static Callback mCallback;
    private static List<Message> mQueue = Collections.synchronizedList(new ArrayList<>());
    private Handler mHandler = new Handler(this);

    public static void setCallback(Callback c) {
        mCallback = c;
        if (isConnected()) c.onRemoteEnabled();
        if (mQueue.size() > 0) {
            for (Message e : mQueue) {
                c.onRemoteMessage(e);
            }
            mQueue.clear();
        }
    }

    public static void removeCallback() {
        mCallback = null;
    }

    public static boolean isConnected() {
        return instance.get() != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (instance.get() != null) return null;
        instance = new WeakReference<>(this);
        if (ScriptManager.hasInstance()) {
            runMain();
        }
        if (mCallback != null) mCallback.onRemoteEnabled();
        return new Messenger(mHandler).getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance.clear();
        mQueue.clear();
        if (mCallback != null) mCallback.onRemoteDisabled();
        return super.onUnbind(intent);
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (mCallback != null) {
            mCallback.onRemoteMessage(msg);
        } else {
            mQueue.add(msg);
        }
        return false;
    }

    private static final String CHANNEL_OPEN = "game_bridge_open";
    private static final int NOTIFICATION_ID = 0x4742; // "GB"

    /**
     * 适配器绑定成功后把命令助手带到前台。
     *
     * Android 11（API 30）起禁止后台启动 Activity，直接 startActivity 会打断适配器所在的
     * Activity（Performing stop of activity that is not resumed），所以改成发通知、由用户
     * 点按进入；Android 10 及以下仍用原来的方式。
     */
    private void runMain() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            postOpenNotification();
            return;
        }
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .setAction(ScriptInterface.ACTION_START_FROM_BACKGROUND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_FROM_BACKGROUND);
            startActivity(i);
        } catch (Exception e) {
            Log.e("GameBridge", "startActivity failed", e);
        }
    }

    /** Android 11+：发一条「点按打开命令助手」的通知 */
    private void postOpenNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_OPEN)
                : new Notification.Builder(this);
        builder.setContentTitle("命令助手")
                .setContentText("游戏已连接，点按打开命令助手")
                .setSmallIcon(R.mipmap.icon_small)
                .setContentIntent(contentIntent)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL_OPEN, "打开命令助手", NotificationManager.IMPORTANCE_DEFAULT));
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e("GameBridge", "notify failed", e);
        }
    }

    public interface Callback {
        void onRemoteEnabled();

        void onRemoteMessage(Message msg);

        void onRemoteDisabled();
    }
}
