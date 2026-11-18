package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics for the music player, from LRCLIB (lrclib.net: a free, open lyrics database). Time-synced lines when it
 * has them (the player highlights the line being sung), plain text otherwise. A song is looked up by its title
 * (cleaned of "Official Video" and the like) and checked against the video's length, so another song of the same name
 * isn't taken.
 */
final class PhoneLyrics {
    private static final String TAG = "PhoneLyrics";
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Map<String, Lyrics> CACHE = Collections.synchronizedMap(new HashMap<>());
    private static final Lyrics NONE = new Lyrics(new ArrayList<>(), false);

    static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    static final class Lyrics {
        final List<Line> lines;
        final boolean synced;
        /** Where they come from (Apple Music, LRCLIB, YouTube Music) */
        final String source;
        /** The length of the recording they're timed to, 0 when not known */
        long lengthMs;
        /**
         * Their times are this video's: the video is the recording, or as long as it, or SponsorBlock says where its
         * parts without music are. Otherwise they show without lighting up lines, until the listener puts them in
         * time (holding the line being sung).
         */
        boolean trusted = true;
        /** The script the song's language is written in (see find), null when not known */
        Character.UnicodeScript script;

        Lyrics(List<Line> lines, boolean synced) {
            this(lines, synced, "LRCLIB");
        }

        Lyrics(List<Line> lines, boolean synced, String source) {
            this.lines = lines;
            this.synced = synced;
            this.source = source;
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }

        /** The line being sung at this moment, or -1 before the first */
        int indexAt(long positionMs) {
            if (!synced) {
                return -1;
            }
            int index = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).timeMs <= positionMs + 250) { // a moment early: the line shows as it starts
                    index = i;
                } else {
                    break;
                }
            }
            return index;
        }
    }

    interface Callback {
        void onLyrics(String videoId, Lyrics lyrics);
    }

    private PhoneLyrics() {
    }

    /** Looks the song up (in the background); the callback runs on the main thread, with empty lyrics if none */
    static void load(String videoId, String title, String author, long durationMs, Callback callback) {
        if (videoId == null) {
            return;
        }
        Lyrics cached = CACHE.get(videoId);
        if (cached != null) {
            callback.onLyrics(videoId, cached);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        LOADER.execute(() -> {
            Lyrics lyrics = NONE;
            try {
                lyrics = oneScript(find(videoId, title, author, durationMs));
                Log.d(TAG, "lyrics of " + videoId + ": " + (lyrics.isEmpty() ? "none" : lyrics.source + ", "
                        + lyrics.lines.size() + " lines, " + (!lyrics.synced ? "words only" : lyrics.trusted ? "timed"
                        : "timed, not to this video") + (PhoneMusic.playingRecordingOf(videoId) != null ? ", playing the recording" : "")));
            } catch (Throwable e) {
                Log.d(TAG, "lyrics of " + videoId + ": " + e);
            }
            CACHE.put(videoId, lyrics);
            Lyrics result = lyrics;
            main.post(() -> callback.onLyrics(videoId, result));
        });
    }

    /** A version of the lyrics LRCLIB has, with what decides between them */
    private static final class Candidate {
        final JSONObject item;
        final String lyrics;
        final boolean synced;
        final double lengthDiff;
        final int order;
        final Character.UnicodeScript script;
        final boolean mixed;

        Candidate(JSONObject item, String lyrics, boolean synced, double lengthDiff, int order) {
            this.item = item;
            this.lyrics = lyrics;
            this.synced = synced;
            this.lengthDiff = lengthDiff;
            this.order = order;
            Object[] main = mainScript(lyrics);
            this.script = (Character.UnicodeScript) main[0];
            this.mixed = (Boolean) main[1];
        }
    }

    /**
     * The lyrics in the song's own language, timed to this recording:
     * <ol>
     *     <li>All the versions LRCLIB has: its own match for this song, artist and length, then search results.</li>
     *     <li>The song's language: the title's script when it isn't Latin; otherwise the script most versions are
     *     written in, other than Latin (a Hindi song: Devanagari; Punjabi: Gurmukhi; Korean: Hangul); Latin when they're
     *     all in Latin (an English or Spanish song).</li>
     *     <li>Of the versions in that script (then romanized ones, then any other; never mixed scripts first), the
     *     timed one closest in length to the video: another version, with another intro or ending, puts every line
     *     off by that much.</li>
     * </ol>
     */
    /** Flow's lyrics sources, in Flow's order (LyricsProviderOrder.DEFAULT, of the ones that answer anyone) */
    private static final String[] ORDER = {"YouTube Music", "BetterLyrics", "lrc.red", "LyricsPlus", "SimpMusic", "LRCLIB"};
    private static final ExecutorService SOURCES = Executors.newCachedThreadPool();
    /** As Flow's run: everything stops at 9 s */
    private static final long RUN_MS = 9_000;

    /**
     * The lyrics for this video, found the way Flow finds them (github.com/Flow-Tube/Flow, data/lyrics):
     * <ol>
     *     <li>What plays: in music, the song's recording (see PhoneMusic.streamFor); YouTube Music names it (title,
     *     artists, length).</li>
     *     <li>Every source asked at once, in Flow's order: YouTube Music's own timed lyrics for the recording (by its
     *     id: they can't be another song's, and are timed to it), BetterLyrics, lrc.red and LyricsPlus (Apple Music's,
     *     by name, artist and length), SimpMusic (by video id), LRCLIB. 9 s at most.</li>
     *     <li>The first timed answer in that order in the song's language (see songScript), its sibling script
     *     converted (see oneScript); else the first timed answer; else words without times.</li>
     *     <li>A music video's parts without music (SponsorBlock) put the lines on its clock (see onThisVideo).</li>
     * </ol>
     */
    private static Lyrics find(String videoId, String title, String author, long durationMs) throws Exception {
        // In music, the recording plays for a music video (see PhoneMusic.streamFor): its timing is the one to match
        String recording = PhoneMusic.wantsMusic() ? PhoneLyricsSources.audioVersion(videoId) : null;
        String playing = recording != null ? recording : videoId;
        PhoneLyricsSources.Track track = PhoneLyricsSources.track(playing);
        String song = track != null ? track.title : displayTitle(title);
        String artist = track != null && !TextUtils.isEmpty(track.artist) ? track.artist : cleanArtist(author);
        long videoMs = recording != null && track != null && track.seconds > 0 ? track.seconds * 1000L
                : durationMs > 0 ? durationMs : track != null ? track.seconds * 1000L : 0;
        int seconds = (int) (videoMs / 1000);
        boolean audioTrack = recording != null || (track != null && track.audioTrack);
        Log.d(TAG, "song: " + song + " / " + artist + (audioTrack ? " (art track)" : "") + ", " + seconds + " s");

        List<java.util.concurrent.Future<Lyrics>> asked = new ArrayList<>();
        asked.add(SOURCES.submit(() -> PhoneLyricsSources.youTubeMusicTimed(track, videoMs)));
        asked.add(SOURCES.submit(() -> {
            Lyrics apple = TextUtils.isEmpty(artist) ? null : PhoneLyricsSources.betterLyrics(song, artist, seconds);
            if (apple != null && !mentionsTitle(apple, song)) {
                // Matched by name only: another song came back (an English one for a Hindi song); the song's own
                // title would be in its words
                Log.d(TAG, "BetterLyrics answered another song for " + song);
                return null;
            }
            if (apple != null) {
                apple.lengthMs = videoMs;
            }
            return apple;
        }));
        asked.add(SOURCES.submit(() -> {
            Lyrics apple = PhoneLyricsSources.lrcRedSearch(song, artist, seconds);
            if (apple == null && !TextUtils.isEmpty(artist)) {
                PhoneLyricsSources.Recording isrc = PhoneLyricsSources.identify(song, artist, seconds);
                apple = PhoneLyricsSources.lrcRed(isrc);
                if (apple != null) {
                    apple.lengthMs = isrc.seconds * 1000L;
                }
            }
            return apple;
        }));
        asked.add(SOURCES.submit(() -> PhoneLyricsSources.lyricsPlus(song, artist, seconds)));
        asked.add(SOURCES.submit(() -> PhoneLyricsSources.simpMusic(playing, seconds)));
        java.util.concurrent.Future<List<Candidate>> lrclib = SOURCES.submit(() -> lrclibVersions(song, artist, videoMs));

        long deadline = System.currentTimeMillis() + RUN_MS;
        List<Lyrics> answers = new ArrayList<>();
        List<Object[]> scripts = new ArrayList<>();
        List<String> latin = new ArrayList<>(); // the words in Latin letters: English, or a song romanized
        for (int i = 0; i < asked.size(); i++) {
            Lyrics answer = await(asked.get(i), deadline);
            answer = answer != null && answer.synced && !answer.isEmpty() ? answer : null;
            answers.add(answer);
            if (answer != null) {
                Object[] main = mainScript(text(answer));
                scripts.add(main);
                if (main[0] == Character.UnicodeScript.LATIN) {
                    latin.add(text(answer));
                }
                Log.d(TAG, ORDER[i] + ": " + answer.lines.size() + " lines in " + main[0]
                        + (answer.lengthMs > 0 ? ", timed to " + answer.lengthMs / 1000 + " s" : ""));
            } else {
                Log.d(TAG, ORDER[i] + ": none");
            }
        }
        List<Candidate> versions = await(lrclib, deadline);
        if (versions == null) {
            versions = new ArrayList<>();
        }
        for (Candidate candidate : versions) {
            if (candidate.script == Character.UnicodeScript.LATIN) {
                latin.add(candidate.lyrics);
            }
        }
        Character.UnicodeScript language = songScript(title, song, versions, scripts, english(latin));
        Lyrics fromLrclib = pick(versions, language, false);
        answers.add(fromLrclib.synced ? fromLrclib : null);
        Log.d(TAG, "song in " + language + ", " + versions.size() + " LRCLIB versions");

        // The first timed answer in Flow's order that's in the song's language; else the first timed one
        int chosen = -1;
        for (int i = 0; i < answers.size() && chosen < 0; i++) {
            Lyrics answer = answers.get(i);
            if (answer != null) {
                Character.UnicodeScript script = (Character.UnicodeScript) mainScript(text(answer))[0];
                if (script == language || siblings(script, language)) {
                    chosen = i;
                }
            }
        }
        for (int i = 0; i < answers.size() && chosen < 0; i++) {
            if (answers.get(i) != null) {
                chosen = i;
            }
        }
        if (chosen >= 0) {
            Log.d(TAG, "showing " + ORDER[chosen] + "'s");
            // YouTube Music's and SimpMusic's are filed under what plays: its own timing
            boolean byId = chosen == 0 || chosen == 4;
            return inScript(onThisVideo(videoId, answers.get(chosen), videoMs, audioTrack || byId), language);
        }

        // Nothing timed: words, in the song's language
        Lyrics words = track != null ? PhoneLyricsSources.youTubeMusicLyrics(track.lyricsId) : null;
        if (words != null && !words.isEmpty() && !fromLrclib.isEmpty()) {
            Character.UnicodeScript wordsScript = (Character.UnicodeScript) mainScript(text(words))[0];
            if (wordsScript != language && !siblings(wordsScript, language)) {
                return inScript(fromLrclib, language); // LRCLIB's words are in the song's language, YouTube Music's aren't
            }
        }
        return inScript(words != null && !words.isEmpty() ? words : fromLrclib, language);
    }

    private static <T> T await(java.util.concurrent.Future<T> future, long deadline) {
        try {
            return future.get(Math.max(1, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            future.cancel(true);
            return null;
        }
    }

    private static Lyrics inScript(Lyrics lyrics, Character.UnicodeScript language) {
        if (lyrics != NONE) { // (shared: never marked)
            lyrics.script = language;
        }
        return lyrics;
    }

    private static String text(Lyrics lyrics) {
        StringBuilder all = new StringBuilder();
        for (Line line : lyrics.lines) {
            all.append(line.text).append('\n');
        }
        return all.toString();
    }

    /** Hindi and Punjabi scripts: the same letters, one converts to the other */
    private static boolean siblings(Character.UnicodeScript a, Character.UnicodeScript b) {
        return indic(a) && indic(b);
    }

    private static boolean indic(Character.UnicodeScript script) {
        return script == Character.UnicodeScript.DEVANAGARI || script == Character.UnicodeScript.GURMUKHI;
    }

    /**
     * Timing made for the recording is this video's when the video is the recording (an art track) or as long as it;
     * a music video with an intro or a scene is longer: SponsorBlock's "non-music" parts, where users marked them,
     * put the lines on the video's clock. Otherwise the lines aren't trusted to light up (they'd run ahead of the
     * singing) until the listener syncs them.
     */
    private static Lyrics onThisVideo(String videoId, Lyrics lyrics, long videoMs, boolean audioTrack) {
        long diff = lyrics.lengthMs > 0 && videoMs > 0 ? Math.abs(lyrics.lengthMs - videoMs) : 0;
        if (audioTrack || diff <= 3_000) {
            return lyrics;
        }
        List<long[]> parts = PhoneLyricsSources.nonMusicParts(videoId);
        if (!parts.isEmpty() && Math.abs(lyrics.lengthMs + PhoneLyricsSources.total(parts) - videoMs) <= 4_000) {
            Lyrics fitted = PhoneLyricsSources.onVideoClock(lyrics, parts);
            fitted.lengthMs = videoMs;
            Log.d(TAG, "on the video's clock with SponsorBlock: " + parts.size() + " parts without music");
            return fitted;
        }
        Log.d(TAG, "timing not this video's: off by " + diff + " ms");
        lyrics.trusted = false;
        return lyrics;
    }

    /**
     * The lyrics all in the song's script: Hindi film songs come with lines in Devanagari and in Gurmukhi (where the
     * words are Punjabi), and some versions of a Hindi song are wholly in Gurmukhi, or of a Punjabi song in
     * Devanagari. The two scripts map letter for letter, so every line is put in the one the listener reads
     * (see readerScript), whichever source the lyrics came from.
     */
    static Lyrics oneScript(Lyrics lyrics) {
        int devanagari = 0;
        int gurmukhi = 0;
        for (Line line : lyrics.lines) {
            for (int i = 0; i < line.text.length(); i++) {
                char c = line.text.charAt(i);
                if (c >= 0x0900 && c <= 0x097F) {
                    devanagari++;
                } else if (c >= 0x0A00 && c <= 0x0A7F) {
                    gurmukhi++;
                }
            }
        }
        if (devanagari == 0 && gurmukhi == 0) {
            return lyrics;
        }
        // Whichever source they came from, in the script the listener reads (see readerScript)
        boolean toGurmukhi = readerScript() == Character.UnicodeScript.GURMUKHI;
        if (toGurmukhi ? devanagari == 0 : gurmukhi == 0) {
            return lyrics;
        }
        List<Line> lines = new ArrayList<>();
        for (Line line : lyrics.lines) {
            lines.add(new Line(line.timeMs, toGurmukhi ? devanagariToGurmukhi(line.text) : gurmukhiToDevanagari(line.text)));
        }
        Lyrics converted = new Lyrics(lines, lyrics.synced, lyrics.source);
        converted.lengthMs = lyrics.lengthMs;
        converted.trusted = lyrics.trusted;
        converted.script = lyrics.script;
        Log.d(TAG, "lyrics put in " + (toGurmukhi ? "Gurmukhi" : "Devanagari"));
        return converted;
    }

    /** Devanagari letters as Gurmukhi: the same letters, 0x100 apart, but for a few Gurmukhi doesn't have */
    static String devanagariToGurmukhi(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x0900 || c > 0x097F || c == 0x0964 || c == 0x0965) {
                out.append(c); // (the danda is both scripts')
            } else if (c == 0x090B) {
                out.append("\u0A30\u0A3F"); // vocalic r: ri
            } else if (c == 0x0943) {
                out.append("\u0A4D\u0A30\u0A3F"); // its sign: + ri
            } else if (c == 0x0937) {
                out.append('\u0A36'); // ssa: sha
            } else if (c == 0x0950) {
                out.append('\u0A74'); // om
            } else if (Character.isDefined(c + 0x100)) {
                out.append((char) (c + 0x100));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Gurmukhi letters as Devanagari: the same letters, 0x100 apart, but for a few signs of Gurmukhi's own */
    static String gurmukhiToDevanagari(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x0A00 || c > 0x0A7F) {
                out.append(c);
            } else if (c == 0x0A70) {
                out.append('\u0902'); // tippi: anusvara
            } else if (c == 0x0A71) {
                // addak: the next consonant doubled (consonant + virama, then the consonant itself)
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if (next >= 0x0A15 && next <= 0x0A5E) {
                    out.append((char) (next - 0x100)).append('\u094D');
                }
            } else if (c == 0x0A72) {
                out.append('\u0907'); // iri
            } else if (c == 0x0A73) {
                out.append('\u0909'); // ura
            } else if (c == 0x0A75) {
                out.append('\u094D').append('\u092F'); // yakash
            } else {
                out.append((char) (c - 0x100));
            }
        }
        return out.toString();
    }

    /** The song's title (a word of it) is in its words: these lyrics are this song's */
    static boolean mentionsTitle(Lyrics lyrics, String title) {
        StringBuilder all = new StringBuilder();
        for (Line line : lyrics.lines) {
            all.append(' ').append(line.text.toLowerCase(java.util.Locale.ROOT));
        }
        String words = all.toString().replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (String word : displayTitle(title).toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !COMMON_WORDS.contains(word) && words.contains(" " + word)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> COMMON_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "song", "the", "and", "from", "with", "official", "video", "audio", "full", "lyrical", "lyrics", "feat",
            "version", "remix", "live", "music", "title", "track"));

    /** The versions LRCLIB has of this song: its own match for song, artist and length, then search results */
    private static List<Candidate> lrclibVersions(String title, String artist, long durationMs) {
        List<Candidate> candidates = new ArrayList<>();
        try {
            collect(candidates, title, artist, durationMs);
        } catch (Exception e) {
            Log.d(TAG, "LRCLIB: " + e);
        }
        return candidates;
    }

    private static void collect(List<Candidate> candidates, String title, String artist, long durationMs) throws Exception {
        List<String> tracks = trackNames(title);
        int order = 0;

        if (!TextUtils.isEmpty(artist) && durationMs > 0) {
            for (String track : tracks) {
                JSONObject exact = get(track, artist, durationMs);
                if (exact != null) {
                    addCandidate(candidates, exact, title, durationMs, order++);
                    break;
                }
            }
        }
        Set<String> queries = new LinkedHashSet<>();
        for (String track : tracks) {
            if (!TextUtils.isEmpty(artist)) {
                queries.add(track + " " + artist);
            }
            queries.add(track);
        }
        int queried = 0;
        for (String query : queries) {
            JSONArray results = search(query);
            for (int i = 0; results != null && i < results.length(); i++) {
                addCandidate(candidates, results.getJSONObject(i), title, durationMs, order++);
            }
            if (++queried >= 2 && countSynced(candidates) >= 3) {
                break; // enough to choose from
            }
        }
    }

    /**
     * Of LRCLIB's versions, the one in the song's script (then romanized, then any other; never mixed scripts
     * first), timed, closest in length to the video: another version, with another intro or ending, puts every line
     * off by that much. With ownScriptOnly, only a timed one in the song's script (or its sibling).
     */
    private static Lyrics pick(List<Candidate> candidates, Character.UnicodeScript language, boolean ownScriptOnly) {
        Candidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            boolean own = candidate.script == language || siblings(candidate.script, language);
            if (ownScriptOnly && (!own || !candidate.synced)) {
                continue;
            }
            int rank = candidate.script == language ? 0 : own ? 1 : candidate.script == Character.UnicodeScript.LATIN ? 2 : 4;
            if (candidate.mixed && !indic(candidate.script)) {
                rank += 2; // (Hindi and Punjabi lines together are put in one script, see oneScript)
            }
            if (!candidate.synced) {
                rank += 1; // words without times: only when nothing timed fits
            }
            double score = rank * 4_000 + candidate.lengthDiff + candidate.order * 30;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return NONE;
        }
        Log.d(TAG, "lyrics: " + best.item.optString("trackName") + " / " + best.item.optString("artistName") + ", "
                + best.script + (best.mixed ? " (mixed)" : "") + " for a song in " + language + ", length off by "
                + Math.round(best.lengthDiff) + " ms, of " + candidates.size() + " versions");
        Lyrics lyrics = best.synced ? parseSynced(best.lyrics) : parsePlain(best.lyrics);
        lyrics.lengthMs = Math.round(best.item.optDouble("duration", 0) * 1000);
        return lyrics;
    }

    private static void addCandidate(List<Candidate> candidates, JSONObject item, String title, long durationMs, int order) {
        if (item.optBoolean("instrumental", false) || !PhoneLyricsSources.sameTitle(item.optString("trackName"), title)) {
            return; // (a search also brings songs that only share a word with this one)
        }
        for (Candidate existing : candidates) {
            if (existing.item.optLong("id", -1) == item.optLong("id", -2)) {
                return; // already there (the exact match comes up in the search too)
            }
        }
        double seconds = item.optDouble("duration", 0);
        double diff = durationMs <= 0 || seconds <= 0 ? 4_000 : Math.abs(seconds * 1000 - durationMs);
        if (diff >= 8_000) {
            return; // another recording
        }
        String synced = item.optString("syncedLyrics", "");
        String plain = item.optString("plainLyrics", "");
        if (!TextUtils.isEmpty(synced)) {
            candidates.add(new Candidate(item, synced, true, diff, order));
        } else if (!TextUtils.isEmpty(plain)) {
            candidates.add(new Candidate(item, plain, false, diff, order));
        }
    }

    private static int countSynced(List<Candidate> candidates) {
        int count = 0;
        for (Candidate candidate : candidates) {
            if (candidate.synced) {
                count++;
            }
        }
        return count;
    }

    /**
     * The script the song's language is written in: the video's or the song's title when it's in a script of its
     * own; otherwise the one most versions of the lyrics are written in (Apple Music's and LRCLIB's), other than
     * Latin (a Hindi song: Devanagari; Punjabi: Gurmukhi; Korean: Hangul); Latin when they're all in Latin (an
     * English or Spanish song). Between Devanagari and Gurmukhi on equal votes, Devanagari: Hindi songs come in
     * Gurmukhi more often than Punjabi ones in Devanagari.
     */
    private static Character.UnicodeScript songScript(String videoTitle, String song, List<Candidate> candidates,
                                                      List<Object[]> answers, boolean english) {
        for (String name : new String[] {song, videoTitle}) {
            Character.UnicodeScript titleScript = (Character.UnicodeScript) mainScript(name)[0];
            if (titleScript != Character.UnicodeScript.LATIN && titleScript != Character.UnicodeScript.COMMON) {
                return indic(titleScript) ? readerScript() : titleScript;
            }
        }
        if (english) {
            // The words in Latin letters are English: an English song (a translation in another script isn't it)
            return Character.UnicodeScript.LATIN;
        }
        Map<Character.UnicodeScript, Integer> votes = new HashMap<>();
        Map<Character.UnicodeScript, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < answers.size(); i++) {
            Object[] answer = answers.get(i);
            vote(votes, firstSeen, (Character.UnicodeScript) answer[0], (Boolean) answer[1], i - answers.size());
        }
        for (Candidate candidate : candidates) {
            vote(votes, firstSeen, candidate.script, candidate.mixed, candidate.order);
        }
        Character.UnicodeScript best = Character.UnicodeScript.LATIN;
        int bestVotes = 0;
        for (Map.Entry<Character.UnicodeScript, Integer> entry : votes.entrySet()) {
            Character.UnicodeScript script = entry.getKey();
            int count = entry.getValue();
            boolean tie = count == bestVotes && bestVotes > 0;
            boolean wins = count > bestVotes || (tie && (siblings(script, best)
                    ? script == Character.UnicodeScript.DEVANAGARI : firstSeen.get(script) < firstSeen.get(best)));
            if (wins) {
                best = script;
                bestVotes = count;
            }
        }
        return indic(best) ? readerScript() : best;
    }

    /**
     * Hindi and Punjabi songs, in the script their listener reads: the sources write the same song in either
     * (a Hindi film song with Punjabi lines comes in Gurmukhi from one, Devanagari from another), so it's
     * Devanagari, or Gurmukhi on a phone set to Punjabi. The two map letter for letter (see oneScript).
     */
    static Character.UnicodeScript readerScript() {
        android.os.LocaleList locales = android.os.LocaleList.getDefault();
        for (int i = 0; i < locales.size(); i++) {
            if ("pa".equals(locales.get(i).getLanguage())) {
                return Character.UnicodeScript.GURMUKHI;
            }
        }
        return Character.UnicodeScript.DEVANAGARI;
    }

    private static final Set<String> ENGLISH = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "and", "you", "i", "to", "me", "my", "of", "in", "is", "it", "your", "that", "we", "on", "for",
            "be", "all", "with", "don't", "i'm", "just", "can", "so", "what", "when", "never", "like", "this", "are",
            "was", "know", "but", "now", "oh", "baby", "want", "got", "get", "go", "up", "down", "out", "if", "she",
            "he", "they", "her", "his", "our", "will", "feel", "let", "it's", "can't", "there", "every"));
    private static final Set<String> ROMANIZED = new java.util.HashSet<>(java.util.Arrays.asList(
            "hai", "hain", "main", "mein", "tu", "tum", "tera", "teri", "tere", "mera", "meri", "mere", "dil", "nahi",
            "nahin", "na", "ke", "ki", "ka", "se", "ko", "ye", "yeh", "woh", "jo", "bhi", "kya", "hum", "ho", "mujhe",
            "tujhe", "aaj", "sang", "da", "di", "de", "nu", "nu", "vich", "ve", "ni", "sanu", "tenu", "tainu", "ek",
            "koi", "kar", "kabhi", "kadi", "pyaar", "ishq", "rab", "yaar", "jaan", "sajna", "mahi", "aa", "oye"));

    /**
     * Words in Latin letters that read as English (its little words make a tenth of them, twice as many as Hindi's
     * or Punjabi's): a romanized Hindi song's would be "tu", "hai", "dil", "mera"
     */
    private static boolean english(List<String> texts) {
        int english = 0;
        int romanized = 0;
        int words = 0;
        for (String text : texts) {
            for (String word : text.toLowerCase(java.util.Locale.ROOT).replace('’', '\'').split("[^a-z']+")) {
                if (word.isEmpty()) {
                    continue;
                }
                words++;
                if (ENGLISH.contains(word)) {
                    english++;
                } else if (ROMANIZED.contains(word)) {
                    romanized++;
                }
            }
        }
        return words >= 30 && english >= words * 0.1 && english > romanized * 2;
    }

    private static void vote(Map<Character.UnicodeScript, Integer> votes, Map<Character.UnicodeScript, Integer> firstSeen,
                             Character.UnicodeScript script, boolean mixed, int order) {
        if (script == Character.UnicodeScript.LATIN || (mixed && !indic(script))) {
            return; // (Hindi and Punjabi lines mixed still say which of the two)
        }
        votes.put(script, votes.containsKey(script) ? votes.get(script) + 1 : 1);
        if (!firstSeen.containsKey(script)) {
            firstSeen.put(script, order);
        }
    }

    /** The script most of the letters are in, and whether a second script has a real share of them */
    private static Object[] mainScript(String text) {
        Map<Character.UnicodeScript, Integer> counts = new HashMap<>();
        int letters = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (!Character.isLetter(cp)) {
                    continue;
                }
                Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                counts.put(script, counts.containsKey(script) ? counts.get(script) + 1 : 1);
                letters++;
            }
        }
        Character.UnicodeScript main = Character.UnicodeScript.LATIN;
        int most = 0;
        for (Map.Entry<Character.UnicodeScript, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > most) {
                main = entry.getKey();
                most = entry.getValue();
            }
        }
        return new Object[] {main, letters > 0 && most < letters * 0.85};
    }

    /** LRCLIB's best record for this song, artist and length (within 2 s), or null */
    private static JSONObject get(String track, String artist, long durationMs) throws Exception {
        URL url = new URL("https://lrclib.net/api/get?track_name=" + URLEncoder.encode(track, "UTF-8")
                + "&artist_name=" + URLEncoder.encode(artist, "UTF-8") + "&duration=" + Math.round(durationMs / 1000.0));
        String body = fetch(url);
        return body != null ? new JSONObject(body) : null;
    }

    private static String fetch(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(6_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("User-Agent", "StreamTube");
        try {
            if (connection.getResponseCode() != 200) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                }
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray search(String query) throws Exception {
        String body = fetch(new URL("https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8")));
        return body != null ? new JSONArray(body) : null;
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");

    /** LRC text from a source other than LRCLIB */
    static Lyrics parseLrc(String text, String source) {
        Lyrics parsed = parseSynced(text);
        return new Lyrics(parsed.lines, parsed.synced, source);
    }

    private static Lyrics parseSynced(String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            Matcher matcher = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = 0;
                if (fraction != null) {
                    millis = Long.parseLong(fraction);
                    millis = fraction.length() == 1 ? millis * 100 : fraction.length() == 2 ? millis * 10 : millis;
                }
                times.add(minutes * 60_000 + seconds * 1000 + millis);
                end = matcher.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String words = raw.substring(end).trim();
            for (long time : times) {
                lines.add(new Line(time, words.isEmpty() ? "♪" : words));
            }
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return new Lyrics(lines, !lines.isEmpty());
    }

    private static Lyrics parsePlain(String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            lines.add(new Line(0, raw.trim()));
        }
        return new Lyrics(lines, false);
    }

    // Titles like "KING: Tu Mil Jaaye | Shah Rukh Khan | ..." or "Artist - Song (Official Music Video)"

    private static final Pattern NOISE = Pattern.compile(
            "[(\\[][^)\\]]*(official|video|lyric|audio|song|visuali[sz]er|4k|hd|full|from|feat|ft\\.|remaster|teaser|music)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);

    static List<String> trackNames(String title) {
        List<String> names = new ArrayList<>();
        if (TextUtils.isEmpty(title)) {
            return names;
        }
        String clean = NOISE.matcher(title).replaceAll(" ");
        clean = clean.replaceAll("(?i)\\b(ft\\.?|feat\\.?)\\s.*$", " ");
        clean = clean.replaceAll("#\\S+", " ");
        String first = clean.split("\\s[|｜/]\\s|\\|")[0].trim(); // the part before " | "
        if (first.contains(" - ")) {
            String[] parts = first.split(" - ", 2);
            add(names, parts[1]); // "Artist - Song"
            add(names, parts[0]);
        }
        if (first.contains(":")) {
            add(names, first.substring(first.indexOf(':') + 1)); // "Film: Song"
        }
        add(names, first);
        return names;
    }

    /**
     * The song's name for the player and the music bar: "Lloyd - You (Official Music Video) ft. Lil Wayne" → "You",
     * "Song | Film | Label" → "Song" (the artist shows below it anyway)
     */
    static String displayTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }
        List<String> names = trackNames(title);
        if (names.isEmpty()) {
            return title;
        }
        String whole = names.get(names.size() - 1);
        if (whole.contains(" - ")) {
            String song = whole.substring(whole.indexOf(" - ") + 3).trim();
            if (song.length() >= 2) {
                return song;
            }
        }
        return whole;
    }

    private static void add(List<String> names, String name) {
        String trimmed = name.replaceAll("\\s+", " ").trim();
        if (trimmed.length() >= 2 && !names.contains(trimmed)) {
            names.add(trimmed);
        }
    }

    static String cleanArtist(String author) {
        if (TextUtils.isEmpty(author)) {
            return null;
        }
        String artist = author.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)\\bvevo$", "").trim();
        int and = artist.toLowerCase().indexOf(" and ");
        if (and > 0) {
            artist = artist.substring(0, and); // "T-Series and 3 more"
        }
        return artist.trim();
    }
}
