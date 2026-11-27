package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search inside a video: find a word or phrase in what's said (the video's captions) and jump to it.
 * A panel under the video lists the transcript (the playing line lit) or, with words typed, the lines holding them,
 * with yellow ticks on the seek bar where each one is. Tap a line to jump there: the panel closes and the line shows
 * on the video for a few seconds with the words in yellow.
 */
final class PhoneVideoSearch {
    interface Host {
        Video current();

        long positionMs();

        long durationMs();

        void seekTo(long ms);

        View videoBox();

        int navInset();

        /** Fractions of the video's length to mark on the seek bar, empty for none */
        void setTicks(float[] ticks);


    }

    private static final int YELLOW = 0xFFFFD54A;
    private static final int YELLOW_TEXT = 0xFFFFE082;

    private final Activity mActivity;
    private final Host mHost;
    private List<PhoneTranscript.Line> mLines;
    private String mVideoId;
    private String mQuery = "";
    private final List<Integer> mHits = new ArrayList<>();
    private int mIndex = -1;
    private int mPlaying = -1;
    private boolean mLoading;

    private LinearLayout mPanel;
    private EditText mField;
    private TextView mStatus;
    private RecyclerView mList;
    private final Adapter mAdapter = new Adapter();
    private boolean mOpen;

    private TextView mCaption;

    PhoneVideoSearch(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
    }

    boolean isOpen() {
        return mOpen;
    }

    // region Opening and closing

    void open() {
        Video video = mHost.current();
        if (video == null || video.videoId == null) {
            return;
        }
        if (!video.videoId.equals(mVideoId)) {
            reset();
            mVideoId = video.videoId;
        }
        if (!PhoneTranscript.mayHave(video.videoId)) {
            android.widget.Toast.makeText(mActivity, R.string.phone_find_no_captions, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (mPanel == null) {
            createPanel();
        }
        mOpen = true;
        place();
        mPanel.setVisibility(View.VISIBLE);
        mPanel.setTranslationY(dp(60));
        mPanel.setAlpha(0f);
        mPanel.animate().translationY(0).alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        mField.setText(mQuery);
        mField.setSelection(mField.getText().length());
        if (TextUtils.isEmpty(mQuery)) {
            mField.requestFocus();
            mField.postDelayed(() -> keyboard(true), 120);
        }
        if (mLines == null) {
            mLoading = true;
            refresh();
            String videoId = mVideoId;
            PhoneTranscript.load(videoId, lines -> {
                if (!videoId.equals(mVideoId)) {
                    return;
                }
                mLoading = false;
                mLines = lines;
                search(mQuery);
            });
        } else {
            search(mQuery);
        }
    }

    /** Back: the panel closes */
    boolean onBack() {
        if (mOpen) {
            end();
            return true;
        }
        return false;
    }

    /** The panel closes and the ticks on the seek bar go (the search is kept for the next open) */
    void end() {
        mOpen = false;
        keyboard(false);
        if (mPanel != null) {
            mPanel.animate().cancel();
            mPanel.setVisibility(View.GONE);
        }
        mHost.setTicks(new float[0]);
    }

    /** Another video started: nothing of this one's search stays */
    void onVideo(Video video) {
        String id = video != null ? video.videoId : null;
        if (TextUtils.equals(id, mVideoId)) {
            return;
        }
        end();
        reset();
        mVideoId = id;
    }

    private void reset() {
        mLines = null;
        mQuery = "";
        mHits.clear();
        mIndex = -1;
        mPlaying = -1;
        mLoading = false;
    }

    /** From the player's ticker: the playing line lit in the transcript, and the panel kept in place */
    void tick() {
        if (!mOpen || mLines == null) {
            return;
        }
        int playing = lineAt(mHost.positionMs());
        if (playing != mPlaying) {
            mPlaying = playing;
            if (TextUtils.isEmpty(mQuery)) {
                mAdapter.notifyDataSetChanged();
            }
        }
    }

    private int lineAt(long ms) {
        if (mLines == null) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < mLines.size(); i++) {
            if (mLines.get(i).startMs <= ms + 300) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    // endregion

    // region Searching

    private void search(String query) {
        mQuery = query != null ? query.trim() : "";
        mHits.clear();
        mHits.addAll(PhoneTranscript.find(mLines, mQuery));
        mIndex = -1;
        long duration = mHost.durationMs();
        float[] ticks = new float[mHits.size()];
        if (duration > 0) {
            for (int i = 0; i < mHits.size(); i++) {
                ticks[i] = Math.min(1f, mLines.get(mHits.get(i)).startMs / (float) duration);
            }
        }
        mHost.setTicks(duration > 0 ? ticks : new float[0]);
        refresh();
        if (TextUtils.isEmpty(mQuery) && mList != null) {
            int playing = lineAt(mHost.positionMs());
            mPlaying = playing;
            if (playing > 0) {
                ((LinearLayoutManager) mList.getLayoutManager()).scrollToPositionWithOffset(playing, dp(80));
            }
        }
    }

    private void refresh() {
        if (mStatus == null) {
            return;
        }
        if (mLoading) {
            mStatus.setText(R.string.phone_find_loading);
        } else if (mLines == null) {
            mStatus.setText(R.string.phone_find_no_captions);
        } else if (TextUtils.isEmpty(mQuery)) {
            mStatus.setText(R.string.phone_find_transcript);
        } else if (mHits.isEmpty()) {
            mStatus.setText(R.string.phone_find_none);
        } else {
            mStatus.setText(mActivity.getResources().getQuantityString(R.plurals.phone_find_count, mHits.size(), mHits.size()));
        }
        mAdapter.notifyDataSetChanged();
    }

    /** A line chosen: the video plays from there */
    private void jumpToLine(int line, int hitIndex) {
        if (mLines == null || line < 0 || line >= mLines.size()) {
            return;
        }
        mIndex = hitIndex;
        mHost.seekTo(Math.max(0, mLines.get(line).startMs - 400)); // a breath before the words
        if (hitIndex >= 0) {
            end(); // back to the video; search again for another moment
            showCaption(line);
        } else {
            keyboard(false);
        }
    }

    /** The line's text with the words found lit yellow */
    private CharSequence lit(String text, boolean bold) {
        SpannableString span = new SpannableString(text);
        if (TextUtils.isEmpty(mQuery)) {
            return span;
        }
        String lower = text.toLowerCase(Locale.getDefault());
        String q = mQuery.toLowerCase(Locale.getDefault());
        int from = 0;
        int at;
        while ((at = lower.indexOf(q, from)) >= 0) {
            int end = at + q.length();
            span.setSpan(new BackgroundColorSpan(0x38FFD54A), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(YELLOW_TEXT), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(Typeface.BOLD), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return span;
    }

    // endregion

    // region The panel

    private void createPanel() {
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        mPanel = new LinearLayout(mActivity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(color(R.color.phone_bg));
        mPanel.setClickable(true);
        mPanel.setElevation(dp(8));

        // ⌄  [ 🔍 Search what's said in this video   ✕ ]
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(10), dp(10), dp(4));
        ImageView close = icon(R.drawable.ic_phone_expand_more, mActivity.getString(R.string.phone_close));
        close.setOnClickListener(v -> end());
        head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout field = new LinearLayout(mActivity);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(14), 0, dp(4), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(color(R.color.phone_surface_2));
        pill.setCornerRadius(dp(22));
        field.setBackground(pill);
        ImageView glass = new ImageView(mActivity);
        glass.setImageResource(R.drawable.ic_phone_search);
        glass.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text_2)));
        field.addView(glass, new LinearLayout.LayoutParams(dp(18), dp(18)));
        mField = new EditText(mActivity);
        mField.setHint(R.string.phone_find_hint);
        mField.setHintTextColor(color(R.color.phone_text_2));
        mField.setTextColor(color(R.color.phone_text));
        mField.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(15));
        mField.setBackground(null);
        mField.setSingleLine(true);
        mField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mField.setPadding(dp(8), 0, dp(4), 0);
        mField.addTextChangedListener(new TextWatcher() {
            private final Runnable mRun = () -> search(mField.getText().toString());

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mField.removeCallbacks(mRun);
                mField.postDelayed(mRun, 150); // a pause in typing
            }
        });
        mField.setOnEditorActionListener((v, actionId, event) -> {
            keyboard(false);
            return true;
        });
        field.addView(mField, new LinearLayout.LayoutParams(0, dp(44), 1f));
        ImageView clear = icon(R.drawable.ic_phone_close, mActivity.getString(R.string.phone_find_clear));
        clear.setOnClickListener(v -> {
            mField.setText("");
            mField.requestFocus();
            keyboard(true);
        });
        field.addView(clear, new LinearLayout.LayoutParams(dp(36), dp(36)));
        head.addView(field, new LinearLayout.LayoutParams(0, dp(44), 1f));
        mPanel.addView(head);

        mStatus = new TextView(mActivity);
        mStatus.setTextColor(color(R.color.phone_text_2));
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(13));
        mStatus.setPadding(dp(18), dp(6), dp(18), dp(10));
        mPanel.addView(mStatus);

        View divider = new View(mActivity);
        divider.setBackgroundColor(color(R.color.phone_divider));
        mPanel.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        mList = new RecyclerView(mActivity);
        mList.setLayoutManager(new LinearLayoutManager(mActivity));
        mList.setItemAnimator(null);
        mList.setAdapter(mAdapter);
        mList.setClipToPadding(false);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    keyboard(false); // reading the results: the keyboard steps aside
                }
            }
        });
        mPanel.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400), Gravity.TOP));
        mPanel.setVisibility(View.GONE);
    }

    /** Under the video, down to the bottom of the screen */
    private void place() {
        int top = videoBottom();
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        params.topMargin = top;
        params.height = content != null ? Math.max(dp(200), content.getHeight() - top) : ViewGroup.LayoutParams.MATCH_PARENT;
        mPanel.setLayoutParams(params);
        mList.setPadding(0, 0, 0, mHost.navInset() + dp(12));
    }

    private int videoBottom() {
        View box = mHost.videoBox();
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        if (box == null || content == null) {
            return 0;
        }
        int[] at = new int[2];
        box.getLocationInWindow(at);
        int[] contentAt = new int[2];
        content.getLocationInWindow(contentAt);
        return Math.max(0, at[1] - contentAt[1] + box.getHeight());
    }

    private final class Adapter extends RecyclerView.Adapter<Row> {
        private boolean results() {
            return !TextUtils.isEmpty(mQuery);
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull Row holder, int position) {
            if (results()) {
                int line = mHits.get(position);
                holder.bind(line, position, false);
            } else {
                holder.bind(position, -1, position == mPlaying);
            }
        }

        @Override
        public int getItemCount() {
            if (mLines == null) {
                return 0;
            }
            return results() ? mHits.size() : mLines.size();
        }
    }

    /** [ 4:12 ]  …the battery drops faster than I expected. */
    private final class Row extends RecyclerView.ViewHolder {
        private final TextView mTime;
        private final TextView mText;

        Row(Context context) {
            super(new LinearLayout(context));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            mTime = new TextView(context);
            mTime.setGravity(Gravity.CENTER);
            mTime.setTextColor(0xFF3EA6FF);
            mTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(13));
            mTime.setTypeface(PhoneUi.medium(context));
            mTime.setFontFeatureSettings("tnum");
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(color(R.color.phone_surface_2));
            chip.setCornerRadius(dp(6));
            mTime.setBackground(chip);
            row.addView(mTime, new LinearLayout.LayoutParams(dp(54), dp(26)));
            mText = new TextView(context);
            mText.setTextColor(color(R.color.phone_text));
            mText.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(14.5f));
            mText.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(12));
            row.addView(mText, textParams);
        }

        void bind(int line, int hitIndex, boolean playing) {
            PhoneTranscript.Line l = mLines.get(line);
            mTime.setText(PhonePlaybackActivity.formatTime(l.startMs));
            mText.setText(lit(l.text, true));
            mText.setTextColor(color(playing || hitIndex >= 0 ? R.color.phone_text : R.color.phone_text_2));
            if (playing) {
                itemView.setBackgroundColor(0x24FFD54A);
            } else {
                itemView.setBackgroundResource(R.drawable.phone_ripple);
            }
            itemView.setOnClickListener(v -> jumpToLine(line, hitIndex));
        }
    }

    // endregion

    // region The line on the video

    /** The line just jumped to, on the video for a few seconds, the words in yellow */
    private void showCaption(int line) {
        View box = mHost.videoBox();
        if (!(box instanceof ViewGroup) || mLines == null) {
            return;
        }
        if (mCaption == null) {
            mCaption = new TextView(mActivity);
            mCaption.setTextColor(0xFFFFFFFF);
            mCaption.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(14));
            mCaption.setGravity(Gravity.CENTER);
            mCaption.setMaxLines(3);
            mCaption.setEllipsize(TextUtils.TruncateAt.END);
            mCaption.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xC8080808);
            bg.setCornerRadius(dp(4));
            mCaption.setBackground(bg);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.setMargins(dp(16), 0, dp(16), dp(28));
            ((ViewGroup) box).addView(mCaption, params);
        }
        mCaption.setText(lit(mLines.get(line).text, true));
        mCaption.animate().cancel();
        mCaption.setAlpha(1f);
        mCaption.setVisibility(View.VISIBLE);
        mCaption.bringToFront();
        mCaption.animate().alpha(0f).setStartDelay(4000).setDuration(400)
                .withEndAction(() -> mCaption.setVisibility(View.GONE)).start();
    }

    // endregion

    private void keyboard(boolean show) {
        InputMethodManager ime = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime == null || mField == null) {
            return;
        }
        if (show) {
            ime.showSoftInput(mField, InputMethodManager.SHOW_IMPLICIT);
        } else {
            ime.hideSoftInputFromWindow(mField.getWindowToken(), 0);
            mField.clearFocus();
        }
    }

    private ImageView icon(int drawable, String description) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(11), dp(11), dp(11), dp(11));
        view.setBackgroundResource(R.drawable.phone_ripple);
        view.setContentDescription(description);
        return view;
    }

    private int color(int res) {
        return mActivity.getResources().getColor(res);
    }

    private static int dp(float value) {
        return PhoneSheetLayout.dp(value);
    }
}
