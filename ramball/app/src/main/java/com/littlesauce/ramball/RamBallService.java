package com.littlesauce.ramball;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RamBallService extends Service {
    private static final String CH_ID = "ramball";
    private static final int NOTI_ID = 9101;

    private WindowManager wm;
    private TextView ball;
    private WindowManager.LayoutParams lp;
    private View volumePanel;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long lastRx = -1, lastTx = -1, lastCpuTotal = -1, lastCpuIdle = -1;
    private float curCpu = 0f;
    private int curRamPct = 0;
    private String curUp = "0K", curDown = "0K";

    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "com.farmerbb.taskbar", "cu.axel.smartdock", "cn.bvtv.beta",
            "android", "com.android.systemui", "com.android.phone", "com.littlesauce.ramball"
    ));

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { sample(); paint(); } catch (Exception ignored) {}
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTI_ID, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showBall();
        ui.post(tick);
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH_ID, "RamBall", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        return b.setContentTitle("RamBall")
                .setContentText("鐐?娓呭唴瀛? 闀挎寜=闊抽噺")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true).build();
    }

    private void showBall() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(28 * getResources().getDisplayMetrics().density);
        d.setColor(Color.parseColor("#D9101828"));
        d.setStroke(2, Color.parseColor("#FF64B5F6"));

        ball = new TextView(this);
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(9);
        ball.setGravity(Gravity.CENTER);
        ball.setBackground(d);
        ball.setPadding(8, 4, 8, 4);
        ball.setLineSpacing(0, 1.0f);

        int w = (int) (132 * getResources().getDisplayMetrics().density);
        int h = (int) (52 * getResources().getDisplayMetrics().density);
        lp = new WindowManager.LayoutParams(
                w, h,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 16;
        lp.y = 200;

        setupTouch();
        wm.addView(ball, lp);
        paint();
    }

    private void setupTouch() {
        final int[] startX = {0}, startY = {0};
        final float[] downX = {0}, downY = {0};
        final boolean[] moved = {false};
        final long[] downTime = {0};

        ball.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = lp.x; startY[0] = lp.y;
                    downX[0] = e.getRawX(); downY[0] = e.getRawY();
                    downTime[0] = System.currentTimeMillis();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (downX[0] - e.getRawX());
                    int dy = (int) (e.getRawY() - downY[0]);
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        moved[0] = true;
                        lp.x = startX[0] + dx;
                        lp.y = startY[0] + dy;
                        try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    long dur = System.currentTimeMillis() - downTime[0];
                    if (!moved[0]) {
                        if (dur >= 600) showVolumePanel();
                        else clean();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private void paint() {
        if (ball == null) return;
        String s = "鈫? + curUp + "  鈫? + curDown + "\n"
                + "C " + (int) curCpu + "%   R " + curRamPct + "%";
        ball.setText(s);
        int ramColor = curRamPct > 85 ? Color.parseColor("#FFFF5252")
                : curRamPct > 70 ? Color.parseColor("#FFFFB74D") : Color.WHITE;
        ball.setTextColor(ramColor);
    }

    private void sample() {
        long rx = 0, tx = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.indexOf(':') < 0 || line.contains("lo:")) continue;
                String[] p = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
                if (p.length >= 9) { rx += Long.parseLong(p[0]); tx += Long.parseLong(p[8]); }
            }
        } catch (Exception ignored) {}
        if (lastRx >= 0) {
            curDown = fmt(rx - lastRx);
            curUp = fmt(tx - lastTx);
        }
        lastRx = rx; lastTx = tx;

        long tot = 0, idle = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line != null) {
                String[] p = line.trim().split("\\s+");
                for (int i = 1; i < p.length; i++) {
                    long v = Long.parseLong(p[i]);
                    tot += v;
                    if (i == 4 || i == 5) idle += v;
                }
            }
        } catch (Exception ignored) {}
        if (lastCpuTotal > 0 && tot > lastCpuTotal) {
            long dt = tot - lastCpuTotal;
            long di = idle - lastCpuIdle;
            curCpu = Math.max(0f, Math.min(100f, (dt - di) * 100f / dt));
        }
        lastCpuTotal = tot; lastCpuIdle = idle;

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        curRamPct = (int) ((mi.totalMem - mi.availMem) * 100 / Math.max(1, mi.totalMem));
    }

    private String fmt(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "K";
        return String.format("%.1fM", bytes / 1048576.0);
    }

    private void showVolumePanel() {
        if (volumePanel != null) { hideVolumePanel(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(18 * getResources().getDisplayMetrics().density);
        d.setColor(Color.parseColor("#F0101828"));
        d.setStroke(2, Color.parseColor("#FF64B5F6"));
        root.setBackground(d);
        root.setPadding(24, 18, 24, 24);

        TextView title = new TextView(this);
        title.setText("闊抽噺");
        title.setTextColor(Color.WHITE);
        title.setTextSize(11);
        root.addView(title);

        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        final TextView val = new TextView(this);
        val.setTextColor(Color.parseColor("#FF90CAF9"));
        val.setTextSize(13);
        val.setText(String.valueOf(cur * 100 / Math.max(1, max)));
        root.addView(val);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(cur);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(String.valueOf(p * 100 / Math.max(1, max)));
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb, new LinearLayout.LayoutParams(
                (int) (180 * getResources().getDisplayMetrics().density),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams vlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        vlp.gravity = Gravity.CENTER;

        try { wm.addView(root, vlp); volumePanel = root; }
        catch (Exception ignored) {}
        ui.postDelayed(this::hideVolumePanel, 8000);
    }

    private void hideVolumePanel() {
        if (volumePanel != null) {
            try { wm.removeView(volumePanel); } catch (Exception ignored) {}
            volumePanel = null;
        }
    }

    private void clean() {
        final long before = availMem();
        new Thread(() -> {
            int killed = 0;
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                if (list != null) for (ActivityManager.RunningAppProcessInfo p : list) {
                    if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue;
                    String n = p.processName;
                    if (n == null) continue;
                    boolean prot = false;
                    for (String w : PROTECTED) if (n.startsWith(w)) { prot = true; break; }
                    if (prot) continue;
                    am.killBackgroundProcesses(n);
                    killed++;
                }
            } catch (Exception ignored) {}
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "sync; echo 1 > /proc/sys/vm/drop_caches"});
                BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                while (r.readLine() != null) { }
                pr.waitFor();
            } catch (Exception ignored) {}
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            final long after = availMem();
            final int k = killed;
            ui.post(() -> {
                sample(); paint();
                Toast.makeText(this, "娓呯悊 " + k + " 杩涚▼锛岄噴鏀?" + Math.max(0, (after - before) / 1048576) + " MB", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private long availMem() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    @Override public void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        hideVolumePanel();
        if (wm != null && ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} }
    }

    @Override public IBinder onBind(Intent i) { return null; }
}