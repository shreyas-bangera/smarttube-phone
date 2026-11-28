package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What's said in the playing video, line by line with its time: from the video's captions (its own, else YouTube's
 * automatic ones), in the phone's language when there are captions in it. Loaded once per video, on first use.
 */
final class PhoneTranscript {
    private static final String TAG = "PhoneVideoSearch";

    static final class Line {
        final long startMs;
        final String text;

        Line(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    interface Callback {
        /** lines: null when the video has no captions or they couldn't be loaded */
        void onTranscript(List<Line> lines);
    }

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static String sVideoId;
    private static List<MediaSubtitle> sTracks;
    private static List<Line> sLines;
    private static boolean sFailed;
    private static final List<Callback> sWaiting = new ArrayList<>();
    private static int sGeneration;

    private PhoneTranscript() {
    }

    /** Told when the playing video's transcript is ready, or known not to be (the player shows or hides Find) */
    private static Runnable sOnChange;

    static void setOnChange(Runnable onChange) {
        sOnChange = onChange;
    }

    /** The playing video has captions to search (known from its streams: nothing is downloaded for this) */
    static boolean available(String videoId) {
        return videoId != null && videoId.equals(sVideoId) && (sLines != null || pick(sTracks) != null);
    }

    /** The captions exist but couldn't be downloaded just now (YouTube busy or limiting): worth trying again */
    static boolean failedNow(String videoId) {
        return available(videoId) && sLines == null && sFailed;
    }

    /** Transcripts already downloaded (the last few videos): opening search again needs no download */
    private static final java.util.LinkedHashMap<String, List<Line>> sCache = new java.util.LinkedHashMap<String, List<Line>>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Line>> eldest) {
            return size() > 6;
        }
    };

    /**
     * The video's caption tracks arrived with its streams. Nothing is downloaded until search is opened (YouTube
     * limits caption downloads: one per opened video was too many)
     */
    static void setTracks(String videoId, List<MediaSubtitle> tracks) {
        if (TextUtils.equals(videoId, sVideoId)) {
            // The same video again (its streams reloaded): what's loaded or loading stays; tracks only fill a gap
            if ((sTracks == null || sTracks.isEmpty()) && tracks != null && !tracks.isEmpty()) {
                sTracks = tracks;
                sFailed = false;
                notifyChange();
            }
            return;
        }
        // Another video: anyone waiting on the old one hears "none" (never left loading)
        List<Callback> waiting = new ArrayList<>(sWaiting);
        sWaiting.clear();
        sVideoId = videoId;
        sTracks = tracks;
        sLines = videoId != null ? sCache.get(videoId) : null;
        sFailed = false;
        sGeneration++;
        for (Callback c : waiting) {
            c.onTranscript(null);
        }
        notifyChange();
    }

    private static void notifyChange() {
        if (sOnChange != null) {
            sOnChange.run();
        }
    }

    /** false: known to have nothing to search (no captions) */
    static boolean mayHave(String videoId) {
        if (!TextUtils.equals(videoId, sVideoId)) {
            return true; // not known yet: try
        }
        return sLines != null || (sTracks != null && !sTracks.isEmpty()); // (a failed download is tried again)
    }

    static void load(String videoId, Callback callback) {
        if (!TextUtils.equals(videoId, sVideoId) || sTracks == null || sTracks.isEmpty()) {
            callback.onTranscript(null);
            return;
        }
        sFailed = false; // a download that failed before is tried again
        if (sLines != null) {
            callback.onTranscript(sLines);
            return;
        }
        sWaiting.add(callback);
        if (sWaiting.size() > 1) {
            return; // already loading
        }
        MediaSubtitle track = pick(sTracks);
        int generation = sGeneration;
        String url = track != null ? track.getBaseUrl() : null;
        new Thread(() -> {
            List<Line> lines = url != null ? fetch(url) : null;
            sMain.post(() -> {
                if (generation != sGeneration) {
                    return; // another video meanwhile
                }
                sLines = lines != null && !lines.isEmpty() ? lines : null;
                sFailed = sLines == null;
                if (sLines != null) {
                    sCache.put(sVideoId, sLines);
                }
                List<Callback> waiting = new ArrayList<>(sWaiting);
                sWaiting.clear();
                for (Callback c : waiting) {
                    c.onTranscript(sLines);
                }
            });
        }, "transcript").start();
    }

    /** The phone's language, its own captions before automatic ones; else English; else the first */
    private static MediaSubtitle pick(List<MediaSubtitle> tracks) {
        if (tracks == null) {
            return null;
        }
        String phone = Locale.getDefault().getLanguage();
        MediaSubtitle best = null;
        int bestScore = -1;
        for (MediaSubtitle t : tracks) {
            if (t == null || TextUtils.isEmpty(t.getBaseUrl())) {
                continue;
            }
            String lang = t.getLanguageCode() != null ? t.getLanguageCode().toLowerCase(Locale.ROOT) : "";
            boolean auto = "asr".equals(t.getType());
            // A machine translation of the captions (tlang=…): YouTube limits those hard, so only as a last resort
            boolean translated = t.getBaseUrl().contains("tlang=");
            int score = translated ? -10 : 0;
            if (lang.startsWith(phone)) {
                score += 4;
            } else if (lang.startsWith("en")) {
                score += 2;
            }
            if (!auto) {
                score += 1;
            }
            if (score > bestScore) {
                best = t;
                bestScore = score;
            }
        }
        return best;
    }

    // region Loading

    private static List<Line> fetch(String baseUrl) {
        // json3: one event per caption line, with its start, and no styling to strip
        String json = download(withFormat(baseUrl, "json3"));
        List<Line> lines = json != null ? parseJson3(json) : null;
        if (lines == null || lines.isEmpty()) {
            String vtt = download(withFormat(baseUrl, "vtt"));
            lines = vtt != null ? parseVtt(vtt) : null;
        }
        Log.d(TAG, "transcript: " + (lines != null ? lines.size() + " lines" : "none") + " (json3 " + (json != null ? json.length() : -1) + " chars)");
        return lines;
    }

    private static String withFormat(String url, String format) {
        if (url.matches(".*[?&]fmt=[^&]*.*")) {
            return url.replaceAll("([?&]fmt=)[^&]*", "$1" + format);
        }
        return url + (url.contains("?") ? "&" : "?") + "fmt=" + format;
    }

    private static String download(String url) {
        String text = downloadOnce(url);
        if (text == null && sLastCode == 429) {
            try {
                Thread.sleep(2000); // YouTube is limiting: a short pause, then once more
            } catch (InterruptedException ignored) {
                return null;
            }
            text = downloadOnce(url);
        }
        return text;
    }

    private static volatile int sLastCode;

    private static String downloadOnce(String url) {
        sLastCode = 0;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            // (no browser user agent: the link was made for the app's own player, which fetches it as is)
            sLastCode = connection.getResponseCode();
            if (connection.getResponseCode() != 200) {
                Log.d(TAG, "captions: HTTP " + connection.getResponseCode() + " for " + url.replaceAll("[?&](signature|sig|pot|key)=[^&]*", ""));
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return text.isEmpty() ? null : text;
            }
        } catch (Exception e) {
            Log.d(TAG, "captions: " + e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** {"events":[{"tStartMs":1200,"segs":[{"utf8":"hello"},{"utf8":" world"}]}, …]} */
    static List<Line> parseJson3(String json) {
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) {
                return null;
            }
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                JSONArray segs = event != null ? event.optJSONArray("segs") : null;
                if (segs == null || event.optInt("aAppend", 0) == 1) {
                    continue; // a line break of the automatic captions
                }
                StringBuilder text = new StringBuilder();
                for (int j = 0; j < segs.length(); j++) {
                    JSONObject seg = segs.optJSONObject(j);
                    if (seg != null) {
                        text.append(seg.optString("utf8", ""));
                    }
                }
                add(lines, event.optLong("tStartMs", 0), text.toString());
            }
            return lines;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern VTT_TIME = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d{2})\\.(\\d{3})\\s*-->");

    /** WebVTT: cues of a time line and text lines (automatic captions repeat the previous line: kept once) */
    static List<Line> parseVtt(String vtt) {
        List<Line> lines = new ArrayList<>();
        String[] rows = vtt.split("\\r?\\n");
        long start = -1;
        StringBuilder text = new StringBuilder();
        for (String row : rows) {
            Matcher m = VTT_TIME.matcher(row);
            if (m.find()) {
                if (start >= 0) {
                    add(lines, start, text.toString());
                }
                long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
                start = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
                text.setLength(0);
            } else if (start >= 0) {
                if (row.trim().isEmpty()) {
                    add(lines, start, text.toString());
                    start = -1;
                    text.setLength(0);
                } else {
                    text.append(text.length() > 0 ? " " : "").append(row.replaceAll("<[^>]*>", ""));
                }
            }
        }
        if (start >= 0) {
            add(lines, start, text.toString());
        }
        return lines;
    }

    private static void add(List<Line> lines, long startMs, String raw) {
        String text = raw.replace('\n', ' ').replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        if (!lines.isEmpty()) {
            Line last = lines.get(lines.size() - 1);
            if (last.text.equals(text) || last.text.endsWith(text)) {
                return; // the same words again (rolling automatic captions)
            }
        }
        lines.add(new Line(startMs, text));
    }

    // endregion

    /** Lines holding the words (any case), in time order */
    static List<Integer> find(List<Line> lines, String query) {
        List<Integer> hits = new ArrayList<>();
        if (lines == null || TextUtils.isEmpty(query)) {
            return hits;
        }
        String q = query.toLowerCase(Locale.getDefault());
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).text.toLowerCase(Locale.getDefault()).contains(q)) {
                hits.add(i);
            }
        }
        return Collections.unmodifiableList(hits);
    }
}
