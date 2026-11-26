package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Parcelable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.phone.widget.RatioFrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns SmartTube's VideoGroup updates (the same ones the TV grids and rows get) into a phone feed.
 * <ul>
 *     <li>MODE_FLAT: every video is a full width card (grids, search, uploads)</li>
 *     <li>MODE_SHELVES: every group is a titled horizontal shelf (home rows, channel page)</li>
 *     <li>MODE_SHORTS: two column vertical tiles</li>
 *     <li>MODE_MULTI: channel avatars strip on top, the selected channel's videos below</li>
 *     <li>MODE_SETTINGS: plain settings rows</li>
 * </ul>
 */
public class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int MODE_FLAT = 0;
    public static final int MODE_SHELVES = 1;
    public static final int MODE_SHORTS = 2;
    public static final int MODE_MULTI = 3;
    public static final int MODE_SETTINGS = 4;
    /** Rows flattened into one continuous feed, Shorts rows shown as a 2x2 block (phone home feed). */
    public static final int MODE_FEED = 5;
    /** Compact rows, thumbnail left and text right (channel Videos tab, like the YouTube app). */
    public static final int MODE_ROWS = 6;
    /** The Music section, laid out like YouTube's Music page (see PhoneMusicFeed) */
    public static final int MODE_MUSIC = 7;

    private static final int SHORTS_BLOCK_SIZE = 4;

    private static final int VT_VIDEO = 1;
    private static final int VT_SHORT = 2;
    private static final int VT_CHANNEL = 3;
    private static final int VT_SHELF = 4;
    private static final int VT_STRIP = 5;
    private static final int VT_SETTING = 6;
    private static final int VT_SECTION_HEADER = 7;
    private static final int VT_CUSTOM_HEADER = 8;
    private static final int VT_ROW = 9;
    private static final int VT_SETTING_HEADER = 10;

    private static final Object HEADER_ITEM = new Object();
    private View mHeaderView;
    /** Each header view has a view type of its own: a recycled holder never shows another screen's header */
    private int mHeaderViewType = VT_CUSTOM_HEADER;

    public interface Listener {
        void onVideoClicked(Video video);

        void onVideoLongClicked(Video video);

        void onStripClicked(Video channel);

        void onShelfScrollEnd(Video lastVideo);
    }

    static final class Group {
        VideoGroup ref;
        final List<Video> videos = new ArrayList<>();
        final Shelf shelf = new Shelf(this);
        final Strip strip = new Strip(this);
        final ShortsHeader shortsHeader = new ShortsHeader(this);
        final List<ShortTile> tiles = new ArrayList<>();
        ShelfAdapter innerAdapter;
        Parcelable innerState;

        Group(VideoGroup ref) {
            this.ref = ref;
        }

        ShortTile tile(int index) {
            return tile(videos.get(index));
        }

        ShortTile tile(Video video) {
            for (ShortTile tile : tiles) {
                if (tile.video == video) {
                    return tile;
                }
            }
            ShortTile tile = new ShortTile(video);
            tiles.add(tile);
            return tile;
        }
    }

    static final class ShortsHeader {
        final Group group;

        ShortsHeader(Group group) {
            this.group = group;
        }
    }

    static final class ShortTile {
        final Video video;

        ShortTile(Video video) {
            this.video = video;
        }
    }

    static final class Shelf {
        final Group group;

        Shelf(Group group) {
            this.group = group;
        }
    }

    static final class Strip {
        final Group group;

        Strip(Group group) {
            this.group = group;
        }
    }

    private final Listener mListener;
    private final List<Group> mGroups = new ArrayList<>();
    private final List<Object> mItems = new ArrayList<>();
    private final List<SettingsItem> mSettings = new ArrayList<>();
    private final RecyclerView.RecycledViewPool mInnerPool = new RecyclerView.RecycledViewPool();
    private int mMode = MODE_FLAT;
    private final PhoneMusicFeed mMusic = new PhoneMusicFeed();
    /** A music playlist: a note on each duration badge, like YouTube */
    private boolean mMusicBadges;

    public void setMusicBadges(boolean music) {
        mMusicBadges = music;
    }
    private Video mSelectedStripItem;

    public FeedAdapter(Listener listener) {
        mListener = listener;
        setHasStableIds(true);
    }

    // region Public API

    public void setMode(int mode) {
        mMode = mode;
        clear();
    }

    public int getMode() {
        return mMode;
    }

    public void clear() {
        mMusic.clear();
        mGroups.clear();
        mSettings.clear();
        mSelectedStripItem = null;
        rebuild();
    }

    public boolean isEmpty() {
        return mItems.isEmpty() || (mItems.size() == 1 && mItems.get(0) == HEADER_ITEM);
    }

    /**
     * A fixed view shown above the feed (e.g. the video details on the player screen).
     */
    public void setHeaderView(View header) {
        mHeaderView = header;
        mHeaderViewType = header == null ? VT_CUSTOM_HEADER : 1000 + (System.identityHashCode(header) & 0x7fff);
        rebuild();
    }

    public void setSettings(List<SettingsItem> items) {
        mMode = MODE_SETTINGS;
        mGroups.clear();
        mSettings.clear();
        if (items != null) {
            mSettings.addAll(items);
        }
        rebuild();
    }

    public void setSelectedStripItem(Video video) {
        mSelectedStripItem = video;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i) instanceof Strip) {
                Strip strip = (Strip) mItems.get(i);
                if (strip.group.innerAdapter != null) {
                    strip.group.innerAdapter.notifyDataSetChanged();
                }
            }
        }
    }

    private String mExcludedVideoId;
    private String mHiddenAuthor;

    /** Which videos of a flat feed are listed (Subscriptions filter chips) */
    public interface VideoFilter {
        boolean accept(Video video);
    }

    private VideoFilter mFilter;
    /** Search: the Shorts among the videos show as two column tiles, in pairs, as in the YouTube app */
    private boolean mShortsInPairs;

    /** What the feed holds now, to put back later with {@link #restoreContent} (Subscriptions: one channel shown) */
    public Object saveContent() {
        return new ArrayList<>(mGroups);
    }

    @SuppressWarnings("unchecked")
    public void restoreContent(Object content) {
        mGroups.clear();
        if (content instanceof List) {
            mGroups.addAll((List<Group>) content);
        }
        rebuild();
    }

    /** Shows only the videos the filter accepts; null shows them all. The loaded videos are kept. */
    public void setFilter(VideoFilter filter) {
        if (filter == mFilter) {
            return;
        }
        mFilter = filter;
        rebuild();
    }

    /** Rows on a channel page: drop the channel's own name from each row's details line */
    public void setHiddenAuthor(String author) {
        mHiddenAuthor = TextUtils.isEmpty(author) ? null : author;
    }

    /**
     * Player recommendations: never list the video that is playing.
     */
    public void setExcludedVideoId(String videoId) {
        if (videoId == null ? mExcludedVideoId == null : videoId.equals(mExcludedVideoId)) {
            return;
        }
        mExcludedVideoId = videoId;
        if (videoId == null) {
            return;
        }
        boolean changed = false;
        for (Group group : mGroups) {
            int oldCount = group.videos.size();
            if (group.videos.removeIf(v -> v != null && videoId.equals(v.videoId))) {
                changed = true;
                if (group.innerAdapter != null) {
                    group.innerAdapter.onDataChanged(oldCount);
                }
            }
        }
        if (changed) {
            rebuild();
        }
    }

    private List<Video> withoutExcluded(List<Video> videos) {
        if (mExcludedVideoId == null || videos == null) {
            return videos;
        }
        List<Video> result = new ArrayList<>(videos.size());
        for (Video video : videos) {
            if (video == null || !mExcludedVideoId.equals(video.videoId)) {
                result.add(video);
            }
        }
        return result;
    }

    /**
     * Same semantics as the TV adapters (VideoGroupObjectAdapter / MultipleRowsFragment).
     */
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        switch (group.getAction()) {
            case VideoGroup.ACTION_REMOVE:
                removeVideos(group.getVideos(), null);
                return;
            case VideoGroup.ACTION_REMOVE_AUTHOR:
                if (group.getVideos() != null && !group.getVideos().isEmpty()) {
                    removeVideos(null, group.getVideos().get(0).getAuthor());
                }
                return;
            case VideoGroup.ACTION_SYNC:
                sync(group);
                return;
            case VideoGroup.ACTION_REPLACE:
                if ((mMode == MODE_SHELVES || mMode == MODE_FEED) && group.getPosition() != -1) {
                    removeGroup(group.getId());
                } else if (mMode == MODE_MULTI) {
                    removeMultiGroups(group.getPosition() == 0);
                } else {
                    mGroups.clear();
                }
                break;
        }

        if (group.getVideos() == null || group.getVideos().isEmpty()) {
            rebuild();
            return;
        }

        Group existing = findGroup(group);

        if (existing != null) {
            existing.ref = group;
            int oldCount = existing.videos.size();
            existing.videos.clear();
            existing.videos.addAll(withoutExcluded(group.getVideos()));
            if (existing.innerAdapter != null) {
                existing.innerAdapter.onDataChanged(oldCount);
            }
        } else {
            Group newGroup = new Group(group);
            newGroup.videos.addAll(withoutExcluded(group.getVideos()));
            int position = group.getPosition();
            if ((mMode == MODE_SHELVES || mMode == MODE_FEED) && position >= 0 && position < mGroups.size()) {
                mGroups.add(position, newGroup);
            } else {
                mGroups.add(newGroup);
            }
        }

        rebuild();
    }

    /**
     * The video whose group should be continued when the vertical list reaches its end.
     */
    public Video getScrollEndVideo() {
        if (mMode == MODE_SHELVES || mMode == MODE_SETTINGS) {
            return null;
        }

        for (int i = mGroups.size() - 1; i >= 0; i--) {
            Group group = mGroups.get(i);
            if (isStripGroup(group) || group.videos.isEmpty() || isShortsBlock(group)) {
                continue;
            }
            return group.videos.get(group.videos.size() - 1);
        }

        return null;
    }

    /** Up to count videos at or after a list position (for thumbnail prefetching) */
    public List<Video> videosFrom(int position, int count) {
        List<Video> result = new ArrayList<>();
        for (int i = Math.max(0, position); i < mItems.size() && result.size() < count; i++) {
            Object item = mItems.get(i);
            if (item instanceof Video) {
                result.add((Video) item);
            } else if (item instanceof ShortTile) {
                result.add(((ShortTile) item).video);
            }
        }
        return result;
    }

    public List<Video> allVideos() {
        List<Video> result = new ArrayList<>();
        for (Group group : mGroups) {
            result.addAll(group.videos);
        }
        return result;
    }

    public int indexOf(Video video) {
        for (int i = 0; i < mItems.size(); i++) {
            Object item = mItems.get(i);
            if (item instanceof Video && item.equals(video)) {
                return i;
            }
        }
        return -1;
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return getItemViewType(position) == VT_SHORT ? 1 : 2;
            }
        };
    }

    // endregion

    // region Model

    private Group findGroup(VideoGroup group) {
        for (Group g : mGroups) {
            if (g.ref == group) {
                return g;
            }
        }
        for (Group g : mGroups) {
            if (g.ref.getId() == group.getId()) {
                return g;
            }
        }
        return null;
    }

    private void removeGroup(int id) {
        for (int i = mGroups.size() - 1; i >= 0; i--) {
            if (mGroups.get(i).ref.getId() == id) {
                mGroups.remove(i);
            }
        }
    }

    private void removeMultiGroups(boolean strip) {
        for (int i = mGroups.size() - 1; i >= 0; i--) {
            if (isStripGroup(mGroups.get(i)) == strip) {
                mGroups.remove(i);
            }
        }
    }

    private boolean isStripGroup(Group group) {
        return mMode == MODE_MULTI && group.ref.getPosition() == 0;
    }

    private boolean isShortsBlock(Group group) {
        return mMode != MODE_SHORTS && mMode != MODE_SHELVES && mMode != MODE_ROWS && group.ref.isShorts();
    }

    public boolean isShortTile(int position) {
        return position >= 0 && position < mItems.size() && getItemViewType(position) == VT_SHORT;
    }

    private void removeVideos(List<Video> videos, String author) {
        for (Group group : mGroups) {
            boolean changed = false;
            for (int i = group.videos.size() - 1; i >= 0; i--) {
                Video video = group.videos.get(i);
                boolean match = videos != null ? videos.contains(video) : author != null && Helpers.equals(author, video.getAuthor());
                if (match) {
                    group.videos.remove(i);
                    if (video.getGroup() != null) {
                        video.getGroup().remove(video);
                    }
                    changed = true;
                }
            }
            if (changed && group.innerAdapter != null) {
                group.innerAdapter.notifyDataSetChanged();
            }
        }
        rebuild();
    }

    private void sync(VideoGroup group) {
        if (group.getVideos() == null) {
            return;
        }

        for (Video video : group.getVideos()) {
            for (Group g : mGroups) {
                for (int i = 0; i < g.videos.size(); i++) {
                    Video origin = g.videos.get(i);
                    if (origin.equals(video)) {
                        origin.sync(video);
                        if (g.innerAdapter != null) {
                            g.innerAdapter.notifyItemChanged(i);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i) instanceof Video) {
                for (Video video : group.getVideos()) {
                    if (mItems.get(i).equals(video)) {
                        notifyItemChanged(i);
                        break;
                    }
                }
            }
        }
    }

    /** Search results: Shorts as tiles (see addWithShortsInPairs) */
    public void setShortsInPairs(boolean inPairs) {
        mShortsInPairs = inPairs;
    }

    /** The Shorts were found (video.isShorts set): they move into tiles */
    public void refresh() {
        rebuild();
    }

    /**
     * The group's videos, the Shorts among them as two column tiles: each pair where its first Short was (a Short
     * left over at the end waits for the next one, or stands alone once there's no more).
     */
    private void addWithShortsInPairs(List<Object> items, Group group) {
        int pairAt = -1;
        Video waiting = null;
        for (Video video : group.videos) {
            if (video == null) {
                continue;
            }
            if (!video.isShorts || isChannelItem(video)) {
                items.add(video);
            } else if (waiting == null) {
                waiting = video;
                pairAt = items.size();
            } else {
                items.add(pairAt, group.tile(waiting));
                items.add(pairAt + 1, group.tile(video));
                waiting = null;
            }
        }
        if (waiting != null) {
            items.add(pairAt, group.tile(waiting));
        }
    }

    private void rebuild() {
        List<Object> items = new ArrayList<>();

        if (mHeaderView != null) {
            items.add(HEADER_ITEM);
        }

        if (mMode == MODE_SETTINGS) {
            items.addAll(mSettings);
        } else if (mMode == MODE_MUSIC) {
            items.addAll(mMusic.build(mGroups));
        } else {
            for (Group group : mGroups) {
                if (group.videos.isEmpty()) {
                    continue;
                }
                if (mMode == MODE_SHELVES) {
                    items.add(group.shelf);
                } else if (isStripGroup(group)) {
                    items.add(group.strip);
                } else if (isShortsBlock(group)) {
                    items.add(group.shortsHeader);
                    int count = Math.min(SHORTS_BLOCK_SIZE, group.videos.size());
                    count -= count % 2; // keep the 2 column block even
                    if (count == 0) {
                        count = 1;
                    }
                    for (int i = 0; i < count; i++) {
                        items.add(group.tile(i));
                    }
                } else if (mFilter != null) {
                    for (Video video : group.videos) {
                        if (video != null && mFilter.accept(video)) {
                            items.add(video);
                        }
                    }
                } else if (mShortsInPairs && mMode == MODE_FLAT) {
                    addWithShortsInPairs(items, group);
                } else {
                    items.addAll(group.videos);
                }
            }
        }

        int oldSize = mItems.size();
        boolean isPrefix = oldSize > 0 && items.size() >= oldSize;
        for (int i = 0; isPrefix && i < oldSize; i++) {
            if (items.get(i) != mItems.get(i)) {
                isPrefix = false;
            }
        }

        mItems.clear();
        mItems.addAll(items);

        if (isPrefix) {
            if (items.size() > oldSize) {
                notifyItemRangeInserted(oldSize, items.size() - oldSize);
            }
        } else {
            notifyDataSetChanged();
        }
    }

    // endregion

    // region Adapter

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int position) {
        Object item = mItems.get(position);
        long hash = System.identityHashCode(item) & 0xffffffffL;
        int music = PhoneMusicFeed.viewType(item);
        if (item == HEADER_ITEM) {
            return (6L << 32) | mHeaderViewType;
        } else if (music != 0) {
            return ((long) music << 32) | hash;
        } else if (item instanceof Shelf) {
            return (1L << 32) | hash;
        } else if (item instanceof Strip) {
            return (2L << 32) | hash;
        } else if (item instanceof SettingsItem) {
            return (3L << 32) | hash;
        } else if (item instanceof ShortsHeader) {
            return (4L << 32) | hash;
        } else if (item instanceof ShortTile) {
            return (5L << 32) | hash;
        }
        return hash;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = mItems.get(position);
        if (item == HEADER_ITEM) {
            return mHeaderViewType;
        } else if (item instanceof Shelf) {
            return VT_SHELF;
        } else if (item instanceof Strip) {
            return VT_STRIP;
        } else if (item instanceof SettingsItem) {
            return item instanceof PhoneSetting && ((PhoneSetting) item).header ? VT_SETTING_HEADER : VT_SETTING;
        } else if (item instanceof ShortsHeader) {
            return VT_SECTION_HEADER;
        } else if (item instanceof ShortTile) {
            return VT_SHORT;
        } else if (PhoneMusicFeed.viewType(item) != 0) {
            return PhoneMusicFeed.viewType(item);
        }

        Video video = (Video) item;
        if (mMode == MODE_SHORTS) {
            return VT_SHORT;
        } else if (isChannelItem(video)) {
            return VT_CHANNEL;
        } else if (mMode == MODE_ROWS) {
            return VT_ROW;
        }
        return VT_VIDEO;
    }

    static boolean isChannelItem(Video video) {
        return video.videoId == null && video.playlistId == null && video.channelId != null;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == mHeaderViewType && mHeaderView != null) {
            if (mHeaderView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mHeaderView.getParent()).removeView(mHeaderView);
            }
            mHeaderView.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(mHeaderView);
        }

        if (PhoneMusicFeed.isMusicType(viewType)) {
            return new Holder(PhoneMusicFeed.create(parent, viewType));
        }

        switch (viewType) {
            case VT_SHORT: {
                View view = inflater.inflate(R.layout.phone_item_short, parent, false);
                ((RatioFrameLayout) view).setHeightToWidth(16f / 9f);
                return new Holder(view);
            }
            case VT_CHANNEL:
                return new Holder(inflater.inflate(R.layout.phone_item_channel, parent, false));
            case VT_SHELF:
            case VT_STRIP: {
                View view = inflater.inflate(R.layout.phone_item_shelf, parent, false);
                RecyclerView inner = view.findViewById(R.id.phone_shelf_list);
                inner.setLayoutManager(new LinearLayoutManager(parent.getContext(), LinearLayoutManager.HORIZONTAL, false));
                inner.setRecycledViewPool(mInnerPool);
                inner.setHasFixedSize(false);
                ShelfHolder holder = new ShelfHolder(view, inner);
                inner.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                        holder.checkScrollEnd();
                    }
                });
                return holder;
            }
            case VT_ROW:
                return new Holder(inflater.inflate(R.layout.phone_item_row, parent, false));
            case VT_SETTING:
                return new Holder(inflater.inflate(R.layout.phone_item_setting, parent, false));
            case VT_SETTING_HEADER:
                return new Holder(inflater.inflate(R.layout.phone_item_setting_header, parent, false));
            case VT_SECTION_HEADER:
                return new Holder(inflater.inflate(R.layout.phone_item_section_header, parent, false));
            case VT_VIDEO:
            default:
                return new Holder(inflater.inflate(R.layout.phone_item_video, parent, false));
        }
    }

    /** The video at this position if it's a big video card (one that can play in its thumbnail in the feed), else null */
    public Video previewVideoAt(int position) {
        if (position < 0 || position >= mItems.size() || getItemViewType(position) != VT_VIDEO) {
            return null;
        }
        Object item = mItems.get(position);
        return item instanceof Video ? (Video) item : null;
    }

    /** Payload: only the watch progress bars changed (a video was just watched) */
    private static final Object PROGRESS = new Object();

    /** Back from the player: the red bars show how far each video was watched, without reloading the cards */
    public void refreshProgress() {
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount(), PROGRESS);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.get(0) == PROGRESS) {
            Object item = mItems.get(position);
            if (item instanceof Video && holder.itemView.findViewById(R.id.phone_progress) != null) {
                PhoneUi.bindProgress(holder.itemView, (Video) item);
            }
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = mItems.get(position);
        int type = holder.getItemViewType();

        if (type == mHeaderViewType) {
            return; // bound by the owner
        } else if (PhoneMusicFeed.isMusicType(type)) {
            PhoneMusicFeed.bind(holder.itemView, item, mListener);
        } else if (type == VT_SHELF) {
            ((ShelfHolder) holder).bind(((Shelf) item).group, false);
        } else if (type == VT_STRIP) {
            ((ShelfHolder) holder).bind(((Strip) item).group, true);
        } else if (type == VT_SETTING) {
            bindSetting(holder.itemView, (SettingsItem) item);
        } else if (type == VT_SETTING_HEADER) {
            ((TextView) holder.itemView).setText(((SettingsItem) item).title);
        } else if (type == VT_SECTION_HEADER) {
            String title = ((ShortsHeader) item).group.ref.getTitle();
            ((TextView) holder.itemView.findViewById(R.id.phone_title)).setText(
                    !TextUtils.isEmpty(title) ? title : holder.itemView.getContext().getString(R.string.header_shorts));
        } else if (type == VT_SHORT) {
            bindShort(holder.itemView, item instanceof ShortTile ? ((ShortTile) item).video : (Video) item);
        } else if (type == VT_CHANNEL) {
            bindChannel(holder.itemView, (Video) item);
        } else {
            Video video = (Video) item;
            PhoneUi.bindCard(holder.itemView, video);
            if (mMusicBadges) {
                TextView badge = holder.itemView.findViewById(R.id.phone_badge);
                if (badge != null && badge.getVisibility() == View.VISIBLE && !badge.getText().toString().startsWith("♪")) {
                    badge.setText("♪ " + badge.getText());
                }
            }
            if (type == VT_ROW && mHiddenAuthor != null) {
                TextView meta = holder.itemView.findViewById(R.id.phone_meta);
                String text = meta.getText().toString();
                if (text.startsWith(mHiddenAuthor)) {
                    String rest = text.substring(mHiddenAuthor.length()).trim();
                    if (rest.startsWith("•") || rest.startsWith("·")) {
                        meta.setText(rest.substring(1).trim());
                    }
                }
            }
            bindClicks(holder.itemView, video);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ShelfHolder) {
            ((ShelfHolder) holder).saveState();
        }
    }

    private void bindClicks(View view, Video video) {
        view.setOnClickListener(v -> {
            PhoneUi.rememberClick(view);
            mListener.onVideoClicked(video);
        });
        view.setOnLongClickListener(v -> {
            mListener.onVideoLongClicked(video);
            return true;
        });
        View more = view.findViewById(R.id.phone_more);
        if (more != null) {
            more.setOnClickListener(v -> mListener.onVideoLongClicked(video));
        }
    }

    private void bindShort(View view, Video video) {
        TextView title = view.findViewById(R.id.phone_title);
        TextView meta = view.findViewById(R.id.phone_meta);
        title.setText(video.getTitle());
        CharSequence metaText = PhoneUi.meta(video);
        meta.setText(metaText);
        meta.setVisibility(TextUtils.isEmpty(metaText) ? View.GONE : View.VISIBLE);
        PhoneUi.loadThumb(view.findViewById(R.id.phone_thumb), video, true);
        bindClicks(view, video);
    }

    private void bindChannel(View view, Video video) {
        TextView title = view.findViewById(R.id.phone_title);
        TextView meta = view.findViewById(R.id.phone_meta);
        title.setText(!TextUtils.isEmpty(video.getTitle()) ? video.getTitle() : video.getAuthor());
        CharSequence metaText = PhoneUi.meta(video);
        meta.setText(metaText);
        meta.setVisibility(TextUtils.isEmpty(metaText) ? View.GONE : View.VISIBLE);
        PhoneUi.loadAvatar(view.findViewById(R.id.phone_thumb), video.getCardImageUrl());
        AvatarCache.remember(view.getContext(), video);
        bindClicks(view, video);
    }

    /** A settings row (phone_item_setting): also used by the Customize page */
    static void bindSetting(View view, SettingsItem item) {
        ImageView icon = view.findViewById(R.id.phone_thumb);
        TextView title = view.findViewById(R.id.phone_title);
        title.setText(item.title);
        if (item.imageResId > 0) {
            icon.setImageResource(item.imageResId);
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setVisibility(View.INVISIBLE);
        }
        // Phone rows: the current value or a switch on the right; an arrow only for rows that open something
        PhoneSetting setting = item instanceof PhoneSetting ? (PhoneSetting) item : null;
        TextView summary = view.findViewById(R.id.phone_setting_summary);
        if (summary != null) {
            boolean hasSummary = setting != null && setting.summary != null;
            summary.setVisibility(hasSummary ? View.VISIBLE : View.GONE);
            summary.setText(hasSummary ? setting.summary : null);
        }
        TextView value = view.findViewById(R.id.phone_setting_value);
        android.widget.Switch toggle = view.findViewById(R.id.phone_setting_switch);
        boolean hasValue = setting != null && setting.value != null;
        boolean hasToggle = setting != null && setting.checked != null;
        value.setVisibility(hasValue ? View.VISIBLE : View.GONE);
        value.setText(hasValue ? setting.value : null);
        toggle.setVisibility(hasToggle ? View.VISIBLE : View.GONE);
        if (hasToggle) {
            toggle.setChecked(setting.checked);
        }
        View dot = view.findViewById(R.id.phone_setting_dot);
        if (dot != null) {
            dot.setVisibility(setting != null && setting.badge ? View.VISIBLE : View.GONE); // e.g. What's new unread
        }
        boolean dropDown = setting != null && setting.onPick != null;
        ImageView chevron = view.findViewById(R.id.phone_setting_chevron);
        chevron.setVisibility(hasToggle ? View.GONE : View.VISIBLE);
        chevron.setRotation(dropDown ? 90 : 0); // pointing down: the list opens right here
        view.setOnClickListener(v -> {
            if (dropDown) {
                setting.onPick.pick(value);
                return;
            }
            if (item.onClick != null) {
                item.onClick.run();
            }
        });
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View view) {
            super(view);
        }
    }

    private final class ShelfHolder extends RecyclerView.ViewHolder {
        private final TextView mTitle;
        private final RecyclerView mInner;
        private Group mGroup;

        ShelfHolder(View view, RecyclerView inner) {
            super(view);
            mTitle = view.findViewById(R.id.phone_title);
            mInner = inner;
        }

        void bind(Group group, boolean isStrip) {
            if (mGroup != null && mGroup != group) {
                saveState();
            }
            mGroup = group;

            String title = group.ref.getTitle();
            mTitle.setText(title);
            mTitle.setVisibility(isStrip || TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);

            int style = isStrip ? ShelfAdapter.STYLE_AVATAR : group.ref.isShorts() ? ShelfAdapter.STYLE_SHORT : ShelfAdapter.STYLE_CARD;
            if (group.innerAdapter == null || group.innerAdapter.getStyle() != style) {
                group.innerAdapter = new ShelfAdapter(group.videos, style, mListener, () -> mSelectedStripItem);
            }

            if (mInner.getAdapter() != group.innerAdapter) {
                mInner.setAdapter(group.innerAdapter);
                RecyclerView.LayoutManager manager = mInner.getLayoutManager();
                if (manager != null) {
                    if (group.innerState != null) {
                        manager.onRestoreInstanceState(group.innerState);
                    } else {
                        manager.scrollToPosition(0);
                    }
                }
            }
        }

        void saveState() {
            if (mGroup != null && mInner.getLayoutManager() != null) {
                mGroup.innerState = mInner.getLayoutManager().onSaveInstanceState();
            }
        }

        void checkScrollEnd() {
            if (mGroup == null || mGroup.videos.isEmpty() || isStripGroup(mGroup)) {
                return;
            }
            LinearLayoutManager manager = (LinearLayoutManager) mInner.getLayoutManager();
            if (manager == null) {
                return;
            }
            int last = manager.findLastVisibleItemPosition();
            if (last >= mGroup.videos.size() - 3) {
                mListener.onShelfScrollEnd(mGroup.videos.get(mGroup.videos.size() - 1));
            }
        }
    }

    // endregion
}
