package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.liskovsoft.smartyoutubetv2.tv.phone.cast.CastProxyServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Casting to smart TVs that aren't Google Cast (Samsung, LG, Sony, Philips, Hisense...), with nothing installed on
 * the TV: their built-in media player for the home network (a DLNA / UPnP "media renderer") plays the video, as it
 * would a film from a computer. The phone hands it one file with video and sound, relayed from YouTube by the phone
 * (the same relay as Google Cast), and controls it: play, pause, where it is, stop.
 */
final class PhoneCastDlna {
    private static final String TAG = "PhoneCast";
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MediaType XML = MediaType.parse("text/xml; charset=\"utf-8\"");

    private static OkHttpClient sHttp;
    private static String sControl;
    private static CastProxyServer sProxy;
    private static String sVideoId;
    private static boolean sPlaying;
    private static long sPositionMs;
    private static long sPositionAt;
    private static int sGeneration;
    private static Runnable sOnEnded;
    private static int sStoppedPolls;

    private PhoneCastDlna() {
    }

    static boolean isActive() {
        return sControl != null;
    }

    static boolean isPlaying() {
        return sPlaying;
    }

    static String videoId() {
        return sVideoId;
    }

    static long positionMs() {
        return sPositionMs + (sPlaying ? SystemClock.elapsedRealtime() - sPositionAt : 0);
    }

    // region Finding the TV's player

    /** The TV's media player, from its description: its name and where it takes commands (null if it has none) */
    static PhoneCast.Tv describe(OkHttpClient http, String location) {
        try (Response response = http.newCall(new Request.Builder().url(location).build()).execute()) {
            String xml = response.body() != null ? response.body().string() : "";
            Matcher services = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL).matcher(xml);
            String control = null;
            while (services.find()) {
                String service = services.group(1);
                if (service.contains(AV_TRANSPORT)) {
                    Matcher url = Pattern.compile("<controlURL>(.*?)</controlURL>", Pattern.DOTALL).matcher(service);
                    if (url.find()) {
                        control = url.group(1).trim();
                    }
                    break;
                }
            }
            if (control == null) {
                return null;
            }
            Matcher base = Pattern.compile("<URLBase>(.*?)</URLBase>", Pattern.DOTALL).matcher(xml);
            okhttp3.HttpUrl root = okhttp3.HttpUrl.parse(base.find() ? base.group(1).trim() : location);
            okhttp3.HttpUrl absolute = root != null ? root.resolve(control) : null;
            if (absolute == null) {
                return null;
            }
            Matcher name = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.DOTALL).matcher(xml);
            PhoneCast.Tv tv = new PhoneCast.Tv(name.find() ? unescape(name.group(1).trim()) : "TV");
            tv.dlnaControl = absolute.toString();
            return tv;
        } catch (Exception e) {
            Log.d(TAG, "describe renderer " + location + ": " + e);
            return null;
        }
    }

    // endregion

    /** Plays the video on the TV's player. done: true once the TV took it */
    static void start(OkHttpClient http, String control, String videoId, long positionMs, Runnable onEnded, PhoneCast.Done done) {
        stop();
        sHttp = http;
        sOnEnded = onEnded;
        int generation = ++sGeneration;
        WORKER.execute(() -> {
            CastProxyServer proxy = new CastProxyServer();
            try {
                proxy.start();
                if (proxy.getBaseUrl() == null) {
                    throw new IllegalStateException("no Wi-Fi address");
                }
                PhoneCastDirect.Media media = PhoneCastDirect.prepare(proxy, videoId, true);
                proxy.setUserAgent(media.userAgent);
                send(http, control, videoId, media, proxy, positionMs);
            } catch (Exception e) {
                proxy.stop();
                Log.d(TAG, "dlna cast of " + videoId + ": " + e);
                MAIN.post(() -> done.done(false, e.getMessage() != null ? e.getMessage() : e.toString()));
                return;
            }
            MAIN.post(() -> {
                if (generation != sGeneration) {
                    proxy.stop();
                    return;
                }
                sControl = control;
                sProxy = proxy;
                sVideoId = videoId;
                started(positionMs);
                poll(generation);
                done.done(true, null);
            });
        });
    }

    /** Another video while casting */
    static void load(String videoId, long positionMs, PhoneCast.Done done) {
        String control = sControl;
        CastProxyServer proxy = sProxy;
        OkHttpClient http = sHttp;
        if (control == null || proxy == null) {
            done.done(false, "not casting");
            return;
        }
        int generation = ++sGeneration;
        WORKER.execute(() -> {
            try {
                PhoneCastDirect.Media media = PhoneCastDirect.prepare(proxy, videoId, true);
                proxy.setUserAgent(media.userAgent);
                send(http, control, videoId, media, proxy, positionMs);
            } catch (Exception e) {
                Log.d(TAG, "dlna cast of " + videoId + ": " + e);
                MAIN.post(() -> done.done(false, e.getMessage() != null ? e.getMessage() : e.toString()));
                return;
            }
            MAIN.post(() -> {
                if (generation != sGeneration || sControl == null) {
                    return;
                }
                sVideoId = videoId;
                started(positionMs);
                poll(generation);
                done.done(true, null);
            });
        });
    }

    static void togglePlay() {
        String control = sControl;
        OkHttpClient http = sHttp;
        if (control == null) {
            return;
        }
        boolean play = !sPlaying;
        sPositionMs = positionMs();
        sPositionAt = SystemClock.elapsedRealtime();
        sPlaying = play;
        WORKER.execute(() -> {
            try {
                if (play) {
                    action(http, control, "Play", "<Speed>1</Speed>");
                } else {
                    action(http, control, "Pause", "");
                }
            } catch (Exception e) {
                Log.d(TAG, "dlna " + (play ? "play" : "pause") + ": " + e);
            }
        });
    }

    static void stop() {
        String control = sControl;
        CastProxyServer proxy = sProxy;
        OkHttpClient http = sHttp;
        sControl = null;
        sProxy = null;
        sPlaying = false;
        sOnEnded = null;
        sGeneration++;
        if (control != null || proxy != null) {
            WORKER.execute(() -> {
                try {
                    if (control != null) {
                        action(http, control, "Stop", "");
                    }
                } catch (Exception e) {
                    Log.d(TAG, "dlna stop: " + e);
                }
                if (proxy != null) {
                    proxy.stop();
                }
            });
        }
    }

    // region Commands (SOAP, as the UPnP standard has them)

    private static void send(OkHttpClient http, String control, String videoId, PhoneCastDirect.Media media,
                             CastProxyServer proxy, long positionMs) throws Exception {
        String url = media.url(proxy, videoId);
        String title = media.title != null ? media.title : "StreamTube";
        String metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + escape(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\">"
                + escape(url) + "</res></item></DIDL-Lite>";
        try {
            action(http, control, "Stop", "");
        } catch (Exception ignored) {
            // nothing was playing
        }
        action(http, control, "SetAVTransportURI",
                "<CurrentURI>" + escape(url) + "</CurrentURI><CurrentURIMetaData>" + escape(metadata) + "</CurrentURIMetaData>");
        action(http, control, "Play", "<Speed>1</Speed>");
        Log.d(TAG, "dlna: playing " + videoId + " (" + media.what + ")");
        if (positionMs > 5_000) {
            // Where it was on the phone: once the TV has started it (TVs refuse a seek before that)
            for (int i = 0; i < 10; i++) {
                Thread.sleep(800);
                try {
                    action(http, control, "Seek", "<Unit>REL_TIME</Unit><Target>" + time(positionMs) + "</Target>");
                    break;
                } catch (Exception e) {
                    // not ready yet
                }
            }
        }
    }

    private static String action(OkHttpClient http, String control, String name, String args) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + name + " xmlns:u=\"" + AV_TRANSPORT + "\"><InstanceID>0</InstanceID>" + args
                + "</u:" + name + "></s:Body></s:Envelope>";
        Request request = new Request.Builder()
                .url(control)
                .header("SOAPACTION", "\"" + AV_TRANSPORT + "#" + name + "\"")
                .post(RequestBody.create(XML, body))
                .build();
        try (Response response = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build().newCall(request).execute()) {
            String answer = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new Exception(name + " refused (" + response.code() + ")");
            }
            return answer;
        }
    }

    // endregion

    // region Following the TV's player

    private static void started(long positionMs) {
        sPlaying = true;
        sPositionMs = Math.max(0, positionMs);
        sPositionAt = SystemClock.elapsedRealtime();
        sStoppedPolls = 0;
    }

    /** Every 2 s: is it playing, and where (the TV only says when asked) */
    private static void poll(int generation) {
        MAIN.postDelayed(() -> {
            String control = sControl;
            OkHttpClient http = sHttp;
            if (generation != sGeneration || control == null) {
                return;
            }
            WORKER.execute(() -> {
                String state = null;
                long position = -1;
                try {
                    Matcher s = Pattern.compile("<CurrentTransportState>(.*?)</CurrentTransportState>")
                            .matcher(action(http, control, "GetTransportInfo", ""));
                    state = s.find() ? s.group(1) : null;
                    Matcher p = Pattern.compile("<RelTime>(.*?)</RelTime>").matcher(action(http, control, "GetPositionInfo", ""));
                    position = p.find() ? parseTime(p.group(1)) : -1;
                } catch (Exception e) {
                    Log.d(TAG, "dlna status: " + e);
                }
                String finalState = state;
                long finalPosition = position;
                MAIN.post(() -> {
                    if (generation != sGeneration || sControl == null) {
                        return;
                    }
                    if (finalState != null) {
                        boolean playing = finalState.equals("PLAYING") || finalState.equals("TRANSITIONING");
                        sPlaying = playing;
                        // Stopped for a while (ended, or stopped on the TV): casting is over
                        sStoppedPolls = finalState.equals("STOPPED") || finalState.equals("NO_MEDIA_PRESENT") ? sStoppedPolls + 1 : 0;
                        if (sStoppedPolls >= 4) {
                            Runnable onEnded = sOnEnded;
                            stop();
                            if (onEnded != null) {
                                onEnded.run();
                            }
                            return;
                        }
                    }
                    if (finalPosition >= 0) {
                        sPositionMs = finalPosition;
                        sPositionAt = SystemClock.elapsedRealtime();
                    }
                    poll(generation);
                });
            });
        }, 2_000);
    }

    // endregion

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static long parseTime(String text) {
        try {
            String[] parts = text.trim().split(":");
            if (parts.length != 3) {
                return -1;
            }
            double seconds = Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Double.parseDouble(parts[2]);
            return Math.round(seconds * 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
