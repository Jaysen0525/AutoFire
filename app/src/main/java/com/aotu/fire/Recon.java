package com.aotu.fire;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 界面侦察：延时若干秒后导出「当前前台界面」的节点结构。
 *
 * 用法：主界面点「开始侦察」→ 立刻切到多闪（或按电源键息屏测锁屏）→ 到点自动采集。
 * 采集不需要点亮屏幕或切换窗口，因为无障碍服务能看到所有窗口，
 * 我们的应用在后台也照样能读。
 */
public final class Recon {

    /** 最近一次采集结果。 */
    public static volatile String lastDump = null;
    public static volatile String lastTarget = null;

    /** 采集结果同时落成的文件名，供文件分享用。 */
    public static final String RECON_FILE = "recon.txt";

    private static Context appCtx;

    private static final Handler H = new Handler(Looper.getMainLooper());

    private Recon() {
    }

    public static void cancel() {
        H.removeCallbacksAndMessages(null);
    }

    public static void capture(final Context ctx, long delayMs) {
        if (ctx != null) {
            appCtx = ctx.getApplicationContext();
        }
        FireLog.i("界面侦察已排定：" + (delayMs / 1000) + " 秒后采集，请立刻切到目标界面。");
        // 拿一把唤醒锁：切到多闪后我们处于后台，防止系统把进程挂起导致定时器不触发
        if (ctx != null) {
            WakeActivity.acquireWakelock(ctx);
        }
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!FireAccessibilityService.ready()) {
                    lastDump = "无障碍服务未连接，无法采集。";
                    FireLog.w("侦察失败：无障碍未连接");
                    WakeActivity.releaseWakelock();
                    return;
                }
                FireAccessibilityService svc = FireAccessibilityService.get();
                String pkg = "";
                try {
                    if (svc.getRootInActiveWindow() != null) {
                        CharSequence p = svc.getRootInActiveWindow().getPackageName();
                        pkg = p == null ? "" : p.toString();
                    }
                } catch (Exception ignored) {
                }
                lastTarget = pkg;
                String dump = svc.dumpAllWindows(true);
                lastDump = dump;
                FireLog.i("侦察完成，前台包名=" + pkg + "，行数=" + dump.split("\n").length
                        + "，字符数=" + dump.length());
                if (appCtx != null) {
                    Exporter.write(appCtx, RECON_FILE, dump);
                }
                WakeActivity.releaseWakelock();
            }
        }, delayMs);
    }

    public static void captureNow() {
        capture(null, 0);
    }
}
