package com.aotu.fire;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后重新排定闹钟 —— 否则重启一次定时就永久失效。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Context ctx = context.getApplicationContext();
        FireLog.init(ctx);
        FireLog.i("收到系统广播：" + action);

        Prefs p = new Prefs(ctx);
        if (p.enabled()) {
            FireScheduler.schedule(ctx);
            FireService.start(ctx);
            FireLog.i("已恢复定时：" + FireScheduler.describe(ctx));
        } else {
            FireLog.i("定时未开启，不恢复");
        }
    }
}
