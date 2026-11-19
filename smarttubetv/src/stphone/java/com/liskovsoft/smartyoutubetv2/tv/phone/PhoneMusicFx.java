package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
     * The player's sky, in layers, all in the song's colours: big soft clouds drifting slowly, the way light moves
     * through a stage haze; beams of light falling from above and swaying; two ribbons of light flowing near the
     * bottom; out-of-focus specks of light (bokeh) rising and twinkling; a fine film grain over all of it. While the
     * song plays it all moves gently; paused, it slows almost to a stop. Darker towards the bottom, so the controls
     * always read well. A new song's colours fade in.
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
        /** Beams: their angles (degrees from straight down), widths and strengths */
        private static final float[] RAY_ANGLE = {-30f, -14f, -2f, 11f, 26f};
        private static final float[] RAY_WIDTH = {0.09f, 0.14f, 0.07f, 0.12f, 0.08f};
        private static final float[] RAY_STRENGTH = {0.55f, 0.8f, 0.6f, 0.75f, 0.5f};
        private final Paint mRayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mRay = new Path();
        private Shader mRayShader;
        /** Ribbons */
        private final Paint mRibbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mRibbon = new Path();
        private final Shader[] mRibbonShaders = new Shader[2];
        /** Bokeh: where each speck is (fractions of the screen), its size (dp), speed, twinkle and colour */
        private static final int SPECKS = 22;
        private final java.util.Random mRandom = new java.util.Random(11);
        private final float[] mBx = new float[SPECKS];
        private final float[] mBy = new float[SPECKS];
        private final float[] mBSize = new float[SPECKS];
        private final float[] mBSpeed = new float[SPECKS];
        private final float[] mBPhase = new float[SPECKS];
        private final int[] mBColor = new int[SPECKS];
        private final Shader[] mSpeckShaders = new Shader[3];
        private final Paint mSpeckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGrain = new Paint();
        private final float mDp;
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
            mDp = context.getResources().getDisplayMetrics().density;
            for (int i = 0; i < SPECKS; i++) {
                spawn(i, mRandom.nextFloat());
            }
            // Film grain: a little tile of random light and dark dots, repeated
            int size = 96;
            int[] dots = new int[size * size];
            for (int i = 0; i < dots.length; i++) {
                int v = mRandom.nextInt(256);
                dots[i] = 0x1A000000 | (v << 16) | (v << 8) | v;
            }
            Bitmap grain = Bitmap.createBitmap(dots, size, size, Bitmap.Config.ARGB_8888);
            mGrain.setShader(new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            mGrain.setAlpha(110);
        }

        /** A speck starts (again) at the given height, somewhere across */
        private void spawn(int i, float y) {
            mBx[i] = mRandom.nextFloat();
            mBy[i] = y;
            mBSize[i] = 4f + mRandom.nextFloat() * mRandom.nextFloat() * 26f;
            mBSpeed[i] = 0.008f + mRandom.nextFloat() * 0.02f;
            mBPhase[i] = mRandom.nextFloat() * 6.28f;
            mBColor[i] = mRandom.nextInt(3);
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
            mClouds[0] = null;
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
                int light = colors[2] & 0x00FFFFFF;
                // A beam: brightest where it starts, fading out down the screen (drawn pointing down)
                mRayShader = new LinearGradient(0, 0, 0, h * 1.15f, new int[] {light | 0x40000000, light | 0x14000000, light},
                        new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                for (int i = 0; i < 2; i++) {
                    int c = colors[i == 0 ? 0 : 1] & 0x00FFFFFF;
                    float top = h * (i == 0 ? 0.70f : 0.80f);
                    mRibbonShaders[i] = new LinearGradient(0, top, 0, top + h * 0.16f,
                            new int[] {c | 0x33000000, c | 0x0D000000, c}, new float[] {0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
                }
                for (int i = 0; i < 3; i++) {
                    int c = PhoneMusicPlayer.blend(colors[i], Color.WHITE, 0.4f) & 0x00FFFFFF;
                    // A speck out of focus: a soft disc with a slightly brighter rim, like a lens makes them
                    mSpeckShaders[i] = new RadialGradient(0, 0, 1f, new int[] {c | 0x66000000, c | 0x80000000, c | 0x26000000, c},
                            new float[] {0f, 0.72f, 0.86f, 1f}, Shader.TileMode.CLAMP);
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
            drawRays(canvas, w, h);
            drawRibbons(canvas, w, h);
            drawSpecks(canvas, w, h, dt);
            canvas.drawRect(0, 0, w, h, mShadePaint);
            canvas.drawRect(0, 0, w, h, mGrain);
            if ((mPlaying || mixing || Math.abs(target - mEnergy) > 0.01f) && isShown()) {
                postInvalidateOnAnimation();
            }
        }

        /** Beams of light from above the top of the screen, swaying slowly, each flickering a little */
        private void drawRays(Canvas canvas, float w, float h) {
            float ox = w * (0.5f + 0.12f * (float) Math.sin(mTime * 0.07f));
            float oy = -h * 0.1f;
            mRayPaint.setShader(mRayShader);
            for (int i = 0; i < RAY_ANGLE.length; i++) {
                float angle = RAY_ANGLE[i] + 5f * (float) Math.sin(mTime * 0.11f + i * 1.3f);
                float half = w * RAY_WIDTH[i] * (1f + 0.25f * (float) Math.sin(mTime * 0.17f + i));
                float flicker = 0.75f + 0.25f * (float) Math.sin(mTime * 0.5f + i * 2.1f);
                mRay.rewind();
                mRay.moveTo(-w * 0.012f, 0);
                mRay.lineTo(w * 0.012f, 0);
                mRay.lineTo(half, h * 1.15f);
                mRay.lineTo(-half, h * 1.15f);
                mRay.close();
                mRayPaint.setAlpha(Math.round(255 * RAY_STRENGTH[i] * flicker * (0.6f + 0.4f * mEnergy)));
                canvas.save();
                canvas.translate(ox, oy);
                canvas.rotate(angle);
                canvas.drawPath(mRay, mRayPaint);
                canvas.restore();
            }
        }

        /** Two ribbons of light flowing across near the bottom, one behind the other */
        private void drawRibbons(Canvas canvas, float w, float h) {
            for (int r = 0; r < 2; r++) {
                float base = h * (r == 0 ? 0.74f : 0.84f);
                float amplitude = (r == 0 ? 26f : 18f) * mDp;
                float length = w * (r == 0 ? 1.1f : 0.8f);
                float phase = mTime * (r == 0 ? 0.6f : -0.45f);
                mRibbon.rewind();
                mRibbon.moveTo(0, h);
                int steps = 32;
                for (int i = 0; i <= steps; i++) {
                    float x = w * i / steps;
                    float y = base + amplitude * (float) Math.sin(x / length * 6.2832f + phase)
                            + amplitude * 0.4f * (float) Math.sin(x / length * 13f - phase * 1.7f);
                    mRibbon.lineTo(x, y);
                }
                mRibbon.lineTo(w, h);
                mRibbon.close();
                mRibbonPaint.setShader(mRibbonShaders[r]);
                mRibbonPaint.setAlpha(Math.round(255 * (0.7f + 0.3f * mEnergy)));
                canvas.drawPath(mRibbon, mRibbonPaint);
            }
        }

        /** Specks of light out of focus, rising slowly, swaying and twinkling */
        private void drawSpecks(Canvas canvas, float w, float h, float dt) {
            float pace = 0.15f + 0.85f * mEnergy;
            for (int i = 0; i < SPECKS; i++) {
                mBy[i] -= dt * mBSpeed[i] * pace;
                if (mBy[i] < -0.05f) {
                    spawn(i, 1.05f);
                }
                float x = w * mBx[i] + 14f * mDp * (float) Math.sin(mTime * 0.4f + mBPhase[i]);
                float y = h * mBy[i];
                float radius = mBSize[i] * mDp;
                float twinkle = 0.5f + 0.5f * (float) Math.sin(mTime * (0.6f + (i % 5) * 0.15f) + mBPhase[i]);
                // Fade in from the bottom, out towards the top
                float edge = Math.min(1f, Math.min(mBy[i] / 0.15f, (1.05f - mBy[i]) / 0.2f));
                if (edge <= 0) {
                    continue;
                }
                mMatrix.setScale(radius, radius);
                mMatrix.postTranslate(x, y);
                mSpeckShaders[mBColor[i]].setLocalMatrix(mMatrix);
                mSpeckPaint.setShader(mSpeckShaders[mBColor[i]]);
                mSpeckPaint.setAlpha(Math.round(255 * edge * (0.25f + 0.55f * twinkle)));
                canvas.drawCircle(x, y, radius, mSpeckPaint);
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
