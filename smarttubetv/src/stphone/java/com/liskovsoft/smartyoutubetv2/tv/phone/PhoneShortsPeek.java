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
        long position = mPlayer.getCurrentPosition();
        Log.d(TAG, "peek hands over at " + position + " ms, after " + activeFor + " ms");
        main.addVideoListener(new VideoListener() {
            @Override
            public void onRenderedFirstFrame() {
                main.removeVideoListener(this);
                mHandler.post(() -> end(main, null));
            }
        });
        main.seekTo(Math.max(0, position + 80)); // a moment ahead: where the hidden player is when the main one draws
        // Safety net: the main player may not report a new frame (paused, or the same frame)
        mHandler.postDelayed(() -> {
            if (mActive && mHandingOff) {
                end(main, null);
            }
        }, 1500);
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
        if (main != null && mMainMuted) {
            main.setVolume(mMainVolume);
        }
        mMainMuted = false;
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

    private SimpleExoPlayer player() {
        if (mPlayer != null) {
            return mPlayer;
        }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000, 8_000, 500, 1_000) // the start of a Short, not all of it
                .createDefaultLoadControl();
        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext, new DefaultRenderersFactory(mContext),
                new DefaultTrackSelector(), loadControl);
        mPlayer.setVideoTextureView(mTexture);
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
