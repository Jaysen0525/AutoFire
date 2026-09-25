package com.aotu.fire;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 无障碍服务：v0 阶段负责「看见」界面（导出节点树）与「尝试解锁」；
 * v1 阶段在这里接入打开多闪、找好友、发消息的完整流程。
 */
public class FireAccessibilityService extends AccessibilityService {

    public static final String PKG_SYSTEMUI = "com.android.systemui";

    private static volatile FireAccessibilityService sInstance;

    public static FireAccessibilityService get() {
        return sInstance;
    }

    public static boolean ready() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        FireLog.init(getApplicationContext());
        FireLog.i("无障碍服务已连接（v0）");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        FireLog.w("无障碍服务已断开");
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // v0 不处理事件；v1 会在这里实现「等待界面加载完成」
    }

    @Override
    public void onInterrupt() {
        FireLog.w("无障碍服务被中断");
    }

    // ------------------------------------------------------------------
    // 界面导出
    // ------------------------------------------------------------------

    public String dumpAllWindows() {
        return NodeDump.dumpAllWindows(this, true);
    }

    public String dumpAllWindows(boolean compact) {
        return NodeDump.dumpAllWindows(this, compact);
    }

    // ------------------------------------------------------------------
    // 节点收集
    // ------------------------------------------------------------------

    /** 收集所有窗口里的节点。 */
    public List<AccessibilityNodeInfo> allNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        int[] counter = new int[]{0};
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Exception ignored) {
        }
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Exception ignored) {
                }
                if (root != null) {
                    NodeDump.collect(root, out, counter);
                }
            }
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
            } catch (Exception ignored) {
            }
            if (root != null) {
                NodeDump.collect(root, out, counter);
            }
        }
        return out;
    }

    /** 只收集系统界面（锁屏、通知栏等）的节点 —— 锁屏数字键盘就在这里。 */
    public List<AccessibilityNodeInfo> systemUiNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        int[] counter = new int[]{0};
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Exception ignored) {
        }
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Exception ignored) {
                }
                if (root == null) {
                    continue;
                }
                CharSequence pkg = root.getPackageName();
                if (pkg != null && PKG_SYSTEMUI.contentEquals(pkg)) {
                    NodeDump.collect(root, out, counter);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /** 按屏幕坐标点一下（dispatchGesture，免 root）。 */
    public boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 60);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gd, null, null);
        } catch (Exception e) {
            FireLog.e("tap 失败 (" + x + "," + y + ")", e);
            return false;
        }
    }

    /** 滑动（用于上滑唤起锁屏密码键盘）。 */
    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, Math.max(60, durationMs));
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gd, null, null);
        } catch (Exception e) {
            FireLog.e("swipe 失败", e);
            return false;
        }
    }

    /** 向上找最近的可点击祖先；找不到就返回原节点。 */
    public static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return n;
            }
        }
        return n;
    }

    /** 点击一个节点：优先 ACTION_CLICK，失败则退化为按坐标点。 */
    public boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        AccessibilityNodeInfo target = clickableAncestor(n);
        if (target != null && target.isClickable() && target.isEnabled()) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tap(r.centerX(), r.centerY());
        }
        return false;
    }

    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** 锁屏（任务收尾时恢复原状）。API 28+ */
    public void lockScreen() {
        try {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            FireLog.i("已锁屏");
        } catch (Exception e) {
            FireLog.e("锁屏失败", e);
        }
    }

    /**
     * 启动应用，并**强制归零到它的根页面**。
     *
     * 为什么必须加 FLAG_ACTIVITY_CLEAR_TOP：
     * 一个已在运行的 App，用启动意图拉起时 Android 默认是「恢复上次的界面」——
     * 比如昨天停在某个聊天页，今天拉起还是那个聊天页，而不是首页。
     * CLEAR_TOP 会把根 Activity 之上的所有页面结束掉，把栈顶拉回根页面。
     */
    public boolean openApp(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                FireLog.w("没有找到应用：" + pkg);
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            FireLog.i("已启动应用（归零到根页面）：" + pkg);
            return true;
        } catch (Exception e) {
            FireLog.e("启动应用失败：" + pkg, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 锁屏数字键盘
    // ------------------------------------------------------------------

    /**
     * 逐个点击锁屏数字键盘输入密码。
     *
     * 两条路并行：
     *   · 首选 —— 无障碍节点点击（准确）
     *   · 兜底 —— 从已找到的任意一个数字键反推出整个九宫格的几何，按坐标点击
     *     （实测本机布局：1 2 3 / 4 5 6 / 7 8 9 / _ 0 _，键宽 284、键高 185）
     *
     * @return 成功点击的数字个数
     */
    public int typePin(String pin) {
        if (pin == null || pin.isEmpty()) {
            FireLog.w("typePin: 密码为空");
            return 0;
        }
        Map<Character, AccessibilityNodeInfo> exact = collectDigitKeys(true);
        Map<Character, AccessibilityNodeInfo> keys = exact.isEmpty() ? collectDigitKeys(false) : exact;
        if (exact.isEmpty() && !keys.isEmpty()) {
            FireLog.w("严格匹配没找到数字键，退化为宽松匹配（共 " + keys.size() + " 个）");
        }
        int[] grid = learnGrid(keys);
        FireLog.i("数字键盘：找到 " + keys.size() + " 个键"
                + (grid == null ? "，几何反推不可用（将只靠节点点击）"
                : "，几何反推成功 x0=" + grid[0] + " y0=" + grid[1]
                        + " w=" + grid[2] + " h=" + grid[3]));

        int ok = 0;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            boolean clicked = false;
            String how;

            AccessibilityNodeInfo key = keys.get(c);
            if (key != null) {
                Rect r = new Rect();
                key.getBoundsInScreen(r);
                clicked = clickNode(key);
                how = "节点" + (clicked ? "" : "(失败)") + " bounds=" + r.toShortString();
            } else if (grid != null) {
                int[] xy = keyCenter(grid, c);
                clicked = tap(xy[0], xy[1]);
                how = "坐标兜底(" + xy[0] + "," + xy[1] + ")";
            } else {
                FireLog.w("第 " + (i + 1) + " 位数字「" + c + "」既找不到节点也无法反推坐标");
                break;
            }
            FireLog.i("点数字「" + c + "」(第 " + (i + 1) + " 位) -> "
                    + (clicked ? "已点击" : "点击失败") + " via " + how);
            if (clicked) {
                ok++;
            }
            sleep(240);
        }
        return ok;
    }

    /**
     * 收集当前锁屏上能识别到的数字键。
     *
     * @param strict true = 只认「文字或描述恰好就是单个数字」的节点（最可靠）；
     *               false = 放宽到「描述里含数字」的短文本（兜底）
     */
    public Map<Character, AccessibilityNodeInfo> collectDigitKeys(boolean strict) {
        Map<Character, AccessibilityNodeInfo> out = new HashMap<>();
        List<AccessibilityNodeInfo> pool = systemUiNodes();
        if (pool.isEmpty()) {
            pool = allNodes();
        }
        for (AccessibilityNodeInfo n : pool) {
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            boolean small = r.width() > 0 && r.height() > 0 && r.width() < 400 && r.height() < 400;
            boolean square = r.height() > 0 && (double) r.width() / r.height() > 0.8
                    && (double) r.width() / r.height() < 3.0;
            boolean hasClickable;
            try {
                hasClickable = n.isClickable()
                        || (n.getParent() != null && n.getParent().isClickable());
            } catch (Exception e) {
                hasClickable = n.isClickable();
            }
            if (!small || !square || !hasClickable) {
                continue;
            }
            String t = NodeDump.trim(n.getText());
            String d = NodeDump.trim(n.getContentDescription());
            Character digit = strict ? exactDigit(t, d) : extractDigit(t, d);
            if (digit != null && !out.containsKey(digit)) {
                out.put(digit, n);
            }
        }
        return out;
    }

    /** 严格识别：文字或描述就是单个数字。 */
    private Character exactDigit(String text, String desc) {
        if (text.length() == 1 && Character.isDigit(text.charAt(0))) {
            return text.charAt(0);
        }
        if (desc.length() == 1 && Character.isDigit(desc.charAt(0))) {
            return desc.charAt(0);
        }
        return null;
    }

    /** 从节点的文字/描述里识别出单个数字。严格优先，宽松兜底。 */
    private Character extractDigit(String text, String desc) {
        if (text.length() == 1 && Character.isDigit(text.charAt(0))) {
            return text.charAt(0);
        }
        if (desc.length() == 1 && Character.isDigit(desc.charAt(0))) {
            return desc.charAt(0);
        }
        for (String s : new String[]{desc, text}) {
            if (s.isEmpty() || s.length() > 12) {
                continue;
            }
            // 「数字 5」「5，数字 5」这类描述
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d").matcher(s);
            if (m.find()) {
                return s.charAt(m.start());
            }
        }
        return null;
    }

    /** 从任意一个已找到的数字键反推九宫格原点与键尺寸。 */
    private int[] learnGrid(Map<Character, AccessibilityNodeInfo> keys) {
        for (Map.Entry<Character, AccessibilityNodeInfo> e : keys.entrySet()) {
            char d = e.getKey();
            Rect r = new Rect();
            try {
                e.getValue().getBoundsInScreen(r);
            } catch (Exception ex) {
                continue;
            }
            if (r.width() <= 0 || r.height() <= 0) {
                continue;
            }
            int col = (d == '0') ? 1 : ((d - '0') - 1) % 3;
            int row = (d == '0') ? 3 : ((d - '0') - 1) / 3;
            return new int[]{r.left - col * r.width(), r.top - row * r.height(),
                    r.width(), r.height()};
        }
        return null;
    }

    /** 按九宫格几何算出某个数字键的中心坐标。 */
    private int[] keyCenter(int[] grid, char digit) {
        int col = (digit == '0') ? 1 : ((digit - '0') - 1) % 3;
        int row = (digit == '0') ? 3 : ((digit - '0') - 1) / 3;
        int cx = grid[0] + col * grid[2] + grid[2] / 2;
        int cy = grid[1] + row * grid[3] + grid[3] / 2;
        return new int[]{cx, cy};
    }

    /**
     * 找锁屏上的某个数字键。
     * 严格匹配优先（文字/描述完全等于该数字），宽松匹配兜底（描述里含该数字）。
     * 只考虑「面积不大且可点击」的节点，避免把时间显示里的数字误当成按键。
     */
    public AccessibilityNodeInfo findDigitKey(char c) {
        String d = String.valueOf(c);
        List<AccessibilityNodeInfo> pool = systemUiNodes();
        if (pool.isEmpty()) {
            FireLog.w("systemUiNodes 为空，退回全量节点搜索");
            pool = allNodes();
        }
        AccessibilityNodeInfo loose = null;
        for (AccessibilityNodeInfo n : pool) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            boolean small = r.width() > 0 && r.height() > 0 && r.width() < 400 && r.height() < 400;
            boolean hasClickable = n.isClickable()
                    || (n.getParent() != null && n.getParent().isClickable());
            if (!small || !hasClickable) {
                continue;
            }
            String t = NodeDump.trim(n.getText());
            String de = NodeDump.trim(n.getContentDescription());
            if (t.equals(d) || de.equals(d)) {
                return n;
            }
            if (loose == null && (de.contains(d) || t.contains(d))) {
                loose = n;
            }
        }
        return loose;
    }

    /** 为诊断输出：把所有「疑似数字键」的候选列出来，便于判断键盘到底长什么样。 */
    public String describeDigitCandidates() {
        StringBuilder sb = new StringBuilder();
        List<AccessibilityNodeInfo> pool = systemUiNodes();
        sb.append("系统界面节点数: ").append(pool.size()).append('\n');
        if (pool.isEmpty()) {
            pool = allNodes();
            sb.append("退回全量节点数: ").append(pool.size()).append('\n');
        }
        int shown = 0;
        for (AccessibilityNodeInfo n : pool) {
            String t = NodeDump.trim(n.getText());
            String de = NodeDump.trim(n.getContentDescription());
            if (t.isEmpty() && de.isEmpty()) {
                continue;
            }
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            sb.append(NodeDump.shortClass(n.getClassName()))
                    .append(" T=\"").append(t).append('"')
                    .append(" D=\"").append(de).append('"')
                    .append(" [").append(r.toShortString()).append(']')
                    .append(n.isClickable() ? " 可点" : "")
                    .append('\n');
            if (++shown >= 120) {
                sb.append("... 已截断\n");
                break;
            }
        }
        if (shown == 0) {
            sb.append("(系统界面里没有任何带文字或描述的节点 —— 锁屏对无障碍不可见)\n");
        }
        return sb.toString();
    }

    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
