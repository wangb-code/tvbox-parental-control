package com.tvbox.parental;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launcher entry point. Opening it also (re)starts the always-on service. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, ControlService.class));
        // Also (re)arm the persisted keep-alive Job here, so that a single
        // explicit launch — including `am start` from ADB — restores the
        // watchdog even after `pm clear` or a reboot dropped the Job
        // (vendor firmware blocks BOOT_COMPLETED and stops cleared apps).
        ControlService.scheduleKeepAlive(this);
        // Do not stay on the back stack: after a quiz is answered, the TV must
        // return to the video/launcher that was on screen before, not here.
        new Handler().postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 2500);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 27, 42));
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("家长控制");
        title.setTextSize(46);
        title.setTextColor(Color.rgb(255, 209, 102));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("\n服务已在后台运行\n\n请在手机浏览器打开\n\nhttp://电视盒IP:8765\n\n"
                + "观看计时、语音读题、遥控器答题、每日重启都在盒子上完成，\n"
                + "不需要电脑保持开机。\n\n本页 2 秒后自动返回。");
        body.setTextSize(24);
        body.setTextColor(Color.WHITE);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(8, 1.2f);
        root.addView(body);

        setContentView(root);
    }
}
