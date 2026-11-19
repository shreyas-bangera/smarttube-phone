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

        void like();

        boolean liked();

        /** Down arrow: the player goes small (the music pill) */
        void minimize();

        /** "Video": back to the video player for this song */
        void showVideo();

        /** Shuffle or repeat was turned on or off */
        void onModesChanged();
    }

    private static final long TICK_MS = 200;

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout mRoot;
    private final View mBackground;
    private final View mGlow;
    private final View mGlow2;
    /** The song's mood, drawn faintly behind everything (see PhoneMood) */
    private final PhoneMood.Backdrop mMood;
    private final LinearLayout mColumn;
    private final FrameLayout mStage;
    private final FrameLayout mArtFrame;
    private final ImageView mArt;
    private final FrameLayout mLyricsPanel;
    private final ScrollView mLyricsScroll;
    private final LinearLayout mLyricsList;
    private final ImageView mBackToArt;
    private final TextView mSource;
    private final TextView mTitle;
    private final TextView mArtist;
    private final ImageView mLike;
    private final LinearLayout mLyricChip;
    private final Bars mChipBars;
    private final TextView mChipLine;
    private final SeekBar mSeek;
    private final TextView mElapsed;
    private final TextView mLeft;
    private final ImageView mPlay;
    private final ImageView mShuffle;
    private final ImageView mRepeat;

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
    private ValueAnimator mPulse;
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

        // Two soft blobs of the artwork's colour, breathing while the song plays
        mGlow = new View(context);
        mGlow2 = new View(context);
        int glowSize = Math.round(context.getResources().getDisplayMetrics().widthPixels * 1.35f);
        FrameLayout.LayoutParams glowParams = new FrameLayout.LayoutParams(glowSize, glowSize, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        glowParams.topMargin = -glowSize / 6;
        mRoot.addView(mGlow, glowParams);
        FrameLayout.LayoutParams glow2Params = new FrameLayout.LayoutParams(glowSize, glowSize, Gravity.END | Gravity.BOTTOM);
        glow2Params.rightMargin = -glowSize / 3;
        glow2Params.bottomMargin = -glowSize / 4;
        mRoot.addView(mGlow2, glow2Params);
        mMood = new PhoneMood.Backdrop(context);
        mRoot.addView(mMood, match());

        ScrollView scroll = new ScrollView(context);
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

        mArtFrame = new FrameLayout(context);
        mArtFrame.setElevation(dp(18));
        rounded(mArtFrame, dp(18));
        mArt = new ImageView(context);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(0x22FFFFFF);
        mArtFrame.addView(mArt, match());
        mStage.addView(mArtFrame, match());
        mArtFrame.setContentDescription(context.getString(R.string.phone_music_artwork));
        setupArtSwipe(scroll);

        mLyricsPanel = new FrameLayout(context);
        mLyricsPanel.setVisibility(View.GONE);
        mLyricsPanel.setElevation(dp(18));
        rounded(mLyricsPanel, dp(18));
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

        // Title, artist, like
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(26), 0, 0);
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        mTitle = text(context, 24, Color.WHITE, true);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        words.addView(mTitle);
        mArtist = text(context, 16, 0xB3FFFFFF, false);
        mArtist.setSingleLine(true);
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        mArtist.setPadding(0, dp(4), 0, 0);
        words.addView(mArtist);
        titleRow.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mLike = icon(context, R.drawable.ic_phone_like, 26);
        mLike.setOnClickListener(v -> {
            mHost.like();
            mHandler.postDelayed(this::updateLike, 300);
        });
        titleRow.addView(mLike, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mColumn.addView(titleRow);

        // The line being sung: a coloured pill with little moving bars (tap: all the lyrics)
        mLyricChip = new LinearLayout(context);
        mLyricChip.setGravity(Gravity.CENTER_VERTICAL);
        mLyricChip.setPadding(dp(12), dp(9), dp(14), dp(9));
        mLyricChip.setVisibility(View.GONE);
        mLyricChip.setOnClickListener(v -> showLyrics(!mLyricsOpen));
        mLyricChip.setContentDescription(context.getString(R.string.phone_music_lyrics));
        mChipBars = new Bars(context);
        mLyricChip.addView(mChipBars, new LinearLayout.LayoutParams(dp(16), dp(16)));
        mChipLine = text(context, 15, Color.WHITE, true);
        mChipLine.setSingleLine(true);
        mChipLine.setEllipsize(TextUtils.TruncateAt.END);
        mChipLine.setPadding(dp(10), 0, 0, 0);
        mLyricChip.addView(mChipLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = dp(12);
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
        controls.setPadding(0, dp(14), 0, dp(10));
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
        mPlay.setBackground(playBg);
        mPlay.setElevation(dp(6));
        mPlay.setOnClickListener(v -> {
            mHost.togglePlay();
            mHandler.postDelayed(this::update, 50);
        });
        ImageView next = icon(context, R.drawable.ic_phone_next, 36);
        next.setOnClickListener(v -> change(1));
        mRepeat = icon(context, R.drawable.ic_phone_mode_repeat_one, 24);
        mRepeat.setContentDescription(context.getString(R.string.phone_music_repeat_one));
        mRepeat.setOnClickListener(v -> toggleMode(PlayerConstants.PLAYBACK_MODE_ONE));
        controls.addView(mShuffle, new LinearLayout.LayoutParams(0, dp(48), 1f));
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(56), 1f));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        playParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(mPlay, playParams);
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1f));
        controls.addView(mRepeat, new LinearLayout.LayoutParams(0, dp(48), 1f));
        mColumn.addView(controls);

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
        } else {
            mRoot.animate().cancel();
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
        if (mShown && mLyricsOpen) {
            showLyrics(false);
            return true;
        }
        return false;
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
        mMood.setMood(PhoneMood.of(video.getTitle(), null)); // the title first, the lyrics when they come

        if (PhoneUi.isAlive(mActivity)) {
            String id = video.videoId;
            Glide.with(mActivity)
                    .load("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg")
                    .centerCrop()
                    // No full size picture: the 16:9 one (the 4:3 one last, it has black bars)
                    .error(Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hq720.jpg").centerCrop()
                            .error(Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").centerCrop()))
                    .transition(DrawableTransitionOptions.withCrossFade(250))
                    .into(mArt);
            Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").centerCrop().into(mBackToArt);
            // The colours come from a small copy of the artwork
            Glide.with(mActivity).asBitmap().load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").override(48, 27)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                            if (id.equals(mVideoId)) {
                                animateColor(vibrant(bitmap));
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
                PhoneMood.Mood mood = PhoneMood.of(video.getTitle(), lyrics.lines);
                android.util.Log.d("PhoneLyrics", "mood of " + videoId + ": " + mood);
                mMood.setMood(mood);
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
        mPlay.setImageTintList(android.content.res.ColorStateList.valueOf(Color.BLACK));
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
        updateModes();
        updateLike();
        updateLyrics(false);
    }

    private void updateLike() {
        boolean liked = mHost.liked();
        mLike.setImageTintList(android.content.res.ColorStateList.valueOf(liked ? 0xFFFF4E6A : Color.WHITE));
        mLike.setAlpha(liked ? 1f : 0.85f);
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
        mArtFrame.setOnTouchListener((v, event) -> {
            int width = mStage.getWidth() > 0 ? mStage.getWidth() : dp(300);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    dragging[0] = false;
                    if (velocity[0] != null) {
                        velocity[0].recycle();
                    }
                    velocity[0] = android.view.VelocityTracker.obtain();
                    velocity[0].addMovement(event);
                    mArtFrame.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    velocity[0].addMovement(event);
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging[0] = true;
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
                    float dx = event.getRawX() - down[0];
                    velocity[0].addMovement(event);
                    velocity[0].computeCurrentVelocity(1000);
                    float vx = velocity[0].getXVelocity();
                    boolean fling = Math.abs(vx) > dp(900) && Math.signum(vx) == Math.signum(dx);
                    mArtFrame.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
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
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {darken(color, 0.55f), darken(color, 0.28f), 0xFF0A0A0A});
        mBackground.setBackground(background);
        mGlow.setBackground(glow(lighten(color), 0x99));
        mGlow2.setBackground(glow(color, 0x66));
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
        // The lyric pill: tinted with it, its words in a light shade of it
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(18));
        chip.setColor((lighten(color) & 0x00FFFFFF) | 0x33000000);
        chip.setStroke(dp(1), (lighten(color) & 0x00FFFFFF) | 0x55000000);
        mLyricChip.setBackground(chip);
        int words = blend(color, Color.WHITE, 0.7f);
        mChipLine.setTextColor(words);
        mChipBars.setColor(words);
        updateModes();
    }

    private static GradientDrawable glow(int color, int alpha) {
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(1f);
        glow.setColors(new int[] {(color & 0x00FFFFFF) | (alpha << 24), (color & 0x00FFFFFF) | ((alpha / 3) << 24), color & 0x00FFFFFF});
        return glow;
    }

    /** While the song plays the glow breathes and drifts a little; paused, it rests */
    private void startPulse() {
        mMood.setRunning(true);
        if (mPulse != null) {
            return;
        }
        mPulse = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        mPulse.setDuration(4_000);
        mPulse.setRepeatCount(ValueAnimator.INFINITE);
        mPulse.setInterpolator(new LinearInterpolator());
        mPulse.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float breath = (float) Math.sin(t);
            float beat = (float) Math.pow(Math.max(0, Math.sin(t * 4)), 6); // a soft beat, four per cycle
            mGlow.setScaleX(1f + 0.08f * breath + 0.03f * beat);
            mGlow.setScaleY(1f + 0.08f * breath + 0.03f * beat);
            mGlow.setAlpha(0.75f + 0.2f * breath + 0.05f * beat);
            mGlow2.setTranslationX(dp(30) * (float) Math.cos(t));
            mGlow2.setTranslationY(dp(20) * (float) Math.sin(t));
            mGlow2.setAlpha(0.6f + 0.25f * (float) Math.cos(t));
        });
        mPulse.start();
        mStage.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
    }

    private void stopPulse() {
        mMood.setRunning(false);
        if (mPulse == null) {
            return;
        }
        mPulse.cancel();
        mPulse = null;
        mGlow.animate().scaleX(1f).scaleY(1f).alpha(0.6f).setDuration(400).start();
        mGlow2.animate().translationX(0).translationY(0).alpha(0.5f).setDuration(400).start();
        mStage.animate().scaleX(0.94f).scaleY(0.94f).setDuration(300).start(); // paused: the artwork steps back
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
