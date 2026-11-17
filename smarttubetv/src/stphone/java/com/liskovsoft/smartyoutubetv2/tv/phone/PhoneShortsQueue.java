package com.liskovsoft.smartyoutubetv2.tv.phone;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The Shorts the user is swiping through (the row or grid the first Short came from, plus every batch loaded after it).
 * Swiping only ever moves inside this list, so it never lands on a regular video.
 */
final class PhoneShortsQueue {
    private static final List<Video> sVideos = new ArrayList<>();
    /** How long a preloaded Shorts feed is used before a fresh one is fetched */
    private static final long FRESH_MS = 20 * 60 * 1000L;

    /** The Shorts feed as YouTube returned it (for its next page), when the queue came from preload() */
    private static com.liskovsoft.mediaserviceinterfaces.data.MediaGroup sFeed;
    private static long sLoadedAt;
    /** The position after the last Short played: the Shorts tab carries on from there */
    private static int sResumeIndex;
    private static io.reactivex.disposables.Disposable sLoad;
    private static io.reactivex.disposables.Disposable sMore;
    private static final List<Runnable> sWaiting = new ArrayList<>();

    private PhoneShortsQueue() {
    }

    static synchronized void set(VideoGroup group) {
        sVideos.clear();
        sFeed = null;
        sResumeIndex = 0;
        append(group);
    }

    // region The Shorts tab: a feed loaded ahead of time, so a tap starts a Short straight away (as in Flow)

    /** A loaded feed with a Short left to play */
    static synchronized boolean isReady() {
        return sFeed != null && System.currentTimeMillis() - sLoadedAt < FRESH_MS && sResumeIndex < sVideos.size();
    }

    /** The Short the Shorts tab opens on */
    static synchronized Video startVideo() {
        return sResumeIndex < sVideos.size() ? sVideos.get(sResumeIndex) : null;
    }

    static synchronized void markPlayed(Video video) {
        int index = indexOf(sVideos, video);
        if (index >= 0) {
            sResumeIndex = index + 1;
            if (sFeed != null && sResumeIndex >= sVideos.size() - 3) {
                loadMore();
            }
        }
    }

    /**
     * Loads the Shorts feed in the background (if there is no fresh one), with the first Shorts' pictures and the first
     * one's stream info, so opening it doesn't wait. onReady runs on the main thread once there is one (or the load
     * failed: then isReady() says so).
     */
    static synchronized void preload(android.content.Context context, Runnable onReady) {
        if (isReady()) {
            if (onReady != null) {
                onReady.run();
            }
            return;
        }
        if (onReady != null) {
            sWaiting.add(onReady);
        }
        if (sLoad != null && !sLoad.isDisposed()) {
            return; // already on its way
        }
        android.content.Context app = context.getApplicationContext();
        sLoad = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().getShortsObserve(),
                feed -> {
                    synchronized (PhoneShortsQueue.class) {
                        if (feed != null && feed.getMediaItems() != null && !feed.getMediaItems().isEmpty()) {
                            sVideos.clear();
                            sFeed = feed;
                            sLoadedAt = System.currentTimeMillis();
                            sResumeIndex = 0;
                            appendFeed(feed);
                            warmUp(app);
                            if (sVideos.size() < 6) {
                                loadMore(); // the first page can hold only a Short or two: the next ones now
                            }
                        }
                    }
                    notifyWaiting();
                },
                error -> notifyWaiting());
    }

    private static void notifyWaiting() {
        List<Runnable> waiting;
        synchronized (PhoneShortsQueue.class) {
            waiting = new ArrayList<>(sWaiting);
            sWaiting.clear();
        }
        for (Runnable runnable : waiting) {
            runnable.run();
        }
    }

    /** The next page of the feed, added to the end (while the user swipes) */
    static synchronized void loadMore() {
        if (sFeed == null || (sMore != null && !sMore.isDisposed())) {
            return;
        }
        com.liskovsoft.mediaserviceinterfaces.data.MediaGroup feed = sFeed;
        sMore = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().continueGroupObserve(feed),
                next -> {
                    synchronized (PhoneShortsQueue.class) {
                        if (next != null && sFeed == feed) {
                            sFeed = next;
                            appendFeed(next);
                        }
                    }
                },
                error -> { });
    }

    /** True when the queue loads its own next pages (see loadMore) */
    static synchronized boolean hasOwnFeed() {
        return sFeed != null;
    }

    private static void appendFeed(com.liskovsoft.mediaserviceinterfaces.data.MediaGroup feed) {
        for (com.liskovsoft.mediaserviceinterfaces.data.MediaItem item : feed.getMediaItems()) {
            if (item == null || item.getVideoId() == null) {
                continue;
            }
            Video video = Video.from(item);
            video.isShorts = true;
            if (indexOf(sVideos, video) < 0) {
                sVideos.add(video);
            }
        }
    }

    /** The first Shorts' pictures, and the first one's stream info (so its video starts without a round trip) */
    private static void warmUp(android.content.Context app) {
        Video first = startVideo();
        for (int i = sResumeIndex; i < Math.min(sVideos.size(), sResumeIndex + 3); i++) {
            PhoneUi.prefetchThumb(app, sVideos.get(i), true);
        }
        if (first != null && first.videoId != null) {
            String videoId = first.videoId;
            com.liskovsoft.sharedutils.rx.RxHelper.runAsync(
                    () -> com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(videoId));
        }
    }

    // endregion

    /** A further batch of the same Shorts list (loaded while the user swipes) */
    static synchronized void append(VideoGroup group) {
        if (group == null || group.getVideos() == null) {
            return;
        }
        // The group comes from the Shorts section or a Shorts row: every item is a Short
        // (items there don't always carry the isShorts flag)
        for (Video video : new ArrayList<>(group.getVideos())) {
            if (video != null && video.videoId != null && indexOf(sVideos, video) < 0) {
                sVideos.add(video);
            }
        }
    }

    static synchronized boolean isActive() {
        return !sVideos.isEmpty();
    }

    static synchronized List<Video> videos() {
        return new ArrayList<>(sVideos);
    }

    static boolean contains(Video video) {
        return indexOf(videos(), video) >= 0;
    }

    private static final java.util.concurrent.ExecutorService AHEAD = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.Set<String> sAsked = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * While a Short plays: the next two get their stream info now (as MeeTube does), so a swipe starts them without
     * waiting for YouTube. One at a time, after the playing Short's own request; each asked for once.
     */
    static void prefetchAhead(android.content.Context context, Video current) {
        android.content.Context app = context.getApplicationContext();
        Video next = next(current);
        Video afterNext = next != null ? next(next) : null;
        for (Video video : new Video[] {next, afterNext}) {
            if (video == null || video.videoId == null || !sAsked.add(video.videoId)) {
                continue;
            }
            if (sAsked.size() > 40) {
                java.util.Iterator<String> oldest = sAsked.iterator();
                oldest.next();
                oldest.remove();
            }
            PhoneUi.prefetchThumb(app, video, true);
            String videoId = video.videoId;
            AHEAD.execute(() -> {
                long start = android.os.SystemClock.elapsedRealtime();
                try {
                    com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(videoId);
                    android.util.Log.d("PhoneShorts", "ahead " + videoId + " ready in "
                            + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
                } catch (Throwable e) {
                    android.util.Log.d("PhoneShorts", "ahead " + videoId + ": " + e);
                }
            });
        }
    }

    static Video next(Video current) {
        List<Video> list = videos();
        int index = indexOf(list, current);
        if (index >= 0 && index + 1 < list.size()) {
            return list.get(index + 1);
        }
        return null;
    }

    static Video previous(Video current) {
        List<Video> list = videos();
        int index = indexOf(list, current);
        return index > 0 ? list.get(index - 1) : null;
    }

    static boolean isNearEnd(Video current) {
        List<Video> list = videos();
        int index = indexOf(list, current);
        return index >= 0 && index >= list.size() - 3;
    }

    static Video last() {
        List<Video> list = videos();
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private static int indexOf(List<Video> list, Video video) {
        if (video == null || video.videoId == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (video.videoId.equals(list.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }
}
