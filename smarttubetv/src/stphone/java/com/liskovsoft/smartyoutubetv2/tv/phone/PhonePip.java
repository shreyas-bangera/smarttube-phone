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
    // region Picture in picture opened inside the app (the player menu's row, as in NewTube)

    /** Screens of ours on screen (started), other than the player */
    private static int sStartedScreens;
    /**
     * The player floats over our own screens because the user asked from its menu: one of our screens getting focus then
     * is not the app being opened again (which expands the floating player). Ends when the app is left.
     */
    private static boolean sInAppPip;

    static void onMenuPipEntered(boolean screenUnderPlayer) {
        sInAppPip = screenUnderPlayer;
    }

    static void onPipEnded() {
        sInAppPip = false;
    }

    /** Called for every screen but the player, from the app's lifecycle callbacks */
    static void onScreenStarted() {
        sStartedScreens++;
    }

    static void onScreenStopped(Activity activity) {
        sStartedScreens = Math.max(0, sStartedScreens - 1);
        if (sStartedScreens == 0 && !activity.isChangingConfigurations() && sInAppPip) {
            sInAppPip = false; // the app was left: opening it again expands the floating player
            Log.d("PhonePip", "app left with the player floating");
        }
    }

    /**
     * One of our screens got focus. If the player floats over another app or the home screen and the app was opened
     * again (its icon, recents), the video comes back full screen, as in NewTube. Focus, not resume: it only lands on our
     * screen when the user really comes back.
     */
    static void onScreenFocused() {
        PhonePlaybackActivity player = PhonePlaybackActivity.floatingPlayer();
        if (player != null && !sInAppPip) {
            player.restoreFromWindow();
        }
    }

    // endregion

    // region What the floating window shows (for the debug log)

    private static int sFrames;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Frames the player's video decoder has put out so far */
    static int rendered() {
        com.google.android.exoplayer2.SimpleExoPlayer exo = PhonePlaybackActivity.currentExoPlayer();
        com.google.android.exoplayer2.decoder.DecoderCounters counters = exo != null ? exo.getVideoDecoderCounters() : null;
        if (counters == null) {
            return 0;
        }
        counters.ensureUpdated();
        return counters.renderedOutputBufferCount;
    }

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
        int[] last = {sFrames, rendered()};
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isDestroyed() || !activity.isInPictureInPictureMode()) {
                    Log.d("PhonePip", who + " window watch ended");
                    return;
                }
                com.google.android.exoplayer2.SimpleExoPlayer exo = PhonePlaybackActivity.currentExoPlayer();
                int rendered = rendered();
                // "decoded" = frames the player put out; "shown" = frames a view drew on screen
                Log.d("PhonePip", who + " window: " + (rendered - last[1]) + " decoded, " + (sFrames - last[0]) + " shown, "
                        + (exo == null ? "no player" : "play " + exo.getPlayWhenReady() + ", state " + exo.getPlaybackState()
                        + ", at " + exo.getCurrentPosition() / 1000 + "s, video " + (exo.getVideoFormat() != null))
                        + ", " + PhoneMiniPlayer.debugState());
                last[0] = sFrames;
                last[1] = rendered;
                sHandler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    // endregion
}
