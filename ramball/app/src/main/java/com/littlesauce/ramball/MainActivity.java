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
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        TextView tv = new TextView(this);
        tv.setText("RamBall 2.0\n\n" +
                "路 鎮诞鐞冩樉绀哄疄鏃剁綉閫?/ CPU / 鍐呭瓨\n" +
                "路 榧犳爣鎮诞 鈫?杩涚▼鍕鹃€夐潰鏉匡紙鍕惧畬鑷姩璁颁綇锛塡n" +
                "路 鍗曞嚮 鈫?娓呯悊宸插嬀閫夌殑杩涚▼\n" +
                "路 闀挎寜 鈫?闊抽噺婊戝潡\n" +
                "路 鍙抽敭 鈫?璁剧疆锛堝瓧鍙?/ 閫忔槑搴︼級\n" +
                "路 鎷栧姩 鈫?绉诲姩浣嶇疆");
        tv.setTextSize(15);
        root.addView(tv);

        Button grant = new Button(this);
        grant.setText("1. 鎺堜簣鎮诞绐楁潈闄?);
        grant.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(grant);

        Button start = new Button(this);
        start.setText("2. 鍚姩鎮诞鐞?);
        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "璇峰厛鎺堜簣鎮诞绐楁潈闄?, Toast.LENGTH_LONG).show();
                return;
            }
            Intent svc = new Intent(this, RamBallService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            Toast.makeText(this, "宸插惎鍔?, Toast.LENGTH_SHORT).show();
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