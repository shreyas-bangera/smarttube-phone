package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * Channel page laid out like the YouTube app: banner, picture, name, handle and counts, description and Subscribe,
 * then tabs (Videos, Shorts, Live, Playlists...). One tab at a time, as a vertical list (Shorts as a grid).
 */
public class PhoneChannelActivity extends PhoneListActivity implements ChannelView {
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ChannelPresenter mPresenter;
    private View mHeader;
    private LinearLayout mTabsView;
    private final List<VideoGroup> mTabs = new ArrayList<>();
    private int mSelectedTabId = Integer.MIN_VALUE;
    private boolean mUserPickedTab;
    private PhoneChannelInfo mInfo;
    private String mInfoChannelId;
    private Boolean mSubscribed;
    private String mMetadataSubscribers;
    private String mMetadataVideoId;
    private boolean mDescriptionExpanded;
    private Disposable mMetadataAction;
    private Disposable mSubscribeAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHeader = getLayoutInflater().inflate(R.layout.phone_channel_header, mList, false);
        mTabsView = mHeader.findViewById(R.id.phone_channel_tabs);
        ((com.liskovsoft.smartyoutubetv2.tv.phone.widget.RatioFrameLayout) mHeader.findViewById(R.id.phone_channel_banner_frame))
                .setHeightToWidth(1f / 4f);
        mHeader.findViewById(R.id.phone_channel_subscribe).setOnClickListener(v -> onSubscribeClicked());
        mHeader.findViewById(R.id.phone_channel_description).setOnClickListener(v -> {
            mDescriptionExpanded = !mDescriptionExpanded;
            ((TextView) v).setMaxLines(mDescriptionExpanded ? 100 : 2);
        });

        mAdapter.setMode(FeedAdapter.MODE_ROWS);
        mAdapter.setHeaderView(mHeader);

        // The channel name moves into the top bar once the header has scrolled away (like the YouTube app)
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateTopBarTitle();
            }
        });

        mPresenter = ChannelPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
        updateTopBarTitle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RxHelper.disposeActions(mMetadataAction, mSubscribeAction);
        mPresenter.onViewDestroyed();
    }

    @Override
    public void finishReally() {
        super.finishReally();
        mPresenter.onFinish();
    }

    @Override
    protected Video getChannel() {
        return mPresenter != null ? mPresenter.getChannel() : null;
    }

    // region Header

    @Override
    protected void updateHeader() {
        super.updateHeader();
        if (mHeader == null) {
            return;
        }
        Video channel = getChannel();
        String channelId = mPresenter != null ? mPresenter.getChannelId() : null;
        if (channelId == null && channel != null) {
            channelId = channel.channelId;
        }

        TextView name = mHeader.findViewById(R.id.phone_channel_name);
        String title = channel != null ? (!TextUtils.isEmpty(channel.getAuthor()) ? channel.getAuthor() : channel.getTitle()) : null;
        if (TextUtils.isEmpty(title)) {
            title = firstAuthor();
        }
        name.setText(!TextUtils.isEmpty(title) ? title : getString(R.string.phone_channel));
        mAdapter.setHiddenAuthor(title);

        ImageView avatar = mHeader.findViewById(R.id.phone_channel_avatar);
        String avatarUrl = mInfo != null ? mInfo.avatarUrl : null;
        if (avatarUrl == null && channel != null) {
            avatarUrl = FeedAdapter.isChannelItem(channel) ? channel.getCardImageUrl() : AvatarCache.find(this, channel);
        }
        if (avatarUrl != null && !avatarUrl.equals(avatar.getTag())) {
            avatar.setTag(avatarUrl);
            PhoneUi.loadAvatar(avatar, avatarUrl);
        }

        if (channelId != null && !channelId.equals(mInfoChannelId)) {
            mInfoChannelId = channelId;
            final String requested = channelId;
            PhoneChannelInfo.fetch(channelId, info -> {
                if (requested.equals(mInfoChannelId) && PhoneUi.isAlive(this)) {
                    mInfo = info;
                    updateHeader();
                }
            });
        }

        // Banner
        View bannerFrame = mHeader.findViewById(R.id.phone_channel_banner_frame);
        ImageView banner = mHeader.findViewById(R.id.phone_channel_banner);
        if (mInfo != null && !TextUtils.isEmpty(mInfo.bannerUrl)) {
            bannerFrame.setVisibility(View.VISIBLE);
            if (!mInfo.bannerUrl.equals(banner.getTag()) && PhoneUi.isAlive(this)) {
                banner.setTag(mInfo.bannerUrl);
                Glide.with(this).load(mInfo.bannerUrl).centerCrop().into(banner);
            }
        } else {
            bannerFrame.setVisibility(View.GONE);
        }

        // Handle, subscribers, videos
        TextView handle = mHeader.findViewById(R.id.phone_channel_handle);
        String handleText = mInfo != null ? mInfo.handle : null;
        handle.setText(handleText);
        handle.setVisibility(TextUtils.isEmpty(handleText) ? View.GONE : View.VISIBLE);

        TextView stats = mHeader.findViewById(R.id.phone_channel_stats);
        String subscribers = mInfo != null && mInfo.subscribers != null ? mInfo.subscribers : mMetadataSubscribers;
        String videos = mInfo != null ? mInfo.videos : null;
        String statsText = join(subscribers, videos);
        stats.setText(statsText);
        stats.setVisibility(TextUtils.isEmpty(statsText) ? View.GONE : View.VISIBLE);

        TextView description = mHeader.findViewById(R.id.phone_channel_description);
        String descriptionText = mInfo != null ? mInfo.description : null;
        description.setText(descriptionText);
        description.setVisibility(TextUtils.isEmpty(descriptionText) ? View.GONE : View.VISIBLE);

        renderSubscribe();
        updateTopBarTitle();
    }

    private void updateTopBarTitle() {
        View topTitle = findViewById(R.id.phone_screen_title);
        View topAvatar = findViewById(R.id.phone_avatar);
        if (topTitle == null || mList == null || mHeader == null) {
            return;
        }
        boolean headerGone;
        RecyclerView.LayoutManager manager = mList.getLayoutManager();
        View header = manager != null ? manager.findViewByPosition(0) : null;
        if (header != mHeader) {
            headerGone = mList.getChildCount() > 0; // the header is scrolled out (or not laid out yet)
        } else {
            View nameRow = (View) mHeader.findViewById(R.id.phone_channel_name).getParent().getParent();
            headerGone = mHeader.getTop() + nameRow.getBottom() < 0;
        }
        topTitle.setAlpha(headerGone ? 1f : 0f);
        if (topAvatar != null) {
            topAvatar.setAlpha(headerGone ? 1f : 0f);
        }
    }

    private String firstAuthor() {
        for (VideoGroup group : mTabs) {
            if (group.getVideos() != null) {
                for (Video video : group.getVideos()) {
                    if (video != null && !TextUtils.isEmpty(video.getAuthor())) {
                        return video.getAuthor();
                    }
                }
            }
        }
        return null;
    }

    private static String join(String a, String b) {
        if (TextUtils.isEmpty(a)) {
            return b;
        }
        if (TextUtils.isEmpty(b)) {
            return a;
        }
        return a + "  ·  " + b;
    }

    // endregion

    // region Subscribe

    private void loadSubscribeState() {
        if (mMetadataVideoId != null) {
            return;
        }
        Video first = null;
        for (VideoGroup group : mTabs) {
            if (group.getVideos() != null) {
                for (Video video : group.getVideos()) {
                    if (video != null && video.videoId != null) {
                        first = video;
                        break;
                    }
                }
            }
            if (first != null) {
                break;
            }
        }
        if (first == null) {
            return;
        }
        mMetadataVideoId = first.videoId;
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        mMetadataAction = RxHelper.execute(service.getMetadataObserve(first.videoId), metadata -> mMain.post(() -> {
            if (metadata == null || !PhoneUi.isAlive(this)) {
                return;
            }
            mSubscribed = metadata.isSubscribed();
            mMetadataSubscribers = metadata.getSubscriberCount();
            if (TextUtils.isEmpty(metadata.getAuthorImageUrl()) || mInfo != null) {
                updateHeader();
            } else {
                ImageView avatar = mHeader.findViewById(R.id.phone_channel_avatar);
                if (avatar.getTag() == null) {
                    avatar.setTag(metadata.getAuthorImageUrl());
                    PhoneUi.loadAvatar(avatar, metadata.getAuthorImageUrl());
                }
                updateHeader();
            }
        }), error -> { /* keep the default Subscribe button */ });
    }

    private String channelId() {
        String channelId = mPresenter != null ? mPresenter.getChannelId() : null;
        if (channelId == null && getChannel() != null) {
            channelId = getChannel().channelId;
        }
        return channelId;
    }

    /** Subscribed: the YouTube app's sheet (notifications All / None, Unsubscribe); otherwise subscribes */
    private void onSubscribeClicked() {
        String channelId = channelId();
        if (channelId == null) {
            return;
        }
        if (!Boolean.TRUE.equals(mSubscribed)) {
            toggleSubscribe();
            return;
        }
        PhoneBellSheet.show(this, channelId, this::renderSubscribe, this::toggleSubscribe);
    }


    private void toggleSubscribe() {
        String channelId = channelId();
        if (channelId == null) {
            return;
        }
        boolean subscribed = Boolean.TRUE.equals(mSubscribed);
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        RxHelper.disposeActions(mSubscribeAction);
        mSubscribeAction = RxHelper.execute(subscribed ? service.unsubscribeObserve(channelId) : service.subscribeObserve(channelId));
        mSubscribed = !subscribed;
        renderSubscribe();
        Toast.makeText(this, subscribed ? R.string.unsubscribed_from_channel : R.string.subscribed_to_channel, Toast.LENGTH_SHORT).show();
    }

    private void renderSubscribe() {
        TextView subscribe = mHeader.findViewById(R.id.phone_channel_subscribe);
        boolean subscribed = Boolean.TRUE.equals(mSubscribed);
        subscribe.setText(subscribed ? R.string.phone_subscribed : R.string.phone_subscribe);
        subscribe.setBackgroundResource(subscribed ? R.drawable.phone_pill_bg : R.drawable.phone_button_bg);
        subscribe.setTextColor(getResources().getColor(subscribed ? R.color.phone_text : R.color.phone_bg));
        // Subscribed: the bell (this channel's notifications) and a ▾ for the choices, as the YouTube app
        if (subscribed) {
            int bell = PhoneNewVideos.bell(this, channelId());
            int size = Math.round(18 * getResources().getDisplayMetrics().density);
            android.graphics.drawable.Drawable start = getResources().getDrawable(PhoneBellSheet.icon(bell)).mutate();
            android.graphics.drawable.Drawable end = getResources().getDrawable(R.drawable.ic_phone_expand_more).mutate();
            int tint = getResources().getColor(R.color.phone_text);
            start.setTint(tint);
            end.setTint(tint);
            start.setBounds(0, 0, size, size);
            end.setBounds(0, 0, size, size);
            subscribe.setCompoundDrawablesRelative(start, null, end, null);
            subscribe.setCompoundDrawablePadding(Math.round(8 * getResources().getDisplayMetrics().density));
            subscribe.setGravity(android.view.Gravity.CENTER);
        } else {
            subscribe.setCompoundDrawablesRelative(null, null, null, null);
        }
    }

    // endregion

    // region Tabs

    private VideoGroup findTab(int id) {
        for (VideoGroup tab : mTabs) {
            if (tab.getId() == id) {
                return tab;
            }
        }
        return null;
    }

    private static boolean isVideosTab(VideoGroup group) {
        String title = group.getTitle();
        return title != null && title.trim().equalsIgnoreCase("videos");
    }

    private void selectTab(VideoGroup group) {
        mSelectedTabId = group.getId();
        mAdapter.setMode(group.isShorts() ? FeedAdapter.MODE_SHORTS : FeedAdapter.MODE_ROWS);
        mAdapter.update(group);
        renderTabs();
    }

    private void renderTabs() {
        mTabsView.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (VideoGroup tab : mTabs) {
            boolean selected = tab.getId() == mSelectedTabId;

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setBackgroundResource(ripple.resourceId);
            box.setPadding((int) (14 * density), 0, (int) (14 * density), 0);

            TextView label = new TextView(this);
            label.setText(!TextUtils.isEmpty(tab.getTitle()) ? tab.getTitle() : getString(R.string.phone_channel));
            label.setTextSize(15);
            label.setTypeface(PhoneUi.regular(label.getContext()));
            label.setTextColor(getResources().getColor(selected ? R.color.phone_text : R.color.phone_text_2));
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            box.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f));

            View underline = new View(this);
            underline.setBackgroundColor(getResources().getColor(R.color.phone_text));
            underline.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            box.addView(underline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (2 * density)));

            box.setOnClickListener(v -> {
                if (tab.getId() != mSelectedTabId) {
                    mUserPickedTab = true;
                    selectTab(tab);
                }
            });
            mTabsView.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
    }

    // endregion

    // region ChannelView

    @Override
    public void update(VideoGroup videoGroup) {
        if (videoGroup == null) {
            return;
        }
        hideMessage();

        int action = videoGroup.getAction();
        if (action == VideoGroup.ACTION_REMOVE || action == VideoGroup.ACTION_REMOVE_AUTHOR || action == VideoGroup.ACTION_SYNC) {
            mAdapter.update(videoGroup);
            return;
        }

        VideoGroup existing = findTab(videoGroup.getId());
        if (existing == null) {
            if (videoGroup.getVideos() == null || videoGroup.getVideos().isEmpty()) {
                return;
            }
            mTabs.add(videoGroup);
            boolean nothingSelected = findTab(mSelectedTabId) == null;
            if (nothingSelected || (!mUserPickedTab && isVideosTab(videoGroup) && !isVideosTab(findTab(mSelectedTabId)))) {
                selectTab(videoGroup);
            } else {
                renderTabs();
            }
        } else {
            mTabs.set(mTabs.indexOf(existing), videoGroup);
            if (videoGroup.getId() == mSelectedTabId) {
                mAdapter.update(videoGroup); // more videos for the open tab
            }
        }

        loadSubscribeState();
        updateHeader();
    }

    @Override
    public void setPosition(int index) {
        // Tabs replace the rows: nothing to scroll to
    }

    @Override
    public void showProgressBar(boolean show) {
        showProgress(show);
    }

    @Override
    public void clear() {
        mTabs.clear();
        mSelectedTabId = Integer.MIN_VALUE;
        mUserPickedTab = false;
        mInfo = null;
        mInfoChannelId = null;
        mSubscribed = null;
        mMetadataSubscribers = null;
        mMetadataVideoId = null;
        RxHelper.disposeActions(mMetadataAction);
        if (mHeader != null) {
            mHeader.findViewById(R.id.phone_channel_avatar).setTag(null);
            mHeader.findViewById(R.id.phone_channel_banner).setTag(null);
        }
        mAdapter.setMode(FeedAdapter.MODE_ROWS);
        if (mTabsView != null) {
            renderTabs();
        }
        updateHeader();
    }

    // endregion

    // region Listener

    @Override
    public void onVideoClicked(Video video) {
        PhoneUi.prepareExpandLaunch(video);
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemClicked(video);
    }

    @Override
    public void onVideoLongClicked(Video video) {
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemLongClicked(video);
    }

    @Override
    public void onStripClicked(Video channel) {
        onVideoClicked(channel);
    }

    @Override
    public void onShelfScrollEnd(Video lastVideo) {
        if (!mPresenter.hasPendingActions()) {
            mPresenter.onScrollEnd(lastVideo);
        }
    }

    @Override
    protected boolean hasPendingActions() {
        return mPresenter.hasPendingActions();
    }

    @Override
    protected boolean wantsSkeleton() {
        return false; // the channel header is part of the list
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
        mPresenter.onScrollEnd(lastVideo);
    }

    // endregion
}
