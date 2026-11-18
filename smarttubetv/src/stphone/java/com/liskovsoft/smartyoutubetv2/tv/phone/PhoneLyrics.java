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
    /**
     * The lyrics for this video, the way MeeTube and Flow find them (see PhoneLyricsSources): YouTube Music names
     * the song, lrc.red says which recording it is; Apple Music's timed lyrics for it (lrc.red, then BetterLyrics),
     * else LRCLIB's (in the song's language, see lrclib), else YouTube Music's words. Then the timing is checked
     * against this video (see onThisVideo).
     */
    private static Lyrics find(String videoId, String title, String author, long durationMs) throws Exception {
        // In music, the recording plays for a music video (see PhoneMusic.streamFor): its timing is the one to match
        String recording = PhoneMusic.wantsMusic() ? PhoneLyricsSources.audioVersion(videoId) : null;
        PhoneLyricsSources.Track track = PhoneLyricsSources.track(recording != null ? recording : videoId);
        String song = track != null ? track.title : displayTitle(title);
        String artist = track != null && !TextUtils.isEmpty(track.artist) ? track.artist : cleanArtist(author);
        long videoMs = recording != null && track != null && track.seconds > 0 ? track.seconds * 1000L
                : durationMs > 0 ? durationMs : track != null ? track.seconds * 1000L : 0;
        boolean audioTrack = recording != null || (track != null && track.audioTrack);
        Log.d(TAG, "song: " + song + " / " + artist + (audioTrack ? " (art track)" : "") + ", " + videoMs / 1000 + " s");

        if (!TextUtils.isEmpty(artist)) {
            PhoneLyricsSources.Recording isrc = PhoneLyricsSources.identify(song, artist, (int) (videoMs / 1000));
            Lyrics apple = PhoneLyricsSources.lrcRed(isrc);
            int seconds = isrc != null ? isrc.seconds : (int) (videoMs / 1000);
            if (apple == null || !apple.synced) {
                apple = PhoneLyricsSources.betterLyrics(song, artist, seconds);
                if (apple != null && !mentionsTitle(apple, song)) {
                    // Matched by name only (no recording to go by): another song came back (an English one for a
                    // Hindi song); the song's own title would be in its words
                    Log.d(TAG, "BetterLyrics answered another song for " + song);
                    apple = null;
                }
            }
            if (apple != null && apple.synced) {
                apple.lengthMs = seconds * 1000L;
                return onThisVideo(videoId, apple, videoMs, audioTrack);
            }
        }

        Lyrics lrclib = lrclib(song, artist, videoMs);
        if (lrclib.synced) {
            return onThisVideo(videoId, lrclib, videoMs, audioTrack);
        }
        Lyrics words = track != null ? PhoneLyricsSources.youTubeMusicLyrics(track.lyricsId) : null;
        return words != null ? words : lrclib;
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
     * Hindi film songs come with lines in two scripts: Devanagari, and Gurmukhi where the words are Punjabi. Read as
     * one song, they're all put in Devanagari (the two scripts map letter for letter). Lyrics wholly in Gurmukhi (a
     * Punjabi song) stay as they are.
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
        int both = devanagari + gurmukhi;
        if (gurmukhi == 0 || devanagari < both * 0.1) {
            return lyrics;
        }
        List<Line> lines = new ArrayList<>();
        for (Line line : lyrics.lines) {
            lines.add(new Line(line.timeMs, gurmukhiToDevanagari(line.text)));
        }
        Lyrics converted = new Lyrics(lines, lyrics.synced, lyrics.source);
        converted.lengthMs = lyrics.lengthMs;
        converted.trusted = lyrics.trusted;
        return converted;
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
    private static boolean mentionsTitle(Lyrics lyrics, String title) {
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

    private static Lyrics lrclib(String title, String artist, long durationMs) throws Exception {
        List<String> tracks = trackNames(title);
        List<Candidate> candidates = new ArrayList<>();
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
        if (candidates.isEmpty()) {
            return NONE;
        }

        Character.UnicodeScript language = songScript(title, candidates);
        Candidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            int rank = candidate.script == language ? 0 : candidate.script == Character.UnicodeScript.LATIN ? 1 : 3;
            if (candidate.mixed) {
                rank += 2;
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

    /** The script the song's language is written in (see find) */
    private static Character.UnicodeScript songScript(String title, List<Candidate> candidates) {
        Character.UnicodeScript titleScript = (Character.UnicodeScript) mainScript(title)[0];
        if (titleScript != Character.UnicodeScript.LATIN && titleScript != Character.UnicodeScript.COMMON) {
            return titleScript;
        }
        Map<Character.UnicodeScript, Integer> votes = new HashMap<>();
        Map<Character.UnicodeScript, Integer> firstSeen = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.mixed || candidate.script == Character.UnicodeScript.LATIN) {
                continue;
            }
            votes.put(candidate.script, votes.containsKey(candidate.script) ? votes.get(candidate.script) + 1 : 1);
            if (!firstSeen.containsKey(candidate.script)) {
                firstSeen.put(candidate.script, candidate.order);
            }
        }
        Character.UnicodeScript best = Character.UnicodeScript.LATIN;
        int bestVotes = 0;
        for (Map.Entry<Character.UnicodeScript, Integer> entry : votes.entrySet()) {
            int count = entry.getValue();
            if (count > bestVotes || (count == bestVotes && firstSeen.get(entry.getKey()) < firstSeen.get(best))) {
                best = entry.getKey();
                bestVotes = count;
            }
        }
        return best;
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
