package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.tv.phone.cast.CastProxyServer;
import com.liskovsoft.smartyoutubetv2.tv.phone.cast.CastV2Session;
import com.liskovsoft.smartyoutubetv2.tv.phone.cast.MpdRewriter;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Casting with no ads, to Google Cast TVs (Google TV, Android TV, Chromecast), as NewTube does: the TV's built-in
 * media player (Google's Default Media Receiver, not the YouTube app) plays the video, fed by the phone. The phone
 * gets the video's streams the way it plays them itself, and a small server on the phone hands the TV the video's
 * description (a DASH manifest) and relays each piece the TV asks for from YouTube (it can't fetch them itself: they
 * are tied to the phone). So no YouTube app on the TV, and no ads. The phone has to stay on the same Wi-Fi.
 *
 * Not for live streams, or videos with no streams the TV can play (H.264, up to 1080p): those go to the TV's YouTube
 * app instead (see PhoneCast).
 */
final class PhoneCastDirect {
    private static final String TAG = "PhoneCast";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static CastProxyServer sProxy;
    private static CastV2Session sSession;
    private static boolean sConnected;
    private static String sVideoId;
    private static int sLoadGeneration;
    private static String sPlayerState = "IDLE";
    private static long sPositionMs;
    private static long sPositionAt;
    private static long sDurationMs;
    /** Told when the TV stops (closed, or the connection is lost) after casting started */
    private static Runnable sOnEnded;

    private PhoneCastDirect() {
    }

    static boolean isActive() {
        return sSession != null;
    }

    static boolean isPlaying() {
        return "PLAYING".equals(sPlayerState) || "BUFFERING".equals(sPlayerState);
    }

    static long positionMs() {
        long position = sPositionMs + ("PLAYING".equals(sPlayerState) ? SystemClock.elapsedRealtime() - sPositionAt : 0);
        return sDurationMs > 0 ? Math.min(position, sDurationMs) : position;
    }

    static String videoId() {
        return sVideoId;
    }

    /**
     * Opens the TV's media player and plays the video on it. done: true once the TV was told to play it; false if it
     * can't be cast this way (then the caller tries the TV's YouTube app).
     */
    static void start(String host, int port, String videoId, long positionMs, Runnable onEnded, PhoneCast.Done done) {
        stop();
        sOnEnded = onEnded;
        boolean[] answered = {false};
        PhoneCast.Done once = (ok, error) -> MAIN.post(() -> {
            if (!answered[0]) {
                answered[0] = true;
                if (!ok) {
                    stop();
                }
                done.done(ok, error);
            }
        });
        // First the video's streams (fails fast for live streams, and videos the TV can't play)
        WORKER.execute(() -> {
            Media media;
            CastProxyServer proxy = new CastProxyServer();
            try {
                proxy.setUserAgent(com.liskovsoft.googlecommon.common.helpers.DefaultHeaders.APP_USER_AGENT);
                proxy.start();
                if (proxy.getBaseUrl() == null) {
                    proxy.stop();
                    once.done(false, "no Wi-Fi address");
                    return;
                }
                media = prepare(proxy, videoId, false);
            } catch (Exception e) {
                proxy.stop();
                Log.d(TAG, "direct cast of " + videoId + " not possible: " + e.getMessage());
                once.done(false, e.getMessage());
                return;
            }
            Media ready = media;
            MAIN.post(() -> {
                sProxy = proxy;
                sVideoId = videoId;
                use(proxy, ready);
                sSession = CastV2Session.forDefaultMediaReceiver(host, port, new Listener(videoId, positionMs, once));
                sSession.start();
                Log.d(TAG, "direct cast: opening the TV's media player for " + videoId);
            });
        });
        // Never wait forever for the TV
        MAIN.postDelayed(() -> once.done(false, "the TV didn't answer"), 20_000);
    }

    /** Another video while casting: the TV's player loads it. done: false if it can't be cast this way */
    static void load(String videoId, long positionMs, PhoneCast.Done done) {
        CastProxyServer proxy = sProxy;
        if (proxy == null || sSession == null) {
            done.done(false, "not casting");
            return;
        }
        int generation = ++sLoadGeneration;
        WORKER.execute(() -> {
            Media media;
            try {
                media = prepare(proxy, videoId, false);
            } catch (Exception e) {
                Log.d(TAG, "direct cast of " + videoId + " not possible: " + e.getMessage());
                MAIN.post(() -> done.done(false, e.getMessage()));
                return;
            }
            Media ready = media;
            MAIN.post(() -> {
                if (generation != sLoadGeneration || sSession == null || sProxy != proxy) {
                    return; // another video came meanwhile, or casting stopped
                }
                sVideoId = videoId;
                use(proxy, ready);
                sendLoad(videoId, positionMs);
                done.done(true, null);
            });
        });
    }

    static void togglePlay() {
        CastV2Session session = sSession;
        if (session == null) {
            return;
        }
        sPositionMs = positionMs();
        sPositionAt = SystemClock.elapsedRealtime();
        if (isPlaying()) {
            sPlayerState = "PAUSED";
            session.pause();
        } else {
            sPlayerState = "PLAYING";
            session.play();
        }
    }

    /** Stops casting: the TV's player closes */
    static void stop() {
        CastV2Session session = sSession;
        CastProxyServer proxy = sProxy;
        sSession = null;
        sProxy = null;
        sConnected = false;
        sOnEnded = null;
        sLoadGeneration++;
        if (session != null || proxy != null) {
            Thread closer = new Thread(() -> {
                if (session != null) {
                    session.close(); // tells the TV to stop its player
                }
                if (proxy != null) {
                    proxy.stop();
                }
            }, "CastDirectStop");
            closer.setDaemon(true);
            closer.start();
        }
    }

    /**
     * What the TV is given for a video: a DASH manifest pointing at the phone's relay (best: separate video and
     * sound up to 1080p), or one file with both (one stream, lower quality, for when there's no DASH) also through
     * the relay. userAgent: the one YouTube gave the links for (the relay presents it).
     */
    static final class Media {
        byte[] manifest;
        String fileUrl;
        String userAgent;
        String what;
        String title;

        /** The URL the TV opens */
        String url(CastProxyServer proxy, String videoId) {
            if (manifest != null) {
                return proxy.getManifestUrl() + "?v=" + videoId; // a fresh URL per video (no stale manifest)
            }
            // (the name at the end is for TVs that look at it; the relay ignores it)
            return proxy.getBaseUrl() + "/seg/" + com.liskovsoft.smartyoutubetv2.tv.phone.cast.SegmentToken.encode(fileUrl) + "?f=video.mp4";
        }

        String mimeType() {
            return manifest != null ? MpdRewriter.MIME_TYPE : "video/mp4";
        }
    }

    /**
     * Gets a video ready for the TV's player, trying in turn: the links the download feature uses (plain links that
     * play anywhere), the player's own streams, then a single file with video and sound. Throws if none work.
     */
    static Media prepare(CastProxyServer proxy, String videoId, boolean fileOnly) throws Exception {
        StringBuilder why = new StringBuilder();
        MediaItemFormatInfo download = null;
        String downloadAgent = null;
        try {
            com.liskovsoft.youtubeapi.service.YouTubeDownloadService.DownloadInfo info =
                    com.liskovsoft.youtubeapi.service.YouTubeDownloadService.getDownloadInfo(videoId);
            if (info != null) {
                download = info.formatInfo;
                downloadAgent = info.userAgent;
            }
        } catch (Throwable e) {
            why.append("links: ").append(e.getMessage()).append("; ");
        }
        if (download != null && download.isLive()) {
            throw new IllegalStateException("live stream");
        }
        String appAgent = com.liskovsoft.googlecommon.common.helpers.DefaultHeaders.APP_USER_AGENT;
        if (!fileOnly) {
            Media media = dash(proxy, download, downloadAgent, "download links", why);
            if (media != null) {
                return media;
            }
        }
        MediaItemFormatInfo player = null;
        try {
            player = com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(videoId);
        } catch (Throwable e) {
            why.append("player: ").append(e.getMessage()).append("; ");
        }
        if (player != null && player.isLive()) {
            throw new IllegalStateException("live stream");
        }
        if (!fileOnly) {
            Media media = dash(proxy, player, appAgent, "player streams", why);
            if (media != null) {
                return media;
            }
        }
        Media file = file(download, downloadAgent);
        if (file == null) {
            file = file(player, appAgent);
        }
        if (file != null) {
            file.title = download != null ? download.getTitle() : player != null ? player.getTitle() : null;
            return file;
        }
        throw new IllegalStateException(why.length() > 0 ? why.toString() : "no stream the TV can play");
    }

    private static Media dash(CastProxyServer proxy, MediaItemFormatInfo info, String userAgent, String what, StringBuilder why) {
        if (info == null || info.isUnplayable() || !info.containsDashFormats()) {
            why.append(what).append(": no DASH; ");
            return null;
        }
        try {
            InputStream mpd = info.createMpdStream();
            if (mpd == null) {
                why.append(what).append(": no manifest; ");
                return null;
            }
            MpdRewriter.Result result = MpdRewriter.rewrite(mpd, proxy.getBaseUrl());
            if (result.isEmpty() || !result.hasCompatibleVideo()) {
                why.append(what).append(": no H.264 video; ");
                return null;
            }
            Media media = new Media();
            media.manifest = result.getMpdBytes();
            media.userAgent = userAgent;
            media.what = what + " (DASH)";
            return media;
        } catch (Throwable e) {
            why.append(what).append(": ").append(e.getMessage()).append("; ");
            return null;
        }
    }

    /** A single file with video and sound (MP4, H.264), the highest there is */
    private static Media file(MediaItemFormatInfo info, String userAgent) {
        if (info == null || info.getUrlFormats() == null) {
            return null;
        }
        com.liskovsoft.mediaserviceinterfaces.data.MediaFormat best = null;
        for (com.liskovsoft.mediaserviceinterfaces.data.MediaFormat format : info.getUrlFormats()) {
            String mime = format != null && format.getMimeType() != null ? format.getMimeType() : "";
            if (format == null || format.getUrl() == null || !mime.startsWith("video/mp4")) {
                continue;
            }
            if (best == null || format.getHeight() > best.getHeight()) {
                best = format;
            }
        }
        if (best == null) {
            return null;
        }
        Media media = new Media();
        media.fileUrl = best.getUrl();
        media.userAgent = userAgent;
        media.what = "one file " + best.getHeight() + "p";
        return media;
    }

    private static Media sMedia;

    /** The relay serves this video now */
    private static void use(CastProxyServer proxy, Media media) {
        sMedia = media;
        proxy.setUserAgent(media.userAgent);
        if (media.manifest != null) {
            proxy.loadVideo(media.manifest);
        }
        Log.d(TAG, "direct cast: " + media.what);
    }

    private static void sendLoad(String videoId, long positionMs) {
        CastV2Session session = sSession;
        CastProxyServer proxy = sProxy;
        Media media = sMedia;
        if (session == null || proxy == null || media == null || proxy.getBaseUrl() == null) {
            return;
        }
        session.load(media.url(proxy, videoId), media.mimeType(), null,
                "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", Math.max(0, positionMs));
        sPlayerState = "BUFFERING";
        sPositionMs = Math.max(0, positionMs);
        sPositionAt = SystemClock.elapsedRealtime();
        Log.d(TAG, "direct cast: playing " + videoId + " from " + positionMs / 1000 + " s");
    }

    private static void ended(String reason) {
        Log.d(TAG, "direct cast ended" + (reason != null ? ": " + reason : ""));
        Runnable onEnded = sOnEnded;
        stop();
        if (onEnded != null) {
            onEnded.run();
        }
    }

    private static final class Listener implements CastV2Session.Listener {
        private final String mVideoId;
        private final long mPositionMs;
        private final PhoneCast.Done mDone;

        Listener(String videoId, long positionMs, PhoneCast.Done done) {
            mVideoId = videoId;
            mPositionMs = positionMs;
            mDone = done;
        }

        private boolean current() {
            return sSession != null;
        }

        @Override
        public void onConnected() {
            MAIN.post(() -> {
                if (!current()) {
                    return;
                }
                sConnected = true;
                sendLoad(mVideoId, mPositionMs);
                mDone.done(true, null);
            });
        }

        @Override
        public void onMediaStatus(String playerState, long positionMs, long durationMs) {
            MAIN.post(() -> {
                if (!current()) {
                    return;
                }
                if (playerState != null) {
                    sPlayerState = playerState;
                }
                if (positionMs >= 0) {
                    sPositionMs = positionMs;
                    sPositionAt = SystemClock.elapsedRealtime();
                }
                if (durationMs > 0) {
                    sDurationMs = durationMs;
                }
            });
        }

        @Override
        public void onLaunchError(String reason) {
            MAIN.post(() -> mDone.done(false, "the TV's player didn't open: " + reason));
        }

        @Override
        public void onLoadFailed(String type) {
            MAIN.post(() -> {
                if (sConnected) {
                    Log.d(TAG, "direct cast: the TV couldn't play it (" + type + ")");
                    sPlayerState = "IDLE";
                }
                mDone.done(false, "the TV couldn't play it");
            });
        }

        @Override
        public void onChannelError(String reason) {
            MAIN.post(() -> {
                mDone.done(false, reason);
                if (current() && sConnected) {
                    ended(reason);
                }
            });
        }

        @Override
        public void onClosed() {
            MAIN.post(() -> {
                if (current() && sConnected) {
                    ended(null);
                }
            });
        }
    }
}
