package com.tvbox.parental;

import android.media.AudioManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The whole parental-control system lives here, on the box itself.
 * No PC, no phone app: the phone is only a browser pointing at port 8765.
 */
public class ControlService extends Service {

    public static final int PORT = 8765;
    /** Bump this on every released build; exposed via /api/status for verification. */
    public static final String APP_VERSION = "7.13";
    public static volatile ControlService INSTANCE;

    private static final String PREFS = "tvctl";
    private static final String CHANNEL = "tvctl";
    private static final Object LOCK = new Object();

    // Anti-guessing: 2 wrong answers on the SAME question swap the question.
    // Separately, 3 cumulative wrong answers in the current quiz round (cleared
    // only after the child answers 2 correct to finish the round) trigger a
    // penalty lock, then a fresh question. Penalty grows linearly 1, 2, 3...
    // minutes with no cap, so repeated guessing gets progressively costly.
    // Counters are persisted.
    private static final int WRONG_PER_QUESTION = 2;
    private static final int MAX_FAILED_QUESTIONS = 3;
    /** Correct answers needed to lift one quiz lock (anti-guessing unchanged). */
    private static final int QUIZ_REQUIRED = 2;

    /** Penalty N lasts N minutes (1, 2, 3, ...), uncapped. */
    private static long penaltyMillis(int count) {
        return Math.max(1, count) * 60000L;
    }

    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        KEYS.put("UP", 19); KEYS.put("DOWN", 20); KEYS.put("LEFT", 21);
        KEYS.put("RIGHT", 22); KEYS.put("OK", 23); KEYS.put("BACK", 4);
        KEYS.put("HOME", 3); KEYS.put("MENU", 82); KEYS.put("VOL_UP", 24);
        KEYS.put("VOL_DOWN", 25); KEYS.put("MUTE", 164); KEYS.put("POWER", 26);
        KEYS.put("WAKEUP", 224); KEYS.put("PLAY_PAUSE", 85); KEYS.put("PAUSE", 127);
        KEYS.put("PLAY", 126); KEYS.put("REW", 89); KEYS.put("FFWD", 90);
        KEYS.put("DEL", 67); KEYS.put("ENTER", 66);
        String digits = "0123456789";
        for (int i = 0; i < 10; i++) {
            KEYS.put(String.valueOf(digits.charAt(i)), 7 + i);
        }
    }

    private SharedPreferences prefs;
    private Handler handler;
    private QuizBank bank;
    private ServerSocket server;
    private QuizOverlay overlay;
    private LockOverlay lockOverlay;
    private LockVoice lockVoice;
    private Weather weather;

    private final QuizOverlay.Callback answerCallback = new QuizOverlay.Callback() {
        @Override public void onAnswer(String choice) { answer(choice); }
        @Override public void onKeyActivity() { lastQuizActivityAt = System.currentTimeMillis(); }
        @Override public void onDismissed() { onQuizWindowGone(); }
    };

    private final Object PLAYBACK_LOCK = new Object();
    private volatile boolean playbackPausedByUs = false;

    // All mutable state is guarded by LOCK and mirrored into prefs.
    // limitMinutes = quiz interval (soft lock, answer correctly to continue).
    // sessionLimitMinutes / restMinutes / dailyLimitMinutes = HARD limits,
    // which answering a quiz can never reset.
    private int limitMinutes = 5;
    private int sessionLimitMinutes = 30;
    private int dailyLimitMinutes = 120;
    private int restMinutes = 60;
    private String subjectsCsv = "math,english,pinyin,visual";
    // "wrong_first" (default, weighted by net mistakes) or "random".
    private String pickMode = "wrong_first";
    private String rebootTime = "01:00";
    private boolean rebootEnabled = true;
    /** ISO weekdays, 1=Mon ... 7=Sun; default Sunday only. */
    private String rebootDays = "7";
    private boolean controlEnabled = true;   // master switch for hard limits
    private boolean voiceEnabled = true;
    private boolean lockVoiceEnabled = true;   // spoken reason when TV locks
    private boolean quizEnabled = true;
    // Watch time only counts while media is actually playing, and the parent
    // can pause the timer by hand (TV switched off but the box keeps running).
    // This panel's TV does not answer HDMI-CEC <Report Power Status>, so the
    // box cannot sense the TV being switched off by itself.
    private boolean timerPausedByUser = false;
    private boolean pauseOnNoPlayback = true;
    // "Nobody answered for 1 minute" hint: the TV is very likely switched off.
    // Mute everything (independent of the hard-lock volume) and keep the timer
    // frozen until someone touches the remote or answers again.
    private static final long AWAY_TIMEOUT_MS = 60 * 1000L;
    private volatile long lastQuizActivityAt = 0;
    private volatile boolean awayNoOneWatching = false;
    private int awaySavedVolume = -1;
    // --- device telemetry for the 控制台 dashboard -------------------------
    // Foreground-app probing was removed: the label was not reliable enough.
    // Network type/IP are cached; rx/tx counters are refreshed per status call.
    private volatile String netType = "";
    private volatile boolean netOnline = false;
    private volatile String netIp = "";
    private volatile long netProbeAt = 0;
    private volatile boolean trafficSupported = true;
    private volatile long trafficLastRx = -1, trafficLastTx = -1, trafficLastAt = 0;
    private volatile long trafficRxBps = 0, trafficTxBps = 0;
    private volatile long trafficRxTotal = 0, trafficTxTotal = 0;
    // Quiz voice was too quiet at the normal volume: temporarily raise MUSIC
    // to 80% while a question is on screen, restore when the overlay closes.
    private int quizSavedVolume = -1;
    private int quizTargetVolume = -1;
    private boolean currentReview = false;
    // Wrong-answer notebook: {"items":{<questionId>:{...}}, "ok":n, "bad":n}.
    // Kept as one JSON blob in prefs so it survives reboots and app updates.
    private JSONObject wrongBook = new JSONObject();
    // Question ids the parent switched off in 题库. Persisted so a reboot or
    // an app update never silently re-enables an "too easy" question.
    private final Set<String> disabledIds = new LinkedHashSet<>();
    private int quizSeconds = 0;
    private int sessionSeconds = 0;
    private int todaySeconds = 0;
    private String todayDate = "";
    private String lastRebootDate = "";
    private boolean manualLocked = false;
    private long restUntilMillis = 0;
    private boolean dailyOverride = false;
    /** Explicit daily-cap lock: only a parent release or the configured
     *  recovery time clears it. It survives date rollover and clock resets. */
    private boolean dailyLocked = false;
    private long dailyUnlockAt = 0;
    private String dailyResetTime = "06:00";
    private long lastSeenMillis = 0;
    private String note = "服务已启动";
    private boolean locked = false;
    private int wrong = 0;
    private int quizCorrect = 0;   // correct answers in the current quiz round
    private int consecutiveFailedQuestions = 0;
    private int penaltyCount = 0;
    private long penaltyUntilMillis = 0;
    private int savedMusicVolume = -1;
    private long hardLockStartedAt = 0;   // episode marker for the spoken notice
    private QuizBank.Q current = null;
    private volatile boolean quizActive = false;
    private volatile long lastLaunchAt = 0;

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        loadPrefs();
        bank = QuizBank.load(this);
        overlay = new QuizOverlay(this);
        lockOverlay = new LockOverlay(this);
        lockVoice = new LockVoice(this, new Runnable() {
            @Override public void run() { reconcileVolumeAfterVoice(); }
        });
        lockVoice.setEnabled(lockVoiceEnabled);
        weather = new Weather(prefs, new Runnable() {
            @Override public void run() {
                handler.post(new Runnable() {
                    @Override public void run() { lockOverlay.setWeather(weather.view()); }
                });
            }
        });
        weather.maybeRefresh(false);
        copyAudioAssets();
        foregroundNotification();
        scheduleKeepAlive(this);
        KeepAliveReceiver.schedule(this);   // 20s alarm watchdog
        startHttp();
        handler.postDelayed(ticker, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private void foregroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "家长控制", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("观看计时与答题控制在后台运行");
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("家长控制运行中")
                .setContentText("手机访问 http://" + localIp() + ":" + PORT)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    /**
     * A short, re-arming watchdog. It is persisted by the system, so after a
     * reboot it comes back and starts the service even though this vendor
     * firmware blocks third-party BOOT_COMPLETED receivers.
     */
    public static void scheduleKeepAlive(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            JobInfo info = new JobInfo.Builder(1001,
                    new ComponentName(c, KeepAliveJob.class))
                    .setPersisted(true)
                    .setMinimumLatency(30 * 1000L)
                    .setOverrideDeadline(60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) { }
    }

    private String localIp() {
        // Shown only as a hint in the notification. Discover this device's
        // LAN address at runtime so the project works on any network.
        try {
            java.util.Enumeration<NetworkInterface> nics =
                    NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface ni = nics.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "电视盒IP";
    }

    /** ISO weekday of today: 1=Monday ... 7=Sunday. */
    private static int isoDayOfWeek() {
        int c = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
        return c == java.util.Calendar.SUNDAY ? 7 : c - 1;
    }

    private java.util.Set<Integer> rebootDaySet() {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for (String p : rebootDays.split(",")) {
            try {
                int d = Integer.parseInt(p.trim());
                if (d >= 1 && d <= 7) {
                    s.add(d);
                }
            } catch (Exception ignored) { }
        }
        return s;
    }

    private JSONArray rebootDaysArray() {
        JSONArray a = new JSONArray();
        for (Integer d : new java.util.TreeSet<>(rebootDaySet())) {
            a.put(d);
        }
        return a;
    }

    private void loadPrefs() {
        synchronized (LOCK) {
            limitMinutes = prefs.getInt("limit_minutes", 5);
            sessionLimitMinutes = prefs.getInt("session_limit_minutes", 30);
            dailyLimitMinutes = prefs.getInt("daily_limit_minutes", 120);
            restMinutes = prefs.getInt("rest_minutes", 60);
            subjectsCsv = prefs.getString("subjects", "math,english,pinyin,visual");
            String pm = prefs.getString("pick_mode", "wrong_first");
            pickMode = "random".equals(pm) ? "random" : "wrong_first";
            rebootTime = prefs.getString("reboot_time", "01:00");
            rebootEnabled = prefs.getBoolean("reboot_enabled", true);
            rebootDays = prefs.getString("reboot_days", "7");
            controlEnabled = prefs.getBoolean("control_enabled", true);
            voiceEnabled = prefs.getBoolean("voice_enabled", true);
            lockVoiceEnabled = prefs.getBoolean("lock_voice_enabled", true);
            quizEnabled = prefs.getBoolean("quiz_enabled", true);
            timerPausedByUser = prefs.getBoolean("timer_paused_by_user", false);
            pauseOnNoPlayback = prefs.getBoolean("pause_on_no_playback", true);
            awaySavedVolume = prefs.getInt("away_saved_volume", -1);
            String wbRaw = prefs.getString("wrong_book", "");
            if (!wbRaw.isEmpty()) {
                try {
                    wrongBook = new JSONObject(wbRaw);
                } catch (Exception ignored) {
                    wrongBook = new JSONObject();
                }
            }
            disabledIds.clear();
            String dq = prefs.getString("disabled_questions", "");
            if (!dq.isEmpty()) {
                try {
                    JSONArray da = new JSONArray(dq);
                    for (int i = 0; i < da.length(); i++) {
                        String v = da.optString(i, "");
                        if (!v.isEmpty()) {
                            disabledIds.add(v);
                        }
                    }
                } catch (Exception ignored) {
                    // Tolerate an older comma-separated value.
                    for (String v : dq.split(",")) {
                        if (!v.trim().isEmpty()) {
                            disabledIds.add(v.trim());
                        }
                    }
                }
            }
            quizSeconds = prefs.getInt("quiz_seconds", 0);
            sessionSeconds = prefs.getInt("session_seconds", 0);
            todaySeconds = prefs.getInt("today_seconds", 0);
            todayDate = prefs.getString("today_date", "");
            lastRebootDate = prefs.getString("last_reboot_date", "");
            manualLocked = prefs.getBoolean("manual_locked", false);
            restUntilMillis = prefs.getLong("rest_until_millis", 0);
            dailyOverride = prefs.getBoolean("daily_override", false);
            // v7.1: this flag used to waive the daily cap for the WHOLE
            // day. It now lasts for one session only. Clear any stale
            // whole-day waiver left by an earlier version, once.
            if (!prefs.getBoolean("daily_override_v71", false)) {
                dailyOverride = false;
                SharedPreferences.Editor mig = prefs.edit();
                mig.putBoolean("daily_override", false);
                mig.putBoolean("daily_override_v71", true);
                mig.apply();
            }
            dailyLocked = prefs.getBoolean("daily_locked", false);
            dailyUnlockAt = prefs.getLong("daily_unlock_at", 0L);
            dailyResetTime = prefs.getString("daily_reset_time", "06:00");
            lastSeenMillis = prefs.getLong("last_seen_millis", 0);
            wrong = prefs.getInt("wrong", 0);
            quizCorrect = prefs.getInt("quiz_correct", 0);
            consecutiveFailedQuestions = prefs.getInt("failed_questions", 0);
            penaltyCount = prefs.getInt("penalty_count", 0);
            penaltyUntilMillis = prefs.getLong("penalty_until_millis", 0);
            savedMusicVolume = prefs.getInt("saved_music_volume", -1);
            hardLockStartedAt = prefs.getLong("hard_lock_started_at", 0);
            note = "服务已启动，计时与答题记录从上次继续";
            locked = false;      // a quiz window never survives a restart
            current = null;
            quizCorrect = 0;

            long now = System.currentTimeMillis();
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
            // A power cut / NTP resync / manual clock change must never wipe
            // accumulated time. Shift any pending rest deadline by the same
            // amount so rebooting can't shorten the forced break either.
            boolean wentBack = lastSeenMillis > 0 && now < lastSeenMillis - 60000L;
            if (wentBack) {
                long shift = lastSeenMillis - now;
                if (restUntilMillis > 0) {
                    restUntilMillis += shift;
                }
                if (penaltyUntilMillis > 0) {
                    penaltyUntilMillis += shift;
                }
                note = "检测到系统时间回拨，累计时间保持不变";
            }
            if (todayDate.isEmpty()) {
                todayDate = today;
            } else if (!today.equals(todayDate)) {
                if (today.compareTo(todayDate) > 0 && !wentBack) {
                    todayDate = today;
                    todaySeconds = 0;
                    sessionSeconds = 0;
                    quizSeconds = 0;
                    restUntilMillis = 0;
                    penaltyCount = 0;
                    dailyOverride = false;
                    lastRebootDate = "";
                    note = "新的一天，计时已重置";
                } else {
                    note = "系统日期异常，累计时间保持不变";
                }
            }
            lastSeenMillis = now;
            save();
        }
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt("limit_minutes", limitMinutes);
        e.putInt("session_limit_minutes", sessionLimitMinutes);
        e.putInt("daily_limit_minutes", dailyLimitMinutes);
        e.putInt("rest_minutes", restMinutes);
        e.putString("subjects", subjectsCsv);
        e.putString("pick_mode", pickMode);
        e.putString("reboot_time", rebootTime);
        e.putBoolean("reboot_enabled", rebootEnabled);
        e.putString("reboot_days", rebootDays);
        e.putBoolean("control_enabled", controlEnabled);
        e.putBoolean("voice_enabled", voiceEnabled);
        e.putBoolean("lock_voice_enabled", lockVoiceEnabled);
        e.putBoolean("quiz_enabled", quizEnabled);
        e.putBoolean("timer_paused_by_user", timerPausedByUser);
        e.putBoolean("pause_on_no_playback", pauseOnNoPlayback);
        e.putInt("away_saved_volume", awaySavedVolume);
        e.putString("wrong_book", wrongBook.toString());
        String disabledSnapshot;
        synchronized (LOCK) {
            disabledSnapshot = new JSONArray(disabledIds).toString();
        }
        e.putString("disabled_questions", disabledSnapshot);
        e.putInt("quiz_seconds", quizSeconds);
        e.putInt("session_seconds", sessionSeconds);
        e.putInt("today_seconds", todaySeconds);
        e.putString("today_date", todayDate);
        e.putString("last_reboot_date", lastRebootDate);
        e.putInt("wrong", wrong);
        e.putInt("quiz_correct", quizCorrect);
        e.putInt("failed_questions", consecutiveFailedQuestions);
        e.putInt("penalty_count", penaltyCount);
        e.putLong("penalty_until_millis", penaltyUntilMillis);
        e.putInt("saved_music_volume", savedMusicVolume);
        e.putLong("hard_lock_started_at", hardLockStartedAt);
        e.putBoolean("manual_locked", manualLocked);
        e.putLong("rest_until_millis", restUntilMillis);
        e.putBoolean("daily_override", dailyOverride);
        e.putBoolean("daily_locked", dailyLocked);
        e.putLong("daily_unlock_at", dailyUnlockAt);
        e.putString("daily_reset_time", dailyResetTime);
        e.putLong("last_seen_millis", lastSeenMillis);
        e.putString("note", note);
        e.apply();
    }

    /** MP3s are copied out of assets so MediaPlayer can use a plain file path. */
    private void copyAudioAssets() {
        try {
            File dir = new File(getCacheDir(), "audio");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String[] names = getAssets().list("audio");
            if (names == null) {
                return;
            }
            int copied = 0;
            java.util.Set<String> shipped = new java.util.HashSet<>();
            for (String name : names) {
                if (!name.endsWith(".mp3")) {
                    continue;
                }
                shipped.add(name);
                // Always overwrite: question ids are reassigned when the bank
                // is rebuilt, and skipping existing files made the box keep
                // playing audio recorded for a DIFFERENT question.
                InputStream in = getAssets().open("audio/" + name);
                FileOutputStream out = new FileOutputStream(new File(dir, name));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.close();
                in.close();
                copied++;
            }
            // Retire clips left behind by earlier builds (e.g. the removed
            // remote instructions) so the app never ships unused content.
            File[] old = dir.listFiles();
            int retired = 0;
            if (old != null) {
                for (File f : old) {
                    if (f.getName().endsWith(".mp3")
                            && !shipped.contains(f.getName()) && f.delete()) {
                        retired++;
                    }
                }
            }
            android.util.Log.i("TVCTL", "audio assets refreshed: " + copied
                    + ", retired: " + retired);
        } catch (Exception e) {
            android.util.Log.e("TVCTL", "copyAudioAssets failed", e);
        }
    }

    // ------------------------------------------------------------ background loop
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Exception ignored) { }
            handler.postDelayed(this, 3000);
        }
    };

    /** Next occurrence of the configured daily-cap recovery time. */
    private long nextDailyResetMillis(long now) {
        try {
            String[] p = dailyResetTime.split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            java.util.Calendar c = java.util.Calendar.getInstance(Locale.CHINA);
            c.setTimeInMillis(now);
            c.set(java.util.Calendar.HOUR_OF_DAY, h);
            c.set(java.util.Calendar.MINUTE, m);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= now) {
                c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            return c.getTimeInMillis();
        } catch (Exception e) {
            return now + 6 * 3600 * 1000L;
        }
    }

    /**
     * Absolute time when the current hard lock ends. 0 = no automatic end
     * (manual lock, or daily cap waiting for the parent).
     */
    private long lockDeadlineMillis(long now) {
        synchronized (LOCK) {
            if (manualLocked) {
                return 0;
            }
            if (penaltyUntilMillis > 0) {
                return penaltyUntilMillis;
            }
            if (restUntilMillis > 0) {
                return restUntilMillis;
            }
            // Daily cap has no automatic countdown: parent must release it.
            return 0;
        }
    }

    private void tick() {
        if (weather != null) {
            weather.maybeRefresh(false);   // internally throttled to 30 min
        }
        final long now = System.currentTimeMillis();
        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        boolean doReboot = false;
        boolean needQuiz = false;
        boolean relaunch = false;
        boolean hard = false;
        boolean penaltyEnded = false;
        boolean restJustEnded = false;
        boolean awayActive = false;
        String noticeClip = null, noticeKey = null;
        String lockTitle = "", lockBody = "", lockCap = "";
        long lockDeadline = 0;

        synchronized (LOCK) {
            // Clock integrity: a reboot / power cut / NTP resync must never
            // wipe accumulated time or shorten a running forced rest.
            boolean wentBack = lastSeenMillis > 0 && now < lastSeenMillis - 60000L;
            if (wentBack) {
                long shift = lastSeenMillis - now;
                if (restUntilMillis > 0) {
                    restUntilMillis += shift;
                }
                if (penaltyUntilMillis > 0) {
                    penaltyUntilMillis += shift;
                }
                note = "检测到系统时间回拨，累计时间保持不变";
            }
            lastSeenMillis = now;

            if (!today.equals(todayDate)) {
                if (today.compareTo(todayDate) > 0 && !wentBack) {
                    todayDate = today;
                    todaySeconds = 0;
                    sessionSeconds = 0;
                    quizSeconds = 0;
                    restUntilMillis = 0;
                    penaltyCount = 0;
                    dailyOverride = false;
                    lastRebootDate = "";
                    note = "新的一天，计时已重置";
                } else {
                    note = "系统日期异常，累计时间保持不变";
                }
            }

            // An explicit daily-cap lock never lifts merely because the date
            // rolled over or a countdown hit zero. Only the configured
            // recovery time or a parent unlock clears it.
            if (dailyLocked && dailyUnlockAt > 0 && now >= dailyUnlockAt) {
                dailyLocked = false;
                dailyUnlockAt = 0;
                dailyOverride = false;
                note = "每日上限已恢复，可以继续看啦";
            }

            if (rebootEnabled && rebootDaySet().contains(isoDayOfWeek())
                    && !today.equals(lastRebootDate)) {
                try {
                    String[] parts = rebootTime.split(":");
                    int target = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                    int cur = Integer.parseInt(
                            new SimpleDateFormat("HH", Locale.CHINA).format(new Date())) * 60
                            + Integer.parseInt(
                            new SimpleDateFormat("mm", Locale.CHINA).format(new Date()));
                    if (cur >= target && cur <= target + 2) {
                        lastRebootDate = today;
                        note = "执行定时重启";
                        doReboot = true;
                    }
                } catch (Exception ignored) { }
            }

            // A finished rest starts a brand-new session.
            if (restUntilMillis > 0 && now >= restUntilMillis) {
                restUntilMillis = 0;
                sessionSeconds = 0;
                quizSeconds = 0;
                note = "休息结束，可以继续看啦";
                restJustEnded = true;
            }

            // Penalty finished: clear it and immediately hand out a NEW quiz.
            if (penaltyUntilMillis > 0 && now >= penaltyUntilMillis) {
                penaltyUntilMillis = 0;
                consecutiveFailedQuestions = 0;
                wrong = 0;
                locked = false;
                current = null;
                note = "惩罚结束，请重新答一道题";
                penaltyEnded = true;
            }

            boolean penaltyLock = penaltyUntilMillis > 0;
            boolean restLock = restUntilMillis > 0;
            boolean dailyLock = controlEnabled && (dailyLocked
                    || (!dailyOverride
                        && todaySeconds >= dailyLimitMinutes * 60));
            hard = manualLocked || penaltyLock
                    || (controlEnabled && (restLock || dailyLock));

            // Time only accumulates while the screen is on, the parent has not
            // paused the timer, and (by default) media is actually playing.
            // A paused video no longer burns the child's quota; a TV switched
            // off while the box keeps running is handled by the manual pause
            // switch, because this panel's TV does not reply to HDMI-CEC.
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean awake = pm == null || pm.isInteractive();
            AudioManager amgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            boolean playing = amgr != null && amgr.isMusicActive();
            boolean counting = !hard && !locked && awake && !timerPausedByUser
                    && (!pauseOnNoPlayback || playing);
            if (counting) {
                quizSeconds += 3;
                sessionSeconds += 3;
                todaySeconds += 3;
            }

            // Hard limits take priority; answering a quiz never resets them.
            if (controlEnabled && !dailyOverride
                    && todaySeconds >= dailyLimitMinutes * 60) {
                hard = true;
                dailyLock = true;
                if (!dailyLocked) {
                    dailyLocked = true;
                    dailyUnlockAt = nextDailyResetMillis(now);
                }
                note = "今日累计时间已用完";
            }
            if (controlEnabled && !hard && !locked
                    && sessionSeconds >= sessionLimitMinutes * 60) {
                restUntilMillis = now + restMinutes * 60000L;
                restLock = true;
                hard = true;
                dailyOverride = false;   // one-session waiver ends here
                note = "单次观看时间到，开始休息";
            }
            if (!hard && !locked && quizEnabled
                    && quizSeconds >= limitMinutes * 60) {
                needQuiz = true;
            }
            if (locked && !penaltyLock && !overlay.isShowing()
                    && now - lastLaunchAt > 3500) {
                relaunch = true;
                lastLaunchAt = now;
            }

            if (hard) {
                if (manualLocked) {
                    lockTitle = "电视已锁定";
                    lockBody = "请在手机上点「解锁电视」继续";
                } else if (penaltyLock) {
                    lockTitle = "答错太多啦";
                    lockBody = "第 " + penaltyCount + " 次惩罚\n到点重新出题，不能瞎猜哦";
                    lockCap = "惩罚剩余时间";
                } else if (restLock) {
                    lockTitle = "休息时间到啦";
                    lockBody = "到点自动解锁";
                    lockCap = "休息时间还剩";
                } else {
                    lockTitle = "今天看够啦";
                    lockBody = "请在手机上点「解锁电视」放行";
                    lockCap = "等待家长放行";
                }
                // One spoken explanation per lock episode, never repeated on
                // every 3 s tick. The marker is persisted; after a reboot the
                // fresh process announces once more why the TV is still off.
                String reason = manualLocked ? "manual"
                        : (penaltyLock ? "penalty" : (restLock ? "rest" : "daily"));
                if (hardLockStartedAt == 0) {
                    hardLockStartedAt = now;
                }
                noticeClip = "lock_" + reason;
                noticeKey = reason + ":" + hardLockStartedAt;
                lockDeadline = lockDeadlineMillis(now);
            } else {
                if (hardLockStartedAt != 0 && restJustEnded) {
                    noticeClip = "unlock_ready";
                    noticeKey = "unlock:" + now;
                }
                hardLockStartedAt = 0;
            }
            awayActive = !hard && locked && overlay.isShowing()
                    && now - lastQuizActivityAt > AWAY_TIMEOUT_MS;
            save();
        }

        // Safety net: if the overlay dismiss callback was missed, still undo
        // the temporary quiz volume boost on the next 3-second tick.
        if (!overlay.isShowing() && quizSavedVolume >= 0) {
            restoreQuizVolume();
        }

        if (doReboot) {
            new Thread(new Runnable() {
                @Override public void run() { AdbLocal.reboot(); }
            }).start();
            return;
        }

        // Spoken explanation uses STREAM_ALARM, so it is audible even though
        // a hard lock mutes STREAM_MUSIC. De-duplication is in LockVoice.
        if (noticeClip != null && lockVoiceEnabled) {
            lockVoice.announce(noticeClip, noticeKey);
        }

        // "Nobody answered for a minute" -> silence everything until someone
        // touches the remote. Uses its own saved volume so it never fights
        // with the hard-lock mute.
        if (awayActive && !awayNoOneWatching) {
            awayNoOneWatching = true;
            awayMute();
            note = "1 分钟无人答题，已停止播放（电视可能已关）";
        } else if (!awayActive && awayNoOneWatching) {
            awayNoOneWatching = false;
            awayUnmute();
            note = "检测到操作，已恢复播放";
        }

        final String ft = lockTitle, fb = lockBody, fc = lockCap;
        final long fd = lockDeadline;
        if (hard) {
            // A hard lock replaces any quiz that happens to be on screen.
            if (overlay.isShowing()) {
                synchronized (LOCK) { locked = false; current = null; }
                handler.post(new Runnable() {
                    @Override public void run() { overlay.dismiss(); }
                });
            }
            syncPlayback(true, false);
            muteForHardLock(true);   // guaranteed silence even if the
                                     // vendor player ignores the media key
            handler.post(new Runnable() {
                @Override public void run() { lockOverlay.show(ft, fb, fd, fc); }
            });
            return;
        }

        muteForHardLock(false);      // restore volume before any quiz voice
        if (penaltyEnded) {
            handler.post(new Runnable() {
                @Override public void run() { lockOverlay.dismiss(); }
            });
            lockNow(true);   // a fresh question, still locked, still paused
            return;
        }

        if (lockOverlay.isShowing()) {
            handler.post(new Runnable() {
                @Override public void run() { lockOverlay.dismiss(); }
            });
        }
        syncPlayback(anyLock(), false);

        if (needQuiz) {
            lockNow(false);
        } else if (relaunch) {
            launchQuiz();
        }
    }

    // ------------------------------------------------------------ playback
    /**
     * On this firmware EVERY media key (85 / 126 / 127) is a play-pause
     * toggle and the launcher keeps a permanent MUSIC AudioTrack alive, so
     * reading "is playing" is unreliable. We therefore send exactly one
     * toggle when any lock begins and its counterpart when every lock ends,
     * tracked by playbackPausedByUs - never twice, never out of sync.
     */
    private static void sendPlaybackToggle(boolean pausing) {
        AdbLocal.shell("input keyevent " + (pausing ? "127" : "85"));
        android.util.Log.i("TVCTL", pausing
                ? "playback pause toggle sent" : "playback resume toggle sent");
    }

    /**
     * AdbLocal.shell("input keyevent") takes about a second. Spawning one
     * thread per toggle lets a fast lock/unlock pair finish out of order and
     * leave the player paused. A single-threaded executor preserves order.
     */
    private static final java.util.concurrent.ExecutorService PLAYBACK_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "tvctl-playback");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private void syncPlayback(final boolean wantPaused, boolean waitForSend) {
        synchronized (PLAYBACK_LOCK) {
            if (wantPaused == playbackPausedByUs) {
                return;
            }
            playbackPausedByUs = wantPaused;   // claim before sending
        }
        java.util.concurrent.Future<?> f = PLAYBACK_EXEC.submit(new Runnable() {
            @Override public void run() { sendPlaybackToggle(wantPaused); }
        });
        if (waitForSend) {
            try {
                f.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) { }
        }
    }

    /** True while ANY quiz/hard lock is active. */
    private boolean anyLock() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            boolean hard = manualLocked || penaltyUntilMillis > now
                    || restUntilMillis > now
                    || (controlEnabled && (dailyLocked
                        || (!dailyOverride
                            && todaySeconds >= dailyLimitMinutes * 60)));
            return locked || hard;
        }
    }

    /** Fired on the main thread after the quiz overlay is really removed. */
    private void onQuizWindowGone() {
        restoreQuizVolume();
        syncPlayback(anyLock(), false);
    }

    /**
     * Hard locks (manual / rest / daily / penalty) also mute STREAM_MUSIC.
     * The vendor player does not reliably honour media keys, so this is the
     * guaranteed part of "the TV stops playing". Quiz soft locks NEVER call
     * this - the child must still hear the question being read aloud.
     * The previous volume is persisted so a reboot can restore it.
     */
    /**
     * A clip finished. Re-align MUSIC volume with the CURRENT lock state:
     * the parent may have unlocked while the announcement was still playing,
     * in which case LockVoice restored the (muted) volume it had sampled and
     * we must undo that, otherwise the TV stays silent after unlocking.
     */
    private void reconcileVolumeAfterVoice() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            boolean hard = anyLock();
            if (hard) {
                if (savedMusicVolume >= 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                }
            } else {
                int v;
                synchronized (LOCK) {
                    v = savedMusicVolume >= 0 ? savedMusicVolume : 8;
                    savedMusicVolume = -1;
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            }
        } catch (Exception e) {
            android.util.Log.e("TVCTL", "reconcileVolumeAfterVoice failed", e);
        }
    }

    private void muteForHardLock(boolean mute) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            if (mute) {
                if (savedMusicVolume < 0) {
                    savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    android.util.Log.i("TVCTL", "hard lock muted music volume "
                            + savedMusicVolume + " -> 0");
                    save();
                }
            } else if (savedMusicVolume >= 0) {
                int v = savedMusicVolume;
                savedMusicVolume = -1;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                android.util.Log.i("TVCTL", "hard lock restored music volume to " + v);
                save();
            }
        } catch (Exception e) {
            android.util.Log.e("TVCTL", "muteForHardLock failed", e);
        }
    }

    /**
     * Separate mute used by the "nobody answered" watchdog. Keeps its own
     * saved volume so it never clashes with the hard-lock mute.
     */
    private void awayMute() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null || awaySavedVolume >= 0) {
                return;
            }
            awaySavedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            android.util.Log.i("TVCTL", "away mute music " + awaySavedVolume + " -> 0");
            save();
        } catch (Exception ignored) { }
    }

    private void awayUnmute() {
        try {
            if (awaySavedVolume < 0) {
                return;
            }
            int v = awaySavedVolume;
            awaySavedVolume = -1;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            }
            android.util.Log.i("TVCTL", "away unmute music -> " + v);
            save();
        } catch (Exception ignored) { }
    }

    /** Raise MUSIC volume while a question is shown; never break a mute. */
    private void ensureQuizVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int target = Math.min(Math.max(1, Math.round(max * 0.85f)), cur + 4);
            if (cur <= 0 || cur >= target) {
                return;
            }
            quizSavedVolume = cur;
            quizTargetVolume = target;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            android.util.Log.i("TVCTL", "quiz volume " + cur + " -> " + target);
        } catch (Exception ignored) { }
    }

    private void restoreQuizVolume() {
        try {
            if (quizSavedVolume < 0) {
                return;
            }
            int v = quizSavedVolume;
            int target = quizTargetVolume;
            quizSavedVolume = -1;
            quizTargetVolume = -1;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == target) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                android.util.Log.i("TVCTL", "quiz volume restored to " + v);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ quiz control
    private String[] subjects() {
        return subjectsCsv.split(",");
    }

    /** Two lines for the quiz overlay: remaining session and daily quota. */
    private String quizRemainText() {
        synchronized (LOCK) {
            int session = Math.max(0, sessionLimitMinutes * 60 - sessionSeconds);
            int today = Math.max(0, dailyLimitMinutes * 60 - todaySeconds);
            return "本轮还可看 " + (int) Math.ceil(session / 60.0) + " 分\n"
                    + "今日还可看 " + (int) Math.ceil(today / 60.0) + " 分";
        }
    }

    /**
     * The final quiz of a session is the one fired while the remaining
     * session time no longer covers another full quiz interval. Return the
     * pre-recorded clip for that minute count, or "" for a normal quiz.
     */
    private String lastRoundClip() {
        synchronized (LOCK) {
            int left = Math.max(0, sessionLimitMinutes * 60 - sessionSeconds);
            if (left <= 0 || left > limitMinutes * 60) {
                return "";
            }
            int min = (int) Math.ceil(left / 60.0);
            if (min < 1) {
                min = 1;
            }
            return min <= 10 ? "last_" + min : "last_more";
        }
    }

    private void launchQuiz() {
        // An overlay never makes the app lose a foreground Activity, which is
        // what triggers this vendor's force-stop. WindowManager needs main thread.
        handler.post(new Runnable() {
            @Override public void run() {
                QuizBank.Q q;
                boolean voice;
                synchronized (LOCK) {
                    if (!locked || current == null) {
                        return;
                    }
                    q = current;
                    voice = voiceEnabled;
                }
                if (!overlay.isShowing()) {
                    ensureQuizVolume();
                    overlay.setRemain(quizRemainText());
                    String intro = lastRoundClip();
                    if (!intro.isEmpty()) {
                        android.util.Log.i("TVCTL", "last-round reminder: " + intro);
                    }
                    overlay.show(q, voice, answerCallback, intro);
                }
                quizActive = true;
            }
        });
    }

    private void lockNow(boolean force) {
        synchronized (LOCK) {
            if (locked) {
                return;
            }
            if (!force && !quizEnabled) {
                return;
            }
            quizCorrect = 0;
            java.util.Map<String, Integer> weights = pickWeights();
            current = bank.variantOf(bank.pick(subjects(), weights, disabledIds));
            if (current == null) {
                note = "题库不可用，暂不出题";
                save();
                return;
            }
            currentReview = weights != null && isHardQuestion(current.id);
            locked = true;
            wrong = 0;
            lastQuizActivityAt = System.currentTimeMillis();
            awayNoOneWatching = false;
            note = "已到时间，等待答题";
            save();
        }
        awayUnmute();
        // Toggle playback BEFORE the overlay takes focus, and wait so the key
        // is definitely delivered before the quiz window appears.
        syncPlayback(true, true);
        lastLaunchAt = System.currentTimeMillis();
        launchQuiz();
    }

    /** Returns "correct" / "wrong" / "newq" / "idle". */
    public String answer(String raw) {
        lastQuizActivityAt = System.currentTimeMillis();
        String choice = raw == null ? "" : raw.trim().toUpperCase(Locale.US);
        if (choice.length() == 1 && "1234".contains(choice)) {
            choice = String.valueOf("ABCD".charAt("1234".indexOf(choice)));
        }
        String result;
        synchronized (LOCK) {
            if (!locked || current == null) {
                return "idle";
            }
            String correct = String.valueOf("ABCD".charAt(current.answer));
            QuizBank.Q answered = current;
            if (choice.equals(correct)) {
                recordAnswer(answered, choice, true);
                quizCorrect++;
                wrong = 0;
                // 累计答错次数只在一轮答对 2 题解锁后清零（见下方解锁分支），
                // 中途答对 1 题不清零，避免"错-对-错-对"刷过惩罚。
                if (quizCorrect >= QUIZ_REQUIRED) {
                    locked = false;
                    current = null;
                    currentReview = false;
                    quizCorrect = 0;
                    consecutiveFailedQuestions = 0;  // 本轮累计答错清零
                    quizSeconds = 0;   // only re-arms the NEXT quiz; the 30-min
                                        // session and the daily total keep running
                    note = "答对 2 题，继续观看";
                    result = "correct";
                } else {
                    java.util.Map<String, Integer> wNext = pickWeights();
                    current = bank.variantOf(bank.pick(subjects(), wNext, disabledIds));
                    currentReview = current != null && wNext != null
                            && isHardQuestion(current.id);
                    note = "已答对 1 题，再来一题";
                    result = "next";
                }
                save();
            } else {
                recordAnswer(answered, choice, false);
                wrong++;
                // 每答错一次就累计（不再按"道"折算），本轮累计达到 3 次触发惩罚。
                consecutiveFailedQuestions++;
                if (consecutiveFailedQuestions >= MAX_FAILED_QUESTIONS) {
                    penaltyCount++;
                    long ms = penaltyMillis(penaltyCount);
                    penaltyUntilMillis = System.currentTimeMillis() + ms;
                    consecutiveFailedQuestions = 0;
                    wrong = 0;
                    quizCorrect = 0;   // penalty restarts the 2-question round
                    current = null;
                    locked = true;   // no quiz page during the penalty
                    note = "本轮累计答错 3 次，第 " + penaltyCount
                            + " 次惩罚，休息 " + (ms / 60000L) + " 分钟";
                    result = "penalty";
                } else if (wrong >= WRONG_PER_QUESTION) {
                    // 同一题错 2 次换一道题（累计不清零，继续累计到 3 次惩罚）
                    wrong = 0;
                    java.util.Map<String, Integer> wNew = pickWeights();
                    current = bank.variantOf(bank.pick(subjects(), wNew, disabledIds));
                    currentReview = current != null && wNew != null
                            && isHardQuestion(current.id);
                    note = "选错 2 次，换一道题（本轮已错 "
                            + consecutiveFailedQuestions + "/3 次）";
                    result = "newq";
                } else {
                    result = "wrong";
                }
                save();
            }
        }
        // Playback is restored by onQuizWindowGone(), fired from the
        // overlay's onDismissed callback - i.e. after focus is released.
        // Sending a toggle here as well would flip the state twice.
        final String r = result;
        handler.post(new Runnable() {
            @Override public void run() {
                if ("correct".equals(r)) {
                    overlay.onResult("correct");
                } else if ("next".equals(r)) {
                    QuizBank.Q q;
                    synchronized (LOCK) { q = current; }
                    overlay.onNext(q, "答对 1 题，再来一题");
                } else if ("newq".equals(r)) {
                    QuizBank.Q q;
                    synchronized (LOCK) { q = current; }
                    overlay.newQuestion(q);
                } else if ("penalty".equals(r)) {
                    final int pc;
                    final long leftSec;
                    synchronized (LOCK) {
                        pc = penaltyCount;
                        leftSec = Math.max(0L,
                                (penaltyUntilMillis - System.currentTimeMillis()) / 1000L);
                    }
                    overlay.dismiss();   // onDismissed keeps playback paused
                    muteForHardLock(true);
                    lockOverlay.show("答错太多啦", "第 " + pc + " 次惩罚\n不能瞎猜哦",
                            lockDeadlineMillis(System.currentTimeMillis()),
                            "惩罚剩余时间");
                } else {
                    overlay.onResult("wrong");
                }
            }
        });
        return result;
    }

    // ---------------------------------------------------------- wrong answers
    /** Records one attempt so the parent page can show a 错题本 (wrong-answer book). */
    private void recordAnswer(QuizBank.Q q, String choice, boolean correct) {
        if (q == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            JSONObject items = wrongBook.optJSONObject("items");
            if (items == null) {
                items = new JSONObject();
                wrongBook.put("items", items);
            }
            JSONObject it = items.optJSONObject(q.id);
            if (it == null) {
                it = new JSONObject();
                it.put("q", q.q);
                it.put("subject", q.subject);
                it.put("title", q.title == null ? "" : q.title);
                it.put("image", q.image == null ? "" : q.image);
                it.put("opts", new JSONArray(java.util.Arrays.asList(q.options)));
                it.put("answer", q.answer);
                it.put("right", 0);
                it.put("wrong", 0);
                items.put(q.id, it);
            }
            it.put("last", now);
            if (correct) {
                it.put("right", it.optInt("right", 0) + 1);
                it.put("last_ok", now);
                wrongBook.put("ok", wrongBook.optInt("ok", 0) + 1);
            } else {
                it.put("wrong", it.optInt("wrong", 0) + 1);
                it.put("last_bad", now);
                it.put("picked", choice == null ? "" : choice);
                String picked = optionText(it.optJSONArray("opts"), choice);
                it.put("picked_text", picked);
                if (!picked.isEmpty()) {
                    // Histogram of the wrong options: "总把 apple 认成 banana"
                    // is far more useful than just the number of mistakes.
                    JSONObject picks = it.optJSONObject("picks");
                    if (picks == null) {
                        picks = new JSONObject();
                        it.put("picks", picks);
                    }
                    picks.put(picked, picks.optInt(picked, 0) + 1);
                }
                wrongBook.put("bad", wrongBook.optInt("bad", 0) + 1);
            }
            wrongBook.put("updated", now);
            save();
        } catch (Exception e) {
            android.util.Log.w("TVCTL", "recordAnswer failed", e);
        }
    }

    private static String optionText(JSONArray opts, String choice) {
        if (opts == null || choice == null || choice.isEmpty()) {
            return "";
        }
        int i = "ABCD".indexOf(choice.charAt(0));
        return (i >= 0 && i < opts.length()) ? opts.optString(i, "") : "";
    }

    // ------------------------------------------------------- device telemetry
    /** Volume + network + traffic, embedded in /api/status. */
    private JSONObject systemJson() throws Exception {
        JSONObject o = new JSONObject();
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            boolean mute = vol == 0;
            try {
                mute = mute || am.isStreamMute(AudioManager.STREAM_MUSIC);
            } catch (Exception ignored) { }
            o.put("volume", vol);
            o.put("volume_max", max);
            o.put("volume_percent", Math.round(vol * 100f / max));
            o.put("muted", mute);
        }
        refreshNetwork();
        refreshTraffic();
        o.put("network_type", netType);
        o.put("network_online", netOnline);
        o.put("ip", netIp);
        o.put("traffic_supported", trafficSupported);
        o.put("net_rx_bps", trafficRxBps);
        o.put("net_tx_bps", trafficTxBps);
        o.put("net_rx_total", trafficRxTotal);
        o.put("net_tx_total", trafficTxTotal);
        o.put("memory", memoryJson());
        return o;
    }

    /** Live RAM figures from /proc/meminfo (total/available/used + swap), in MB. */
    private JSONObject memoryJson() {
        JSONObject m = new JSONObject();
        long totalKb = -1, availKb = -1, freeKb = -1, cachedKb = -1,
                swapTotalKb = -1, swapFreeKb = -1;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream("/proc/meminfo"), StandardCharsets.UTF_8), 2048);
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                long v;
                try { v = Long.parseLong(parts[1]); } catch (Exception bad) { continue; }
                String key = parts[0];
                if (key.equals("MemTotal:")) totalKb = v;
                else if (key.equals("MemAvailable:")) availKb = v;
                else if (key.equals("MemFree:")) freeKb = v;
                else if (key.equals("Cached:")) cachedKb = v;
                else if (key.equals("SwapTotal:")) swapTotalKb = v;
                else if (key.equals("SwapFree:")) swapFreeKb = v;
            }
        } catch (Exception e) {
            android.util.Log.w("TVCTL", "read /proc/meminfo failed", e);
        } finally {
            if (br != null) { try { br.close(); } catch (Exception ignored) { } }
        }
        if (totalKb <= 0) return m;
        // Older kernels may lack MemAvailable: approximate with free + cached.
        if (availKb < 0) availKb = Math.max(0, freeKb) + Math.max(0, cachedKb);
        long usedKb = Math.max(0, totalKb - availKb);
        try {
            m.put("total_mb", Math.round(totalKb / 1024f));
            m.put("avail_mb", Math.round(availKb / 1024f));
            m.put("used_mb", Math.round(usedKb / 1024f));
            m.put("used_percent", Math.round(usedKb * 100f / totalKb));
            m.put("avail_percent", Math.round(availKb * 100f / totalKb));
            if (swapTotalKb > 0) {
                m.put("swap_total_mb", Math.round(swapTotalKb / 1024f));
                m.put("swap_used_mb", Math.round(Math.max(0, swapTotalKb - swapFreeKb) / 1024f));
            }
        } catch (Exception ignored) { }
        return m;
    }

    /** TrafficStats gives bytes since boot; two samples produce a live rate. */
    private void refreshTraffic() {
        try {
            long rx = android.net.TrafficStats.getTotalRxBytes();
            long tx = android.net.TrafficStats.getTotalTxBytes();
            if (rx < 0 || tx < 0) {
                trafficSupported = false;
                return;
            }
            trafficSupported = true;
            trafficRxTotal = rx;
            trafficTxTotal = tx;
            long now = System.currentTimeMillis();
            if (trafficLastAt > 0 && now > trafficLastAt && trafficLastRx >= 0) {
                double sec = (now - trafficLastAt) / 1000.0;
                trafficRxBps = (long) Math.max(0, (rx - trafficLastRx) / sec);
                trafficTxBps = (long) Math.max(0, (tx - trafficLastTx) / sec);
            }
            trafficLastRx = rx;
            trafficLastTx = tx;
            trafficLastAt = now;
        } catch (Exception e) {
            trafficSupported = false;
        }
    }

    /** Cached (10 s) network state: type, internet capability, LAN IPv4. */
    private void refreshNetwork() {
        long now = System.currentTimeMillis();
        if (now - netProbeAt < 10000) {
            return;
        }
        netProbeAt = now;
        String type = "none";
        boolean online = false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = n == null ? null
                    : cm.getNetworkCapabilities(n);
            if (caps != null) {
                online = caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    type = "wifi";
                } else if (caps.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = "ethernet";
                } else if (caps.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    type = "mobile";
                } else {
                    type = "other";
                }
            }
        } catch (Exception ignored) { }
        String ip = "";
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements() && ip.isEmpty()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a.isLoopbackAddress() || a instanceof java.net.Inet6Address) {
                        continue;
                    }
                    String host = a.getHostAddress();
                    if (host != null && !host.isEmpty()) {
                        ip = host;
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }
        netType = type;
        netOnline = online;
        netIp = ip;
    }

    /** null = pure random; otherwise an absolute weight per question. */
    private java.util.Map<String, Integer> pickWeights() {
        return "random".equals(pickMode) ? null : quizWeights();
    }

    /**
     * Never-asked questions get weight 7 so they surface early; repeatedly
     * wrong ones get 1 + min(netWrong,6)*4 (up to 25); mastered ones stay at 1.
     */
    private java.util.Map<String, Integer> quizWeights() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        JSONObject items = wrongBook.optJSONObject("items");
        for (QuizBank.Q q : bank.allQuestions()) {
            JSONObject o = items == null ? null : items.optJSONObject(q.id);
            int right = o == null ? 0 : o.optInt("right", 0);
            int bad = o == null ? 0 : o.optInt("wrong", 0);
            int w;
            if (right + bad == 0) {
                w = 7;
            } else if (bad == 0 && right >= 3) {
                w = 1;
            } else {
                w = 1 + Math.min(Math.max(bad - right, 0), 6) * 4;
            }
            m.put(q.id, w);
        }
        return m;
    }

    /** "错题复习" badge only when this question is net-wrong. */
    private boolean isHardQuestion(String id) {
        JSONObject items = wrongBook.optJSONObject("items");
        JSONObject o = items == null ? null : items.optJSONObject(id);
        return o != null && o.optInt("wrong", 0) > o.optInt("right", 0);
    }

    /** Report consumed by the phone page: totals, per-subject accuracy, hardest items. */
    private JSONObject wrongBookJson() throws Exception {
        synchronized (LOCK) {
            String[] names = {"数学", "英语", "语文", "图形"};
            java.util.LinkedHashMap<String, int[]> per = new java.util.LinkedHashMap<>();
            for (String n : names) {
                per.put(n, new int[]{0, 0, 0});   // {correct, wrong, distinct wrong items}
            }
            java.util.List<JSONObject> rows = new java.util.ArrayList<>();
            JSONObject items = wrongBook.optJSONObject("items");
            if (items != null) {
                java.util.Iterator<String> keys = items.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject it = items.optJSONObject(id);
                    if (it == null) {
                        continue;
                    }
                    // A bank refresh replaces the text of a question but keeps
                    // its id. Always display the CURRENT question, otherwise
                    // an old "数一数圆点" would show up next to new math stats.
                    QuizBank.Q cur = bankQuestion(id);
                    JSONArray opts = cur != null
                            ? new JSONArray(java.util.Arrays.asList(cur.options))
                            : it.optJSONArray("opts");
                    int ai = cur != null ? cur.answer : it.optInt("answer", -1);
                    String subj = cur != null ? cur.subject : it.optString("subject", "");
                    if ("拼音".equals(subj)) {
                        subj = "语文";   // legacy records
                    }
                    JSONObject row = new JSONObject(it.toString());
                    row.put("id", id);
                    row.put("subject", subj);
                    if (cur != null) {
                        row.put("q", cur.q);
                        row.put("title", cur.title == null ? "" : cur.title);
                        row.put("image", cur.image == null ? "" : cur.image);
                    }
                    row.put("answer_text", (opts != null && ai >= 0 && ai < opts.length())
                            ? opts.optString(ai, "") : "");
                    if (!row.has("picked_text") || row.optString("picked_text", "").isEmpty()) {
                        row.put("picked_text", optionText(opts, it.optString("picked", "")));
                    }
                    row.remove("opts");   // keep the payload small; text is enough
                    int right = it.optInt("right", 0);
                    int bad = it.optInt("wrong", 0);
                    int[] agg = per.get(subj);
                    if (agg != null) {
                        agg[0] += right;
                        agg[1] += bad;
                        if (bad > 0) {
                            agg[2]++;
                        }
                    }
                    if (bad > 0) {
                        rows.add(row);
                    }
                }
            }
            java.util.Collections.sort(rows, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int d = b.optInt("wrong", 0) - a.optInt("wrong", 0);
                    if (d != 0) {
                        return d;
                    }
                    long la = a.optLong("last_bad", 0);
                    long lb = b.optLong("last_bad", 0);
                    return la == lb ? 0 : (lb > la ? 1 : -1);
                }
            });
            JSONArray subs = new JSONArray();
            for (String n : names) {
                int[] agg = per.get(n);
                JSONObject s = new JSONObject();
                int asked = agg[0] + agg[1];
                s.put("subject", n);
                s.put("correct", agg[0]);
                s.put("wrong", agg[1]);
                s.put("asked", asked);
                s.put("wrong_items", agg[2]);
                s.put("accuracy", asked == 0 ? 0
                        : Math.round(agg[0] * 1000.0 / asked) / 10.0);
                subs.put(s);
            }
            int ok = wrongBook.optInt("ok", 0);
            int bad = wrongBook.optInt("bad", 0);
            int asked = ok + bad;
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("asked", asked);
            out.put("correct", ok);
            out.put("wrong", bad);
            out.put("accuracy", asked == 0 ? 0 : Math.round(ok * 1000.0 / asked) / 10.0);
            out.put("subjects", subs);
            out.put("distinct", rows.size());
            out.put("items", new JSONArray(rows.subList(0, Math.min(rows.size(), 200))));
            out.put("updated", wrongBook.optLong("updated", 0));
            return out;
        }
    }

    // --------------------------------------------------------------- 题库
    /**
     * Full catalogue for the parent's 题库 page: every question shipped in
     * assets (including ones the child has never seen), merged with the
     * per-question counters from the wrong-answer book and the enabled flag.
     */
    private JSONObject questionBankJson() throws Exception {
        synchronized (LOCK) {
            JSONObject items = wrongBook.optJSONObject("items");
            String[] names = {"数学", "英语", "语文", "图形"};
            java.util.LinkedHashMap<String, int[]> per = new java.util.LinkedHashMap<>();
            for (String n : names) {
                per.put(n, new int[]{0, 0, 0, 0});  // {total, enabled, asked, wrong}
            }
            JSONArray rows = new JSONArray();
            int enabled = 0, never = 0, askedTotal = 0, wrongTotal = 0;
            List<QuizBank.Q> qs = bank.allQuestions();
            for (QuizBank.Q q : qs) {
                JSONObject it = items == null ? null : items.optJSONObject(q.id);
                int right = it == null ? 0 : it.optInt("right", 0);
                int bad = it == null ? 0 : it.optInt("wrong", 0);
                int asked = right + bad;
                boolean on = !disabledIds.contains(q.id);
                if (on) {
                    enabled++;
                }
                if (asked == 0) {
                    never++;
                }
                askedTotal += asked;
                wrongTotal += bad;
                JSONObject row = new JSONObject();
                row.put("id", q.id);
                row.put("subject", q.subject);
                row.put("q", q.q);
                row.put("title", q.title == null ? "" : q.title);
                row.put("image", q.image == null ? "" : q.image);
                row.put("options", new JSONArray(java.util.Arrays.asList(q.options)));
                row.put("answer_text", q.answer >= 0 && q.answer < q.options.length
                        ? q.options[q.answer] : "");
                row.put("right", right);
                row.put("wrong", bad);
                row.put("asked", asked);
                row.put("accuracy", asked == 0 ? 0
                        : Math.round(right * 1000.0 / asked) / 10.0);
                row.put("enabled", on);
                row.put("last", it == null ? 0 : it.optLong("last", 0));
                row.put("last_bad", it == null ? 0 : it.optLong("last_bad", 0));
                row.put("picked_text", it == null ? "" : it.optString("picked_text", ""));
                row.put("picks", it == null ? new JSONObject() : it.optJSONObject("picks"));
                rows.put(row);
                int[] agg = per.get(q.subject);
                if (agg != null) {
                    agg[0]++;
                    if (on) {
                        agg[1]++;
                    }
                    agg[2] += asked;
                    agg[3] += bad;
                }
            }
            JSONArray subs = new JSONArray();
            for (String n : names) {
                int[] a = per.get(n);
                JSONObject s = new JSONObject();
                s.put("subject", n);
                s.put("total", a[0]);
                s.put("enabled", a[1]);
                s.put("asked", a[2]);
                s.put("wrong", a[3]);
                subs.put(s);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("total", qs.size());
            out.put("enabled", enabled);
            out.put("disabled", qs.size() - enabled);
            out.put("never_asked", never);
            out.put("asked", askedTotal);
            out.put("correct", askedTotal - wrongTotal);
            out.put("wrong", wrongTotal);
            out.put("accuracy", askedTotal == 0 ? 0
                    : Math.round((askedTotal - wrongTotal) * 1000.0 / askedTotal) / 10.0);
            out.put("subjects", subs);
            out.put("subjects_enabled", new JSONArray(java.util.Arrays.asList(subjects())));
            out.put("pick_mode", pickMode);
            out.put("items", rows);
            return out;
        }
    }

    /**
     * Switch questions on/off. Accepts a single id, an ids array, all:true,
     * or smart:true (disable "too easy" items: asked >= 3, never wrong).
     * Returns the new enabled count so the page can reconcile its state.
     */
    private byte[] setQuestionEnabled(JSONObject b) {
        int changed = 0;
        synchronized (LOCK) {
            try {
                boolean on = b.optBoolean("enabled", true);
                if (b.optBoolean("all", false)) {
                    for (QuizBank.Q q : bank.allQuestions()) {
                        if (on) {
                            changed += disabledIds.remove(q.id) ? 1 : 0;
                        } else {
                            changed += disabledIds.add(q.id) ? 1 : 0;
                        }
                    }
                } else if (b.optBoolean("smart", false)) {
                    JSONObject items = wrongBook.optJSONObject("items");
                    for (QuizBank.Q q : bank.allQuestions()) {
                        JSONObject it = items == null ? null : items.optJSONObject(q.id);
                        int asked = it == null ? 0
                                : it.optInt("right", 0) + it.optInt("wrong", 0);
                        int bad = it == null ? 0 : it.optInt("wrong", 0);
                        if (asked >= 3 && bad == 0 && disabledIds.add(q.id)) {
                            changed++;
                        }
                    }
                } else {
                    JSONArray ids = b.optJSONArray("ids");
                    if (ids == null) {
                        ids = new JSONArray();
                        String one = b.optString("id", "");
                        if (!one.isEmpty()) {
                            ids.put(one);
                        }
                    }
                    for (int i = 0; i < ids.length(); i++) {
                        String id = ids.optString(i, "");
                        if (id.isEmpty() || !isKnownQuestion(id)) {
                            continue;
                        }
                        if (on) {
                            changed += disabledIds.remove(id) ? 1 : 0;
                        } else {
                            changed += disabledIds.add(id) ? 1 : 0;
                        }
                    }
                }
                note = "题库设置已保存";
                save();
            } catch (Exception e) {
                android.util.Log.w("TVCTL", "setQuestionEnabled failed", e);
            }
            int onCount = 0;
            for (QuizBank.Q q : bank.allQuestions()) {
                if (!disabledIds.contains(q.id)) {
                    onCount++;
                }
            }
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("changed", changed);
                out.put("enabled", onCount);
                out.put("total", bank.size());
                out.put("all_disabled", onCount == 0);
            } catch (Exception ignored) { }
            return json(out.toString());
        }
    }

    private QuizBank.Q bankQuestion(String id) {
        for (QuizBank.Q q : bank.allQuestions()) {
            if (q.id.equals(id)) {
                return q;
            }
        }
        return null;
    }

    private boolean isKnownQuestion(String id) {
        return bankQuestion(id) != null;
    }

    /** Clears only the QUIZ lock; hard locks (manual/rest/daily) survive. */
    public void forceUnlock() {
        synchronized (LOCK) {
            locked = false;
            current = null;
            wrong = 0;
            quizCorrect = 0;
            quizSeconds = 0;
            awayNoOneWatching = false;
            note = "已跳过这道题（时长不重置）";
            save();
        }
        awayUnmute();
        // overlay.dismiss() triggers onDismissed -> onQuizWindowGone().
        // If no quiz window was up, there is no dismiss event to rely on.
        if (!overlay.isShowing()) {
            onQuizWindowGone();
        }
        handler.post(new Runnable() {
            @Override public void run() { overlay.dismiss(); }
        });
    }

    /** Parent manually restarts the session and clears the penalty ladder. */
    public void resetTimer() {
        synchronized (LOCK) {
            sessionSeconds = 0;
            quizSeconds = 0;
            penaltyCount = 0;
            consecutiveFailedQuestions = 0;
            wrong = 0;
            note = "本轮计时与惩罚次数已重置（今日累计不变）";
            save();
        }
    }

    /** One-tap lock: only the phone can lift it. Survives a reboot. */
    public void manualLock() {
        synchronized (LOCK) {
            manualLocked = true;
            note = "家长已一键锁定";
            save();
        }
        applyLockUi();
    }

    /**
     * Parent unlock resolves exactly ONE active lock. It is deliberately not
     * a blanket waiver: lifting a manual lock must not reset the session timer,
     * and a daily-cap waiver lasts only for the session it was granted for.
     * With no lock present it changes nothing at all, so repeated taps cannot
     * keep adding 30-minute sessions. Returns the text shown on the phone.
     */
    public String parentUnlock() {
        String result;
        boolean quizAfterPenalty = false;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            boolean penaltyLock = penaltyUntilMillis > now;
            boolean restLock = restUntilMillis > now;
            boolean dailyLock = controlEnabled && (dailyLocked
                    || (!dailyOverride
                        && todaySeconds >= dailyLimitMinutes * 60));

            if (manualLocked) {
                manualLocked = false;
                result = "已解除手动锁定";
            } else if (penaltyLock) {
                penaltyUntilMillis = 0;
                consecutiveFailedQuestions = 0;
                wrong = 0;
                quizCorrect = 0;
                locked = false;
                current = null;
                quizAfterPenalty = true;
                result = "已清除惩罚，重新出题";
            } else if (restLock) {
                restUntilMillis = 0;
                sessionSeconds = 0;      // same as a rest ending naturally
                quizSeconds = 0;
                result = "已结束本次休息";
            } else if (dailyLock) {
                dailyLocked = false;
                dailyUnlockAt = 0;
                dailyOverride = true;    // one-session waiver; see tick()
                sessionSeconds = 0;
                quizSeconds = 0;
                result = "已放行本轮一次，今日累计继续计算";
            } else {
                return "当前无需解锁";
            }
            note = result;
            save();
        }
        awayUnmute();
        if (quizAfterPenalty) {
            // Keep the quiz pause state intact: lockNow() will not send a
            // second playback toggle while playback is already paused by us.
            handler.post(new Runnable() {
                @Override public void run() { lockOverlay.dismiss(); }
            });
            lockNow(true);
        } else {
            applyLockUi();
        }
        return result;
    }

    /** Reconcile the lock window and playback right after a parent action. */
    private void applyLockUi() {
        final boolean hard = anyLock();
        final long deadline = hard ? lockDeadlineMillis(System.currentTimeMillis()) : 0;
        final String cap, lockText, lockBodyText;
        synchronized (LOCK) {
            if (!manualLocked && dailyLocked && deadline <= 0) {
                cap = "等待家长放行";
                lockText = "今天看够啦";
                lockBodyText = "请在手机上点「解锁电视」放行";
            } else {
                cap = deadline > 0 ? "距离自动解锁还有" : "";
                lockText = "电视已锁定";
                lockBodyText = "请在手机上点「解锁电视」继续";
            }
        }
        handler.post(new Runnable() {
            @Override public void run() {
                if (hard) {
                    overlay.dismiss();
                    lockOverlay.show(lockText, lockBodyText, deadline, cap);
                } else {
                    lockOverlay.dismiss();
                    overlay.dismiss();
                }
            }
        });
        syncPlayback(hard, false);
        muteForHardLock(hard);
    }

    public void setQuizActive(boolean v) {
        quizActive = v;
    }

    public QuizBank.Q currentQuestion() {
        synchronized (LOCK) {
            return current;
        }
    }

    public boolean isLocked() {
        synchronized (LOCK) {
            return locked;
        }
    }

    public boolean isVoiceEnabled() {
        synchronized (LOCK) {
            return voiceEnabled;
        }
    }

    // ------------------------------------------------------------ HTTP server
    private void startHttp() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0"));
                    while (!server.isClosed()) {
                        final Socket c = server.accept();
                        c.setSoTimeout(8000);
                        new Thread(new Runnable() {
                            @Override public void run() { handle(c); }
                        }).start();
                    }
                } catch (Exception ignored) { }
            }
        }, "tvctl-http").start();
    }

    private void handle(Socket c) {
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
            String line = r.readLine();
            if (line == null) {
                c.close();
                return;
            }
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            String path = rawPath;
            int cut = path.indexOf('?');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            int len = 0;
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                String hl = h.toLowerCase(Locale.US);
                if (hl.startsWith("content-length:")) {
                    try { len = Integer.parseInt(h.substring(15).trim()); } catch (Exception ignored) { }
                }
            }
            // Read the body through the SAME BufferedReader used for the
            // headers. It buffers ahead, so reading the raw InputStream here
            // would block forever when the body arrived in the first packet.
            String body = "";
            if (len > 0 && len < 65536) {
                char[] cb = new char[len];
                int off = 0;
                while (off < len) {
                    int n = r.read(cb, off, len - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
                byte[] bb = new byte[off];
                for (int i = 0; i < off; i++) {
                    bb[i] = (byte) cb[i];
                }
                body = new String(bb, 0, off, StandardCharsets.UTF_8);
            }
            JSONObject b = new JSONObject();
            if (!body.isEmpty()) {
                try { b = new JSONObject(body); } catch (Exception ignored) { }
            }

            // Access token feature removed by request: LAN / remote requests are
            // no longer gated. (Remote exposure is not used any more.)

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                byte[] html = readAsset("index.html");
                respond(out, 200, "text/html; charset=utf-8", html);
            } else if ("GET".equals(method)
                    && ("/wrongbook".equals(path) || "/wrongbook.html".equals(path))) {
                // 错题本已并入题库页；保留旧书签，直接送同一页面。
                respond(out, 200, "text/html; charset=utf-8", readAsset("bank.html"));
            } else if ("GET".equals(method)
                    && ("/changelog".equals(path) || "/changelog.html".equals(path))) {
                respond(out, 200, "text/html; charset=utf-8", readAsset("changelog.html"));
            } else if ("GET".equals(method)
                    && ("/bank".equals(path) || "/bank.html".equals(path)
                        || "/questionbank".equals(path) || "/questionbank.html".equals(path))) {
                respond(out, 200, "text/html; charset=utf-8", readAsset("bank.html"));
            } else if ("GET".equals(method) && "/common.js".equals(path)) {
                respond(out, 200, "text/javascript; charset=utf-8", readAsset("common.js"));
            } else if ("/api/questionbank".equals(path)) {
                respond(out, 200, "application/json; charset=utf-8",
                        questionBankJson().toString().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/question_enabled".equals(path)) {
                respond(out, 200, setQuestionEnabled(b));
            } else if ("/api/status".equals(path)) {
                respond(out, 200, "application/json; charset=utf-8",
                        statusJson().toString().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/weather".equals(path)) {
                respond(out, 200, "application/json; charset=utf-8",
                        weather.json().toString().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/weather_refresh".equals(path)) {
                weather.maybeRefresh(true);
                respond(out, 200, json("{\"ok\":true}"));
            } else if ("/api/wrongbook".equals(path)) {
                respond(out, 200, "application/json; charset=utf-8",
                        wrongBookJson().toString().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/img/")) {
                byte[] png = readImage(path.substring(5));
                if (png == null) {
                    respond(out, 404, json("{\"ok\":false}"));
                } else {
                    respond(out, 200, "image/png", png);
                }
            } else if ("/api/key".equals(path)) {
                Integer code = KEYS.get(b.optString("key", "").toUpperCase(Locale.US));
                if (code != null) {
                    shellAsync("input keyevent " + code);
                    respond(out, 200, json("{\"ok\":true}"));
                } else {
                    respond(out, 400, json("{\"ok\":false,\"msg\":\"unknown key\"}"));
                }
            } else if ("/api/text".equals(path)) {
                String t = b.optString("text", "").replaceAll("[^A-Za-z0-9 _.\\-]", "");
                if (t.length() > 60) {
                    t = t.substring(0, 60);
                }
                if (!t.isEmpty()) {
                    shellAsync("input text '" + t.replace(" ", "%s") + "'");
                }
                respond(out, 200, json("{\"ok\":true}"));
            } else if ("/api/answer".equals(path)) {
                String r0 = answer(b.optString("choice", ""));
                if ("idle".equals(r0)) {
                    respond(out, 400, json("{\"ok\":false,\"msg\":\"当前不在答题状态\"}"));
                } else {
                    JSONObject o = new JSONObject();
                    int qc;
                    synchronized (LOCK) { qc = quizCorrect; }
                    o.put("ok", true);
                    o.put("correct", "correct".equals(r0) || "next".equals(r0));
                    o.put("finished", "correct".equals(r0));
                    o.put("quiz_correct", qc);
                    o.put("quiz_required", QUIZ_REQUIRED);
                    o.put("wrong", wrong);
                    o.put("msg", "correct".equals(r0) ? "答对 2 题，继续观看"
                            : ("next".equals(r0) ? "答对 1 题，再来一题"
                            : ("penalty".equals(r0) ? note
                            : ("newq".equals(r0) ? "选错 2 次，换一道题" : "再想想哦"))));
                    respond(out, 200, o.toString().getBytes(StandardCharsets.UTF_8));
                }
            } else if ("/api/find_remote".equals(path)) {
                // Vendor firmware ships BuzzingService, which writes the BLE
                // buzzer characteristic on the paired remote (10 s beep).
                //
                // Skyworth gate: BuzzingService reads persist.sys.findme.keyflag.
                // When it is "true" the service logs "findme state:true" and
                // stops immediately WITHOUT beeping; only "false" runs the
                // full GATT write (val[0]=1 for 10s, then val[0]=0). The flag
                // can get stuck at true, which made this button silently do
                // nothing. Reset it synchronously BEFORE starting the service
                // so every tap actually beeps.
                try {
                    AdbLocal.shell("setprop persist.sys.findme.keyflag false");
                } catch (Exception ignored) { }
                boolean started = false;
                try {
                    Intent fi = new Intent("com.skyworthdigital.action.FIND_ME");
                    fi.setPackage("com.skyworthdigital.bluetooth.settings");
                    started = (startService(fi) != null);
                } catch (Exception ignored) { }
                if (!started) {
                    shellAsync("am startservice -a com.skyworthdigital.action.FIND_ME -p com.skyworthdigital.bluetooth.settings");
                }
                synchronized (LOCK) {
                    note = "已让遥控器响铃 10 秒";
                }
                respond(out, 200, json("{\"ok\":true,\"msg\":\"遥控器响铃 10 秒\"}"));
            } else if ("/api/speak".equals(path)) {
                handler.post(new Runnable() {
                    @Override public void run() { overlay.replay(); }
                });
                respond(out, 200, json("{\"ok\":true}"));
            } else if (isPrivileged(path)) {
                // Passwordless by design: lock / unlock / every setting works
                // with a single tap.
                respond(out, 200, privileged(path, b));
            } else {
                respond(out, 404, json("{\"ok\":false,\"msg\":\"not found\"}"));
            }
        } catch (Exception e) {
            android.util.Log.e("TVCTL", "http handler failed", e);
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    private boolean isPrivileged(String p) {
        return "/api/settings".equals(p) || "/api/reboot".equals(p)
                || "/api/force_unlock".equals(p) || "/api/start_quiz".equals(p)
                || "/api/reset_timer".equals(p)
                || "/api/lock".equals(p) || "/api/unlock".equals(p)
                || "/api/timer_pause".equals(p) || "/api/wrongbook_clear".equals(p);
    }

    private byte[] privileged(String path, JSONObject b) {
        if ("/api/settings".equals(path)) {
            synchronized (LOCK) {
                if (b.has("limit_minutes")) {
                    limitMinutes = clamp(b.optInt("limit_minutes", 5), 1, 600);
                }
                if (b.has("quiz_interval_minutes") && !b.has("limit_minutes")) {
                    limitMinutes = clamp(b.optInt("quiz_interval_minutes", 5), 1, 600);
                }
                if (b.has("session_limit_minutes")) {
                    sessionLimitMinutes = clamp(b.optInt("session_limit_minutes", 30), 1, 600);
                }
                if (b.has("rest_minutes")) {
                    restMinutes = clamp(b.optInt("rest_minutes", 60), 1, 600);
                }
                if (b.has("daily_limit_minutes")) {
                    dailyLimitMinutes = clamp(b.optInt("daily_limit_minutes", 120), 1, 600);
                }
                JSONArray arr = b.optJSONArray("subjects");
                if (arr != null && arr.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        String v = arr.optString(i, "");
                        if ("math".equals(v) || "english".equals(v)
                                || "pinyin".equals(v) || "chinese".equals(v)
                                || "visual".equals(v)) {
                            if (sb.length() > 0) {
                                sb.append(',');
                            }
                            sb.append(v);
                        }
                    }
                    if (sb.length() > 0) {
                        subjectsCsv = sb.toString();
                    }
                }
                if (b.has("pick_mode")) {
                    pickMode = "random".equals(b.optString("pick_mode", ""))
                            ? "random" : "wrong_first";
                }
                if (b.has("reboot_time")
                        && b.optString("reboot_time", "").matches("^\\d{1,2}:\\d{2}$")) {
                    rebootTime = b.optString("reboot_time");
                }
                if (b.has("reboot_enabled")) {
                    rebootEnabled = b.optBoolean("reboot_enabled", true);
                }
                if (b.has("daily_reset_time")) {
                    String t = b.optString("daily_reset_time", "");
                    if (t.matches("^\\d{1,2}:\\d{2}$")) {
                        dailyResetTime = t;
                        if (dailyLocked) {
                            dailyUnlockAt = nextDailyResetMillis(
                                    System.currentTimeMillis());
                        }
                    }
                }
                JSONArray days = b.optJSONArray("reboot_days");
                if (days != null) {
                    StringBuilder db = new StringBuilder();
                    for (int i = 0; i < days.length(); i++) {
                        int d = days.optInt(i, 0);
                        if (d >= 1 && d <= 7) {
                            if (db.length() > 0) {
                                db.append(',');
                            }
                            db.append(d);
                        }
                    }
                    if (db.length() > 0) {
                        rebootDays = db.toString();
                    }
                }
                if (b.has("control_enabled")) {
                    boolean v = b.optBoolean("control_enabled", true);
                    if (v != controlEnabled) {
                        // Switching the master limit off must release any
                        // running rest / daily lock, but NEVER clears the
                        // accumulated daily total. Time keeps counting even
                        // while the limit is off, so toggling can't bypass it.
                        restUntilMillis = 0;
                        dailyOverride = false;
                        dailyLocked = false;
                        dailyUnlockAt = 0;
                        sessionSeconds = 0;
                        quizSeconds = 0;
                        controlEnabled = v;
                    }
                }
                if (b.has("voice_enabled")) {
                    voiceEnabled = b.optBoolean("voice_enabled", true);
                }
                if (b.has("lock_voice_enabled")) {
                    lockVoiceEnabled = b.optBoolean("lock_voice_enabled", true);
                    lockVoice.setEnabled(lockVoiceEnabled);
                    if (!lockVoiceEnabled) {
                        lockVoice.stop();
                    }
                }
                if (b.has("quiz_enabled")) {
                    boolean v = b.optBoolean("quiz_enabled", true);
                    if (v && !quizEnabled) {
                        quizSeconds = 0;
                    }
                    quizEnabled = v;
                }
                if (b.has("pause_on_no_playback")) {
                    pauseOnNoPlayback = b.optBoolean("pause_on_no_playback", true);
                }
                if (b.has("weather_enabled")) {
                    boolean we = b.optBoolean("weather_enabled", true);
                    prefs.edit().putBoolean("weather_enabled", we).apply();
                    if (weather != null) {
                        lockOverlay.setWeather(weather.view());
                    }
                }
                String wc = b.optString("weather_city_code", "");
                if (wc.matches("^\\d{9}$")) {
                    prefs.edit().putString("weather_city_code", wc)
                            .putString("weather_city_name",
                                    b.optString("weather_city_name", ""))
                            .apply();
                    if (weather != null) {
                        weather.maybeRefresh(true);
                    }
                }
                note = "设置已保存";
                save();
            }
        } else if ("/api/reboot".equals(path)) {
            new Thread(new Runnable() {
                @Override public void run() { AdbLocal.reboot(); }
            }).start();
        } else if ("/api/force_unlock".equals(path)) {
            forceUnlock();
        } else if ("/api/start_quiz".equals(path)) {
            lockNow(true);   // a parent asking for a quiz is never blocked by the toggle
        } else if ("/api/reset_timer".equals(path)) {
            resetTimer();
        } else if ("/api/lock".equals(path)) {
            manualLock();
        } else if ("/api/unlock".equals(path)) {
            String unlockMsg = parentUnlock();
            return json("{\"ok\":true,\"msg\":"
                    + JSONObject.quote(unlockMsg) + "}");
        } else if ("/api/timer_pause".equals(path)) {
            timerPausedByUser = b.optBoolean("paused", !timerPausedByUser);
            note = timerPausedByUser ? "已暂停计时" : "已恢复计时";
            save();
        } else if ("/api/wrongbook_clear".equals(path)) {
            synchronized (LOCK) {
                wrongBook = new JSONObject();
                note = "错题本已清空";
                save();
            }
        }
        return json("{\"ok\":true}");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void shellAsync(final String cmd) {
        new Thread(new Runnable() {
            @Override public void run() { AdbLocal.shell(cmd); }
        }).start();
    }

    private JSONObject statusJson() throws Exception {
        JSONObject o = new JSONObject();
        synchronized (LOCK) {
            o.put("connected", true);
            o.put("running_on", "box");
            o.put("app_version", APP_VERSION);
            o.put("locked", locked);
            if (locked && current != null) {
                JSONObject q = new JSONObject();
                q.put("q", current.q);
                q.put("subject", current.subject);
                q.put("options", new JSONArray(java.util.Arrays.asList(current.options)));
                q.put("review", currentReview);
                o.put("quiz", q);
            } else {
                o.put("quiz", JSONObject.NULL);
            }
            long now = System.currentTimeMillis();
            boolean penaltyLock = penaltyUntilMillis > now;
            boolean restLock = restUntilMillis > now;
            boolean dailyLock = controlEnabled && (dailyLocked
                    || (!dailyOverride
                        && todaySeconds >= dailyLimitMinutes * 60));
            boolean hard = manualLocked || penaltyLock
                    || (controlEnabled && (restLock || dailyLock));
            AudioManager amgrS = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            o.put("playing", amgrS != null && amgrS.isMusicActive());
            PowerManager pmS = (PowerManager) getSystemService(Context.POWER_SERVICE);
            o.put("screen_on", pmS == null || pmS.isInteractive());
            o.put("timer_paused", timerPausedByUser);
            o.put("pause_on_no_playback", pauseOnNoPlayback);
            o.put("away_no_one_watching", awayNoOneWatching);
            o.put("session_seconds", sessionSeconds);
            o.put("today_seconds", todaySeconds);
            o.put("quiz_seconds", quizSeconds);
            o.put("limit_minutes", limitMinutes);
            o.put("quiz_interval_minutes", limitMinutes);
            o.put("session_limit_minutes", sessionLimitMinutes);
            o.put("daily_limit_minutes", dailyLimitMinutes);
            o.put("rest_minutes", restMinutes);
            o.put("session_remaining_seconds",
                    Math.max(0, sessionLimitMinutes * 60 - sessionSeconds));
            o.put("today_remaining_seconds", dailyLocked ? 0
                    : Math.max(0, dailyLimitMinutes * 60 - todaySeconds));
            o.put("quiz_remaining_seconds",
                    Math.max(0, limitMinutes * 60 - quizSeconds));
            o.put("rest_remaining_seconds",
                    restLock ? (int) ((restUntilMillis - now) / 1000L) : 0);
            o.put("hard_locked", hard);
            o.put("manual_locked", manualLocked);
            o.put("penalty_active", penaltyLock);
            o.put("penalty_remaining_seconds", penaltyLock
                    ? (int) ((penaltyUntilMillis - now) / 1000L) : 0);
            o.put("penalty_count", penaltyCount);
            o.put("next_penalty_minutes",
                    (int) (penaltyMillis(penaltyCount + 1) / 60000L));
            o.put("muted_for_lock", savedMusicVolume >= 0);
            o.put("failed_questions", consecutiveFailedQuestions);
            o.put("lock_reason", manualLocked ? "manual"
                    : (penaltyLock ? "penalty"
                    : (restLock ? "rest" : (dailyLock ? "daily" : ""))));
            o.put("daily_override", dailyOverride);
            o.put("daily_locked", dailyLocked);
            o.put("daily_unlock_at", dailyUnlockAt);
            o.put("daily_reset_time", dailyResetTime);
            o.put("subjects", new JSONArray(java.util.Arrays.asList(subjects())));
            o.put("pick_mode", pickMode);
            o.put("reboot_time", rebootTime);
            o.put("reboot_enabled", rebootEnabled);
            o.put("reboot_days", rebootDaysArray());
            o.put("weather_enabled", prefs.getBoolean("weather_enabled", true));
            o.put("weather_city_code", prefs.getString("weather_city_code", ""));
            o.put("weather_city_name", prefs.getString("weather_city_name", ""));
            o.put("control_enabled", controlEnabled);
            o.put("limit_enabled", controlEnabled);
            o.put("voice_enabled", voiceEnabled);
            o.put("lock_voice_enabled", lockVoiceEnabled);
            o.put("quiz_enabled", quizEnabled);
            o.put("system", systemJson());
            o.put("wrong", wrong);
            JSONObject wbi = wrongBook.optJSONObject("items");
            int wbCount = 0;
            if (wbi != null) {
                java.util.Iterator<String> wk = wbi.keys();
                while (wk.hasNext()) {
                    JSONObject wIt = wbi.optJSONObject(wk.next());
                    if (wIt != null && wIt.optInt("wrong", 0) > 0) {
                        wbCount++;
                    }
                }
            }
            o.put("wrong_book_count", wbCount);
            o.put("answer_total", wrongBook.optInt("ok", 0) + wrongBook.optInt("bad", 0));
            o.put("answer_correct", wrongBook.optInt("ok", 0));
            o.put("note", note);
            o.put("question_count", bank.size());
            int enabledCount = 0;
            for (QuizBank.Q bq : bank.allQuestions()) {
                if (!disabledIds.contains(bq.id)) {
                    enabledCount++;
                }
            }
            o.put("question_enabled_count", enabledCount);
            o.put("quiz_active", overlay.isShowing());
            o.put("quiz_correct", quizCorrect);
            o.put("quiz_required", QUIZ_REQUIRED);
        }
        o.put("clock", new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()));
        return o;
    }

    private byte[] readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return "<h1>index.html missing</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Object-question picture, served so the parent page can show a thumbnail. */
    private byte[] readImage(String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9_\\-]", "");
        if (clean.isEmpty()) {
            return null;
        }
        try {
            InputStream in = getAssets().open("images/" + clean + ".png");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            in.close();
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void respond(OutputStream out, int code, byte[] body) throws Exception {
        respond(out, code, "application/json; charset=utf-8", body);
    }

    private void respond(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
