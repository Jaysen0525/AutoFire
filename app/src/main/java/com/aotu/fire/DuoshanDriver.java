package com.aotu.fire;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 多闪（包名 my.maya.android）的界面操作。
 *
 * 设计原则：**用「结构 + 文字」，不用控件 id** ——
 * 多闪的 id 是混淆过的（e / kd / e5u / msa / o6y / n3x …），每次发版都会变。
 * 但下面这些是稳定的：
 *
 *   · 会话列表 = 一个 RecyclerView，每一行是一个 Button
 *   · 每个会话行里，昵称藏在头像节点的 contentDescription 里（不是 text！）
 *   · 顶栏标题的内容描述是「消息」，可用来判定「当前在消息列表页」
 *   · 底部三个 tab 的内容描述是「多闪，按钮」「发现，按钮」「我，按钮」
 *
 * 目标好友已置顶 → 它就在 RecyclerView 的第一行。
 */
public final class DuoshanDriver {

    public static final String PKG = "my.maya.android";

    /** 顶栏标题的内容描述：处在消息列表页的标志 */
    private static final String MARK_MESSAGE_PAGE = "消息";

    /** 底部第一个 tab：回到消息列表 */
    private static final String MARK_HOME_TAB = "多闪";

    /** 开屏弹窗上常见的「关掉它」按钮文案 */
    private static final String[] POPUP_TEXTS = {
            "跳过", "关闭", "以后再说", "我知道了", "知道啦", "暂不", "稍后", "取消", "不再提示",
            "下次再说", "忽略"
    };

    /** 这些描述不是好友昵称，提取昵称时要排除 */
    private static final String[] NOT_NICKNAME = {
            "在线", "续火花", "互动通知", "更多面板", "已读 ·", "打开通知", "点亮中", "重燃中"
    };

    private DuoshanDriver() {
    }

    // ------------------------------------------------------------------
    // 基础
    // ------------------------------------------------------------------

    public static String foregroundPackage(FireAccessibilityService svc) {
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) {
                return "";
            }
            CharSequence p = root.getPackageName();
            return p == null ? "" : p.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isDuoshanForeground(FireAccessibilityService svc) {
        return PKG.equals(foregroundPackage(svc));
    }

    private static boolean isDuoshan(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p != null && PKG.contentEquals(p);
    }

    static boolean hasClickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) {
                return true;
            }
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        String t = NodeDump.trim(n.getText());
        if (t.isEmpty()) {
            t = NodeDump.trim(n.getContentDescription());
        }
        return t;
    }

    private static String descOf(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        CharSequence d = n.getContentDescription();
        return d == null ? "" : d.toString().trim();
    }

    /** 昵称归一化：去掉空格和零宽字符，便于比对。 */
    public static String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(" ", "").replace("\u200b", "").replace("\u00a0", "").trim();
    }

    /**
     * 昵称匹配 —— **保守优先，宁可不匹配也不能错配**。
     *
     * 为什么不能用双向 contains：如果填的昵称是「姜」，双向 contains 会把
     * 「姜纭晞」「小姜」「姜饼人」全部命中，第一行匹配到谁就给谁发消息。
     * 发错人是不可逆的社交事故，所以这里只接受三种情况：
     *   1. 完全相等
     *   2. 行里的名字包含你填的昵称，且你填的长度 ≥ 3
     *   3. 你填的昵称包含行里的名字，且行里的名字长度 ≥ 4（界面把名字截断的情况）
     */
    public static boolean nicknameMatches(String rowName, String want) {
        String a = normalizeName(rowName);
        String b = normalizeName(want);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        if (b.length() >= 3 && a.toLowerCase(java.util.Locale.ROOT)
                .contains(b.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        return a.length() >= 4 && b.toLowerCase(java.util.Locale.ROOT)
                .contains(a.toLowerCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // 页面判定
    // ------------------------------------------------------------------

    /**
     * 是否停在「消息列表页」（顶栏标题的内容描述是「消息」）。
     *
     * 必须先排除聊天页：实测聊天页打开后，**会话列表连同顶栏标题都还留在同一棵节点树里**，
     * 只看「标题是消息」会把聊天页误判成列表页（多好友连续发送时这个判断会被反复调用，
     * 一旦误判就会在聊天页里找会话行，直接跑偏）。
     */
    public static boolean isMessagePage(FireAccessibilityService svc) {
        if (isChatPage(svc)) {
            return false;
        }
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            if (MARK_MESSAGE_PAGE.equals(descOf(n))) {
                return true;
            }
        }
        return false;
    }

    /** 是否停在某个聊天窗口（有输入框即视为在聊天页）。 */
    public static boolean isChatPage(FireAccessibilityService svc) {
        return findInputBox(svc) != null;
    }

    /** 底部第一个 tab（「多闪，按钮」）—— 点它回到消息列表。 */
    public static AccessibilityNodeInfo findHomeTab(FireAccessibilityService svc, int screenH) {
        AccessibilityNodeInfo loose = null;
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            if (r.top < screenH * 0.82) {
                continue;   // 只找底部区域
            }
            String d = descOf(n);
            String t = NodeDump.trim(n.getText());
            if (d.contains(MARK_HOME_TAB) && d.contains("按钮")) {
                return n;
            }
            if (MARK_HOME_TAB.equals(t) && (n.isClickable() || hasClickableAncestor(n))) {
                loose = n;
            }
        }
        return loose;
    }

    // ------------------------------------------------------------------
    // 弹窗 / 横幅
    // ------------------------------------------------------------------

    /** 找开屏广告、活动弹窗、通知横幅上的关闭按钮。 */
    public static AccessibilityNodeInfo findPopupButton(FireAccessibilityService svc) {
        List<AccessibilityNodeInfo> all = svc.allNodes();

        // 1) 明确叫 close 的按钮（多闪的通知横幅就是 vid=...:id/iv_close）
        for (AccessibilityNodeInfo n : all) {
            if (!isDuoshan(n)) {
                continue;
            }
            String vid = n.getViewIdResourceName();
            if (vid != null && vid.endsWith("iv_close") && n.isClickable()) {
                return n;
            }
        }

        // 2) 文案匹配
        for (String want : POPUP_TEXTS) {
            for (AccessibilityNodeInfo n : all) {
                if (!isDuoshan(n)) {
                    continue;
                }
                String t = textOf(n);
                if (t.isEmpty()) {
                    continue;
                }
                if (t.equals(want) || (t.length() <= want.length() + 3 && t.contains(want))) {
                    if (n.isClickable() || hasClickableAncestor(n)) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 会话列表（核心）
    // ------------------------------------------------------------------

    /** 找到会话列表的 RecyclerView 节点。 */
    public static AccessibilityNodeInfo findConversationRecycler(FireAccessibilityService svc) {
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            if ("RecyclerView".equals(NodeDump.shortClass(n.getClassName()))) {
                return n;
            }
        }
        return null;
    }

    /**
     * 取出会话列表里的每一行。
     * 结构实测：RecyclerView 下每行是一个 Button（行内嵌套的按钮不会再被当成行）。
     */
    public static List<AccessibilityNodeInfo> conversationRows(FireAccessibilityService svc) {
        List<AccessibilityNodeInfo> rows = new ArrayList<>();
        AccessibilityNodeInfo recycler = findConversationRecycler(svc);
        if (recycler == null) {
            return rows;
        }
        collectRows(recycler, rows, 0);
        Collections.sort(rows, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect();
                Rect rb = new Rect();
                try {
                    a.getBoundsInScreen(ra);
                    b.getBoundsInScreen(rb);
                } catch (Exception e) {
                    return 0;
                }
                return Integer.compare(ra.top, rb.top);
            }
        });
        return rows;
    }

    private static void collectRows(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 4) {
            return;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception ignored) {
            }
            if (c == null) {
                continue;
            }
            if ("Button".equals(NodeDump.shortClass(c.getClassName()))) {
                out.add(c);
                continue;   // 不再往下钻，避免把行内的「续火花」按钮也当成一行
            }
            collectRows(c, out, depth + 1);
        }
    }

    /**
     * 从一个会话行里提取好友昵称。
     *
     * ★ 关键：**只看子节点，排除行节点自身** ★
     *
     * 多闪也给会话行本身设了 contentDescription，内容是整行摘要：
     *     "置顶,好友甲,未读2条消息,[杀马特]刚刚朋友在线,"
     *     "好友戊,消息预览14:20"
     * 它的面积（1080×184）远大于头像节点（144×144），用「面积最大」会把整行摘要选中 ——
     * 那玩意儿包含**消息预览**，拿它做匹配会有认错人的风险（预览里提到某人名字就会命中）。
     *
     * 真正的昵称在头像节点的描述里，是干净的一个名字。
     * 兜底顺序：面积最大的子节点描述 → 最长的子节点描述 → 最长的子节点文字。
     */
    public static String rowNickname(AccessibilityNodeInfo row) {
        String bestByArea = "";
        int bestArea = 0;
        String bestByLen = "";

        List<AccessibilityNodeInfo> nodes = NodeDump.collectAll(row);
        for (int i = 1; i < nodes.size(); i++) {   // 跳过第 0 个 = 行节点自身
            AccessibilityNodeInfo n = nodes.get(i);
            String d = descOf(n);
            if (!d.isEmpty() && !isNotNickname(d)) {
                if (d.length() > bestByLen.length()) {
                    bestByLen = d;
                }
                Rect r = new Rect();
                try {
                    n.getBoundsInScreen(r);
                } catch (Exception e) {
                    continue;
                }
                int area = Math.max(0, r.width()) * Math.max(0, r.height());
                if (area > bestArea) {
                    bestArea = area;
                    bestByArea = d;
                }
            }
        }
        if (!bestByArea.isEmpty()) {
            return bestByArea;
        }
        if (!bestByLen.isEmpty()) {
            return bestByLen;
        }
        // 兜底 3：子节点文字里最长的（有些版本昵称放在 text 上）
        String bestText = "";
        for (int i = 1; i < nodes.size(); i++) {
            String t = NodeDump.trim(nodes.get(i).getText());
            if (t.isEmpty() || isNotNickname(t) || t.length() > 20) {
                continue;
            }
            // 纯数字是未读红点数（"1"、"12"），不是昵称
            if (t.matches("\\d+")) {
                continue;
            }
            if (t.length() > bestText.length()) {
                bestText = t;
            }
        }
        return bestText;
    }

    /** 会话行自身的整行摘要（"置顶,名字,预览文字时间状态,"），仅用于兜底匹配。 */
    public static String rowSummary(AccessibilityNodeInfo row) {
        return descOf(row);
    }

    /**
     * 兜底匹配：拿整行摘要的前两个字段做**完全相等**比对。
     *
     * 摘要格式是 `[置顶|免打扰,]名字,预览文字时间状态,` —— 名字永远在前两个字段里。
     * 用「完全相等」而不是「包含」，就是为了避免预览文字里提到某人名字时被误命中。
     */
    public static boolean summaryMatchesTarget(AccessibilityNodeInfo row, String want) {
        String desc = rowSummary(row);
        String w = normalizeName(want);
        if (desc.isEmpty() || w.isEmpty()) {
            return false;
        }
        String[] fields = desc.split("[,，]");
        int limit = Math.min(2, fields.length);
        for (int i = 0; i < limit; i++) {
            if (normalizeName(fields[i]).equalsIgnoreCase(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNotNickname(String s) {
        for (String bad : NOT_NICKNAME) {
            if (s.equals(bad) || s.startsWith(bad)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验「当前聊天窗口里的确是这个好友」—— 发送前的最后一道闸门。
     *
     * 为什么不能简单地"全树搜昵称"：聊天页打开后，**会话列表还在同一棵节点树里**
     * （实测：`Button D="置顶,好友甲,[草稿] ..."` 就在聊天页的树中）。
     * 全树搜的话，在列表页也会命中，这道闸门等于形同虚设。
     *
     * 可靠的判据（实测聊天页结构）：
     *   · 聊天页顶栏的好友昵称是 **TextView 的 text**，位置在屏幕顶部 ≈8% 处
     *     （实测 id/o9e T="好友甲" 位于 y=118）
     *   · 而会话列表里的昵称是 **contentDescription**，且位置在 y≥221
     * 所以这里只认「屏幕顶部区域 + text 字段」，两者兼备。
     */
    public static boolean currentChatLooksLike(FireAccessibilityService svc, String nickname, int screenH) {
        String want = normalizeName(nickname);
        if (want.isEmpty()) {
            return false;
        }
        int topLimit = (int) (screenH * 0.10);
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            String t = NodeDump.trim(n.getText());
            if (t.isEmpty() || !nicknameMatches(t, want)) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            // 必须真的可见（被滚出屏幕的节点高度会是负数）
            if (r.height() <= 0 || r.width() <= 0) {
                continue;
            }
            if (r.top < 0 || r.top > topLimit) {
                continue;
            }
            // ★ 关键判据：聊天页的标题节点是**可聚焦**的，消息气泡和未读红点都不是。
            //   光靠位置切不干净 —— 消息列表一直铺到 y=0，气泡既可能出现在标题上方
            //   （实测 y=4）也可能在下方（实测 y=185），位置阈值怎么调都会有漏网的。
            if (!n.isFocusable()) {
                continue;
            }
            FireLog.d("收件人校验命中: \"" + t + "\" bounds=" + r.toShortString());
            return true;
        }
        return false;
    }

    /** 顶栏区域里出现的所有文本（校验失败时打印出来，方便判断判据该怎么调）。 */
    public static String describeTopTexts(FireAccessibilityService svc, int screenH) {
        int topLimit = (int) (screenH * 0.10);
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            String t = NodeDump.trim(n.getText());
            if (t.isEmpty() || t.length() > 30) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            if (r.top < 0 || r.top > topLimit || r.height() <= 0) {
                continue;
            }
            sb.append("    T=\"").append(t).append("\" bounds=").append(r.toShortString())
                    .append(n.isFocusable() ? " 可聚焦" : "")
                    .append('\n');
        }
        return sb.length() == 0 ? "    （顶栏区域没有文本节点）\n" : sb.toString();
    }

    /** 读出聊天页顶栏的好友昵称（用于日志与诊断）。 */
    public static String chatTitle(FireAccessibilityService svc, int screenH) {
        int topLimit = (int) (screenH * 0.10);
        String best = "";
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            String t = NodeDump.trim(n.getText());
            if (t.isEmpty() || t.length() > 20 || isNotNickname(t)) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            if (r.height() <= 0 || r.width() <= 0) {
                continue;   // 不可见（被滚出屏幕）
            }
            if (r.top < 0 || r.top > topLimit) {
                continue;
            }
            // 只认可聚焦的标题节点，避免把消息气泡的文字混进"当前会话是谁"的判断里
            if (!n.isFocusable()) {
                continue;
            }
            if (!best.contains(t)) {
                best = best.isEmpty() ? t : best + " / " + t;
            }
        }
        return best;
    }

    /**
     * 找目标会话行。
     *
     * **只认昵称匹配**：匹配不到就返回 null，绝不退化成「取第一行」。
     *
     * v1.5 的教训：之前没填昵称时会退回「取列表第一行」，成了一次真实的发错人事故 ——
     * 会话列表的顺序会随新消息变化，「第一行」并不等于「置顶好友」。
     * 现在没填昵称直接返回 null，由上层拒绝执行（见 TaskRunner.start）。
     */
    public static AccessibilityNodeInfo findTargetRow(FireAccessibilityService svc, String nickname) {
        List<AccessibilityNodeInfo> rows = conversationRows(svc);
        if (rows.isEmpty()) {
            return null;
        }
        String want = normalizeName(nickname);
        if (want.isEmpty()) {
            FireLog.w("没有设置好友昵称 —— 拒绝猜测目标，直接放弃");
            return null;
        }
        for (AccessibilityNodeInfo row : rows) {
            // ① 头像节点里的干净昵称
            String got = rowNickname(row);
            if (nicknameMatches(got, want)) {
                return row;
            }
            // ② 整行摘要的前两个字段，完全相等才算（避免预览里的名字误命中）
            if (summaryMatchesTarget(row, want)) {
                return row;
            }
            // ③ 行内子节点的文字，只认完全相等
            for (AccessibilityNodeInfo n : NodeDump.collectAll(row)) {
                String t = normalizeName(NodeDump.trim(n.getText()));
                if (!t.isEmpty() && t.equalsIgnoreCase(want)) {
                    return row;
                }
            }
        }
        return null;
    }

    /** 输出某一行内部的原始内容，用于排查「昵称提取不到」的原因。 */
    public static String describeRowDetail(AccessibilityNodeInfo row) {
        StringBuilder sb = new StringBuilder();
        sb.append("该行内部节点明细（用于排查昵称提取）：\n");
        int i = 0;
        for (AccessibilityNodeInfo n : NodeDump.collectAll(row)) {
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception ignored) {
            }
            i++;
            sb.append("    [").append(i).append("] ")
                    .append(NodeDump.shortClass(n.getClassName()))
                    .append(" bounds=").append(r.toShortString())
                    .append(" T=\"").append(NodeDump.trim(n.getText())).append('"')
                    .append(" D=\"").append(descOf(n)).append('"')
                    .append(n.isClickable() ? " 可点" : "")
                    .append('\n');
        }
        if (i == 0) {
            sb.append("    (这一行里读不到任何子节点)\n");
        }
        return sb.toString();
    }

    /** 列出当前列表里所有会话的昵称（诊断用）。 */
    public static String describeRows(FireAccessibilityService svc) {
        StringBuilder sb = new StringBuilder();
        List<AccessibilityNodeInfo> rows = conversationRows(svc);
        sb.append("会话行数: ").append(rows.size()).append('\n');
        int i = 0;
        for (AccessibilityNodeInfo row : rows) {
            Rect r = new Rect();
            try {
                row.getBoundsInScreen(r);
            } catch (Exception ignored) {
            }
            i++;
            sb.append("  #").append(i).append(" 昵称=\"")
                    .append(rowNickname(row)).append("\" bounds=").append(r.toShortString());
            AccessibilityNodeInfo renew = findRenewButton(row);
            if (renew != null) {
                sb.append("  [该行有「续火花」按钮]");
            }
            sb.append('\n');
        }
        if (rows.isEmpty()) {
            sb.append("  (没找到 RecyclerView 会话行 —— 界面结构可能变了)\n");
        }
        return sb.toString();
    }

    /** 行内是否有「续火花」快捷按钮（多闪自带的一键续火花入口）。 */
    public static AccessibilityNodeInfo findRenewButton(AccessibilityNodeInfo row) {
        for (AccessibilityNodeInfo n : NodeDump.collectAll(row)) {
            String d = descOf(n);
            String t = NodeDump.trim(n.getText());
            if ("续火花".equals(d) || "续火花".equals(t)) {
                return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 聊天窗口
    // ------------------------------------------------------------------

    /** 找输入框：可编辑节点里最靠下的那个。 */
    public static AccessibilityNodeInfo findInputBox(FireAccessibilityService svc) {
        List<AccessibilityNodeInfo> all = svc.allNodes();
        AccessibilityNodeInfo best = null;
        int bestBottom = -1;
        for (AccessibilityNodeInfo n : all) {
            if (!isDuoshan(n) || !n.isEditable()) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            if (r.width() < 60 || r.height() < 20) {
                continue;
            }
            if (r.bottom > bestBottom) {
                bestBottom = r.bottom;
                best = n;
            }
        }
        return best;
    }

    /** 找发送按钮：内容描述「发送」最可靠（实测 id/gxi D="发送"），其次取输入框右侧的可点击节点。 */
    public static AccessibilityNodeInfo findSendButton(FireAccessibilityService svc, Rect inputBounds) {
        List<AccessibilityNodeInfo> all = svc.allNodes();
        AccessibilityNodeInfo byText = null;
        AccessibilityNodeInfo byGeometry = null;

        for (AccessibilityNodeInfo n : all) {
            if (!isDuoshan(n)) {
                continue;
            }
            String desc = descOf(n);
            String txt = NodeDump.trim(n.getText());
            if ("发送".equals(desc) || "发送".equals(txt)
                    || "Send".equalsIgnoreCase(desc) || "Send".equalsIgnoreCase(txt)) {
                if (n.isClickable() || hasClickableAncestor(n)) {
                    byText = n;
                }
                continue;
            }
            if (inputBounds == null) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            boolean verticalOverlap = r.bottom > inputBounds.top && r.top < inputBounds.bottom;
            boolean rightOf = r.left >= inputBounds.right - 20;
            boolean sane = r.width() > 20 && r.width() < 400 && r.height() > 20 && r.height() < 300;
            if (verticalOverlap && rightOf && sane && (n.isClickable() || hasClickableAncestor(n))) {
                if (byGeometry == null) {
                    byGeometry = n;
                }
            }
        }
        return byText != null ? byText : byGeometry;
    }

    /** 给输入框填字。先直接 ACTION_SET_TEXT，不行就聚焦/点击后重试。 */
    public static boolean setInputText(FireAccessibilityService svc, AccessibilityNodeInfo input, String text) {
        if (input == null) {
            return false;
        }
        if (trySetText(input, text)) {
            return true;
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        svc.sleep(300);
        AccessibilityNodeInfo again = findInputBox(svc);
        if (again != null && trySetText(again, text)) {
            return true;
        }
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        svc.sleep(400);
        again = findInputBox(svc);
        return again != null && trySetText(again, text);
    }

    private static boolean trySetText(AccessibilityNodeInfo node, String text) {
        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            return false;
        }
    }

    /** 宽松版回读：全树搜文本。**不用于发送校验**，只给侦察类场景用。 */
    public static boolean containsText(FireAccessibilityService svc, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String needle = text.trim();
        for (AccessibilityNodeInfo n : svc.allNodes()) {
            String t = NodeDump.trim(n.getText());
            if (!t.isEmpty() && (t.equals(needle) || t.contains(needle))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 回读校验（严格版）：确认消息**真的出现在消息列表里**。
     *
     * 为什么不能用上面那个"全树搜文本"：`setInputText` 会把文案写进输入框，
     * 而输入框里那段文字同样是一个 text 节点 —— 万一发送按钮没点中（被系统吞掉、
     * 坐标偏了），文字还留在输入框里，宽松版照样返回 true，
     * 于是任务报告"✔ 成功"，实际消息根本没发出去，火花第二天照样断。
     *
     * 两道过滤：
     *   1. **排除可编辑节点** —— 输入框里的是"还没发出去"，不算
     *   2. **命中节点必须在输入栏上方**（消息区），排除底部工具栏与输入栏自身
     */
    public static boolean containsSentMessage(FireAccessibilityService svc, String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String needle = text.trim();

        // 用输入栏的顶边当分界线；拿不到就不启用这条过滤（仍保留第 1 条）
        int inputTop = Integer.MAX_VALUE;
        AccessibilityNodeInfo input = findInputBox(svc);
        if (input != null) {
            Rect ir = new Rect();
            try {
                input.getBoundsInScreen(ir);
                inputTop = ir.top;
            } catch (Exception ignored) {
            }
        }

        for (AccessibilityNodeInfo n : svc.allNodes()) {
            if (!isDuoshan(n)) {
                continue;
            }
            if (n.isEditable()) {
                continue;   // ★ 输入框里的字 = 还没发出去
            }
            String t = NodeDump.trim(n.getText());
            if (t.isEmpty() || !(t.equals(needle) || t.contains(needle))) {
                continue;
            }
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Exception e) {
                continue;
            }
            if (r.height() <= 0 || r.width() <= 0) {
                continue;   // 不可见
            }
            if (r.bottom > inputTop) {
                continue;   // 在输入栏下方 → 不是消息气泡
            }
            FireLog.i("回读命中消息气泡: \"" + t + "\" bounds=" + r.toShortString());
            return true;
        }
        return false;
    }
}
