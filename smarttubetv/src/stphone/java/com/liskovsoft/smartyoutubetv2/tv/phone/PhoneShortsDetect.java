package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds the Shorts among search results, as the YouTube app does. The TV search YouTube answers this app with doesn't
 * always mark them, so a short video is checked by its picture: YouTube keeps a picture in the video's own shape for
 * Shorts (oar2.jpg); a tall one means a portrait video, which then shows as a Shorts tile and plays in the Shorts
 * player. The picture is the one the tile shows anyway, so checking it costs nothing extra.
 */
final class PhoneShortsDetect {
    /** Shorts are up to 3 minutes */
    private static final long SHORTS_MAX_MS = 3 * 60_000L + 1_000;
    private static final ExecutorService CHECKS = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** videoId: portrait or not (checked once) */
    private static final Map<String, Boolean> sKnown = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 600;
        }
    };

    /** Short videos seen playing in portrait (up to 3 minutes): they open in the Shorts player */
    private static final java.util.Set<String> sPortraitPlayed = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    /** Longer videos seen playing in portrait: the regular player shows them tall (videoId: height to width) */
    private static final Map<String, Float> sTall = java.util.Collections.synchronizedMap(new LinkedHashMap<String, Float>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Float> eldest) {
            return size() > 300;
        }
    });

    static void rememberTall(String videoId, float heightToWidth) {
        if (videoId != null) {
            sTall.put(videoId, heightToWidth);
        }
    }

    /** The video's height to width if it's a long portrait video seen before, else 0 */
    static float tallRatio(String videoId) {
        Float ratio = videoId != null ? sTall.get(videoId) : null;
        return ratio != null ? ratio : 0f;
    }

    private PhoneShortsDetect() {
    }

    static void rememberPortrait(String videoId) {
        if (videoId == null) {
            return;
        }
        sPortraitPlayed.add(videoId);
        if (sPortraitPlayed.size() > 300) {
            synchronized (sPortraitPlayed) {
                java.util.Iterator<String> oldest = sPortraitPlayed.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    static boolean isPortraitVideo(String videoId) {
        return videoId != null && sPortraitPlayed.contains(videoId);
    }

    /**
     * Marks the Shorts in the list (video.isShorts), those known straight away now; the others are checked in the
     * background, and onFound runs (on the main thread) once that found any.
     */
    static void mark(Context context, List<Video> videos, Runnable onFound) {
        if (videos == null) {
            return;
        }
        List<Video> unsure = new ArrayList<>();
        for (Video video : new ArrayList<>(videos)) {
            if (video == null || video.isShorts || !mightBeShort(video)) {
                continue;
            }
            Boolean known;
            synchronized (sKnown) {
                known = sKnown.get(video.videoId);
            }
            if (known != null) {
                video.isShorts = known;
            } else if (surelyShort(video)) {
                video.isShorts = true;
            } else {
                unsure.add(video);
            }
        }
        if (unsure.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        int[] left = {unsure.size()};
        boolean[] found = {false};
        for (Video video : unsure) {
            CHECKS.execute(() -> {
                boolean portrait = isPortrait(app, video.videoId);
                synchronized (sKnown) {
                    sKnown.put(video.videoId, portrait);
                }
                MAIN.post(() -> {
                    if (portrait) {
                        video.isShorts = true;
                        found[0] = true;
                    }
                    if (--left[0] == 0 && found[0] && onFound != null) {
                        onFound.run();
                    }
                });
            });
        }
    }

    /** A plain video short enough to be a Short (not a channel, playlist, live or upcoming one) */
    private static boolean mightBeShort(Video video) {
        if (TextUtils.isEmpty(video.videoId) || video.playlistId != null || video.isLive || video.isUpcoming
                || video.isChannel() || video.isChapter) {
            return false;
        }
        long duration = video.getDurationMs();
        return duration <= 0 || duration <= SHORTS_MAX_MS;
    }

    /** Known without looking: Shorts pictures in the TV's shape, or "#shorts" in a short video's title */
    private static boolean surelyShort(Video video) {
        String image = video.cardImageUrl;
        if (image != null && (image.contains("-AG2CIACgA-") || image.contains("-oaymwEmCIAFEOAD8quKqQMa8AEB-AHOBYAC"))) {
            return true;
        }
        String title = video.getTitle() != null ? video.getTitle().toLowerCase(Locale.ROOT) : "";
        return title.contains("#shorts") && video.getDurationMs() > 0;
    }

    /** The video's own-shape picture is taller than wide (there's none for regular videos) */
    private static boolean isPortrait(Context app, String videoId) {
        try {
            File file = Glide.with(app).downloadOnly()
                    .load("https://i.ytimg.com/vi/" + videoId + "/oar2.jpg")
                    .submit().get(8, TimeUnit.SECONDS);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            return bounds.outHeight > bounds.outWidth * 1.15f;
        } catch (Throwable e) {
            return false; // no such picture: a regular video
        }
    }
}
