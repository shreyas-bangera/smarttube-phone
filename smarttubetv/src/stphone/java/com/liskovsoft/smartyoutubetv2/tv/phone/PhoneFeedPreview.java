package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Playback in feeds, like the YouTube app: the video card most in view on Home or Subscriptions plays, muted, right in
 * its thumbnail; scroll on and the next one takes over, only ever one at a time. One player and one video surface for
 * the whole feed (the surface moves to the card playing), with the app's own way of getting a video's streams
 * (SmartTube's format info and media sources, as the Shorts' hidden player). The thumbnail stays until the video's
 * first frame is drawn, then the video fades in over it (no spinner); a seek bar shows how far it is and can be
 * dragged, a glass button turns the sound on or off. Stream details are kept and looked up ahead for the next cards. A tap on the card opens the video in the player from where the preview was.
 * Settings > Customize > Playback in feeds: Always (the default), Wi-Fi only or Off.
 */
final class PhoneFeedPreview {
    private static final String TAG = "PhoneFeedPreview";
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** A card plays once this much of its picture is in view, and stops below the second */
    private static final float START_VISIBLE = 0.65f;
    private static final float STOP_VISIBLE = 0.5f;
    /** Scrolling settles this long before a card starts (fast scrolling doesn't start every card it passes) */
    private static final long SETTLE_MS = 220;
    /** Sound on for the previews (the mute button), for as long as the app runs */
    private static boolean sSound;
    /** Where each video's preview got to: scrolling back, it carries on from there */
    private static final LruCache<String, Long> POSITIONS = new LruCache<>(100);
    /** Videos without a playable stream (or live ones, Shorts): their thumbnails stay */
    private static final Set<String> SKIP = new HashSet<>();
    /** Videos' stream details, kept: coming back to a card (or to one looked up ahead) it starts at once */
    private static final LruCache<String, MediaItemFormatInfo> INFO = new LruCache<>(40);
    private static final Set<String> FETCHING = java.util.Collections.synchronizedSet(new HashSet<>());

    interface Host {
        /** The feed on screen plays previews (Home or Subscriptions, nothing else in front) */
        boolean previewsWanted();

        /** The video at this list position, if it's a card that can play in its thumbnail (else null) */
        Video previewVideoAt(int position);
    }

    private final Context mContext;
    private final RecyclerView mList;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final float mD;
    /** The preview: the video, the red line, the sound button and a small spinner, put inside the card's thumbnail */
    private final FrameLayout mView;
    private final TextureView mTexture;
    private final android.widget.SeekBar mSeek;
    private boolean mSeeking;
    private final ImageView mSound;
    private ExoMediaSourceFactory mSources;
    private final Rect mListRect = new Rect();
    private final int[] mAt = new int[2];
    private SimpleExoPlayer mPlayer;
    private DefaultTrackSelector mTrackSelector;
    private Video mVideo;
    private ViewGroup mFrame;
    private boolean mFirstFrame;
    private int mGeneration;
    private boolean mResumed;

    private final Runnable mPick = this::pick;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            tick();
            if (mVideo != null) {
                mHandler.postDelayed(this, 250);
            }
        }
    };

    PhoneFeedPreview(Context context, RecyclerView list, Host host) {
        mContext = context.getApplicationContext();
        mList = list;
        mHost = host;
        mD = context.getResources().getDisplayMetrics().density;

        mView = new FrameLayout(context);
        mView.setClickable(false); // a tap goes to the card (it opens the video)
        mTexture = new TextureView(context);
        mTexture.setAlpha(0f);
        mView.addView(mTexture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A seek bar along the bottom: how far the preview is, and drag it to go elsewhere in the video
        mSeek = new android.widget.SeekBar(context);
        mSeek.setMax(1000);
        mSeek.setPadding(dp(10), 0, dp(10), 0);
        mSeek.setSplitTrack(false);
        int accent = context.getResources().getColor(R.color.phone_accent);
        mSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        mSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        dot.setSize(dp(11), dp(11));
        mSeek.setThumb(dot);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            mSeek.setMinHeight(dp(3));
            mSeek.setMaxHeight(dp(3));
        }
        mSeek.setOnTouchListener((v, event) -> {
            // Dragging the bar: not the feed's scroll, not a tap on the card
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        mSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                mSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                mSeeking = false;
                if (mPlayer != null && mPlayer.getDuration() > 0) {
                    mPlayer.seekTo(mPlayer.getDuration() * seekBar.getProgress() / 1000);
                }
            }
        });
        mSeek.setVisibility(View.GONE);
        FrameLayout.LayoutParams seekParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26), Gravity.BOTTOM);
        seekParams.bottomMargin = -dp(5);
        mView.addView(mSeek, seekParams);

        // Sound on or off: a small glass button in the top right corner
        mSound = new ImageView(context);
        GradientDrawable glass = new GradientDrawable();
        glass.setShape(GradientDrawable.OVAL);
        glass.setColor(0x73000000);
        mSound.setBackground(glass);
        mSound.setPadding(dp(7), dp(7), dp(7), dp(7));
        mSound.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        mSound.setOnClickListener(v -> {
            sSound = !sSound;
            applySound();
        });
        FrameLayout.LayoutParams soundParams = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
        soundParams.setMargins(0, dp(8), dp(8), 0);
        mView.addView(mSound, soundParams);
        applySound();

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                // The one playing scrolled mostly away: it stops now; another one starts once scrolling settles
                if (mVideo != null && visibleFraction(mFrame) < STOP_VISIBLE) {
                    stop("scrolled away");
                }
                schedule();
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                schedule();
            }
        });
        list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                // The card playing was recycled (scrolled far off, or the feed changed): the preview leaves with it
                if (mFrame != null && isInside(mFrame, view)) {
                    stop("card gone");
                }
            }
        });
    }

    // region From the screen

    /** The screen is in front again: look for a card to play */
    void onResume() {
        mResumed = true;
        schedule();
    }

    /** The screen went behind (the player, another page, the home screen, screen off): no preview, no player */
    void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mPick);
        stop("paused");
        release();
    }

    /** The feed changed (new videos, another section): pick again */
    void onFeedChanged() {
        if (mVideo != null && !mHost.previewsWanted()) {
            stop("left the feed");
        }
        schedule();
    }

    /** Leaving the feed (another tab or section): stop at once, before its cards go */
    void stopNow() {
        mHandler.removeCallbacks(mPick);
        stop("section changed");
    }

    /**
     * A card was tapped: if it's the one playing, the player opens it from where the preview is (and the preview
     * stops, so there's never two at once)
     */
    void onVideoOpened(Video video) {
        if (video != null && mVideo != null && video.videoId != null && video.videoId.equals(mVideo.videoId) && mPlayer != null) {
            long position = mPlayer.getCurrentPosition();
            if (position > 1_000) {
                video.pendingPosMs = position;
            }
        }
        mHandler.removeCallbacks(mPick);
        stop("opened");
    }

    // endregion

    // region Choosing the card

    private void schedule() {
        mHandler.removeCallbacks(mPick);
        if (mResumed) {
            mHandler.postDelayed(mPick, SETTLE_MS);
        }
    }

    /** The card most in view (at least START_VISIBLE of its picture), the higher one when two are alike */
    private void pick() {
        if (!mResumed || mList.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING) {
            if (mResumed) {
                schedule(); // still under the finger: wait for it to settle
            }
            return;
        }
        if (!allowed()) {
            stop("not allowed here");
            return;
        }
        Video best = null;
        ViewGroup bestFrame = null;
        float bestShown = 0f;
        for (int i = 0; i < mList.getChildCount(); i++) {
            View child = mList.getChildAt(i);
            int position = mList.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) {
                continue;
            }
            Video video = mHost.previewVideoAt(position);
            if (video == null || video.videoId == null || !playable(video)) {
                continue;
            }
            ViewGroup frame = child.findViewById(R.id.phone_thumb_frame);
            float shown = visibleFraction(frame);
            if (shown >= START_VISIBLE && shown > bestShown + 0.05f) {
                best = video;
                bestFrame = frame;
                bestShown = shown;
            }
        }
        if (best == null) {
            stop("none in view");
            return;
        }
        if (mVideo != null && best.videoId.equals(mVideo.videoId) && bestFrame == mFrame) {
            return; // already playing there
        }
        play(best, bestFrame);
        lookAhead(best);
    }

    /** The next cards' stream details, fetched ahead (in the background): scrolling on, they start at once */
    private void lookAhead(Video current) {
        int found = 0;
        for (int i = 0; i < mList.getChildCount() && found < 2; i++) {
            int position = mList.getChildAdapterPosition(mList.getChildAt(i));
            if (position == RecyclerView.NO_POSITION) {
                continue;
            }
            for (int next = position; next <= position + 3 && found < 2; next++) {
                Video video = mHost.previewVideoAt(next);
                if (video != null && video.videoId != null && !video.videoId.equals(current.videoId) && playable(video)
                        && INFO.get(video.videoId) == null && FETCHING.add(video.videoId)) {
                    found++;
                    String id = video.videoId;
                    LOADER.execute(() -> {
                        try {
                            MediaItemFormatInfo info = com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(id);
                            if (info != null) {
                                INFO.put(id, info);
                            }
                        } catch (Throwable e) {
                            Log.d(TAG, "ahead " + id + ": " + e);
                        } finally {
                            FETCHING.remove(id);
                        }
                    });
                }
            }
            break; // from the first card in view down
        }
    }

    /** The settings, the network and what's on screen allow previews now */
    private boolean allowed() {
        int mode = PhoneTweaks.feedPlayback(mContext);
        if (mode == PhoneTweaks.FEED_OFF || !mHost.previewsWanted()) {
            return false;
        }
        if (mode == PhoneTweaks.FEED_WIFI && !onWifi()) {
            return false;
        }
        // A video playing elsewhere (the mini player, the music bar): no second one in the feed
        SimpleExoPlayer main = PhonePlaybackActivity.currentExoPlayer();
        return main == null || !main.getPlayWhenReady();
    }

    private boolean onWifi() {
        ConnectivityManager manager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }

    /** Live streams, premieres to come and Shorts keep their thumbnails, as do videos that couldn't play */
    private static boolean playable(Video video) {
        return !video.isLive && !video.isUpcoming && !video.isShorts && !SKIP.contains(video.videoId);
    }

    /** How much of the card's picture is in the feed's view (between the bars), 0 to 1 */
    private float visibleFraction(View frame) {
        if (frame == null || !frame.isAttachedToWindow() || frame.getHeight() == 0) {
            return 0f;
        }
        Rect list = mListRect;
        if (!mList.getGlobalVisibleRect(list)) {
            return 0f;
        }
        list.top += mList.getPaddingTop();
        list.bottom -= mList.getPaddingBottom();
        frame.getLocationOnScreen(mAt);
        int top = Math.max(mAt[1], list.top);
        int bottom = Math.min(mAt[1] + frame.getHeight(), list.bottom);
        return Math.max(0, bottom - top) / (float) frame.getHeight();
    }

    private static boolean isInside(View view, View ancestor) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) {
                return true;
            }
        }
        return false;
    }

    // endregion

    // region Playing

    private void play(Video video, ViewGroup frame) {
        stop(null);
        mVideo = video;
        mFrame = frame;
        mFirstFrame = false;
        int generation = ++mGeneration;
        // Into the card's thumbnail, over the picture (under the badge and the progress bar)
        ViewGroup parent = (ViewGroup) mView.getParent();
        if (parent != null) {
            parent.removeView(mView);
        }
        frame.addView(mView, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mView.bringToFront(); // over the card's length badge and watched bar (hidden while it plays, see coverCard)
        mTexture.setAlpha(0f);
        mSeek.setProgress(0);
        mSeek.setVisibility(View.GONE);
        mSound.setVisibility(View.GONE);
        String id = video.videoId;
        Log.d(TAG, "preview " + id);
        MediaItemFormatInfo known = INFO.get(id);
        if (known != null) {
            start(id, known, generation); // looked up already: no wait
            return;
        }
        LOADER.execute(() -> {
            MediaItemFormatInfo info = null;
            try {
                info = com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(id);
            } catch (Throwable e) {
                Log.d(TAG, "preview " + id + ": " + e);
            }
            if (info != null) {
                INFO.put(id, info);
            }
            MediaItemFormatInfo result = info;
            mHandler.post(() -> start(id, result, generation));
        });
    }

    /** The streams are known: the player gets them and plays (silent until the first frame shows) */
    private void start(String id, MediaItemFormatInfo info, int generation) {
        if (generation != mGeneration) {
            return; // scrolled on meanwhile
        }
        MediaSource source = null;
        if (info != null && !info.isUnplayable() && !info.isLive()) {
            try {
                if (mSources == null) {
                    mSources = new ExoMediaSourceFactory(mContext);
                }
                source = info.containsDashFormats() ? mSources.fromDashFormatInfo(info)
                        : info.containsSabrFormats() ? mSources.fromSabrFormatInfo(info) : null;
            } catch (Throwable e) {
                Log.d(TAG, "preview " + id + " source: " + e);
            }
        }
        if (source == null) {
            SKIP.add(id); // its thumbnail stays (and it's not tried again)
            INFO.remove(id);
            stop("no stream");
            return;
        }
        SimpleExoPlayer player = player();
        applySound();
        player.setPlayWhenReady(true);
        player.prepare(source, true, true);
        Long resume = POSITIONS.get(id);
        if (resume != null && resume > 0) {
            player.seekTo(resume);
        }
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    /** Stops the preview (the thumbnail shows again); the player stays for the next card */
    private void stop(String reason) {
        mGeneration++;
        mHandler.removeCallbacks(mTick);
        if (mVideo != null) {
            if (reason != null) {
                Log.d(TAG, "preview stops: " + reason);
            }
            if (mPlayer != null && mFirstFrame && mVideo.videoId != null) {
                POSITIONS.put(mVideo.videoId, mPlayer.getCurrentPosition());
            }
        }
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
            mPlayer.stop(true); // its streams and buffers go
        }
        mTexture.animate().cancel();
        mTexture.setAlpha(0f);
        coverCard(false);
        ViewGroup parent = (ViewGroup) mView.getParent();
        if (parent != null) {
            parent.removeView(mView);
        }
        mVideo = null;
        mFrame = null;
        mFirstFrame = false;
    }

    /** While the video plays, the card's length badge and watched bar step aside for the seek bar (and come back) */
    private void coverCard(boolean playing) {
        if (mFrame == null) {
            return;
        }
        for (int id : new int[] {R.id.phone_badge, R.id.phone_progress}) {
            View view = mFrame.findViewById(id);
            if (view == null) {
                continue;
            }
            if (playing) {
                if (view.getVisibility() == View.VISIBLE) {
                    view.setTag(R.id.phone_thumb_frame, Boolean.TRUE);
                    view.setVisibility(View.INVISIBLE);
                }
            } else if (Boolean.TRUE.equals(view.getTag(R.id.phone_thumb_frame))) {
                view.setTag(R.id.phone_thumb_frame, null);
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    /** No player at all (the screen is behind): its decoders and memory go back */
    private void release() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
            mTrackSelector = null;
        }
    }

    /** While playing: the red line follows, and at the end the preview starts over */
    private void tick() {
        if (mPlayer == null || mVideo == null) {
            return;
        }
        long duration = mPlayer.getDuration();
        long position = mPlayer.getCurrentPosition();
        if (duration > 0 && !mSeeking) {
            mSeek.setProgress((int) Math.max(0, Math.min(1000, position * 1000 / duration)));
        }
    }

    private void applySound() {
        mSound.setImageResource(sSound ? R.drawable.ic_phone_volume : R.drawable.ic_phone_volume_off);
        mSound.setContentDescription(mContext.getString(sSound ? R.string.phone_feed_sound_off : R.string.phone_feed_sound_on));
        if (mPlayer != null) {
            mPlayer.setVolume(sSound ? 1f : 0f);
        }
    }

    private SimpleExoPlayer player() {
        if (mPlayer != null) {
            return mPlayer;
        }
        // A small buffer (a preview, not the whole video) that starts on very little, and at most 480p (plenty for a
        // thumbnail): quick to start, light on data, the decoder and the battery
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 8_000, 250, 1_000)
                .createDefaultLoadControl();
        mTrackSelector = new DefaultTrackSelector();
        mTrackSelector.setParameters(mTrackSelector.buildUponParameters().setMaxVideoSize(854, 480).setMaxVideoFrameRate(30));
        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext, new DefaultRenderersFactory(mContext), mTrackSelector, loadControl);
        mPlayer.setVideoTextureView(mTexture);
        mPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        mPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        mPlayer.addVideoListener(new VideoListener() {
            @Override
            public void onRenderedFirstFrame() {
                if (mVideo == null || mFirstFrame) {
                    return;
                }
                mFirstFrame = true;
                // The video fades in over the thumbnail: no black frame, no jump, no spinner
                mTexture.animate().alpha(1f).setDuration(200).start();
                mSound.setVisibility(View.VISIBLE);
                mSeek.setVisibility(View.VISIBLE);
                coverCard(true);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                scaleToCard(width * pixelWidthHeightRatio, height);
            }
        });
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerError(ExoPlaybackException error) {
                Log.d(TAG, "preview " + (mVideo != null ? mVideo.videoId : "") + " error: " + error.getMessage());
                if (mVideo != null && mVideo.videoId != null) {
                    SKIP.add(mVideo.videoId);
                }
                stop("error");
            }
        });
        return mPlayer;
    }

    /** The video fills the card's picture like the thumbnail does (cut at the sides or top, never stretched) */
    private void scaleToCard(float videoWidth, float videoHeight) {
        int w = mTexture.getWidth();
        int h = mTexture.getHeight();
        if (w == 0 || h == 0 || videoWidth <= 0 || videoHeight <= 0) {
            mTexture.post(() -> {
                if (mTexture.getWidth() > 0) {
                    scaleToCard(videoWidth, videoHeight);
                }
            });
            return;
        }
        float viewRatio = w / (float) h;
        float videoRatio = videoWidth / videoHeight;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        if (videoRatio > viewRatio) {
            matrix.setScale(videoRatio / viewRatio, 1f, w / 2f, h / 2f);
        } else {
            matrix.setScale(1f, viewRatio / videoRatio, w / 2f, h / 2f);
        }
        mTexture.setTransform(matrix);
    }

    // endregion

    private int dp(float value) {
        return Math.round(value * mD);
    }
}
