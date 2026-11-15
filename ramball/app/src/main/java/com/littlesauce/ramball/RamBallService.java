package com.littlesauce.ramball;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RamBallService extends Service {
    private static final String CH_ID = "ramball";
    private static final int NOTI_ID = 9101;
    private static final String PREF = "ramball";

    private WindowManager wm;
    private TextView ball;
    private WindowManager.LayoutParams lp;
    private View volumePanel, procPanel, settingsPanel;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    private long lastRx = -1, lastTx = -1, lastCpuTotal = -1, lastCpuIdle = -1;
    private float curCpu = 0f;
    private int curRamPct = 0;
    private String curUp = "0K", curDown = "0K";

    private static final Set<String> ALWAYS_PROTECT = new HashSet<>(Arrays.asList(
            "com.farmerbb.taskbar", "cu.axel.smartdock", "android",
            "com.android.systemui", "com.android.phone", "com.littlesauce.ramball"
    ));

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { sample(); paint(); } catch (Exception ignored) {}
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable hideProcDelayed = () -> hideProcPanel();

    @Override public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
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
                .setContentText("鐐?娓呯悊 闀挎寜=闊抽噺 鎮仠=杩涚▼ 鍙抽敭=璁剧疆")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true).build();
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void showBall() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(28));
        d.setColor(Color.parseColor("#D9101828"));
        d.setStroke(2, Color.parseColor("#FF64B5F6"));

        ball = new TextView(this);
        ball.setTextColor(Color.WHITE);
        ball.setGravity(Gravity.CENTER);
        ball.setBackground(d);
        ball.setPadding(dp(6), dp(4), dp(6), dp(4));
        ball.setAlpha(sp.getFloat("opacity", 1f));
        ball.setTextSize(sp.getFloat("font_size", 9f));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(14);
        lp.y = dp(180);

        setupTouch();
        setupHover();
        setupRightClick();
        wm.addView(ball, lp);
        paint();
    }

    private void setupTouch() {
        final int[] sx = {0}, sy = {0};
        final float[] dx = {0}, dy = {0};
        final boolean[] moved = {false};
        final long[] t0 = {0};

        ball.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx[0] = lp.x; sy[0] = lp.y;
                    dx[0] = e.getRawX(); dy[0] = e.getRawY();
                    t0[0] = System.currentTimeMillis();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int mx = (int) (dx[0] - e.getRawX());
                    int my = (int) (e.getRawY() - dy[0]);
                    if (Math.abs(mx) > 10 || Math.abs(my) > 10) {
                        moved[0] = true;
                        lp.x = sx[0] + mx;
                        lp.y = sy[0] + my;
                        try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    long dur = System.currentTimeMillis() - t0[0];
                    if (!moved[0]) {
                        if (dur >= 600) showVolumePanel();
                        else clean();
                    }
                    return true;
                }
                case MotionEvent.ACTION_BUTTON_PRESS: {
                    int btn = e.getButtonState();
                    if (btn == MotionEvent.BUTTON_SECONDARY) {
                        showSettingsPanel();
                        return true;
                    }
                    return false;
                }
            }
            return false;
        });
    }

    private void setupHover() {
        ball.setOnHoverListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                ui.removeCallbacks(hideProcDelayed);
                showProcPanel();
            } else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                ui.postDelayed(hideProcDelayed, 700);
            }
            return false;
        });
    }

    private void setupRightClick() {
        ball.setOnGenericMotionListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                    && e.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                showSettingsPanel();
                return true;
            }
            return false;
        });
    }

    private void paint() {
        if (ball == null) return;
        ball.setText("鈫? + curUp + "  鈫? + curDown + "\n"
                + "C " + (int) curCpu + "%   R " + curRamPct + "%");
        ball.setTextSize(sp.getFloat("font_size", 9f));
        ball.setAlpha(sp.getFloat("opacity", 1f));
        int c = curRamPct > 85 ? Color.parseColor("#FFFF5252")
                : curRamPct > 70 ? Color.parseColor("#FFFFB74D") : Color.WHITE;
        ball.setTextColor(c);
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
        if (lastRx >= 0) { curDown = fmt(rx - lastRx); curUp = fmt(tx - lastTx); }
        lastRx = rx; lastTx = tx;

        long tot = 0, idle = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String l = br.readLine();
            if (l != null) {
                String[] p = l.trim().split("\\s+");
                for (int i = 1; i < p.length; i++) {
                    long v = Long.parseLong(p[i]);
                    tot += v;
                    if (i == 4 || i == 5) idle += v;
                }
            }
        } catch (Exception ignored) {}
        if (lastCpuTotal > 0 && tot > lastCpuTotal) {
            long dt = tot - lastCpuTotal, di = idle - lastCpuIdle;
            curCpu = Math.max(0f, Math.min(100f, (dt - di) * 100f / dt));
        }
        lastCpuTotal = tot; lastCpuIdle = idle;

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        curRamPct = (int) ((mi.totalMem - mi.availMem) * 100 / Math.max(1, mi.totalMem));
    }

    private String fmt(long b) {
        if (b < 1024) return b + "B";
        if (b < 1048576) return (b / 1024) + "K";
        return String.format("%.1fM", b / 1048576.0);
    }

    // ---------- process picker (hover) ----------

    private void showProcPanel() {
        if (procPanel != null) return;
        hideVolumePanel();
        hideSettingsPanel();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor("#F0121A24"));
        bg.setStroke(2, Color.parseColor("#FF64B5F6"));
        root.setBackground(bg);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText("鍕鹃€夎娓呯悊鐨勮繘绋?);
        title.setTextColor(Color.WHITE);
        title.setTextSize(11);
        root.addView(title);

        Set<String> saved = sp.getStringSet("selected_pkgs", new HashSet<>());
        List<String[]> procs = listProcesses(saved);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (String[] p : procs) {
            CheckBox cb = new CheckBox(this);
            cb.setText(p[1] + "  (" + p[2] + " MB)");
            cb.setTextColor(Color.WHITE);
            cb.setTextSize(10);
            cb.setChecked(saved.contains(p[0]));
            cb.setTag(p[0]);
            cb.setOnCheckedChangeListener((v, checked) -> saveSelection());
            list.addView(cb);
        }
        if (procs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("(娌℃湁鍙竻鐞嗙殑杩涚▼)");
            empty.setTextColor(Color.GRAY);
            empty.setTextSize(10);
            list.addView(empty);
        }
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(dp(220), dp(220)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button clear = new Button(this);
        clear.setText("鍏ㄤ笉閫?);
        clear.setTextSize(9);
        clear.setOnClickListener(v -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                if (c instanceof CheckBox) ((CheckBox) c).setChecked(false);
            }
            sp.edit().putStringSet("selected_pkgs", new HashSet<>()).apply();
        });
        btns.addView(clear);

        Button done = new Button(this);
        done.setText("瀹屾垚");
        done.setTextSize(9);
        done.setOnClickListener(v -> hideProcPanel());
        btns.addView(done);

        root.addView(btns);

        WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.TOP | Gravity.END;
        plp.x = dp(14);
        plp.y = dp(180) + dp(56);

        root.setOnHoverListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) ui.removeCallbacks(hideProcDelayed);
            else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) ui.postDelayed(hideProcDelayed, 700);
            return false;
        });

        try { wm.addView(root, plp); procPanel = root; } catch (Exception ignored) {}
    }

    private void saveSelection() {
        if (procPanel == null) return;
        Set<String> sel = new HashSet<>();
        LinearLayout root = (LinearLayout) procPanel;
        ScrollView sv = (ScrollView) root.getChildAt(1);
        LinearLayout list = (LinearLayout) sv.getChildAt(0);
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof CheckBox && ((CheckBox) c).isChecked())
                sel.add((String) c.getTag());
        }
        sp.edit().putStringSet("selected_pkgs", sel).apply();
    }

    private List<String[]> listProcesses(Set<String> saved) {
        List<String[]> out = new ArrayList<>();
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
        if (list == null) return out;
        Set<String> seen = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo p : list) {
            if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) continue;
            String pkg = p.processName;
            if (pkg == null || pkg.contains(":")) continue;
            if (ALWAYS_PROTECT.contains(pkg)) continue;
            if (pkg.equals("cn.bvtv.beta")) continue;
            if (!seen.add(pkg)) continue;
            String label = pkg;
            int mb = 0;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
            } catch (Exception ignored) {}
            out.add(new String[]{pkg, label, String.valueOf(mb)});
        }
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    private void hideProcPanel() {
        if (procPanel != null) {
            try { wm.removeView(procPanel); } catch (Exception ignored) {}
            procPanel = null;
        }
    }

    // ---------- settings (right click) ----------

    private void showSettingsPanel() {
        if (settingsPanel != null) { hideSettingsPanel(); return; }
        hideVolumePanel();
        hideProcPanel();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor("#F0121A24"));
        bg.setStroke(2, Color.parseColor("#FF64B5F6"));
        root.setBackground(bg);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView t = new TextView(this);
        t.setText("璁剧疆");
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        root.addView(t);

        final TextView fsVal = new TextView(this);
        fsVal.setTextColor(Color.parseColor("#FF90CAF9"));
        fsVal.setTextSize(10);
        fsVal.setText("瀛楀彿 " + sp.getFloat("font_size", 9f));
        root.addView(fsVal);

        SeekBar fs = new SeekBar(this);
        fs.setMax(18);
        fs.setProgress((int) (sp.getFloat("font_size", 9f) * 2) - 10);
        fs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                float v = (p + 10) / 2f;
                fsVal.setText("瀛楀彿 " + v);
                sp.edit().putFloat("font_size", v).apply();
                paint();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(fs, new LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView opVal = new TextView(this);
        opVal.setTextColor(Color.parseColor("#FF90CAF9"));
        opVal.setTextSize(10);
        opVal.setText("閫忔槑搴?" + (int) (sp.getFloat("opacity", 1f) * 100) + "%");
        root.addView(opVal);

        SeekBar op = new SeekBar(this);
        op.setMax(100);
        op.setProgress((int) (sp.getFloat("opacity", 1f) * 100));
        op.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                opVal.setText("閫忔槑搴?" + p + "%");
                sp.edit().putFloat("opacity", p / 100f).apply();
                paint();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(op, new LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT));

        Button close = new Button(this);
        close.setText("鍏抽棴");
        close.setTextSize(9);
        close.setOnClickListener(v -> hideSettingsPanel());
        root.addView(close);

        WindowManager.LayoutParams splp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        splp.gravity = Gravity.CENTER;

        try { wm.addView(root, splp); settingsPanel = root; } catch (Exception ignored) {}
    }

    private void hideSettingsPanel() {
        if (settingsPanel != null) {
            try { wm.removeView(settingsPanel); } catch (Exception ignored) {}
            settingsPanel = null;
        }
    }

    // ---------- volume ----------

    private void showVolumePanel() {
        if (volumePanel != null) { hideVolumePanel(); return; }
        hideProcPanel();
        hideSettingsPanel();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(Color.parseColor("#F0101828"));
        d.setStroke(2, Color.parseColor("#FF64B5F6"));
        root.setBackground(d);
        root.setPadding(dp(22), dp(16), dp(22), dp(20));

        TextView title = new TextView(this);
        title.setText("闊抽噺");
        title.setTextColor(Color.WHITE);
        title.setTextSize(11);
        root.addView(title);

        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final TextView val = new TextView(this);
        val.setTextColor(Color.parseColor("#FF90CAF9"));
        val.setTextSize(13);
        val.setText(String.valueOf(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / Math.max(1, max)));
        root.addView(val);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(String.valueOf(p * 100 / Math.max(1, max)));
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams vlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        vlp.gravity = Gravity.CENTER;

        try { wm.addView(root, vlp); volumePanel = root; } catch (Exception ignored) {}
        ui.postDelayed(this::hideVolumePanel, 8000);
    }

    private void hideVolumePanel() {
        if (volumePanel != null) {
            try { wm.removeView(volumePanel); } catch (Exception ignored) {}
            volumePanel = null;
        }
    }

    // ---------- clean ----------

    private void clean() {
        final long before = availMem();
        new Thread(() -> {
            Set<String> selected = sp.getStringSet("selected_pkgs", new HashSet<>());
            int killed = 0;
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (selected.isEmpty()) {
                List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                if (list != null) for (ActivityManager.RunningAppProcessInfo p : list) {
                    if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue;
                    String n = p.processName;
                    if (n == null || n.contains(":")) continue;
                    if (ALWAYS_PROTECT.contains(n) || n.equals("cn.bvtv.beta")) continue;
                    am.killBackgroundProcesses(n);
                    killed++;
                }
            } else {
                for (String pkg : selected) {
                    if (ALWAYS_PROTECT.contains(pkg)) continue;
                    try { am.killBackgroundProcesses(pkg); killed++; } catch (Exception ignored) {}
                }
            }
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "sync; echo 1 > /proc/sys/vm/drop_caches"});
                BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                while (r.readLine() != null) { }
                pr.waitFor();
            } catch (Exception ignored) {}
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            final long after = availMem();
            final int k = killed;
            final boolean wasSel = !selected.isEmpty();
            ui.post(() -> {
                sample(); paint();
                Toast.makeText(this,
                        (wasSel ? "娓呯悊宸查€夌殑 " + k + " 涓繘绋? : "娓呯悊 " + k + " 涓悗鍙拌繘绋?)
                                + "锛岄噴鏀?" + Math.max(0, (after - before) / 1048576) + " MB",
                        Toast.LENGTH_SHORT).show();
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
        hideVolumePanel(); hideProcPanel(); hideSettingsPanel();
        if (wm != null && ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} }
    }

    @Override public IBinder onBind(Intent i) { return null; }
}