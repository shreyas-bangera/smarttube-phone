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
    private static final long CHECK_EVERY_MINUTES = 60;
    private static final String WORK_NAME_V2 = "phone_new_videos_v2";
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

    /** Keeps the background check running (or stops it when it's switched off). Safe to call at every start. */
    static void schedule(Context context) {
        try {
            WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
            manager.cancelUniqueWork(WORK_NAME); // the first version (every 2 hours, account feed)
            if (!isEnabled(context)) {
                manager.cancelUniqueWork(WORK_NAME_V2);
                return;
            }
            Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(CheckWorker.class, CHECK_EVERY_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build();
            manager.enqueueUniquePeriodicWork(WORK_NAME_V2, ExistingPeriodicWorkPolicy.KEEP, request);
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
        List<MediaItem> items = latestUploads(app);
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
        for (MediaItem item : items) {
            String id = item != null ? item.getVideoId() : null;
            if (id == null) {
                continue;
            }
            current.add(id);
            // Videos and live streams; Shorts and not yet started premieres would mostly be noise. Only what came out
            // since the last look (a channel subscribed to meanwhile doesn't bring its older videos).
            long published = item.getPublishedDate();
            boolean recent = published <= 0 || (published > lastCheck - RECENT_MARGIN_MS && published > now - MAX_AGE_MS);
            // (a channel whose bell is on All brings its Shorts too)
            boolean shortOk = !item.isShorts() || bell(app, item.getChannelId()) == BELL_ALL;
            if (!seen.contains(id) && recent && shortOk && !item.isUpcoming()) {
                fresh.add(Video.from(item));
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

    /**
     * The latest uploads of the subscribed channels, as Flow does it: each channel's public feed (RSS), which needs no
     * sign-in, so it also works when Android wakes the app in the background. The channel list is taken from the
     * account while the app is open; without one yet, the account's Subscriptions feed.
     */
    private static List<MediaItem> latestUploads(Context app) {
        boolean signedIn = false;
        try {
            signedIn = YouTubeServiceManager.instance().getSignInService().isSigned();
            if (signedIn) {
                refreshChannels(app, false);
            }
        } catch (Throwable e) {
            Log.e(TAG, "channels: " + e);
        }
        List<MediaItem> items = new ArrayList<>();
        String[] channels = channels(app);
        if (channels.length > 0) {
            for (int from = 0; from < channels.length; from += RSS_CHUNK) {
                String[] chunk = java.util.Arrays.copyOfRange(channels, from, Math.min(channels.length, from + RSS_CHUNK));
                try {
                    MediaGroup group = YouTubeServiceManager.instance().getContentService().getRssFeed(chunk);
                    if (group != null && group.getMediaItems() != null) {
                        items.addAll(group.getMediaItems());
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "rss: " + e); // the other channels still count
                }
            }
        } else if (signedIn) {
            MediaGroup feed = YouTubeServiceManager.instance().getContentService().getSubscriptions();
            if (feed != null && feed.getMediaItems() != null) {
                items.addAll(feed.getMediaItems());
            }
        }
        java.util.Collections.sort(items, (a, b) -> Long.compare(b.getPublishedDate(), a.getPublishedDate()));
        return items;
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
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + video.videoId))
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
