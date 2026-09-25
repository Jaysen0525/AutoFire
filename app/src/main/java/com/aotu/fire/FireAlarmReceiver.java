package com.aotu.fire;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 每日闹钟到点：启动保活服务 + 执行完整任务链，然后排定下一次。 */
public class FireAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        FireLog.init(context.getApplicationContext());
        FireLog.i("⏰ 闹钟触发：" + (intent == null ? "?" : intent.getAction()));

        Context ctx = context.getApplicationContext();
        FireService.start(ctx);
        WakeActivity.acquireWakelock(ctx);
        TaskRunner.get().start(ctx, true);

        // 滚动排定明天同一时刻
        FireScheduler.schedule(ctx);
        FireLog.i("下一次触发：" + FireScheduler.describe(ctx));
    }
}
