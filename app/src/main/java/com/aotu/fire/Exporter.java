package com.aotu.fire;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** 把文本存成文件并分享出去 —— 长文本走文件通道才不会在传输中被截断。 */
public final class Exporter {

    private Exporter() {
    }

    static File dirOf(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        return dir;
    }

    public static File write(Context ctx, String name, String content) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            File f = new File(dir, name);
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
                w.write(content == null ? "" : content);
            }
            FireLog.i("已写入文件：" + f.getAbsolutePath() + "（" + f.length() + " 字节）");
            return f;
        } catch (Exception e) {
            FireLog.e("写文件失败：" + name, e);
            return null;
        }
    }

    /** 一次分享多个文件（执行诊断包：报告 + 每一步的界面 dump）。 */
    public static void shareMultiple(Context ctx, String subject, java.util.List<String> names) {
        try {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            ClipData clip = null;
            for (String n : names) {
                File f = new File(dirOf(ctx), n);
                if (!f.exists()) {
                    continue;
                }
                Uri u = ExportProvider.uriFor(n);
                uris.add(u);
                if (clip == null) {
                    clip = ClipData.newRawUri(n, u);
                } else {
                    clip.addItem(new ClipData.Item(u));
                }
            }
            if (uris.isEmpty()) {
                FireLog.w("没有可分享的文件");
                return;
            }
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("text/plain");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            if (clip != null) {
                i.setClipData(clip);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, subject);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            FireLog.i("已发起多文件分享，共 " + uris.size() + " 个文件");
        } catch (Exception e) {
            FireLog.e("多文件分享失败", e);
        }
    }

    /** 写文件 + 拉起分享面板（可直接发到 QQ/微信）。 */
    public static void share(Context ctx, String name, String content) {
        File f = write(ctx, name, content);
        if (f == null) {
            return;
        }
        try {
            Uri uri = ExportProvider.uriFor(name);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, name);
            i.setClipData(ClipData.newRawUri(name, uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "发送导出文件：" + name);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Exception e) {
            FireLog.e("分享失败", e);
        }
    }
}
