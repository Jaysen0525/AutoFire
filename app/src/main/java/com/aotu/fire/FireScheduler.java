package com.aotu.fire;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 定时层：用 setAlarmClock 排定每天一次的精确闹钟。
 *
 * 为什么用 setAlarmClock 而不是 setExactAndAllowWhileIdle：
 *   · setAlarmClock 是系统里优先级最高的闹钟类型，能可靠穿透 Doze（凌晨 1 点手机通常已进入深度空闲）
 *   · 它会让系统在状态栏显示闹钟图标，等于给用户一个「任务已排定」的可视确认
 *   · 触发时系统会短暂把本应用放进临时白名单，有利于后续启动界面
 */
public final class FireScheduler {

    private static final int REQ_ALARM = 7001;
    private static final int REQ_SHOW = 7002;
    public static final String ACTION_ALARM = "com.aotu.fire.ACTION_ALARM";

    private FireScheduler() {
    }

    /** 计算下一个触发时刻（今天该时刻已过则顺延到明天）。 */
    public static long nextTrigger(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 5_000L) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /** 排定每日闹钟。已关闭定时则自动取消。 */
    public static void schedule(Context ctx) {
        Prefs p = new Prefs(ctx);
        if (!p.enabled()) {
            cancel(ctx);
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            FireLog.w("拿不到 AlarmManager，定时未排定");
            return;
        }

        long when = nextTrigger(p.hour(), p.minute());

        Intent i = new Intent(ctx, FireAlarmReceiver.class).setAction(ACTION_ALARM);
        PendingIntent op = PendingIntent.getBroadcast(ctx, REQ_ALARM, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent show = new Intent(ctx, MainActivity.class);
        show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent showPi = PendingIntent.getActivity(ctx, REQ_SHOW, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi), op);
            p.setScheduledAt(when);
            FireLog.i("已排定每日闹钟：" + format(when) + "（" + p.hour() + ":"
                    + String.format(Locale.US, "%02d", p.minute()) + "）");
        } catch (Exception e) {
            FireLog.e("排定闹钟失败", e);
            // 退一步用不精确的闹钟，至少不会完全不触发
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, op);
                p.setScheduledAt(when);
                FireLog.w("已退化为非精确闹钟（可能晚几分钟触发）");
            } catch (Exception e2) {
                FireLog.e("退化方案也失败", e2);
            }
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(ctx, FireAlarmReceiver.class).setAction(ACTION_ALARM);
        PendingIntent op = PendingIntent.getBroadcast(ctx, REQ_ALARM, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(op);
        new Prefs(ctx).setScheduledAt(0L);
        FireLog.i("已取消每日闹钟");
    }

    public static String describe(Context ctx) {
        Prefs p = new Prefs(ctx);
        if (!p.enabled()) {
            return "未开启";
        }
        long at = p.scheduledAt();
        if (at <= 0) {
            return "已开启，但未排定（点「保存并排定」）";
        }
        return format(at) + "（约 " + humanLeft(at) + " 后）";
    }

    public static String format(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static String humanLeft(long millis) {
        long d = millis - System.currentTimeMillis();
        if (d <= 0) {
            return "已过期";
        }
        long h = d / 3600000;
        long m = (d % 3600000) / 60000;
        if (h > 0) {
            return h + " 小时 " + m + " 分";
        }
        return m + " 分";
    }
}
