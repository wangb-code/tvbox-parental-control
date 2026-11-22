package com.tvbox.parental;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;

/**
 * Full-screen quiz drawn by the background service as an overlay.
 *
 * This vendor firmware force-stops any app whose foreground Activity goes
 * away, so the quiz must never be an Activity.
 *
 * Layout rules (important on TVs):
 *  - reserve an overscan safe area (~7.5% horizontal, ~5.5% vertical): TV
 *    panels crop the outer edge, so 64px of padding is not enough;
 *  - every growable block uses layout_weight, so total height always equals
 *    the screen height. Nothing can overflow regardless of density, font
 *    scale or question length;
 *  - text sizes are set in real pixels derived from the screen size, never sp.
 */
public class QuizOverlay {

    public interface Callback {
        void onAnswer(String choice);
        /** Any remote-key activity while the quiz is on screen. */
        void onKeyActivity();
        /** Fired after the overlay window is really removed (focus restored). */
        void onDismissed();
    }

    private static final int[] CARD_BORDER = {
            0xFFEF5350, 0xFF42A5F5, 0xFF66BB6A, 0xFFFFA726   // red / blue / green / orange
    };
    private static final int CARD_BG = 0xFF1B3A57;
    private static final int CARD_BG_SELECTED = 0xFF2F6191;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> queue = new ArrayList<>();
    private MediaPlayer mp;

    private LinearLayout root;
    private TextView badge, titleView, question, tip;
    private ImageView imageView;
    private final TextView[] opts = new TextView[4];
    private int selected = 0;
    private boolean showing = false;
    private boolean leaving = false;
    private boolean voiceEnabled = true;
    private String shownId = "";
    /** Spoken before the first question of a round only; cleared on a new question. */
    private String introClip = "";
    private String remainText = "";
    private QuizBank.Q current;
    private Callback cb;
    private float scale = 1f;

    public QuizOverlay(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        scale = Math.min(dm.widthPixels / 1920f, dm.heightPixels / 1080f);
        if (scale <= 0f) {
            scale = 1f;
        }
    }

    public synchronized boolean isShowing() {
        return showing;
    }

    /** Two short lines showing the remaining session / daily quota. */
    public synchronized void setRemain(String text) {
        remainText = text == null ? "" : text;
        if (showing && tip != null) {
            ui.post(new Runnable() {
                @Override public void run() {
                    if (tip == null) {
                        return;
                    }
                    if (remainText.isEmpty()) {
                        tip.setVisibility(View.GONE);
                    } else {
                        tip.setText(remainText);
                        tip.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
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

    /** sp-like value converted to real pixels, scaled to the actual panel. */
    private int px(float sp) {
        return (int) (sp * scale
                * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public synchronized void show(QuizBank.Q q, boolean voice, Callback callback,
                                 String introClipName) {
        cb = null;              // suppress onDismissed for a previous question
        dismissInternal();
        current = q;
        voiceEnabled = voice;
        cb = callback;
        selected = 0;
        leaving = false;
        buildView();
        try {
            wm.addView(root, params());
            showing = true;
        } catch (Exception e) {
            showing = false;
            return;
        }
        introClip = introClipName == null ? "" : introClipName;
        refresh();
        if (introClip.isEmpty()) {
            playChain(q.voiceId);
        } else {
            playChain(introClip, q.voiceId);
        }
    }

    private LinearLayout.LayoutParams fixedRow() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void buildView() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int padX = (int) (dm.widthPixels * 0.075f);
        int padY = (int) (dm.heightPixels * 0.055f);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 27, 42));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padX, padY, padX, padY);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        // Colourful decoration row - playful, not exam-like.
        LinearLayout deco = new LinearLayout(ctx);
        deco.setOrientation(LinearLayout.HORIZONTAL);
        deco.setGravity(Gravity.CENTER);
        int[] dots = {0xFFEF5350, 0xFFFFCA28, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC};
        for (int col : dots) {
            View dot = new View(ctx);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(col);
            dot.setBackground(dg);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(px(9), px(9));
            lp.setMargins(px(5), 0, px(5), 0);
            dot.setLayoutParams(lp);
            deco.addView(dot);
        }
        root.addView(deco, fixedRow());

        badge = new TextView(ctx);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19));
        badge.setTextColor(Color.WHITE);
        badge.setPadding(px(10), px(2), px(10), px(2));
        badge.setGravity(Gravity.CENTER);
        root.addView(badge, fixedRow());

        titleView = new TextView(ctx);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        titleView.setTextColor(Color.rgb(255, 209, 102));
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(1);
        root.addView(titleView, fixedRow());

        question = new TextView(ctx);
        question.setTextColor(Color.WHITE);
        question.setGravity(Gravity.CENTER);
        question.setPadding(px(4), px(2), px(4), px(2));
        question.setSingleLine(false);
        question.setMaxLines(2);
        root.addView(question, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.35f));

        imageView = new ImageView(ctx);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        root.addView(imageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.35f));

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 2; i++) {
                TextView t = new TextView(ctx);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(Color.WHITE);
                t.setPadding(px(6), px(4), px(6), px(4));
                t.setMaxLines(2);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(px(8), px(5), px(8), px(5));
                t.setLayoutParams(lp);
                opts[r * 2 + i] = t;
                row.addView(t);
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.5f));
        }

        tip = new TextView(ctx);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19));
        tip.setTextColor(Color.rgb(143, 179, 208));
        tip.setGravity(Gravity.CENTER);
        tip.setMaxLines(2);
        root.addView(tip, fixedRow());

        root.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_HOME
                        || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_MUTE) {
                    return false;
                }
                if (leaving) {
                    return true;
                }
                if (cb != null) cb.onKeyActivity();
                // Some remotes do have number keys; direction keys are primary.
                if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4) {
                    selected = keyCode - KeyEvent.KEYCODE_1;
                    highlight();
                    submit();
                    return true;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_UP:
                        selected = (selected + 3) % 4;
                        highlight();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        selected = (selected + 1) % 4;
                        highlight();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        submit();
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_0:
                    case KeyEvent.KEYCODE_MEDIA_REWIND:
                        replay();
                        return true;
                    default:
                        return true;
                }
            }
        });
        root.post(new Runnable() {
            @Override public void run() { root.requestFocus(); }
        });
    }

    private void refresh() {
        if (current == null || root == null) {
            return;
        }
        shownId = current.voiceId;
        badge.setText("  " + current.subject + "  ");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(subjectColor(current.subject));
        badge.setBackground(bg);

        titleView.setText(current.title != null && !current.title.isEmpty()
                ? current.title : "休息时间 · 答对继续看");
        titleView.setTextColor(Color.rgb(255, 209, 102));

        if (current.image != null && !current.image.isEmpty()) {
            question.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            try {
                java.io.InputStream is = ctx.getAssets().open("images/" + current.image + ".png");
                android.graphics.Bitmap bm = BitmapFactory.decodeStream(is);
                is.close();
                imageView.setImageBitmap(bm);
            } catch (Exception e) {
                imageView.setVisibility(View.GONE);
                question.setVisibility(View.VISIBLE);
                question.setText(current.q);
            }
        } else {
            imageView.setVisibility(View.GONE);
            question.setVisibility(View.VISIBLE);
            question.setText(current.q);
        }
        String s = current.q == null ? "" : current.q;
        float qSize = current.big ? 58 : 46;
        if (s.length() > 34) qSize *= 0.72f;
        else if (s.length() > 24) qSize *= 0.82f;
        else if (s.length() > 16) qSize *= 0.92f;
        question.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(qSize));
        int qc = current.big ? Color.rgb(255, 209, 102) : Color.WHITE;
        if (current.qcolor != null && current.qcolor.startsWith("#")) {
            try { qc = Color.parseColor(current.qcolor); } catch (Exception ignored) { }
        }
        question.setTextColor(qc);

        int maxLen = 0;
        for (int i = 0; i < 4; i++) {
            String value = i < current.options.length ? current.options[i] : "";
            maxLen = Math.max(maxLen, value.length());
            // A coloured, bold "1. " badge keeps the option NUMBER visually
            // separate from its CONTENT. Without the dot, "1   5" reads like
            // the number 15 and gets confused with a maths question.
            String label = (i + 1) + ".  ";
            SpannableString sp = new SpannableString(label + value);
            sp.setSpan(new ForegroundColorSpan(CARD_BORDER[i]), 0, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new RelativeSizeSpan(1.12f), 0, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            opts[i].setText(sp);
            String col = (current.colors != null && i < current.colors.length)
                    ? current.colors[i] : null;
            opts[i].setTag(col);
        }
        float oSize = current.big ? 40 : 30;
        if (maxLen > 14) oSize *= 0.72f;
        else if (maxLen > 8) oSize *= 0.82f;
        for (int i = 0; i < 4; i++) {
            opts[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, px(oSize));
        }
        // No remote-control tutorial. Reuse the row for the remaining quota.
        if (remainText.isEmpty()) {
            tip.setVisibility(View.GONE);
        } else {
            tip.setText(remainText);
            tip.setVisibility(View.VISIBLE);
        }
        highlight();
    }

    private static int subjectColor(String s) {
        if ("数学".equals(s)) return 0xFF42A5F5;
        if ("英语".equals(s)) return 0xFF66BB6A;
        if ("语文".equals(s) || "拼音".equals(s)) return 0xFFFFA726;
        if ("图形".equals(s)) return 0xFFAB47BC;
        return 0xFFFFD166;
    }

    private void highlight() {
        for (int i = 0; i < 4; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(9));
            g.setColor(i == selected ? CARD_BG_SELECTED : CARD_BG);
            g.setStroke(px(i == selected ? 3 : 2),
                    i == selected ? Color.WHITE : CARD_BORDER[i]);
            opts[i].setBackground(g);

            String col = opts[i].getTag() instanceof String ? (String) opts[i].getTag() : null;
            if (col != null && col.startsWith("#")) {
                try {
                    opts[i].setTextColor(Color.parseColor(col));
                } catch (Exception e) {
                    opts[i].setTextColor(Color.WHITE);
                }
            } else {
                opts[i].setTextColor(i == selected ? Color.rgb(255, 209, 102) : Color.WHITE);
            }
        }
    }

    private void submit() {
        if (cb != null) {
            cb.onAnswer(String.valueOf("ABCD".charAt(selected)));
        }
    }

    public synchronized void newQuestion(QuizBank.Q q) {
        current = q;
        selected = 0;
        leaving = false;
        introClip = "";   // do not repeat the reminder on a replacement question
        refresh();
        playChain(q.voiceId);
    }

    /** Correct, but the round still needs another answer: keep the lock on. */
    public synchronized void onNext(QuizBank.Q q, String text) {
        if (!showing || root == null || titleView == null) {
            if (q != null) {
                newQuestion(q);
            }
            return;
        }
        leaving = true;
        titleView.setText(text);
        titleView.setTextColor(Color.rgb(102, 187, 106));
        playChain("right");
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (showing && q != null) {
                    leaving = false;
                    newQuestion(q);
                }
            }
        }, 2200);
    }

    public synchronized void onResult(String result) {
        if ("correct".equals(result)) {
            leaving = true;
            if (!showing || root == null || titleView == null) {
                // Answer came from the phone while no quiz window was up.
                // There will be no dismiss() event, so release playback here.
                playChain("right");
                final Callback c = cb;
                if (c != null) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            try { c.onDismissed(); } catch (Exception ignored) { }
                        }
                    });
                }
                return;
            }
            titleView.setText("答对啦，真棒！");
            titleView.setTextColor(Color.rgb(102, 187, 106));
            playChain("right");
            ui.postDelayed(new Runnable() {
                @Override public void run() { dismiss(); }
            }, 1900);
        } else if (!leaving) {
            playChain("wrong", shownId);
        }
    }

    public synchronized void replay() {
        if (leaving || !showing) {
            return;
        }
        playChain(shownId);
    }

    public synchronized void dismiss() {
        leaving = true;
        dismissInternal();
    }

    private void dismissInternal() {
        stopAudio();
        boolean removed = false;
        if (showing && root != null) {
            try {
                wm.removeViewImmediate(root);
                removed = true;
            } catch (Exception ignored) { }
        }
        showing = false;
        root = null;
        if (removed) {
            final Callback old = cb;
            if (old != null) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        try { old.onDismissed(); } catch (Exception ignored) { }
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------- voice
    private void playChain(String... names) {
        stopAudio();
        if (!voiceEnabled) {
            return;
        }
        for (String n : names) {
            if (n != null && !n.isEmpty()) {
                queue.add(n);
            }
        }
        nextClip();
    }

    private void stopAudio() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        queue.clear();
    }

    private void nextClip() {
        if (queue.isEmpty()) {
            return;
        }
        final String name = queue.remove(0);
        File f = new File(ctx.getCacheDir(), "audio/" + name + ".mp3");
        if (!f.exists()) {
            nextClip();
            return;
        }
        try {
            final MediaPlayer player = new MediaPlayer();
            mp = player;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    try { m.release(); } catch (Exception ignored) { }
                    if (mp == m) {
                        mp = null;
                    }
                    ui.post(new Runnable() {
                        @Override public void run() { nextClip(); }
                    });
                }
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            mp = null;
            nextClip();
        }
    }
}
