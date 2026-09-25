package com.aotu.fire;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 前台服务：让进程在息屏后不被国产 ROM 冻结。
 *
 * 这是「到点能自动干活」的前提 —— 普通后台进程在屏幕熄灭几分钟后就会被系统冻结，
 * 里面的 Handler / 定时器全部停止工作。前台服务是唯一不需要 root 的合法保活手段。
 */
public class FireService extends Service {

    private static volatile boolean sRunning = false;

    public static boolean isRunning() {
        return sRunning;
    }

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, FireService.class);
            ctx.startForegroundService(i);
        } catch (Exception e) {
            FireLog.e("启动前台服务失败", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, FireService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        FireLog.init(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(1001, Notifier.buildForegroundNotification(this, "后台待命，等待到点执行"));
            if (!sRunning) {
                sRunning = true;
                FireLog.i("前台服务已启动（进程进入不易被冻结状态）");
            }
        } catch (Exception e) {
            FireLog.e("startForeground 失败", e);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        FireLog.w("前台服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
