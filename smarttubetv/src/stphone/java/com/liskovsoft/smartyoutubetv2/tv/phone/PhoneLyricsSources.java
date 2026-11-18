package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Where the music player's lyrics come from besides LRCLIB, the way MeeTube and Flow find them:
 * <ul>
 *     <li>YouTube Music: what the song really is (its own title, artists and length for this video, and whether the
 *     video is the recording itself, an "art track"), and its own lyrics (words only).</li>
 *     <li>lrc.red: which recording that is (its ISRC and true length), and Apple Music's timed lyrics for it.</li>
 *     <li>BetterLyrics: Apple Music's timed lyrics, by the song's name, artist and length.</li>
 *     <li>SponsorBlock: the parts of a music video with no music (an intro, a scene), so lyrics timed to the song
 *     can be put on the video's clock.</li>
 * </ul>
 * All of them answer within a few seconds or not at all (the lyrics are no use after the second chorus).
 */
final class PhoneLyricsSources {
    private static final String TAG = "PhoneLyrics";
    private static final String WEB_REMIX = "{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20250922.03.00\",\"hl\":\"en\"}}";
    private static final int TIMEOUT_MS = 6_000;
    private static final Pattern ARTISTS = Pattern.compile("\\s*(?:,|&|/|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    private PhoneLyricsSources() {
    }

    /** What YouTube Music calls this video */
    static final class Track {
        String title;
        String artist;
        int seconds;
        /** An art track: the recording itself, so the recording's timing is this video's */
        boolean audioTrack;
        String lyricsId;
        String lyricsParams;
    }

    /** A recording lrc.red knows */
    static final class Recording {
        String isrc;
        int seconds;
    }

    // region YouTube Music

    static Track track(String videoId) {
        try {
            JSONObject root = new JSONObject(post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
                    "{\"context\":" + WEB_REMIX + ",\"videoId\":\"" + videoId + "\"}"));
            List<JSONObject> found = new ArrayList<>();
            findAll(root, "playlistPanelVideoRenderer", found);
            Track track = null;
            for (JSONObject item : found) {
                if (videoId.equals(item.optString("videoId"))) {
                    track = new Track();
                    track.title = text(item.optJSONObject("title"));
                    // "Artist, Artist & Artist • album or views • year or likes"
                    track.artist = text(item.optJSONObject("longBylineText")).split(" \u2022 ")[0].trim();
                    track.seconds = seconds(text(item.optJSONObject("lengthText")));
                    track.audioTrack = item.toString().contains("MUSIC_VIDEO_TYPE_ATV");
                    break;
                }
            }
            if (track == null || TextUtils.isEmpty(track.title)) {
                return null;
            }
            found.clear();
            findAll(root, "tabRenderer", found);
            for (JSONObject tab : found) {
                JSONObject endpoint = tab.optJSONObject("endpoint");
                JSONObject browse = endpoint != null ? endpoint.optJSONObject("browseEndpoint") : null;
                // "unselectable": YouTube Music has no lyrics for this song
                if (browse != null && !tab.optBoolean("unselectable") && browse.toString().contains("MUSIC_PAGE_TYPE_TRACK_LYRICS")) {
                    track.lyricsId = browse.optString("browseId", null);
                    track.lyricsParams = browse.optString("params", null);
                }
            }
            return track;
        } catch (Throwable e) {
            Log.d(TAG, "YouTube Music: " + e);
            return null;
        }
    }

    private static final java.util.Map<String, String> AUDIO_VERSIONS = java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static final String NONE = "";

    /**
     * The song's recording (YouTube Music's "art track") for a music video: what YouTube Music plays in Song mode.
     * Null when the video is the recording, or YouTube Music doesn't pair it with one. Remembered per video.
     */
    static String audioVersion(String videoId) {
        String known = AUDIO_VERSIONS.get(videoId);
        if (known != null) {
            return known.isEmpty() ? null : known;
        }
        String found = null;
        try {
            JSONObject root = new JSONObject(post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
                    "{\"context\":" + WEB_REMIX + ",\"videoId\":\"" + videoId + "\",\"isAudioOnly\":true}"));
            List<JSONObject> pairs = new ArrayList<>();
            findAll(root, "playlistPanelVideoWrapperRenderer", pairs);
            for (JSONObject pair : pairs) {
                List<JSONObject> twins = new ArrayList<>();
                JSONObject primary = pair.optJSONObject("primaryRenderer");
                if (primary != null && primary.optJSONObject("playlistPanelVideoRenderer") != null) {
                    twins.add(primary.optJSONObject("playlistPanelVideoRenderer"));
                }
                JSONArray counterparts = pair.optJSONArray("counterpart");
                for (int i = 0; counterparts != null && i < counterparts.length(); i++) {
                    JSONObject counterpart = counterparts.optJSONObject(i);
                    JSONObject renderer = counterpart != null ? counterpart.optJSONObject("counterpartRenderer") : null;
                    if (renderer != null && renderer.optJSONObject("playlistPanelVideoRenderer") != null) {
                        twins.add(renderer.optJSONObject("playlistPanelVideoRenderer"));
                    }
                }
                boolean ours = false;
                for (JSONObject twin : twins) {
                    ours |= videoId.equals(twin.optString("videoId"));
                }
                if (!ours) {
                    continue;
                }
                for (JSONObject twin : twins) {
                    String id = twin.optString("videoId");
                    if (!id.isEmpty() && !id.equals(videoId) && twin.toString().contains("MUSIC_VIDEO_TYPE_ATV")) {
                        found = id;
                    }
                }
                break;
            }
            AUDIO_VERSIONS.put(videoId, found != null ? found : NONE);
        } catch (Throwable e) {
            Log.d(TAG, "audio version of " + videoId + ": " + e);
        }
        return found;
    }

    /** YouTube Music's own lyrics (words only, no timing), or null */
    static PhoneLyrics.Lyrics youTubeMusicLyrics(String lyricsId) {
        if (lyricsId == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(post("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    "{\"context\":" + WEB_REMIX + ",\"browseId\":\"" + lyricsId + "\"}"));
            List<JSONObject> found = new ArrayList<>();
            findAll(root, "musicDescriptionShelfRenderer", found);
            if (found.isEmpty()) {
                return null;
            }
            String text = text(found.get(0).optJSONObject("description")).trim();
            List<PhoneLyrics.Line> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                lines.add(new PhoneLyrics.Line(0, line.trim()));
            }
            return lines.size() < 4 ? null : new PhoneLyrics.Lyrics(lines, false, "YouTube Music");
        } catch (Throwable e) {
            Log.d(TAG, "YouTube Music lyrics: " + e);
            return null;
        }
    }

    // endregion

    // region Apple Music's lyrics: lrc.red and BetterLyrics

    /** Which recording this is: the closest in length of lrc.red's recordings with this title and artist */
    static Recording identify(String title, String artist, int videoSeconds) {
        try {
            JSONArray hits = new JSONObject(get("https://lrc.red/search.json?q=" + enc(title + " " + firstArtist(artist))))
                    .optJSONArray("hits");
            Recording best = null;
            double bestOff = Double.MAX_VALUE;
            for (int i = 0; hits != null && i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                String isrc = hit != null ? hit.optString("isrc") : "";
                if (isrc.isEmpty() || !sameArtist(hit.optString("artist"), artist) || !sameTitle(hit.optString("title"), title)) {
                    continue;
                }
                double duration = hit.optDouble("duration", 0);
                double off = videoSeconds > 0 ? Math.abs(duration - videoSeconds) : 0;
                if (off < bestOff) {
                    bestOff = off;
                    best = new Recording();
                    best.isrc = isrc;
                    best.seconds = (int) Math.round(duration);
                }
            }
            return best;
        } catch (Throwable e) {
            Log.d(TAG, "lrc.red search: " + e);
            return null;
        }
    }

    static PhoneLyrics.Lyrics lrcRed(Recording recording) {
        if (recording == null) {
            return null;
        }
        try {
            return enough(parseTtml(get("https://lrc.red/s/" + recording.isrc + ".ttml"), "Apple Music (lrc.red)"));
        } catch (Throwable e) {
            Log.d(TAG, "lrc.red: " + e);
            return null;
        }
    }

    static PhoneLyrics.Lyrics betterLyrics(String title, String artist, int seconds) {
        try {
            String ttml = new JSONObject(get("https://lyrics-api.boidu.dev/getLyrics?s=" + enc(title) + "&a=" + enc(artist)
                    + (seconds > 0 ? "&d=" + seconds : ""))).optString("ttml");
            return ttml.isEmpty() ? null : enough(parseTtml(ttml, "Apple Music (BetterLyrics)"));
        } catch (Throwable e) {
            Log.d(TAG, "BetterLyrics: " + e);
            return null;
        }
    }

    /**
     * Apple Music's TTML: a {@code <p begin>} per sung line, its answering vocal (ttm:role x-bg) after it in
     * brackets, translations and romanisations left out
     */
    static PhoneLyrics.Lyrics parseTtml(String ttml, String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        harden(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        harden(factory, "http://xml.org/sax/features/external-general-entities", false);
        harden(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        NodeList paragraphs = factory.newDocumentBuilder().parse(new InputSource(new StringReader(ttml))).getElementsByTagName("p");
        List<PhoneLyrics.Line> lines = new ArrayList<>();
        boolean timed = true;
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            StringBuilder lead = new StringBuilder();
            StringBuilder backing = new StringBuilder();
            collect(paragraph, lead, backing, false);
            String text = squash(lead);
            String echo = squash(backing);
            if (!echo.isEmpty()) {
                text = (text + " " + (echo.startsWith("(") ? echo : "(" + echo + ")")).trim();
            }
            if (text.isEmpty()) {
                continue;
            }
            long begin = ttmlTime(paragraph.getAttribute("begin"));
            timed &= begin >= 0;
            lines.add(new PhoneLyrics.Line(Math.max(0, begin), text));
        }
        if (timed) {
            Collections.sort(lines, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        }
        return new PhoneLyrics.Lyrics(lines, timed && !lines.isEmpty(), source);
    }

    private static void collect(Node node, StringBuilder lead, StringBuilder backing, boolean inBacking) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String role = attribute((Element) child, "ttm:role");
                if (!"x-translation".equals(role) && !"x-roman".equals(role)) {
                    collect(child, lead, backing, inBacking || "x-bg".equals(role));
                }
            } else if (child.getNodeType() == Node.TEXT_NODE) {
                (inBacking ? backing : lead).append(child.getNodeValue());
            }
        }
    }

    /** A prefixed attribute ("ttm:role"): Android's parser files it under its local name */
    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (!value.isEmpty()) {
            return value;
        }
        String local = name.substring(name.indexOf(':') + 1);
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (name.equals(attribute.getNodeName()) || local.equals(attribute.getNodeName()) || local.equals(attribute.getLocalName())) {
                return attribute.getNodeValue();
            }
        }
        return "";
    }

    /** "12.345", "1:02.345", "00:01:02.345", "62.3s" or "62345ms" in ms; -1 when absent or unreadable */
    static long ttmlTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        String raw = value.trim();
        try {
            if (raw.endsWith("ms")) {
                return (long) Double.parseDouble(raw.substring(0, raw.length() - 2));
            }
            double seconds = 0;
            for (String part : (raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw).split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void harden(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // not this parser's
        }
    }

    // endregion

    // region SponsorBlock: the parts of a music video with no music

    /** [start, end] in ms of each "non-music" part SponsorBlock users marked in this video, in order; empty if none */
    static List<long[]> nonMusicParts(String videoId) {
        List<long[]> parts = new ArrayList<>();
        try {
            JSONArray segments = new JSONArray(get("https://sponsor.ajay.app/api/skipSegments?videoID=" + enc(videoId)
                    + "&category=music_offtopic"));
            for (int i = 0; i < segments.length(); i++) {
                JSONArray segment = segments.getJSONObject(i).optJSONArray("segment");
                if (segment != null && segment.length() == 2) {
                    parts.add(new long[] {Math.round(segment.getDouble(0) * 1000), Math.round(segment.getDouble(1) * 1000)});
                }
            }
            Collections.sort(parts, (a, b) -> Long.compare(a[0], b[0]));
        } catch (Throwable e) {
            // 404: nothing marked
        }
        return parts;
    }

    /** Lyrics timed to the song, on the video's clock: every non-music part before a line pushes it later */
    static PhoneLyrics.Lyrics onVideoClock(PhoneLyrics.Lyrics lyrics, List<long[]> parts) {
        List<PhoneLyrics.Line> lines = new ArrayList<>();
        for (PhoneLyrics.Line line : lyrics.lines) {
            long time = line.timeMs;
            for (long[] part : parts) {
                if (part[0] <= time) {
                    time += part[1] - part[0];
                } else {
                    break;
                }
            }
            lines.add(new PhoneLyrics.Line(time, line.text));
        }
        return new PhoneLyrics.Lyrics(lines, lyrics.synced, lyrics.source);
    }

    static long total(List<long[]> parts) {
        long total = 0;
        for (long[] part : parts) {
            total += part[1] - part[0];
        }
        return total;
    }

    // endregion

    // region As Flow asks them (github.com/Flow-Tube/Flow, data/lyrics): by video id first, then by name

    private static final String ANDROID_MUSIC_VERSION = "7.21.50";
    private static final String ANDROID_MUSIC = "{\"client\":{\"clientName\":\"ANDROID_MUSIC\",\"clientVersion\":\""
            + ANDROID_MUSIC_VERSION + "\",\"osName\":\"Android\",\"osVersion\":\"14\",\"androidSdkVersion\":34,"
            + "\"hl\":\"en\",\"gl\":\"US\"}}";

    /**
     * YouTube Music's own timed lyrics: the lyrics tab of this very video asked for by the Android YouTube Music
     * app, so they can't be another song's, and are timed to this recording. Song rows have them; music video rows
     * send their lines without times, which reads as none.
     */
    static PhoneLyrics.Lyrics youTubeMusicTimed(Track track, long lengthMs) {
        if (track == null || track.lyricsId == null) {
            return null;
        }
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "com.google.android.apps.youtube.music/" + ANDROID_MUSIC_VERSION + " (Linux; U; Android 14) gzip");
            headers.put("X-YouTube-Client-Name", "21");
            headers.put("X-YouTube-Client-Version", ANDROID_MUSIC_VERSION);
            headers.put("X-Goog-Api-Format-Version", "1");
            headers.put("X-Origin", "https://music.youtube.com");
            headers.put("Referer", "https://music.youtube.com/");
            JSONObject body = new JSONObject();
            body.put("context", new JSONObject(ANDROID_MUSIC));
            body.put("browseId", track.lyricsId);
            if (!TextUtils.isEmpty(track.lyricsParams)) {
                body.put("params", track.lyricsParams);
            }
            JSONObject root = new JSONObject(request("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    body.toString(), headers));
            List<JSONObject> models = new ArrayList<>();
            findAll(root, "timedLyricsModel", models);
            JSONObject data = models.isEmpty() ? null : models.get(0).optJSONObject("lyricsData");
            JSONArray cues = data != null ? data.optJSONArray("timedLyricsData") : null;
            List<PhoneLyrics.Line> lines = new ArrayList<>();
            for (int i = 0; cues != null && i < cues.length(); i++) {
                JSONObject cue = cues.optJSONObject(i);
                JSONObject range = cue != null ? cue.optJSONObject("cueRange") : null;
                String text = cue != null ? cue.optString("lyricLine").trim() : "";
                if (range == null || !range.has("startTimeMilliseconds") || text.replace("♪", "").trim().isEmpty()) {
                    continue; // (a music video's lines come without times; a ♪ row only marks a gap)
                }
                lines.add(new PhoneLyrics.Line(range.optLong("startTimeMilliseconds"), text));
            }
            PhoneLyrics.Lyrics lyrics = timed(lines, "YouTube Music");
            if (lyrics != null) {
                lyrics.lengthMs = lengthMs;
            }
            return lyrics;
        } catch (Throwable e) {
            Log.d(TAG, "YouTube Music timed lyrics: " + e);
            return null;
        }
    }

    /** lrc.red's search, as Flow asks it: the same song and artist, word-timed files first, then the closest length */
    static PhoneLyrics.Lyrics lrcRedSearch(String title, String artist, int seconds) {
        try {
            String query = "track=" + enc(title) + (TextUtils.isEmpty(artist) ? "" : "&artist=" + enc(firstArtist(artist)))
                    + (seconds > 0 ? "&duration=" + seconds : "");
            JSONArray results = new JSONObject(get("https://lrc.red/api/v1?" + query)).optJSONArray("results");
            JSONObject best = null;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject hit = results.optJSONObject(i);
                String url = hit != null ? hit.optString("lyricsUrl") : "";
                if (!url.startsWith("https://") || !sameTitle(hit.optString("track_name"), title)
                        || (!TextUtils.isEmpty(artist) && !sameArtist(hit.optString("artist_name"), artist))) {
                    continue;
                }
                int length = hit.optInt("duration", 0);
                int off = seconds > 0 && length > 0 ? Math.abs(length - seconds) : 5;
                if (off > 10) {
                    continue; // another recording
                }
                double score = off + ("word".equalsIgnoreCase(hit.optString("timing_type")) ? 0 : 0.5);
                if (score < bestScore) {
                    best = hit;
                    bestScore = score;
                }
            }
            if (best == null) {
                return null;
            }
            PhoneLyrics.Lyrics lyrics = enough(parseTtml(get(best.optString("lyricsUrl")), "Apple Music (lrc.red)"));
            if (lyrics != null) {
                lyrics.lengthMs = best.optInt("duration", seconds) * 1000L;
            }
            return lyrics;
        } catch (Throwable e) {
            Log.d(TAG, "lrc.red: " + e);
            return null;
        }
    }

    /** LyricsPlus (lyricsplus.binimum.org): Apple Music's and others' timed lyrics, by name, artist and length */
    static PhoneLyrics.Lyrics lyricsPlus(String title, String artist, int seconds) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(artist)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(get("https://lyricsplus.binimum.org/v2/lyrics/get?title=" + enc(title)
                    + "&artist=" + enc(firstArtist(artist)) + (seconds > 0 ? "&duration=" + seconds : "")));
            JSONArray items = root.optJSONArray("lyrics");
            List<PhoneLyrics.Line> lines = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String text = item.optString("text").trim();
                JSONArray syllables = item.optJSONArray("syllabus");
                if (syllables != null && syllables.length() > 0) {
                    StringBuilder lead = new StringBuilder();
                    for (int j = 0; j < syllables.length(); j++) {
                        JSONObject syllable = syllables.optJSONObject(j);
                        if (syllable != null && !syllable.optBoolean("isBackground")) {
                            lead.append(syllable.optString("text"));
                        }
                    }
                    if (lead.toString().trim().length() > 0) {
                        text = lead.toString().trim();
                    }
                }
                if (!text.isEmpty()) {
                    lines.add(new PhoneLyrics.Line(item.optLong("time"), squash(text)));
                }
            }
            PhoneLyrics.Lyrics lyrics = timed(lines, "LyricsPlus");
            if (lyrics != null && !PhoneLyrics.mentionsTitle(lyrics, title)) {
                Log.d(TAG, "LyricsPlus answered another song for " + title);
                return null;
            }
            if (lyrics != null) {
                lyrics.lengthMs = seconds * 1000L;
            }
            return lyrics;
        } catch (Throwable e) {
            Log.d(TAG, "LyricsPlus: " + e);
            return null;
        }
    }

    /**
     * SimpMusic's lyrics (api-lyrics.simpmusic.org), filed by YouTube video id; of its rows for the id, the one
     * within 5 s of the song's length (another upload's would drift), the best voted
     */
    static PhoneLyrics.Lyrics simpMusic(String videoId, int seconds) {
        try {
            JSONObject root = new JSONObject(get("https://api-lyrics.simpmusic.org/v1/" + enc(videoId)));
            JSONArray rows = root.optJSONArray("data");
            JSONObject best = null;
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                int length = row.optInt("durationSeconds", 0) > 0 ? row.optInt("durationSeconds") : row.optInt("duration", 0);
                if (seconds > 0 && Math.abs(length - seconds) > 5) {
                    continue;
                }
                if (best == null || row.optInt("vote", 0) > best.optInt("vote", 0)) {
                    best = row;
                }
            }
            if (best == null) {
                return null;
            }
            String synced = best.optString("syncedLyrics", "");
            PhoneLyrics.Lyrics lyrics = synced.isEmpty() || "null".equals(synced) ? null
                    : PhoneLyrics.parseLrc(synced, "SimpMusic");
            lyrics = lyrics != null && lyrics.synced ? enough(lyrics) : null;
            if (lyrics != null) {
                int length = best.optInt("durationSeconds", 0) > 0 ? best.optInt("durationSeconds") : best.optInt("duration", seconds);
                lyrics.lengthMs = length * 1000L;
            }
            return lyrics;
        } catch (Throwable e) {
            Log.d(TAG, "SimpMusic: " + e);
            return null;
        }
    }

    /** Timed lines, enough of them and spread over the song (Flow's check: at least 10 s from first to last) */
    private static PhoneLyrics.Lyrics timed(List<PhoneLyrics.Line> lines, String source) {
        if (lines.size() < 4) {
            return null;
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        if (lines.get(lines.size() - 1).timeMs - lines.get(0).timeMs < 10_000) {
            return null;
        }
        return new PhoneLyrics.Lyrics(lines, true, source);
    }

    // endregion

    // region Helpers

    private static PhoneLyrics.Lyrics enough(PhoneLyrics.Lyrics lyrics) {
        return lyrics != null && lyrics.lines.size() >= 4 ? lyrics : null;
    }

    static boolean sameArtist(String theirs, String ours) {
        String all = letters(ours);
        for (String name : ARTISTS.split(theirs)) {
            String one = letters(name);
            if (!one.isEmpty() && all.contains(one)) {
                return true;
            }
        }
        String first = letters(ARTISTS.split(ours)[0]);
        return !first.isEmpty() && letters(theirs).contains(first);
    }

    static boolean sameTitle(String theirs, String ours) {
        List<String> names = PhoneLyrics.trackNames(theirs);
        String a = letters(names.isEmpty() ? theirs : names.get(0));
        String b = letters(ours);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static String letters(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String firstArtist(String artist) {
        return ARTISTS.split(artist)[0].trim();
    }

    static int seconds(String length) {
        int seconds = 0;
        for (String part : length.trim().split(":")) {
            try {
                seconds = seconds * 60 + Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return seconds;
    }

    static void findAll(Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String name = keys.next();
                Object value = object.opt(name);
                if (name.equals(key) && value instanceof JSONObject) {
                    out.add((JSONObject) value);
                }
                findAll(value, key, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                findAll(array.opt(i), key, out);
            }
        }
    }

    /** An InnerTube text: its runs joined, or its simpleText */
    static String text(JSONObject text) {
        if (text == null) {
            return "";
        }
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) {
            return text.optString("simpleText");
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                joined.append(run.optString("text"));
            }
        }
        return joined.toString();
    }

    private static String squash(CharSequence text) {
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static String enc(String text) throws Exception {
        return URLEncoder.encode(text, "UTF-8");
    }

    static String get(String url) throws Exception {
        return request(url, null);
    }

    private static String post(String url, String json) throws Exception {
        return request(url, json);
    }

    private static String request(String address, String json) throws Exception {
        return request(address, json, null);
    }

    private static String request(String address, String json, java.util.Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "StreamTube");
        connection.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        try {
            if (json != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(json.getBytes("UTF-8"));
                }
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code);
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

    // endregion
}
