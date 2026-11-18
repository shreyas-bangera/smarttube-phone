package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/**
 * Phones: the stream played for a video can be another video's, the video itself staying what the app shows and
 * keeps (its title, the list it's in, what comes next, history). The music player plays a song's recording (its
 * YouTube Music "art track") instead of its music video: no intro or scenes, and lyrics timed to the recording fit.
 */
public final class StreamRedirect {
    public interface Resolver {
        /** The video whose stream to play for this one, or null for its own. Called off the main thread. */
        String streamVideoId(Video video);
    }

    private static volatile Resolver sResolver;

    private StreamRedirect() {
    }

    public static void set(Resolver resolver) {
        sResolver = resolver;
    }

    public static boolean isSet() {
        return sResolver != null;
    }

    /** The video id whose stream plays for this video (its own when there's no other) */
    public static String resolve(Video video) {
        Resolver resolver = sResolver;
        String id = null;
        try {
            id = resolver != null ? resolver.streamVideoId(video) : null;
        } catch (Throwable e) {
            // its own stream then
        }
        return id != null ? id : video.videoId;
    }
}
