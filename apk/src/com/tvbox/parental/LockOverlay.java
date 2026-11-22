package com.tvbox.parental;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Full-screen, remote-proof lock screen drawn by the background service.
 *
 * Unlike the quiz, NOTHING on the remote can dismiss this window - every key
 * is swallowed. Only the parent's phone (/api/unlock) removes it.
 * The overlay is a global window, so even pressing HOME leaves it on top.
 */
public class LockOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView title, body, hint;
    private LinearLayout weatherPanel, weatherRow;
    private TextView weatherHead, weatherFoot;
    private TextView countdown, countdownCap;
    private volatile JSONObject weatherData;
    private String weatherStamp = "";
    /** Absolute unlock time; 0 means a manual lock with no automatic end. */
    private long deadlineMs = 0;
    private String countdownLabel = "";
    private boolean showing = false;
    private float scale = 1f;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            if (showing) {
                ui.postDelayed(this, 1000);
            }
        }
    };

    public LockOverlay(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        scale = Math.min(dm.widthPixels / 1920f, dm.heightPixels / 1080f);
        if (scale <= 0f) {
            scale = 1f;
        }
    }

    private int px(float sp) {
        return (int) (sp * scale * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.OPAQUE);
        p.gravity = Gravity.CENTER;
        return p;
    }

    public synchronized boolean isShowing() {
        return showing;
    }

    private void build() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int padX = (int) (dm.widthPixels * 0.075f);
        int padY = (int) (dm.heightPixels * 0.03f);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 24, 39));
        root.setGravity(Gravity.CENTER);
        root.setPadding(padX, padY, padX, padY);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        int[] dots = {0xFFEF5350, 0xFFFFCA28, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC};
        LinearLayout deco = new LinearLayout(ctx);
        deco.setOrientation(LinearLayout.HORIZONTAL);
        deco.setGravity(Gravity.CENTER);
        for (int col : dots) {
            View dot = new View(ctx);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(col);
            dot.setBackground(dg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(10), px(10));
            lp.setMargins(px(6), 0, px(6), px(10));
            dot.setLayoutParams(lp);
            deco.addView(dot);
        }
        root.addView(deco, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView badge = new TextView(ctx);
        badge.setText("  家长控制  ");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20));
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(0xFFB42318);
        badge.setBackground(bg);
        badge.setPadding(px(10), px(2), px(10), px(2));
        root.addView(badge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(52));
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, px(4), 0, px(2));
        root.addView(title, tlp);

        // ---- 倒计时 ----------------------------------------------------
        countdownCap = new TextView(ctx);
        countdownCap.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        countdownCap.setTextColor(Color.rgb(143, 179, 208));
        countdownCap.setGravity(Gravity.CENTER);
        countdownCap.setSingleLine(true);
        root.addView(countdownCap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        countdown = new TextView(ctx);
        countdown.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(58));
        countdown.setTextColor(Color.rgb(255, 209, 102));
        countdown.setGravity(Gravity.CENTER);
        countdown.setSingleLine(true);
        countdown.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, px(8));
        root.addView(countdown, clp);

        body = new TextView(ctx);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        body.setTextColor(Color.rgb(255, 209, 102));
        body.setGravity(Gravity.CENTER);
        body.setMaxLines(3);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, px(2), 0, px(4));
        root.addView(body, blp);

        // ---- 最近 7 天天气 --------------------------------------------
        weatherPanel = new LinearLayout(ctx);
        weatherPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable wpBg = new GradientDrawable();
        wpBg.setCornerRadius(px(16));
        wpBg.setColor(0xFF1B2740);
        wpBg.setStroke(Math.max(1, px(1)), 0xFF33445F);
        weatherPanel.setBackground(wpBg);
        weatherPanel.setPadding(px(14), px(7), px(14), px(5));

        weatherHead = new TextView(ctx);
        weatherHead.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        weatherHead.setTextColor(Color.WHITE);
        weatherHead.setGravity(Gravity.CENTER);
        weatherHead.setSingleLine(true);
        weatherPanel.addView(weatherHead, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        weatherRow = new LinearLayout(ctx);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.CENTER);
        weatherPanel.addView(weatherRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        weatherFoot = new TextView(ctx);
        weatherFoot.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        weatherFoot.setTextColor(Color.rgb(143, 179, 208));
        weatherFoot.setGravity(Gravity.CENTER);
        weatherFoot.setSingleLine(true);
        weatherPanel.addView(weatherFoot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, px(6), 0, px(8));
        root.addView(weatherPanel, wlp);
        weatherPanel.setVisibility(View.GONE);

        hint = new TextView(ctx);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        hint.setTextColor(Color.rgb(143, 179, 208));
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(2);
        hint.setText("遥控器按键无效 · 请在手机上点「解锁电视」继续");
        root.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Swallow EVERY key. HOME is handled by the system, but because this
        // is a global overlay it simply stays on top of whatever appears.
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                return true;
            }
        });
        root.post(new Runnable() {
            @Override public void run() { root.requestFocus(); }
        });
    }

    private TextView cell(String text, float size, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int parseColor(String s, int fallback) {
        try { return Color.parseColor(s); } catch (Exception e) { return fallback; }
    }

    /** Called on the UI thread only. Rebuilds the 7 cards only when data changes. */
    private void applyWeather() {
        if (weatherPanel == null || weatherRow == null) {
            return;
        }
        final JSONObject w = weatherData;
        if (w == null || !w.optBoolean("enabled", true)) {
            weatherPanel.setVisibility(View.GONE);
            return;
        }
        weatherPanel.setVisibility(View.VISIBLE);
        JSONArray days = w.optJSONArray("days");
        JSONObject cur = w.optJSONObject("current");
        int n = days == null ? 0 : days.length();

        String city = w.optString("city", "");
        if (city.isEmpty()) {
            city = w.optString("city_name", "");
        }
        String head = (city.isEmpty() ? "" : city + " · ") + "最近 7 天天气";
        if (cur != null) {
            String temp = cur.optString("temp", "");
            String q = cur.optString("quality", "");
            if (!temp.isEmpty()) {
                head += "   当前 " + temp + "°";
            }
            if (!q.isEmpty()) {
                head += " · 空气" + q;
            }
        }
        weatherHead.setText(head);

        String upd = w.optString("updated", "");
        if (upd.length() >= 16) {
            upd = upd.substring(5, 16);
        }
        String foot;
        if (!w.optBoolean("ok", false)) {
            foot = "天气数据加载中…";
        } else if (w.optBoolean("stale", false)) {
            foot = "网络暂不可用 · 显示上次数据" + (upd.isEmpty() ? "" : "（" + upd + "）");
        } else {
            foot = "更新于 " + upd;
        }
        weatherFoot.setText(foot);

        String stamp = city + "|" + w.optString("updated", "") + "|" + n
                + "|" + (w.optBoolean("stale", false) ? 1 : 0);
        if (stamp.equals(weatherStamp)) {
            return;
        }
        weatherStamp = stamp;
        weatherRow.removeAllViews();

        for (int i = 0; i < 7; i++) {
            JSONObject d = days != null && i < n ? days.optJSONObject(i) : null;
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(12));
            g.setColor(i == 0 ? 0xFF2C4570 : 0xFF22314C);
            if (i == 0) {
                g.setStroke(Math.max(1, px(1)), 0xFF64B5F6);
            }
            card.setBackground(g);
            card.setPadding(px(2), px(5), px(2), px(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(px(3), px(4), px(3), px(2));
            weatherRow.addView(card, lp);

            if (d == null) {
                card.addView(cell("--", 16, Color.WHITE, true));
                card.addView(cell("--", 13, 0xFF8FB3D0, false));
                card.addView(cell("待更新", 19, 0xFF8FB3D0, true));
                card.addView(cell("--°", 16, Color.WHITE, true));
                continue;
            }
            int tint = parseColor(d.optString("color", "#CFD8DC"), 0xFFCFD8DC);
            String hi = d.optString("high", ""), lo = d.optString("low", "");
            card.addView(cell(d.optString("label", ""), 16,
                    i == 0 ? Color.rgb(129, 199, 255) : Color.WHITE, true));
            card.addView(cell(d.optString("date", ""), 13, 0xFF8FB3D0, false));
            card.addView(cell(d.optString("type", "--"), 21, tint, true));
            card.addView(cell((hi.isEmpty() ? "--" : hi) + "° / "
                    + (lo.isEmpty() ? "--" : lo) + "°", 16, Color.WHITE, true));
            card.addView(cell(d.optString("wind", ""), 12, 0xFF8FB3D0, false));
        }
    }

    public synchronized void setWeather(JSONObject w) {
        weatherData = w;
        ui.post(new Runnable() {
            @Override public void run() { applyWeather(); }
        });
    }

    public synchronized void show(String t, String b) {
        show(t, b, 0, "");
    }

    public synchronized void show(String t, String b, long deadline) {
        show(t, b, deadline, deadline > 0 ? "距离自动解锁还有" : "");
    }

    public synchronized void show(String t, String b, long deadline, String cap) {
        boolean fresh = !showing || root == null;
        if (fresh) {
            build();
            try {
                wm.addView(root, params());
                showing = true;
            } catch (Exception e) {
                showing = false;
                return;
            }
        }
        boolean restart = fresh || deadlineMs != deadline;
        deadlineMs = deadline;
        countdownLabel = cap == null ? "" : cap;
        update(t, b);
        applyWeather();
        if (restart) {
            ui.removeCallbacks(clockTick);
        }
        updateClock();
        if (restart && showing) {
            ui.postDelayed(clockTick, 1000);
        }
    }

    private void updateClock() {
        if (countdown == null || countdownCap == null) {
            return;
        }
        // A manual lock has no end time: keep the same block but never show a
        // misleading running countdown.
        if (deadlineMs <= 0) {
            countdownCap.setVisibility(View.GONE);
            countdown.setVisibility(View.VISIBLE);
            countdown.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34));
            countdown.setText(countdownLabel.isEmpty()
                    ? "等待家长解锁" : countdownLabel);
            countdown.setTextColor(Color.rgb(143, 179, 208));
            return;
        }
        countdownCap.setVisibility(View.VISIBLE);
        countdown.setVisibility(View.VISIBLE);
        countdown.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(58));
        long now = System.currentTimeMillis();
        long ms;
        if (deadlineMs > now) {
            ms = deadlineMs - now;
            countdownCap.setText(countdownLabel.isEmpty()
                    ? "距离自动解锁还有" : countdownLabel);
            countdown.setTextColor(Color.rgb(255, 209, 102));
        } else {
            ms = 0;
            countdownCap.setText("即将解锁");
            countdown.setTextColor(Color.rgb(129, 220, 160));
        }
        countdown.setText(hms(ms));
    }

    private static String hms(long ms) {
        long s = ms / 1000L;
        long h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        return h > 0
                ? String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, sec)
                : String.format(Locale.CHINA, "%02d:%02d", m, sec);
    }

    public synchronized void update(String t, String b) {
        if (!showing || root == null) {
            return;
        }
        final String ft = t, fb = b;
        ui.post(new Runnable() {
            @Override public void run() {
                if (title != null) {
                    title.setText(ft);
                }
                if (body != null) {
                    body.setText(fb);
                }
            }
        });
    }

    public synchronized void dismiss() {
        if (showing && root != null) {
            try { wm.removeViewImmediate(root); } catch (Exception ignored) { }
        }
        ui.removeCallbacks(clockTick);
        showing = false;
        root = null;
        countdown = null;
        countdownCap = null;
        deadlineMs = 0;
        weatherPanel = null;
        weatherRow = null;
        weatherHead = null;
        weatherFoot = null;
        weatherStamp = "";
    }
}
