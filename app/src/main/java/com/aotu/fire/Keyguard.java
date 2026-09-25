package com.aotu.fire;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;

/** 锁屏状态查询与「请求解锁」。 */
public final class Keyguard {

    private Keyguard() {
    }

    public static KeyguardManager km(Context ctx) {
        return (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
    }

    /** 当前是否处于锁屏状态。 */
    public static boolean isLocked(Context ctx) {
        KeyguardManager m = km(ctx);
        return m != null && m.isKeyguardLocked();
    }

    /** 设备是否设置了安全锁屏（PIN / 密码 / 图案）。true 表示不能靠 requestDismissKeyguard 直接解开。 */
    public static boolean isSecure(Context ctx) {
        KeyguardManager m = km(ctx);
        return m != null && m.isDeviceSecure();
    }

    /**
     * 请系统尝试解除锁屏。
     * 无安全锁屏（仅滑动）时会直接解锁并回调成功；
     * 有 PIN/密码时会弹出验证界面 —— 那正是我们要用无障碍点击数字键盘绕过的场景。
     */
    public static void requestDismiss(Activity a) {
        KeyguardManager m = km(a);
        if (m == null) {
            return;
        }
        try {
            m.requestDismissKeyguard(a, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    FireLog.i("requestDismissKeyguard: 成功");
                }

                @Override
                public void onDismissError() {
                    FireLog.w("requestDismissKeyguard: 失败");
                }

                @Override
                public void onDismissCancelled() {
                    FireLog.w("requestDismissKeyguard: 被取消（需要用户在锁屏上验证）");
                }
            });
        } catch (Exception e) {
            FireLog.e("requestDismissKeyguard 抛异常", e);
        }
    }
}
