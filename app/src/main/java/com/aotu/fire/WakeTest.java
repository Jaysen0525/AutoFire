package com.aotu.fire;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 唤醒诊断：用三次实验把「到点自动亮屏」这条路拆开，逐段定位断点。
 *
 *   模式 1（前台）：应用还在前台时启动 WakeActivity —— 验证 Activity 自身与解锁流程是否正常
 *   模式 2（后台）：把应用切到后台后启动 WakeActivity —— 单独验证「后台启动 Activity 是否被系统拦截」
 *                    （小米默认禁止，需要「后台弹出界面」权限，这是头号嫌疑）
 *   模式 3（灭屏）：按电源键息屏后启动 —— 验证最终场景，并额外尝试系统级唤醒手段
 *
 * 每次实验都写一条时间线。如果时间线里连「尝试启动」这一行都没有，
 * 说明进程在息屏后被冻结了（前台服务没起到作用）。
 */
public final class WakeTest {

    public static final int MODE_FRONT = 1;
    public static final int MODE_BACK = 2;
    public static final int MODE_SCREEN_OFF = 3;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final StringBuilder HISTORY = new StringBuilder();
    private static final StringBuilder TL = new StringBuilder();

    private static long t0;
    private static int testNo = 0;
    private static PowerManager.WakeLock sPartial;
    private static Context appCtx;

    private WakeTest() {
    }

    public static String history() {
        return HISTORY.toString();
    }

    public static void clearHistory() {
        HISTORY.setLength(0);
        TL.setLength(0);
    }

    public static boolean isRunning() {
        return t0 != 0;
    }

    private static String modeName(int mode) {
        switch (mode) {
            case MODE_FRONT:
                return "1-前台启动";
            case MODE_BACK:
                return "2-后台启动";
            case MODE_SCREEN_OFF:
                return "3-灭屏启动";
            default:
                return "未知";
        }
    }

    private static void tl(String msg) {
        String line = String.format(java.util.Locale.US, "[+%5dms] %s",
                t0 == 0 ? 0 : (System.currentTimeMillis() - t0), msg);
        TL.append(line).append('\n');
        FireLog.d("诊断 | " + line);
    }

    private static String screenState(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean on = pm != null && pm.isInteractive();
            return (on ? "屏幕亮" : "屏幕灭") + " / 锁屏=" + (Keyguard.isLocked(ctx) ? "已锁" : "未锁");
        } catch (Exception e) {
            return "未知";
        }
    }

    // ------------------------------------------------------------------

    public static void start(Context ctx, int mode, long delayMs, String pin) {
        appCtx = ctx.getApplicationContext();
        testNo++;
        TL.setLength(0);
        t0 = System.currentTimeMillis();

        TL.append("===== 测试 ").append(testNo).append("（模式 ")
                .append(modeName(mode)).append("）=====\n");
        tl("排定延迟 " + delayMs + "ms");

        FireService.start(ctx);
        acquireWakelock(ctx);
        WakeActivity.markNotStarted();
        Notifier.ensureChannels(ctx);

        tl("初始状态：" + screenState(ctx));
        tl("前台服务保活：" + (FireService.isRunning() ? "已启动" : "未启动"));
        tl("全屏通知权限：" + (Notifier.canUseFullScreenIntent(ctx) ? "可用" : "不可用（Android 14+ 需手动允许）"));

        final int m = mode;
        final String p = pin;
        H.postDelayed(() -> attempt(m, p), delayMs);
        // 6 秒后回收结论：此时要么 WakeActivity 已经跑过一轮，要么压根没起来
        H.postDelayed(() -> verdict(m), delayMs + 7000);
    }

    private static void attempt(int mode, String pin) {
        if (appCtx == null) {
            return;
        }
        tl("★ 延时到点，进程仍然存活（未被冻结）");

        if (mode == MODE_SCREEN_OFF) {
            if (!WakeActivity.wasStarted()) {
                wakeScreenByWakelock();
                Notifier.fireFullScreenWake(appCtx, mode, pin, false);
                tl("已尝试：屏幕 WakeLock + 全屏意图通知");
            }
        }

        tl("现在调用 startActivity(WakeActivity)");
        try {
            Intent i = new Intent(appCtx, WakeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(WakeActivity.EXTRA_PIN, pin);
            i.putExtra(WakeActivity.EXTRA_MODE, mode);
            appCtx.startActivity(i);
            tl("startActivity 已返回，没有抛异常（注意：被系统静默拦截时也不会抛异常）");
        } catch (Exception e) {
            tl("✘ startActivity 抛异常：" + e);
        }
    }

    private static void verdict(int mode) {
        if (appCtx == null) {
            return;
        }
        boolean started = WakeActivity.wasStarted();
        tl("--- 判定（t+" + (System.currentTimeMillis() - t0) + "ms）---");
        tl("WakeActivity 是否被启动：" + (started ? "是" : "否"));
        tl("当前状态：" + screenState(appCtx));

        if (!started) {
            tl("结论：✘ WakeActivity 没能启动。");
            tl("  → 如果这是模式 2/3：本机拦截了「后台启动 Activity」。");
            tl("  → 小米澎湃OS：设置 → 应用设置 → 应用管理 → 自动续火花 → 权限管理 → 其他权限 → 打开「后台弹出界面」。");
            tl("  → 同时确认「全屏通知」权限已允许，且本应用已开启「自启动」。");
            tl("  → 如果这是模式 1：连前台都起不来，请把本时间线发给我。");
        } else if (mode == MODE_SCREEN_OFF) {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            boolean on = pm != null && pm.isInteractive();
            if (on) {
                tl("结论：✔ 灭屏后成功点亮屏幕并启动了解锁流程！（自动亮屏可行）");
            } else {
                tl("结论：△ WakeActivity 起来了，但屏幕没有点亮。");
                tl("  → 说明后台启动没被拦，卡在「点亮屏幕」这一环。");
                tl("  → 备用方案：直接用「屏幕常亮模式」，跳过唤醒环节。");
            }
        } else {
            tl("结论：✔ WakeActivity 正常启动（该环节通过）。");
        }

        tl("本轮解锁结果见 ③ 区域的报告。");
        HISTORY.append(TL).append('\n');
        t0 = 0;
        releaseWakelock();
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static void wakeScreenByWakelock() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "AotuFire:screenwake");
            wl.setReferenceCounted(false);
            wl.acquire(60 * 1000L);
            tl("已申请屏幕唤醒锁 SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP（60 秒）");
        } catch (Exception e) {
            tl("屏幕唤醒锁申请失败：" + e);
        }
    }

    private static void acquireWakelock(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (sPartial == null) {
                sPartial = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AotuFire:diag");
                sPartial.setReferenceCounted(false);
            }
            if (!sPartial.isHeld()) {
                sPartial.acquire(10 * 60 * 1000L);
                tl("已获取 PARTIAL_WAKE_LOCK（10 分钟，防止息屏后 CPU 休眠）");
            }
        } catch (Exception e) {
            tl("获取 WakeLock 失败：" + e);
        }
    }

    private static void releaseWakelock() {
        try {
            if (sPartial != null && sPartial.isHeld()) {
                sPartial.release();
            }
        } catch (Exception ignored) {
        }
    }
}
