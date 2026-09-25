package com.aotu.fire;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 一次「续火花」任务的完整执行链（状态机）。
 *
 * 流程：
 *   唤醒亮屏 → 解锁 → 打开多闪 → 等待列表（顺手关弹窗）→ 点击置顶会话
 *   → 等输入框 → 填文案 → 点发送 → 回读校验 → 回桌面 + 息屏 → 写报告
 *
 * 每一步都会把现场 dump 存成文件（step_XX_xxx.txt），失败时尤其重要 ——
 * 这些 dump 是后续把定位规则精确化的唯一依据。
 *
 * v1-alpha 的定位用的是启发式规则（置顶会话 = 列表区最上方那一项），
 * 拿到真实 dump 后会替换为精确规则。
 */
public final class TaskRunner {

    public static final String REPORT_FILE = "task_report.txt";

    private static final TaskRunner INSTANCE = new TaskRunner();

    public static volatile String lastReport = null;

    public static TaskRunner get() {
        return INSTANCE;
    }

    private final Handler H = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final List<String> dumpFiles = new ArrayList<>();
    private final List<String> dumpNames = new ArrayList<>();
    private final Random random = new Random();

    /** 最近一次任务产生的 dump 文件名（供一键打包分享）。 */
    private static final List<String> LAST_DUMPS = new ArrayList<>();

    public static List<String> lastDumps() {
        return new ArrayList<>(LAST_DUMPS);
    }

    private Context appCtx;
    private Prefs prefs;
    private String message = "";
    private String nickname = "";
    /** 本次要处理的全部好友（逐个处理，每处理完一位都回到列表页重新定位） */
    private final List<String> targets = new ArrayList<>();
    private int targetIndex;
    /** 每位好友的处理结果，用于最后汇总 */
    private final List<String> results = new ArrayList<>();
    /** 任务总时长上限，避免多好友时无限期跑下去 */
    private long taskDeadline;
    private boolean running;
    private int dumpSeq;
    private PowerManager.WakeLock screenWakelock;
    /** 最近一次有进展的时刻，用于识别「卡死的任务」 */
    private long lastProgressAt;
    /** 取消后的冷却期，避免旧任务残留回调干扰新任务 */
    private long graceUntil;
    /**
     * 任务代次。每次 start() 递增；所有延时回调都带上自己出发时的代次，
     * 只有代次仍然相等时才允许继续 —— 这样即使旧任务的回调还挂在 Handler 上，
     * 也绝不会跑到新任务的流程里去（v1.5 的并发乱发就是这个原因）。
     */
    private int generation;
    /**
     * 本次任务是否「自己解锁过」。
     * 收尾时只有自己解锁过才负责把屏幕锁回去 —— 否则唤醒失败时也会去锁屏，
     * 用户看到的就是「我刚解开，它又给我锁上」（v1.7 的实测反馈）。
     */
    private boolean weUnlocked;
    /**
     * 是否仍在「唤醒+解锁」阶段。
     *
     * 75 秒兜底定时器只在这个阶段才允许开枪。之前它不带这个判断 ——
     * 解锁早就成功了，任务的 75 秒倒计时却还在走，多好友跑到第 3、4 位时
     * （解锁约 15 秒 + 每位约 15 秒）刚好越线，正在执行的任务被自己的兜底定时器掐死，
     * 出现「前几位发了、后几位没发」的半截状态。
     */
    private boolean unlockPhase;

    private TaskRunner() {
    }

    public boolean isRunning() {
        return running;
    }

    private FireAccessibilityService svc() {
        return FireAccessibilityService.get();
    }

    /** 当前回调是否已经过期（任务已结束，或已被新一代任务取代）。 */
    private boolean stale(int myGen) {
        return !running || myGen != generation;
    }

    /** 当前正在处理的好友。 */
    private String currentTarget() {
        if (targetIndex >= 0 && targetIndex < targets.size()) {
            return targets.get(targetIndex);
        }
        return nickname;
    }

    private boolean hasMoreTargets() {
        return targetIndex + 1 < targets.size();
    }

    /** 是否已经超出任务总时长上限（多好友时防止无限期跑下去）。 */
    private boolean pastTaskDeadline() {
        return taskDeadline > 0 && System.currentTimeMillis() > taskDeadline;
    }

    private int screenH() {
        FireAccessibilityService s = svc();
        if (s == null) {
            return 2400;
        }
        return s.getResources().getDisplayMetrics().heightPixels;
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    /** @param fullChain true = 从「唤醒亮屏 + 解锁」开始；false = 直接从打开多闪开始（界面里手动跑） */
    public void start(Context ctx, boolean fullChain) {
        long now = System.currentTimeMillis();
        if (running) {
            long idle = now - lastProgressAt;
            if (idle < 180_000L) {
                FireLog.w("已有任务在执行（空闲 " + (idle / 1000) + " 秒），忽略本次请求");
                return;
            }
            // 超过 3 分钟没有任何进展 → 判定卡死。
            // ★ 这里只重置、**不自动接力**：旧任务挂在 Handler 上的回调还在，
            //   如果立刻把 running 置回 true，那些回调的 "!running" 守卫会集体失效，
            //   两个流程并行点击同一个界面 → 就是「任务完成后还在跑、乱发消息」的来源。
            FireLog.w("上一个任务已空闲 " + (idle / 1000) + " 秒，判定卡死，已强制重置。请重新发起任务。");
            running = false;
            unlockPhase = false;
            generation++;              // 让旧任务的残留回调彻底失效
            releaseScreenWakelock();
            // ★ 必须一并撤掉常亮悬浮窗，否则屏幕再也自动熄不了，一直耗电
            if (appCtx != null) {
                ScreenKeeper.stop(appCtx);
            }
            return;
        }
        if (now < graceUntil) {
            FireLog.w("刚取消的任务还在收尾，请几秒后再试");
            return;
        }
        appCtx = ctx.getApplicationContext();
        prefs = new Prefs(appCtx);

        // ★★ 硬性安全闸门：没有好友昵称就绝不执行 ★★
        // v1.5 的实战教训：昵称没填时会退化成「盲点列表第一行」，
        // 而会话列表顺序会随新消息变化，结果就是**发错人**。这是不可逆的社交事故，
        // 所以宁可一天不续火花，也不允许在无法确认目标的情况下发送。
        targets.clear();
        targets.addAll(prefs.targetFriends());
        if (targets.isEmpty()) {
            FireLog.e("拒绝执行：没有设置好友昵称（无法确认收件人，风险不可接受）");
            if (appCtx != null) {
                Notifier.notifyResult(appCtx, false,
                        "任务未执行：请先在「设置」里填写好友昵称（一行一个）。"
                                + "没有昵称就无法确认收件人，程序不会冒险发送。");
            }
            return;
        }
        targetIndex = 0;
        nickname = targets.get(0);

        generation++;
        running = true;
        weUnlocked = false;
        unlockPhase = false;
        results.clear();
        report.setLength(0);
        dumpFiles.clear();
        dumpNames.clear();
        dumpSeq = 0;
        // 总时长上限：每位好友约 15 秒（含间隔），再留足余量
        taskDeadline = now + Math.max(180_000L, targets.size() * 30_000L + 120_000L);

        FireLog.init(appCtx);
        FireService.start(appCtx);

        String pool = prefs.messages();
        String[] lines = pool.split("\\n");
        List<String> valid = new ArrayList<>();
        for (String l : lines) {
            String t = l.trim();
            if (!t.isEmpty()) {
                valid.add(t);
            }
        }
        message = valid.isEmpty() ? "1" : valid.get(random.nextInt(valid.size()));

        note("========== 续火花任务开始 ==========");
        note("任务代次: #" + generation);
        note("完整链路(含唤醒解锁): " + fullChain);
        note("本次文案: \"" + message + "\"");
        note("目标好友（共 " + targets.size() + " 位）: " + targets);
        note("无障碍服务: " + (svc() != null ? "已连接" : "未连接"));

        // ★ 屏幕常亮必须在两条路径上都生效。
        // 之前它只挂在 stepWakeAndUnlock 里，导致「立即执行」模式跑多闪时
        // 屏幕可能中途自动熄灭 —— 现在两条路径共用这里启动。
        ScreenKeeper.start(appCtx);

        if (svc() == null) {
            // 进程刚被闹钟从冷启动拉起时（例如用户清空了后台），
            // 无障碍服务要过几秒才由系统绑定完成。以前这里直接判死，
            // 等于「清空后台 + 到点触发」必定失败。
            note("无障碍服务尚未就绪（进程可能刚被拉起），等待最多 15 秒…");
            waitForService(fullChain, 0);
            return;
        }

        if (fullChain) {
            stepWakeAndUnlock();
        } else {
            stepNormalizeDuoshan();
        }
    }

    /** 轮询等待无障碍服务就绪（进程冷启动后系统需要几秒绑定它）。 */
    private void waitForService(boolean fullChain, int round) {
        final int myGen = generation;
        if (svc() != null) {
            note("✔ 无障碍服务已就绪（等待约 " + round + " 秒）");
            if (fullChain) {
                stepWakeAndUnlock();
            } else {
                stepNormalizeDuoshan();
            }
            return;
        }
        if (round >= 15) {
            finish(false, "无障碍服务未连接（等待 15 秒仍不可用）。"
                    + "请到「设置 → 无障碍」确认服务没有被系统关掉");
            return;
        }
        H.postDelayed(() -> {
            if (!stale(myGen)) {
                waitForService(fullChain, round + 1);
            }
        }, 1000);
    }

    // ------------------------------------------------------------------
    // 第 0 步：唤醒 + 解锁
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void stepWakeAndUnlock() {
        note("");
        note("--- 第 0 步：唤醒亮屏 + 解锁 ---");
        unlockPhase = true;
        boolean interactive = isInteractive();
        note("当前屏幕: " + (interactive ? "亮" : "灭")
                + "，锁屏: " + (Keyguard.isLocked(appCtx) ? "已锁" : "未锁"));

        // 刻意**不在这里点亮屏幕**：全屏意图只有在屏幕仍熄灭时才会「点亮屏幕并启动界面」，
        // 一旦我们先自己把屏幕点亮，它就只能弹个横幅、不再启动界面（实测踩过）。
        // 点亮推迟到第 3.5 秒的尝试 3。
        if (!interactive) {
            note("保持屏幕熄灭，先把最有把握的全屏意图通道打出去");
        }

        if (!Keyguard.isLocked(appCtx)) {
            note("设备未锁定，跳过解锁");
            stepNormalizeDuoshan();
            return;
        }

        if (prefs.pin().isEmpty()) {
            finish(false, "设备已锁定但没保存锁屏密码，无法继续");
            return;
        }

        // 屏幕常亮已在 start() 里开启（两条路径共用），这里不再重复

        // 唤醒是整条链最脆的一环。实测结论：
        //   · 手动测试（App 刚在前台）时，系统会放行「后台启动 Activity」
        //   · 真·定时闹钟触发（App 已经几分钟没露面）时，同样的调用被静默拒绝
        //   · 全屏意图只有在**屏幕仍熄灭**时才会「点亮屏幕并启动界面」，
        //     屏幕已经亮着时它只弹个横幅、不启动界面
        // 所以顺序是：趁屏幕还黑先发全屏意图 → 再自己点屏重试 → 最后走
        // **完全不需要 Activity 的解锁通道**。
        final int myGen = generation;
        WakeActivity.markNotStarted();

        // ① 立刻发全屏意图通知 —— 此刻屏幕仍熄灭（我们刻意还没点屏），成功率最高
        H.postDelayed(() -> {
            if (stale(myGen) || WakeActivity.wasStarted()) {
                return;
            }
            note("尝试 1：全屏意图通知（屏幕仍熄灭，这是系统唯一保证亮屏并启动界面的通道）");
            Notifier.fireFullScreenWake(appCtx, WakeTest.MODE_SCREEN_OFF, prefs.pin(), true);
        }, 150);

        // ② 同时直接启动一次（此时也还没点屏）
        H.postDelayed(() -> {
            if (stale(myGen) || WakeActivity.wasStarted()) {
                return;
            }
            launchWakeActivity("尝试 2：直接 startActivity");
        }, 1200);

        // ③ 3.5 秒还没起来：现在才自己点亮屏幕（会重新点亮已熄灭的屏幕）+ 再启动
        H.postDelayed(() -> {
            if (stale(myGen) || WakeActivity.wasStarted()) {
                return;
            }
            forceScreenOn();
            launchWakeActivity("尝试 3：已自行点亮屏幕，再次 startActivity");
        }, 3500);

        // ④ 7 秒还没起来：再点一次屏 + 再发一次全屏意图（换新 id）
        H.postDelayed(() -> {
            if (stale(myGen) || WakeActivity.wasStarted()) {
                return;
            }
            forceScreenOn();
            note("尝试 4：再次点亮屏幕 + 发全屏意图通知");
            Notifier.fireFullScreenWake(appCtx, WakeTest.MODE_SCREEN_OFF, prefs.pin(), true);
        }, 7000);

        // ⑤ 11 秒还没起来：放弃面板，改走「不需要 Activity」的解锁通道
        H.postDelayed(() -> {
            if (stale(myGen) || WakeActivity.wasStarted()) {
                return;
            }
            note("四次尝试都没能唤起解锁面板 → 改用「不用面板」的解锁通道");
            dump("00_wake_failed");
            stepUnlockWithoutActivity();
        }, 11_000);

        // 兜底超时：**只在解锁阶段生效**。解锁一完成就把它缴械，
        // 否则多好友跑到一半会被自己的兜底定时器掐死。
        H.postDelayed(() -> {
            if (unlockPhase && !stale(myGen)) {
                note("解锁阶段超过 75 秒仍无结论，放弃本次任务");
                finish(false, "解锁超时（75 秒内面板与备用通道都没给出结论）");
            }
        }, 75_000);
    }

    /**
     * 强制（重新）点亮屏幕。
     *
     * 注意必须「先释放再申请」：ACQUIRE_CAUSES_WAKEUP 只在**获取锁的那一瞬间**生效，
     * 已经持有的锁再 acquire 不会把已经自动熄灭的屏幕重新点亮。
     */
    @SuppressWarnings("deprecation")
    private void forceScreenOn() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (screenWakelock == null) {
                screenWakelock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "AotuFire:task");
                screenWakelock.setReferenceCounted(false);
            } else if (screenWakelock.isHeld()) {
                screenWakelock.release();
            }
            screenWakelock.acquire(5 * 60 * 1000L);
            FireLog.i("已点亮屏幕" + (pm.isInteractive() ? "" : "（重新点亮）"));
        } catch (Exception e) {
            FireLog.e("点亮屏幕失败", e);
        }
    }

    /**
     * 「不需要 Activity」的解锁通道。
     *
     * 为什么需要它：系统在特定情况下会拒绝后台启动 Activity（真·定时闹钟触发时实测会发生），
     * 而 requestDismissKeyguard 需要一个 Activity 上下文。但锁屏密码键盘本身是可以
     * 用无障碍手势直接操作的 —— 上滑唤出键盘，然后点数字，全程不需要任何界面。
     *
     * 这条通道绕开了「后台启动 Activity」这个最脆的环节。
     */
    private void stepUnlockWithoutActivity() {
        note("");
        note("--- 备用解锁通道：上滑唤出密码键盘 + 无障碍点数字（不需要任何界面）---");
        forceScreenOn();
        final int myGen = generation;
        final int[] round = new int[]{0};
        final Runnable[] loop = new Runnable[1];

        loop[0] = new Runnable() {
            @Override
            public void run() {
                if (stale(myGen)) {
                    return;
                }
                FireAccessibilityService s2 = svc();
                if (s2 == null) {
                    finish(false, "无障碍服务断开");
                    return;
                }
                // 每一轮都重新点亮屏幕 —— 这台手机 10 秒就会自动息屏，
                // 而注入的手势不算"用户操作"，不会重置计时
                forceScreenOn();
                if (!Keyguard.isLocked(appCtx)) {
                    note("✔ 备用通道解锁成功（设备已不再锁定）");
                    unlockPhase = false;
                    weUnlocked = true;
                    stepNormalizeDuoshan();
                    return;
                }
                round[0]++;
                boolean keypad = !s2.collectDigitKeys(true).isEmpty()
                        || !s2.collectDigitKeys(false).isEmpty();
                if (!keypad) {
                    // 反复上滑尝试唤出密码键盘
                    int w = s2.getResources().getDisplayMetrics().widthPixels;
                    int h = s2.getResources().getDisplayMetrics().heightPixels;
                    if (round[0] % 3 == 1) {
                        s2.swipe(w / 2, (int) (h * 0.82), w / 2, (int) (h * 0.35), 260);
                        note("第 " + round[0] + " 轮：上滑尝试唤出密码键盘");
                    }
                    if (round[0] >= 15) {
                        note("✘ 上滑也唤不出密码键盘");
                        dump("00_noactivity_unlock_failed");
                        finish(false, "面板起不来，不用面板的上滑解锁也没成功。"
                                + "建议开启「开发者选项 → 充电时屏幕不休眠」并长期插电，"
                                + "让屏幕保持常亮即可彻底绕开这一环");
                        return;
                    }
                    H.postDelayed(loop[0], 700);
                    return;
                }
                note("密码键盘已出现，开始输入");
                int clicked = s2.typePin(prefs.pin());
                note("成功点击数字个数: " + clicked + " / " + prefs.pin().length());
                s2.sleep(600);
                if (round[0] >= 15) {
                    dump("00_noactivity_unlock_failed");
                    finish(false, "备用通道输入密码后仍未解锁");
                    return;
                }
                H.postDelayed(loop[0], 1100);
            }
        };
        H.postDelayed(loop[0], 600);
    }

    /** 直接拉起解锁面板。每次调用都是一次「尝试」，结果由调用方按时间点检查。 */
    private void launchWakeActivity(String label) {
        note(label);
        try {
            android.content.Intent i = new android.content.Intent(appCtx, WakeActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(WakeActivity.EXTRA_PIN, prefs.pin());
            i.putExtra(WakeActivity.EXTRA_MODE, WakeTest.MODE_SCREEN_OFF);
            i.putExtra(WakeActivity.EXTRA_FOR_TASK, true);
            appCtx.startActivity(i);
            note("startActivity 已返回（被系统静默拦截时也不会抛异常）");
        } catch (Exception e) {
            note("startActivity 抛异常: " + e);
        }
    }

    /** WakeActivity 解锁成功后会回调这里。 */
    public void onUnlocked() {
        if (!running) {
            return;
        }
        unlockPhase = false;          // 解锁阶段结束，兜底定时器失效
        weUnlocked = true;
        note("✔ 解锁完成，继续执行");
        stepNormalizeDuoshan();
    }

    /** WakeActivity 解锁失败时回调这里。 */
    public void onUnlockFailed(String reason) {
        if (!running) {
            return;
        }
        unlockPhase = false;
        finish(false, "解锁失败：" + reason);
    }

    private boolean isInteractive() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 第 1 步：打开多闪 + 等待列表（顺手关弹窗）
    // ------------------------------------------------------------------

    /**
     * 第 1 步：把多闪**归零到「会话列表」页**，而不是假设启动后就是首页。
     *
     * 为什么必须这么做：一个已在运行的 App，用启动意图拉起时 Android 默认恢复它上次的界面 ——
     * 昨天执行完停在某个聊天页，今天解锁后多闪就在那个聊天页上。此时如果直接开始找会话列表，
     * 会把消息气泡误判成会话项，轻则失败、重则发错人。
     *
     * 所以这里用循环纠错，不假设任何起点：
     *   ① 有开屏弹窗/活动页 → 关掉
     *   ② 前台不是多闪      → 用 CLEAR_TOP 重新拉起（强制回根页面）
     *   ③ 在多闪但停在聊天页 → 按返回键退出来
     *   ④ 连续几轮都识别不出页面 → 按一次返回兜底
     *   ⑤ 确认是会话列表页且找到目标会话 → 才进入下一步
     */
    private void stepNormalizeDuoshan() {
        stepNormalizeDuoshan(true);
    }

    /**
     * @param freshStart true = 从"不知道前台是什么"的干净起点开始（解锁后 / 任务第一步）：
     *                   先回桌面清场，再用 CLEAR_TOP 拉起多闪。
     *                   false = 我们已经在多闪里（处理下一位好友）：**不要回桌面**，
     *                   只按返回键退到消息列表即可 —— 否则每位好友都要白跑一趟桌面 +
     *                   冷启动多闪，又慢又难看。
     */
    private void stepNormalizeDuoshan(boolean freshStart) {
        note("");
        note("--- 第 " + (targetIndex + 1) + "/" + targets.size() + " 位：把多闪归零到「聊天列表」页 ---");
        FireAccessibilityService s = svc();
        if (s == null) {
            finish(false, "无障碍服务断开");
            return;
        }
        note("起始前台: " + describeForeground(s));

        if (freshStart) {
            // 只有彻底不确定起点时才回桌面清场（别的 App、系统弹窗、全屏页面）
            try {
                s.pressHome();
                note("已按 Home 回桌面，清除残留前台状态");
            } catch (Exception e) {
                note("按 Home 失败: " + e);
            }
        } else {
            note("已在多闪内，直接就地归零（不回桌面）");
        }

        final long deadline = System.currentTimeMillis() + 40_000;
        final int myGen = generation;
        final int[] round = new int[]{0};
        final int[] backCount = new int[]{0};
        final int[] popupCount = new int[]{0};
        final int[] unknownStreak = new int[]{0};
        final int[] tabCount = new int[]{0};
        final Runnable[] loop = new Runnable[1];

        loop[0] = new Runnable() {
            @Override
            public void run() {
                if (stale(myGen)) {
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    dump("01_normalize_timeout");
                    // 已经在消息列表页、只是找不到这位好友 → 跳过这一位，继续下一位
                    // （昵称可能改过、或对方昵称没暴露；不应该让整批任务因此中断）
                    FireAccessibilityService sNow = svc();
                    if (sNow != null && DuoshanDriver.isMessagePage(sNow)) {
                        note("已在消息列表页但找不到「" + currentTarget() + "」");
                        targetFailed("在会话列表里找不到这位好友（昵称可能需要更新）");
                    } else {
                        finish(false, "40 秒内没能把多闪归零到「聊天列表」页（见 dump）");
                    }
                    return;
                }
                FireAccessibilityService s2 = svc();
                if (s2 == null) {
                    finish(false, "归零过程中无障碍断开");
                    return;
                }
                round[0]++;
                String fg = DuoshanDriver.foregroundPackage(s2);

                // ① 关开屏弹窗 / 活动页
                AccessibilityNodeInfo popup = DuoshanDriver.findPopupButton(s2);
                if (popup != null && popupCount[0] < 6) {
                    String t = NodeDump.trim(popup.getText());
                    if (t.isEmpty()) {
                        t = NodeDump.trim(popup.getContentDescription());
                    }
                    boolean clicked = s2.clickNode(popup);
                    popupCount[0]++;
                    note("第 " + round[0] + " 轮 · 关弹窗「" + t + "」-> "
                            + (clicked ? "已点击" : "点击失败"));
                    H.postDelayed(loop[0], 1200);
                    return;
                }

                // ② 不在多闪 → 强制拉起根页面
                if (!DuoshanDriver.PKG.equals(fg)) {
                    note("第 " + round[0] + " 轮 · 前台是 " + describeForeground(s2)
                            + " → 拉起多闪（CLEAR_TOP 归零到根页面）");
                    s2.openApp(DuoshanDriver.PKG);
                    H.postDelayed(loop[0], 2000);
                    return;
                }

                // ③ 在多闪但停在聊天页 → 返回
                AccessibilityNodeInfo inputBox = DuoshanDriver.findInputBox(s2);
                if (inputBox != null) {
                    if (backCount[0] < 4) {
                        backCount[0]++;
                        note("第 " + round[0] + " 轮 · 检测到停在聊天页，按返回键（第 "
                                + backCount[0] + " 次）");
                        s2.pressBack();
                        H.postDelayed(loop[0], 1300);
                        return;
                    }
                    note("第 " + round[0] + " 轮 · 已按过多次返回，仍在聊天页");
                }

                // ④ 不在消息列表页 → 点底部第一个 tab 切回来
                boolean onMessagePage = DuoshanDriver.isMessagePage(s2);
                if (!onMessagePage) {
                    AccessibilityNodeInfo tab = DuoshanDriver.findHomeTab(s2, screenH());
                    if (tab != null && tabCount[0] < 4) {
                        tabCount[0]++;
                        boolean clicked = s2.clickNode(tab);
                        note("第 " + round[0] + " 轮 · 不在消息列表页 → 点底部「多闪」tab -> "
                                + (clicked ? "已点击" : "点击失败") + "（第 " + tabCount[0] + " 次）");
                        H.postDelayed(loop[0], 1200);
                        return;
                    }
                }

                // ⑤ 已在消息列表页 → 找目标会话行（RecyclerView 结构锚定）
                AccessibilityNodeInfo row = DuoshanDriver.findTargetRow(s2, currentTarget());
                if (row != null) {
                    Rect r = new Rect();
                    row.getBoundsInScreen(r);
                    note("✔ 第 " + round[0] + " 轮 · 命中目标会话：昵称=\""
                            + DuoshanDriver.rowNickname(row) + "\" bounds=" + r.toShortString());
                    dump("01_list");
                    stepTapConversation(row);
                    return;
                }

                // ⑥ 连续识别不出 → 按一次返回兜底（覆盖各种二级页面）
                unknownStreak[0]++;
                if (unknownStreak[0] >= 3 && backCount[0] < 4) {
                    backCount[0]++;
                    unknownStreak[0] = 0;
                    note("第 " + round[0] + " 轮 · 页面无法识别，按返回键兜底（第 "
                            + backCount[0] + " 次）");
                    s2.pressBack();
                    H.postDelayed(loop[0], 1300);
                    return;
                }

                if (round[0] % 4 == 0) {
                    List<AccessibilityNodeInfo> rows = DuoshanDriver.conversationRows(s2);
                    note("第 " + round[0] + " 轮 · 消息列表页=" + onMessagePage
                            + "，尚未命中目标会话「" + currentTarget() + "」。当前列表：\n"
                            + DuoshanDriver.describeRows(s2));
                    if (!rows.isEmpty() && round[0] <= 4) {
                        note(DuoshanDriver.describeRowDetail(rows.get(0)));
                    }
                } else {
                    note("第 " + round[0] + " 轮 · 消息列表页=" + onMessagePage
                            + "，还没命中目标会话，继续等待…");
                }
                H.postDelayed(loop[0], 1000);
            }
        };
        H.postDelayed(loop[0], 800);
    }

    private String describeForeground(FireAccessibilityService s) {
        String fg = DuoshanDriver.foregroundPackage(s);
        if (fg.isEmpty() || "null".equals(fg)) {
            return "（未知）";
        }
        return fg;
    }

    // ------------------------------------------------------------------
    // 第 2 步：点击置顶会话
    // ------------------------------------------------------------------

    private void stepTapConversation(AccessibilityNodeInfo conv) {
        note("");
        note("--- 第 2 步：进入会话 ---");
        Rect r = new Rect();
        conv.getBoundsInScreen(r);
        note("目标会话节点: " + NodeDump.shortClass(conv.getClassName())
                + " 昵称=\"" + DuoshanDriver.rowNickname(conv) + "\" bounds=" + r.toShortString());

        FireAccessibilityService s = svc();
        boolean clicked = s != null && s.clickNode(conv);
        note("点击结果: " + (clicked ? "已点击" : "点击失败"));
        if (!clicked && s != null) {
            s.tap(r.centerX(), r.centerY());
            note("已退化为坐标点击");
        }

        waitFor("聊天窗口输入框", 15_000,
                () -> {
                    FireAccessibilityService s2 = svc();
                    return s2 != null && DuoshanDriver.isChatPage(s2);
                },
                () -> {
                    dump("03_chat");
                    stepInputText();
                }, true);
    }

    // ------------------------------------------------------------------
    // 第 3 步：填文案
    // ------------------------------------------------------------------

    private void stepInputText() {
        note("");
        note("--- 第 3 步：确认收件人 + 填写文案 ---");
        FireAccessibilityService s = svc();
        if (s == null) {
            finish(false, "无障碍服务断开");
            return;
        }

        // ★★ 发送前的最后一道安全闸门 ★★
        // 前面所有定位都可能出错（昵称提取失败、点到别的行、页面结构变化），
        // 但这里是"打字"之前的最后机会：确认不了收件人就绝不发送。
        // 宁可今天不续火花，也不能把消息发给陌生人。
        // （多好友时这道闸门更重要：连续发送时任何一次串行都可能发错人）
        final String target = currentTarget();
        String title = DuoshanDriver.chatTitle(s, screenH());
        note("当前聊天窗口顶栏显示的昵称：「" + title + "」");
        if (!DuoshanDriver.currentChatLooksLike(s, target, screenH())) {
            note("✘ 聊天窗口顶栏里找不到目标昵称「" + target + "」");
            note("→ 判定为进错了会话，已中止。**没有发送任何消息。**");
            note("顶栏区域实际出现的文本（用于排查判据）：\n"
                    + DuoshanDriver.describeTopTexts(s, screenH()));
            dump("03_wrong_chat");
            targetFailed("进错了会话：顶栏是「" + title + "」，不是「" + target + "」（未发送）");
            return;
        }
        note("✔ 已确认当前会话是目标好友「" + target + "」");

        AccessibilityNodeInfo input = DuoshanDriver.findInputBox(s);
        if (input == null) {
            dump("03_input_fail");
            targetFailed("没找到输入框");
            return;
        }
        Rect ir = new Rect();
        input.getBoundsInScreen(ir);
        note("输入框: " + NodeDump.shortClass(input.getClassName())
                + " bounds=" + ir.toShortString());

        boolean set = DuoshanDriver.setInputText(s, input, message);
        note("填入文案: " + (set ? "成功" : "失败"));
        if (!set) {
            dump("03_settext_fail");
            targetFailed("文案写入失败（输入框可能是自绘控件）");
            return;
        }
        final int myGen = generation;
        H.postDelayed(() -> {
            if (stale(myGen)) {
                return;
            }
            stepSend(ir);
        }, 900);
    }

    // ------------------------------------------------------------------
    // 第 4 步：发送
    // ------------------------------------------------------------------

    private void stepSend(Rect inputBounds) {
        note("");
        note("--- 第 4 步：点击发送 ---");
        FireAccessibilityService s = svc();
        if (s == null) {
            finish(false, "无障碍服务断开");
            return;
        }
        AccessibilityNodeInfo send = DuoshanDriver.findSendButton(s, inputBounds);
        if (send == null) {
            dump("04_send_fail");
            targetFailed("没找到发送按钮");
            return;
        }
        Rect sr = new Rect();
        send.getBoundsInScreen(sr);
        note("发送按钮: " + NodeDump.shortClass(send.getClassName())
                + " T=\"" + NodeDump.trim(send.getText()) + "\" bounds=" + sr.toShortString());

        boolean clicked = s.clickNode(send);
        note("点击结果: " + (clicked ? "已点击" : "点击失败"));
        if (!clicked) {
            s.tap(sr.centerX(), sr.centerY());
            note("已退化为坐标点击");
        }
        final int myGen = generation;
        H.postDelayed(() -> {
            if (stale(myGen)) {
                return;
            }
            stepVerify();
        }, 1500);
    }

    // ------------------------------------------------------------------
    // 第 5 步：回读校验
    // ------------------------------------------------------------------

    private void stepVerify() {
        if (stale(generation)) {
            return;
        }
        note("");
        note("--- 第 5 步：回读校验（只认消息列表里的气泡，输入框里的不算）---");
        waitFor("刚发出的消息出现在消息列表里", 8000,
                () -> {
                    FireAccessibilityService s = svc();
                    return s != null && DuoshanDriver.containsSentMessage(s, message);
                },
                this::stepSuccess, true);
    }

    private void stepSuccess() {
        final String target = currentTarget();
        note("✔ 校验通过：已发给「" + target + "」：" + message);
        results.add(target + " ✔ 已发送 \"" + message + "\"");
        stepNextTarget();
    }

    // ------------------------------------------------------------------
    // 多位好友：处理下一位
    // ------------------------------------------------------------------

    /** 当前好友处理失败：记下来、跳过，继续下一位（不中断整批任务）。 */
    private void targetFailed(String reason) {
        final String target = currentTarget();
        note("✘ 好友「" + target + "」这一步失败：" + reason + " → 跳过，继续下一位");
        results.add(target + " ✘ " + reason);
        if (hasMoreTargets()) {
            // 先退出聊天页，回到列表页再处理下一位
            FireAccessibilityService s = svc();
            if (s != null && DuoshanDriver.isChatPage(s)) {
                s.pressBack();
            }
        }
        stepNextTarget();
    }

    /** 排定下一位好友（带一段随机间隔，模拟真人节奏）。 */
    private void stepNextTarget() {
        if (pastTaskDeadline()) {
            finish(false, "任务总时长超限，已停止（剩余 "
                    + (targets.size() - targetIndex - 1) + " 位未处理）");
            return;
        }
        if (!hasMoreTargets()) {
            finishSummary();
            return;
        }
        targetIndex++;
        final String next = currentTarget();
        // 每位之间随机停顿 6~14 秒：既像真人操作，也避免短时间连发触发风控
        int gap = 6000 + random.nextInt(8000);
        note("");
        note("--- 等待约 " + (gap / 1000) + " 秒后处理下一位：「" + next + "」---");

        final int myGen = generation;
        H.postDelayed(() -> {
            if (stale(myGen)) {
                return;
            }
            FireAccessibilityService s = svc();
            if (s == null) {
                finish(false, "无障碍服务断开");
                return;
            }
            // 就地归零：如果在聊天页就退回列表，**不回桌面、也不重启多闪**
            if (DuoshanDriver.isChatPage(s)) {
                note("按返回键退出当前会话，回到消息列表（就地归零，不回桌面）");
                s.pressBack();
                H.postDelayed(() -> {
                    if (!stale(myGen)) {
                        stepNormalizeDuoshan(false);
                    }
                }, 1200);
            } else {
                stepNormalizeDuoshan(false);
            }
        }, gap);
    }

    /** 全部好友处理完毕，汇总收尾。 */
    private void finishSummary() {
        int ok = 0;
        for (String r : results) {
            if (r.contains("✔")) {
                ok++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("成功 ").append(ok).append(" / ").append(targets.size()).append(" 位好友");
        for (String r : results) {
            sb.append("\n  · ").append(r);
        }
        note("");
        note("========== 全部处理完毕 ==========");
        note(sb.toString());
        finish(ok > 0, sb.toString());
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private void finish(boolean success, String reason) {
        if (!running) {
            return;
        }
        note("");
        note("========== 任务结束 ==========");
        note((success ? "结果：✔ 成功" : "结果：✘ 失败") + " —— " + reason);

        FireAccessibilityService s = svc();
        if (s != null) {
            try {
                dump("99_final");
            } catch (Exception ignored) {
            }
            // 回到桌面并息屏，恢复原状
            try {
                // 先撤掉常亮窗口，否则锁屏后屏幕会被它顶住不熄
                ScreenKeeper.stop(appCtx);
                s.pressHome();
                s.sleep(700);
                if (weUnlocked) {
                    s.lockScreen();
                    note("已回到桌面并锁屏（本次任务自己解锁过，负责锁回去）");
                } else {
                    note("已回到桌面（本次任务没有自己解锁，不去动锁屏状态）");
                }
            } catch (Exception e) {
                note("收尾失败: " + e);
            }
        } else {
            ScreenKeeper.stop(appCtx);
        }

        note("");
        note("--- 现场 dump 文件（一并分享即可）---");
        for (String f : dumpFiles) {
            note("  " + f);
        }

        releaseScreenWakelock();
        running = false;
        lastReport = report.toString();
        LAST_DUMPS.clear();
        LAST_DUMPS.addAll(dumpNames);
        Exporter.write(appCtx, REPORT_FILE, lastReport);
        Notifier.notifyResult(appCtx, success, reason);
        FireLog.i("任务结束：" + (success ? "成功" : "失败") + " —— " + reason);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private interface Cond {
        boolean ok();
    }

    private void waitFor(String what, long timeoutMs, Cond cond, Runnable onOk) {
        waitFor(what, timeoutMs, cond, onOk, false);
    }

    /**
     * @param soft true = 超时只算「当前这位好友失败」，跳到下一位继续；
     *             false = 超时视为整批任务失败
     */
    private void waitFor(String what, long timeoutMs, Cond cond, Runnable onOk, boolean soft) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final int myGen = generation;
        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override
            public void run() {
                if (stale(myGen)) {
                    return;
                }
                try {
                    if (cond.ok()) {
                        note("✔ " + what + " 已就绪");
                        onOk.run();
                        return;
                    }
                } catch (Exception e) {
                    note("等待 " + what + " 时异常: " + e);
                }
                if (System.currentTimeMillis() > deadline) {
                    dump("timeout");
                    if (soft) {
                        targetFailed("等待超时：" + what);
                    } else {
                        finish(false, "等待超时：" + what);
                    }
                    return;
                }
                H.postDelayed(holder[0], 600);
            }
        };
        H.postDelayed(holder[0], 600);
    }

    /** 把当前所有窗口结构存成文件，便于事后定位问题。 */
    private void dump(String tag) {
        FireAccessibilityService s = svc();
        if (s == null) {
            return;
        }
        try {
            String content = s.dumpAllWindows(true);
            dumpSeq++;
            String name = String.format(java.util.Locale.US, "step_%02d_%s.txt", dumpSeq, tag);
            Exporter.write(appCtx, name, content);
            dumpFiles.add(name + "（" + content.split("\n").length + " 行）");
            dumpNames.add(name);
            note("已存现场: " + name + "（" + content.length() + " 字符）");
        } catch (Exception e) {
            note("dump 失败: " + e);
        }
    }

    private void note(String line) {
        lastProgressAt = System.currentTimeMillis();
        report.append(line).append('\n');
        FireLog.d("任务 | " + line);
    }

    /** 手动取消正在运行（或已卡死）的任务。 */
    public void cancel(String reason) {
        if (!running) {
            FireLog.w("没有正在运行的任务可取消");
            return;
        }
        note("任务被取消：" + reason);
        releaseScreenWakelock();
        running = false;
        unlockPhase = false;
        graceUntil = System.currentTimeMillis() + 3000L;
        lastReport = report.toString();
        if (appCtx != null) {
            // ★ 取消路径也必须撤掉常亮悬浮窗。漏了它，屏幕会一直亮着耗电到进程被杀。
            ScreenKeeper.stop(appCtx);
            Exporter.write(appCtx, REPORT_FILE, lastReport);
            Notifier.notifyResult(appCtx, false, "任务被手动取消：" + reason);
        }
        FireLog.w("任务已取消（3 秒冷却后可以重新发起）");
    }

    private void releaseScreenWakelock() {
        try {
            if (screenWakelock != null && screenWakelock.isHeld()) {
                screenWakelock.release();
            }
        } catch (Exception ignored) {
        }
    }

    /** 输出任务报告全文（含所有 dump 文件清单），供界面展示与分享。 */
    public static String buildPackageWithDumps(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("########## 任务报告 ##########\n");
        sb.append(lastReport == null ? "（没有执行记录）\n" : lastReport).append('\n');
        sb.append("\n########## 环境自检 ##########\n");
        sb.append(FireLog.text());
        return sb.toString();
    }
}
