package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The music player (songs opened from the Music page), in the style of YouTube Music and Spotify: a background glowing
 * in the colours of the song's artwork, gently pulsing while it plays; the big artwork; title, artist and like; a seek
 * bar; shuffle, previous, play, next and repeat; and the lyrics, the line being sung lit up (tap the card for all of
 * them, tap a line to go there).
 *
 * It's a layer over the video player screen: the same player plays underneath, so everything else (the queue, the
 * mini player, background playback) works as for videos. "Video" (top right) shows the video instead.
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

        /** Down arrow: the player goes small, as from the video */
        void minimize();

        /** "Video": back to the video player for this song */
        void showVideo();
    }

    private static final long TICK_MS = 200;

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout mRoot;
    private final View mBackground;
    private final View mGlow;
    private final View mGlow2;
    private final ImageView mArt;
    private final FrameLayout mArtFrame;
    private final TextView mSource;
    private final TextView mTitle;
    private final TextView mArtist;
    private final ImageView mLike;
    private final SeekBar mSeek;
    private final TextView mElapsed;
    private final TextView mLeft;
    private final ImageView mPlay;
    private final ImageView mShuffle;
    private final ImageView mRepeat;
    private final LinearLayout mLyricsCard;
    private final TextView[] mLyricLines = new TextView[5];
    private final TextView mLyricsNote;
    private final FrameLayout mLyricsFull;
    private final ScrollView mLyricsScroll;
    private final LinearLayout mLyricsList;

    private boolean mShown;
    private boolean mSeeking;
    private String mVideoId;
    private PhoneLyrics.Lyrics mLyrics;
    private int mLyricIndex = -2;
    private int mColor = 0xFF3A3A3A;
    private ValueAnimator mPulse;

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

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mRoot.addView(scroll, match());
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), 0, dp(24), dp(24));
        scroll.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top: down arrow, "Playing from …", Video
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(8), 0, dp(8));
        ImageView down = icon(context, R.drawable.ic_phone_collapse, 26);
        down.setOnClickListener(v -> mHost.minimize());
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
        column.addView(top);

        // The artwork
        mArtFrame = new FrameLayout(context);
        mArtFrame.setElevation(dp(18));
        mArtFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        mArtFrame.setClipToOutline(true);
        mArt = new ImageView(context);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(0x22FFFFFF);
        mArtFrame.addView(mArt, match());
        int artSize = context.getResources().getDisplayMetrics().widthPixels - dp(48);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(artSize, artSize);
        artParams.topMargin = dp(20);
        artParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(mArtFrame, artParams);

        // Title, artist, like
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(28), 0, 0);
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
        column.addView(titleRow);

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
        column.addView(mSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout times = new FrameLayout(context);
        times.setPadding(dp(4), 0, dp(4), 0);
        mElapsed = text(context, 12, 0xB3FFFFFF, false);
        times.addView(mElapsed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        mLeft = text(context, 12, 0xB3FFFFFF, false);
        times.addView(mLeft, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        column.addView(times);

        // Shuffle, previous, play, next, repeat
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(14), 0, dp(10));
        mShuffle = icon(context, R.drawable.ic_phone_mode_shuffle, 24);
        mShuffle.setOnClickListener(v -> toggleMode(PlayerConstantsModes.SHUFFLE));
        ImageView previous = icon(context, R.drawable.ic_phone_prev, 36);
        previous.setOnClickListener(v -> mHost.previous());
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
        next.setOnClickListener(v -> mHost.next());
        mRepeat = icon(context, R.drawable.ic_phone_mode_repeat_one, 24);
        mRepeat.setOnClickListener(v -> toggleMode(PlayerConstantsModes.ONE));
        controls.addView(mShuffle, new LinearLayout.LayoutParams(0, dp(48), 1f));
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(56), 1f));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        playParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(mPlay, playParams);
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1f));
        controls.addView(mRepeat, new LinearLayout.LayoutParams(0, dp(48), 1f));
        column.addView(controls);

        // Lyrics card: the line being sung, two before and two after
        mLyricsCard = new LinearLayout(context);
        mLyricsCard.setOrientation(LinearLayout.VERTICAL);
        mLyricsCard.setPadding(dp(20), dp(18), dp(20), dp(20));
        mLyricsCard.setOnClickListener(v -> showFullLyrics(true));
        TextView lyricsTitle = text(context, 16, Color.WHITE, true);
        lyricsTitle.setText(R.string.phone_music_lyrics);
        lyricsTitle.setPadding(0, 0, 0, dp(10));
        mLyricsCard.addView(lyricsTitle);
        for (int i = 0; i < mLyricLines.length; i++) {
            TextView line = text(context, 19, Color.WHITE, true);
            line.setPadding(0, dp(3), 0, dp(3));
            mLyricLines[i] = line;
            mLyricsCard.addView(line);
        }
        mLyricsNote = text(context, 15, 0xB3FFFFFF, false);
        mLyricsCard.addView(mLyricsNote);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(18);
        column.addView(mLyricsCard, cardParams);

        // All the lyrics, full screen
        mLyricsFull = new FrameLayout(context);
        mLyricsFull.setVisibility(View.GONE);
        mLyricsFull.setClickable(true);
        mLyricsScroll = new ScrollView(context);
        mLyricsScroll.setVerticalScrollBarEnabled(false);
        mLyricsList = new LinearLayout(context);
        mLyricsList.setOrientation(LinearLayout.VERTICAL);
        mLyricsList.setPadding(dp(28), dp(80), dp(28), dp(200));
        mLyricsScroll.addView(mLyricsList);
        mLyricsFull.addView(mLyricsScroll, match());
        ImageView close = icon(context, R.drawable.ic_phone_collapse, 26);
        close.setOnClickListener(v -> showFullLyrics(false));
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
        closeParams.setMargins(dp(20), dp(12), 0, 0);
        mLyricsFull.addView(close, closeParams);
        mRoot.addView(mLyricsFull, match());

        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            column.setPadding(dp(24), insets.getSystemWindowInsetTop(), dp(24), dp(24) + insets.getSystemWindowInsetBottom());
            mLyricsFull.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
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
            showFullLyrics(false);
            stopPulse();
            mHandler.removeCallbacks(mTick);
        }
    }

    void release() {
        show(false);
        ViewGroup parent = (ViewGroup) mRoot.getParent();
        if (parent != null) {
            parent.removeView(mRoot);
        }
    }

    /** Back pressed: closes the full lyrics first */
    boolean onBack() {
        if (mShown && mLyricsFull.getVisibility() == View.VISIBLE) {
            showFullLyrics(false);
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
        mTitle.setText(cleanTitle(video.getTitle()));
        String artist = PhoneLyrics.cleanArtist(video.getAuthor());
        mArtist.setText(artist != null ? artist : "");
        mLyrics = null;
        mLyricIndex = -2;
        showLyricsState(mActivity.getString(R.string.phone_music_lyrics_loading));

        if (PhoneUi.isAlive(mActivity)) {
            String id = video.videoId;
            Glide.with(mActivity)
                    .load("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg")
                    .centerCrop()
                    .error(Glide.with(mActivity).load("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").centerCrop())
                    .transition(DrawableTransitionOptions.withCrossFade(250))
                    .into(mArt);
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

        SimpleExoPlayer exo = mHost.exo();
        long duration = exo != null && exo.getDuration() > 0 ? exo.getDuration() : video.getDurationMs();
        PhoneLyrics.load(video.videoId, video.getTitle(), video.getAuthor(), duration, (videoId, lyrics) -> {
            if (videoId.equals(mVideoId)) {
                mLyrics = lyrics;
                mLyricIndex = -2;
                fillFullLyrics();
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
        if (playing && exo.getPlaybackState() == com.google.android.exoplayer2.Player.STATE_READY) {
            startPulse();
        } else {
            stopPulse();
        }
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

    // region Shuffle and repeat (the app's playback mode)

    private static final class PlayerConstantsModes {
        static final int SHUFFLE = 1;
        static final int ONE = 2;
    }

    private void toggleMode(int which) {
        PlayerData data = PlayerData.instance(mActivity);
        int mode = data.getPlaybackMode();
        int shuffle = com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        int one = com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ONE;
        int all = com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ALL;
        int target = which == PlayerConstantsModes.SHUFFLE ? shuffle : one;
        data.setPlaybackMode(mode == target ? all : target);
        updateModes();
    }

    private void updateModes() {
        int mode = PlayerData.instance(mActivity).getPlaybackMode();
        boolean shuffle = mode == com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        boolean one = mode == com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ONE;
        mShuffle.setAlpha(shuffle ? 1f : 0.55f);
        mShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(shuffle ? lighten(mColor) : Color.WHITE));
        mRepeat.setAlpha(one ? 1f : 0.55f);
        mRepeat.setImageTintList(android.content.res.ColorStateList.valueOf(one ? lighten(mColor) : Color.WHITE));
    }

    // endregion

    // region Lyrics

    private void showLyricsState(String note) {
        for (TextView line : mLyricLines) {
            line.setVisibility(View.GONE);
        }
        mLyricsNote.setText(note);
        mLyricsNote.setVisibility(View.VISIBLE);
    }

    private void updateLyrics(boolean force) {
        if (mLyrics == null) {
            return;
        }
        if (mLyrics.isEmpty()) {
            showLyricsState(mActivity.getString(R.string.phone_music_lyrics_none));
            return;
        }
        SimpleExoPlayer exo = mHost.exo();
        long position = exo != null ? exo.getCurrentPosition() : 0;
        int index = mLyrics.synced ? mLyrics.indexAt(position) : 0;
        if (index == mLyricIndex && !force) {
            return;
        }
        mLyricIndex = index;
        mLyricsNote.setVisibility(View.GONE);
        int current = Math.max(index, 0);
        for (int i = 0; i < mLyricLines.length; i++) {
            int lineIndex = mLyrics.synced ? current - 2 + i : i;
            TextView view = mLyricLines[i];
            if (lineIndex < 0 || lineIndex >= mLyrics.lines.size()) {
                view.setVisibility(lineIndex < 0 ? View.INVISIBLE : View.GONE);
                view.setText(" ");
                continue;
            }
            view.setVisibility(View.VISIBLE);
            view.setText(mLyrics.lines.get(lineIndex).text);
            boolean now = mLyrics.synced && lineIndex == index;
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, now ? 22 : 18);
            view.setAlpha(!mLyrics.synced ? 0.9f : now ? 1f : 0.45f);
        }
        highlightFull(index);
    }

    private void fillFullLyrics() {
        mLyricsList.removeAllViews();
        if (mLyrics == null) {
            return;
        }
        for (int i = 0; i < mLyrics.lines.size(); i++) {
            PhoneLyrics.Line line = mLyrics.lines.get(i);
            TextView view = text(mActivity, 26, Color.WHITE, true);
            view.setText(line.text);
            view.setPadding(0, dp(8), 0, dp(8));
            view.setAlpha(mLyrics.synced ? 0.4f : 0.95f);
            if (mLyrics.synced) {
                view.setOnClickListener(v -> {
                    SimpleExoPlayer exo = mHost.exo();
                    if (exo != null) {
                        exo.seekTo(line.timeMs); // tap a line: the song goes there
                    }
                });
            }
            mLyricsList.addView(view);
        }
    }

    private void highlightFull(int index) {
        if (mLyrics == null || !mLyrics.synced || mLyricsList.getChildCount() == 0) {
            return;
        }
        for (int i = 0; i < mLyricsList.getChildCount(); i++) {
            mLyricsList.getChildAt(i).animate().alpha(i == index ? 1f : i < index ? 0.3f : 0.45f).setDuration(200).start();
        }
        if (mLyricsFull.getVisibility() == View.VISIBLE && index >= 0 && index < mLyricsList.getChildCount()) {
            View line = mLyricsList.getChildAt(index);
            int target = line.getTop() - mLyricsScroll.getHeight() / 3;
            mLyricsScroll.smoothScrollTo(0, Math.max(0, target));
        }
    }

    private void showFullLyrics(boolean show) {
        if (show && (mLyrics == null || mLyrics.isEmpty())) {
            return;
        }
        if (show) {
            mLyricsFull.setBackgroundColor(darken(mColor, 0.45f) | 0xFF000000);
            mLyricsFull.setAlpha(0f);
            mLyricsFull.setVisibility(View.VISIBLE);
            mLyricsFull.animate().alpha(1f).setDuration(200).start();
            mLyricsFull.post(() -> highlightFull(mLyricIndex));
        } else {
            mLyricsFull.setVisibility(View.GONE);
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
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(22));
        card.setColor((darken(color, 0.6f) & 0x00FFFFFF) | 0xCC000000);
        mLyricsCard.setBackground(card);
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
            mArtFrame.setScaleX(1f + 0.008f * beat);
            mArtFrame.setScaleY(1f + 0.008f * beat);
        });
        mPulse.start();
        mArtFrame.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
    }

    private void stopPulse() {
        if (mPulse == null) {
            return;
        }
        mPulse.cancel();
        mPulse = null;
        mGlow.animate().scaleX(1f).scaleY(1f).alpha(0.6f).setDuration(400).start();
        mGlow2.animate().translationX(0).translationY(0).alpha(0.5f).setDuration(400).start();
        mArtFrame.animate().scaleX(0.94f).scaleY(0.94f).setDuration(300).start(); // paused: the artwork steps back
    }

    /** The most colourful bright-enough tone of the artwork */
    private static int vibrant(Bitmap bitmap) {
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

    private static int darken(int color, float amount) {
        return blend(color, Color.BLACK, amount);
    }

    private static int lighten(int color) {
        return blend(color, Color.WHITE, 0.35f);
    }

    private static int blend(int from, int to, float t) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    // endregion

    // region Helpers

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
        view.setScaleType(ImageView.ScaleType.CENTER);
        Drawable d = view.getDrawable();
        if (d != null) {
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.max(0, (dp(44) - dp(sizeDp)) / 2);
            view.setPadding(pad, pad, pad, pad);
        }
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
