package com.aotu.fire;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置存取。
 *
 * 安全提示：锁屏密码以明文保存在应用私有目录（/data/data/com.aotu.fire/shared_prefs/），
 * 本应用不申请网络权限，数据不会离开手机；但手机已 root 或被人拿到备份文件时仍可能泄露。
 * 因此界面提供「本次测试不保存密码」选项。
 */
public final class Prefs {

    private static final String NAME = "aotu_fire";
    private static final String K_PIN = "pin";
    private static final String K_SAVE_PIN = "save_pin";
    private static final String K_TARGET = "target_friend";
    private static final String K_TARGETS = "target_friends";
    private static final String K_HOUR = "hour";
    private static final String K_MINUTE = "minute";
    private static final String K_ENABLED = "enabled";
    private static final String K_MESSAGES = "messages";

    public static final String DEFAULT_MESSAGES =
            "1\n早\n在\n哈喽\n打卡\n冒个泡\n今天也要开心\n滴滴\n续火花\n来了";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String pin() {
        return sp.getString(K_PIN, "");
    }

    public void setPin(String v) {
        sp.edit().putString(K_PIN, v == null ? "" : v).apply();
    }

    public boolean savePin() {
        return sp.getBoolean(K_SAVE_PIN, true);
    }

    public void setSavePin(boolean v) {
        sp.edit().putBoolean(K_SAVE_PIN, v).apply();
    }

    public String targetFriend() {
        return sp.getString(K_TARGET, "");
    }

    public void setTargetFriend(String v) {
        sp.edit().putString(K_TARGET, v == null ? "" : v).apply();
    }

    /**
     * 目标好友昵称列表（一行一个，也接受逗号/顿号分隔）。
     *
     * 兼容旧版本：早期只支持单个好友、存在 target_friend 里；
     * 新键为空但旧键有值时会自动迁移过来。
     */
    public java.util.List<String> targetFriends() {
        String raw = sp.getString(K_TARGETS, "");
        if (raw == null || raw.trim().isEmpty()) {
            String legacy = targetFriend();
            if (legacy == null || legacy.trim().isEmpty()) {
                return new java.util.ArrayList<>();
            }
            raw = legacy;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : raw.split("[\\n\\r,，、;；]")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public void setTargetFriends(String raw) {
        sp.edit().putString(K_TARGETS, raw == null ? "" : raw).apply();
    }

    public int hour() {
        return sp.getInt(K_HOUR, 1);
    }

    public int minute() {
        return sp.getInt(K_MINUTE, 0);
    }

    public void setTime(int hour, int minute) {
        sp.edit().putInt(K_HOUR, hour).putInt(K_MINUTE, minute).apply();
    }

    public boolean enabled() {
        return sp.getBoolean(K_ENABLED, false);
    }

    public void setEnabled(boolean v) {
        sp.edit().putBoolean(K_ENABLED, v).apply();
    }

    public String messages() {
        return sp.getString(K_MESSAGES, DEFAULT_MESSAGES);
    }

    public void setMessages(String v) {
        sp.edit().putString(K_MESSAGES, v == null ? DEFAULT_MESSAGES : v).apply();
    }

    /** 下一次排定的触发时刻（毫秒），用于界面显示。 */
    public long scheduledAt() {
        return sp.getLong("scheduled_at", 0L);
    }

    public void setScheduledAt(long v) {
        sp.edit().putLong("scheduled_at", v).apply();
    }
}
