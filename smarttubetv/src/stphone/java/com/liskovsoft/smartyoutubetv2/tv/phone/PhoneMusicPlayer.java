package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * The music player (songs opened from the Music page), in the style of YouTube Music and Spotify: a background glowing
 * in the colours of the song's artwork, gently pulsing while it plays; the big artwork (swipe it sideways for the next
 * or previous song); title, artist and like; the line being sung, in a coloured pill under the title (tap it: the
 * lyrics take the artwork's place, the line being sung lit up; the little artwork in their corner brings it back); a
 * seek bar; shuffle, previous, play, next and repeat.
 *
 * It's a layer over the video player screen: the same player plays underneath, so the queue, background playback and
 * the mini player (a music pill, see PhoneMusicPill) work as for videos. "Video" (top right) shows the video instead.
 */
final class PhoneMusicPlayer {
    interface Host {
        SimpleExoPlayer exo();

        Video video();

        void togglePlay();

        void next();

        void previous();

        /** Down arrow: the player goes small (the music pill) */
        void minimize();

        /** "Video": back to the video player for this song */
        void showVideo();

        /** Shuffle or repeat was turned on or off */
        void onModesChanged();

        /** The songs that play after this one, in order (shuffle's pick first) */
        List<Video> upNext();

        /** The songs played before this one, oldest first (Cover Flow's left side) */
        List<Video> before();

        /** A song picked in Up next */
        void play(Video video);
    }

    private static final long TICK_MS = 200;

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout mRoot;
    private final View mBackground;
    /** The artwork itself, blurred and much bigger, turning slowly behind everything (Android 12 and later) */
    private final ImageView mSkyArt;
    private ValueAnimator mSkyTurn;
    /** Clouds of the song's colours drifting over it */
    private final PhoneMusicFx.Sky mSky;
    /** The play button's glow */
    private final PhoneMusicFx.Halo mPlayHalo;
    /** The coloured glow under the other buttons when pressed */
    private final android.graphics.drawable.RippleDrawable[] mPressGlows = new android.graphics.drawable.RippleDrawable[2];
    /** The song's mood, drawn faintly behind everything (see PhoneMood) */
    private final PhoneMood.Backdrop mMood;
    private final LinearLayout mColumn;
    private final FrameLayout mStage;
    private final FrameLayout mArtFrame;
    private final ImageView mArt;
    /** Behind a music video's thumbnail (shown whole): the same picture, blurred, filling the square */
    private final ImageView mArtFill;
    private final FrameLayout mLyricsPanel;
    private final ScrollView mLyricsScroll;
    private final LinearLayout mLyricsList;
    private final ImageView mBackToArt;
    private final TextView mSource;
    private final TextView mTitle;
    private final TextView mArtist;
    private final LinearLayout mLyricChip;
    private final Bars mChipBars;
    private final TextView mChipLine;
    private final SeekBar mSeek;
    private final TextView mElapsed;
    private final TextView mLeft;
    private final ImageView mPlay;
    private final ImageView mShuffle;
    private final ImageView mRepeat;
    /** Behind the artwork: the song's colours glowing around it */
    private final PhoneMusicFx.Aura mAura;
    /** Over the artwork: a glare sweeping across it when a song starts */
    private final PhoneMusicFx.Glare mGlare = new PhoneMusicFx.Glare();
    /** Under the controls: the next song, tap for the whole queue */
    private final LinearLayout mUpNext;
    private final TextView mUpNextTitle;
    private final PhoneMusicQueue mQueue;
    /** Cover Flow: the player in landscape, and over it in portrait from a long press on the artwork */
    private final PhoneMusicFlow mFlow;
    private final ScrollView mScroll;
    private int mUpNextTicks;

    private boolean mShown;
    private boolean mSeeking;
    private boolean mLyricsOpen;
    private String mVideoId;
    private PhoneLyrics.Lyrics mLyrics;
    private int mLyricIndex = -2;
    /** How much later than their own times the lyrics show for this song (set by the user, kept per song) */
    /** timed() when the lines were last drawn: they're redrawn when it changes (the length becomes known) */
    private boolean mDrawnTimed;
    /** Behind the sync control and the little artwork: the panel's colour fading out, the lines slide under it */
    private View mLyricsHeader;
    private int mColor = 0xFF3A3A3A;
    /** Playing: the background's turn and the artwork's size follow it */
    private boolean mPulsing;
    /** After a swipe: the side the next artwork comes in from (1 from the right, -1 from the left), 0 none */
    private int mEnterFrom;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mShown) {
                return;
            }
            update();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    PhoneMusicPlayer(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
        Context context = activity;

        mRoot = new FrameLayout(context);
        mRoot.setClickable(true); // touches stay here
        mRoot.setVisibility(View.GONE);
        mRoot.setBackgroundColor(Color.BLACK);

        mBackground = new View(context);
        mRoot.addView(mBackground, match());

        // The artwork, blurred and much bigger, turning slowly: its own colours fill the screen
        // (drawn small and scaled up: the blur works on a small picture, cheap, and it's all softness anyway)
        mSkyArt = new ImageView(context);
        mSkyArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int skyBox = context.getResources().getDisplayMetrics().widthPixels / 3;
        float skyScale = Math.max(context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels) * 1.5f / skyBox;
        mSkyArt.setScaleX(skyScale);
        mSkyArt.setScaleY(skyScale);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            float blur = dp(60) / skyScale;
            mSkyArt.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.MIRROR));
            mSkyArt.setAlpha(0.55f);
        } else {
            mSkyArt.setVisibility(View.GONE); // no blur: the clouds alone
        }
        mRoot.addView(mSkyArt, new FrameLayout.LayoutParams(skyBox, skyBox, Gravity.CENTER));
        // Clouds of the song's colours drifting over it
        mSky = new PhoneMusicFx.Sky(context);
        mRoot.addView(mSky, match());
        mMood = new PhoneMood.Backdrop(context);
        mRoot.addView(mMood, match());

        ScrollView scroll = new ScrollView(context);
        mScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mRoot.addView(scroll, match());
        mColumn = new LinearLayout(context);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        mColumn.setPadding(dp(24), 0, dp(24), dp(24));
        scroll.addView(mColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top: down arrow, "Playing from …", Video
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(8), 0, dp(8));
        ImageView down = icon(context, R.drawable.ic_phone_collapse, 26);
        down.setOnClickListener(v -> mHost.minimize());
        down.setContentDescription(context.getString(R.string.phone_music_minimize));
        top.addView(down, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout playing = new LinearLayout(context);
        playing.setOrientation(LinearLayout.VERTICAL);
        playing.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView playingFrom = text(context, 11, 0xB3FFFFFF, false);
        playingFrom.setText(R.string.phone_music_playing_from);
        playingFrom.setLetterSpacing(0.12f);
        playingFrom.setAllCaps(true);
        playing.addView(playingFrom);
        mSource = text(context, 14, Color.WHITE, true);
        mSource.setSingleLine(true);
        mSource.setEllipsize(TextUtils.TruncateAt.END);
        playing.addView(mSource);
        top.addView(playing, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView video = text(context, 13, Color.WHITE, true);
        video.setText(R.string.phone_music_video);
        video.setGravity(Gravity.CENTER);
        video.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable videoBg = new GradientDrawable();
        videoBg.setCornerRadius(dp(16));
        videoBg.setColor(0x33FFFFFF);
        video.setBackground(videoBg);
        video.setOnClickListener(v -> mHost.showVideo());
        top.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        mColumn.addView(top);

        // The stage: the artwork, or the lyrics in its place
        int stageSize = context.getResources().getDisplayMetrics().widthPixels - dp(48);
        mStage = new FrameLayout(context);
        mStage.setClipChildren(false);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(stageSize, stageSize);
        stageParams.topMargin = dp(20);
        stageParams.gravity = Gravity.CENTER_HORIZONTAL;
        mColumn.addView(mStage, stageParams);
        mColumn.setClipChildren(false);
        mColumn.setClipToPadding(false);
        mAura = new PhoneMusicFx.Aura(context);
        int auraSize = Math.round(stageSize * 1.8f);
        mStage.addView(mAura, new FrameLayout.LayoutParams(auraSize, auraSize, Gravity.CENTER));

        mArtFrame = new FrameLayout(context);
        mArtFrame.setElevation(dp(24));
        rounded(mArtFrame, dp(14));
        mArtFrame.setBackgroundColor(0x22FFFFFF);
        mArtFill = new ImageView(context);
        mArtFill.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArtFill.setVisibility(View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            mArtFill.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(28), dp(28), android.graphics.Shader.TileMode.CLAMP));
            mArtFill.setAlpha(0.85f);
        } else {
            mArtFill.setAlpha(0.35f);
        }
        mArtFrame.addView(mArtFill, match());
        mArt = new ImageView(context);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArtFrame.addView(mArt, match());
        mStage.addView(mArtFrame, match());
        mArtFrame.setForeground(mGlare);
        mArtFrame.setContentDescription(context.getString(R.string.phone_music_artwork));
        setupArtSwipe(scroll);

        mLyricsPanel = new FrameLayout(context);
        mLyricsPanel.setVisibility(View.GONE);
        mLyricsPanel.setElevation(dp(24));
        rounded(mLyricsPanel, dp(14));
        mLyricsScroll = new ScrollView(context);
        mLyricsScroll.setVerticalScrollBarEnabled(false);
        mLyricsScroll.setVerticalFadingEdgeEnabled(true);
        mLyricsScroll.setFadingEdgeLength(dp(48));
        mLyricsList = new LinearLayout(context);
        mLyricsList.setOrientation(LinearLayout.VERTICAL);
        mLyricsList.setPadding(dp(22), stageSize / 3, dp(22), stageSize / 2);
        mLyricsScroll.addView(mLyricsList);
        mLyricsPanel.addView(mLyricsScroll, match());
        // Back to the artwork: a little copy of it in the corner
        mBackToArt = new ImageView(context);
        mBackToArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(mBackToArt, dp(10));
        mBackToArt.setElevation(dp(6));
        mBackToArt.setOnClickListener(v -> showLyrics(false));
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        backParams.setMargins(0, dp(12), dp(12), 0);
        mLyricsPanel.addView(mBackToArt, backParams);
        mLyricsHeader = new View(context);
        mLyricsPanel.addView(mLyricsHeader, mLyricsPanel.indexOfChild(mBackToArt),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88), Gravity.TOP));
        mStage.addView(mLyricsPanel, match());

        // Title and artist
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(2), dp(28), 0, 0);
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        mTitle = text(context, 22, Color.WHITE, true);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        words.addView(mTitle);
        mArtist = text(context, 15, 0xB3FFFFFF, false);
        mArtist.setSingleLine(true);
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        mArtist.setPadding(0, dp(2), 0, 0);
        words.addView(mArtist);
        titleRow.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mColumn.addView(titleRow);

        // The line being sung: a coloured pill with little moving bars (tap: all the lyrics)
        mLyricChip = new LinearLayout(context);
        mLyricChip.setGravity(Gravity.CENTER_VERTICAL);
        mLyricChip.setPadding(dp(2), dp(6), 0, dp(6));
        mLyricChip.setVisibility(View.GONE);
        mLyricChip.setOnClickListener(v -> showLyrics(!mLyricsOpen));
        mLyricChip.setContentDescription(context.getString(R.string.phone_music_lyrics));
        mChipBars = new Bars(context);
        mLyricChip.addView(mChipBars, new LinearLayout.LayoutParams(dp(16), dp(16)));
        mChipLine = text(context, 14, Color.WHITE, false);
        mChipLine.setSingleLine(true);
        mChipLine.setEllipsize(TextUtils.TruncateAt.END);
        mChipLine.setPadding(dp(10), 0, 0, 0);
        mLyricChip.addView(mChipLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = dp(4);
        mColumn.addView(mLyricChip, chipParams);

        // Seek bar and times
        mSeek = new SeekBar(context);
        mSeek.setMax(1000);
        mSeek.setPadding(dp(4), dp(18), dp(4), dp(6));
        mSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        mSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    SimpleExoPlayer exo = mHost.exo();
                    if (exo != null && exo.getDuration() > 0) {
                        long position = exo.getDuration() * progress / 1000;
                        mElapsed.setText(time(position));
                        mLeft.setText("-" + time(exo.getDuration() - position));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mSeeking = false;
                SimpleExoPlayer exo = mHost.exo();
                if (exo != null && exo.getDuration() > 0) {
                    exo.seekTo(exo.getDuration() * seekBar.getProgress() / 1000);
                }
            }
        });
        mColumn.addView(mSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout times = new FrameLayout(context);
        times.setPadding(dp(4), 0, dp(4), 0);
        mElapsed = text(context, 12, 0xB3FFFFFF, false);
        times.addView(mElapsed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        mLeft = text(context, 12, 0xB3FFFFFF, false);
        times.addView(mLeft, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        mColumn.addView(times);

        // Shuffle, previous, play, next, repeat
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(10), 0, dp(6));
        mShuffle = icon(context, R.drawable.ic_phone_mode_shuffle, 24);
        mShuffle.setContentDescription(context.getString(R.string.phone_music_shuffle));
        mShuffle.setOnClickListener(v -> toggleMode(PlayerConstants.PLAYBACK_MODE_SHUFFLE));
        ImageView previous = icon(context, R.drawable.ic_phone_prev, 36);
        previous.setOnClickListener(v -> change(-1));
        mPlay = new ImageView(context);
        mPlay.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(Color.WHITE);
        mPlay.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), playBg, null));
        mPlay.setElevation(dp(10));
        mPlay.setOnClickListener(v -> {
            mHost.togglePlay();
            mHandler.postDelayed(this::update, 50);
        });
        ImageView next = icon(context, R.drawable.ic_phone_next, 36);
        next.setOnClickListener(v -> change(1));
        // Pressed, previous and next light up in the song's colour
        ImageView[] skips = {previous, next};
        for (int i = 0; i < 2; i++) {
            mPressGlows[i] = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x66FFFFFF), null, null);
            mPressGlows[i].setRadius(dp(30));
            skips[i].setBackground(mPressGlows[i]);
        }
        mRepeat = icon(context, R.drawable.ic_phone_mode_repeat_one, 24);
        mRepeat.setContentDescription(context.getString(R.string.phone_music_repeat_one));
        mRepeat.setOnClickListener(v -> toggleMode(PlayerConstants.PLAYBACK_MODE_ONE));
        controls.addView(mShuffle, new LinearLayout.LayoutParams(0, dp(48), 1f));
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(56), 1f));
        FrameLayout playHolder = new FrameLayout(context);
        playHolder.setClipChildren(false);
        mPlayHalo = new PhoneMusicFx.Halo(context);
        playHolder.addView(mPlayHalo, new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER));
        playHolder.addView(mPlay, new FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(70), dp(70));
        playParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(playHolder, playParams);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1f));
        controls.addView(mRepeat, new LinearLayout.LayoutParams(0, dp(48), 1f));
        mColumn.addView(controls);

        // Up next: the next song in a glass pill, tap for the queue
        mUpNext = new LinearLayout(context);
        mUpNext.setGravity(Gravity.CENTER_VERTICAL);
        mUpNext.setPadding(dp(14), dp(10), dp(14), dp(10));
        mUpNext.setClickable(true);
        mUpNext.setForeground(context.getDrawable(R.drawable.phone_ripple));
        rounded(mUpNext, dp(18));
        ImageView queueIcon = new ImageView(context);
        queueIcon.setImageResource(R.drawable.ic_phone_playlist);
        queueIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mUpNext.addView(queueIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout upWords = new LinearLayout(context);
        upWords.setOrientation(LinearLayout.VERTICAL);
        TextView upLabel = text(context, 11, 0x99FFFFFF, true);
        upLabel.setText(R.string.phone_music_up_next);
        upLabel.setAllCaps(true);
        upLabel.setLetterSpacing(0.1f);
        upWords.addView(upLabel);
        mUpNextTitle = text(context, 14, Color.WHITE, false);
        mUpNextTitle.setSingleLine(true);
        mUpNextTitle.setEllipsize(TextUtils.TruncateAt.END);
        upWords.addView(mUpNextTitle);
        LinearLayout.LayoutParams upWordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        upWordsParams.setMarginStart(dp(12));
        mUpNext.addView(upWords, upWordsParams);
        ImageView open = new ImageView(context);
        open.setImageResource(R.drawable.ic_phone_collapse);
        open.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        open.setRotation(180f);
        open.setAlpha(0.7f);
        mUpNext.addView(open, new LinearLayout.LayoutParams(dp(22), dp(22)));
        mUpNext.setOnClickListener(v -> openQueue());
        LinearLayout.LayoutParams upNextParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upNextParams.topMargin = dp(8);
        mColumn.addView(mUpNext, upNextParams);

        mFlow = new PhoneMusicFlow(activity, mRoot, new PhoneMusicFlow.Host() {
            @Override
            public Video playing() {
                return mHost.video();
            }

            @Override
            public List<Video> before() {
                return mHost.before();
            }

            @Override
            public List<Video> upNext() {
                return mHost.upNext();
            }

            @Override
            public void play(Video video) {
                mHost.play(video);
            }

            @Override
            public SimpleExoPlayer exo() {
                return mHost.exo();
            }

            @Override
            public void togglePlay() {
                mHost.togglePlay();
                mHandler.postDelayed(PhoneMusicPlayer.this::update, 50);
            }

            @Override
            public void next() {
                mHost.next();
            }

            @Override
            public void previous() {
                mHost.previous();
            }

            @Override
            public void toggleShuffle() {
                toggleMode(PlayerConstants.PLAYBACK_MODE_SHUFFLE);
            }

            @Override
            public boolean shuffle() {
                return PlayerData.instance(mActivity).getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE;
            }

            @Override
            public void minimize() {
                mHost.minimize();
            }
        });
        // Turned sideways: Cover Flow is the player
        mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                v.post(this::applyOrientation);
            }
        });

        mQueue = new PhoneMusicQueue(activity, mRoot, new PhoneMusicQueue.Source() {
            @Override
            public Video playing() {
                return mHost.video();
            }

            @Override
            public List<Video> upNext() {
                return mHost.upNext();
            }

            @Override
            public void play(Video video) {
                mHost.play(video);
                mHandler.postDelayed(PhoneMusicPlayer.this::refreshUpNext, 600);
            }
        });

        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            mColumn.setPadding(dp(24), insets.getSystemWindowInsetTop(), dp(24), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(mRoot, match());
        applyColor(mColor);
    }

    // region Showing

    boolean isShown() {
        return mShown;
    }

    void show(boolean show) {
        if (show == mShown) {
            if (show) {
                bindVideo();
            }
            return;
        }
        mShown = show;
        if (show) {
            mRoot.animate().cancel();
            mRoot.setTranslationY(0);
            mRoot.setAlpha(0f);
            mRoot.setVisibility(View.VISIBLE);
            mRoot.animate().alpha(1f).setDuration(220).start();
            mRoot.requestApplyInsets();
            bindVideo();
            mHandler.removeCallbacks(mTick);
            mHandler.post(mTick);
            mRoot.post(this::applyOrientation);
        } else {
            mRoot.animate().cancel();
            mQueue.close();
            mFlow.hide();
            mScroll.setVisibility(View.VISIBLE);
            mRoot.setVisibility(View.GONE);
            stopPulse();
            mHandler.removeCallbacks(mTick);
        }
    }

    /** Down arrow: the whole screen slides down, then the pill takes over */
    void slideAway(Runnable done) {
        mRoot.animate().cancel();
        mRoot.animate().translationY(mRoot.getHeight()).setDuration(240)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    show(false);
                    mRoot.setTranslationY(0);
                    done.run();
                }).start();
    }

    void release() {
        show(false);
        ViewGroup parent = (ViewGroup) mRoot.getParent();
        if (parent != null) {
            parent.removeView(mRoot);
        }
    }

    /** Back pressed: the lyrics give the artwork its place back first */
    boolean onBack() {
        if (mShown && mFlow.isShown() && !mFlow.isLandscape()) {
            mFlow.hide();
            return true;
        }
        if (mShown && mQueue.isOpen()) {
            mQueue.close();
            return true;
        }
        if (mShown && mLyricsOpen) {
            showLyrics(false);
            return true;
        }
        return false;
    }

    // endregion

    // region Cover Flow

    /** Landscape: Cover Flow is the player (the portrait one hides); portrait: the usual player */
    private void applyOrientation() {
        if (!mShown || mRoot.getWidth() == 0) {
            return;
        }
        boolean wide = mRoot.getWidth() > mRoot.getHeight();
        if (wide && !mFlow.isLandscape()) {
            mQueue.close();
            mFlow.show(true);
            mFlow.setColor(mColor);
            mScroll.setVisibility(View.INVISIBLE);
        } else if (!wide && mFlow.isLandscape()) {
            mFlow.hide();
            mScroll.setVisibility(View.VISIBLE);
        }
    }

    /** A long press on the artwork: Cover Flow over the player, to pick a song */
    private void openFlow() {
        if (mFlow.isShown()) {
            return;
        }
        mArtFrame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        mFlow.show(false);
        mFlow.setColor(mColor);
    }

    // endregion

    // region Up next

    private void openQueue() {
        SimpleExoPlayer exo = mHost.exo();
        mQueue.open(mColor, exo != null && exo.getPlayWhenReady());
    }

    private void refreshUpNext() {
        List<Video> next = mHost.upNext();
        if (next.isEmpty()) {
            mUpNextTitle.setText(R.string.phone_music_up_next_none);
            mUpNextTitle.setAlpha(0.6f);
            return;
        }
        Video first = next.get(0);
        String title = PhoneLyrics.displayTitle(first.getTitle());
        String artist = PhoneLyrics.cleanArtist(first.getAuthor());
        String text = !TextUtils.isEmpty(artist) ? title + "  ·  " + artist : title;
        if (!text.contentEquals(mUpNextTitle.getText())) {
            mUpNextTitle.setText(text);
            mUpNextTitle.setAlpha(0f);
            mUpNextTitle.animate().alpha(1f).setDuration(300).start();
        }
    }

    // endregion

    // region The song

    private void bindVideo() {
        Video video = mHost.video();
        if (video == null || video.videoId == null || video.videoId.equals(mVideoId)) {
            return;
        }
        mVideoId = video.videoId;
        String source = PhoneMusic.source();
        mSource.setText(!TextUtils.isEmpty(source) ? source : mActivity.getString(R.string.phone_music_title));
        mTitle.setText(PhoneLyrics.displayTitle(video.getTitle()));
        String artist = PhoneLyrics.cleanArtist(video.getAuthor());
        mArtist.setText(artist != null ? artist : "");
        mLyrics = null;
        mLyricIndex = -2;
        mLyricsList.removeAllViews();
        mLyricChip.setVisibility(View.GONE);
        if (mLyricsOpen) {
            showLyrics(false);
        }
        mMood.setScene(PhoneMood.sceneFor(video.videoId)); // each song its own scene
        mUpNextTicks = 0; // the queue changes with the song: read again soon

        if (PhoneUi.isAlive(mActivity)) {
            String id = video.videoId;
            mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mArtFill.setVisibility(View.GONE);
            Glide.with(mActivity)
                    .load("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg")
                    // No full size picture: the 16:9 one (the 4:3 one last, it has black bars)
                    .error(Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hq720.jpg")
                            .error(Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").centerCrop()))
                    .transition(DrawableTransitionOptions.withCrossFade(250))
                    .into(mArt);
            Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hq720.jpg")
                    .error(Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"))
                    .into(mArtFill);
            Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").centerCrop().into(mBackToArt);
            if (mSkyArt.getVisibility() == View.VISIBLE) {
                Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg")
                        .transition(DrawableTransitionOptions.withCrossFade(900)).into(mSkyArt);
            }
            // The colours come from a small copy of the artwork
            Glide.with(mActivity).asBitmap().load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").override(48, 27)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                            if (id.equals(mVideoId)) {
                                animateColor(vibrant(bitmap));
                                frameArt(bitmap);
                                int[] palette = PhoneMusicFx.palette(bitmap);
                                mAura.setColors(palette);
                                mSky.setColors(palette);
                                mGlare.sweep();
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {
                        }
                    });
        }
        enterArt();

        SimpleExoPlayer exo = mHost.exo();
        long duration = exo != null && exo.getDuration() > 0 ? exo.getDuration() : video.getDurationMs();
        PhoneLyrics.load(video.videoId, video.getTitle(), video.getAuthor(), duration, (videoId, lyrics) -> {
            if (videoId.equals(mVideoId)) {
                mLyrics = lyrics;
                mLyricIndex = -2;
                fillLyrics();
                updateLyrics(true);
            }
        });
    }

    /** "Song (Official Video) | Film | Label" → "Song" */
    private static String cleanTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }
        List<String> names = PhoneLyrics.trackNames(title);
        String last = names.isEmpty() ? title : names.get(names.size() - 1); // the whole first part, tidied
        return last.isEmpty() ? title : last;
    }

    private void update() {
        SimpleExoPlayer exo = mHost.exo();
        if (exo == null) {
            return;
        }
        bindVideo();
        boolean playing = exo.getPlayWhenReady();
        mPlay.setImageResource(playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
        long duration = exo.getDuration();
        long position = exo.getCurrentPosition();
        if (!mSeeking && duration > 0) {
            mSeek.setProgress((int) (position * 1000 / duration));
            mSeek.setSecondaryProgress((int) (exo.getBufferedPosition() * 1000 / duration));
            mElapsed.setText(time(position));
            mLeft.setText("-" + time(Math.max(0, duration - position)));
        }
        boolean moving = playing && exo.getPlaybackState() == com.google.android.exoplayer2.Player.STATE_READY;
        if (moving) {
            startPulse();
        } else {
            stopPulse();
        }
        mChipBars.setPlaying(moving);
        mAura.setPlaying(moving);
        mSky.setPlaying(moving);
        mPlayHalo.setPlaying(moving);
        mQueue.setPlaying(moving);
        // Up next: read now and then (the suggestions come in after the song starts; shuffle picks)
        if (mUpNextTicks-- <= 0) {
            mUpNextTicks = 10;
            refreshUpNext();
            mFlow.refresh(false);
        }
        mFlow.update(playing, moving);
        updateModes();
        updateLyrics(false);
    }

    // endregion

    // region Swipe the artwork: next and previous song

    /** Next (1) or previous (-1) song: the artwork slides off that way, the next one comes in when the song changes */
    private void change(int direction) {
        int width = mStage.getWidth() > 0 ? mStage.getWidth() : dp(300);
        mArtFrame.animate().cancel();
        mArtFrame.animate()
                .translationX(-direction * width * 1.15f)
                .rotation(-direction * 6f)
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f))
                .start();
        mEnterFrom = direction;
        // Safety net: the song may not change (the start of a list): the artwork comes back
        mHandler.postDelayed(() -> {
            if (mEnterFrom == direction) {
                mEnterFrom = -direction;
                enterArt();
            }
        }, 2_500);
        if (direction > 0) {
            mHost.next();
        } else {
            mHost.previous();
        }
    }

    /** A new song (or none after all): its artwork slides in from the side it was swiped to */
    private void enterArt() {
        int from = mEnterFrom;
        mEnterFrom = 0;
        mArtFrame.animate().cancel();
        if (from == 0) {
            mArtFrame.setTranslationX(0);
            mArtFrame.setRotation(0);
            mArtFrame.setAlpha(1f);
            return;
        }
        int width = mStage.getWidth() > 0 ? mStage.getWidth() : dp(300);
        mArtFrame.setTranslationX(from * width * 0.9f);
        mArtFrame.setRotation(from * 5f);
        mArtFrame.setAlpha(0f);
        mArtFrame.animate().translationX(0).rotation(0).alpha(1f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupArtSwipe(ScrollView scroll) {
        float[] down = new float[2];
        boolean[] dragging = {false};
        android.view.VelocityTracker[] velocity = {null};
        int slop = android.view.ViewConfiguration.get(mActivity).getScaledTouchSlop();
        boolean[] held = {false};
        Runnable longPress = () -> {
            if (!dragging[0]) {
                held[0] = true;
                mArtFrame.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                openFlow();
            }
        };
        mArtFrame.setOnTouchListener((v, event) -> {
            int width = mStage.getWidth() > 0 ? mStage.getWidth() : dp(300);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    dragging[0] = false;
                    held[0] = false;
                    if (velocity[0] != null) {
                        velocity[0].recycle();
                    }
                    velocity[0] = android.view.VelocityTracker.obtain();
                    velocity[0].addMovement(event);
                    mArtFrame.animate().cancel();
                    // Held: it sinks in a little, then Cover Flow opens
                    mArtFrame.animate().scaleX(0.97f).scaleY(0.97f).setStartDelay(120).setDuration(300).start();
                    mHandler.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    velocity[0].addMovement(event);
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (held[0]) {
                        return true; // Cover Flow is open: this touch is done
                    }
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        mHandler.removeCallbacks(longPress);
                    }
                    if (!dragging[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging[0] = true;
                        mArtFrame.animate().cancel();
                        scroll.requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        float f = Math.min(1f, Math.abs(dx) / width);
                        mArtFrame.setTranslationX(dx);
                        mArtFrame.setRotation(dx / width * 8f);
                        mArtFrame.setAlpha(1f - f * 0.5f);
                        float scale = 1f - f * 0.06f;
                        mArtFrame.setScaleX(scale);
                        mArtFrame.setScaleY(scale);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    mHandler.removeCallbacks(longPress);
                    if (held[0]) {
                        held[0] = false;
                        return true;
                    }
                    float dx = event.getRawX() - down[0];
                    velocity[0].addMovement(event);
                    velocity[0].computeCurrentVelocity(1000);
                    float vx = velocity[0].getXVelocity();
                    boolean fling = Math.abs(vx) > dp(900) && Math.signum(vx) == Math.signum(dx);
                    mArtFrame.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(200).start();
                    if (dragging[0] && event.getActionMasked() == MotionEvent.ACTION_UP
                            && (Math.abs(dx) > width * 0.28f || fling)) {
                        change(dx < 0 ? 1 : -1); // swiped left: the next song
                    } else {
                        mArtFrame.animate().translationX(0).rotation(0).alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f)).start();
                    }
                    dragging[0] = false;
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    // endregion

    // region Shuffle and repeat (the app's playback mode)

    private void toggleMode(int target) {
        PlayerData data = PlayerData.instance(mActivity);
        int mode = data.getPlaybackMode();
        int now = mode == target ? PlayerConstants.PLAYBACK_MODE_ALL : target;
        data.setPlaybackMode(now);
        android.util.Log.d("PhoneMusic", "mode: " + (now == PlayerConstants.PLAYBACK_MODE_SHUFFLE ? "shuffle"
                : now == PlayerConstants.PLAYBACK_MODE_ONE ? "repeat one" : "in order"));
        updateModes();
        mHost.onModesChanged();
    }

    private void updateModes() {
        int mode = PlayerData.instance(mActivity).getPlaybackMode();
        boolean shuffle = mode == PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        boolean one = mode == PlayerConstants.PLAYBACK_MODE_ONE;
        showMode(mShuffle, shuffle);
        showMode(mRepeat, one);
    }

    /**
     * On: the icon bright in the song's light colour, a soft circle of it behind and a dot under it (as music apps
     * show it). Off: a dim white icon, nothing behind.
     */
    private void showMode(ImageView button, boolean on) {
        int light = lighten(mColor);
        button.setAlpha(on ? 1f : 0.5f);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(on ? light : Color.WHITE));
        button.setBackground(on ? new ModeMark(light, dp(17), dp(2)) : null);
        button.setSelected(on);
    }

    /** The "on" mark of Shuffle and Repeat: a circle of the colour behind the icon and a dot under it */
    private static final class ModeMark extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mColor;
        private final float mRadius;
        private final float mDot;

        ModeMark(int color, float radius, float dot) {
            mColor = color;
            mRadius = radius;
            mDot = dot;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float cx = getBounds().exactCenterX();
            float cy = getBounds().exactCenterY();
            // A soft glow of the song's colour, a circle in it, the dot under
            mPaint.setShader(new android.graphics.RadialGradient(cx, cy, mRadius * 1.4f,
                    new int[] {(mColor & 0x00FFFFFF) | 0x47000000, mColor & 0x00FFFFFF}, null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, mRadius * 1.4f, mPaint);
            mPaint.setShader(null);
            mPaint.setColor((mColor & 0x00FFFFFF) | 0x2E000000);
            canvas.drawCircle(cx, cy, mRadius, mPaint);
            mPaint.setColor(mColor);
            canvas.drawCircle(cx, cy + mRadius + mDot * 1.5f, mDot, mPaint); // (inside the 48 dp button)
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // endregion

    // region Lyrics

    private void updateLyrics(boolean force) {
        if (mLyrics == null || mLyrics.isEmpty()) {
            mLyricChip.setVisibility(View.GONE);
            return;
        }
        SimpleExoPlayer exo = mHost.exo();
        long position = exo != null ? exo.getCurrentPosition() : 0;
        if (mDrawnTimed != timed()) {
            fillLyrics(); // the playing length is known now: the lines light up, or don't
            android.util.Log.d("PhoneLyrics", "playing " + playingMs() / 1000 + " s, lyrics timed to "
                    + mLyrics.lengthMs / 1000 + " s: " + (fits() ? "in time" : introMs() > 0 ? "after an intro of "
                    + introMs() + " ms" : "not in time"));
            force = true;
        }
        int index = timed() ? mLyrics.indexAt(position - offset()) : 0;
        if (index == mLyricIndex && !force) {
            return;
        }
        mLyricIndex = index;
        // The pill: the line being sung (before the first line, the first one to come; unsynced, the first line)
        String line = mLyrics.lines.get(Math.max(0, Math.min(index, mLyrics.lines.size() - 1))).text;
        if (index < 0 && timed()) {
            line = "♪";
        }
        boolean appearing = mLyricChip.getVisibility() != View.VISIBLE;
        mLyricChip.setVisibility(mLyricsOpen ? View.INVISIBLE : View.VISIBLE);
        if (!TextUtils.equals(mChipLine.getText(), line)) {
            String text = line;
            if (appearing) {
                mChipLine.setText(text);
            } else {
                // The new line rises in
                mChipLine.animate().cancel();
                mChipLine.animate().alpha(0f).translationY(-dp(6)).setDuration(120).withEndAction(() -> {
                    mChipLine.setText(text);
                    mChipLine.setTranslationY(dp(6));
                    mChipLine.animate().alpha(1f).translationY(0).setDuration(180).start();
                }).start();
            }
        }
        highlightLyrics(index);
    }

    private void fillLyrics() {
        mLyricsList.removeAllViews();
        mDrawnTimed = timed();
        if (mLyrics == null) {
            return;
        }
        for (int i = 0; i < mLyrics.lines.size(); i++) {
            PhoneLyrics.Line line = mLyrics.lines.get(i);
            TextView view = text(mActivity, 23, Color.WHITE, true);
            view.setText(line.text);
            view.setPadding(0, dp(7), 0, dp(7));
            view.setAlpha(timed() ? 0.4f : 0.95f);
            view.setLineSpacing(dp(2), 1f);
            if (mLyrics.synced) {
                view.setOnClickListener(v -> {
                    SimpleExoPlayer exo = mHost.exo();
                    if (exo != null) {
                        exo.seekTo(Math.max(0, line.timeMs + offset())); // tap a line: the song goes there
                    }
                });
            }
            mLyricsList.addView(view);
        }
    }

    private void highlightLyrics(int index) {
        if (mLyrics == null || !timed() || mLyricsList.getChildCount() == 0) {
            return;
        }
        for (int i = 0; i < mLyricsList.getChildCount(); i++) {
            View view = mLyricsList.getChildAt(i);
            view.animate().alpha(i == index ? 1f : i < index ? 0.3f : 0.45f)
                    .scaleX(i == index ? 1.04f : 1f).scaleY(i == index ? 1.04f : 1f).setDuration(220).start();
            view.setPivotX(0);
        }
        if (mLyricsOpen && index >= 0 && index < mLyricsList.getChildCount()) {
            View line = mLyricsList.getChildAt(index);
            int target = line.getTop() - mLyricsScroll.getHeight() / 3;
            mLyricsScroll.smoothScrollTo(0, Math.max(0, target));
        }
    }

    /**
     * The lines light up as they're sung: timed lyrics whose timing is what's playing (see PhoneLyrics.onThisVideo),
     * or moved past a music video's intro
     */
    private boolean timed() {
        return mLyrics != null && mLyrics.synced && (fits() || introMs() > 0);
    }

    /** How far the lines are moved: past a music video's intro (see introMs) */
    private long offset() {
        return introMs();
    }

    /** The playing length, or 0 while not known */
    private long playingMs() {
        SimpleExoPlayer exo = mHost.exo();
        long duration = exo != null ? exo.getDuration() : 0;
        return duration > 0 ? duration : 0;
    }

    /**
     * The lyrics are timed to what's playing: as long as it (within 3 s), checked against what really plays (the
     * recording or the music video), or, while its length isn't known, as PhoneLyrics judged.
     */
    private boolean fits() {
        long playing = playingMs();
        if (mLyrics == null || mLyrics.lengthMs <= 0 || playing <= 0) {
            return mLyrics != null && mLyrics.trusted;
        }
        return Math.abs(playing - mLyrics.lengthMs) <= 3_000;
    }

    /**
     * A music video longer than the recording by up to 45 s: an intro (a film scene, a title card) before the song,
     * the way music videos mostly are; the lines start that much later. 0 when it fits or isn't that.
     */
    private long introMs() {
        long playing = playingMs();
        if (mLyrics == null || !mLyrics.synced || mLyrics.lengthMs <= 0 || playing <= 0 || fits()) {
            return 0;
        }
        long extra = playing - mLyrics.lengthMs;
        return extra > 3_000 && extra <= 45_000 ? extra : 0;
    }

    /** The lyrics take the artwork's place (or give it back) */
    private void showLyrics(boolean show) {
        if (show && (mLyrics == null || mLyrics.isEmpty())) {
            return;
        }
        mLyricsOpen = show;
        View in = show ? mLyricsPanel : mArtFrame;
        View out = show ? mArtFrame : mLyricsPanel;
        out.animate().cancel();
        out.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(180).withEndAction(() -> {
            out.setVisibility(show ? View.INVISIBLE : View.GONE);
            out.setScaleX(1f);
            out.setScaleY(1f);
        }).start();
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setScaleX(0.94f);
        in.setScaleY(0.94f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        mLyricChip.setVisibility(show ? View.INVISIBLE : (mLyrics != null && !mLyrics.isEmpty() ? View.VISIBLE : View.GONE));
        if (show) {
            mLyricsPanel.post(() -> highlightLyrics(mLyricIndex));
        }
    }

    // endregion

    // region Colour and glow

    private void animateColor(int to) {
        int from = mColor;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(600);
        animator.addUpdateListener(a -> applyColor(blend(from, to, (float) a.getAnimatedValue())));
        animator.start();
    }

    private void applyColor(int color) {
        mColor = color;
        if (mUpNext != null) {
            GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {(lighten(color) & 0x00FFFFFF) | 0x2E000000, 0x14FFFFFF});
            glass.setCornerRadius(dp(18));
            glass.setStroke(Math.max(1, dp(1) / 2), (lighten(color) & 0x00FFFFFF) | 0x40000000);
            mUpNext.setBackground(glass);
        }
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {darken(color, 0.55f), darken(color, 0.28f), 0xFF0A0A0A});
        mBackground.setBackground(background);
        int light = lighten(color);
        mPlayHalo.setColor(light);
        if (mFlow != null) {
            mFlow.setColor(color);
        }
        for (android.graphics.drawable.RippleDrawable glow : mPressGlows) {
            glow.setColor(android.content.res.ColorStateList.valueOf((light & 0x00FFFFFF) | 0x73000000));
        }
        mPlay.setImageTintList(android.content.res.ColorStateList.valueOf(darken(color, 0.62f)));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            mPlay.setOutlineSpotShadowColor(light);
            mPlay.setOutlineAmbientShadowColor(light);
        }
        mTitle.setShadowLayer(dp(14), 0, 0, (light & 0x00FFFFFF) | 0x59000000);
        mMood.setColor(color);
        // The lyrics panel: a deep shade of the song's colour
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {darken(color, 0.35f), darken(color, 0.62f)});
        mLyricsPanel.setBackground(panel);
        if (mLyricsHeader != null) {
            int top = darken(color, 0.35f);
            mLyricsHeader.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {top, (top & 0x00FFFFFF) | 0xCC000000, top & 0x00FFFFFF}));
        }
        // The lyric line: no box, its words in a light shade of the song's colour
        mLyricChip.setBackground(null);
        int words = blend(color, Color.WHITE, 0.72f);
        // The artwork's shadow in the song's colour, not grey
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            mArtFrame.setOutlineSpotShadowColor(lighten(color));
            mArtFrame.setOutlineAmbientShadowColor(color);
            mLyricsPanel.setOutlineSpotShadowColor(lighten(color));
        }
        mChipLine.setTextColor(words);
        mChipBars.setColor(words);
        updateModes();
    }

    /** While the song plays the blurred artwork behind turns slowly (a turn in two minutes); paused, it rests */
    private void startPulse() {
        mMood.setRunning(true);
        if (mPulsing) {
            return;
        }
        mPulsing = true;
        if (mSkyArt.getVisibility() == View.VISIBLE) {
            if (mSkyTurn == null) {
                mSkyTurn = android.animation.ObjectAnimator.ofFloat(mSkyArt, View.ROTATION, 0f, 360f);
                mSkyTurn.setDuration(120_000);
                mSkyTurn.setRepeatCount(ValueAnimator.INFINITE);
                mSkyTurn.setInterpolator(new LinearInterpolator());
                mSkyTurn.start();
            } else {
                mSkyTurn.resume();
            }
        }
        mStage.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
    }

    private void stopPulse() {
        mMood.setRunning(false);
        if (!mPulsing) {
            return;
        }
        mPulsing = false;
        if (mSkyTurn != null) {
            mSkyTurn.pause();
        }
        mStage.animate().scaleX(0.94f).scaleY(0.94f).setDuration(300).start(); // paused: the artwork steps back
    }

    /**
     * How the artwork sits in its square: a song's cover (a square picture padded out to 16:9 with plain sides) fills
     * it; a music video's thumbnail is shown whole, over a blurred copy of itself filling the rest
     */
    private void frameArt(Bitmap small) {
        boolean cover = plainSides(small);
        mArt.setScaleType(cover ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        mArtFill.setVisibility(cover ? View.GONE : View.VISIBLE);
    }

    /** The outer eighths of the picture are (nearly) one colour each: padding around a square cover */
    private static boolean plainSides(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 8 || h < 4 || w < h * 1.3f) {
            return true; // already square-ish: fill
        }
        int strip = Math.max(1, w / 8);
        return spread(bitmap, 0, strip, h) < 18 && spread(bitmap, w - strip, w, h) < 18;
    }

    /** How much the colours vary in columns [from, to): the standard deviation of their brightness */
    private static double spread(Bitmap bitmap, int from, int to, int h) {
        double sum = 0;
        double squares = 0;
        int n = 0;
        for (int x = from; x < to; x++) {
            for (int y = 0; y < h; y++) {
                int c = bitmap.getPixel(x, y);
                double v = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
                sum += v;
                squares += v * v;
                n++;
            }
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, squares / n - mean * mean));
    }

    /** The most colourful bright-enough tone of the artwork */
    static int vibrant(Bitmap bitmap) {
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, weight = 0;
        double ar = 0, ag = 0, ab = 0;
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                ar += Color.red(pixel);
                ag += Color.green(pixel);
                ab += Color.blue(pixel);
                count++;
                if (hsv[2] < 0.25f || hsv[2] > 0.97f) {
                    continue;
                }
                double w = Math.pow(hsv[1], 2) * hsv[2];
                r += Color.red(pixel) * w;
                g += Color.green(pixel) * w;
                b += Color.blue(pixel) * w;
                weight += w;
            }
        }
        int color = weight > 0.5 ? Color.rgb((int) (r / weight), (int) (g / weight), (int) (b / weight))
                : Color.rgb((int) (ar / Math.max(1, count)), (int) (ag / Math.max(1, count)), (int) (ab / Math.max(1, count)));
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.05f); // a little richer
        hsv[2] = Math.max(0.55f, Math.min(0.9f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    static int darken(int color, float amount) {
        return blend(color, Color.BLACK, amount);
    }

    static int lighten(int color) {
        return blend(color, Color.WHITE, 0.35f);
    }

    static int blend(int from, int to, float t) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    // endregion

    /** Three little bars moving like a level meter while the song plays: "this is what's being sung" */
    static final class Bars extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private ValueAnimator mAnimator;
        private float mPhase;

        Bars(Context context) {
            super(context);
            mPaint.setColor(Color.WHITE);
        }

        void setColor(int color) {
            mPaint.setColor(color);
            invalidate();
        }

        void setPlaying(boolean playing) {
            if (playing && mAnimator == null) {
                mAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
                mAnimator.setDuration(900);
                mAnimator.setRepeatCount(ValueAnimator.INFINITE);
                mAnimator.setInterpolator(new LinearInterpolator());
                mAnimator.addUpdateListener(a -> {
                    mPhase = (float) a.getAnimatedValue();
                    invalidate();
                });
                mAnimator.start();
            } else if (!playing && mAnimator != null) {
                mAnimator.cancel();
                mAnimator = null;
                mPhase = 0;
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            setPlaying(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float bar = w / 5f;
            float[] offsets = {0f, 2.1f, 4.2f};
            for (int i = 0; i < 3; i++) {
                float level = mAnimator == null ? (i == 1 ? 0.6f : 0.35f)
                        : 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(mPhase * (i == 1 ? 2 : 1) + offsets[i]));
                float left = i * bar * 2f;
                mRect.set(left, h * (1f - level), left + bar, h);
                canvas.drawRoundRect(mRect, bar / 2f, bar / 2f, mPaint);
            }
        }
    }

    // region Helpers

    private static void rounded(View view, int radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private static String time(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(java.util.Locale.US, "%d:%02d", minutes, seconds % 60);
    }

    private TextView text(Context context, int sp, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(medium ? PhoneUi.medium(context) : PhoneUi.regular(context));
        return view;
    }

    private ImageView icon(Context context, int drawable, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.max(0, (dp(44) - dp(sizeDp)) / 2);
        view.setPadding(pad, pad, pad, pad);
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        return view;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    // endregion
}
