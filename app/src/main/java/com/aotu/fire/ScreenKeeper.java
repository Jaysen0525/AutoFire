package com.aotu.fire;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 屏幕保持常亮。
 *
 * 为什么需要它：这台手机的**锁屏 10 秒无操作就自动息屏**，而整条任务链
 * （唤醒亮屏 → 解锁 → 打开多闪 → 定位 → 输入 → 发送 → 校验）需要 20 秒以上。
 * 更要命的是**无障碍注入的点击手势不算"用户操作"，不会重置息屏计时**，
 * 所以流程跑到一半屏幕就会自己灭掉 —— 表现为"有时候成、有时候卡在中间"。
 *
 * 为什么不用 WakeLock：实测 `SCREEN_BRIGHT_WAKE_LOCK` 这类老 API 只保证能点亮屏幕，
 * **并不阻止屏幕自动息屏**（10 秒后照样灭）。
 *
 * 用悬浮窗最靠谱：给一个 1×1 像素、全透明、不吃触摸的 TYPE_APPLICATION_OVERLAY 窗口
 * 加上 FLAG_KEEP_SCREEN_ON，屏幕就会一直亮着，而且不遮挡、不干扰任何操作。
 * 只需要「显示在其他应用上层」权限（本应用已申请）。
 */
public final class ScreenKeeper {

    private static volatile View sView;
    private static volatile boolean sWarned;

    private ScreenKeeper() {
    }

    /** 开始保持屏幕常亮（整个任务期间调用一次即可，可重复调用）。 */
    public static void start(Context ctx) {
        if (sView != null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            View v = new View(app);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(v, lp);
            sView = v;
            FireLog.i("已开启「屏幕保持常亮」悬浮窗（任务期间不会自动息屏）");
        } catch (Exception e) {
            if (!sWarned) {
                sWarned = true;
                FireLog.e("开启屏幕常亮失败（多半是缺少悬浮窗权限）", e);
            }
        }
    }

    /** 撤掉常亮窗口，把屏幕交还给系统正常计时。 */
    public static void stop(Context ctx) {
        final View v = sView;
        if (v == null) {
            return;
        }
        sView = null;
        Context app = ctx.getApplicationContext();
        Runnable remove = () -> {
            try {
                WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.removeViewImmediate(v);
                    FireLog.i("已关闭屏幕常亮");
                }
            } catch (Exception e) {
                FireLog.e("移除屏幕常亮窗口失败", e);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run();
        } else {
            new Handler(Looper.getMainLooper()).post(remove);
        }
    }

    public static boolean isActive() {
        return sView != null;
    }
}
