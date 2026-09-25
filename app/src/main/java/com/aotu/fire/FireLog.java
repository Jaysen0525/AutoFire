package com.aotu.fire;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 极简日志：内存环形缓冲 + 追加写入应用私有外部目录。
 * 全部操作都在主线程发生（无障碍回调、广播、Handler），加锁只为兜底。
 */
public final class FireLog {

    private static final String TAG = "AotuFire";
    private static final int MAX_LINES = 500;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static File logFile;

    private FireLog() {
    }

    /** 尽早调用一次，之后日志才会落盘。 */
    public static synchronized void init(Context ctx) {
        if (logFile != null) {
            return;
        }
        File dir = ctx.getApplicationContext().getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getApplicationContext().getFilesDir();
        }
        logFile = new File(dir, "aotu_fire.log");
    }

    public static synchronized File file() {
        return logFile;
    }

    public static void d(String msg) {
        write("D", msg);
    }

    public static void i(String msg) {
        write("I", msg);
    }

    public static void w(String msg) {
        write("W", msg);
    }

    public static void e(String msg) {
        write("E", msg);
    }

    public static void e(String msg, Throwable t) {
        write("E", msg + " -> " + t);
    }

    private static void write(String level, String msg) {
        String line = TIME.format(new Date()) + " [" + level + "] " + msg;
        Log.println(level.equals("E") ? Log.ERROR : Log.INFO, TAG, msg);
        synchronized (FireLog.class) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
            appendToFile(line);
        }
        notifyListeners();
    }

    private static void appendToFile(String line) {
        if (logFile == null) {
            return;
        }
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
            w.write(line);
            w.write("\n");
        } catch (Exception ignored) {
            // 日志写不进去不能影响主流程
        }
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
        if (logFile != null && logFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
        }
        notifyListeners();
    }

    public static synchronized void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static synchronized void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyListeners() {
        List<Runnable> copy;
        synchronized (FireLog.class) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }
}
