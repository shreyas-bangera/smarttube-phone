package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Outline;
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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A playlist (or a mix) playing, as the YouTube app shows it: a bar at the bottom with what plays next, which opens
 * the playlist under the video: its name, repeat and shuffle, and its videos with the one playing highlighted.
 */
final class PhonePlaylistPanel {
    interface Host {
        Video current();

        void play(Video video);

        /** The list's end is near: its next videos */
        void loadMore(Video last);

        /** The bar (and the panel) may show: a regular video in the portrait player, on screen */
        boolean allowed();

        View videoBox();

        int navInset();

        /** Repeat the playlist / shuffle it: on or off now, and switching */
        boolean isRepeat();

        boolean isShuffle();

        void setRepeat(boolean on);

        void setShuffle(boolean on);
    }

    private final Activity mActivity;
    private final Host mHost;
    private final List<Video> mVideos = new ArrayList<>();
    private String mPlaylistId;
    private String mTitle;

    private LinearLayout mBar;
    private TextView mBarTop;
    private TextView mBarBottom;
    private ImageView mBarIcon;

    private LinearLayout mPanel;
    private TextView mPanelTitle;
    private TextView mPanelSubtitle;
    private ImageView mRepeat;
    private ImageView mShuffle;
    private PhonePlaylistSaver mSaver;
    private RecyclerView mList;
    private final Adapter mAdapter = new Adapter();
    private boolean mOpen;

    PhonePlaylistPanel(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
    }

    // region The playlist

    /** A group of the player's suggestions that is the playing video's playlist: kept here, not among the suggestions */
    static boolean isPlaylistOf(VideoGroup group, Video playing) {
        if (group == null || playing == null || playing.getPlaylistId() == null || group.isEmpty()) {
            return false;
        }
        String id = playing.getPlaylistId();
        int same = 0;
        for (Video video : group.getVideos()) {
            if (id.equals(video.getPlaylistId())) {
                same++;
            }
        }
        return same >= Math.max(1, group.getVideos().size() / 2);
    }

    void setGroup(VideoGroup group, Video playing) {
        String id = playing.getPlaylistId();
        if (!TextUtils.equals(id, mPlaylistId) || group.getAction() == VideoGroup.ACTION_REPLACE) {
            mVideos.clear();
            mPlaylistId = id;
        }
        if (!TextUtils.isEmpty(group.getTitle())) {
            mTitle = group.getTitle();
        }
        for (Video video : group.getVideos()) {
            if (video.videoId != null && indexOf(video.videoId) < 0) {
                mVideos.add(video);
            }
        }
        refresh();
    }

    /** Another video started: a video of another playlist (or none) clears it */
    void onVideo(Video playing) {
        if (playing == null || playing.getPlaylistId() == null || !playing.getPlaylistId().equals(mPlaylistId)) {
            clear();
            return;
        }
        refresh();
        if (mOpen) {
            scrollToCurrent();
        }
    }

    void clear() {
        mVideos.clear();
        mPlaylistId = null;
        mTitle = null;
        close(false);
        refresh();
    }

    boolean hasPlaylist() {
        return !mVideos.isEmpty();
    }

    private int indexOf(String videoId) {
        for (int i = 0; i < mVideos.size(); i++) {
            if (videoId.equals(mVideos.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    private int currentIndex() {
        Video current = mHost.current();
        return current != null && current.videoId != null ? indexOf(current.videoId) : -1;
    }

    private boolean isMix() {
        return mPlaylistId != null && mPlaylistId.startsWith("RD");
    }

    private String title() {
        if (!TextUtils.isEmpty(mTitle)) {
            return mTitle;
        }
        return mActivity.getString(isMix() ? R.string.phone_playlist_mix : R.string.phone_playlist);
    }

    // endregion

    // region The bar

    /** Room the bar takes at the bottom (the details list keeps clear of it), 0 when it isn't there */
    int barRoom() {
        return mBar != null && mBar.getVisibility() == View.VISIBLE ? dp(64) + dp(10) : 0;
    }

    boolean isOpen() {
        return mOpen;
    }

    /** From the player's ticker: the bar shows when it may */
    void tick() {
        boolean allowed = mHost.allowed();
        if (!allowed && mOpen) {
            close(false);
        }
        boolean show = allowed && hasPlaylist() && !mOpen;
        if (show && mBar == null) {
            createBar();
        }
        if (mBar == null) {
            return;
        }
        if (show != (mBar.getVisibility() == View.VISIBLE)) {
            mBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (show) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mBar.getLayoutParams();
            int bottom = mHost.navInset() + dp(10);
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom;
                mBar.setLayoutParams(params);
            }
        }
    }

    private void createBar() {
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        mBar = new LinearLayout(mActivity);
        mBar.setGravity(Gravity.CENTER_VERTICAL);
        mBar.setPadding(dp(16), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.phone_surface_2));
        bg.setCornerRadius(dp(16));
        mBar.setBackground(bg);
        mBar.setElevation(dp(6));
        mBar.setOnClickListener(v -> open());

        mBarIcon = new ImageView(mActivity);
        mBarIcon.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        mBar.addView(mBarIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        mBarTop = text(15, R.color.phone_text, true);
        mBarBottom = text(13, R.color.phone_text_2, false);
        texts.addView(mBarTop);
        texts.addView(mBarBottom);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMarginStart(dp(16));
        textsParams.setMarginEnd(dp(8));
        mBar.addView(texts, textsParams);

        ImageView up = new ImageView(mActivity);
        up.setImageResource(R.drawable.ic_phone_chevron);
        up.setRotation(-90);
        up.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        mBar.addView(up, new LinearLayout.LayoutParams(dp(28), dp(28)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64),
                Gravity.BOTTOM);
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        content.addView(mBar, params);
        mBar.setVisibility(View.GONE);
        bindBar();
    }

    private void bindBar() {
        if (mBar == null) {
            return;
        }
        mBarIcon.setImageResource(isMix() ? R.drawable.ic_phone_mix : R.drawable.ic_phone_playlist);
        int index = currentIndex();
        Video next = index >= 0 && index + 1 < mVideos.size() ? mVideos.get(index + 1) : null;
        if (next != null) {
            mBarTop.setText(mActivity.getString(R.string.phone_playlist_next, next.getTitle()));
            mBarBottom.setText(title());
        } else {
            mBarTop.setText(title());
            mBarBottom.setText(position(index));
        }
    }

    /** "3 / 50" */
    private String position(int index) {
        return index >= 0 ? (index + 1) + " / " + mVideos.size() : String.valueOf(mVideos.size());
    }

    // endregion

    // region The panel

    void open() {
        if (mOpen || !hasPlaylist()) {
            return;
        }
        if (mPanel == null) {
            createPanel();
        }
        mOpen = true;
        if (mBar != null) {
            mBar.setVisibility(View.GONE);
        }
        place();
        bindPanel();
        mPanel.setVisibility(View.VISIBLE);
        mPanel.setTranslationY(mPanel.getLayoutParams().height > 0 ? mPanel.getLayoutParams().height : dp(400));
        mPanel.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        scrollToCurrent();
    }

    void close(boolean animate) {
        if (!mOpen || mPanel == null) {
            mOpen = false;
            return;
        }
        mOpen = false;
        mPanel.animate().cancel();
        if (animate) {
            mPanel.animate().translationY(mPanel.getHeight()).setDuration(180)
                    .withEndAction(() -> mPanel.setVisibility(View.GONE)).start();
        } else {
            mPanel.setVisibility(View.GONE);
        }
        tick();
    }

    /** Under the video, down to the bottom of the screen */
    private void place() {
        View box = mHost.videoBox();
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        int top = 0;
        if (box != null && content != null) {
            int[] at = new int[2];
            box.getLocationInWindow(at);
            int[] contentAt = new int[2];
            content.getLocationInWindow(contentAt);
            top = Math.max(0, at[1] - contentAt[1] + box.getHeight());
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        int height = content != null ? content.getHeight() - top : ViewGroup.LayoutParams.MATCH_PARENT;
        if (params.topMargin != top || params.height != height) {
            params.topMargin = top;
            params.height = height;
            mPanel.setLayoutParams(params);
        }
        mPanel.setPadding(0, 0, 0, mHost.navInset());
    }

    private void createPanel() {
        ViewGroup content = mActivity.findViewById(android.R.id.content);
        mPanel = new LinearLayout(mActivity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(color(R.color.phone_bg));
        mPanel.setClickable(true); // touches stay on the panel
        mPanel.setElevation(dp(8));

        View handle = new View(mActivity);
        handle.setBackgroundResource(R.drawable.phone_handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(8);
        mPanel.addView(handle, handleParams);

        // Name, what it is (or where in it), X
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(10), dp(8), 0);
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        mPanelTitle = text(22, R.color.phone_text, false);
        mPanelSubtitle = text(14, R.color.phone_text_2, false);
        texts.addView(mPanelTitle);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(2);
        texts.addView(mPanelSubtitle, subtitleParams);
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = icon(R.drawable.ic_phone_close, mActivity.getString(R.string.phone_close));
        close.setOnClickListener(v -> close(true));
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mPanel.addView(head);

        // Repeat, shuffle
        LinearLayout controls = new LinearLayout(mActivity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), dp(4), dp(8), dp(4));
        mRepeat = icon(R.drawable.ic_phone_mode_repeat, mActivity.getString(R.string.phone_mode_repeat_list));
        mRepeat.setOnClickListener(v -> {
            mHost.setRepeat(!mHost.isRepeat());
            bindControls();
        });
        controls.addView(mRepeat, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mShuffle = icon(R.drawable.ic_phone_mode_shuffle, mActivity.getString(R.string.phone_mode_shuffle));
        mShuffle.setOnClickListener(v -> {
            mHost.setShuffle(!mHost.isShuffle());
            bindControls();
        });
        controls.addView(mShuffle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        controls.addView(new View(mActivity), new LinearLayout.LayoutParams(0, 1, 1f));
        // Save to library (the page of a playlist also unsaves; here the owner isn't known, so it only saves)
        ImageView save = icon(R.drawable.ic_phone_library_add, mActivity.getString(R.string.phone_playlist_save));
        save.setVisibility(View.GONE);
        mSaver = new PhonePlaylistSaver(save, false);
        controls.addView(save, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mPanel.addView(controls);

        mList = new RecyclerView(mActivity);
        mList.setLayoutManager(new LinearLayoutManager(mActivity));
        mList.setItemAnimator(null);
        mList.setAdapter(mAdapter);
        mList.setClipToPadding(false);
        mList.setPadding(0, 0, 0, dp(8));
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private Video mAsked;

            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
                if (manager != null && !mVideos.isEmpty() && manager.findLastVisibleItemPosition() >= mVideos.size() - 4) {
                    Video last = mVideos.get(mVideos.size() - 1);
                    if (last != mAsked) {
                        mAsked = last;
                        mHost.loadMore(last);
                    }
                }
            }
        });
        mPanel.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400), Gravity.TOP));
        mPanel.setVisibility(View.GONE);
    }

    private void bindPanel() {
        if (mPanel == null) {
            return;
        }
        mPanelTitle.setText(title());
        mPanelSubtitle.setText(isMix() ? mActivity.getString(R.string.phone_playlist_mix_about) : position(currentIndex()));
        mSaver.bind(mActivity, mPlaylistId, title(), null, null);
        bindControls();
        mAdapter.notifyDataSetChanged();
    }

    private void bindControls() {
        tint(mRepeat, mHost.isRepeat());
        tint(mShuffle, mHost.isShuffle());
    }

    /** On: white on a light circle, as the YouTube app; off: grey */
    private void tint(ImageView view, boolean on) {
        if (view == null) {
            return;
        }
        view.setImageTintList(ColorStateList.valueOf(color(on ? R.color.phone_text : R.color.phone_text_2)));
        if (on) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(color(R.color.phone_surface_2));
            view.setBackground(circle);
        } else {
            view.setBackgroundResource(R.drawable.phone_ripple);
        }
    }

    private void scrollToCurrent() {
        int index = currentIndex();
        if (mList != null && index >= 0) {
            ((LinearLayoutManager) mList.getLayoutManager()).scrollToPositionWithOffset(index, dp(8));
        }
    }

    private void refresh() {
        bindBar();
        if (mOpen) {
            bindPanel();
        }
        tick();
    }

    private final class Adapter extends RecyclerView.Adapter<Row> {
        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull Row holder, int position) {
            holder.bind(mVideos.get(position), position == currentIndex());
        }

        @Override
        public int getItemCount() {
            return mVideos.size();
        }
    }

    /** A video in the playlist: picture (with its length), title, channel, views and date */
    private final class Row extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mLength;
        private final TextView mTitle;
        private final TextView mAuthor;
        private final TextView mMeta;

        Row(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(dp(16), dp(8), dp(8), dp(8));

            FrameLayout frame = new FrameLayout(mActivity);
            int radius = dp(10);
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            frame.setClipToOutline(true);
            mThumb = new ImageView(mActivity);
            mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mThumb.setBackgroundColor(color(R.color.phone_surface_2));
            frame.addView(mThumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mLength = new TextView(mActivity);
            mLength.setTextColor(0xFFFFFFFF);
            mLength.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(11.5f));
            mLength.setBackgroundResource(R.drawable.phone_badge_bg);
            mLength.setPadding(dp(4), dp(1), dp(4), dp(1));
            FrameLayout.LayoutParams lengthParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lengthParams.setMargins(dp(4), dp(4), dp(4), dp(4));
            frame.addView(mLength, lengthParams);
            row.addView(frame, new LinearLayout.LayoutParams(dp(160), dp(90)));

            LinearLayout texts = new LinearLayout(mActivity);
            texts.setOrientation(LinearLayout.VERTICAL);
            mTitle = text(15, R.color.phone_text, false);
            mTitle.setSingleLine(false);
            mTitle.setMaxLines(2);
            mAuthor = text(13, R.color.phone_text_2, false);
            mMeta = text(13, R.color.phone_text_2, false);
            texts.addView(mTitle);
            LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            authorParams.topMargin = dp(4);
            texts.addView(mAuthor, authorParams);
            texts.addView(mMeta);
            LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textsParams.setMarginStart(dp(14));
            textsParams.setMarginEnd(dp(8));
            row.addView(texts, textsParams);
        }

        void bind(Video video, boolean playing) {
            itemView.setBackgroundColor(playing ? color(R.color.phone_surface) : 0);
            if (!playing) {
                itemView.setBackgroundResource(R.drawable.phone_ripple);
            }
            itemView.setOnClickListener(v -> {
                if (!playing) {
                    mHost.play(video);
                }
            });
            mTitle.setText(video.getTitle());
            mAuthor.setText(video.getAuthor());
            mAuthor.setVisibility(TextUtils.isEmpty(video.getAuthor()) ? View.GONE : View.VISIBLE);
            CharSequence meta = PhoneUi.meta(video);
            String metaText = meta != null ? meta.toString() : "";
            String author = video.getAuthor();
            if (!TextUtils.isEmpty(author) && metaText.startsWith(author)) {
                metaText = metaText.substring(author.length()).replaceFirst("^\\s*·\\s*", "");
            }
            mMeta.setText(metaText);
            mMeta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);
            mLength.setText(video.badge);
            mLength.setVisibility(TextUtils.isEmpty(video.badge) ? View.GONE : View.VISIBLE);
            PhoneUi.loadThumb(mThumb, video, false);
        }
    }

    // endregion

    private TextView text(float sp, int color, boolean medium) {
        TextView view = new TextView(mActivity);
        view.setTextColor(color(color));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(sp));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (medium) {
            view.setTypeface(PhoneUi.medium(mActivity));
        }
        return view;
    }

    private ImageView icon(int drawable, String description) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
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
