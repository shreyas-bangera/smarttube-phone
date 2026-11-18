package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Bundle;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;

/**
 * A single list of videos: channel uploads, a playlist, a mix.
 */
public class PhoneChannelUploadsActivity extends PhoneListActivity implements ChannelUploadsView {
    private ChannelUploadsPresenter mPresenter;
    /** A playlist (not a channel's uploads): YouTube's playlist header on top, songs as rows */
    private PhonePlaylistHeader mHeader;
    /** Opened from the Music page: its songs play in the music player */
    private boolean mMusicPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMusicPage = PhoneMusic.isMusicTapRecent();
        mAdapter.setMode(FeedAdapter.MODE_FLAT);

        mPresenter = ChannelUploadsPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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

    // region ChannelUploadsView

    @Override
    public void update(VideoGroup videoGroup) {
        hideMessage();
        Video playlist = getChannel();
        if (mHeader == null && isPlaylist(playlist)) {
            mHeader = new PhonePlaylistHeader(this, new PhonePlaylistHeader.Actions() {
                @Override
                public void playAll() {
                    java.util.List<Video> videos = mAdapter.allVideos();
                    if (!videos.isEmpty()) {
                        onVideoClicked(videos.get(0));
                    }
                }

                @Override
                public void shuffle() {
                    java.util.List<Video> videos = mAdapter.allVideos();
                    if (!videos.isEmpty()) {
                        onVideoClicked(videos.get(new java.util.Random().nextInt(videos.size())));
                    }
                }
            });
            mAdapter.setMode(FeedAdapter.MODE_ROWS);
            mAdapter.setMusicBadges(mMusicPage);
            mAdapter.setHeaderView(mHeader.view());
        }
        mAdapter.update(videoGroup);
        if (mHeader != null) {
            mHeader.bind(playlist, mAdapter.allVideos().size());
        }
        updateHeader();
        checkScrollEndLater();
    }

    @Override
    public void showProgressBar(boolean show) {
        showProgress(show);
    }

    @Override
    public void clear() {
        mAdapter.setMode(mHeader != null ? FeedAdapter.MODE_ROWS : FeedAdapter.MODE_FLAT);
    }

    private static boolean isPlaylist(Video video) {
        return video != null && !FeedAdapter.isChannelItem(video) && !TextUtils.isEmpty(video.playlistId) && !video.hasVideo();
    }

    // endregion

    // region Listener

    @Override
    public void onVideoClicked(Video video) {
        if (mMusicPage) {
            Video playlist = getChannel();
            PhoneMusic.markMusic(playlist != null ? playlist.getTitle() : null); // plays in the music player
        }
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
    protected void onListScrollEnd(Video lastVideo) {
        mPresenter.onScrollEnd(lastVideo);
    }

    // endregion
}
