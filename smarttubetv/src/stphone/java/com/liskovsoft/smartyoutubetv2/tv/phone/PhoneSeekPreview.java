package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.previewtimebar.StoryboardManager;

/**
 * While seeking (dragging the seek bar, or the line along the bottom of the video), a picture of the video at that
 * point, with its time under it, above the finger, like the YouTube app. The pictures are YouTube's storyboards (small
 * frames every few seconds), loaded once per video; short videos without them show the time alone.
 */
final class PhoneSeekPreview {
    private final Activity mActivity;
    private final FrameLayout mParent;
    private final StoryboardManager mStoryboard;
    private final float mD;
    private LinearLayout mView;
    private FrameLayout mCard;
    private ImageView mFrame;
    private TextView mTime;
    private TextView mChapter;
    private String mPreparedFor;
    private long mPreparedLength;
    private int mWanted = -1;

    PhoneSeekPreview(Activity activity, FrameLayout parent) {
        mActivity = activity;
        mParent = parent;
        mStoryboard = new StoryboardManager(activity);
        mD = activity.getResources().getDisplayMetrics().density;
    }

    /** The video's storyboard, loaded ahead (once per video, once its length is known) */
    void prepare(Video video, long lengthMs) {
        if (video == null || video.videoId == null || lengthMs <= 0) {
            return;
        }
        if (video.videoId.equals(mPreparedFor) && Math.abs(lengthMs - mPreparedLength) < 2_000) {
            return;
        }
        mPreparedFor = video.videoId;
        mPreparedLength = lengthMs;
        mWanted = -1;
        mStoryboard.init(video, lengthMs);
    }

    /** Seeking to progress (of 1000), at this time, in this chapter (or none) */
    void show(int progress, String time, String chapter) {
        build();
        long[] positions = mStoryboard.getSeekPositions();
        boolean pictures = positions != null && positions.length > 0;
        mCard.setVisibility(pictures ? View.VISIBLE : View.GONE);
        mTime.setText(time);
        boolean named = chapter != null && !chapter.isEmpty();
        mChapter.setVisibility(named ? View.VISIBLE : View.GONE);
        if (named) {
            mChapter.setText(chapter);
        }
        if (mView.getVisibility() != View.VISIBLE) {
            mView.setVisibility(View.VISIBLE);
            mView.setAlpha(0f);
            mView.setScaleX(0.9f);
            mView.setScaleY(0.9f);
            mView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
        }
        // Above the finger, kept inside the video
        float width = mView.getWidth() > 0 ? mView.getWidth() : (pictures ? cardWidth() : dp(60));
        float x = mParent.getWidth() * progress / 1000f - width / 2f;
        mView.setTranslationX(Math.max(dp(8), Math.min(mParent.getWidth() - width - dp(8), x)));
        if (pictures) {
            int index = Math.max(0, Math.min(positions.length - 1, Math.round(progress / 1000f * (positions.length - 1))));
            if (index != mWanted) {
                mWanted = index;
                mStoryboard.getBitmap(index, bitmap -> {
                    if (index == mWanted && mFrame != null) {
                        mFrame.setImageBitmap(bitmap);
                    }
                });
            }
        }
    }

    void hide() {
        if (mView != null && mView.getVisibility() == View.VISIBLE) {
            View view = mView;
            view.animate().alpha(0f).setDuration(120).withEndAction(() -> view.setVisibility(View.GONE)).start();
        }
    }

    private void build() {
        boolean landscape = mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int bottom = dp(landscape ? 84 : 52); // above the time row and the seek bar
        if (mView != null) {
            ViewGroup.LayoutParams card = mCard.getLayoutParams();
            if (card.width != cardWidth()) { // turned: the size for the new orientation
                card.width = cardWidth();
                card.height = Math.round(cardWidth() * 9f / 16f);
                mCard.setLayoutParams(card);
            }
            ((FrameLayout.LayoutParams) mView.getLayoutParams()).bottomMargin = bottom;
            mView.requestLayout();
            return;
        }
        mView = new LinearLayout(mActivity);
        mView.setOrientation(LinearLayout.VERTICAL);
        mView.setGravity(Gravity.CENTER_HORIZONTAL);
        mView.setVisibility(View.GONE);

        mCard = new FrameLayout(mActivity);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(8));
        border.setColor(Color.BLACK);
        border.setStroke(dp(2), Color.WHITE);
        mCard.setBackground(border);
        mCard.setPadding(dp(2), dp(2), dp(2), dp(2));
        mCard.setElevation(dp(6));
        mCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        mCard.setClipToOutline(true);
        mFrame = new ImageView(mActivity);
        mFrame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mCard.addView(mFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mView.addView(mCard, new LinearLayout.LayoutParams(cardWidth(), Math.round(cardWidth() * 9f / 16f)));

        // The chapter's name, under the picture
        mChapter = new TextView(mActivity);
        mChapter.setTextColor(Color.WHITE);
        mChapter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mChapter.setTypeface(PhoneUi.medium(mActivity));
        mChapter.setShadowLayer(dp(4), 0, 0, 0xCC000000);
        mChapter.setSingleLine(true);
        mChapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mChapter.setMaxWidth(dp(220));
        mChapter.setPadding(dp(6), dp(5), dp(6), 0);
        mChapter.setVisibility(View.GONE);
        mView.addView(mChapter);

        mTime = new TextView(mActivity);
        mTime.setTextColor(Color.WHITE);
        mTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTime.setTypeface(PhoneUi.medium(mActivity));
        mTime.setShadowLayer(dp(4), 0, 0, 0xCC000000);
        mTime.setPadding(dp(6), dp(2), dp(6), 0);
        mView.addView(mTime);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        params.bottomMargin = bottom;
        mParent.addView(mView, params);
    }

    private int cardWidth() {
        boolean landscape = mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        return dp(landscape ? 200 : 128);
    }

    private int dp(float value) {
        return Math.round(value * mD);
    }
}
