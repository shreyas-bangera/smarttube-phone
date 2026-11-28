package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * New videos from the channels the account is subscribed to. YouTube doesn't hand its notification inbox to
 * third-party apps, so the app keeps its own: from time to time, in the background, it looks at the Subscriptions feed,
 * and what wasn't there before becomes an Android notification and a row on the Notifications tab
 * ({@link PhoneNotificationsActivity}). One request per check, only while signed in, only with a connection.
 */
final class PhoneNewVideos {
    private static final String TAG = "PhoneNewVideos";
    private static final String PREFS = "phone_new_videos";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_SEEN = "seen";
    private static final String KEY_INBOX = "inbox";
    private static final String KEY_UNREAD = "unread";
    private static final String WORK_NAME = "phone_new_videos";
    private static final String CHANNEL = "new_videos";
    private static final String GROUP = "org.smarttube.phone.NEW_VIDEOS";
    private static final int SUMMARY_ID = 5200;
    private static final long CHECK_EVERY_MINUTES = 30;
    private static final String WORK_NAME_V2 = "phone_new_videos_v2";
    /** Every 30 minutes, the channels' own feeds (Shorts too): a new name, so the hourly v2 is replaced */
    private static final String WORK_NAME_V3 = "phone_new_videos_v3";
    private static final int MAX_SEEN = 3000;
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_CHANNELS_AT = "channels_at";
    private static final long CHANNELS_MAX_AGE_MS = 12 * 60 * 60 * 1000L;
    private static final long RECENT_MARGIN_MS = 60 * 60 * 1000L;
    private static final long MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L;
    /** Channel feeds fetched together (SmartTube's RSS reader takes up to 100) */
    private static final int RSS_CHUNK = 50;
    private static final int MAX_INBOX = 100;
    /** More new videos than this in one check: the rest are on the Notifications tab only */
    private static final int MAX_POSTED = 4;

    /** A row of the inbox */
    static final class Entry {
        final Video video;
        final long foundAtMs;

        Entry(Video video, long foundAtMs) {
            this.video = video;
            this.foundAtMs = foundAtMs;
        }
    }

    private PhoneNewVideos() {
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    // region Each channel's bell, as the YouTube app: All (Shorts too), Personalised (videos and live: the default), None

    static final int BELL_NONE = 0;
    static final int BELL_PERSONALIZED = 1;
    static final int BELL_ALL = 2;
    private static final String KEY_MUTED = "muted_channels";
    private static final String KEY_ALL = "all_channels";

    static int bell(Context context, String channelId) {
        if (TextUtils.isEmpty(channelId)) {
            return BELL_PERSONALIZED;
        }
        if (set(context, KEY_MUTED).contains(channelId)) {
            return BELL_NONE;
        }
        return set(context, KEY_ALL).contains(channelId) ? BELL_ALL : BELL_PERSONALIZED;
    }

    private static final String KEY_SHORTS = "shorts";

    /** Settings › Notifications › Shorts: Personalised channels' Shorts too (on by default) */
    static boolean isShortsOn(Context context) {
        return prefs(context).getBoolean(KEY_SHORTS, true);
    }

    static void setShortsOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SHORTS, on).apply();
    }

    /** A Short of this channel is notified: the bell on All, or on Personalised with Shorts on */
    static boolean wantsShorts(Context context, String channelId) {
        int bell = bell(context, channelId);
        return bell == BELL_ALL || (bell == BELL_PERSONALIZED && isShortsOn(context));
    }

    static boolean isBellOn(Context context, String channelId) {
        return bell(context, channelId) != BELL_NONE;
    }

    static void setBell(Context context, String channelId, boolean on) {
        setBell(context, channelId, on ? BELL_PERSONALIZED : BELL_NONE);
    }

    static void setBell(Context context, String channelId, int level) {
        if (TextUtils.isEmpty(channelId)) {
            return;
        }
        Set<String> muted = set(context, KEY_MUTED);
        Set<String> all = set(context, KEY_ALL);
        muted.remove(channelId);
        all.remove(channelId);
        if (level == BELL_NONE) {
            muted.add(channelId);
        } else if (level == BELL_ALL) {
            all.add(channelId);
        }
        prefs(context).edit().putStringSet(KEY_MUTED, muted).putStringSet(KEY_ALL, all).apply();
    }

    private static Set<String> set(Context context, String key) {
        return new java.util.HashSet<>(prefs(context).getStringSet(key, java.util.Collections.emptySet()));
    }

    // endregion

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        schedule(context);
    }

    /** Android's battery saving lets this app's background check run on time (not delayed or skipped) */
    static boolean isUnrestricted(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Asks Android to let the check run in the background (its own dialog), else opens its battery settings */
    static void askUnrestricted(android.app.Activity activity) {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            if (!isUnrestricted(activity)) {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "battery dialog: " + e);
        }
        try {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            Log.e(TAG, "battery settings: " + e);
        }
    }

    /** Keeps the background check running (or stops it when it's switched off). Safe to call at every start. */
    static void schedule(Context context) {
        try {
            WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
            manager.cancelUniqueWork(WORK_NAME); // the first version (every 2 hours, account feed)
            manager.cancelUniqueWork(WORK_NAME_V2); // the second (hourly)
            if (!isEnabled(context)) {
                manager.cancelUniqueWork(WORK_NAME_V3);
                return;
            }
            Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(CheckWorker.class, CHECK_EVERY_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build();
            manager.enqueueUniquePeriodicWork(WORK_NAME_V3, ExistingPeriodicWorkPolicy.KEEP, request);
            // The background check reads the channels' feeds: remember the channels while the account is at hand
            Context app = context.getApplicationContext();
            com.liskovsoft.sharedutils.rx.RxHelper.runAsync(() -> {
                try {
                    if (YouTubeServiceManager.instance().getSignInService().isSigned()) {
                        refreshChannels(app, false);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "channels: " + e);
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "schedule: " + e); // no WorkManager on this device: the tab still fills while the app is open
        }
    }

    static int unread(Context context) {
        return prefs(context).getInt(KEY_UNREAD, 0);
    }

    static void markRead(Context context) {
        prefs(context).edit().putInt(KEY_UNREAD, 0).apply();
        NotificationManagerCompat.from(context).cancel(SUMMARY_ID);
    }

    static List<Entry> inbox(Context context) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_INBOX, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Video video = Video.fromString(item.optString("video", null));
                if (video != null && video.videoId != null) {
                    result.add(new Entry(video, item.optLong("at")));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "inbox: " + e);
        }
        return result;
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY_INBOX).putInt(KEY_UNREAD, 0).apply();
    }

    /**
     * Looks at the Subscriptions feed once (blocking: not for the UI thread). Returns how many videos are new.
     * The first look only remembers what's there, so signing in doesn't end in a pile of notifications.
     */
    static synchronized int check(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app)) {
            return 0;
        }
        List<Entry2> items = latestUploads(app);
        Log.d(TAG, "latest uploads: " + items.size());
        if (items.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long lastCheck = prefs(app).getLong(KEY_LAST_CHECK, now);

        SharedPreferences prefs = prefs(app);
        Set<String> seen = new LinkedHashSet<>();
        String stored = prefs.getString(KEY_SEEN, "");
        if (!TextUtils.isEmpty(stored)) {
            for (String id : stored.split(",")) {
                seen.add(id);
            }
        }

        List<Video> fresh = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (Entry2 item : items) {
            String id = item.videoId;
            if (id == null) {
                continue;
            }
            current.add(id);
            // Only what came out since the last look (a channel subscribed to meanwhile doesn't bring its older videos).
            // Shorts too, unless they're switched off (Settings › Notifications) or the channel's bell says otherwise.
            long published = item.published;
            boolean recent = published <= 0 || (published > lastCheck - RECENT_MARGIN_MS && published > now - MAX_AGE_MS);
            if (!seen.contains(id) && recent && (!item.isShort || wantsShorts(app, item.channelId))) {
                fresh.add(item.toVideo());
            }
        }

        // Newest ids first, the oldest remembered ones fall off the end
        Set<String> merged = new LinkedHashSet<>(current);
        merged.addAll(seen);
        StringBuilder joined = new StringBuilder();
        int count = 0;
        for (String id : merged) {
            if (count++ >= MAX_SEEN) {
                break;
            }
            joined.append(joined.length() > 0 ? "," : "").append(id);
        }
        boolean seeded = prefs.getBoolean(KEY_SEEDED, false);
        prefs.edit().putString(KEY_SEEN, joined.toString()).putBoolean(KEY_SEEDED, true).putLong(KEY_LAST_CHECK, now).apply();
        Log.d(TAG, "new: " + fresh.size() + (seeded ? "" : " (first look: only remembered)"));
        if (!seeded || fresh.isEmpty()) {
            return 0;
        }

        try {
            JSONArray inbox = new JSONArray();
            for (Video video : fresh) {
                inbox.put(new JSONObject().put("video", video.toString()).put("at", now));
            }
            JSONArray old = new JSONArray(prefs.getString(KEY_INBOX, "[]"));
            for (int i = 0; i < old.length() && inbox.length() < MAX_INBOX; i++) {
                inbox.put(old.get(i));
            }
            prefs.edit().putString(KEY_INBOX, inbox.toString()).putInt(KEY_UNREAD, prefs.getInt(KEY_UNREAD, 0) + fresh.size()).apply();
        } catch (Exception e) {
            Log.e(TAG, "store: " + e);
        }
        // Phone notifications only for the channels whose bell is on (all of them shown on the Notifications tab)
        List<Video> ringing = new ArrayList<>();
        for (Video video : fresh) {
            if (isBellOn(app, video.channelId)) {
                ringing.add(video);
            }
        }
        Log.d(TAG, "notified: " + ringing.size() + " of " + fresh.size());
        if (!ringing.isEmpty()) {
            post(app, ringing);
        }
        return fresh.size();
    }

    /** A new upload as the channel's feed lists it */
    static final class Entry2 {
        String videoId;
        String channelId;
        String title;
        String author;
        long published;
        boolean isShort;

        Video toVideo() {
            Video video = new Video();
            video.videoId = videoId;
            video.channelId = channelId;
            video.title = title;
            video.author = author;
            video.secondTitle = author; // (what the Notifications tab keeps, and reads the channel's name from)
            video.cardImageUrl = "https://i.ytimg.com/vi/" + videoId + (isShort ? "/oar2.jpg" : "/hqdefault.jpg");
            video.isShorts = isShort;
            if (isShort) {
                video.badge = "Shorts";
            }
            return video;
        }
    }

    /**
     * The latest uploads of the subscribed channels: each channel's public feed (RSS), read straight from YouTube a
     * few at a time. It needs no sign-in, so it also works when Android wakes the app in the background, and a Short
     * is known by its link (/shorts/). The channel list is taken from the account while the app is open; without one
     * yet, the account's Subscriptions feed.
     */
    private static List<Entry2> latestUploads(Context app) {
        boolean signedIn = false;
        try {
            signedIn = YouTubeServiceManager.instance().getSignInService().isSigned();
            if (signedIn) {
                refreshChannels(app, false);
            }
        } catch (Throwable e) {
            Log.e(TAG, "channels: " + e);
        }
        List<Entry2> items = new ArrayList<>();
        String[] channels = channels(app);
        if (channels.length > 0) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
            List<java.util.concurrent.Future<List<Entry2>>> jobs = new ArrayList<>();
            for (String channel : channels) {
                jobs.add(pool.submit(() -> readFeed(channel)));
            }
            for (java.util.concurrent.Future<List<Entry2>> job : jobs) {
                try {
                    List<Entry2> found = job.get(30, TimeUnit.SECONDS);
                    if (found != null) {
                        items.addAll(found);
                    }
                } catch (Exception e) {
                    // that channel next time; the others still count
                }
            }
            pool.shutdownNow();
        } else if (signedIn) {
            try {
                MediaGroup feed = YouTubeServiceManager.instance().getContentService().getSubscriptions();
                if (feed != null && feed.getMediaItems() != null) {
                    for (MediaItem item : feed.getMediaItems()) {
                        if (item == null || item.getVideoId() == null || item.isUpcoming()) {
                            continue;
                        }
                        Entry2 entry = new Entry2();
                        entry.videoId = item.getVideoId();
                        entry.channelId = item.getChannelId();
                        entry.title = item.getTitle();
                        entry.author = item.getAuthor();
                        entry.published = item.getPublishedDate();
                        entry.isShort = item.isShorts();
                        items.add(entry);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "subscriptions: " + e);
            }
        }
        java.util.Collections.sort(items, (a, b) -> Long.compare(b.published, a.published));
        return items;
    }

    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile("<entry>(.*?)</entry>", java.util.regex.Pattern.DOTALL);

    /** One channel's feed: its latest 15 uploads */
    private static List<Entry2> readFeed(String channelId) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            String xml;
            try (java.io.InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                xml = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            List<Entry2> entries = new ArrayList<>();
            java.util.regex.Matcher m = ENTRY.matcher(xml);
            while (m.find()) {
                String e = m.group(1);
                Entry2 entry = new Entry2();
                entry.videoId = tag(e, "yt:videoId");
                entry.channelId = tag(e, "yt:channelId");
                if (entry.channelId == null) {
                    entry.channelId = channelId;
                }
                entry.title = tag(e, "title");
                entry.author = tag(e, "name");
                String published = tag(e, "published");
                try {
                    entry.published = published != null ? java.time.OffsetDateTime.parse(published).toInstant().toEpochMilli() : 0;
                } catch (Exception ignored) {
                    entry.published = 0;
                }
                entry.isShort = e.contains("/shorts/" + entry.videoId);
                if (entry.videoId != null) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String tag(String xml, String name) {
        int start = xml.indexOf("<" + name + ">");
        if (start < 0) {
            return null;
        }
        start += name.length() + 2;
        int end = xml.indexOf("</" + name + ">", start);
        if (end < 0) {
            return null;
        }
        return xml.substring(start, end).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /** Remembers the subscribed channels (the account is only at hand in the app): at most twice a day, or now */
    static void refreshChannels(Context context, boolean force) {
        SharedPreferences prefs = prefs(context);
        long age = System.currentTimeMillis() - prefs.getLong(KEY_CHANNELS_AT, 0);
        // KEY_CHANNELS_V: lists saved before the channel ids were read from the web guide are taken again
        boolean current = prefs.getInt("channels_v", 0) == 2;
        if (!force && current && age < CHANNELS_MAX_AGE_MS && !TextUtils.isEmpty(prefs.getString(KEY_CHANNELS, ""))) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        // The web guide's list has the channel ids. (The TV list SmartTube shows has none: with it this list stayed
        // empty, and the background check had nothing to look at once the app was closed.)
        try {
            addChannelIds(ids, YouTubeServiceManager.instance().getContentService().getSubscribedChannelsWithIds());
        } catch (Throwable e) {
            Log.e(TAG, "channels (guide): " + e);
        }
        if (ids.isEmpty()) {
            addChannelIds(ids, YouTubeServiceManager.instance().getContentService().getSubscribedChannels());
        }
        if (ids.isEmpty()) {
            // The channels of the videos on the Subscriptions feed: those that upload, at least
            addChannelIds(ids, YouTubeServiceManager.instance().getContentService().getSubscriptions());
        }
        Log.d(TAG, "channels: " + ids.size());
        if (ids.isEmpty()) {
            return; // the old list stays
        }
        prefs.edit().putInt("channels_v", 2).putString(KEY_CHANNELS, TextUtils.join(",", ids)).putLong(KEY_CHANNELS_AT, System.currentTimeMillis()).apply();
    }

    private static void addChannelIds(Set<String> ids, MediaGroup group) {
        List<MediaItem> items = group != null ? group.getMediaItems() : null;
        if (items == null) {
            return;
        }
        for (MediaItem item : items) {
            String id = item != null ? item.getChannelId() : null;
            if (!TextUtils.isEmpty(id) && id.startsWith("UC")) {
                ids.add(id);
            }
        }
    }

    private static String[] channels(Context context) {
        String stored = prefs(context).getString(KEY_CHANNELS, "");
        return TextUtils.isEmpty(stored) ? new String[0] : stored.split(",");
    }

    private static void post(Context context, List<Video> fresh) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return; // the tab still shows them
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager system = context.getSystemService(NotificationManager.class);
            if (system != null && system.getNotificationChannel(CHANNEL) == null) {
                system.createNotificationChannel(new NotificationChannel(CHANNEL,
                        context.getString(R.string.phone_new_videos_channel), NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        try {
            for (int i = 0; i < fresh.size() && i < MAX_POSTED; i++) {
                Video video = fresh.get(i);
                // Opens like any YouTube link: the launcher screen hands it to the player
                String link = video.isShorts ? "https://www.youtube.com/shorts/" + video.videoId
                        : "https://www.youtube.com/watch?v=" + video.videoId;
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        .setClass(context, SplashActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_phone_play)
                        .setContentTitle(video.getAuthor())
                        .setContentText(video.getTitle())
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(video.getTitle()))
                        .setAutoCancel(true)
                        .setGroup(GROUP)
                        .setContentIntent(PendingIntent.getActivity(context, video.videoId.hashCode(), open,
                                PendingIntent.FLAG_UPDATE_CURRENT | immutable));
                Bitmap picture = thumbnail(context, video);
                if (picture != null) {
                    builder.setLargeIcon(picture);
                }
                manager.notify(video.videoId.hashCode(), builder.build());
            }
            Intent tab = new Intent(context, PhoneNotificationsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            manager.notify(SUMMARY_ID, new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_phone_play)
                    .setContentTitle(context.getResources().getQuantityString(R.plurals.phone_new_videos_count, fresh.size(), fresh.size()))
                    .setAutoCancel(true)
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setContentIntent(PendingIntent.getActivity(context, SUMMARY_ID, tab, PendingIntent.FLAG_UPDATE_CURRENT | immutable))
                    .build());
        } catch (SecurityException e) {
            Log.e(TAG, "post: " + e); // notifications were switched off meanwhile
        }
    }

    private static Bitmap thumbnail(Context context, Video video) {
        try {
            String url = video.getCardImageUrl();
            return TextUtils.isEmpty(url) ? null : Glide.with(context).asBitmap().load(url).submit(320, 180).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null; // a notification without a picture
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The background check (WorkManager: survives restarts, waits for a connection) */
    public static class CheckWorker extends Worker {
        public CheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            try {
                Log.d(TAG, "checked: " + check(getApplicationContext()) + " new");
            } catch (Throwable e) {
                Log.e(TAG, "check: " + e); // offline, signed out meanwhile, YouTube said no: next time
            }
            return Result.success();
        }
    }
}
