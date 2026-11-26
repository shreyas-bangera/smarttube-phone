package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The player's sleep timer (gear > Sleep timer), like the YouTube app: after the chosen minutes, or at the end of the
 * video playing now, the video pauses. It runs for the app (the mini player and the background too), until it fires or
 * is turned off.
 */
final class PhoneSleepTimer {
    /** The choices, as in the YouTube app (and a couple of longer ones) */
    static final int[] MINUTES = {10, 15, 20, 30, 45, 60, 90, 120};

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Context sApp;
    /** When it fires (uptime), 0: not by the clock */
    private static long sEndsAt;
    private static boolean sEndOfVideo;
    /** The video the "end of video" timer waits for */
    private static String sVideoId;
    /** The choice made (minutes, -1 the end of the video, 0 none): it has the tick in the list */
    private static int sChoice;

    private PhoneSleepTimer() {
    }

    static boolean isOn() {
        return sEndsAt > 0 || sEndOfVideo;
    }

    static void startMinutes(Context context, int minutes) {
        sApp = context.getApplicationContext();
        sEndOfVideo = false;
        sChoice = minutes;
        sEndsAt = SystemClock.uptimeMillis() + minutes * 60_000L;
        schedule();
    }

    static void startEndOfVideo(Context context, String videoId) {
        sApp = context.getApplicationContext();
        sEndsAt = 0;
        sChoice = -1;
        sEndOfVideo = true;
        sVideoId = videoId;
        schedule();
    }

    static void stop() {
        sChoice = 0;
        sEndsAt = 0;
        sEndOfVideo = false;
        sVideoId = null;
        sHandler.removeCallbacks(sCheck);
    }

    static int choice() {
        return sChoice;
    }

    /** The value shown in the sheet and under the video: Off, "23 min left" or "End of video" */
    static String label(Context context) {
        if (sEndOfVideo) {
            return context.getString(R.string.phone_sleep_end_of_video);
        }
        if (sEndsAt > 0) {
            long left = Math.max(0, sEndsAt - SystemClock.uptimeMillis());
            return context.getString(R.string.phone_sleep_left, duration(context, (int) Math.max(1, (left + 59_999) / 60_000)));
        }
        return context.getString(R.string.phone_off);
    }

    /** A choice's name: "20 minutes", "1 hour", "1 hr 30 min" */
    static String choiceName(Context context, int minutes) {
        if (minutes < 60) {
            return context.getString(R.string.phone_sleep_minutes, minutes);
        }
        if (minutes % 60 == 0) {
            return minutes == 60 ? context.getString(R.string.phone_sleep_hour)
                    : context.getString(R.string.phone_sleep_hours, minutes / 60);
        }
        return duration(context, minutes);
    }

    /** "12 min", "1 hr 6 min", "2 hr" */
    static String duration(Context context, int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return context.getString(R.string.phone_sleep_min, m);
        }
        return m == 0 ? context.getString(R.string.phone_sleep_hr, h) : context.getString(R.string.phone_sleep_hr_min, h, m);
    }

    /** What's left of the video playing now ("1 hr 6 min"), for the "End of video" choice; null when unknown */
    static String videoLeft(Context context) {
        SimpleExoPlayer exo = PhonePlaybackActivity.currentExoPlayer();
        if (exo == null || exo.getDuration() <= 0) {
            return null;
        }
        long left = Math.max(0, exo.getDuration() - exo.getCurrentPosition());
        return duration(context, (int) Math.max(1, (left + 59_999) / 60_000));
    }

    private static void schedule() {
        sHandler.removeCallbacks(sCheck);
        sHandler.postDelayed(sCheck, 1_000);
    }

    private static final Runnable sCheck = new Runnable() {
        @Override
        public void run() {
            if (!isOn()) {
                return;
            }
            SimpleExoPlayer exo = PhonePlaybackActivity.currentExoPlayer();
            boolean fire;
            if (sEndOfVideo) {
                // Just before the end, so the next video (autoplay) doesn't start; or another video already started
                Video video = PhonePlaybackActivity.currentVideoOrNull();
                boolean otherVideo = video != null && sVideoId != null && !sVideoId.equals(video.videoId);
                long duration = exo != null ? exo.getDuration() : 0;
                boolean atEnd = exo != null && (exo.getPlaybackState() == Player.STATE_ENDED
                        || (duration > 0 && duration - exo.getCurrentPosition() < 1_500));
                fire = otherVideo || atEnd;
            } else {
                fire = SystemClock.uptimeMillis() >= sEndsAt;
            }
            if (!fire) {
                sHandler.postDelayed(this, sEndOfVideo ? 500 : 1_000);
                return;
            }
            stop();
            if (exo != null) {
                exo.setPlayWhenReady(false);
            }
            PhonePlaybackActivity.onSleepTimerFired();
            if (sApp != null) {
                android.widget.Toast.makeText(sApp, R.string.phone_sleep_done, android.widget.Toast.LENGTH_LONG).show();
            }
        }
    };
}
