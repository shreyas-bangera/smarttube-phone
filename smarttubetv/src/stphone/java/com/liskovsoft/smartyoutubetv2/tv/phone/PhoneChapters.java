package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * A video's chapters (as SmartTube reads them from YouTube: a title and a start time each): the one at a time, and
 * the gaps they make in the seek bar, like the YouTube app's.
 */
final class PhoneChapters {
    private final List<Video> mChapters = new ArrayList<>();

    void set(List<Video> chapters) {
        mChapters.clear();
        if (chapters != null) {
            for (Video chapter : chapters) {
                if (chapter != null && chapter.isChapter) {
                    mChapters.add(chapter);
                }
            }
        }
    }

    void clear() {
        mChapters.clear();
    }

    boolean isEmpty() {
        return mChapters.isEmpty();
    }

    /** The chapter playing at this time, or null */
    String titleAt(long positionMs) {
        String title = null;
        for (Video chapter : mChapters) {
            if (chapter.startTimeMs <= positionMs) {
                title = chapter.title;
            } else {
                break;
            }
        }
        return title;
    }

    /** Where the chapters after the first start, as fractions of the video */
    float[] marks(long durationMs) {
        if (durationMs <= 0 || mChapters.size() < 2) {
            return new float[0];
        }
        List<Float> marks = new ArrayList<>();
        for (Video chapter : mChapters) {
            if (chapter.startTimeMs > 0 && chapter.startTimeMs < durationMs) {
                marks.add(chapter.startTimeMs / (float) durationMs);
            }
        }
        float[] result = new float[marks.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = marks.get(i);
        }
        return result;
    }

    /**
     * Over a seek bar (or the thin line under the video): a short gap at each chapter's start, cut through the line
     * in the colour behind it, so the line reads as segments
     */
    static final class Gaps extends Drawable {
        private final Paint mPaint = new Paint();
        private final float mGap;
        private final float mHeight;
        private float[] mMarks = new float[0];

        /** gap: how wide each cut is; height: how tall (a little more than the line); color: what's behind */
        Gaps(float gap, float height, int color) {
            mGap = gap;
            mHeight = height;
            mPaint.setColor(color);
        }

        void setMarks(float[] marks) {
            mMarks = marks != null ? marks : new float[0];
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mMarks.length == 0) {
                return;
            }
            Rect b = getBounds();
            float cy = b.exactCenterY();
            for (float mark : mMarks) {
                float x = b.left + b.width() * mark;
                canvas.drawRect(x - mGap / 2f, cy - mHeight / 2f, x + mGap / 2f, cy + mHeight / 2f, mPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
