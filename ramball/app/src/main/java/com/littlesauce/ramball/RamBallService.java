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
import android.provider.Settings;
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
import java.io.File;
import java.io.FileReader;
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
    private View mainPanel;
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

    private volatile boolean dragging = false;

    private final Runnable hidePanelDelayed = new Runnable() {
        @Override public void run() {
            // never hide while a slider is being dragged
            if (dragging) { ui.postDelayed(this, 900); return; }
            hideMainPanel();
        }
    };

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
                .setContentText("悬停=面板  单击=清理  长按=音量")
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
        ball.setAlpha(sp.getFloat("opacity", 1f));
        ball.setTextSize(sp.getFloat("font_size", 9f));
        applyBallScale();

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
                        if (!moved[0]) hideMainPanel();   // dragging: get the panel out of the way
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
                        if (dur >= 600) showMainPanel(true);
                        else clean();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private void setupHover() {
        ball.setOnHoverListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                ui.removeCallbacks(hidePanelDelayed);
                if (mainPanel == null) showMainPanel(false);
            } else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                ui.postDelayed(hidePanelDelayed, 800);
            }
            return false;
        });
    }

    /** Ball size is independent of font size. */    /**
     * Hover events stop at the first child that consumes them, so the panel root
     * never sees HOVER_ENTER when the pointer lands on a CheckBox or SeekBar.
     * That is why the panel used to vanish out from under the cursor.
     * Attach the keep-alive listener to every child recursively.
     */
    private void attachHoverKeepAlive(View v) {
        v.setOnHoverListener((vv, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) {
                ui.removeCallbacks(hidePanelDelayed);
            } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                ui.postDelayed(hidePanelDelayed, 1500);
            }
            return false;   // let children keep their own hover styling
        });
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) attachHoverKeepAlive(g.getChildAt(i));
        }
    }

    private void applyBallScale() {
        if (ball == null) return;
        float s = sp.getFloat("ball_scale", 1f);
        if (s < 0.5f) s = 0.5f;
        if (s > 2.0f) s = 2.0f;
        ball.setPadding((int) (dp(6) * s), (int) (dp(4) * s), (int) (dp(6) * s), (int) (dp(4) * s));
        ball.setMinWidth((int) (dp(92) * s));
        ball.setMinHeight((int) (dp(38) * s));
        ball.requestLayout();
    }

    private void paint() {        if (ball == null) return;
        ball.setText("\u2191" + curUp + "  \u2193" + curDown + "\n"
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

    private String readFile(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // ---------- main panel: processes + volume + brightness + config ----------

    private void showMainPanel(boolean forceShow) {
        if (mainPanel != null) {
            if (forceShow) hideMainPanel();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor("#F0121A24"));
        bg.setStroke(2, Color.parseColor("#FF64B5F6"));
        root.setBackground(bg);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setAlpha(sp.getFloat("opacity", 1f));

        // --- header row: title + config button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("\u52fe\u9009\u8981\u6e05\u7406\u7684\u8fdb\u7a0b");
        title.setTextColor(Color.WHITE);
        title.setTextSize(11);
        title.setPadding(0, dp(4), 0, 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, tlp);

        Button cfgBtn = new Button(this);
        cfgBtn.setText("\u2699");
        cfgBtn.setTextSize(12);
        cfgBtn.setPadding(dp(6), 0, dp(6), 0);
        cfgBtn.setOnClickListener(v -> showConfigInline(root, forceShow));
        header.addView(cfgBtn);

        root.addView(header);

        // --- process list
        Set<String> saved = sp.getStringSet("selected_pkgs", new HashSet<String>());
        List<String[]> procs = listProcesses();

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (String[] p : procs) {
            CheckBox cb = new CheckBox(this);
            cb.setText(p[1] + "  (" + p[2] + ")");
            cb.setTextColor(Color.WHITE);
            cb.setTextSize(10);
            cb.setChecked(saved.contains(p[0]));
            cb.setTag(p[0]);
            cb.setOnCheckedChangeListener((v, checked) -> saveSelection(list));
            list.addView(cb);
        }
        if (procs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("(\u672a\u627e\u5230\u53ef\u6e05\u7406\u7684\u8fdb\u7a0b)");
            empty.setTextColor(Color.GRAY);
            empty.setTextSize(10);
            list.addView(empty);
        }
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(dp(240), dp(150)));
        root.setTag(list);

        // --- buttons
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button clear = new Button(this);
        clear.setText("\u5168\u4e0d\u9009");
        clear.setTextSize(9);
        clear.setOnClickListener(v -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                if (c instanceof CheckBox) ((CheckBox) c).setChecked(false);
            }
            sp.edit().putStringSet("selected_pkgs", new HashSet<String>()).apply();
        });
        btns.addView(clear);

        Button selectAll = new Button(this);
        selectAll.setText("\u5168\u9009");
        selectAll.setTextSize(9);
        selectAll.setOnClickListener(v -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                if (c instanceof CheckBox) ((CheckBox) c).setChecked(true);
            }
        });
        btns.addView(selectAll);

        Button apply = new Button(this);
        apply.setText("\u6e05\u7406");
        apply.setTextSize(9);
        apply.setOnClickListener(v -> { clean(); hideMainPanel(); });
        btns.addView(apply);

        Button done = new Button(this);
        done.setText("\u5b8c\u6210");
        done.setTextSize(9);
        done.setOnClickListener(v -> hideMainPanel());
        btns.addView(done);

        root.addView(btns);

        // --- volume slider
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        final int vmax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView volLabel = new TextView(this);
        volLabel.setText("\u97f3\u91cf");
        volLabel.setTextColor(Color.WHITE);
        volLabel.setTextSize(10);
        volLabel.setWidth(dp(34));
        volRow.addView(volLabel);

        SeekBar vsb = new SeekBar(this);
        vsb.setMax(vmax);
        vsb.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        vsb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                ui.removeCallbacks(hidePanelDelayed);
            }
        });
        volRow.addView(vsb, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(volRow);

        // --- brightness slider
        LinearLayout briRow = new LinearLayout(this);
        briRow.setOrientation(LinearLayout.HORIZONTAL);
        briRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView briLabel = new TextView(this);
        briLabel.setText("\u4eae\u5ea6");
        briLabel.setTextColor(Color.WHITE);
        briLabel.setTextSize(10);
        briLabel.setWidth(dp(34));
        briRow.addView(briLabel);

        int curBri = 128;
        try { curBri = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); }
        catch (Exception ignored) {}

        SeekBar bsb = new SeekBar(this);
        bsb.setMax(255);
        bsb.setProgress(curBri);
        bsb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, p);
                } catch (Exception e) {
                    Toast.makeText(RamBallService.this,
                            "\u9700\u8981\u4fee\u6539\u7cfb\u7edf\u8bbe\u7f6e\u6743\u9650", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                ui.removeCallbacks(hidePanelDelayed);
            }
        });
        briRow.addView(bsb, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(briRow);

        // --- position: below the ball, or above it when there is no room
        WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.TOP | Gravity.END;
        plp.x = lp.x;

        int ballH = (ball != null && ball.getHeight() > 0) ? ball.getHeight() : dp(52);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int estH = dp(360);
        int gap = dp(sp.getInt("panel_gap", 6));
        int below = lp.y + ballH + gap;
        if (below + estH > screenH - dp(8)) {
            plp.y = Math.max(dp(8), lp.y - estH - gap);   // open upwards
        } else {
            plp.y = below;
        }

        attachHoverKeepAlive(root);

        try { wm.addView(root, plp); mainPanel = root; } catch (Exception ignored) {}
    }

    private void showConfigInline(LinearLayout panel, boolean forceShow) {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor("#F0182028"));
        bg.setStroke(2, Color.parseColor("#FFFFB74D"));
        overlay.setBackground(bg);
        overlay.setPadding(dp(14), dp(10), dp(14), dp(14));

        TextView t = new TextView(this);
        t.setText("\u914d\u7f6e");
        t.setTextColor(Color.WHITE);
        t.setTextSize(11);
        overlay.addView(t);

        final TextView fsVal = new TextView(this);
        fsVal.setTextColor(Color.parseColor("#FF90CAF9"));
        fsVal.setTextSize(10);
        fsVal.setText("\u5b57\u53f7 " + sp.getFloat("font_size", 9f));
        overlay.addView(fsVal);

        SeekBar fs = new SeekBar(this);
        fs.setMax(18);
        fs.setProgress((int) (sp.getFloat("font_size", 9f) * 2) - 10);
        fs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                float v = (p + 10) / 2f;
                fsVal.setText("\u5b57\u53f7 " + v);
                sp.edit().putFloat("font_size", v).apply();
                paint();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        overlay.addView(fs, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView opVal = new TextView(this);
        opVal.setTextColor(Color.parseColor("#FF90CAF9"));
        opVal.setTextSize(10);
        opVal.setText("\u900f\u660e\u5ea6 " + (int) (sp.getFloat("opacity", 1f) * 100) + "%");
        overlay.addView(opVal);

        SeekBar op = new SeekBar(this);
        op.setMax(100);
        op.setProgress((int) (sp.getFloat("opacity", 1f) * 100));
        op.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                opVal.setText("\u900f\u660e\u5ea6 " + p + "%");
                sp.edit().putFloat("opacity", p / 100f).apply();
                paint();
                if (mainPanel != null) mainPanel.setAlpha(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        overlay.addView(op, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView bsVal = new TextView(this);
        bsVal.setTextColor(Color.parseColor("#FF90CAF9"));
        bsVal.setTextSize(10);
        bsVal.setText("\u7403\u5927\u5c0f " + String.format("%.1f", sp.getFloat("ball_scale", 1f)) + "x");
        overlay.addView(bsVal);

        SeekBar bs = new SeekBar(this);
        bs.setMax(15);
        bs.setProgress((int) ((sp.getFloat("ball_scale", 1f) - 0.5f) * 10));
        bs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                float v = 0.5f + p / 10f;
                bsVal.setText("\u7403\u5927\u5c0f " + String.format("%.1f", v) + "x");
                sp.edit().putFloat("ball_scale", v).apply();
                applyBallScale();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; }
        });
        overlay.addView(bs, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView gapVal = new TextView(this);
        gapVal.setTextColor(Color.parseColor("#FF90CAF9"));
        gapVal.setTextSize(10);
        gapVal.setText("\u9762\u677f\u95f4\u8ddd " + sp.getInt("panel_gap", 6) + "dp");
        overlay.addView(gapVal);

        SeekBar gap = new SeekBar(this);
        gap.setMax(40);
        gap.setProgress(sp.getInt("panel_gap", 6));
        gap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                gapVal.setText("\u9762\u677f\u95f4\u8ddd " + p + "dp");
                sp.edit().putInt("panel_gap", p).apply();
                if (mainPanel != null) {
                    // re-layout the panel at the new offset
                    hideMainPanel();
                    showMainPanel(false);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; }
        });
        overlay.addView(gap, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));
        Button close = new Button(this);
        close.setText("\u5173\u95ed");
        close.setTextSize(9);
        close.setOnClickListener(v -> {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        });
        overlay.addView(close);

        WindowManager.LayoutParams olp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        olp.gravity = Gravity.CENTER;
        try { wm.addView(overlay, olp); } catch (Exception ignored) {}
    }

    private void saveSelection(LinearLayout list) {
        Set<String> sel = new HashSet<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof CheckBox && ((CheckBox) c).isChecked())
                sel.add((String) c.getTag());
        }
        sp.edit().putStringSet("selected_pkgs", sel).apply();
    }

    /**
     * Enumerates app processes by walking /proc directly.
     * ActivityManager.getRunningAppProcesses() only returns our own process since
     * Android 5.1, and the su binary on this ROM refuses non-shell callers
     * ("su.real: not allowed"), so /proc is the only path that works.
     */
    private List<String[]> listProcesses() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PackageManager pm = getPackageManager();

        // Every direct process-enumeration API is walled off on this ROM:
        //   getRunningAppProcesses()  -> only our own process (Android 5.1+)
        //   getRunningServices()      -> only our own service (Android 8+)
        //   new File("/proc")         -> one entry (hidepid=2 + untrusted_app)
        // UsageStatsManager is a binder call into system_server and works as long
        // as GET_USAGE_STATS is granted via appops.
        android.app.usage.UsageStatsManager usm =
                (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        long lastCleanTime = sp.getLong("last_clean_time", 0L);
        java.util.List<android.app.usage.UsageStats> stats = null;
        try {
            stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    now - 6L * 3600L * 1000L, now);
        } catch (Exception e) {
            android.util.Log.w("RamBall", "queryUsageStats failed", e);
        }

        if (stats == null || stats.isEmpty()) {
            android.util.Log.w("RamBall", "UsageStats empty - GET_USAGE_STATS not granted?");
            return out;
        }

        for (android.app.usage.UsageStats s : stats) {
            String pkg = s.getPackageName();
            if (pkg == null) continue;
            if (pkg.equals(getPackageName())) continue;
            if (ALWAYS_PROTECT.contains(pkg)) continue;
            if (pkg.equals("cn.bvtv.beta")) continue;
            // anything that has not been used since the last clean is hidden:
            // otherwise a cleaned app looks like it is still running just because
            // UsageStats keeps its historical record.
            if (s.getLastTimeUsed() <= lastCleanTime) continue;
            if (!seen.add(pkg)) continue;
            if (!hasLauncher(pm, pkg)) continue;

            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
            } catch (Exception ignored) {}

            long ago = Math.max(0, (now - s.getLastTimeUsed()) / 60000L);
            out.add(new String[]{pkg, label, ago + "m"});
        }

        android.util.Log.i("RamBall", "listProcesses: usageStats=" + stats.size()
                + " kept=" + out.size());
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    private boolean hasLauncher(PackageManager pm, String pkg) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            i.setPackage(pkg);
            java.util.List<android.content.pm.ResolveInfo> ri =
                    pm.queryIntentActivities(i, 0);
            return ri != null && !ri.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void hideMainPanel() {
        if (mainPanel != null) {
            try { wm.removeView(mainPanel); } catch (Exception ignored) {}
            mainPanel = null;
        }
    }

    // ---------- clean ----------

    private void clean() {
        final long before = availMem();
        android.util.Log.i("RamBall", "clean() start, availBefore=" + (before / 1048576) + "MB");
        new Thread(() -> {
            Set<String> selected = sp.getStringSet("selected_pkgs", new HashSet<String>());
            int killed = 0;
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (selected.isEmpty()) {
                for (String[] p : listProcesses()) {
                    try { am.killBackgroundProcesses(p[0]); killed++; } catch (Exception ignored) {}
                }
            } else {
                for (String pkg : selected) {
                    if (ALWAYS_PROTECT.contains(pkg)) continue;
                    try { am.killBackgroundProcesses(pkg); killed++; } catch (Exception ignored) {}
                }
            }
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            final long after = availMem();
            final int k = killed;
            final boolean wasSel = !selected.isEmpty();
            // remember the cut-off so cleaned apps disappear from the list until
            // the user actually opens them again
            sp.edit().putLong("last_clean_time", System.currentTimeMillis()).apply();
            android.util.Log.i("RamBall", "clean() done killed=" + k
                    + " freed=" + ((availMem() - before) / 1048576) + "MB");
            ui.post(() -> {
                sample(); paint();
                Toast.makeText(this,
                        (wasSel ? "\u6e05\u7406\u5df2\u9009\u7684 " + k + " \u4e2a\u8fdb\u7a0b"
                                : "\u6e05\u7406 " + k + " \u4e2a\u540e\u53f0\u8fdb\u7a0b")
                                + "\uff0c\u91ca\u653e " + Math.max(0, (after - before) / 1048576) + " MB",
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
        hideMainPanel();
        if (wm != null && ball != null) { try { wm.removeView(ball); } catch (Exception ignored) {} }
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
