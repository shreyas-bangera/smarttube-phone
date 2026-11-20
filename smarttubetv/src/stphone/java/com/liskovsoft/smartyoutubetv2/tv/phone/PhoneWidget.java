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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The 2x2 home screen widget: the song's artwork, its name and artist, Play/Pause and Next, on a background drawn
 * from the song itself (its artwork blurred, a deep shade of its colour, soft lights of its colours), like the music
 * player. The player keeps it current ({@link #sync}); it keeps the last song after the app closes, and then a tap
 * anywhere opens the app.
 */
public class PhoneWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "org.smarttube.phone.widget.TOGGLE";
    private static final String ACTION_NEXT = "org.smarttube.phone.widget.NEXT";
    private static final String PREFS = "phone_widget";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

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

    /** From the player: this video, playing or not (live: a player is open to take Play/Pause and Next) */
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
        draw(app, sWidgetIds, video.videoId, title, artist, playing, live);
    }

    /** From the widget's buttons: to the open player, or open the app */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action) || ACTION_NEXT.equals(action)) {
            PhonePlaybackActivity.widgetCommand(context, ACTION_NEXT.equals(action));
            return;
        }
        super.onReceive(context, intent);
    }

    // endregion

    // region The launcher

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        Context app = context.getApplicationContext();
        sWidgetIds = ids(app);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = sShownId != null ? sShownId : prefs.getString("id", null);
        String title = prefs.getString("title", null);
        String artist = prefs.getString("artist", null);
        PendingResult pending = goAsync();
        draw(app, widgetIds, id, title, artist, sShownPlaying, sShownLive, pending);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId, Bundle options) {
        onUpdate(context, manager, new int[] {widgetId}); // resized: drawn again at the new size
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

    private static void draw(Context app, int[] widgetIds, String id, String title, String artist, boolean playing, boolean live) {
        draw(app, widgetIds, id, title, artist, playing, live, null);
    }

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
                            Bitmap small = Bitmap.createScaledBitmap(art, 48, 27, true);
                            palette = PhoneMusicFx.palette(small);
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
                    .submit(480, 270).get();
        } catch (Exception e) {
            return null;
        }
    }

    private static RemoteViews views(Context app, AppWidgetManager manager, int widgetId, String id, String title,
                                     String artist, boolean playing, boolean live, Bitmap art, int[] palette) {
        RemoteViews views = new RemoteViews(app.getPackageName(), R.layout.phone_widget);
        float d = app.getResources().getDisplayMetrics().density;
        // The widget's size on this launcher (dp), for a sharp background
        Bundle options = manager.getAppWidgetOptions(widgetId);
        int wDp = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 160));
        int hDp = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160));
        int w = Math.min(Math.round(wDp * d), 900);
        int h = Math.min(Math.round(hDp * d), 900);
        views.setImageViewBitmap(R.id.phone_widget_bg, background(w, h, d, art, palette));

        boolean idle = id == null;
        if (art != null) {
            views.setImageViewBitmap(R.id.phone_widget_art, cover(art, Math.round(62 * d), 14 * d));
        } else {
            views.setImageViewResource(R.id.phone_widget_art, R.mipmap.app_icon);
        }
        views.setTextViewText(R.id.phone_widget_title, idle || TextUtils.isEmpty(title)
                ? app.getString(R.string.phone_widget_idle) : title);
        views.setTextViewText(R.id.phone_widget_artist, idle ? app.getString(R.string.phone_widget_idle_hint)
                : artist != null ? artist : "");
        views.setImageViewResource(R.id.phone_widget_play, playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
        // The play button's icon in a deep shade of the song's colour, Next in white
        views.setInt(R.id.phone_widget_play, "setColorFilter", PhoneMusicPlayer.darken(palette[0], 0.55f));
        views.setInt(R.id.phone_widget_next, "setColorFilter", Color.WHITE);

        PendingIntent open = PendingIntent.getActivity(app, 0, openApp(app),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.phone_widget_root, open);
        if (live) {
            views.setOnClickPendingIntent(R.id.phone_widget_play_box, button(app, ACTION_TOGGLE, 1));
            views.setOnClickPendingIntent(R.id.phone_widget_next, button(app, ACTION_NEXT, 2));
        } else {
            // No player open: the buttons open the app too
            views.setOnClickPendingIntent(R.id.phone_widget_play_box, open);
            views.setOnClickPendingIntent(R.id.phone_widget_next, open);
        }
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
     * colour in the top left and of its other colour in the bottom right, darker at the bottom for the words
     */
    private static Bitmap background(int w, int h, float d, Bitmap art, int[] palette) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float radius = 26 * d;
        RectF box = new RectF(0, 0, w, h);
        Path clip = new Path();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        paint.setShader(new LinearGradient(0, 0, w, h, PhoneMusicPlayer.darken(palette[0], 0.5f), 0xFF0B0B0D, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        if (art != null) {
            // Blurred: drawn tiny, then stretched back up with smoothing
            Bitmap tiny = Bitmap.createScaledBitmap(art, 12, 7, true);
            Bitmap soft = Bitmap.createScaledBitmap(tiny, 48, 27, true);
            paint.setShader(null);
            paint.setAlpha(120);
            float scale = Math.max(w / 48f, h / 27f) * 1.15f;
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - 48 * scale) / 2f, (h - 27 * scale) / 2f);
            canvas.drawBitmap(soft, m, paint);
            paint.setAlpha(255);
        }
        int main = palette[0] & 0x00FFFFFF;
        int other = palette[1] & 0x00FFFFFF;
        paint.setShader(new RadialGradient(w * 0.15f, h * 0.1f, Math.max(w, h) * 0.75f,
                new int[] {main | 0x8C000000, main | 0x26000000, main}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        paint.setShader(new RadialGradient(w * 0.95f, h * 0.95f, Math.max(w, h) * 0.6f,
                new int[] {other | 0x59000000, other}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        paint.setShader(new LinearGradient(0, h * 0.35f, 0, h, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
        canvas.drawRect(box, paint);
        // A thin light edge, like glass
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, d * 0.8f));
        paint.setColor(0x26FFFFFF);
        RectF edge = new RectF(box);
        edge.inset(d * 0.4f, d * 0.4f);
        canvas.drawRoundRect(edge, radius, radius, paint);
        return bitmap;
    }

    /** The artwork as a rounded square (the middle of a 16:9 picture), with a soft shadow line */
    private static Bitmap cover(Bitmap art, int size, float radius) {
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
