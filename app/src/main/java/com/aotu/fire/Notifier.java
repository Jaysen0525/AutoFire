package com.aotu.fire;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * 通知与「全屏意图」。
 *
 * 全屏意图（full-screen intent）是 Android 官方给闹钟/来电准备的「点亮屏幕并显示在锁屏之上」
 * 的正规通道 —— 相比自己 startActivity + setTurnScreenOn，它由系统代为启动，绕开了
 * 「后台启动 Activity」的限制。
 *
 * 注意 Android 14+ 的新限制：非闹钟/通话类应用默认拿不到这个权限，
 * 需要用户在 设置 → 应用 → 特殊权限 → 全屏通知 里手动允许。本类会检测并如实上报。
 */
public final class Notifier {

    public static final String CH_ALARM = "aotu_fire_alarm";
    public static final String CH_FG = "aotu_fire_fg";

    private static final int WAKE_ID_BASE = 2000;

    /** 每次唤醒用一个**新的**通知 id —— 见下面 fireFullScreenWake 的说明 */
    private static volatile int sWakeNotifyId = WAKE_ID_BASE;

    private Notifier() {
    }

    /**
     * 把应用图标的火苗渲染成 Bitmap，用作通知的**大图标**。
     *
     * 为什么需要它：不设置大图标时，部分 ROM 会自行用"应用图标"或
     * 系统默认图标来填充通知右侧那块区域 —— 那就是用户看到的"安卓默认机器人"。
     * 我们自己明确指定，所有 ROM 上显示的都是我们的火苗。
     */
    private static Bitmap appIconBitmap(Context ctx, int sizePx) {
        try {
            Drawable d = ctx.getDrawable(R.drawable.ic_launcher_foreground);
            if (d == null) {
                return null;
            }
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFFFFFFFF);          // 白底，避免透明背景在通知里发灰
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(c);
            return bmp;
        } catch (Exception e) {
            FireLog.w("生成通知大图标失败：" + e);
            return null;
        }
    }

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            NotificationChannel c = new NotificationChannel(
                    CH_ALARM, "续火花唤醒", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("到点唤醒屏幕以执行续火花");
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(c);
        }
        if (nm.getNotificationChannel(CH_FG) == null) {
            NotificationChannel c = new NotificationChannel(
                    CH_FG, "后台运行", NotificationManager.IMPORTANCE_MIN);
            c.setDescription("保持自动续火花在后台待命");
            nm.createNotificationChannel(c);
        }
    }

    /** 能否使用全屏意图（Android 14+ 需用户手动授权）。 */
    public static boolean canUseFullScreenIntent(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nm =
                        (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.canUseFullScreenIntent();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 发一条带全屏意图的高优先级通知 —— 这是让系统替我们点亮屏幕的正规手段。
     *
     * @param forTask true 表示这次唤醒要接着跑完整任务链。
     *                这个标志必须传进来：v1.0 漏了它，导致全屏意图抢跑启动的实例
     *                按「独立自检」模式运行，解锁成功后不回调 TaskRunner，整个任务卡死。
     */
    public static void fireFullScreenWake(Context ctx, int mode, String pin, boolean forTask) {
        ensureChannels(ctx);
        Intent i = new Intent(ctx, WakeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(WakeActivity.EXTRA_PIN, pin);
        i.putExtra(WakeActivity.EXTRA_MODE, mode);
        i.putExtra(WakeActivity.EXTRA_FOR_TASK, forTask);

        PendingIntent pi = PendingIntent.getActivity(ctx, 100, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n;
        try {
            n = new Notification.Builder(ctx, CH_ALARM)
                    .setSmallIcon(R.drawable.ic_stat_fire)
                    .setLargeIcon(appIconBitmap(ctx, 144))
                    .setContentTitle("自动续火花")
                    .setContentText("到点了，正在唤醒屏幕")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build();
        } catch (Exception e) {
            FireLog.e("构造全屏意图通知失败", e);
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            // ★ 关键：必须先撤掉上一次的通知，再换一个**新的 id** 发。
            // 用固定 id + notify() 只是「更新」已有通知，不保证重新触发全屏意图 ——
            // 现象就是「第一次能亮屏并解锁，第二次只亮屏、面板起不来」。
            nm.cancel(sWakeNotifyId);
            sWakeNotifyId++;
            if (sWakeNotifyId > WAKE_ID_BASE + 200) {
                sWakeNotifyId = WAKE_ID_BASE + 1;
            }
            nm.notify(sWakeNotifyId, n);
            FireLog.i("已发出全屏意图通知（id=" + sWakeNotifyId
                    + "，canUseFullScreenIntent=" + canUseFullScreenIntent(ctx) + "）");
        } catch (Exception e) {
            FireLog.e("发送全屏意图通知失败", e);
        }
    }

    public static Notification buildForegroundNotification(Context ctx, String text) {
        ensureChannels(ctx);
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 101, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, CH_FG)
                .setSmallIcon(R.drawable.ic_stat_fire)
                .setLargeIcon(appIconBitmap(ctx, 144))
                .setContentTitle("自动续火花")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public static void cancelWake(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(sWakeNotifyId);
        }
    }

    /**
     * 任务结果通知 —— 凌晨执行完，早上起来能一眼看到成没成。
     * 失败尤其重要：火花当天没续上是可以手动补的，前提是你得知道它失败了。
     */
    public static void notifyResult(Context ctx, boolean success, String text) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 102, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            Notification n = new Notification.Builder(ctx, CH_ALARM)
                    .setSmallIcon(R.drawable.ic_stat_fire)
                    .setLargeIcon(appIconBitmap(ctx, 144))
                    .setContentTitle(success ? "✔ 续火花成功" : "✘ 续火花失败（可能需要手动补）")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2002, n);
        } catch (Exception e) {
            FireLog.e("发结果通知失败", e);
        }
    }
}
