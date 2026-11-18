package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.annotation.SuppressLint;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The mini player for music: a dark, rounded pill at the bottom of the app with the song's artwork, title and artist,
 * pause (circled by how far the song has played) and next. Tinted with the artwork's colour. Tap it for the music
 * player; swipe it down or sideways to stop the music.
 */
@SuppressLint("ViewConstructor")
final class PhoneMusicPill extends FrameLayout {
    interface Actions {
        SimpleExoPlayer exo();

        Video video();

        void expand();

        void next();

        void close();
    }

    private final PhoneActivity mHost;
    private final Actions mActions;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ImageView mArt;
    private final TextView mTitle;
    private final TextView mArtist;
    private final Ring mPlay;
    private final View mTint;
    private String mVideoId;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, 250);
        }
    };

    PhoneMusicPill(PhoneActivity host, Actions actions) {
        super(host);
        mHost = host;
        mActions = actions;
        Context context = host;
        setElevation(dp(14));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(30));
            }
        });
        setClipToOutline(true);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(30));
        background.setColor(0xF0161616);
        background.setStroke(dp(1), 0x26FFFFFF);
        setBackground(background);

        // The artwork's colour, glowing in from the left
        mTint = new View(context);
        addView(mTint, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mArt = new ImageView(context);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(0x22FFFFFF);
        mArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22));
            }
        });
        mArt.setClipToOutline(true);
        row.addView(mArt, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, dp(8), 0);
        mTitle = new TextView(context);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mTitle.setTypeface(PhoneUi.medium(context));
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        words.addView(mTitle);
        mArtist = new TextView(context);
        mArtist.setTextColor(0x99FFFFFF);
        mArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mArtist.setTypeface(PhoneUi.regular(context));
        mArtist.setSingleLine(true);
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(mArtist);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mPlay = new Ring(context);
        mPlay.setOnClickListener(v -> {
            SimpleExoPlayer exo = mActions.exo();
            if (exo != null) {
                exo.setPlayWhenReady(!exo.getPlayWhenReady());
                update();
            }
        });
        row.addView(mPlay, new LinearLayout.LayoutParams(dp(52), dp(52)));

        ImageView next = new ImageView(context);
        next.setImageResource(R.drawable.ic_phone_next);
        next.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setPadding(dp(10), dp(10), dp(10), dp(10));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        next.setBackgroundResource(ripple.resourceId);
        next.setOnClickListener(v -> mActions.next());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        nextParams.setMarginStart(dp(4));
        row.addView(next, nextParams);

        setupGestures();
    }

    PhoneActivity host() {
        return mHost;
    }

    /** At the bottom of the screen, above the tabs; slides up into place */
    void place(boolean animate) {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
            frame.gravity = Gravity.BOTTOM;
            frame.leftMargin = dp(10);
            frame.rightMargin = dp(10);
            frame.bottomMargin = mHost.miniPlayerBottomOffset() + dp(8);
            setLayoutParams(frame);
        }
        if (animate) {
            setTranslationY(dp(120));
            setAlpha(0f);
            animate().translationY(0).alpha(1f).setDuration(320).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mTick);
    }

    void remove() {
        mHandler.removeCallbacks(mTick);
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
    }

    /** Stopped: slides down off the screen (or off the side it was thrown to) */
    void animateAway(float toX) {
        mHandler.removeCallbacks(mTick);
        animate().cancel();
        animate().translationX(toX).translationY(toX == 0 ? dp(140) : getTranslationY()).alpha(0f).setDuration(220)
                .withEndAction(this::remove).start();
    }

    private void update() {
        SimpleExoPlayer exo = mActions.exo();
        Video video = mActions.video();
        if (video != null && video.videoId != null && !video.videoId.equals(mVideoId)) {
            mVideoId = video.videoId;
            bind(video);
        }
        if (exo == null) {
            return;
        }
        long duration = exo.getDuration();
        mPlay.set(exo.getPlayWhenReady(), duration > 0 ? exo.getCurrentPosition() / (float) duration : 0f);
    }

    private void bind(Video video) {
        String title = video.getTitle();
        java.util.List<String> names = PhoneLyrics.trackNames(title);
        mTitle.setText(names.isEmpty() ? title : names.get(names.size() - 1));
        String artist = PhoneLyrics.cleanArtist(video.getAuthor());
        mArtist.setText(artist != null ? artist : "");
        if (!PhoneUi.isAlive(getContext())) {
            return;
        }
        String id = video.videoId;
        Glide.with(getContext()).load("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").centerCrop().into(mArt);
        Glide.with(getContext()).asBitmap().load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").override(48, 27)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        if (!id.equals(mVideoId)) {
                            return;
                        }
                        int color = PhoneMusicPlayer.vibrant(bitmap);
                        mTint.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                                new int[] {(color & 0x00FFFFFF) | 0x59000000, (color & 0x00FFFFFF) | 0x14000000, 0x00000000}));
                        mPlay.setColor(PhoneMusicPlayer.lighten(color));
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    /** Tap: the music player. Swipe down or sideways: the music stops. */
    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        float[] down = new float[2];
        boolean[] dragging = {false};
        int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    dragging[0] = false;
                    animate().cancel();
                    animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > slop) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        boolean sideways = Math.abs(dx) > Math.abs(dy);
                        setTranslationX(sideways ? dx : 0);
                        setTranslationY(sideways ? 0 : Math.max(0, dy));
                        float travel = sideways ? Math.abs(dx) / getWidth() : Math.max(0, dy) / (getHeight() * 1.5f);
                        setAlpha(1f - Math.min(0.7f, travel));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    float dx = getTranslationX();
                    float dy = getTranslationY();
                    if (!dragging[0] && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        mActions.expand();
                    } else if (Math.abs(dx) > getWidth() * 0.35f) {
                        animateAway(Math.signum(dx) * getWidth() * 1.2f);
                        mActions.close();
                    } else if (dy > getHeight() * 0.8f) {
                        animateAway(0);
                        mActions.close();
                    } else {
                        animate().translationX(0).translationY(0).alpha(1f).setDuration(200).start();
                    }
                    dragging[0] = false;
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Play / pause, with how far the song has played drawn around it */
    private static final class Ring extends View {
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mProgress = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mOval = new RectF();
        private final Drawable mPause;
        private final Drawable mPlayIcon;
        private boolean mPlaying;
        private float mFraction;

        Ring(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            mTrack.setStyle(Paint.Style.STROKE);
            mTrack.setStrokeWidth(3 * density);
            mTrack.setColor(0x33FFFFFF);
            mProgress.setStyle(Paint.Style.STROKE);
            mProgress.setStrokeWidth(3 * density);
            mProgress.setStrokeCap(Paint.Cap.ROUND);
            mProgress.setColor(0xFF4FC3F7);
            mDot.setColor(0xFF4FC3F7);
            mPause = context.getResources().getDrawable(R.drawable.ic_phone_pause).mutate();
            mPlayIcon = context.getResources().getDrawable(R.drawable.ic_phone_play).mutate();
            mPause.setTint(Color.WHITE);
            mPlayIcon.setTint(Color.WHITE);
            TypedValue ripple = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            setBackgroundResource(ripple.resourceId);
        }

        void setColor(int color) {
            mProgress.setColor(color);
            mDot.setColor(color);
            invalidate();
        }

        void set(boolean playing, float fraction) {
            if (playing != mPlaying || Math.abs(fraction - mFraction) > 0.0005f) {
                mPlaying = playing;
                mFraction = Math.max(0f, Math.min(1f, fraction));
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = mTrack.getStrokeWidth() * 2f;
            mOval.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawOval(mOval, mTrack);
            float sweep = 360f * mFraction;
            canvas.drawArc(mOval, -90f, sweep, false, mProgress);
            double angle = Math.toRadians(sweep - 90f);
            float cx = mOval.centerX() + (float) Math.cos(angle) * mOval.width() / 2f;
            float cy = mOval.centerY() + (float) Math.sin(angle) * mOval.height() / 2f;
            canvas.drawCircle(cx, cy, mTrack.getStrokeWidth() * 1.4f, mDot);
            Drawable icon = mPlaying ? mPause : mPlayIcon;
            int size = Math.round(getWidth() * 0.46f);
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
    }
}
