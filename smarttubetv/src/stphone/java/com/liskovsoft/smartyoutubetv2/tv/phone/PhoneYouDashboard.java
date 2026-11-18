package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * The You tab's page, laid out like the YouTube app's: "History ›" with the latest videos as a row of cards, then
 * "Library": Liked videos, Watch later and the other playlists, Your videos and Downloads, each with its picture.
 * Shown as the header of the (empty) feed list; a place opens in the same tab and Back comes back here.
 */
final class PhoneYouDashboard {
    private static final int SHELF_SIZE = 12;
    private static final String WATCH_LATER = "WL";
    private static final String LIKED = "LL";

    interface Callback {
        /** A SmartTube section of the You tab (MediaGroup.TYPE_...); false when it's switched off */
        boolean hasSection(int sectionId);

        String sectionTitle(int sectionId);

        void openSection(int sectionId);

        void openDownloads();

        boolean isSignedIn();

        FeedAdapter.Listener listener();
    }

    private final PhoneBrowseActivity mActivity;
    private final Callback mCallback;
    private final LinearLayout mView;
    private final List<Video> mHistory = new ArrayList<>();
    private final List<Video> mPlaylists = new ArrayList<>();
    private final ShelfAdapter mHistoryAdapter;
    private final View mHistoryShelf;
    private final LinearLayout mLibrary;
    private Disposable mHistoryAction;
    private Disposable mPlaylistsAction;
    private final float mDensity;

    PhoneYouDashboard(PhoneBrowseActivity activity, Callback callback) {
        mActivity = activity;
        mCallback = callback;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mView = new LinearLayout(activity);
        mView.setOrientation(LinearLayout.VERTICAL);
        mView.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mView.setPadding(0, 0, 0, dp(16));

        mHistoryAdapter = new ShelfAdapter(mHistory, ShelfAdapter.STYLE_COMPACT, callback.listener(), null);
        mHistoryShelf = historyShelf();
        mView.addView(mHistoryShelf);

        mView.addView(heading(activity.getString(R.string.phone_you_library), false, null));
        mLibrary = new LinearLayout(activity);
        mLibrary.setOrientation(LinearLayout.VERTICAL);
        mView.addView(mLibrary);
    }

    View getView() {
        return mView;
    }

    /** The tab is shown: the Library for what exists now, then fresh History and Playlists */
    void show() {
        boolean signedIn = mCallback.isSignedIn();
        mHistoryShelf.setVisibility(signedIn && !mHistory.isEmpty() ? View.VISIBLE : View.GONE);
        buildLibrary();
        RxHelper.disposeActions(mHistoryAction, mPlaylistsAction);
        if (!signedIn) {
            return; // History and the playlists belong to an account
        }
        if (mCallback.hasSection(MediaGroup.TYPE_HISTORY)) {
            mHistoryAction = RxHelper.execute(YouTubeServiceManager.instance().getContentService().getHistoryObserve(),
                    group -> {
                        copy(group, mHistory, SHELF_SIZE);
                        mHistoryAdapter.onDataChanged(0);
                        mHistoryShelf.setVisibility(mHistory.isEmpty() ? View.GONE : View.VISIBLE);
                    }, error -> { });
        }
        mPlaylistsAction = RxHelper.execute(YouTubeServiceManager.instance().getContentService().getPlaylistsObserve(),
                group -> {
                    copy(group, mPlaylists, 50);
                    buildLibrary();
                }, error -> { });
    }

    void release() {
        RxHelper.disposeActions(mHistoryAction, mPlaylistsAction);
    }

    private static void copy(MediaGroup group, List<Video> target, int max) {
        List<MediaItem> items = group != null ? group.getMediaItems() : null;
        target.clear();
        if (items != null) {
            for (MediaItem item : items) {
                if (item != null && target.size() < max) {
                    target.add(Video.from(item));
                }
            }
        }
    }

    // region History

    private View historyShelf() {
        LinearLayout shelf = new LinearLayout(mActivity);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.addView(heading(mActivity.getString(R.string.phone_you_history), true,
                () -> mCallback.openSection(MediaGroup.TYPE_HISTORY)));
        RecyclerView cards = new RecyclerView(mActivity);
        cards.setLayoutManager(new LinearLayoutManager(mActivity, LinearLayoutManager.HORIZONTAL, false));
        cards.setAdapter(mHistoryAdapter);
        cards.setClipToPadding(false);
        cards.setPadding(dp(8), 0, dp(8), 0);
        cards.setNestedScrollingEnabled(false);
        cards.setItemAnimator(null);
        shelf.addView(cards, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shelf.setVisibility(View.GONE);
        return shelf;
    }

    /** A section title; with a chevron it opens the section */
    private View heading(String text, boolean opens, Runnable onClick) {
        LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(20), dp(16), dp(12));
        TextView title = new TextView(mActivity);
        title.setText(text);
        title.setTextColor(color(R.color.phone_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(PhoneUi.medium(title.getContext()));
        header.addView(title);
        if (opens) {
            ImageView chevron = new ImageView(mActivity);
            chevron.setImageResource(R.drawable.ic_phone_chevron);
            chevron.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(24), dp(24));
            params.setMarginStart(dp(4));
            header.addView(chevron, params);
            header.setBackgroundResource(R.drawable.phone_ripple);
            header.setOnClickListener(v -> onClick.run());
        }
        return header;
    }

    // endregion

    // region Library

    private void buildLibrary() {
        mLibrary.removeAllViews();
        if (mCallback.isSignedIn()) {
            Video liked = null;
            Video later = null;
            List<Video> others = new ArrayList<>();
            for (Video playlist : mPlaylists) {
                if (isPlaylist(playlist, LIKED)) {
                    liked = playlist;
                } else if (isPlaylist(playlist, WATCH_LATER)) {
                    later = playlist;
                } else {
                    others.add(playlist);
                }
            }
            addPlaylistRow(liked, LIKED, R.string.phone_you_liked, R.drawable.ic_phone_like);
            if (mCallback.hasSection(MediaGroup.TYPE_MY_VIDEOS)) {
                addRow(mCallback.sectionTitle(MediaGroup.TYPE_MY_VIDEOS), null, null, R.drawable.ic_phone_play,
                        () -> mCallback.openSection(MediaGroup.TYPE_MY_VIDEOS));
            }
            addPlaylistRow(later, WATCH_LATER, R.string.phone_you_watch_later, R.drawable.ic_phone_history);
            for (Video playlist : others) {
                addRow(playlist.getTitle(), subtitle(playlist, null), playlist, R.drawable.ic_phone_library,
                        () -> mCallback.listener().onVideoClicked(playlist));
            }
        }
        addRow(mActivity.getString(R.string.phone_downloads), null, null, R.drawable.ic_phone_download, mCallback::openDownloads);
    }

    private static boolean isPlaylist(Video video, String id) {
        String playlistId = video != null ? video.playlistId : null;
        return playlistId != null && (playlistId.equals(id) || playlistId.equals("VL" + id));
    }

    /** Liked videos and Watch later: from the account's playlists, else opened by their fixed ids */
    private void addPlaylistRow(Video playlist, String id, int titleRes, int icon) {
        String title = playlist != null && !TextUtils.isEmpty(playlist.getTitle()) ? playlist.getTitle() : mActivity.getString(titleRes);
        addRow(title, mActivity.getString(R.string.phone_you_private), playlist, icon, () -> {
            if (playlist != null) {
                mCallback.listener().onVideoClicked(playlist);
            } else {
                Video fallback = new Video();
                fallback.playlistId = id;
                fallback.title = title;
                com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(mActivity).openChannel(fallback);
            }
        });
    }

    private String subtitle(Video playlist, String fallback) {
        CharSequence second = playlist != null ? playlist.getSecondTitle() : null;
        return !TextUtils.isEmpty(second) ? second.toString() : fallback;
    }

    /** Picture (16:9, rounded, a small icon in its corner) on the left, title and a line under it on the right */
    private void addRow(String title, String subtitle, Video picture, int icon, Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        row.setBackgroundResource(R.drawable.phone_ripple);
        row.setOnClickListener(v -> onClick.run());

        FrameLayout frame = new FrameLayout(mActivity);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color(R.color.phone_surface_2));
        frame.setBackground(shape);
        frame.setClipToOutline(true);
        if (picture != null) {
            ImageView thumb = new ImageView(mActivity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            PhoneUi.loadThumb(thumb, picture, false);
            ImageView badge = new ImageView(mActivity);
            badge.setImageResource(icon);
            badge.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            badge.setPadding(dp(5), dp(5), dp(5), dp(5));
            GradientDrawable badgeShape = new GradientDrawable();
            badgeShape.setCornerRadius(dp(6));
            badgeShape.setColor(0x99000000);
            badge.setBackground(badgeShape);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM | Gravity.END);
            badgeParams.setMargins(0, 0, dp(6), dp(6));
            frame.addView(badge, badgeParams);
        } else {
            ImageView symbol = new ImageView(mActivity);
            symbol.setImageResource(icon);
            symbol.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
            frame.addView(symbol, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        }
        row.addView(frame, new LinearLayout.LayoutParams(dp(150), dp(84)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(mActivity);
        name.setText(title);
        name.setTextColor(color(R.color.phone_text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView line = new TextView(mActivity);
            line.setText(subtitle);
            line.setTextColor(color(R.color.phone_text_2));
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            line.setMaxLines(1);
            line.setPadding(0, dp(4), 0, 0);
            texts.addView(line);
        }
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(16));
        row.addView(texts, textParams);

        mLibrary.addView(row);
    }

    // endregion

    private int color(int res) {
        return mActivity.getResources().getColor(res);
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }
}
