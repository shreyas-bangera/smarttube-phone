package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.SystemClock;

/**
 * Whether what's being opened is music: songs opened from the Music page (or a playlist opened from it) play in the
 * music player (PhoneMusicPlayer), anything opened elsewhere in the usual video player.
 */
final class PhoneMusic {
    private static final long FRESH_MS = 6_000;
    private static long sClickAt;
    private static boolean sClickIsMusic;
    private static boolean sConsumed = true;
    private static String sSource;

    private PhoneMusic() {
    }

    /** Any card tapped: a video, unless markMusic() follows */
    static void onCardClick() {
        sClickAt = SystemClock.elapsedRealtime();
        sClickIsMusic = false;
        sConsumed = false;
    }

    /** Tapped on the Music page or in a Music playlist: plays as music, "playing from" the source */
    static void markMusic(String source) {
        sClickAt = SystemClock.elapsedRealtime();
        sClickIsMusic = true;
        sConsumed = false;
        if (source != null) {
            sSource = source;
        }
    }

    /** A Music tap just happened (a playlist page opening from the Music page) */
    static boolean isMusicTapRecent() {
        return sClickIsMusic && SystemClock.elapsedRealtime() - sClickAt < FRESH_MS;
    }

    /**
     * For the player, once per tap: TRUE (music), FALSE (a video) or null (no fresh tap: the next song of a list
     * plays on in the same way)
     */
    static Boolean consumeChoice() {
        if (sConsumed || SystemClock.elapsedRealtime() - sClickAt > FRESH_MS) {
            return null;
        }
        sConsumed = true;
        return sClickIsMusic;
    }

    static String source() {
        return sSource;
    }

    // The song's recording instead of its music video

    private static volatile boolean sMusicMode;
    /** The stream played for each video, when it's another video's (its recording) */
    private static final java.util.Map<String, String> sStreams = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** The music player is showing (or about to): the songs play as their recordings */
    static void setMusicMode(boolean on) {
        sMusicMode = on;
    }

    /** Music now: a tap on the Music page (or a music search) just now, or the music player on */
    static boolean wantsMusic() {
        boolean fresh = !sConsumed && SystemClock.elapsedRealtime() - sClickAt < FRESH_MS;
        return fresh ? sClickIsMusic : sMusicMode;
    }

    /**
     * The stream to play for this video (off the main thread): in music, the song's recording (YouTube Music's
     * "art track") when this is its music video; null for the video's own
     */
    static String streamFor(com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video) {
        if (video == null || video.videoId == null || video.isShorts || video.isLive || !wantsMusic()) {
            if (video != null && video.videoId != null) {
                sStreams.remove(video.videoId);
            }
            return null;
        }
        String recording = PhoneLyricsSources.audioVersion(video.videoId);
        if (recording != null) {
            sStreams.put(video.videoId, recording);
            android.util.Log.d("PhoneLyrics", "plays the recording " + recording + " for " + video.videoId);
        } else {
            sStreams.remove(video.videoId);
        }
        return recording;
    }

    /** The recording being played for this video, or null when it plays its own stream */
    static String playingRecordingOf(String videoId) {
        return videoId != null ? sStreams.get(videoId) : null;
    }

    // Music search: search results (songs, playlists) open in the music player. Kept until turned off.

    private static final String PREFS = "phone_music";
    private static final String KEY_SEARCH = "music_search";

    static boolean isMusicSearch(android.content.Context context) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_SEARCH, false);
    }

    static void setMusicSearch(android.content.Context context, boolean on) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_SEARCH, on).apply();
    }
}
