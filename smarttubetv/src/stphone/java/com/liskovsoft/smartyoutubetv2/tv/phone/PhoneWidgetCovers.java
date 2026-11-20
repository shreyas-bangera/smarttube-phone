package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The covers in the widget's stack: the song playing, then the songs to come (as the player last told the widget).
 * Each is the song's artwork as a rounded square; a tap plays it.
 */
public class PhoneWidgetCovers extends RemoteViewsService {
    /** Covers already drawn, by video id (the stack asks again as it's swiped) */
    private static final LruCache<String, Bitmap> COVERS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context mContext;
        private List<String[]> mSongs = new ArrayList<>();

        Factory(Context context) {
            mContext = context;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            mSongs = PhoneWidget.queue(mContext);
        }

        @Override
        public void onDestroy() {
        }

        @Override
        public int getCount() {
            return mSongs.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.phone_widget_cover);
            if (position < 0 || position >= mSongs.size()) {
                return views;
            }
            String[] song = mSongs.get(position);
            String id = song[0];
            Bitmap cover = COVERS.get(id);
            if (cover == null) {
                // Off the main thread already: the stack asks from a background thread
                Bitmap art = PhoneWidget.load(mContext, id, 480, 270);
                if (art != null) {
                    float d = mContext.getResources().getDisplayMetrics().density;
                    cover = PhoneWidget.cover(art, Math.round(150 * d), 16 * d);
                    COVERS.put(id, cover);
                }
            }
            if (cover != null) {
                views.setImageViewBitmap(R.id.phone_widget_cover, cover);
            } else {
                views.setImageViewResource(R.id.phone_widget_cover, R.mipmap.app_icon);
            }
            views.setContentDescription(R.id.phone_widget_cover, song[1]);
            views.setOnClickFillInIntent(R.id.phone_widget_cover, new Intent().putExtra(PhoneWidget.EXTRA_ID, id));
            return views;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position < mSongs.size() ? mSongs.get(position)[0].hashCode() : position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
