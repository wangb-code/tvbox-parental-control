package com.tvbox.parental;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Speaks a short explanation when a hard lock starts (or when a rest ends).
 *
 * A hard lock mutes STREAM_MUSIC to guarantee the video stops. The quiz voice
 * already uses STREAM_MUSIC and is confirmed audible, so this player uses the
 * same stream: it briefly lifts the mute for the announcement and restores it
 * as soon as the clip finishes. STREAM_ALARM is NOT used - this vendor firmware
 * forces its speaker volume down to 1/7 during a hard lock, which made the
 * announcement almost inaudible.
 *
 * Re-muting is a no-op while already muted (savedMusicVolume >= 0), so the
 * 3-second tick cannot cut the clip short. onDone lets the service re-apply
 * the mute, and also covers the case where a parent unlocks mid-clip.
 */
public class LockVoice {

    private final Context ctx;
    private final AudioManager am;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onDone;
    private MediaPlayer mp;
    private String lastKey = "";
    private int savedMusic = -1;
    private boolean enabled = true;

    public LockVoice(Context c, Runnable done) {
        ctx = c;
        onDone = done;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    /** Reset de-duplication when the lock is released. */
    public synchronized void clear() {
        lastKey = "";
    }

    public synchronized boolean isPlaying() {
        return mp != null;
    }

    /**
     * Announce a lock state once. The key is the state identity (reason plus
     * its start marker), so tick() calling this every 3 s cannot repeat it.
     */
    public void announce(final String clip, final String key) {
        ui.post(new Runnable() {
            @Override public void run() { play(clip, key); }
        });
    }

    private synchronized void play(String clip, String key) {
        if (!enabled || clip == null || key == null || key.equals(lastKey)) {
            return;
        }
        File f = new File(ctx.getCacheDir(), "audio/" + clip + ".mp3");
        if (!f.isFile() || f.length() < 2000) {
            return;
        }
        lastKey = key;                 // claim it even if playback fails, so
                                       // a broken clip cannot be retried forever
        stopInternal();

        try {
            if (am != null) {
                savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                // Hard lock has this at 0. Use the volume the TV had before
                // the lock (typically 8) so the child hears it clearly.
                if (savedMusic < 1) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0);
                }
            }
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            p.setDataSource(f.getAbsolutePath());
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { finish(m); }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    finish(m);
                    return true;
                }
            });
            p.prepare();
            p.start();
            android.util.Log.i("TVCTL", "lock voice playing: " + clip + " key=" + key);
        } catch (Exception e) {
            android.util.Log.e("TVCTL", "lock voice failed", e);
            finish(mp);
        }
    }

    private synchronized void finish(MediaPlayer m) {
        if (m != null) {
            try { m.release(); } catch (Exception ignored) { }
        }
        if (mp == m) {
            mp = null;
        }
        restoreVolume();
        if (onDone != null) {
            ui.post(onDone);
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    /** Internal variant: never fires onDone, used when replacing a clip. */
    private void stopInternal() {
        if (mp != null) {
            try { mp.setOnCompletionListener(null); } catch (Exception ignored) { }
            try { mp.setOnErrorListener(null); } catch (Exception ignored) { }
            try { mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        restoreVolume();
    }

    private void restoreVolume() {
        if (am != null && savedMusic >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0);
            } catch (Exception ignored) { }
            savedMusic = -1;
        }
    }
}
