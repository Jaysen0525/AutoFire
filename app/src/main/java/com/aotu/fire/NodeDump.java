package com.aotu.fire;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 把无障碍节点树导出成人能读、我也能解析的文本。
 *
 * 输出分两段：
 *   1) 层级树   —— 看清嵌套关系，定位问题用
 *   2) 扁平清单 —— 所有「有文字或可点击」的节点，按屏幕从上到下排序，一眼看出按钮在哪
 *
 * 这份文本是 v0 的核心交付物：把它发给我，我就能写出精确的定位代码，
 * 而不是像盲人摸象一样猜坐标。
 */
public final class NodeDump {

    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 40;
    private static final int MAX_TEXT = 40;
    private static final int MAX_FLAT = 250;

    private NodeDump() {
    }

    public static String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "A11Y_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "SPLIT_DIVIDER";
            case AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY:
                return "MAGNIFY_OVERLAY";
            default:
                return "TYPE_" + type;
        }
    }

    /** 导出所有窗口（紧凑模式）。这是判断「锁屏能不能被无障碍看到」的关键手段。 */
    public static String dumpAllWindows(AccessibilityService svc) {
        return dumpAllWindows(svc, true);
    }

    /**
     * @param compact true = 折叠与业务无关的纯容器节点，并跳过未锁屏时的状态栏窗口，
     *                输出体积通常能减小一半以上，便于传输
     */
    public static String dumpAllWindows(AccessibilityService svc, boolean compact) {
        StringBuilder sb = new StringBuilder();
        boolean locked = Keyguard.isLocked(svc);
        sb.append("===== AotuFire 界面结构导出 =====\n");
        sb.append("模式: ").append(compact ? "紧凑（推荐）" : "完整").append('\n');
        sb.append("时间: ").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss",
                System.currentTimeMillis())).append('\n');
        sb.append("屏幕: ").append(svc.getResources().getDisplayMetrics().widthPixels)
                .append('x').append(svc.getResources().getDisplayMetrics().heightPixels).append('\n');
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
        sb.append("锁屏: ").append(locked ? "已锁定" : "未锁定").append('\n');

        List<AccessibilityWindowInfo> windows;
        try {
            windows = svc.getWindows();
        } catch (Exception e) {
            windows = null;
        }

        int totalNodes = 0;
        List<Flat> flat = new ArrayList<>();
        int[] counter = new int[]{0};

        if (windows == null || windows.isEmpty()) {
            sb.append("\n!!! getWindows() 返回空：无障碍服务未正确启用或系统限制了窗口访问。\n");
        } else {
            sb.append("\n共 ").append(windows.size()).append(" 个窗口\n");
            int idx = 0;
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Exception ignored) {
                }
                String pkg = root == null ? "" : safe(root.getPackageName());

                // 紧凑模式：没锁屏时状态栏那棵树又长又没业务价值，直接跳过
                if (compact && !locked && pkg.startsWith("com.android.systemui")) {
                    sb.append("\n----- 跳过系统状态栏窗口（紧凑模式）pkg=").append(pkg).append(" -----\n");
                    continue;
                }

                idx++;
                sb.append("\n----- 窗口 #").append(idx).append(" -----\n");
                sb.append("type=").append(windowTypeName(w.getType()))
                        .append(" active=").append(w.isActive())
                        .append(" focused=").append(w.isFocused())
                        .append(" layer=").append(w.getLayer())
                        .append(" pkg=").append(pkg)
                        .append(" title=").append(safe(w.getTitle()))
                        .append('\n');
                if (root == null) {
                    sb.append("  (拿不到根节点 —— 这个窗口的内容对无障碍不可见)\n");
                    continue;
                }
                totalNodes += walk(root, 0, sb, flat, counter, compact);
                if (counter[0] > MAX_NODES) {
                    sb.append("  ... 节点数超过 ").append(MAX_NODES).append("，已截断\n");
                    break;
                }
            }
        }

        // 焦点窗口兜底：有些 ROM 的 getWindows() 拿不到活动窗口
        if (totalNodes == 0) {
            sb.append("\n----- 兜底：getRootInActiveWindow() -----\n");
            AccessibilityNodeInfo root = null;
            try {
                root = svc.getRootInActiveWindow();
            } catch (Exception ignored) {
            }
            if (root == null) {
                sb.append("(同样为空)\n");
            } else {
                sb.append("pkg=").append(safe(root.getPackageName())).append('\n');
                walk(root, 0, sb, flat, counter, compact);
            }
        }

        // 扁平清单
        sb.append("\n===== 扁平清单（有文字 / 有 id / 可点击 / 可编辑，按屏幕位置排序）=====\n");
        Collections.sort(flat, new Comparator<Flat>() {
            @Override
            public int compare(Flat a, Flat b) {
                if (a.top != b.top) {
                    return Integer.compare(a.top, b.top);
                }
                return Integer.compare(a.left, b.left);
            }
        });
        int shown = 0;
        for (Flat f : flat) {
            if (shown++ >= MAX_FLAT) {
                sb.append("... 还有 ").append(flat.size() - MAX_FLAT).append(" 条，已截断\n");
                break;
            }
            sb.append(f.line).append('\n');
        }
        if (flat.isEmpty()) {
            sb.append("(没有任何有文字或可点击的节点 —— 说明这个界面对无障碍基本不可见)\n");
        }
        sb.append("\n===== 导出结束，节点总数 ").append(totalNodes).append(" =====\n");
        return sb.toString();
    }

    private static int walk(AccessibilityNodeInfo node, int depth, StringBuilder sb,
                            List<Flat> flat, int[] counter, boolean compact) {
        if (node == null || depth > MAX_DEPTH) {
            return 0;
        }
        counter[0]++;
        if (counter[0] > MAX_NODES) {
            return 0;
        }

        Rect r = new Rect();
        node.getBoundsInScreen(r);

        String cls = shortClass(node.getClassName());
        String text = trim(node.getText());
        String desc = trim(node.getContentDescription());
        String vid = shortId(node.getViewIdResourceName());

        // 有文字 / 有描述 / 有 id / 可交互 —— 才算「有信息量」的节点。
        // 紧凑模式下，纯布局容器不输出，其子节点提升到当前层级，避免输出被无用的容器撑爆。
        boolean interesting = !text.isEmpty() || !desc.isEmpty() || !vid.isEmpty()
                || node.isClickable() || node.isEditable() || node.isScrollable();

        if (compact && !interesting) {
            int count0 = 1;
            int n0 = node.getChildCount();
            for (int i = 0; i < n0; i++) {
                AccessibilityNodeInfo c0 = null;
                try {
                    c0 = node.getChild(i);
                } catch (Exception ignored) {
                }
                if (c0 != null) {
                    count0 += walk(c0, depth, sb, flat, counter, compact);
                }
            }
            return count0;
        }

        StringBuilder line = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            line.append("  ");
        }
        line.append('L').append(depth).append(' ').append(cls);
        if (!vid.isEmpty()) {
            line.append(" vid=").append(vid);
        }
        if (!text.isEmpty()) {
            line.append(" T=\"").append(text).append('"');
        }
        if (!desc.isEmpty()) {
            line.append(" D=\"").append(desc).append('"');
        }
        line.append(" [").append(r.left).append(',').append(r.top).append("][")
                .append(r.right).append(',').append(r.bottom).append(']');
        if (node.isClickable()) {
            line.append(" c");
        }
        if (node.isEditable()) {
            line.append(" e");
        }
        if (node.isScrollable()) {
            line.append(" s");
        }
        if (node.isFocusable()) {
            line.append(" f");
        }
        if (node.isCheckable() && node.isChecked()) {
            line.append(" *");
        }
        if (!node.isEnabled()) {
            line.append(" x");
        }
        sb.append(line).append('\n');

        if (!text.isEmpty() || !desc.isEmpty() || !vid.isEmpty()
                || node.isClickable() || node.isEditable()) {
            flat.add(new Flat(r.top, r.left,
                    "[y=" + r.top + " x=" + r.left + " " + (r.right - r.left) + "x" + (r.bottom - r.top) + "] "
                            + cls
                            + (vid.isEmpty() ? "" : " vid=" + vid)
                            + (text.isEmpty() ? "" : " T=\"" + text + "\"")
                            + (desc.isEmpty() ? "" : " D=\"" + desc + "\"")
                            + (node.isClickable() ? " 可点" : "")
                            + (node.isEditable() ? " 可输入" : "")));
        }

        int count = 1;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
            }
            if (child == null) {
                continue;
            }
            count += walk(child, depth + 1, sb, flat, counter, compact);
        }
        return count;
    }

    /** 递归收集所有节点，供业务逻辑查找用（带数量上限，防止卡死）。 */
    public static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int[] counter) {
        if (node == null || counter[0] > MAX_NODES) {
            return;
        }
        counter[0]++;
        out.add(node);
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception ignored) {
            }
            collect(c, out, counter);
        }
    }

    public static List<AccessibilityNodeInfo> collectAll(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out, new int[]{0});
        return out;
    }

    static String shortClass(CharSequence cs) {
        if (cs == null) {
            return "?";
        }
        String s = cs.toString();
        int i = s.lastIndexOf('.');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.replace("com.duoshan:", "").replace("com.android.systemui:", "sys:");
    }

    static String trim(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        String s = cs.toString().replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > MAX_TEXT) {
            s = s.substring(0, MAX_TEXT) + "…";
        }
        return s;
    }

    static String safe(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static final class Flat {
        final int top;
        final int left;
        final String line;

        Flat(int top, int left, String line) {
            this.top = top;
            this.left = left;
            this.line = line;
        }
    }
}
