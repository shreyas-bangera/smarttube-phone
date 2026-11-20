package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * The music player's Up next: a panel of glass in the song's colours sliding up over the player, with the song
 * playing at the top (its bars moving) and the songs after it, in the order they'll play (shuffle's pick first). Tap
 * one to play it; swipe the panel down, tap above it or press Back to close it.
 */
final class PhoneMusicQueue {
    interface Source {
        Video playing();

        List<Video> upNext();

        void play(Video video);

        /** The songs after this one, put in this order */
        void reorder(List<Video> upNext);
    }

    private final Activity mActivity;
    private final FrameLayout mParent;
    private final Source mSource;
    private final float mD;
    private View mScrim;
    private PhoneSheetLayout mPanel;
    private LinearLayout mRows;
    private PhoneMusicPlayer.Bars mBars;
    private boolean mOpen;
    /** The rows of the songs to come, and those songs, in the order shown */
    private final List<View> mNextRows = new java.util.ArrayList<>();
    private final List<Video> mNextVideos = new java.util.ArrayList<>();
    private ScrollView mScroll;

    PhoneMusicQueue(Activity activity, FrameLayout parent, Source source) {
        mActivity = activity;
        mParent = parent;
        mSource = source;
        mD = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return mOpen;
    }

    void open(int color, boolean playing) {
        if (mOpen) {
            return;
        }
        mOpen = true;
        build(color, playing);
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setDuration(220).start();
        mPanel.setTranslationY(mParent.getHeight());
        mPanel.animate().translationY(0).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
        // The rows come in one after another
        for (int i = 0; i < mRows.getChildCount(); i++) {
            View row = mRows.getChildAt(i);
            row.setAlpha(0f);
            row.setTranslationY(dp(18));
            row.animate().alpha(1f).translationY(0).setStartDelay(120 + Math.min(i, 10) * 35L).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }

    void close() {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        View scrim = mScrim;
        View panel = mPanel;
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> mParent.removeView(scrim)).start();
        panel.animate().translationY(mParent.getHeight()).setDuration(240).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> mParent.removeView(panel)).start();
    }

    /** The song playing changed, or played / paused: its row follows */
    void setPlaying(boolean playing) {
        if (mBars != null) {
            mBars.setPlaying(playing);
        }
    }

    private void build(int color, boolean playing) {
        mScrim = new View(mActivity);
        mScrim.setBackgroundColor(0x80000000);
        mScrim.setOnClickListener(v -> close());
        mParent.addView(mScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mPanel = new PhoneSheetLayout(mActivity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setClickable(true);
        mPanel.setOnDismiss(this::dismissed);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {PhoneMusicPlayer.darken(color, 0.42f) | 0xFF000000, 0xFF0B0B0D});
        glass.setCornerRadii(new float[] {dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        glass.setStroke(Math.max(1, dp(1) / 2), (PhoneMusicPlayer.lighten(color) & 0x00FFFFFF) | 0x33000000);
        mPanel.setBackground(glass);
        mPanel.setElevation(dp(24));
        mPanel.setClipToOutline(true);
        mPanel.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        int navigation = mParent.getRootWindowInsets() != null ? mParent.getRootWindowInsets().getSystemWindowInsetBottom() : 0;
        mPanel.setPadding(0, dp(8), 0, navigation + dp(8));

        View handle = new View(mActivity);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(2));
        pill.setColor(0x59FFFFFF);
        handle.setBackground(pill);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        mPanel.addView(handle, handleParams);

        TextView title = text(20, Color.WHITE, true);
        title.setText(R.string.phone_music_up_next);
        title.setPadding(dp(22), dp(14), dp(22), dp(10));
        mPanel.addView(title);

        mRows = new LinearLayout(mActivity);
        mRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(mActivity);
        mScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(mRows);
        mPanel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Video now = mSource.playing();
        if (now != null) {
            mRows.addView(section(R.string.phone_music_queue_now));
            mRows.addView(row(now, color, true, playing));
        }
        List<Video> next = mSource.upNext();
        if (!next.isEmpty()) {
            mRows.addView(section(R.string.phone_music_queue_next));
            mNextRows.clear();
            mNextVideos.clear();
            for (Video video : next) {
                View row = row(video, color, false, false);
                mNextRows.add(row);
                mNextVideos.add(video);
                mRows.addView(row);
            }
        } else {
            TextView none = text(14, 0x99FFFFFF, false);
            none.setText(R.string.phone_music_up_next_none);
            none.setPadding(dp(22), dp(16), dp(22), dp(16));
            mRows.addView(none);
        }

        int height = Math.round(mParent.getHeight() * 0.72f);
        mParent.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    }

    private void dismissed() {
        // Swiped down: already off screen
        mOpen = false;
        mParent.removeView(mPanel);
        View scrim = mScrim;
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> mParent.removeView(scrim)).start();
    }

    private View section(int text) {
        TextView label = text(12, 0x99FFFFFF, true);
        label.setText(text);
        label.setAllCaps(true);
        label.setLetterSpacing(0.1f);
        label.setPadding(dp(22), dp(12), dp(22), dp(6));
        return label;
    }

    private View row(Video video, int color, boolean current, boolean playing) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(18), dp(8));
        if (current) {
            GradientDrawable lit = new GradientDrawable();
            lit.setCornerRadius(dp(16));
            lit.setColor((PhoneMusicPlayer.lighten(color) & 0x00FFFFFF) | 0x1F000000);
            row.setBackground(new android.graphics.drawable.InsetDrawable(lit, dp(8), 0, dp(8), 0));
        } else {
            row.setBackgroundResource(R.drawable.phone_ripple);
            row.setOnClickListener(v -> {
                close();
                mSource.play(video);
            });
        }

        FrameLayout art = new FrameLayout(mActivity);
        art.setClipToOutline(true);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        ImageView thumb = new ImageView(mActivity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0x22FFFFFF);
        art.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (video.videoId != null && PhoneUi.isAlive(mActivity)) {
            Glide.with(mActivity).load("https://i.ytimg.com/vi/" + video.videoId + "/mqdefault.jpg").centerCrop().into(thumb);
        }
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        artParams.setMarginStart(dp(8));
        row.addView(art, artParams);

        LinearLayout words = new LinearLayout(mActivity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(15, Color.WHITE, current);
        name.setText(PhoneLyrics.displayTitle(video.getTitle()));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        TextView by = text(13, current ? PhoneMusicPlayer.blend(color, Color.WHITE, 0.72f) : 0x99FFFFFF, false);
        String artist = PhoneLyrics.cleanArtist(video.getAuthor());
        by.setText(artist != null ? artist : "");
        by.setSingleLine(true);
        by.setEllipsize(TextUtils.TruncateAt.END);
        by.setPadding(0, dp(2), 0, 0);
        words.addView(by);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.setMarginStart(dp(14));
        row.addView(words, wordsParams);

        if (current) {
            mBars = new PhoneMusicPlayer.Bars(mActivity);
            mBars.setColor(PhoneMusicPlayer.blend(color, Color.WHITE, 0.6f));
            mBars.setPlaying(playing);
            row.addView(mBars, new LinearLayout.LayoutParams(dp(18), dp(18)));
        } else if (video.getDurationMs() > 0) {
            TextView length = text(12, 0x80FFFFFF, false);
            length.setText(time(video.getDurationMs()));
            row.addView(length);
        }
        if (!current) {
            // Hold and drag the handle to move the song up or down the queue
            ImageView handle = new ImageView(mActivity);
            handle.setImageResource(R.drawable.ic_phone_drag);
            handle.setImageTintList(android.content.res.ColorStateList.valueOf(0x99FFFFFF));
            handle.setScaleType(ImageView.ScaleType.CENTER);
            handle.setContentDescription(mActivity.getString(R.string.phone_music_queue_move));
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(44));
            handleParams.setMarginStart(dp(4));
            row.addView(handle, handleParams);
            handle.setOnTouchListener(new Dragger(row));
        }
        return row;
    }

    /**
     * Moving a song: hold its handle, and it lifts (a little bigger, a shadow) and follows the finger; passing the
     * middle of the song above or below swaps them (that one slides into the gap). Let go: it settles, and the queue
     * takes the new order.
     */
    private final class Dragger implements View.OnTouchListener {
        private final View mRow;
        private float mLastY;

        Dragger(View row) {
            mRow = row;
        }

        @SuppressWarnings("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    mLastY = event.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true); // the list and the sheet stay put
                    mRow.animate().cancel();
                    mRow.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dp(10)).setDuration(120).start();
                    GradientDrawable lifted = new GradientDrawable();
                    lifted.setCornerRadius(dp(14));
                    lifted.setColor(0x33FFFFFF);
                    mRow.setBackground(lifted);
                    mRow.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - mLastY;
                    mLastY = event.getRawY();
                    mRow.setTranslationY(mRow.getTranslationY() + dy);
                    swapIfPast();
                    keepInView(event.getRawY());
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    mRow.animate().translationY(0).scaleX(1f).scaleY(1f).translationZ(0).setDuration(180)
                            .withEndAction(() -> mRow.setBackgroundResource(R.drawable.phone_ripple)).start();
                    mSource.reorder(new java.util.ArrayList<>(mNextVideos));
                    return true;
                default:
                    return true;
            }
        }

        /** Past the middle of the next song up or down: they change places */
        private void swapIfPast() {
            int at = mNextRows.indexOf(mRow);
            float shift = mRow.getTranslationY();
            if (shift > 0 && at < mNextRows.size() - 1) {
                View below = mNextRows.get(at + 1);
                if (shift > below.getHeight() / 2f) {
                    move(at, at + 1, below, -1);
                }
            } else if (shift < 0 && at > 0) {
                View above = mNextRows.get(at - 1);
                if (-shift > above.getHeight() / 2f) {
                    move(at, at - 1, above, 1);
                }
            }
        }

        /** The dragged row and the one next to it change places (otherDirection: -1 it was below, 1 above) */
        private void move(int from, int to, View other, int otherDirection) {
            int index = mRows.indexOfChild(mRow);
            mRows.removeView(other);
            mRows.addView(other, index); // below: it goes before the dragged row; above: after it
            java.util.Collections.swap(mNextRows, from, to);
            java.util.Collections.swap(mNextVideos, from, to);
            // The dragged row stays under the finger; the other one slides into the gap it left
            mRow.setTranslationY(mRow.getTranslationY() + otherDirection * other.getHeight());
            other.setTranslationY(-otherDirection * mRow.getHeight());
            other.animate().translationY(0).setDuration(160).start();
            mRow.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }

        /** Near the top or bottom of the list: it scrolls a little */
        private void keepInView(float rawY) {
            int[] at = new int[2];
            mScroll.getLocationOnScreen(at);
            float edge = dp(48);
            if (rawY < at[1] + edge) {
                mScroll.scrollBy(0, -dp(8));
            } else if (rawY > at[1] + mScroll.getHeight() - edge) {
                mScroll.scrollBy(0, dp(8));
            }
        }
    }

    private static String time(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView view = new TextView(mActivity);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(medium ? PhoneUi.medium(mActivity) : PhoneUi.regular(mActivity));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * mD);
    }
}
