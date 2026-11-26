package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Casting to a TV, the way the YouTube app does it: the TV's own YouTube app (or SmartTube on the TV) plays the video
 * from YouTube itself, in its full quality, and the phone is the remote.
 *
 * TVs are found two ways: on the same Wi-Fi (DIAL: the TV answers a search on the network, and is asked to open
 * YouTube with the video), or by the code the TV shows under Settings > Link with TV code (which also works off the
 * Wi-Fi). Either way the phone then joins the TV's "lounge" (YouTube's remote control channel) as a remote, and sends
 * it the video to play, where to start, play and pause. TVs linked once are remembered.
 *
 * SmartTube's engine has the TV's half of this (it receives casts); this is the phone's half.
 *
 * Google Cast TVs (Google TV, Android TV, Chromecast) are first cast to with no ads: their own media player plays
 * the video, fed by the phone (PhoneCastDirect, as NewTube does); the YouTube app is the fallback.
 */
final class PhoneCast {
    private static final String TAG = "PhoneCast";
    private static final String LOUNGE = "https://www.youtube.com/api/lounge";
    private static final String PREFS = "phone_cast";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** The Wi-Fi search runs on its own, so picking a TV doesn't wait for it to end */
    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** A TV: its name, and how to reach it (its lounge's screen id, or its DIAL address on the Wi-Fi) */
    static final class Tv {
        final String name;
        /** YouTube's id for the TV's screen (known once linked) */
        String screenId;
        String loungeToken;
        long tokenExpires;
        /** On the Wi-Fi: where to ask the TV to open YouTube (DIAL), null for TVs linked by code */
        String dialUrl;
        /** On the Wi-Fi, a Google Cast TV (Google TV, Android TV, Chromecast): its address */
        String castHost;
        int castPort;

        Tv(String name) {
            this.name = name;
        }

        boolean onWifi() {
            return dialUrl != null || castHost != null;
        }
    }

    interface Done {
        void done(boolean ok, String error);
    }

    interface Found {
        void found(List<Tv> tvs);
    }

    // region Casting now

    private static Tv sTv;
    private static String sSid;
    private static String sGsessionId;
    private static int sRid;
    private static int sOfs;
    private static final String DEVICE_ID = UUID.randomUUID().toString();
    private static String sVideoId;
    private static boolean sPlaying;
    /** Where the TV is (estimated: where it started, plus the time it has played since) */
    private static long sPositionMs;
    private static long sPositionAt;

    static boolean isCasting() {
        return sTv != null;
    }

    static String tvName() {
        return sTv != null ? sTv.name : null;
    }

    /** Casting with no ads: the TV's own media player plays the video, fed by the phone (see PhoneCastDirect) */
    private static boolean sDirect;
    private static Context sApp;

    static boolean isPlaying() {
        return sDirect ? PhoneCastDirect.isPlaying() : sPlaying;
    }

    static String videoId() {
        return sDirect ? PhoneCastDirect.videoId() : sVideoId;
    }

    /** Where the TV should be by now in the video (for picking up on the phone when casting stops) */
    static long positionMs() {
        if (sDirect) {
            return PhoneCastDirect.positionMs();
        }
        return sPositionMs + (sPlaying ? System.currentTimeMillis() - sPositionAt : 0);
    }

    /**
     * Starts casting this video, from this moment, to this TV. A Google Cast TV gets it with no ads (its own media
     * player, fed by the phone); if that can't play this video (live, or no stream the TV can play), or for any
     * other TV, the TV's YouTube (or SmartTube) app plays it.
     */
    static void start(Context context, Tv tv, String videoId, long positionMs, Done done) {
        Context app = context.getApplicationContext();
        sApp = app;
        stop();
        if (tv.castHost != null) {
            PhoneCastDirect.start(tv.castHost, tv.castPort, videoId, positionMs, PhoneCast::directEnded, (ok, error) -> {
                if (ok) {
                    sTv = tv;
                    sDirect = true;
                    Log.d(TAG, "casting " + videoId + " to " + tv.name + " with no ads");
                    remember(app, tv);
                    done.done(true, null);
                } else {
                    Log.d(TAG, "no ad-free cast to " + tv.name + " (" + error + "): the TV's YouTube app");
                    startYouTube(app, tv, videoId, positionMs, done);
                }
            });
            return;
        }
        startYouTube(app, tv, videoId, positionMs, done);
    }

    /** The TV stopped its player (closed on the TV, or the Wi-Fi was lost) */
    private static void directEnded() {
        if (sDirect) {
            sPositionMs = PhoneCastDirect.positionMs();
            sDirect = false;
            sTv = null;
            sPlaying = false;
        }
    }

    /** The TV's YouTube (or SmartTube) app plays it; the phone is its remote (the lounge) */
    private static void startYouTube(Context app, Tv tv, String videoId, long positionMs, Done done) {
        WORKER.execute(() -> {
            try {
                if (tv.castHost != null) {
                    // A Google Cast TV: YouTube opens on it and says which screen it is
                    String screenId = PhoneCastV2.openYouTube(wifiSockets(app), tv.castHost, tv.castPort);
                    if (!screenId.equals(tv.screenId)) {
                        tv.screenId = screenId;
                        tv.loungeToken = null;
                    }
                } else if (tv.dialUrl != null && tv.screenId == null) {
                    launchOnWifi(app, tv, videoId, positionMs); // opens YouTube on the TV with the video, and links it
                }
                ensureToken(tv);
                connect(tv);
                sTv = tv;
                play(videoId, positionMs);
                remember(app, tv);
                MAIN.post(() -> done.done(true, null));
            } catch (Exception e) {
                Log.d(TAG, "cast to " + tv.name + ": " + e);
                sTv = null;
                MAIN.post(() -> done.done(false, e.getMessage()));
            }
        });
    }

    /** Another video, while casting: the TV plays it */
    static void cast(String videoId, long positionMs) {
        if (sTv == null || videoId == null) {
            return;
        }
        if (sDirect) {
            Tv tv = sTv;
            PhoneCastDirect.load(videoId, positionMs, (ok, error) -> {
                if (!ok && sDirect && sTv == tv) {
                    // This one can't be cast with no ads (e.g. live): the TV's YouTube app plays it
                    Log.d(TAG, "no ad-free cast of " + videoId + " (" + error + "): the TV's YouTube app");
                    PhoneCastDirect.stop();
                    sDirect = false;
                    sTv = null;
                    startYouTube(sApp, tv, videoId, positionMs, (started, e) -> { });
                }
            });
            return;
        }
        WORKER.execute(() -> {
            try {
                play(videoId, positionMs);
            } catch (Exception e) {
                Log.d(TAG, "cast " + videoId + ": " + e);
            }
        });
    }

    static void togglePlay() {
        if (sTv == null) {
            return;
        }
        if (sDirect) {
            PhoneCastDirect.togglePlay();
            return;
        }
        boolean play = !sPlaying;
        sPositionMs = positionMs();
        sPositionAt = System.currentTimeMillis();
        sPlaying = play;
        WORKER.execute(() -> {
            try {
                command(play ? "play" : "pause", null);
            } catch (Exception e) {
                Log.d(TAG, (play ? "play" : "pause") + ": " + e);
            }
        });
    }

    /** Stops casting: the TV pauses and the phone leaves its lounge */
    static void stop() {
        if (sDirect) {
            sPositionMs = PhoneCastDirect.positionMs();
            sVideoId = PhoneCastDirect.videoId();
            sDirect = false;
            sTv = null;
            sPlaying = false;
            PhoneCastDirect.stop();
            return;
        }
        if (sTv == null) {
            return;
        }
        sPositionMs = positionMs();
        sPlaying = false;
        String sid = sSid;
        String gsid = sGsessionId;
        Tv tv = sTv;
        sTv = null;
        WORKER.execute(() -> {
            try {
                if (sid != null) {
                    command("pause", null, tv, sid, gsid);
                    // Leave the lounge
                    Request request = new Request.Builder()
                            .url(LOUNGE + "/bc/bind?RID=" + (++sRid) + "&VER=8&CVER=1&gsessionid=" + gsid + "&SID=" + sid
                                    + "&TYPE=terminate&loungeIdToken=" + tv.loungeToken)
                            .post(new FormBody.Builder().add("count", "0").build())
                            .build();
                    try (Response ignored = HTTP.newCall(request).execute()) {
                        // left
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "stop: " + e);
            }
        });
    }

    private static void play(String videoId, long positionMs) throws Exception {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("videoId", videoId);
        args.put("videoIds", videoId);
        args.put("currentTime", String.valueOf(Math.max(0, positionMs / 1000)));
        args.put("currentIndex", "0");
        args.put("listId", "");
        args.put("audioOnly", "false");
        args.put("params", "");
        args.put("playerParams", "");
        args.put("prioritizeMobileSenderPlaybackStateOnConnection", "true");
        command("setPlaylist", args);
        sVideoId = videoId;
        sPlaying = true;
        sPositionMs = positionMs;
        sPositionAt = System.currentTimeMillis();
        Log.d(TAG, "casting " + videoId + " at " + positionMs / 1000 + " s to " + (sTv != null ? sTv.name : "?"));
    }

    // endregion

    // region The lounge (YouTube's remote control channel)

    /** Links a TV by the code it shows (Settings > Link with TV code) */
    static void link(Context context, String code, Done done) {
        Context app = context.getApplicationContext();
        String digits = code.replaceAll("[^0-9]", "");
        WORKER.execute(() -> {
            try {
                Tv tv = screenFor(digits);
                if (tv == null) {
                    throw new Exception("No TV with that code");
                }
                remember(app, tv);
                MAIN.post(() -> done.done(true, tv.name));
            } catch (Exception e) {
                Log.d(TAG, "link: " + e);
                MAIN.post(() -> done.done(false, e.getMessage()));
            }
        });
    }

    /** The TV paired with this code (one the TV shows, or one the phone gave it when opening YouTube on it) */
    private static Tv screenFor(String pairingCode) throws Exception {
        Request request = new Request.Builder()
                .url(LOUNGE + "/pairing/get_screen")
                .post(new FormBody.Builder().add("pairing_code", pairingCode).build())
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            JSONObject screen = new JSONObject(response.body().string()).getJSONObject("screen");
            Tv tv = new Tv(screen.optString("name", "TV"));
            tv.screenId = screen.getString("screenId");
            tv.loungeToken = screen.getString("loungeToken");
            tv.tokenExpires = screen.optLong("expiration", 0);
            return tv;
        }
    }

    /** A fresh lounge token for the TV (they last a while; asked again when old) */
    private static void ensureToken(Tv tv) throws Exception {
        if (tv.screenId == null) {
            throw new Exception("The TV isn't linked");
        }
        if (tv.loungeToken != null && tv.tokenExpires > System.currentTimeMillis() + 60_000) {
            return;
        }
        Request request = new Request.Builder()
                .url(LOUNGE + "/pairing/get_lounge_token_batch")
                .post(new FormBody.Builder().add("screen_ids", tv.screenId).build())
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("The TV can't be reached");
            }
            JSONObject screen = new JSONObject(response.body().string()).getJSONArray("screens").getJSONObject(0);
            tv.loungeToken = screen.getString("loungeToken");
            tv.tokenExpires = screen.optLong("expiration", System.currentTimeMillis() + 3_600_000L);
        }
    }

    /** Joins the TV's lounge as a remote: the session's ids come back in the answer */
    private static void connect(Tv tv) throws Exception {
        sRid = (int) (Math.random() * 80_000) + 10_000;
        sOfs = 0;
        Request request = new Request.Builder()
                .url(LOUNGE + "/bc/bind?RID=" + sRid + "&VER=8&CVER=1&auth_failure_option=send_error")
                .post(new FormBody.Builder()
                        .add("app", "android-phone-13.14.55")
                        .add("mdx-version", "3")
                        .add("name", "StreamTube")
                        .add("id", DEVICE_ID)
                        .add("device", "REMOTE_CONTROL")
                        .add("capabilities", "que,dsdtr,atp")
                        .add("pairing_type", "cast")
                        .add("ui", "false")
                        .add("theme", "cl")
                        .add("loungeIdToken", tv.loungeToken)
                        .build())
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            Matcher sid = Pattern.compile("\\[\"c\",\"(.*?)\"").matcher(text);
            Matcher gsid = Pattern.compile("\\[\"S\",\"(.*?)\"\\]").matcher(text);
            if (!response.isSuccessful() || !sid.find()) {
                throw new Exception("The TV didn't answer (" + response.code() + ")");
            }
            sSid = sid.group(1);
            sGsessionId = gsid.find() ? gsid.group(1) : "";
        }
    }

    private static void command(String name, Map<String, String> args) throws Exception {
        command(name, args, sTv, sSid, sGsessionId);
    }

    private static void command(String name, Map<String, String> args, Tv tv, String sid, String gsid) throws Exception {
        if (tv == null || sid == null) {
            return;
        }
        FormBody.Builder body = new FormBody.Builder()
                .add("count", "1")
                .add("ofs", String.valueOf(sOfs++))
                .add("req0__sc", name);
        if (args != null) {
            for (Map.Entry<String, String> arg : args.entrySet()) {
                body.add("req0_" + arg.getKey(), arg.getValue());
            }
        }
        Request request = new Request.Builder()
                .url(LOUNGE + "/bc/bind?RID=" + (++sRid) + "&VER=8&CVER=1&gsessionid=" + gsid + "&SID=" + sid
                        + "&loungeIdToken=" + tv.loungeToken)
                .post(body.build())
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (response.code() == 400 || response.code() == 404 || response.code() == 410) {
                // The session ended (the TV restarted, or a long pause): join again and send it once more
                ensureToken(tv);
                connect(tv);
                if (args != null || !"pause".equals(name)) {
                    command(name, args, tv, sSid, sGsessionId);
                }
            }
        }
    }

    // endregion

    // region TVs on the Wi-Fi (DIAL for smart TVs, Google Cast for Google TV, Android TV and Chromecast)

    /** The Wi-Fi network (not mobile data, which a phone may use at the same time) */
    private static android.net.Network wifiNetwork(Context app) {
        android.net.ConnectivityManager manager = (android.net.ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        for (android.net.Network network : manager.getAllNetworks()) {
            android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return network;
            }
        }
        return null;
    }

    private static javax.net.SocketFactory wifiSockets(Context app) {
        android.net.Network wifi = wifiNetwork(app);
        return wifi != null ? wifi.getSocketFactory() : null;
    }

    /** For talking to TVs: over the Wi-Fi */
    private static OkHttpClient local(Context app) {
        javax.net.SocketFactory sockets = wifiSockets(app);
        return sockets != null ? HTTP.newBuilder().socketFactory(sockets).build() : HTTP;
    }

    /**
     * Looks for TVs on the Wi-Fi for a few seconds, two ways at once (smart TVs answer a DIAL search, Google Cast TVs
     * a Cast search); saved TVs come first, those found are added as they answer
     */
    static void find(Context context, Found found) {
        Context app = context.getApplicationContext();
        List<Tv> tvs = java.util.Collections.synchronizedList(saved(app));
        Runnable report = () -> {
            List<Tv> copy;
            synchronized (tvs) {
                copy = new ArrayList<>(tvs);
            }
            MAIN.post(() -> found.found(copy));
        };
        report.run();
        findCast(app, tvs, report);
        SEARCH.execute(() -> {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wifi != null ? wifi.createMulticastLock("streamtube-cast") : null;
            try {
                if (lock != null) {
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                for (String location : search(app)) {
                    Tv tv = describe(app, location);
                    if (tv != null) {
                        add(tvs, tv);
                        report.run();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "search: " + e);
            } finally {
                if (lock != null && lock.isHeld()) {
                    lock.release();
                }
            }
        });
    }

    /** A TV found: added, or merged with the saved one of the same name (it's on the Wi-Fi now) */
    private static void add(List<Tv> tvs, Tv tv) {
        synchronized (tvs) {
            for (Tv other : tvs) {
                if (other.name.equalsIgnoreCase(tv.name)) {
                    if (tv.dialUrl != null) {
                        other.dialUrl = tv.dialUrl;
                    }
                    if (tv.castHost != null) {
                        other.castHost = tv.castHost;
                        other.castPort = tv.castPort;
                    }
                    return;
                }
            }
            tvs.add(tv);
        }
    }

    /** Google Cast TVs, by their announcement on the Wi-Fi (_googlecast._tcp), for five seconds */
    private static void findCast(Context app, List<Tv> tvs, Runnable report) {
        android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            return;
        }
        java.util.concurrent.LinkedBlockingQueue<android.net.nsd.NsdServiceInfo> toResolve = new java.util.concurrent.LinkedBlockingQueue<>();
        android.net.nsd.NsdManager.DiscoveryListener listener = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.d(TAG, "cast search failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(android.net.nsd.NsdServiceInfo service) {
                toResolve.offer(service);
            }

            @Override
            public void onServiceLost(android.net.nsd.NsdServiceInfo service) {
            }
        };
        try {
            nsd.discoverServices("_googlecast._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            Log.d(TAG, "cast search: " + e);
            return;
        }
        // One at a time: Android resolves one service at once
        new Thread(() -> {
            long until = System.currentTimeMillis() + 5_000;
            try {
                while (System.currentTimeMillis() < until) {
                    android.net.nsd.NsdServiceInfo service = toResolve.poll(300, TimeUnit.MILLISECONDS);
                    if (service == null) {
                        continue;
                    }
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    nsd.resolveService(service, new android.net.nsd.NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(android.net.nsd.NsdServiceInfo info, int errorCode) {
                            done.countDown();
                        }

                        @Override
                        public void onServiceResolved(android.net.nsd.NsdServiceInfo info) {
                            Map<String, byte[]> attributes = info.getAttributes();
                            byte[] fn = attributes != null ? attributes.get("fn") : null;
                            Tv tv = new Tv(fn != null ? new String(fn, StandardCharsets.UTF_8) : info.getServiceName());
                            tv.castHost = info.getHost().getHostAddress();
                            tv.castPort = info.getPort() > 0 ? info.getPort() : 8009;
                            Log.d(TAG, "cast device " + tv.name + " at " + tv.castHost + ":" + tv.castPort);
                            add(tvs, tv);
                            report.run();
                            done.countDown();
                        }
                    });
                    done.await(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                // done
            } finally {
                try {
                    nsd.stopServiceDiscovery(listener);
                } catch (Exception ignored) {
                    // already stopped
                }
            }
        }, "cast-search").start();
    }

    /** Asks the Wi-Fi who can open apps (DIAL), for three seconds: the addresses of their descriptions */
    private static List<String> search(Context app) throws Exception {
        List<String> locations = new ArrayList<>();
        String message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n";
        try (MulticastSocket socket = new MulticastSocket()) {
            android.net.Network wifi = wifiNetwork(app);
            if (wifi != null) {
                wifi.bindSocket(socket); // the search goes out on the Wi-Fi, not mobile data
            }
            socket.setTimeToLive(4);
            socket.setSoTimeout(700);
            byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long until = System.currentTimeMillis() + 3_500;
            long nextSend = 0;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < until) {
                if (System.currentTimeMillis() >= nextSend) {
                    socket.send(new DatagramPacket(bytes, bytes.length, group, 1900)); // again: some TVs miss the first
                    nextSend = System.currentTimeMillis() + 1_000;
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String answer = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                Matcher location = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(answer);
                if (location.find() && !locations.contains(location.group(1))) {
                    locations.add(location.group(1));
                    Log.d(TAG, "dial device at " + location.group(1));
                }
            }
        }
        return locations;
    }

    /** A TV's description: its name, and where its apps are opened (only TVs with YouTube) */
    private static Tv describe(Context app, String location) {
        OkHttpClient http = local(app);
        try (Response response = http.newCall(new Request.Builder().url(location).build()).execute()) {
            String apps = response.header("Application-URL");
            String xml = response.body() != null ? response.body().string() : "";
            if (apps == null) {
                return null;
            }
            Matcher name = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.DOTALL).matcher(xml);
            Tv tv = new Tv(name.find() ? name.group(1).trim() : "TV");
            tv.dialUrl = (apps.endsWith("/") ? apps : apps + "/") + "YouTube";
            // Only TVs that have YouTube (or SmartTube answering as it)
            try (Response youtube = http.newCall(new Request.Builder().url(tv.dialUrl).build()).execute()) {
                return youtube.isSuccessful() ? tv : null;
            }
        } catch (Exception e) {
            Log.d(TAG, "describe " + location + ": " + e);
            return null;
        }
    }

    /**
     * Opens YouTube on the TV with the video (DIAL), handing it a code of the phone's: once the TV registers it, the
     * code leads to the TV's screen, and the phone joins its lounge as a remote
     */
    private static void launchOnWifi(Context app, Tv tv, String videoId, long positionMs) throws Exception {
        String code = UUID.randomUUID().toString();
        String body = "pairingCode=" + code + "&v=" + videoId + "&t=" + Math.max(0, positionMs / 1000) + "&theme=cl";
        Request request = new Request.Builder()
                .url(tv.dialUrl)
                .post(RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), body))
                .build();
        try (Response response = local(app).newCall(request).execute()) {
            if (response.code() >= 400) {
                throw new Exception("The TV didn't open YouTube (" + response.code() + ")");
            }
        }
        // The TV opens YouTube and registers the code: ask for the screen until it's there
        for (int i = 0; i < 20; i++) {
            Tv screen = screenFor(code);
            if (screen != null) {
                tv.screenId = screen.screenId;
                tv.loungeToken = screen.loungeToken;
                tv.tokenExpires = screen.tokenExpires;
                return;
            }
            Thread.sleep(700);
        }
        throw new Exception("The TV opened YouTube but didn't link");
    }

    // endregion

    // region TVs linked before

    static List<Tv> saved(Context context) {
        List<Tv> tvs = new ArrayList<>();
        String data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tvs", "[]");
        try {
            JSONArray list = new JSONArray(data);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                Tv tv = new Tv(item.optString("name", "TV"));
                tv.screenId = item.optString("screenId", null);
                if (tv.screenId != null) {
                    tvs.add(tv);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "saved: " + e);
        }
        return tvs;
    }

    private static synchronized void remember(Context context, Tv tv) {
        if (tv.screenId == null) {
            return;
        }
        List<Tv> tvs = saved(context);
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("name", tv.name).put("screenId", tv.screenId));
            for (Tv other : tvs) {
                if (!other.screenId.equals(tv.screenId) && list.length() < 8) {
                    list.put(new JSONObject().put("name", other.name).put("screenId", other.screenId));
                }
            }
        } catch (Exception e) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tvs", list.toString()).apply();
    }

    static void forget(Context context, Tv tv) {
        List<Tv> tvs = saved(context);
        JSONArray list = new JSONArray();
        try {
            for (Tv other : tvs) {
                if (!other.screenId.equals(tv.screenId)) {
                    list.put(new JSONObject().put("name", other.name).put("screenId", other.screenId));
                }
            }
        } catch (Exception e) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("tvs", list.toString()).apply();
    }

    // endregion

    private PhoneCast() {
    }
}
