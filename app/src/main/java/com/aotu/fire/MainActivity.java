package com.aotu.fire;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.0 主界面：配置 + 一键开启 + 手动执行 + 诊断。
 */
public class MainActivity extends Activity {

    private static final int REQ_FULL_DELAY_MS = 12_000;

    private Prefs prefs;
    private final Handler H = new Handler(Looper.getMainLooper());

    private TextView tvHeroSub;
    private TextView tvHeroPill;
    private TextView tvNextRun;
    private TextView tvHeroDetail;
    private TextView tvCheckSummary;
    private LinearLayout checkContainer;
    private TextView tvTimeline;
    private TextView tvTaskReport;
    private TextView tvReconResult;
    private TextView tvLog;
    // Switch 和 CheckBox 都是 CompoundButton 的子类，用父类型声明就不会因为换控件而崩
    private CompoundButton cbEnable;
    private EditText etTime;
    private EditText etNicknames;
    private EditText etPin;
    private EditText etMessages;
    private View diagContainer;
    private Button btnToggleDiag;

    /** 开关拨动 → 尝试保存；保存失败（如时间格式非法）时把开关拨回真实状态，避免界面与现实脱节 */
    private void setEnableListener() {
        cbEnable.setOnCheckedChangeListener((b, checked) -> {
            if (!saveConfig(false)) {
                cbEnable.setOnCheckedChangeListener(null);
                cbEnable.setChecked(new Prefs(this).enabled());
                setEnableListener();
            }
        });
    }

    /** 环境自检的文本快照（诊断包里要用，界面本身改成了图标+药丸） */
    private String selfCheckText = "";

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            refreshTimeline();
            refreshTaskReport();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FireLog.init(getApplicationContext());
        prefs = new Prefs(this);
        setContentView(R.layout.activity_main);

        tvHeroSub = findViewById(R.id.tvHeroSub);
        tvHeroPill = findViewById(R.id.tvHeroPill);
        tvNextRun = findViewById(R.id.tvNextRun);
        tvHeroDetail = findViewById(R.id.tvHeroDetail);
        tvCheckSummary = findViewById(R.id.tvCheckSummary);
        checkContainer = findViewById(R.id.checkContainer);
        tvTimeline = findViewById(R.id.tvTimeline);
        tvTaskReport = findViewById(R.id.tvTaskReport);
        tvReconResult = findViewById(R.id.tvReconResult);
        tvLog = findViewById(R.id.tvLog);
        cbEnable = findViewById(R.id.cbEnable);
        etTime = findViewById(R.id.etTime);
        etNicknames = findViewById(R.id.etNicknames);
        etPin = findViewById(R.id.etPin);
        etMessages = findViewById(R.id.etMessages);
        diagContainer = findViewById(R.id.diagContainer);
        btnToggleDiag = findViewById(R.id.btnToggleDiag);

        // 载入配置
        cbEnable.setChecked(prefs.enabled());
        etTime.setText(String.format(Locale.US, "%02d:%02d", prefs.hour(), prefs.minute()));
        etNicknames.setText(android.text.TextUtils.join("\n", prefs.targetFriends()));
        etPin.setText(prefs.pin());
        etMessages.setText(prefs.messages());

        // 环境自检的行是动态生成的（见 refreshSelfCheck），这里只需要挂上小米说明按钮
        findViewById(R.id.btnFixMiui).setOnClickListener(v -> showMiuiGuide());

        // 高级 / 诊断 折叠
        btnToggleDiag.setOnClickListener(v -> {
            boolean show = diagContainer.getVisibility() != View.VISIBLE;
            diagContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleDiag.setText(show ? "高级 / 诊断  ▴" : "高级 / 诊断  ▾");
        });

        // 配置
        findViewById(R.id.btnSaveConfig).setOnClickListener(v -> saveConfig(true));
        cbEnable.setOnCheckedChangeListener(null);
        setEnableListener();

        // 执行
        findViewById(R.id.btnRunDuoshan).setOnClickListener(v -> runDuoshanOnly());
        findViewById(R.id.btnRunFull).setOnClickListener(v -> runFullChain());
        findViewById(R.id.btnCancelTask).setOnClickListener(v -> {
            if (TaskRunner.get().isRunning()) {
                TaskRunner.get().cancel("用户手动取消");
                toast("已取消当前任务，3 秒后可重新发起");
            } else {
                toast("当前没有正在执行的任务");
            }
            refreshTaskReport();
        });
        findViewById(R.id.btnShareTask).setOnClickListener(v -> shareTaskPackage());

        // 诊断
        findViewById(R.id.btnTest1).setOnClickListener(v -> runWakeTest(WakeTest.MODE_FRONT, 4000));
        findViewById(R.id.btnTest2).setOnClickListener(v -> runWakeTest(WakeTest.MODE_BACK, 8000));
        findViewById(R.id.btnTest3).setOnClickListener(v -> runWakeTest(WakeTest.MODE_SCREEN_OFF, 15000));
        findViewById(R.id.btnClearTimeline).setOnClickListener(v -> {
            WakeTest.clearHistory();
            refreshTimeline();
            toast("已清空诊断记录");
        });
        findViewById(R.id.btnStopService).setOnClickListener(v -> {
            FireService.stop(this);
            Notifier.cancelWake(this);
            toast("已停止保活服务");
            refreshSelfCheck();
        });

        // 侦察
        findViewById(R.id.btnRecon).setOnClickListener(v -> onReconClicked());
        findViewById(R.id.btnShareRecon).setOnClickListener(v -> {
            String t = Recon.lastDump;
            if (t == null || t.isEmpty()) {
                toast("还没有采集结果");
                return;
            }
            Exporter.share(this, Recon.RECON_FILE, t);
        });

        // 日志
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyToClipboard("AotuFire 日志", FireLog.text()));
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            FireLog.clear();
            refreshLog();
        });

        // 每次打开都校正一次闹钟（防止被系统清掉）
        FireScheduler.schedule(this);
        FireLog.i("主界面已打开（v1.0）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        FireLog.addListener(refresher);
        refreshSelfCheck();
        refreshTimeline();
        refreshTaskReport();
        refreshRecon();
        refreshLog();
    }

    @Override
    protected void onPause() {
        FireLog.removeListener(refresher);
        super.onPause();
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** @return 时间是否合法 */
    private boolean saveConfig(boolean scheduleAlarm) {
        int[] hm = parseTime(etTime.getText().toString());
        if (hm == null) {
            toast("时间格式不对，请填 01:00 这样");
            return false;
        }
        Prefs p = new Prefs(this);
        p.setTime(hm[0], hm[1]);
        p.setEnabled(cbEnable.isChecked());
        p.setTargetFriends(etNicknames.getText().toString());
        p.setPin(etPin.getText().toString().trim());
        p.setSavePin(true);
        p.setMessages(etMessages.getText().toString());

        if (scheduleAlarm || cbEnable.isChecked()) {
            FireScheduler.schedule(this);
        } else {
            FireScheduler.cancel(this);
        }
        refreshSelfCheck();

        if (scheduleAlarm) {
            java.util.List<String> list = p.targetFriends();
            if (list.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("好友昵称是空的")
                        .setMessage("没有昵称就无法确认收件人 —— 程序宁可拒绝执行，也不会冒险猜。\n\n"
                                + "请在「好友昵称」里一行一个填上要续火花的好友名字。")
                        .setPositiveButton("我这就去填", null)
                        .show();
                etNicknames.requestFocus();
                return true;
            }
            if (cbEnable.isChecked()) {
                toast("已保存 " + list.size() + " 位好友。下次触发：" + FireScheduler.describe(this));
            } else {
                toast("已保存 " + list.size() + " 位好友。定时未开启");
            }
        }
        return true;
    }

    private static int[] parseTime(String raw) {        if (raw == null) {
            return null;
        }
        String t = raw.trim().replace('：', ':').replaceAll("[^0-9:]", "");
        try {
            int h;
            int m;
            if (t.contains(":")) {
                String[] parts = t.split(":");
                if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    return null;
                }
                h = Integer.parseInt(parts[0]);
                m = Integer.parseInt(parts[1]);
            } else if (t.length() == 4) {
                h = Integer.parseInt(t.substring(0, 2));
                m = Integer.parseInt(t.substring(2));
            } else if (!t.isEmpty() && t.length() <= 2) {
                h = Integer.parseInt(t);
                m = 0;
            } else {
                return null;
            }
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return new int[]{h, m};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private void runDuoshanOnly() {
        if (!ensureNickname()) {
            return;
        }
        if (!ensureNotBusy(this::runDuoshanOnly)) {
            return;
        }
        if (!guard()) {
            return;
        }
        if (!saveConfig(false)) {
            return;
        }
        toast("开始执行（跳过解锁）");
        TaskRunner.get().start(this, false);
    }

    private void runFullChain() {
        if (!ensureNickname()) {
            return;
        }
        if (!ensureNotBusy(this::runFullChain)) {
            return;
        }
        if (!guard()) {
            return;
        }
        if (!saveConfig(false)) {
            return;
        }
        FireService.start(this);
        WakeActivity.acquireWakelock(this);
        toast("12 秒后执行，请立刻按电源键息屏");
        tvTaskReport.setText("已排定完整链路测试：12 秒后自动亮屏解锁并执行。\n请立刻按电源键息屏，然后别碰手机。");
        H.postDelayed(() -> TaskRunner.get().start(this, true), REQ_FULL_DELAY_MS);
    }

    /**
     * 硬性闸门：没有好友昵称不允许执行。
     *
     * 这不是"建议"，是事故之后立的规矩 —— 没有昵称就无法确认收件人，
     * 程序只能盲点列表第一行，而会话顺序会随新消息变化，结果就是发给了别人。
     */
    private boolean ensureNickname() {
        // 只看输入框当前内容：prefs 里可能还留着旧名单，
        // 如果这里放行，随后的 saveConfig 会把空名单写进 prefs，已保存的名单就被静默清掉了
        if (!etNicknames.getText().toString().trim().isEmpty()) {
            return true;
        }
        new AlertDialog.Builder(this)
                .setTitle("必须先填好友昵称")
                .setMessage("没有昵称就无法确认收件人。\n\n"
                        + "之前正是因为昵称为空，程序退化成「盲点会话列表第一行」，"
                        + "而列表顺序会随新消息变化 —— 结果发给了别人。\n\n"
                        + "请一行一个填上要续火花的好友名字：程序只认这些名字，"
                        + "认不出的那一位会被跳过，绝不会拿别人顶替。")
                .setPositiveButton("我这就去填", null)
                .show();
        etNicknames.requestFocus();
        return false;
    }

    /**
     * 有任务还在跑时，不要静默拒绝（v1.0 就是这么干的，你连点两次全被吞掉，
     * 看起来就像功能没实现）。这里给用户一个明确的取消机会。
     */
    private boolean ensureNotBusy(Runnable thenRun) {
        if (!TaskRunner.get().isRunning()) {
            return true;
        }
        new AlertDialog.Builder(this)
                .setTitle("已有任务在执行")
                .setMessage("上一个任务还没结束（可能卡在某一步了）。\n\n"
                        + "取消它并重新开始？")
                .setPositiveButton("取消并重新开始", (d, w) -> {
                    TaskRunner.get().cancel("用户重新发起任务时取消");
                    toast("已取消上一个任务，3 秒后自动重新开始");
                    if (thenRun != null) {
                        H.postDelayed(thenRun, 3200);
                    }
                })
                .setNegativeButton("算了，继续等", null)
                .show();
        return false;
    }

    private boolean guard() {
        if (!isAccessibilityEnabled()) {
            toast("请先开启无障碍服务");
            openAccessibilitySettings();
            return false;
        }
        return true;
    }

    private void shareTaskPackage() {
        // 合成一个总诊断文件：任务报告 + 解锁面板报告 + 完整运行日志
        // （v1.0 只分享了报告和 dump，缺了解锁面板的日志，导致定位问题只能靠猜）
        StringBuilder sb = new StringBuilder();
        sb.append("########## 一、任务报告 ##########\n");
        sb.append(TaskRunner.lastReport == null ? "（无）\n" : TaskRunner.lastReport).append('\n');
        sb.append("\n########## 二、解锁面板报告 ##########\n");
        String wr = WakeActivity.lastReport;
        if (wr == null || wr.isEmpty()) {
            wr = WakeActivity.readReportFile(this);
        }
        sb.append(wr == null || wr.isEmpty() ? "（无）\n" : wr).append('\n');
        sb.append("\n########## 三、环境自检 ##########\n");
        sb.append(selfCheckText);
        sb.append("\n########## 四、完整运行日志 ##########\n");
        sb.append(FireLog.text());
        Exporter.write(this, "diagnostics.txt", sb.toString());

        List<String> names = new ArrayList<>(TaskRunner.lastDumps());
        names.add(TaskRunner.REPORT_FILE);
        names.add("diagnostics.txt");
        Exporter.shareMultiple(this, "AotuFire 执行诊断包", names);
    }

    private void refreshTaskReport() {
        String r = TaskRunner.lastReport;
        if (r != null && !r.isEmpty()) {
            tvTaskReport.setText(tail(r, 2500));
        }
    }

    // ------------------------------------------------------------------
    // ① 环境自检
    // ------------------------------------------------------------------

    /**
     * 环境自检：动态生成每一行（左边圆点药丸 + 名称 + 说明 + 一键跳设置）。
     * 同时拼一份纯文本快照，供诊断包分享用。
     */
    private void refreshSelfCheck() {
        java.util.List<String> names = prefs.targetFriends();
        boolean a11y = isAccessibilityEnabled();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notif = hasNotificationPermission();
        boolean fsi = Notifier.canUseFullScreenIntent(this);
        boolean battery = isIgnoringBatteryOptimizations();
        boolean exact = canScheduleExactAlarms();
        boolean fg = FireService.isRunning();
        boolean pinOk = !prefs.pin().isEmpty();
        boolean namesOk = !names.isEmpty();

        if (checkContainer == null) {
            return;
        }
        checkContainer.removeAllViews();
        StringBuilder sb = new StringBuilder();
        int total = 0;
        int ready = 0;

        // ---- 6 项权限：缺一不可 ----
        total++;
        ready += addCheck("无障碍服务", a11y, a11y ? "已开启" : "未开启 · 必须开启才能操作界面",
                true, v -> openAccessibilitySettings(), sb) ? 1 : 0;

        total++;
        ready += addCheck("悬浮窗", overlay, overlay ? "已允许 · 用于保持屏幕常亮"
                        : "未允许 · 屏幕 10 秒就会自动熄，流程会中途断掉",
                true, v -> openOverlaySettings(), sb) ? 1 : 0;

        total++;
        ready += addCheck("通知权限", notif, notif ? "已授予 · 能看到每天的执行结果"
                        : "未授予 · 看不到执行成功还是失败",
                true, v -> openNotificationSettings(), sb) ? 1 : 0;

        total++;
        ready += addCheck("全屏通知", fsi, fsi ? "可用 · 到点由系统点亮屏幕并唤起解锁"
                        : "不可用 · 到点可能唤不起解锁面板",
                true, v -> openFullScreenIntentSettings(), sb) ? 1 : 0;

        total++;
        ready += addCheck("忽略电池优化", battery, battery ? "已忽略 · 凌晨不会被系统冻结"
                        : "未忽略 · 凌晨可能被系统掐掉",
                true, v -> openBatterySettings(), sb) ? 1 : 0;

        total++;
        ready += addCheck("精确闹钟", exact, exact ? "已允许 · 到点准时执行"
                        : "未允许 · 执行时间可能被推迟几十分钟",
                true, v -> openExactAlarmSettings(), sb) ? 1 : 0;

        // ---- 2 项状态：不影响"就绪"计数 ----
        addCheck("前台服务", fg, fg ? "运行中 · 进程不易被冻结"
                        : "待命 · 到点由闹钟自动拉起（正常状态）",
                false, null, sb);

        total++;
        boolean configOk = namesOk && pinOk;
        String configStatus;
        if (!namesOk) {
            configStatus = "未填写好友名单 · 任务会拒绝执行（这是安全设计）";
        } else if (!pinOk) {
            configStatus = names.size() + " 位好友 · 但锁屏密码未保存，无法自动解锁";
        } else {
            configStatus = names.size() + " 位好友 · 锁屏密码已保存";
        }
        ready += addCheck("任务配置", configOk, configStatus, true, null, sb) ? 1 : 0;

        tvCheckSummary.setText(ready + " / " + total + " 项就绪");
        if (ready == total) {
            tvCheckSummary.setBackgroundResource(R.drawable.pill_ok);
            tvCheckSummary.setTextColor(getColor(R.color.ok));
        } else {
            tvCheckSummary.setBackgroundResource(R.drawable.pill_warn);
            tvCheckSummary.setTextColor(getColor(R.color.warn));
        }

        // ---- 头部状态 ----
        boolean enabled = cbEnable.isChecked();
        tvHeroPill.setText(enabled ? "● 运行中" : "○ 已停止");
        tvHeroPill.setBackgroundResource(enabled ? R.drawable.pill_hero_on : R.drawable.pill_hero_off);
        tvHeroSub.setText(enabled
                ? "每天 " + String.format(Locale.US, "%02d:%02d", prefs.hour(), prefs.minute())
                        + " 自动给 " + names.size() + " 位好友续火花"
                : "当前已停止 · 打开右上角开关即可启用");
        tvNextRun.setText(FireScheduler.describe(this));

        int msgCount = 0;
        for (String l : prefs.messages().split("\\n")) {
            if (!l.trim().isEmpty()) {
                msgCount++;
            }
        }
        tvHeroDetail.setText("目标好友 " + names.size() + " 位 · 文案池 " + msgCount + " 条 · "
                + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);

        // ---- 文本快照（诊断包用）----
        sb.append("下次触发：").append(FireScheduler.describe(this)).append('\n');
        sb.append("开关：").append(enabled ? "已开启" : "已关闭").append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）· ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        selfCheckText = sb.toString();
    }

    /** 生成一行自检项；返回该项是否"已就绪"。 */
    private boolean addCheck(String name, boolean ok, String status, boolean critical,
                             View.OnClickListener fix, StringBuilder sb) {
        View row = getLayoutInflater().inflate(R.layout.item_check, checkContainer, false);
        TextView dot = row.findViewById(R.id.tvDot);
        TextView tvName = row.findViewById(R.id.tvName);
        TextView tvStatus = row.findViewById(R.id.tvStatus);
        Button btnFix = row.findViewById(R.id.btnFix);

        dot.setText(ok ? "✓" : "!");
        dot.setBackgroundResource(ok ? R.drawable.pill_ok : R.drawable.pill_bad);
        dot.setTextColor(ok ? getColor(R.color.ok) : getColor(R.color.bad));
        tvName.setText(name);
        tvStatus.setText(status);

        if (fix != null) {
            btnFix.setVisibility(View.VISIBLE);
            btnFix.setOnClickListener(fix);
        } else {
            btnFix.setVisibility(View.GONE);
        }

        checkContainer.addView(row);
        sb.append(ok ? "[✓] " : "[✗] ").append(name).append("：").append(status).append('\n');
        return ok;
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            String pkg = getPackageName();
            return enabled.contains(FireAccessibilityService.class.getName())
                    || (enabled.contains(pkg) && enabled.contains("FireAccessibilityService"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canScheduleExactAlarms() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                return am != null && am.canScheduleExactAlarms();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // ④ 唤醒诊断
    // ------------------------------------------------------------------

    private void runWakeTest(int mode, long delayMs) {
        if (!guard()) {
            return;
        }
        String pin = etPin.getText().toString().trim();
        if (pin.length() < 4) {
            toast("请先输入锁屏密码");
            return;
        }
        prefs.setPin(pin);
        WakeTest.clearHistory();
        WakeTest.start(this, mode, delayMs, pin);

        String tip;
        switch (mode) {
            case WakeTest.MODE_FRONT:
                tip = "测试 1 已排定：4 秒后自动跑。";
                break;
            case WakeTest.MODE_BACK:
                tip = "测试 2 已排定：8 秒后从后台唤起，请按 Home 键回到桌面。";
                break;
            default:
                tip = "测试 3 已排定：15 秒后亮屏解锁，请立刻按电源键息屏。";
                break;
        }
        tvTimeline.setText(tip);
        toast(tip);
        if (mode == WakeTest.MODE_BACK) {
            moveTaskToBack(true);
        }
    }

    private void refreshTimeline() {
        String h = WakeTest.history();
        if (h != null && !h.isEmpty()) {
            tvTimeline.setText(tail(h, 2000));
        }
    }

    // ------------------------------------------------------------------
    // ⑤ 侦察
    // ------------------------------------------------------------------

    private void onReconClicked() {
        if (!guard()) {
            return;
        }
        Recon.cancel();
        Recon.lastDump = null;
        tvReconResult.setText("等待采集…（8 秒后自动采集，请立刻切到多闪）");
        Recon.capture(this, 8000);
        toast("8 秒后采集，请立刻切到多闪");
        moveTaskToBack(true);
    }

    private void refreshRecon() {
        String t = Recon.lastDump;
        if (t != null) {
            tvReconResult.setText(head(t, 2000));
        }
    }

    // ------------------------------------------------------------------
    // 系统设置跳转
    // ------------------------------------------------------------------

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("在列表里找到「自动续火花」并开启");
        } catch (Exception e) {
            toast("打不开无障碍设置：" + e);
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("打不开悬浮窗设置：" + e);
        }
    }

    private void openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
                return;
            }
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            toast("打不开通知设置：" + e);
        }
    }

    private void openFullScreenIntentSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                        Uri.parse("package:" + getPackageName())));
            } else {
                toast("当前系统版本无需单独授权全屏通知");
            }
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("打不开电池设置：" + e2);
            }
        }
    }

    private void openExactAlarmSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            toast("打不开精确闹钟设置：" + e);
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("打不开应用信息页：" + e);
        }
    }

    private void showMiuiGuide() {
        new AlertDialog.Builder(this)
                .setTitle("小米澎湃OS 必需权限")
                .setMessage("澎湃OS 默认禁止应用从后台弹出界面，会导致到点无法自动亮屏。\n\n"
                        + "设置 → 应用设置 → 应用管理 → 自动续火花 → 权限管理 → 其他权限 → 「后台弹出界面」→ 允许\n\n"
                        + "同一页顺手把这些也打开：\n"
                        + "· 自启动\n"
                        + "· 省电策略 → 无限制\n"
                        + "· 显示在其他应用上层（悬浮窗）\n\n"
                        + "提示：你上次的实测显示这三个测试已经全部通过，说明权限是正确的。")
                .setPositiveButton("打开应用信息页", (d, w) -> openAppDetails())
                .setNegativeButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private void refreshLog() {
        String[] lines = FireLog.text().split("\n");
        int from = Math.max(0, lines.length - 25);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        tvLog.setText(sb.length() == 0 ? "（无）" : sb.toString());
    }

    private static String head(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…（截断，用分享按钮拿全文）";
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : "…（前面省略）\n" + s.substring(s.length() - max);
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                toast("已复制（" + text.length() + " 字）");
            }
        } catch (Exception e) {
            toast("复制失败：" + e);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        FireLog.i("提示：" + msg);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshSelfCheck();
    }

    @Override
    protected void onDestroy() {
        H.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
