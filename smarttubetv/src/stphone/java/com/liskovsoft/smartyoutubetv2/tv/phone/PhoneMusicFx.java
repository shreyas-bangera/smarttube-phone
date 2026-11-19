package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The music player's lights: the song's colours read from its artwork ({@link #palette}), an aura of them around
 * the artwork ({@link Aura}) and a glare sweeping across the artwork when a song starts ({@link Glare}).
 */
final class PhoneMusicFx {
    private PhoneMusicFx() {
    }

    /**
     * Three lights from the artwork: its most colourful tone, the next most present hue (or that tone turned a third
     * of the way round the colour wheel when the artwork has only one), and a light shade of the first
     */
    static int[] palette(Bitmap bitmap) {
        float[] weights = new float[12];
        float[][] sums = new float[12][3];
        float[] hsv = new float[3];
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int c = bitmap.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.18f || hsv[2] < 0.18f) {
                    continue; // greys and near blacks are no light
                }
                int bucket = Math.min(11, (int) (hsv[0] / 30f));
                float w = hsv[1] * hsv[2];
                weights[bucket] += w;
                sums[bucket][0] += Color.red(c) * w;
                sums[bucket][1] += Color.green(c) * w;
                sums[bucket][2] += Color.blue(c) * w;
            }
        }
        int first = -1;
        int second = -1;
        for (int i = 0; i < 12; i++) {
            if (first < 0 || weights[i] > weights[first]) {
                second = first;
                first = i;
            } else if (second < 0 || weights[i] > weights[second]) {
                second = i;
            }
        }
        int main = first >= 0 && weights[first] > 0 ? average(sums[first], weights[first]) : 0xFF8899FF;
        int other;
        if (second >= 0 && weights[second] > weights[first] * 0.18f && Math.abs(second - first) > 1) {
            other = average(sums[second], weights[second]);
        } else {
            Color.colorToHSV(main, hsv);
            hsv[0] = (hsv[0] + 120) % 360;
            other = Color.HSVToColor(hsv);
        }
        return new int[] {brighten(main), brighten(other), PhoneMusicPlayer.lighten(main)};
    }

    private static int average(float[] sum, float weight) {
        return Color.rgb(Math.round(sum[0] / weight), Math.round(sum[1] / weight), Math.round(sum[2] / weight));
    }

    /** Lights glow: full saturation and brightness */
    private static int brighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }

    /**
     * Around the artwork: three soft lights of the song's colours circling slowly behind it and breathing while the
     * song plays; paused, they settle and dim. A new song's colours fade in.
     */
    static final class Aura extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix mMatrix = new Matrix();
        private final Shader[] mLights = new Shader[3];
        private int[] mColors = {0xFF8899FF, 0xFFFF88CC, 0xFFCCDDFF};
        private int[] mFrom = mColors;
        private float mMix = 1f;
        private boolean mPlaying;
        private float mEnergy;
        private float mTime;
        private long mLast;

        Aura(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setColors(int[] colors) {
            mFrom = current();
            mColors = colors;
            mMix = 0f;
            mLights[0] = null;
            invalidate();
        }

        void setPlaying(boolean playing) {
            if (mPlaying != playing) {
                mPlaying = playing;
                mLast = 0;
                invalidate();
            }
        }

        private int[] current() {
            int[] mixed = new int[3];
            for (int i = 0; i < 3; i++) {
                mixed[i] = PhoneMusicPlayer.blend(mFrom[i], mColors[i], mMix);
            }
            return mixed;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mLights[0] = null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = mLast == 0 ? 0 : Math.min(0.05f, (now - mLast) / 1000f);
            mLast = now;
            float target = mPlaying ? 1f : 0.45f;
            mEnergy += (target - mEnergy) * Math.min(1f, dt * 2f);
            mTime += dt * (0.25f + 0.55f * mEnergy);
            boolean mixing = mMix < 1f;
            if (mixing) {
                mMix = Math.min(1f, mMix + dt * 1.6f);
                mLights[0] = null;
            }
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0) {
                return;
            }
            float radius = size * 0.30f;
            if (mLights[0] == null) {
                int[] colors = current();
                for (int i = 0; i < 3; i++) {
                    int c = colors[i];
                    mLights[i] = new RadialGradient(0, 0, radius,
                            new int[] {(c & 0x00FFFFFF) | 0xCC000000, (c & 0x00FFFFFF) | 0x66000000, c & 0x00FFFFFF},
                            new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                }
            }
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float orbit = size * 0.18f; // out to the artwork's edges: the lights peek round it as they circle
            float breathe = 0.82f + 0.18f * (float) Math.sin(mTime * 2.1f);
            for (int i = 0; i < 3; i++) {
                double angle = mTime * (i == 1 ? -0.9 : 0.7) + i * Math.PI * 2 / 3;
                float x = cx + orbit * (float) Math.cos(angle);
                float y = cy + orbit * (float) Math.sin(angle) * 0.8f;
                float scale = (i == 2 ? 0.7f : 1f) * (0.9f + 0.15f * breathe * mEnergy);
                mMatrix.setScale(scale, scale);
                mMatrix.postTranslate(x, y);
                mLights[i].setLocalMatrix(mMatrix);
                mPaint.setShader(mLights[i]);
                mPaint.setAlpha(Math.round(255 * (0.55f + 0.45f * mEnergy) * (i == 2 ? 0.7f : 1f)));
                canvas.drawCircle(x, y, radius * scale, mPaint);
            }
            if ((mPlaying || mixing || Math.abs(target - mEnergy) > 0.01f) && isShown()) {
                postInvalidateOnAnimation();
            }
        }
    }

    /** A soft band of light sweeping once across the artwork, top left to bottom right, when a new song starts */
    static final class Glare extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mAt = -1f;
        private ValueAnimator mSweep;

        void sweep() {
            if (mSweep != null) {
                mSweep.cancel();
            }
            mSweep = ValueAnimator.ofFloat(-0.6f, 1.6f);
            mSweep.setDuration(1100);
            mSweep.setStartDelay(150);
            mSweep.setInterpolator(new DecelerateInterpolator(1.4f));
            mSweep.addUpdateListener(a -> {
                mAt = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mSweep.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mAt <= -0.6f || mAt >= 1.6f) {
                return;
            }
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float x = b.left + w * mAt;
            float band = w * 0.35f;
            mPaint.setShader(new LinearGradient(x - band, b.top, x + band, b.top + h * 0.6f,
                    new int[] {0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
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

    /**
     * The player's sky: big soft clouds of the song's colours drifting slowly over a deep shade of them, the way
     * light moves through a stage haze. While the song plays they drift and swell gently; paused, they slow almost to
     * a stop. Darker towards the bottom, so the controls always read well. A new song's colours fade in.
     */
    static final class Sky extends View {
        private static final int COUNT = 5;
        /** Each cloud: its colour (index in the palette), strength, size, and its slow path */
        private static final int[] COLOR = {0, 1, 2, 1, 0};
        private static final float[] STRENGTH = {0.62f, 0.5f, 0.34f, 0.4f, 0.45f};
        private static final float[] SIZE = {0.95f, 0.85f, 0.6f, 0.75f, 0.8f};
        private static final float[] HOME_X = {0.2f, 0.85f, 0.55f, 0.1f, 0.9f};
        private static final float[] HOME_Y = {0.12f, 0.3f, 0.5f, 0.72f, 0.88f};
        private static final float[] SPEED_X = {0.21f, -0.17f, 0.29f, 0.19f, -0.23f};
        private static final float[] SPEED_Y = {0.15f, 0.23f, -0.19f, -0.13f, 0.17f};
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadePaint = new Paint();
        private final Matrix mMatrix = new Matrix();
        private final Shader[] mClouds = new Shader[COUNT];
        private int[] mColors = {0xFF8899FF, 0xFFFF88CC, 0xFFCCDDFF};
        private int[] mFrom = mColors;
        private float mMix = 1f;
        private boolean mPlaying;
        private float mEnergy;
        private float mTime;
        private long mLast;

        Sky(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setColors(int[] colors) {
            mFrom = current();
            mColors = colors;
            mMix = 0f;
            mClouds[0] = null;
            invalidate();
        }

        void setPlaying(boolean playing) {
            if (mPlaying != playing) {
                mPlaying = playing;
                mLast = 0;
                invalidate();
            }
        }

        private int[] current() {
            int[] mixed = new int[3];
            for (int i = 0; i < 3; i++) {
                mixed[i] = PhoneMusicPlayer.blend(mFrom[i], mColors[i], mMix);
            }
            return mixed;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            // Darker towards the bottom (the controls), a little at the very top (the status bar)
            mShadePaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[] {0x47000000, 0x00000000, 0x33000000, 0xC7000000},
                    new float[] {0f, 0.22f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = mLast == 0 ? 0 : Math.min(0.05f, (now - mLast) / 1000f);
            mLast = now;
            float target = mPlaying ? 1f : 0.15f;
            mEnergy += (target - mEnergy) * Math.min(1f, dt * 1.2f);
            mTime += dt * (0.12f + 0.88f * mEnergy);
            boolean mixing = mMix < 1f;
            if (mixing) {
                mMix = Math.min(1f, mMix + dt * 1.2f);
                mClouds[0] = null;
            }
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (mClouds[0] == null) {
                int[] colors = current();
                for (int i = 0; i < COUNT; i++) {
                    int c = colors[COLOR[i]];
                    // A unit cloud: dense in the middle, a long soft fall-off
                    mClouds[i] = new RadialGradient(0, 0, 1f,
                            new int[] {(c & 0x00FFFFFF) | 0xFF000000, (c & 0x00FFFFFF) | 0x8C000000,
                                    (c & 0x00FFFFFF) | 0x26000000, c & 0x00FFFFFF},
                            new float[] {0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP);
                }
            }
            float breathe = (float) Math.sin(mTime * 1.3f);
            for (int i = 0; i < COUNT; i++) {
                float x = w * (HOME_X[i] + 0.22f * (float) Math.sin(mTime * SPEED_X[i] + i * 1.7f));
                float y = h * (HOME_Y[i] + 0.1f * (float) Math.cos(mTime * SPEED_Y[i] + i * 2.3f));
                float radius = w * SIZE[i] * (1f + 0.06f * mEnergy * (float) Math.sin(mTime * 0.9f + i));
                if (i == 0) {
                    radius *= 1f + 0.05f * mEnergy * breathe; // the main light swells softly
                }
                mMatrix.setScale(radius, radius);
                mMatrix.postTranslate(x, y);
                mClouds[i].setLocalMatrix(mMatrix);
                mPaint.setShader(mClouds[i]);
                mPaint.setAlpha(Math.round(255 * STRENGTH[i] * (0.7f + 0.3f * mEnergy)));
                canvas.drawCircle(x, y, radius, mPaint);
            }
            canvas.drawRect(0, 0, w, h, mShadePaint);
            if ((mPlaying || mixing || Math.abs(target - mEnergy) > 0.01f) && isShown()) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * A glow behind a button in the song's colour (the play button's): it breathes while the song plays, and dims
     * when paused.
     */
    static final class Halo extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mColor = 0xFFCCDDFF;
        private boolean mPlaying;
        private float mEnergy;
        private float mTime;
        private long mLast;

        Halo(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setColor(int color) {
            if (mColor != color) {
                mColor = color;
                mPaint.setShader(null);
                invalidate();
            }
        }

        void setPlaying(boolean playing) {
            if (mPlaying != playing) {
                mPlaying = playing;
                mLast = 0;
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mPaint.setShader(null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = mLast == 0 ? 0 : Math.min(0.05f, (now - mLast) / 1000f);
            mLast = now;
            float target = mPlaying ? 1f : 0.35f;
            mEnergy += (target - mEnergy) * Math.min(1f, dt * 3f);
            mTime += dt;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            if (r <= 0) {
                return;
            }
            if (mPaint.getShader() == null) {
                int c = mColor & 0x00FFFFFF;
                mPaint.setShader(new RadialGradient(getWidth() / 2f, getHeight() / 2f, r,
                        new int[] {c | 0xCC000000, c | 0x8C000000, c | 0x2E000000, c},
                        new float[] {0f, 0.42f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            }
            float breathe = 0.5f + 0.5f * (float) Math.sin(mTime * 2.4f);
            float scale = 0.86f + 0.14f * mEnergy * breathe;
            mPaint.setAlpha(Math.round(255 * (0.35f + 0.55f * mEnergy * (0.75f + 0.25f * breathe))));
            canvas.save();
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, mPaint);
            canvas.restore();
            if ((mPlaying || Math.abs(target - mEnergy) > 0.01f) && isShown()) {
                postInvalidateOnAnimation();
            }
        }
    }
}
