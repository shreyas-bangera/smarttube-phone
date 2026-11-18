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

    private static Lyrics find(String title, String author, long durationMs) throws Exception {
        String artist = cleanArtist(author);
        Set<String> queries = new LinkedHashSet<>();
        for (String track : trackNames(title)) {
            if (!TextUtils.isEmpty(artist)) {
                queries.add(track + " " + artist);
            }
            queries.add(track);
        }
        JSONObject plainOnly = null;
        for (String query : queries) {
            JSONArray results = search(query);
            JSONObject best = null;
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                double seconds = item.optDouble("duration", 0);
                boolean lengthFits = durationMs <= 0 || seconds <= 0 || Math.abs(seconds * 1000 - durationMs) < 8_000;
                if (!lengthFits || item.optBoolean("instrumental", false)) {
                    continue;
                }
                if (!TextUtils.isEmpty(item.optString("syncedLyrics", ""))) {
                    best = item;
                    break;
                }
                if (plainOnly == null && !TextUtils.isEmpty(item.optString("plainLyrics", ""))) {
                    plainOnly = item;
                }
            }
            if (best != null) {
                Log.d(TAG, "synced lyrics: " + best.optString("trackName") + " / " + best.optString("artistName"));
                return parseSynced(best.optString("syncedLyrics"));
            }
        }
        if (plainOnly != null) {
            return parsePlain(plainOnly.optString("plainLyrics"));
        }
        return NONE;
    }

    private static JSONArray search(String query) throws Exception {
        URL url = new URL("https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8"));
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
            return new JSONArray(body.toString());
        } finally {
            connection.disconnect();
        }
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
