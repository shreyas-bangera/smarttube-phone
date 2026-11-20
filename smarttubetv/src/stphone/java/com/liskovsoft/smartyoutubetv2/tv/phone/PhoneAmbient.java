package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Ambient mode, like the YouTube app's: a soft glow of the video's own colours around it, above it (behind the status
 * bar) and below it (fading out under the title), following the picture as it changes. Every moment or two a tiny
 * copy of the frame is taken, softened, and crossfaded in. Dark appearance only, as on YouTube.
 */
final class PhoneAmbient {
    private final View mRoot;
    private final View mVideoBox;
    private final View mDetails;
    private final float mD;
    private final int mBackground;
    private final Glow mGlow;
    private final Drawable mDetailsBackground;
    private final Drawable mRootBackground;
    private boolean mActive;
    private float mStrength = 0.52f;

    PhoneAmbient(Context context, View root, View videoBox, View details, int background) {
        mRoot = root;
        mVideoBox = videoBox;
        mDetails = details;
        mD = context.getResources().getDisplayMetrics().density;
        mBackground = background;
        mGlow = new Glow();
        mDetailsBackground = details.getBackground();
        mRootBackground = root.getBackground();
    }

    /** Dark appearance: the page's background is dark */
    static boolean isDark(int background) {
        return 0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background) < 60;
    }

    /** How opaque the glow is (Customize > Glow strength) */
    void setStrength(float strength) {
        if (strength != mStrength) {
            mStrength = strength;
            mGlow.invalidateSelf();
        }
    }

    boolean isActive() {
        return mActive;
    }

    void setActive(boolean active) {
        if (active == mActive) {
            return;
        }
        mActive = active;
        if (active) {
            mRoot.setBackground(mGlow);
            mDetails.setBackground(new Fade());
        } else {
            mRoot.setBackground(mRootBackground);
            mDetails.setBackground(mDetailsBackground);
            mGlow.clear();
        }
    }

    /** A new tiny frame of the video (about 32 x 18): it softens and fades in over the last one */
    void show(Bitmap frame) {
        if (!mActive || frame == null) {
            return;
        }
        // Softer still: down to a few pixels and back up, smoothed
        Bitmap tiny = Bitmap.createScaledBitmap(frame, 8, 5, true);
        Bitmap soft = Bitmap.createScaledBitmap(tiny, 32, 18, true);
        mGlow.next(soft);
    }

    /** Behind the player: the page's colour, with the glow around the video */
    private final class Glow extends Drawable {
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint mFade = new Paint();
        private final RectF mRect = new RectF();
        private Bitmap mNow;
        private Bitmap mBefore;
        private float mMix = 1f;
        private ValueAnimator mAnimator;

        void next(Bitmap frame) {
            mBefore = mNow;
            mNow = frame;
            if (mAnimator != null) {
                mAnimator.cancel();
            }
            mMix = 0f;
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(1100);
            mAnimator.setInterpolator(new DecelerateInterpolator());
            mAnimator.addUpdateListener(a -> {
                mMix = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mAnimator.start();
        }

        void clear() {
            if (mAnimator != null) {
                mAnimator.cancel();
            }
            mNow = null;
            mBefore = null;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            canvas.drawColor(mBackground);
            if (mNow == null) {
                return;
            }
            float videoTop = mVideoBox.getTop();
            float videoBottom = mVideoBox.getBottom();
            float below = 230 * mD;
            // The glow: the frame stretched over the video and beyond it, a little wider than the screen
            mRect.set(b.left - b.width() * 0.15f, Math.min(b.top, videoTop) - 40 * mD, b.right + b.width() * 0.15f, videoBottom + below);
            float strength = mStrength;
            if (mBefore != null && mMix < 1f) {
                mPaint.setAlpha(Math.round(255 * strength * (1f - mMix)));
                canvas.drawBitmap(mBefore, null, mRect, mPaint);
            }
            mPaint.setAlpha(Math.round(255 * strength * (mBefore != null ? mMix : 1f)));
            canvas.drawBitmap(mNow, null, mRect, mPaint);
            // Below the video it fades out into the page
            mFade.setShader(new LinearGradient(0, videoBottom, 0, videoBottom + below,
                    mBackground & 0x00FFFFFF, mBackground, Shader.TileMode.CLAMP));
            canvas.drawRect(b.left, videoBottom, b.right, b.bottom, mFade);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /** The details' background: clear at the top (the glow shows through under the title), then the page's colour */
    private final class Fade extends Drawable {
        private final Paint mPaint = new Paint();

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float clear = 200 * mD;
            mPaint.setShader(new LinearGradient(0, b.top, 0, b.top + clear,
                    mBackground & 0x00FFFFFF, mBackground, Shader.TileMode.CLAMP));
            canvas.drawRect(b, mPaint);
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
