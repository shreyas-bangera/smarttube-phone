package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.SizeF;
import android.util.TypedValue;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen widget, in three layouts for its size: small (2x2: a big cover with Next and Play beside it, the
 * song under it), wide (the cover on the left, the song and Previous, Play and Next on the right) and large (a big
 * cover in the middle, the song and the buttons under it). On Android 12 and later the cover is sized to the widget.
 * The cover is a stack of the song playing and the songs to come: swipe through it, tap one to play it. The
 * background is drawn from the song (its artwork blurred, a deep shade of its colour, soft lights of its palette, a
 * glass edge), like the music player. The player keeps it current ({@link #sync}); after the app closes it keeps
 * the last song, and a tap opens the app.
 */
public class PhoneWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "org.smarttube.phone.widget.TOGGLE";
    private static final String ACTION_NEXT = "org.smarttube.phone.widget.NEXT";
    private static final String ACTION_PREVIOUS = "org.smarttube.phone.widget.PREVIOUS";
    private static final String ACTION_PICK = "org.smarttube.phone.widget.PICK";
    static final String EXTRA_ID = "id";
    private static final String PREFS = "phone_widget";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int SMALL = 0;
    private static final int WIDE = 1;
    private static final int LARGE = 2;

    /** What the widget shows now (the player calls in many times a second: only a change redraws) */
    private static String sShownId;
    private static boolean sShownPlaying;
    private static boolean sShownLive;
    private static String sShownQueue = "";
    private static int[] sWidgetIds;
    /** The last song's artwork and colours, kept so play/pause doesn't load them again */
    private static String sArtFor;
    private static Bitmap sArt;
    private static int[] sPalette;

    // region The player

    /**
     * From the player: this video, playing or not (live: a player is open to take the buttons), and the songs after
     * it (null: unchanged)
     */
    static void sync(Context context, Video video, boolean musicMode, boolean playing, boolean live, List<Video> upNext) {
        if (video == null || video.videoId == null) {
            return;
        }
        String queue = upNext != null ? queueKey(video, upNext) : sShownQueue;
        boolean songChanged = !video.videoId.equals(sShownId);
        boolean queueChanged = !queue.equals(sShownQueue);
        if (!songChanged && !queueChanged && playing == sShownPlaying && live == sShownLive) {
            return;
        }
        Context app = context.getApplicationContext();
        if (sWidgetIds == null) {
            sWidgetIds = ids(app);
        }
        sShownId = video.videoId;
        sShownPlaying = playing;
        sShownLive = live;
        String title = musicMode ? PhoneLyrics.displayTitle(video.getTitle()) : video.getTitle();
        String artist = musicMode ? PhoneLyrics.cleanArtist(video.getAuthor()) : video.getAuthor();
        SharedPreferences.Editor prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("id", video.videoId)
                .putString("title", title)
                .putString("artist", artist);
        if (queueChanged && upNext != null) {
            sShownQueue = queue;
            prefs.putString("queue", encode(video, upNext, musicMode));
        }
        prefs.apply();
        if (sWidgetIds.length == 0) {
            return; // none on the home screen
        }
        if (queueChanged) {
            AppWidgetManager.getInstance(app).notifyAppWidgetViewDataChanged(sWidgetIds, R.id.phone_widget_stack);
        }
        draw(app, sWidgetIds, video.videoId, title, artist, playing, live, songChanged, null);
    }

    /** The covers in the stack: the song playing, then the ones to come (id, title, artist each) */
    static List<String[]> queue(Context context) {
        String data = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("queue", "");
        List<String[]> songs = new ArrayList<>();
        for (String line : data.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length >= 3 && !parts[0].isEmpty()) {
                songs.add(parts);
            }
        }
        return songs;
    }

    private static String queueKey(Video current, List<Video> upNext) {
        StringBuilder key = new StringBuilder(current.videoId);
        for (Video video : upNext) {
            key.append(',').append(video.videoId);
        }
        return key.toString();
    }

    private static String encode(Video current, List<Video> upNext, boolean musicMode) {
        StringBuilder data = new StringBuilder();
        List<Video> all = new ArrayList<>();
        all.add(current);
        all.addAll(upNext.subList(0, Math.min(upNext.size(), 14)));
        for (Video video : all) {
            if (video == null || video.videoId == null) {
                continue;
            }
            String title = musicMode ? PhoneLyrics.displayTitle(video.getTitle()) : video.getTitle();
            String artist = musicMode ? PhoneLyrics.cleanArtist(video.getAuthor()) : video.getAuthor();
            data.append(video.videoId).append('\t').append(clean(title)).append('\t').append(clean(artist)).append('\n');
        }
        return data.toString();
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ');
    }

    /** The widget's buttons and covers: to the open player, or open the app */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_TOGGLE, null);
        } else if (ACTION_NEXT.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_NEXT, null);
        } else if (ACTION_PREVIOUS.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_PREVIOUS, null);
        } else if (ACTION_PICK.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_PICK, intent.getStringExtra(EXTRA_ID));
        } else {
            super.onReceive(context, intent);
        }
    }

    // endregion

    // region The launcher

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        Context app = context.getApplicationContext();
        sWidgetIds = ids(app);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = sShownId != null ? sShownId : prefs.getString("id", null);
        draw(app, widgetIds, id, prefs.getString("title", null), prefs.getString("artist", null), sShownPlaying,
                sShownLive, true, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId, Bundle options) {
        onUpdate(context, manager, new int[] {widgetId}); // resized: drawn again for the new size
    }

    @Override
    public void onDeleted(Context context, int[] widgetIds) {
        sWidgetIds = null;
    }

    @Override
    public void onEnabled(Context context) {
        sWidgetIds = null;
    }

    private static int[] ids(Context app) {
        return AppWidgetManager.getInstance(app).getAppWidgetIds(new ComponentName(app, PhoneWidget.class));
    }

    // endregion

    // region Drawing

    /** Off the main thread (the artwork may need loading), then to every widget */
    private static void draw(Context app, int[] widgetIds, String id, String title, String artist, boolean playing,
                             boolean live, boolean toFront, PendingResult pending) {
        WORKER.execute(() -> {
            try {
                Bitmap art = null;
                int[] palette = null;
                if (id != null) {
                    if (id.equals(sArtFor) && sArt != null) {
                        art = sArt;
                        palette = sPalette;
                    } else {
                        art = load(app, id, 480, 270);
                        if (art != null) {
                            palette = PhoneMusicFx.palette(Bitmap.createScaledBitmap(art, 48, 27, true));
                            sArtFor = id;
                            sArt = art;
                            sPalette = palette;
                        }
                    }
                }
                if (palette == null) {
                    palette = new int[] {0xFF8C6CFF, 0xFFFF6FA8, 0xFFCDBFFF};
                }
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                for (int widgetId : widgetIds) {
                    manager.updateAppWidget(widgetId, views(app, manager, widgetId, id, title, artist, playing, live,
                            toFront, art, palette));
                }
            } catch (RuntimeException e) {
                android.util.Log.w("PhoneWidget", "draw: " + e);
            } finally {
                if (pending != null) {
                    pending.finish();
                }
            }
        });
    }

    static Bitmap load(Context app, String id, int width, int height) {
        try {
            return Glide.with(app).asBitmap().load("https://i.ytimg.com/vi/" + id + "/hq720.jpg")
                    .error(Glide.with(app).asBitmap().load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg"))
                    .submit(width, height).get();
        } catch (Exception e) {
            return null;
        }
    }

    /** One set of views per size the launcher may show the widget at (portrait and landscape) */
    private static RemoteViews views(Context app, AppWidgetManager manager, int widgetId, String id, String title,
                                     String artist, boolean playing, boolean live, boolean toFront, Bitmap art, int[] palette) {
        Bundle options = manager.getAppWidgetOptions(widgetId);
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                Map<SizeF, RemoteViews> bySize = new HashMap<>();
                for (SizeF size : sizes) {
                    bySize.put(size, sized(app, widgetId, size.getWidth(), size.getHeight(), id, title, artist, playing,
                            live, toFront, art, palette));
                }
                return new RemoteViews(bySize);
            }
        }
        // Portrait: the full width and the shorter height
        float w = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170));
        float h = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 170));
        return sized(app, widgetId, w, h, id, title, artist, playing, live, toFront, art, palette);
    }

    private static int kind(float wDp, float hDp) {
        if (wDp >= 230 && hDp >= 250) {
            return LARGE;
        }
        return wDp >= 230 ? WIDE : SMALL;
    }

    private static RemoteViews sized(Context app, int widgetId, float wDp, float hDp, String id, String title,
                                     String artist, boolean playing, boolean live, boolean toFront, Bitmap art, int[] palette) {
        int kind = kind(wDp, hDp);
        int layout = kind == LARGE ? R.layout.phone_widget_large : kind == WIDE ? R.layout.phone_widget_wide : R.layout.phone_widget;
        RemoteViews views = new RemoteViews(app.getPackageName(), layout);
        float d = app.getResources().getDisplayMetrics().density;
        float radius = Build.VERSION.SDK_INT >= 31
                ? app.getResources().getDimension(android.R.dimen.system_app_widget_background_radius) : 24 * d;
        // Drawn at half size and stretched: it's all soft light anyway, and it keeps the widget light
        int bw = Math.max(1, Math.min(Math.round(wDp * d / 2f), 600));
        int bh = Math.max(1, Math.min(Math.round(hDp * d / 2f), 600));
        views.setImageViewBitmap(R.id.phone_widget_bg, background(bw, bh, d / 2f, radius / 2f, art, palette));

        // The cover, sized to the widget (Android 12 and later; before, each layout's own size)
        if (Build.VERSION.SDK_INT >= 31) {
            float cover;
            if (kind == LARGE) {
                cover = Math.min(wDp - 32, hDp - 32 - 128);
            } else if (kind == WIDE) {
                cover = hDp - 24;
            } else {
                cover = Math.min(wDp - 24 - 52, hDp - 24 - 40);
            }
            cover = Math.max(56, Math.min(cover, 320));
            views.setViewLayoutWidth(R.id.phone_widget_art_box, cover, TypedValue.COMPLEX_UNIT_DIP);
            views.setViewLayoutHeight(R.id.phone_widget_art_box, cover, TypedValue.COMPLEX_UNIT_DIP);
        }

        // The stack of covers: the song playing first, then the ones to come
        Intent covers = new Intent(app, PhoneWidgetCovers.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        covers.setData(Uri.parse(covers.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.phone_widget_stack, covers);
        views.setEmptyView(R.id.phone_widget_stack, R.id.phone_widget_empty);
        if (toFront) {
            views.setDisplayedChild(R.id.phone_widget_stack, 0);
        }

        boolean idle = id == null;
        views.setTextViewText(R.id.phone_widget_title, idle || TextUtils.isEmpty(title)
                ? app.getString(R.string.phone_widget_idle) : title);
        views.setTextViewText(R.id.phone_widget_artist, idle ? app.getString(R.string.phone_widget_idle_hint)
                : artist != null ? artist : "");
        views.setImageViewResource(R.id.phone_widget_play, playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
        // The play button's icon in a deep shade of the song's colour, the others white
        views.setInt(R.id.phone_widget_play, "setColorFilter", PhoneMusicPlayer.darken(palette[0], 0.55f));
        views.setInt(R.id.phone_widget_next, "setColorFilter", 0xFFFFFFFF);
        if (kind != SMALL) {
            views.setInt(R.id.phone_widget_prev, "setColorFilter", 0xFFFFFFFF);
        }

        PendingIntent open = PendingIntent.getActivity(app, 0, openApp(app),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.phone_widget_root, open);
        views.setOnClickPendingIntent(R.id.phone_widget_empty, open);
        if (live) {
            views.setOnClickPendingIntent(R.id.phone_widget_play_box, button(app, ACTION_TOGGLE, 1));
            views.setOnClickPendingIntent(R.id.phone_widget_next, button(app, ACTION_NEXT, 2));
            if (kind != SMALL) {
                views.setOnClickPendingIntent(R.id.phone_widget_prev, button(app, ACTION_PREVIOUS, 3));
            }
        } else {
            // No player open: the buttons open the app
            views.setOnClickPendingIntent(R.id.phone_widget_play_box, open);
            views.setOnClickPendingIntent(R.id.phone_widget_next, open);
            if (kind != SMALL) {
                views.setOnClickPendingIntent(R.id.phone_widget_prev, open);
            }
        }
        // A cover tapped: that song (the template; each cover adds its id)
        Intent pick = new Intent(app, PhoneWidget.class).setAction(ACTION_PICK);
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        views.setPendingIntentTemplate(R.id.phone_widget_stack,
                PendingIntent.getBroadcast(app, 4, pick, PendingIntent.FLAG_UPDATE_CURRENT | mutable));
        return views;
    }

    private static Intent openApp(Context app) {
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (intent == null) {
            intent = new Intent();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    private static PendingIntent button(Context app, String action, int code) {
        Intent intent = new Intent(app, PhoneWidget.class).setAction(action);
        return PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The background: rounded, a deep shade of the song's colour, its artwork blurred over it, a soft light of its
     * colour in the top left and of its other colour in the bottom right, darker at the bottom for the words, and a
     * thin light edge like glass
     */
    private static Bitmap background(int w, int h, float d, float radius, Bitmap art, int[] palette) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        RectF box = new RectF(0, 0, w, h);
        Path clip = new Path();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        paint.setShader(new LinearGradient(0, 0, w, h, PhoneMusicPlayer.darken(palette[0], 0.45f), 0xFF0B0B0D, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        if (art != null) {
            // Blurred: drawn tiny, then stretched back up with smoothing
            Bitmap soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(art, 12, 7, true), 48, 27, true);
            paint.setShader(null);
            paint.setAlpha(130);
            float scale = Math.max(w / 48f, h / 27f) * 1.2f;
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - 48 * scale) / 2f, (h - 27 * scale) / 2f);
            canvas.drawBitmap(soft, m, paint);
            paint.setAlpha(255);
        }
        int main = palette[0] & 0x00FFFFFF;
        int other = palette[1] & 0x00FFFFFF;
        paint.setShader(new RadialGradient(w * 0.12f, h * 0.08f, Math.max(w, h) * 0.8f,
                new int[] {main | 0x8C000000, main | 0x26000000, main}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        paint.setShader(new RadialGradient(w * 0.95f, h * 0.95f, Math.max(w, h) * 0.65f,
                new int[] {other | 0x59000000, other}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        paint.setShader(new LinearGradient(0, h * 0.4f, 0, h, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, d * 0.8f));
        paint.setColor(0x2EFFFFFF);
        RectF edge = new RectF(box);
        edge.inset(d * 0.4f, d * 0.4f);
        canvas.drawRoundRect(edge, radius, radius, paint);
        return bitmap;
    }

    /** The artwork as a rounded square (the middle of a 16:9 picture) */
    static Bitmap cover(Bitmap art, int size, float radius) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(size / (float) art.getWidth(), size / (float) art.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((size - art.getWidth() * scale) / 2f, (size - art.getHeight() * scale) / 2f);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        return bitmap;
    }

    // endregion
}
