package com.aotu.fire;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 亮屏 + 解锁面板。
 *
 * 解锁顺序（来自实测，顺序不能反）：
 *   1. 点亮屏幕（setTurnScreenOn + 屏幕 WakeLock）
 *   2. **先**调 requestDismissKeyguard —— 实测锁屏数字键盘是这一步之后才出现的
 *   3. 轮询等数字键盘；长时间不出来就上滑一次再继续等
 *   4. 逐个点击密码数字（节点优先，坐标几何兜底）
 *   5. 校验锁屏状态；没解开就整体重试一轮
 *
 * 三个防"静默死掉"的保险（v1.0 就是死在这里）：
 *   · Handler 改为**实例字段** —— 之前是静态共享的，onDestroy 里一句
 *     removeCallbacksAndMessages(null) 会把整条流程的后续步骤全部清掉，不打日志不报错
 *   · 每一步都包 try/catch，异常一律转成明确的失败上报
 *   · 独立的看门狗：到点还没结束就主动判定失败，而不是让上层干等
 */
public class WakeActivity extends Activity {

    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FOR_TASK = "for_task";

    public static final String REPORT_FILE = "unlock_report.txt";

    /** 看门狗时限：超过这个时间还没结论就主动失败 */
    private static final long WATCHDOG_MS = 50_000;

    public static volatile String lastReport = null;

    private static volatile boolean sStarted = false;

    /** 注意：实例字段，不是静态。静态共享会导致 onDestroy 误清整条流程。 */
    private final Handler H = new Handler(Looper.getMainLooper());

    private static PowerManager.WakeLock sWakelock;

    private final StringBuilder report = new StringBuilder();
    private String pin;
    private boolean forTask;
    private boolean done;
    private int attempt;
    private boolean swipedUp;
    private TextView status;

    public static void markNotStarted() {
        sStarted = false;
    }

    public static boolean wasStarted() {
        return sStarted;
    }

    // ------------------------------------------------------------------
    // 唤醒锁（静态，供其它组件复用）
    // ------------------------------------------------------------------

    static void acquireWakelock(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (sWakelock == null) {
                sWakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AotuFire:test");
                sWakelock.setReferenceCounted(false);
            }
            if (!sWakelock.isHeld()) {
                sWakelock.acquire(10 * 60 * 1000L);
                FireLog.i("已获取 PARTIAL_WAKE_LOCK（10 分钟）");
            }
        } catch (Exception e) {
            FireLog.e("获取 WakeLock 失败", e);
        }
    }

    static void releaseWakelock() {
        try {
            if (sWakelock != null && sWakelock.isHeld()) {
                sWakelock.release();
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sStarted = true;
        Intent it = getIntent();
        pin = it == null ? "" : it.getStringExtra(EXTRA_PIN);
        if (pin == null) {
            pin = "";
        }
        forTask = it != null && it.getBooleanExtra(EXTRA_FOR_TASK, false);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        buildUi();

        log("================ 解锁面板 ================");
        log("用途: " + (forTask ? "任务执行链" : "独立自检"));
        log("onCreate: 屏幕=" + (isInteractive() ? "亮" : "灭")
                + " 锁屏=" + (Keyguard.isLocked(this) ? "已锁" : "未锁")
                + " 安全锁屏=" + (Keyguard.isSecure(this) ? "是" : "否"));
        log("无障碍: " + (FireAccessibilityService.ready() ? "已连接" : "未连接")
                + "，密码位数: " + pin.length());

        if (!isInteractive()) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    PowerManager.WakeLock wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "AotuFire:screenwake");
                    wl.setReferenceCounted(false);
                    wl.acquire(120 * 1000L);
                    log("已补发屏幕唤醒锁");
                }
            } catch (Exception e) {
                log("屏幕唤醒锁失败: " + e);
            }
        }

        // 看门狗：无论流程卡在哪里，都不能让上层干等
        armWatchdog();
        beginUnlockFlow(1200);
    }

    /** 到点还没结论就主动失败，避免上层干等。 */
    private void armWatchdog() {
        H.postDelayed(() -> {
            if (!done) {
                fail("看门狗超时（" + (WATCHDOG_MS / 1000) + " 秒内没拿到解锁结论）");
            }
        }, WATCHDOG_MS);
    }

    /** 解锁流程的入口：先做前置检查，再进入「请求系统解锁 → 等键盘 → 点数字」。 */
    private void beginUnlockFlow(long delayMs) {
        if (!FireAccessibilityService.ready()) {
            fail("无障碍服务未启用");
            return;
        }
        if (pin.isEmpty()) {
            fail("没有锁屏密码");
            return;
        }
        if (!Keyguard.isLocked(this)) {
            succeed("设备本来就未锁定");
            return;
        }
        post(delayMs, "requestDismiss", this::requestDismissStep);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#E6000000"));
        root.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText(forTask ? "自动续火花 · 正在执行" : "自动续火花 · 解锁自检");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#B0FFFFFF"));
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 30, 0, 0);
        status.setText("正在自动解锁…");
        root.addView(status);

        setContentView(root);
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /**
     * 关键补丁 1：同一个实例被复用时（CLEAR_TOP|SINGLE_TOP 不会重建 Activity），
     * 新的 Intent 只能通过这里拿到。
     *
     * 关键补丁 2（v1.4）：如果被复用的实例**已经跑完**、而设备现在又是锁屏状态，
     * 那么这次唤醒必须**重置面板并重新解锁** —— 否则本次唤醒会被静默吞掉：
     * 面板不出现、不报错，任务干等到超时。这正是「间隔太短的第二次执行只亮屏」的原因。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        String p = intent.getStringExtra(EXTRA_PIN);
        if (p != null && !p.isEmpty()) {
            pin = p;
        }
        boolean wantsTask = intent.getBooleanExtra(EXTRA_FOR_TASK, false);
        log("onNewIntent: 要求任务模式=" + wantsTask + "，本实例已完成=" + done
                + "，当前锁屏=" + (Keyguard.isLocked(this) ? "已锁" : "未锁"));

        if (wantsTask && !forTask) {
            forTask = true;
            log("已升级为「任务执行链」模式");
        }
        if (!wantsTask) {
            return;
        }
        if (!done) {
            log("面板正在执行中，本次请求合并进当前流程");
            return;
        }
        if (!Keyguard.isLocked(this)) {
            log("设备已解锁，补发执行链回调");
            TaskRunner.get().onUnlocked();
            return;
        }

        // 走到了这里：复用了已结束的实例，而设备仍然锁着 → 必须重新跑一轮
        log("复用了已结束的实例且设备仍锁定 → 重置面板，重新执行解锁流程");
        H.removeCallbacksAndMessages(null);
        done = false;
        attempt = 0;
        swipedUp = false;
        status.setText("正在自动解锁…");
        armWatchdog();
        beginUnlockFlow(600);
    }

    /** 统一的延时执行入口：带 done 检查 + 异常兜底，绝不让步骤静默消失。 */
    private void post(long delayMs, String tag, Runnable step) {
        H.postDelayed(() -> {
            if (done) {
                return;
            }
            try {
                step.run();
            } catch (Throwable t) {
                log("✘ 步骤「" + tag + "」抛异常: " + t);
                fail("内部异常于步骤 " + tag + "：" + t);
            }
        }, delayMs);
    }

    // ------------------------------------------------------------------
    // 解锁流程
    // ------------------------------------------------------------------

    private void requestDismissStep() {
        attempt++;
        log("");
        log("--- 第 " + attempt + " 轮：请求系统解锁（触发密码键盘）---");
        if (!forTask && attempt == 1) {
            try {
                String d = FireAccessibilityService.get().dumpAllWindows(true);
                Exporter.write(this, "lockdump.txt", d);
                log("锁屏结构已存 lockdump.txt（" + d.split("\n").length + " 行）");
            } catch (Exception e) {
                log("采集失败: " + e);
            }
        }
        Keyguard.requestDismiss(this);
        post(1300, "pollKeypad", () -> pollKeypad(0));
    }

    /** 轮询等数字键盘出现。长时间不出现就上滑一次再继续等。 */
    private void pollKeypad(int round) {
        if (!Keyguard.isLocked(this)) {
            succeed("键盘出现前系统就解开了");
            return;
        }
        FireAccessibilityService s = FireAccessibilityService.get();
        if (s == null) {
            fail("无障碍服务中途断开");
            return;
        }

        boolean keypad;
        try {
            FireAccessibilityService s2 = FireAccessibilityService.get();
            keypad = s2 != null
                    && (!s2.collectDigitKeys(true).isEmpty() || !s2.collectDigitKeys(false).isEmpty());
        } catch (Throwable t) {
            log("检测数字键盘时异常: " + t);
            keypad = false;
        }

        // 心跳日志：每 3 轮记一次，方便事后判断卡在哪
        if (round % 3 == 0 && round > 0) {
            log("等待密码键盘中…（第 " + round + " 轮，约 " + (round * 7 / 10) + " 秒）"
                    + " 锁屏=" + (Keyguard.isLocked(this) ? "已锁" : "未锁"));
        }

        if (!keypad) {
            // 等了约 4 秒还没键盘 → 上滑一次尝试唤起（部分锁屏需要先上滑）
            if (!swipedUp && round >= 6) {
                swipedUp = true;
                int w = getResources().getDisplayMetrics().widthPixels;
                int h = getResources().getDisplayMetrics().heightPixels;
                s.swipe(w / 2, (int) (h * 0.80), w / 2, (int) (h * 0.42), 260);
                log("等不到键盘，已上滑一次尝试唤起密码键盘");
                post(900, "pollKeypadAfterSwipe", () -> pollKeypad(round + 1));
                return;
            }
            if (round >= 12) {
                log("等了约 9 秒仍没出现数字键盘");
                verdict();
                return;
            }
            post(700, "pollKeypad", () -> pollKeypad(round + 1));
            return;
        }

        log("数字键盘已出现，开始输入密码");
        status.setText("正在输入密码…");
        int clicked = s.typePin(pin);
        log("成功点击数字个数: " + clicked + " / " + pin.length());
        s.sleep(500);
        post(1200, "verdict", this::verdict);
    }

    private void verdict() {
        boolean locked = Keyguard.isLocked(this);
        log("当前锁屏状态: " + (locked ? "仍然锁定" : "已解锁"));
        if (!locked) {
            succeed("自动解锁成功");
            return;
        }
        if (attempt < 2) {
            log("第 " + attempt + " 轮失败，重试一次");
            swipedUp = false;
            post(800, "requestDismiss", this::requestDismissStep);
            return;
        }
        if (!forTask) {
            FireAccessibilityService s = FireAccessibilityService.get();
            if (s != null) {
                log("");
                log("--- 诊断：锁屏上带文字/描述的节点 ---");
                log(s.describeDigitCandidates());
            }
        }
        fail("两轮尝试后仍未解锁");
    }

    // ------------------------------------------------------------------
    // 结果
    // ------------------------------------------------------------------

    private void succeed(String why) {
        if (done) {
            return;
        }
        done = true;
        H.removeCallbacksAndMessages(null);
        log("✔ " + why);
        status.setText("已解锁：" + why);
        if (forTask) {
            FireLog.i("解锁完成，交还执行链");
            TaskRunner.get().onUnlocked();
            H.postDelayed(this::finish, 400);
        } else {
            wrapUp("结论：✔ " + why);
        }
    }

    private void fail(String why) {
        if (done) {
            return;
        }
        done = true;
        H.removeCallbacksAndMessages(null);
        log("✘ " + why);
        status.setText("解锁失败：" + why);
        if (forTask) {
            FireLog.w("解锁失败，通知执行链：" + why);
            TaskRunner.get().onUnlockFailed(why);
            H.postDelayed(this::finish, 1200);
        } else {
            wrapUp("结论：✘ " + why + "。请把报告发给我。");
        }
    }

    private void wrapUp(String conclusion) {
        log("");
        log(conclusion);
        String full = report.toString();
        lastReport = full;
        Exporter.write(this, REPORT_FILE, full);
        status.setText(conclusion + "\n\n3 秒后自动关闭");
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                releaseWakelock();
                finish();
            }
        }, 3000);
    }

    private void log(String line) {
        report.append(line).append('\n');
        FireLog.d("解锁 | " + line);
    }

    public static String readReportFile(Context ctx) {
        try {
            File f = new File(Exporter.dirOf(ctx), REPORT_FILE);
            if (!f.exists()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        // 只清理本实例的回调（H 是实例字段，不会误伤其它组件）
        H.removeCallbacksAndMessages(null);
        if (forTask && !done) {
            FireLog.w("解锁面板在给出结论前被系统销毁，主动上报失败");
            TaskRunner.get().onUnlockFailed("解锁面板被系统提前销毁（可能被锁屏切换或系统回收）");
        }
        super.onDestroy();
    }
}
