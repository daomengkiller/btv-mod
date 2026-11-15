package com.littlesauce.ramball;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        TextView tv = new TextView(this);
        tv.setText("RamBall 鈥?鐐逛竴涓嬫竻鍐呭瓨\n\n1) 鎺堜簣鎮诞绐楁潈闄怽n2) 鍚姩鎮诞鐞僜n3) 鍗￠】鏃剁偣鐞?);
        tv.setTextSize(16);
        root.addView(tv);

        Button grant = new Button(this);
        grant.setText("1. 鎺堜簣鎮诞绐楁潈闄?);
        grant.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(grant);

        Button start = new Button(this);
        start.setText("2. 鍚姩鎮诞鐞?);
        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "璇峰厛鎺堜簣鎮诞绐楁潈闄?, Toast.LENGTH_LONG).show();
                return;
            }
            Intent svc = new Intent(this, RamBallService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else
                startService(svc);
            Toast.makeText(this, "鎮诞鐞冨凡鍚姩", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("3. 鍋滄鎮诞鐞?);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, RamBallService.class));
            Toast.makeText(this, "宸插仠姝?, Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        setContentView(root);
    }
}