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
}
