package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.SizeF;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen widget: the song's artwork filling it, edge to edge, darkening at the bottom into a deep shade of
 * the song's colour where its name and the buttons sit (glass circles, a white Play), like the music player. Three
 * layouts by size: small (Next top right, Play bottom right), wide (Previous, Play and Next bottom right) and large
 * (the buttons spread across the bottom). Each size gets its own picture, cut to its shape. A tap on the artwork
 * opens the player with the song; after the app closes it keeps the last song, and a tap opens the app.
 */
public class PhoneWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "org.smarttube.phone.widget.TOGGLE";
    private static final String ACTION_NEXT = "org.smarttube.phone.widget.NEXT";
    private static final String ACTION_PREVIOUS = "org.smarttube.phone.widget.PREVIOUS";
    private static final String PREFS = "phone_widget";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int SMALL = 0;
    private static final int WIDE = 1;
    private static final int LARGE = 2;

    /** What the widget shows now (the player calls in many times a second: only a change redraws) */
    private static String sShownId;
    private static boolean sShownPlaying;
    private static boolean sShownLive;
    private static int[] sWidgetIds;
    /** The last song's artwork and colours, kept so play/pause doesn't load them again */
    private static String sArtFor;
    private static Bitmap sArt;
    private static int[] sPalette;

    // region The player

    /** From the player: this video, playing or not (live: a player is open to take the buttons) */
    static void sync(Context context, Video video, boolean musicMode, boolean playing, boolean live) {
        if (video == null || video.videoId == null) {
            return;
        }
        if (video.videoId.equals(sShownId) && playing == sShownPlaying && live == sShownLive) {
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
        // Kept for later draws (the launcher redrawing it, after a restart)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("id", video.videoId)
                .putString("title", title)
                .putString("artist", artist)
                .apply();
        if (sWidgetIds.length == 0) {
            return; // none on the home screen
        }
        draw(app, sWidgetIds, video.videoId, title, artist, playing, live, null);
    }

    /** The widget's buttons: to the open player, or open the app */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_TOGGLE);
        } else if (ACTION_NEXT.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_NEXT);
        } else if (ACTION_PREVIOUS.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, PhonePlaybackActivity.WIDGET_PREVIOUS);
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
                sShownLive, goAsync());
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
                             boolean live, PendingResult pending) {
        WORKER.execute(() -> {
            try {
                Bitmap art = null;
                int[] palette = null;
                if (id != null) {
                    if (id.equals(sArtFor) && sArt != null) {
                        art = sArt;
                        palette = sPalette;
                    } else {
                        art = load(app, id);
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
                    manager.updateAppWidget(widgetId, views(app, manager, widgetId, id, title, artist, playing, live, art, palette));
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

    private static Bitmap load(Context app, String id) {
        try {
            return Glide.with(app).asBitmap().load("https://i.ytimg.com/vi/" + id + "/hq720.jpg")
                    .error(Glide.with(app).asBitmap().load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg"))
                    .submit(1280, 720).get();
        } catch (Exception e) {
            return null;
        }
    }

    /** One set of views per size the launcher may show the widget at (portrait and landscape) */
    private static RemoteViews views(Context app, AppWidgetManager manager, int widgetId, String id, String title,
                                     String artist, boolean playing, boolean live, Bitmap art, int[] palette) {
        Bundle options = manager.getAppWidgetOptions(widgetId);
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                Map<SizeF, RemoteViews> bySize = new HashMap<>();
                for (SizeF size : sizes) {
                    bySize.put(size, sized(app, size.getWidth(), size.getHeight(), id, title, artist, playing, live, art, palette));
                }
                return new RemoteViews(bySize);
            }
        }
        // Portrait: the full width and the shorter height
        float w = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170));
        float h = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 170));
        return sized(app, w, h, id, title, artist, playing, live, art, palette);
    }

    private static int kind(float wDp, float hDp) {
        if (wDp >= 230 && hDp >= 230) {
            return LARGE;
        }
        return wDp >= 230 ? WIDE : SMALL;
    }

    private static RemoteViews sized(Context app, float wDp, float hDp, String id, String title, String artist,
                                     boolean playing, boolean live, Bitmap art, int[] palette) {
        int kind = kind(wDp, hDp);
        int layout = kind == LARGE ? R.layout.phone_widget_large : kind == WIDE ? R.layout.phone_widget_wide : R.layout.phone_widget;
        RemoteViews views = new RemoteViews(app.getPackageName(), layout);
        float d = app.getResources().getDisplayMetrics().density;
        float radius = Build.VERSION.SDK_INT >= 31
                ? app.getResources().getDimension(android.R.dimen.system_app_widget_background_radius) : 24 * d;
        // The picture, cut to this size's shape (sharp, but no bigger than it needs to be)
        float scale = Math.min(1f, 720f / (Math.max(wDp, hDp) * d));
        int bw = Math.max(1, Math.round(wDp * d * scale));
        int bh = Math.max(1, Math.round(hDp * d * scale));
        views.setImageViewBitmap(R.id.phone_widget_bg, picture(bw, bh, d * scale, radius * scale, art, palette, kind));

        boolean idle = id == null;
        views.setTextViewText(R.id.phone_widget_title, idle || TextUtils.isEmpty(title)
                ? app.getString(R.string.phone_widget_idle) : title);
        views.setTextViewText(R.id.phone_widget_artist, idle ? app.getString(R.string.phone_widget_idle_hint)
                : artist != null ? artist : "");
        views.setImageViewResource(R.id.phone_widget_play, playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
        // Play's icon in a deep shade of the song's colour, the glass buttons' white
        views.setInt(R.id.phone_widget_play, "setColorFilter", PhoneMusicPlayer.darken(palette[0], 0.55f));
        views.setInt(R.id.phone_widget_next, "setColorFilter", 0xFFFFFFFF);
        if (kind != SMALL) {
            views.setInt(R.id.phone_widget_prev, "setColorFilter", 0xFFFFFFFF);
        }

        PendingIntent open = PendingIntent.getActivity(app, 0, openApp(app),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // The artwork: the player with this song (as the playback notification opens it), or the app
        views.setOnClickPendingIntent(R.id.phone_widget_root, live ? openPlayer(app) : open);
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
        return views;
    }

    private static PendingIntent openPlayer(Context app) {
        Intent intent = new Intent(app, PhonePlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(app, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
     * The widget's picture: the artwork filling it edge to edge (cut to its shape), a little shade at the top for the
     * small layout's button, and from the middle down a gradient into a deep shade of the song's colour, where the
     * words and buttons sit; a soft light of its other colour from the corner; rounded like the launcher's widgets,
     * with a thin light edge like glass. No artwork: a deep gradient of the colours.
     */
    private static Bitmap picture(int w, int h, float d, float radius, Bitmap art, int[] palette, int kind) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        RectF box = new RectF(0, 0, w, h);
        Path clip = new Path();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int deep = PhoneMusicPlayer.darken(palette[0], 0.72f) & 0x00FFFFFF;

        if (art != null) {
            float scale = Math.max(w / (float) art.getWidth(), h / (float) art.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - art.getWidth() * scale) / 2f, (h - art.getHeight() * scale) / 2f);
            canvas.drawBitmap(art, m, paint);
        } else {
            paint.setShader(new LinearGradient(0, 0, w, h, PhoneMusicPlayer.darken(palette[0], 0.3f), 0xFF0B0B0D,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(box, paint);
        }
        if (kind == SMALL) {
            // A little shade at the top, where Next sits
            paint.setShader(new LinearGradient(0, 0, 0, h * 0.35f, 0x47000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(box, paint);
        }
        // From the middle down: into the song's colour, deep enough for white words
        float from = kind == LARGE ? 0.42f : kind == WIDE ? 0.2f : 0.36f;
        paint.setShader(new LinearGradient(0, h * from, 0, h,
                new int[] {deep, deep | 0x8C000000, deep | 0xEB000000}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        if (kind == WIDE) {
            // Wide: from the left too, behind the words
            paint.setShader(new LinearGradient(0, 0, w * 0.75f, 0, deep | 0x99000000, deep, Shader.TileMode.CLAMP));
            canvas.drawRect(box, paint);
        }
        // A soft light of the song's other colour from the bottom corner
        int light = palette[1] & 0x00FFFFFF;
        paint.setShader(new RadialGradient(w, h, Math.max(w, h) * 0.7f, new int[] {light | 0x40000000, light}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, d * 0.8f));
        paint.setColor(0x33FFFFFF);
        RectF edge = new RectF(box);
        edge.inset(d * 0.4f, d * 0.4f);
        canvas.drawRoundRect(edge, radius, radius, paint);
        return bitmap;
    }

    // endregion
}
