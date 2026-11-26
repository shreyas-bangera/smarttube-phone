package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;

/**
 * Shorts that start the moment they're swiped to (as in MeeTube): while one Short plays, a second, hidden player
 * loads the next one and stops on its first frame. On the swipe that player is shown and plays at once, while the main
 * player loads the same Short muted behind it; once the main player is ready it jumps to the same moment, gets the
 * sound, and the hidden player steps aside (and starts on the Short after).
 */
final class PhoneShortsPeek {
    private static final String TAG = "PhoneShorts";
    private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout mView;
    private final AspectRatioFrameLayout mFrame;
    private final TextureView mTexture;
    private SimpleExoPlayer mPlayer;
    private DefaultTrackSelector mTrackSelector;
    /** The Short the hidden player holds, or is loading */
    private String mVideoId;
    private boolean mFirstFrame;
    private boolean mFailed;
    /** Showing and playing in place of the main player (between the swipe and the hand-off) */
    private boolean mActive;
    private long mActiveSince;
    private float mMainVolume = 1f;
    private boolean mMainMuted;
    /** The main player went through loading after the swipe (so "ready" is the new Short, not the old one) */
    private boolean mMainReloaded;
    private boolean mHandingOff;
    private int mGeneration;
    private Runnable mOnDone;

    PhoneShortsPeek(Context context, FrameLayout parent, int index) {
        mContext = context.getApplicationContext();
        // Always laid out (a hidden view gets no video surface, so no first frame could be ready), but see-through
        // until it plays: the video is invisible and there's no background
        mView = new FrameLayout(context);
        mView.setClickable(false);
        mFrame = new AspectRatioFrameLayout(context);
        mFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        mTexture = new TextureView(context);
        mTexture.setAlpha(0f);
        mFrame.addView(mTexture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mView.addView(mFrame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        parent.addView(mView, index, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    View view() {
        return mView;
    }

    boolean isActive() {
        return mActive;
    }

    /** The hidden player holds this Short, its first frame drawn: it can come in live while the user swipes to it */
    boolean hasFrameOf(String videoId) {
        return !mActive && videoId != null && videoId.equals(mVideoId) && mFirstFrame && !mFailed && mPlayer != null;
    }

    /** While swiping: the waiting Short's first frame shows (as the page coming in), or is hidden again */
    void showWaiting(boolean show) {
        if (!mActive) {
            mTexture.setAlpha(show ? 1f : 0f);
        }
    }

    /** Loads this Short in the hidden player, paused on its first frame (nothing happens if it's already there) */
    void prepare(String videoId) {
        if (videoId == null || mActive || videoId.equals(mVideoId)) {
            return;
        }
        mVideoId = videoId;
        mFirstFrame = false;
        mFailed = false;
        int generation = ++mGeneration;
        if (mPlayer != null) {
            mPlayer.stop(true);
        }
        long start = SystemClock.elapsedRealtime();
        LOADER.execute(() -> {
            MediaItemFormatInfo info = null;
            try {
                info = com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(videoId);
            } catch (Throwable e) {
                Log.d(TAG, "peek " + videoId + ": " + e);
            }
            MediaItemFormatInfo result = info;
            mHandler.post(() -> {
                if (generation != mGeneration || mActive) {
                    return;
                }
                if (result == null || result.isUnplayable()) {
                    mFailed = true;
                    return;
                }
                MediaSource source;
                try {
                    ExoMediaSourceFactory factory = new ExoMediaSourceFactory(mContext);
                    source = result.containsDashFormats() ? factory.fromDashFormatInfo(result)
                            : result.containsSabrFormats() ? factory.fromSabrFormatInfo(result) : null;
                } catch (Throwable e) {
                    Log.d(TAG, "peek " + videoId + " source: " + e);
                    source = null;
                }
                if (source == null) {
                    mFailed = true;
                    return;
                }
                SimpleExoPlayer player = player();
                player.setVolume(0f);
                player.setPlayWhenReady(false);
                mTrackSelector.setParameters(mTrackSelector.buildUponParameters().clearSelectionOverrides());
                player.prepare(source, true, true);
                Log.d(TAG, "peek " + videoId + " loading (info in " + (SystemClock.elapsedRealtime() - start) + " ms)");
            });
        });
    }

    /**
     * The swipe landed on this Short: if the hidden player has it, it's shown and plays now (the main player is muted
     * until the hand-off). Returns whether it took over.
     */
    boolean takeOver(String videoId, SimpleExoPlayer main, Runnable onDone) {
        if (videoId == null || !videoId.equals(mVideoId) || mPlayer == null || mFailed || main == null) {
            Log.d(TAG, "peek: not ready for " + videoId + " (holds " + mVideoId + (mFailed ? ", failed" : "") + ")");
            return false;
        }
        int state = mPlayer.getPlaybackState();
        if (state != Player.STATE_READY && state != Player.STATE_BUFFERING) {
            return false;
        }
        mActive = true;
        mActiveSince = SystemClock.elapsedRealtime();
        mHandingOff = false;
        mMainReloaded = false;
        mOnDone = onDone;
        mMainVolume = main.getVolume();
        main.setVolume(0f);
        mMainMuted = true;
        mPlayer.setVolume(mMainVolume > 0 ? mMainVolume : 1f);
        mPlayer.setPlayWhenReady(true);
        mTexture.setAlpha(1f);
        if (mFirstFrame) {
            mView.setBackgroundColor(Color.BLACK); // until then the Short's thumbnail shows through
        }
        Log.d(TAG, "peek plays " + videoId + (state == Player.STATE_READY ? " (ready)" : " (still loading)"));
        return true;
    }

    /**
     * Called often while active: once the main player has loaded this Short, it jumps to where the hidden player is,
     * gets the sound back and draws; the hidden player then steps aside.
     */
    void tick(SimpleExoPlayer main, boolean mainOnThisShort) {
        if (!mActive || main == null) {
            return;
        }
        long activeFor = SystemClock.elapsedRealtime() - mActiveSince;
        if (activeFor > 20_000) {
            end(main, "timeout");
            return;
        }
        if (mHandingOff) {
            return;
        }
        if (main.getVolume() > 0f) {
            // The app set the main player's volume for the new video: that's the volume to hand back
            mMainVolume = main.getVolume();
            main.setVolume(0f);
            mPlayer.setVolume(mMainVolume);
        }
        int state = main.getPlaybackState();
        if (mainOnThisShort && state != Player.STATE_READY) {
            mMainReloaded = true;
        }
        if (!mainOnThisShort || !mMainReloaded || state != Player.STATE_READY) {
            return;
        }
        mHandingOff = true;
        int generation = ++mHandOffGeneration;
        // The main player waits, paused, a moment ahead of the hidden one (its frame already drawn behind it); the
        // instant the hidden player gets there, the main one starts and the hidden one goes: no jump back, no gap
        mMainPausedByUs = true;
        mMainStarted = false;
        main.setPlayWhenReady(false);
        long target = mPlayer.getCurrentPosition() + leadMs();
        main.seekTo(target);
        Log.d(TAG, "peek hands over at " + target + " ms, after " + activeFor + " ms");
        mHandler.post(() -> meet(main, generation, target, SystemClock.elapsedRealtime()));
    }

    /** The meeting point is this far ahead: enough for the main player to get there and still be started early */
    private static long leadMs() {
        return Math.max(600, sStartEarlyMs + 400);
    }
    private boolean mMainPausedByUs;
    private int mHandOffGeneration;

    /**
     * How early the main player is started: it takes a while before its picture and sound really move (about 0.4 s
     * on a OnePlus). Learned from each hand-off, so the two players meet at the same moment on any phone.
     */
    private static long sStartEarlyMs = 430;
    private boolean mMainStarted;

    private void meet(SimpleExoPlayer main, int generation, long target, long since) {
        if (!mActive || generation != mHandOffGeneration) {
            return;
        }
        long position = mPlayer.getCurrentPosition();
        boolean mainReady = main.getPlaybackState() == Player.STATE_READY;
        if (mMainStarted) {
            // Both play now (the main one still silent, under the hidden one): the switch happens once the main
            // player's picture really moves, so there's no frozen frame and no gap in the sound
            if (main.getCurrentPosition() >= target + 30 || SystemClock.elapsedRealtime() - since > 5_000) {
                long mainPosition = main.getCurrentPosition();
                long drift = position - mainPosition; // > 0: the main player is behind (it would repeat a moment)
                Log.d(TAG, "peek switch: main at " + mainPosition + " ms, hidden at " + position + " ms, start "
                        + sStartEarlyMs + " ms early");
                if (Math.abs(drift) < 1_000) {
                    sStartEarlyMs = Math.max(50, Math.min(1_200, sStartEarlyMs + drift * 3 / 4));
                }
                main.setVolume(mMainVolume);
                mMainMuted = false;
                end(main, null);
                return;
            }
            mHandler.postDelayed(() -> meet(main, generation, target, since), 10);
            return;
        }
        if (mainReady && position >= target - sStartEarlyMs) {
            mMainStarted = true;
            mMainPausedByUs = false;
            main.setPlayWhenReady(true); // silent until the switch
            mHandler.postDelayed(() -> meet(main, generation, target, since), 10);
            return;
        }
        if (SystemClock.elapsedRealtime() - since > 4_000) {
            end(main, "hand-off timeout");
            return;
        }
        if (!mainReady && position > target - 100) {
            // The main player isn't there yet: a new meeting point a bit further on
            long next = position + leadMs();
            main.seekTo(next);
            mHandler.postDelayed(() -> meet(main, generation, next, since), 16);
            return;
        }
        mHandler.postDelayed(() -> meet(main, generation, target, since), 16);
    }

    /** Back to the main player only (the hand-off is done, or the swipe went elsewhere) */
    void end(SimpleExoPlayer main, String reason) {
        if (!mActive) {
            return;
        }
        mActive = false;
        mHandingOff = false;
        if (reason != null) {
            Log.d(TAG, "peek ends: " + reason);
        }
        mHandOffGeneration++; // a pending meeting is off
        if (main != null && mMainMuted) {
            main.setVolume(mMainVolume);
        }
        mMainMuted = false;
        if (main != null && mMainPausedByUs) {
            // Cut short mid hand-off: the main player plays on (not when the Shorts were left: then it stays quiet)
            boolean left = reason != null && (reason.startsWith("left") || reason.startsWith("released"));
            if (!left) {
                main.setPlayWhenReady(true);
            }
        }
        mMainPausedByUs = false;
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
            mPlayer.setVolume(0f);
        }
        mTexture.setAlpha(0f);
        mView.setBackground(null);
        mVideoId = null; // used: the next prepare() loads the Short after
        Runnable done = mOnDone;
        mOnDone = null;
        if (done != null && reason == null) {
            done.run(); // handed over: the main player shows this Short now
        }
    }

    void release(SimpleExoPlayer main) {
        end(main, "released");
        mGeneration++;
        mVideoId = null;
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    /**
     * Shorts often come with dubbed sound tracks in other languages: like the main player, take the original one
     * (otherwise the first second could be in another language until the main player takes over)
     */
    private void selectOriginalAudio() {
        com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo info =
                mTrackSelector != null ? mTrackSelector.getCurrentMappedTrackInfo() : null;
        if (info == null) {
            return;
        }
        for (int renderer = 0; renderer < info.getRendererCount(); renderer++) {
            if (info.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            com.google.android.exoplayer2.source.TrackGroupArray groups = info.getTrackGroups(renderer);
            if (groups.length < 2 && (groups.length == 0 || groups.get(0).length < 2)) {
                return; // one sound track: nothing to choose
            }
            DefaultTrackSelector.SelectionOverride current = mTrackSelector.getParameters().getSelectionOverride(renderer, groups);
            for (int g = 0; g < groups.length; g++) {
                com.google.android.exoplayer2.source.TrackGroup group = groups.get(g);
                for (int t = 0; t < group.length; t++) {
                    String language = group.getFormat(t).language;
                    if (language != null && language.toLowerCase().contains("original")) {
                        if (current != null && current.groupIndex == g) {
                            return; // already on it
                        }
                        Log.d(TAG, "peek " + mVideoId + ": original sound " + language);
                        mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                                .setSelectionOverride(renderer, groups, new DefaultTrackSelector.SelectionOverride(g, t)));
                        return;
                    }
                }
            }
            return;
        }
    }

    private SimpleExoPlayer player() {
        if (mPlayer != null) {
            return mPlayer;
        }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000, 8_000, 500, 1_000) // the start of a Short, not all of it
                .createDefaultLoadControl();
        mTrackSelector = new DefaultTrackSelector();
        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext, new DefaultRenderersFactory(mContext), mTrackSelector, loadControl);
        mPlayer.setVideoTextureView(mTexture);
        // The same loudness boost as the main player (Settings: volume above 100%, auto volume): otherwise the sound
        // gets louder at the hand-off
        try {
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData data = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(mContext);
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData tweaks = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.instance(mContext);
            float volume = tweaks.isPlayerAutoVolumeEnabled() ? data.getPlayerVolume() * 2.0f : data.getPlayerVolume();
            if (volume > 1f) {
                mPlayer.addAudioListener(new com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster(true, volume, mPlayer));
            }
        } catch (Throwable e) {
            Log.d(TAG, "peek: no loudness boost: " + e);
        }
        mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        mPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        mPlayer.addVideoListener(new VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                if (width > 0 && height > 0) {
                    mFrame.setAspectRatio(width * pixelWidthHeightRatio / height);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (!mFirstFrame) {
                    mFirstFrame = true;
                    Log.d(TAG, "peek " + mVideoId + " first frame ready");
                }
                if (mActive) {
                    mView.setBackgroundColor(Color.BLACK);
                }
            }
        });
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onTracksChanged(com.google.android.exoplayer2.source.TrackGroupArray trackGroups,
                                        com.google.android.exoplayer2.trackselection.TrackSelectionArray trackSelections) {
                selectOriginalAudio();
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                Log.d(TAG, "peek " + mVideoId + " error: " + error.getMessage());
                mFailed = true;
                if (mActive) {
                    end(PhonePlaybackActivity.currentExoPlayer(), "error");
                }
            }
        });
        return mPlayer;
    }
}
