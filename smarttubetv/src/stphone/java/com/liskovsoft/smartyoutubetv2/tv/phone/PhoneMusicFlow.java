package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Cover Flow: the songs of the queue as a row of covers, the one in the middle facing you and the others turned
 * towards it on both sides, the ones played before on the left and the ones to come on the right. Swipe through
 * them; tap the middle one to play it, a side one to bring it to the middle.
 *
 * In landscape it's the music player itself, with a slim bar of controls under the covers (time, the song, shuffle,
 * previous, play and next). In portrait it opens over the player from a long press on the artwork, and closes with
 * Back, the close button, a tap above or below the covers, or once a song is picked.
 */
final class PhoneMusicFlow {
    interface Host {
        Video playing();

        /** The songs played before this one, oldest first */
        List<Video> before();

        /** The songs after this one, in order */
        List<Video> upNext();

        void play(Video video);

        SimpleExoPlayer exo();

        void togglePlay();

        void next();

        void previous();

        void toggleShuffle();

        boolean shuffle();

        void minimize();
    }

    private final Activity mActivity;
    private final FrameLayout mParent;
    private final Host mHost;
    private final float mD;
    private FrameLayout mScreen;
    private Flow mFlow;
    private TextView mTitle;
    private TextView mArtist;
    private PhoneMusicPlayer.Bars mBars;
    private SeekBar mSeek;
    private TextView mElapsed;
    private TextView mLeft;
    private ImageView mPlay;
    private ImageView mShuffle;
    private boolean mLandscape;
    private boolean mSeeking;
    private int mColor = 0xFF8899FF;
    private final List<Video> mItems = new ArrayList<>();
    private int mPlayingIndex;
    private String mItemsKey = "";
    private String mPlayingId;
    /** When the covers were last touched: the flow follows the song playing only after a while */
    private long mTouchedAt;
    private Runnable mOnHide;

    PhoneMusicFlow(Activity activity, FrameLayout parent, Host host) {
        mActivity = activity;
        mParent = parent;
        mHost = host;
        mD = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShown() {
        return mScreen != null;
    }

    boolean isLandscape() {
        return mScreen != null && mLandscape;
    }

    /** Open it, as the landscape player or over the portrait one */
    void show(boolean landscape) {
        if (mScreen != null && mLandscape == landscape) {
            return;
        }
        if (mScreen != null) {
            mParent.removeView(mScreen);
            mScreen = null;
        }
        mLandscape = landscape;
        mItemsKey = "";
        mPlayingId = null;
        build();
        refresh(true);
        mScreen.setAlpha(0f);
        mScreen.animate().alpha(1f).setDuration(220).start();
        mFlow.setScaleX(0.92f);
        mFlow.setScaleY(0.92f);
        mFlow.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /** Told when it closes (the player behind comes back into focus) */
    void setOnHide(Runnable onHide) {
        mOnHide = onHide;
    }

    void hide() {
        if (mScreen == null) {
            return;
        }
        if (mOnHide != null) {
            mOnHide.run();
        }
        View screen = mScreen;
        mScreen = null;
        screen.animate().alpha(0f).setDuration(180).withEndAction(() -> mParent.removeView(screen)).start();
    }

    void setColor(int color) {
        mColor = color;
        if (mScreen == null) {
            return;
        }
        mBars.setColor(PhoneMusicPlayer.blend(color, Color.WHITE, 0.6f));
        if (mPlay != null) {
            mPlay.setImageTintList(android.content.res.ColorStateList.valueOf(PhoneMusicPlayer.darken(color, 0.62f)));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                mPlay.setOutlineSpotShadowColor(PhoneMusicPlayer.lighten(color));
            }
        }
        if (mShuffle != null) {
            showShuffle();
        }
    }

    /** The queue again (a new song, more songs to come): the covers follow, and the playing one comes to the middle */
    void refresh(boolean jump) {
        if (mScreen == null) {
            return;
        }
        Video playing = mHost.playing();
        List<Video> items = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int playingIndex = 0;
        for (Video video : mHost.before()) {
            if (video != null && video.videoId != null && !seen.contains(video.videoId)
                    && (playing == null || !video.videoId.equals(playing.videoId))) {
                seen.add(video.videoId);
                items.add(video);
            }
        }
        if (playing != null && playing.videoId != null) {
            playingIndex = items.size();
            seen.add(playing.videoId);
            items.add(playing);
        }
        for (Video video : mHost.upNext()) {
            if (video != null && video.videoId != null && !seen.contains(video.videoId)) {
                seen.add(video.videoId);
                items.add(video);
            }
        }
        String key = TextUtils.join(",", seen);
        if (key.equals(mItemsKey)) {
            return;
        }
        String playingId = playing != null ? playing.videoId : null;
        boolean songChanged = playingId != null && !playingId.equals(mPlayingId);
        mPlayingId = playingId;
        mItemsKey = key;
        mItems.clear();
        mItems.addAll(items);
        mPlayingIndex = playingIndex;
        boolean follow = jump || songChanged && System.currentTimeMillis() - mTouchedAt > 3_000;
        mFlow.setItems(mItems, follow ? playingIndex : Math.min(mFlow.focused(), mItems.size() - 1), jump);
        showFocused(mFlow.focused());
    }

    /** Every tick of the player: the controls follow it */
    void update(boolean playing, boolean moving) {
        if (mScreen == null) {
            return;
        }
        mBars.setPlaying(moving);
        if (!mLandscape) {
            return;
        }
        mPlay.setImageResource(playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
        SimpleExoPlayer exo = mHost.exo();
        if (exo != null && !mSeeking && exo.getDuration() > 0) {
            long duration = exo.getDuration();
            long position = exo.getCurrentPosition();
            mSeek.setProgress((int) (position * 1000 / duration));
            mElapsed.setText(time(position));
            mLeft.setText("-" + time(Math.max(0, duration - position)));
        }
        showShuffle();
    }

    // region Building

    private void build() {
        mScreen = new FrameLayout(mActivity);
        mScreen.setClickable(true);
        // Over the player's own living background (its colours, lights and the song's animation), just a touch
        // darker so the covers stand out
        mScreen.setBackgroundColor(0x1F000000);
        mScreen.setOnClickListener(v -> {
            if (!mLandscape) {
                hide(); // a tap above or below the covers
            }
        });
        android.view.WindowInsets insets = mParent.getRootWindowInsets();
        int left = insets != null ? insets.getSystemWindowInsetLeft() : 0;
        int right = insets != null ? insets.getSystemWindowInsetRight() : 0;
        int top = insets != null ? insets.getSystemWindowInsetTop() : 0;
        int bottom = insets != null ? insets.getSystemWindowInsetBottom() : 0;
        mScreen.setPadding(left, top, right, bottom);
        mParent.addView(mScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int width = mParent.getWidth() - left - right;
        int height = mParent.getHeight() - top - bottom;
        mFlow = new Flow(mActivity, mLandscape);
        mFlow.setListener(new Flow.Listener() {
            @Override
            public void onFocused(int index) {
                mTouchedAt = System.currentTimeMillis();
                showFocused(index);
            }

            @Override
            public void onPicked(int index) {
                pick(index);
            }
        });

        LinearLayout column = new LinearLayout(mActivity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        mScreen.setClipChildren(false);

        int cover;
        if (mLandscape) {
            cover = Math.round(Math.min(height * 0.52f, width * 0.25f));
            mFlow.setCoverSize(cover);
            column.setPadding(0, Math.round(height * 0.08f), 0, 0);
            column.addView(mFlow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(cover * 1.2f)));
            mScreen.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            mScreen.addView(controlBar(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            // Top left: back to the video player's small bar, as the portrait player's arrow
            ImageView down = button(R.drawable.ic_phone_collapse, 24, 44);
            down.setContentDescription(mActivity.getString(R.string.phone_music_minimize));
            down.setOnClickListener(v -> mHost.minimize());
            FrameLayout.LayoutParams downParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
            downParams.setMargins(dp(12), dp(8), 0, 0);
            mScreen.addView(down, downParams);
        } else {
            cover = Math.round(Math.min(width * 0.5f, height * 0.32f));
            mFlow.setCoverSize(cover);
            column.addView(mFlow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(cover * 1.25f)));
            LinearLayout words = labels(Gravity.CENTER_HORIZONTAL, 20);
            LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            wordsParams.topMargin = dp(26);
            column.addView(words, wordsParams);
            TextView hint = text(13, 0x80FFFFFF, false);
            hint.setText(R.string.phone_music_flow_hint);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = dp(18);
            column.addView(hint, hintParams);
            mScreen.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            ImageView close = button(R.drawable.ic_phone_collapse, 24, 44);
            close.setContentDescription(mActivity.getString(R.string.phone_music_flow_close));
            close.setOnClickListener(v -> hide());
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
            closeParams.setMargins(dp(14), dp(8), 0, 0);
            mScreen.addView(close, closeParams);
        }
        setColor(mColor);
    }

    /** Landscape: time on the left, the song in the middle, the buttons on the right */
    private View controlBar() {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), dp(6), dp(20), dp(14));
        bar.setClipChildren(false);

        LinearLayout time = new LinearLayout(mActivity);
        time.setOrientation(LinearLayout.VERTICAL);
        mSeek = new SeekBar(mActivity);
        mSeek.setMax(1000);
        mSeek.setPadding(dp(6), dp(8), dp(6), dp(4));
        mSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        mSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                SimpleExoPlayer exo = mHost.exo();
                if (fromUser && exo != null && exo.getDuration() > 0) {
                    long position = exo.getDuration() * progress / 1000;
                    mElapsed.setText(time(position));
                    mLeft.setText("-" + time(Math.max(0, exo.getDuration() - position)));
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
        time.addView(mSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = new LinearLayout(mActivity);
        times.setPadding(dp(10), 0, dp(10), 0);
        mElapsed = text(11, 0x99FFFFFF, false);
        mLeft = text(11, 0x99FFFFFF, false);
        mLeft.setGravity(Gravity.END);
        times.addView(mElapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(mLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        time.addView(times);
        bar.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f);
        wordsParams.setMargins(dp(16), 0, dp(16), 0);
        bar.addView(labels(Gravity.CENTER_HORIZONTAL, 16), wordsParams);

        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        buttons.setClipChildren(false);
        mShuffle = button(R.drawable.ic_phone_mode_shuffle, 20, 44);
        mShuffle.setContentDescription(mActivity.getString(R.string.phone_music_shuffle));
        mShuffle.setOnClickListener(v -> {
            mHost.toggleShuffle();
            showShuffle();
        });
        ImageView previous = button(R.drawable.ic_phone_prev, 28, 48);
        previous.setOnClickListener(v -> mHost.previous());
        mPlay = new ImageView(mActivity);
        mPlay.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(Color.WHITE);
        mPlay.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), playBg, null));
        mPlay.setElevation(dp(8));
        mPlay.setImageResource(R.drawable.ic_phone_play);
        mPlay.setOnClickListener(v -> mHost.togglePlay());
        ImageView next = button(R.drawable.ic_phone_next, 28, 48);
        next.setOnClickListener(v -> mHost.next());
        buttons.addView(mShuffle, new LinearLayout.LayoutParams(dp(44), dp(44)));
        buttons.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        playParams.setMargins(dp(6), 0, dp(6), 0);
        buttons.addView(mPlay, playParams);
        buttons.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(buttons, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    /** The focused song's title and artist, the playing one with its bars moving */
    private LinearLayout labels(int gravity, int titleSp) {
        LinearLayout words = new LinearLayout(mActivity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(gravity);
        words.setPadding(dp(24), 0, dp(24), 0);
        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setGravity(Gravity.CENTER);
        mBars = new PhoneMusicPlayer.Bars(mActivity);
        LinearLayout.LayoutParams barsParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        barsParams.setMarginEnd(dp(8));
        titleRow.addView(mBars, barsParams);
        mTitle = text(titleSp, Color.WHITE, true);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(mTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        words.addView(titleRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mArtist = text(titleSp - 4, 0xB3FFFFFF, false);
        mArtist.setSingleLine(true);
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        mArtist.setGravity(Gravity.CENTER);
        mArtist.setPadding(0, dp(4), 0, 0);
        words.addView(mArtist, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return words;
    }

    private void showFocused(int index) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        Video video = mItems.get(index);
        String title = PhoneLyrics.displayTitle(video.getTitle());
        if (!title.contentEquals(mTitle.getText())) {
            mTitle.setText(title);
            String artist = PhoneLyrics.cleanArtist(video.getAuthor());
            mArtist.setText(artist != null ? artist : "");
        }
        mBars.setVisibility(index == mPlayingIndex ? View.VISIBLE : View.GONE);
    }

    private void showShuffle() {
        boolean on = mHost.shuffle();
        mShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(on ? PhoneMusicPlayer.lighten(mColor) : Color.WHITE));
        mShuffle.setAlpha(on ? 1f : 0.6f);
    }

    private void pick(int index) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        if (index != mPlayingIndex) {
            mHost.play(mItems.get(index));
        }
        if (!mLandscape) {
            hide();
        }
    }

    private ImageView button(int drawable, int iconDp, int sizeDp) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(drawable);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.max(0, (dp(sizeDp) - dp(iconDp)) / 2);
        view.setPadding(pad, pad, pad, pad);
        TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        return view;
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView view = new TextView(mActivity);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(medium ? PhoneUi.medium(mActivity) : PhoneUi.regular(mActivity));
        return view;
    }

    private static String time(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(float value) {
        return Math.round(value * mD);
    }

    // endregion

    /**
     * The row of covers. Its position is a fractional index (the cover in the middle); each cover is placed, turned
     * and scaled from how far it is from there, so a drag moves them all smoothly, and a let go settles on the
     * nearest one (or further, after a flick).
     */
    static final class Flow extends FrameLayout {
        interface Listener {
            void onFocused(int index);

            void onPicked(int index);
        }

        /** Covers drawn on each side of the middle one */
        private static final int SIDE = 8;
        private final boolean mWide;
        private final float mD;
        private final int mSlop;
        private final List<FrameLayout> mCards = new ArrayList<>();
        private List<Video> mItems = new ArrayList<>();
        private Listener mListener;
        private int mSize;
        private float mPos;
        private int mFocused = -1;
        private float mDownX;
        private float mDownPos;
        private boolean mDragging;
        private VelocityTracker mVelocity;
        private ValueAnimator mSettle;

        Flow(Context context, boolean wide) {
            super(context);
            mWide = wide;
            mD = context.getResources().getDisplayMetrics().density;
            mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(false);
            setClipToPadding(false);
        }

        void setListener(Listener listener) {
            mListener = listener;
        }

        void setCoverSize(int size) {
            mSize = size;
        }

        int focused() {
            return Math.max(0, mFocused);
        }

        void setItems(List<Video> items, int focus, boolean jump) {
            mItems = new ArrayList<>(items);
            removeAllViews();
            mCards.clear();
            for (Video video : mItems) {
                mCards.add(card(video));
            }
            focus = Math.max(0, Math.min(focus, mItems.size() - 1));
            if (jump || mFocused < 0) {
                stopSettle();
                mPos = focus;
                mFocused = -1;
                place();
            } else {
                place();
                settleTo(focus);
            }
        }

        private FrameLayout card(Video video) {
            FrameLayout card = new FrameLayout(getContext());
            card.setBackgroundColor(0xFF1C1C1E);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 6 * mD);
                }
            });
            card.setClipToOutline(true);
            card.setElevation(8 * mD);
            card.setCameraDistance(mSize * 14f);
            ImageView art = new ImageView(getContext());
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // A sheen across the top, as on glossy cover art
            View sheen = new View(getContext());
            sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
            card.addView(sheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(card, new FrameLayout.LayoutParams(mSize, mSize, Gravity.CENTER));
            if (video.videoId != null && PhoneUi.isAlive(getContext())) {
                Glide.with(getContext()).load("https://i.ytimg.com/vi/" + video.videoId + "/hq720.jpg")
                        .error(Glide.with(getContext()).load("https://i.ytimg.com/vi/" + video.videoId + "/mqdefault.jpg"))
                        .into(art);
            }
            return card;
        }

        /** Every cover where it belongs for the position now */
        private void place() {
            float gap = mSize * (mWide ? 0.7f : 0.64f); // from the middle to the first cover on a side
            float step = mSize * (mWide ? 0.2f : 0.15f); // between the side covers
            for (int i = 0; i < mCards.size(); i++) {
                FrameLayout card = mCards.get(i);
                float d = i - mPos;
                float a = Math.abs(d);
                if (a > SIDE + 1) {
                    card.setVisibility(INVISIBLE);
                    continue;
                }
                card.setVisibility(VISIBLE);
                float near = Math.min(1f, a);
                float x = a <= 1 ? d * gap : Math.signum(d) * (gap + (a - 1) * step);
                card.setTranslationX(x);
                card.setRotationY(-Math.signum(d) * near * 64f);
                float scale = 1.12f - 0.28f * near; // the middle one a little bigger than the rest
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setTranslationZ((SIDE + 2 - a) * mD); // the nearer the middle, the more on top
                card.setAlpha(a > SIDE ? Math.max(0f, SIDE + 1 - a) : 1f);
                // The turned covers a little darker, as if lit from the front
                if (card.getChildCount() > 1) {
                    card.getChildAt(0).setAlpha(1f - 0.28f * near);
                }
            }
            int focus = Math.round(mPos);
            if (focus != mFocused && focus >= 0 && focus < mItems.size()) {
                boolean first = mFocused < 0;
                mFocused = focus;
                if (!first) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                if (mListener != null) {
                    mListener.onFocused(focus);
                }
            }
        }

        private void settleTo(int index) {
            stopSettle();
            index = Math.max(0, Math.min(index, mItems.size() - 1));
            mSettle = ValueAnimator.ofFloat(mPos, index);
            mSettle.setDuration(Math.min(700, 260 + Math.round(Math.abs(index - mPos) * 60)));
            mSettle.setInterpolator(new DecelerateInterpolator(1.8f));
            mSettle.addUpdateListener(a -> {
                mPos = (float) a.getAnimatedValue();
                place();
            });
            mSettle.start();
        }

        private void stopSettle() {
            if (mSettle != null) {
                mSettle.cancel();
                mSettle = null;
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return true; // the covers themselves don't take touches: the row does
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mItems.isEmpty()) {
                return false;
            }
            float span = mSize * (mWide ? 0.42f : 0.36f); // a drag this long moves one cover
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopSettle();
                    mDownX = event.getX();
                    mDownPos = mPos;
                    mDragging = false;
                    if (mVelocity != null) {
                        mVelocity.recycle();
                    }
                    mVelocity = VelocityTracker.obtain();
                    mVelocity.addMovement(event);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    mVelocity.addMovement(event);
                    float dx = event.getX() - mDownX;
                    if (!mDragging && Math.abs(dx) > mSlop) {
                        mDragging = true;
                    }
                    if (mDragging) {
                        float pos = mDownPos - dx / span;
                        // Past the ends: it gives a little, then holds
                        float max = mItems.size() - 1;
                        if (pos < 0) {
                            pos = -0.35f * (1f - 1f / (1f - pos));
                        } else if (pos > max) {
                            pos = max + 0.35f * (1f - 1f / (1f + pos - max));
                        }
                        mPos = pos;
                        place();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    mVelocity.addMovement(event);
                    mVelocity.computeCurrentVelocity(1000);
                    float vx = mVelocity.getXVelocity();
                    mVelocity.recycle();
                    mVelocity = null;
                    if (mDragging) {
                        float projected = mPos - vx / span * 0.22f; // a flick carries it on
                        settleTo(Math.round(projected));
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        tap(event.getX());
                    }
                    mDragging = false;
                    return true;
                }
                default:
                    return true;
            }
        }

        /** The cover under the finger: the middle one plays, a side one comes to the middle */
        private void tap(float x) {
            float center = getWidth() / 2f;
            int hit = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < mCards.size(); i++) {
                FrameLayout card = mCards.get(i);
                if (card.getVisibility() != VISIBLE) {
                    continue;
                }
                float half = mSize * card.getScaleX() / 2f * (float) Math.max(0.35, Math.cos(Math.toRadians(card.getRotationY())));
                float cx = center + card.getTranslationX();
                float distance = Math.abs(i - mPos);
                if (Math.abs(x - cx) <= half + 4 * mD && distance < best) {
                    best = distance;
                    hit = i;
                }
            }
            if (hit < 0) {
                performClick(); // beside the covers
                return;
            }
            if (hit == Math.round(mPos)) {
                if (mListener != null) {
                    mListener.onPicked(hit);
                }
            } else {
                settleTo(hit);
            }
        }

        @Override
        public boolean performClick() {
            ViewGroup parent = (ViewGroup) getParent();
            while (parent != null && !(parent.isClickable())) {
                parent = parent.getParent() instanceof ViewGroup ? (ViewGroup) parent.getParent() : null;
            }
            if (parent != null) {
                parent.performClick(); // as a tap on the screen around the covers
            }
            return super.performClick();
        }
    }
}
