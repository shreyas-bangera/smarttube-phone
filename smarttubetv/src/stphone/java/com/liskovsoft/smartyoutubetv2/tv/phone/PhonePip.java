package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/** Android's own "Allow picture-in-picture" switch for the app (Settings > Apps > Special app access) */
final class PhonePip {
    private PhonePip() {
    }

    /** False when the user turned picture in picture off for StreamTube in Android's settings */
    static boolean systemAllows(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return true;
            }
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            Log.d("PhonePip", "allowed? " + e);
            return true;
        }
    }

    /** Android's picture-in-picture switch for this app (or the app's info page where that isn't available) */
    static void openSystemSettings(Activity activity) {
        Uri app = Uri.parse("package:" + activity.getPackageName());
        try {
            activity.startActivity(new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", app));
        } catch (RuntimeException e) {
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
            } catch (RuntimeException ignored) {
                // no settings screen
            }
        }
    }
    // region What the floating window shows (for the debug log)

    private static int sFrames;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** A view showing the video drew a new frame */
    static void frameShown() {
        sFrames++;
    }

    /** Logs every 2 seconds while the screen is in the floating window: new frames, and what the player is doing */
    static void watch(Activity activity) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        String who = activity.getClass().getSimpleName();
        int[] last = {sFrames};
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isDestroyed() || !activity.isInPictureInPictureMode()) {
                    Log.d("PhonePip", who + " window watch ended");
                    return;
                }
                com.google.android.exoplayer2.SimpleExoPlayer exo = PhonePlaybackActivity.currentExoPlayer();
                Log.d("PhonePip", who + " window: " + (sFrames - last[0]) + " new frames, "
                        + (exo == null ? "no player" : "play " + exo.getPlayWhenReady() + ", state " + exo.getPlaybackState()
                        + ", at " + exo.getCurrentPosition() / 1000 + "s, video " + (exo.getVideoFormat() != null)));
                last[0] = sFrames;
                sHandler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    // endregion
}
