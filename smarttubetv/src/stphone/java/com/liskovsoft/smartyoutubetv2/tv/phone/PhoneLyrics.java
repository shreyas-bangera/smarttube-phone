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

        Lyrics(List<Line> lines, boolean synced) {
            this.lines = lines;
            this.synced = synced;
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
                lyrics = find(title, author, durationMs);
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
    private static Lyrics find(String title, String author, long durationMs) throws Exception {
        String artist = cleanArtist(author);
        List<String> tracks = trackNames(title);
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;

        if (!TextUtils.isEmpty(artist) && durationMs > 0) {
            for (String track : tracks) {
                JSONObject exact = get(track, artist, durationMs);
                if (exact != null) {
                    addCandidate(candidates, exact, durationMs, order++);
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
                addCandidate(candidates, results.getJSONObject(i), durationMs, order++);
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
        return best.synced ? parseSynced(best.lyrics) : parsePlain(best.lyrics);
    }

    private static void addCandidate(List<Candidate> candidates, JSONObject item, long durationMs, int order) {
        if (item.optBoolean("instrumental", false)) {
            return;
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
