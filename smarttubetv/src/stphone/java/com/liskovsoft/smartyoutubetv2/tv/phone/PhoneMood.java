package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The animation behind the music player: each song gets one of many scenes (hearts, confetti, fireflies, bubbles,
 * shooting stars, petals, lanterns, neon waves, a galaxy, butterflies, music notes, light through a prism…), the
 * same one every time it plays and never the one the song before had.
 */
final class PhoneMood {
    enum Scene { HEARTS, SPARKLES, AURORA, RINGS, EMBERS, BOKEH, FIREFLIES, CONFETTI, BUBBLES, STARS, PETALS, SNOW,
        LANTERNS, NEON, GALAXY, BUTTERFLIES, NOTES, PRISM }

    /** Bright colours the colourful scenes take from */
    private static final int[] RAINBOW = {0xFFFF5E7E, 0xFFFFC23D, 0xFF4DD8FF, 0xFF7CFF8A, 0xFFB78CFF, 0xFFFF8F3D,
            0xFFFF7AD9};

    private static Scene sLast;

    private PhoneMood() {
    }

    /** This song's scene: picked from its id, so it's always the same one, but not the last song's */
    static Scene sceneFor(String videoId) {
        Scene[] all = Scene.values();
        int index = videoId != null ? (videoId.hashCode() & 0x7FFFFFFF) % all.length : new Random().nextInt(all.length);
        Scene scene = all[index];
        if (scene == sLast) {
            scene = all[(index + 1 + (videoId != null ? videoId.length() % (all.length - 1) : 0)) % all.length];
        }
        sLast = scene;
        return scene;
    }

    /**
     * Drawn behind everything in the player, over its background and under the artwork and controls. It runs while
     * the song plays, eases to a stop when it pauses, rests when the player is hidden, and stands still when the
     * phone's animations are off. A new song's scene fades in as the old one fades out.
     */
    static final class Backdrop extends View {
        private static final int COUNT = 40;
        private final Random mRandom = new Random(7);
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final Path mHeart = new Path();
        private final Path mStar = new Path();
        private final RectF mRect = new RectF();
        private final float[] mX = new float[COUNT];
        private final float[] mY = new float[COUNT];
        private final float[] mSize = new float[COUNT];
        private final float[] mSpeed = new float[COUNT];
        private final float[] mPhase = new float[COUNT];
        private final float mDp;
        private final LinearGradient[] mBands = new LinearGradient[3];
        private boolean mBandsStale = true;
        private Shader mPrism;

        private Scene mMood = Scene.BOKEH;
        private Scene mNext;
        private int mColor = 0xFF8888AA;
        private boolean mRunning;
        /** 0 to 1: how fast time goes (eases in on play, out on pause) */
        private float mSpeedNow;
        /** 0 to 1: how much of the scene shows (fades out and in when it changes) */
        private float mFade;
        private float mTime;
        private long mLastFrame;

        Backdrop(Context context) {
            super(context);
            mDp = context.getResources().getDisplayMetrics().density;
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeCap(Paint.Cap.ROUND);
            mText.setTextAlign(Paint.Align.CENTER);
            // A heart and a four-pointed star, one unit across
            mHeart.moveTo(0f, 0.3f);
            mHeart.cubicTo(-0.05f, 0.2f, -0.5f, -0.05f, -0.5f, -0.25f);
            mHeart.cubicTo(-0.5f, -0.5f, -0.15f, -0.55f, 0f, -0.3f);
            mHeart.cubicTo(0.15f, -0.55f, 0.5f, -0.5f, 0.5f, -0.25f);
            mHeart.cubicTo(0.5f, -0.05f, 0.05f, 0.2f, 0f, 0.3f);
            mHeart.close();
            mStar.moveTo(0f, -0.5f);
            mStar.quadTo(0.06f, -0.06f, 0.5f, 0f);
            mStar.quadTo(0.06f, 0.06f, 0f, 0.5f);
            mStar.quadTo(-0.06f, 0.06f, -0.5f, 0f);
            mStar.quadTo(-0.06f, -0.06f, 0f, -0.5f);
            mStar.close();
        }

        void setScene(Scene scene) {
            if (scene == null || scene == mMood && mNext == null) {
                return;
            }
            mNext = scene;
            if (mFade <= 0.01f || getWidth() == 0) {
                swap();
            }
            invalidate();
        }

        void setColor(int color) {
            mColor = color;
            mBandsStale = true;
            invalidate();
        }

        void setRunning(boolean running) {
            mRunning = running;
            mLastFrame = 0;
            invalidate();
        }

        private void swap() {
            mMood = mNext;
            mNext = null;
            mFade = 0f;
            seed();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            seed();
            mBandsStale = true;
            mPrism = null;
        }

        /** The particles' places, sizes, speeds and phases for this scene */
        private void seed() {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            for (int i = 0; i < COUNT; i++) {
                mX[i] = mRandom.nextFloat() * w;
                mY[i] = mRandom.nextFloat() * h;
                mSize[i] = mRandom.nextFloat();
                mSpeed[i] = 0.6f + 0.8f * mRandom.nextFloat();
                mPhase[i] = (float) (mRandom.nextFloat() * Math.PI * 2);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = mLastFrame == 0 ? 0f : Math.min(0.05f, (now - mLastFrame) / 1000f);
            mLastFrame = now;
            boolean motion = ValueAnimator.areAnimatorsEnabled();
            float target = mRunning && motion ? 1f : 0f;
            mSpeedNow += (target - mSpeedNow) * Math.min(1f, dt * 2.5f);
            if (Math.abs(target - mSpeedNow) < 0.01f) {
                mSpeedNow = target;
            }
            // Changing scene: the old one fades out, the new one in
            float fadeTarget = mNext != null ? 0f : 1f;
            mFade += (fadeTarget - mFade) * Math.min(1f, (motion ? dt : 1f) * 1.6f);
            if (mNext != null && mFade < 0.02f) {
                swap();
            }
            if (!motion && mNext == null) {
                mFade = 1f;
            }
            float step = dt * mSpeedNow;
            mTime += step;

            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.FILL);
            switch (mMood) {
                case HEARTS: hearts(canvas, step); break;
                case SPARKLES: sparkles(canvas, step); break;
                case AURORA: aurora(canvas); break;
                case RINGS: rings(canvas, step); break;
                case EMBERS: embers(canvas, step); break;
                case FIREFLIES: fireflies(canvas); break;
                case CONFETTI: confetti(canvas, step); break;
                case BUBBLES: bubbles(canvas, step); break;
                case STARS: stars(canvas); break;
                case PETALS: petals(canvas, step); break;
                case SNOW: snow(canvas, step); break;
                case LANTERNS: lanterns(canvas, step); break;
                case NEON: neon(canvas); break;
                case GALAXY: galaxy(canvas); break;
                case BUTTERFLIES: butterflies(canvas); break;
                case NOTES: notes(canvas, step); break;
                case PRISM: prism(canvas); break;
                default: bokeh(canvas, step); break;
            }
            boolean settled = mSpeedNow == 0f && (mNext == null && Math.abs(mFade - 1f) < 0.01f);
            if (!settled && isShown()) {
                postInvalidateOnAnimation();
            }
        }

        /** The song's colour turned towards the scene's, this faint (and fading with the scene) */
        private int tint(int towards, float amount, float alpha) {
            return solid(towards, amount, alpha * mFade);
        }

        private int solid(int towards, float amount, float alpha) {
            int color = PhoneMusicPlayer.blend(PhoneMusicPlayer.lighten(mColor), towards, amount);
            return (color & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255) << 24);
        }

        /** A colour as it is, this opaque (and fading with the scene) */
        private int pure(int color, float alpha) {
            return (color & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, alpha * mFade)) * 255) << 24);
        }

        /** Something that came off one edge comes back on the other */
        private float wrap(float value, float size, float margin) {
            float span = size + 2 * margin;
            float v = (value + margin) % span;
            return (v < 0 ? v + span : v) - margin;
        }

        /** Hearts: soft hearts rising, swaying a little, fading out near the top */
        private void hearts(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 14; i++) {
                mY[i] -= step * mDp * (14 + 16 * mSpeed[i]);
                if (mY[i] < -40 * mDp) {
                    mY[i] = h + 40 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float size = mDp * (10 + 18 * mSize[i]);
                float x = mX[i] + mDp * 14 * (float) Math.sin(mTime * 0.7f * mSpeed[i] + mPhase[i]);
                float life = Math.min(1f, Math.max(0f, mY[i] / (h * 0.35f)));
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(tint(i % 3 == 0 ? 0xFFFF9FC6 : 0xFFFF5C93, 0.75f, (0.22f + 0.22f * mSize[i]) * life));
                canvas.save();
                canvas.translate(x, mY[i]);
                canvas.rotate(12f * (float) Math.sin(mTime * 0.5f + mPhase[i]));
                canvas.scale(size, size);
                canvas.drawPath(mHeart, mPaint);
                canvas.restore();
            }
        }

        /** Sparkles: twinkling in warm colours, drifting up */
        private void sparkles(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            int[] colors = {0xFFFFD36E, 0xFFFF8FC8, 0xFFFFFFFF};
            for (int i = 0; i < 28; i++) {
                mY[i] -= step * mDp * 8 * mSpeed[i];
                if (mY[i] < -10 * mDp) {
                    mY[i] = h + 10 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float twinkle = (float) Math.pow(Math.max(0, Math.sin(mTime * 1.6f * mSpeed[i] + mPhase[i])), 2);
                float size = mDp * (5 + 9 * mSize[i]) * (0.6f + 0.4f * twinkle);
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(tint(colors[i % 3], 0.8f, 0.12f + 0.45f * twinkle));
                canvas.save();
                canvas.translate(mX[i], mY[i]);
                canvas.rotate(mTime * 20 * (i % 2 == 0 ? 1 : -1));
                canvas.scale(size, size);
                canvas.drawPath(mStar, mPaint);
                canvas.restore();
            }
        }

        /** Aurora: three bands, waving slowly across the top half */
        private void aurora(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int[] tones = {0xFF6FE3D0, 0xFF8F9BFF, 0xFFC08FFF};
            for (int band = 0; band < 3; band++) {
                float top = h * (0.12f + 0.16f * band);
                float depth = h * 0.22f;
                if (mBandsStale || mBands[band] == null) {
                    mBands[band] = new LinearGradient(0, top - 30 * mDp, 0, top + depth,
                            solid(tones[band], 0.75f, 0.28f), Color.TRANSPARENT, Shader.TileMode.CLAMP);
                }
                mPath.reset();
                float amplitude = 26 * mDp;
                for (int x = 0; x <= w; x += Math.max(8, w / 40)) {
                    float y = top + amplitude * (float) Math.sin(x / (w * 0.32f) + mTime * (0.35f + 0.12f * band) + band * 1.7f)
                            + amplitude * 0.5f * (float) Math.sin(x / (w * 0.13f) - mTime * 0.22f + band);
                    if (x == 0) {
                        mPath.moveTo(x, y);
                    } else {
                        mPath.lineTo(x, y);
                    }
                }
                mPath.lineTo(w, top + depth);
                mPath.lineTo(0, top + depth);
                mPath.close();
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setShader(mBands[band]);
                mPaint.setAlpha(Math.round(255 * mFade));
                canvas.drawPath(mPath, mPaint);
                mPaint.setShader(null);
            }
            mBandsStale = false;
        }

        /** Rings: pulsing out from behind the artwork on the beat, and sparks flying out */
        private void rings(Canvas canvas, float step) {
            int w = getWidth();
            float cx = w / 2f;
            float cy = getHeight() * 0.33f;
            mStroke.setStrokeWidth(1.5f * mDp);
            for (int i = 0; i < 3; i++) {
                float p = (mTime / 2.4f + i / 3f) % 1f;
                float radius = w * (0.25f + 0.65f * p);
                mStroke.setColor(tint(Color.WHITE, 0.3f, 0.22f * (1 - p) * (1 - p)));
                canvas.drawCircle(cx, cy, radius, mStroke);
            }
            mPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 18; i++) {
                float p = (mTime * 0.25f * mSpeed[i] + mPhase[i] / 6.2832f) % 1f;
                float distance = w * (0.3f + 0.6f * p);
                float angle = mPhase[i] * 3;
                mPaint.setColor(tint(Color.WHITE, 0.3f, 0.25f * (1 - p)));
                canvas.drawCircle(cx + distance * (float) Math.cos(angle), cy + distance * (float) Math.sin(angle),
                        mDp * (1.2f + 1.5f * mSize[i]), mPaint);
            }
        }

        /** Embers: rising and flickering, under slow beams of light */
        private void embers(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            mPaint.setStyle(Paint.Style.FILL);
            // Beams from above, turning slowly
            for (int i = 0; i < 5; i++) {
                float angle = (float) Math.toRadians(-30 + i * 15 + 6 * Math.sin(mTime * 0.15f + i));
                mPath.reset();
                mPath.moveTo(w / 2f, -h * 0.1f);
                float reach = h * 1.1f;
                mPath.lineTo(w / 2f + reach * (float) Math.sin(angle - 0.05f), reach * (float) Math.cos(angle - 0.05f));
                mPath.lineTo(w / 2f + reach * (float) Math.sin(angle + 0.05f), reach * (float) Math.cos(angle + 0.05f));
                mPath.close();
                mPaint.setColor(tint(0xFFFFC46B, 0.6f, 0.035f + 0.02f * (float) Math.sin(mTime * 0.4f + i)));
                canvas.drawPath(mPath, mPaint);
            }
            for (int i = 0; i < 24; i++) {
                mY[i] -= step * mDp * (10 + 14 * mSpeed[i]);
                if (mY[i] < -10 * mDp) {
                    mY[i] = h + 10 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float flicker = 0.6f + 0.4f * (float) Math.sin(mTime * 3 * mSpeed[i] + mPhase[i]);
                float x = mX[i] + mDp * 8 * (float) Math.sin(mTime * 0.8f + mPhase[i]);
                float life = Math.min(1f, Math.max(0f, mY[i] / (h * 0.4f)));
                float size = mDp * (1.5f + 2.5f * mSize[i]);
                mPaint.setColor(tint(0xFFFFB04D, 0.7f, 0.07f * flicker * life));
                canvas.drawCircle(x, mY[i], size * 3.2f, mPaint); // its glow
                mPaint.setColor(tint(0xFFFFD99A, 0.7f, 0.35f * flicker * life));
                canvas.drawCircle(x, mY[i], size, mPaint);
            }
        }

        /** Bokeh: big soft circles of the song's colour, drifting */
        private void bokeh(Canvas canvas, float step) {
            mPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 12; i++) {
                float x = mX[i] + mDp * 40 * (float) Math.sin(mTime * 0.12f * mSpeed[i] + mPhase[i]);
                float y = mY[i] + mDp * 30 * (float) Math.cos(mTime * 0.1f * mSpeed[i] + mPhase[i]);
                float radius = mDp * (18 + 44 * mSize[i]);
                float breathe = 0.5f + 0.5f * (float) Math.sin(mTime * 0.5f + mPhase[i]);
                mPaint.setColor(tint(RAINBOW[i % RAINBOW.length], 0.45f, 0.06f + 0.07f * breathe));
                canvas.drawCircle(x, y, radius, mPaint);
            }
        }

        /** Fireflies: little lights wandering on lazy curves, glowing and dimming one by one */
        private void fireflies(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 26; i++) {
                float x = wrap(mX[i] + mDp * 60 * (float) Math.sin(mTime * 0.23f * mSpeed[i] + mPhase[i])
                        + mDp * 25 * (float) Math.sin(mTime * 0.61f + mPhase[i] * 2), w, 20 * mDp);
                float y = wrap(mY[i] + mDp * 45 * (float) Math.cos(mTime * 0.19f * mSpeed[i] + mPhase[i])
                        - mTime * mDp * 4 * mSpeed[i], h, 20 * mDp);
                float glow = (float) Math.pow(Math.max(0, Math.sin(mTime * 1.1f * mSpeed[i] + mPhase[i])), 3);
                int color = i % 4 == 0 ? 0xFFFFE27A : i % 4 == 1 ? 0xFFC8FF7A : PhoneMusicPlayer.blend(0xFFFFF3A0, PhoneMusicPlayer.lighten(mColor), 0.3f);
                mPaint.setColor(pure(color, 0.05f + 0.16f * glow));
                canvas.drawCircle(x, y, mDp * 14 * (0.6f + 0.4f * mSize[i]), mPaint);
                mPaint.setColor(pure(color, 0.25f + 0.65f * glow));
                canvas.drawCircle(x, y, mDp * (1.6f + 1.2f * mSize[i]), mPaint);
            }
        }

        /** Confetti: bright paper bits falling, tumbling and swaying */
        private void confetti(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 36; i++) {
                mY[i] += step * mDp * (34 + 36 * mSpeed[i]);
                if (mY[i] > h + 20 * mDp) {
                    mY[i] = -20 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float x = mX[i] + mDp * 22 * (float) Math.sin(mTime * 0.9f * mSpeed[i] + mPhase[i]);
                float tumble = (float) Math.cos(mTime * 3.2f * mSpeed[i] + mPhase[i]);
                float width = mDp * (4 + 4 * mSize[i]);
                float length = width * 1.7f;
                mPaint.setColor(pure(RAINBOW[i % RAINBOW.length], 0.3f + 0.3f * Math.abs(tumble)));
                canvas.save();
                canvas.translate(x, mY[i]);
                canvas.rotate(mTime * 90 * mSpeed[i] * (i % 2 == 0 ? 1 : -1) + mPhase[i] * 57);
                canvas.scale(1f, Math.max(0.12f, Math.abs(tumble)));
                mRect.set(-width / 2, -length / 2, width / 2, length / 2);
                canvas.drawRoundRect(mRect, mDp, mDp, mPaint);
                canvas.restore();
            }
        }

        /** Bubbles: soap bubbles rising and wobbling, their rims shimmering through the colours */
        private void bubbles(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            float[] hsv = {0f, 0.55f, 1f};
            mStroke.setStrokeWidth(1.4f * mDp);
            for (int i = 0; i < 16; i++) {
                mY[i] -= step * mDp * (16 + 20 * mSpeed[i]);
                float radius = mDp * (10 + 26 * mSize[i]);
                if (mY[i] < -radius * 2) {
                    mY[i] = h + radius * 2;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float x = mX[i] + mDp * 18 * (float) Math.sin(mTime * 0.8f * mSpeed[i] + mPhase[i]);
                float life = Math.min(1f, Math.max(0f, mY[i] / (h * 0.25f)));
                float wobble = 1f + 0.05f * (float) Math.sin(mTime * 3f + mPhase[i]);
                hsv[0] = (mTime * 40 + i * 47) % 360;
                mPaint.setColor(pure(Color.WHITE, 0.04f * life));
                canvas.drawOval(x - radius * wobble, mY[i] - radius / wobble, x + radius * wobble, mY[i] + radius / wobble, mPaint);
                mStroke.setColor(pure(Color.HSVToColor(hsv), 0.42f * life));
                canvas.drawOval(x - radius * wobble, mY[i] - radius / wobble, x + radius * wobble, mY[i] + radius / wobble, mStroke);
                // The shine
                mStroke.setColor(pure(Color.WHITE, 0.55f * life));
                mRect.set(x - radius * 0.65f, mY[i] - radius * 0.65f, x + radius * 0.65f, mY[i] + radius * 0.65f);
                canvas.drawArc(mRect, 200, 50, false, mStroke);
            }
        }

        /** Stars: a sky of twinkling stars, a shooting star now and then */
        private void stars(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 38; i++) {
                float twinkle = 0.5f + 0.5f * (float) Math.sin(mTime * (0.8f + mSpeed[i]) + mPhase[i]);
                float size = mDp * (0.8f + 1.6f * mSize[i] * mSize[i]);
                int color = i % 5 == 0 ? 0xFFBFD8FF : i % 7 == 0 ? 0xFFFFE6B0 : Color.WHITE;
                mPaint.setColor(pure(color, 0.15f + 0.6f * twinkle * (0.4f + 0.6f * mSize[i])));
                canvas.drawCircle(mX[i], mY[i] * 0.85f, size, mPaint);
                if (mSize[i] > 0.85f) {
                    mPaint.setColor(pure(color, 0.6f * twinkle));
                    canvas.save();
                    canvas.translate(mX[i], mY[i] * 0.85f);
                    canvas.scale(size * 6, size * 6);
                    canvas.drawPath(mStar, mPaint);
                    canvas.restore();
                }
            }
            // A shooting star every few seconds, from the top towards the lower left
            float p = (mTime * 0.28f) % 1f;
            int shot = (int) (mTime * 0.28f);
            if (p < 0.35f) {
                float q = p / 0.35f;
                Random place = new Random(shot * 31L);
                float sx = w * (0.4f + 0.6f * place.nextFloat());
                float sy = h * 0.05f + h * 0.25f * place.nextFloat();
                float dx = -w * 0.55f;
                float dy = h * 0.22f;
                float hx = sx + dx * q;
                float hy = sy + dy * q;
                float tail = 0.25f;
                float tx = hx - dx * tail;
                float ty = hy - dy * tail;
                float alpha = (float) Math.sin(q * Math.PI);
                mStroke.setStrokeWidth(2f * mDp);
                mStroke.setShader(new LinearGradient(tx, ty, hx, hy, 0x00FFFFFF, pure(Color.WHITE, 0.9f * alpha), Shader.TileMode.CLAMP));
                canvas.drawLine(tx, ty, hx, hy, mStroke);
                mStroke.setShader(null);
                mPaint.setColor(pure(Color.WHITE, 0.9f * alpha));
                canvas.drawCircle(hx, hy, 2.2f * mDp, mPaint);
            }
        }

        /** Petals: blossom petals drifting down, turning over as they fall */
        private void petals(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 24; i++) {
                mY[i] += step * mDp * (20 + 18 * mSpeed[i]);
                mX[i] += step * mDp * 10;
                if (mY[i] > h + 20 * mDp) {
                    mY[i] = -20 * mDp;
                    mX[i] = mRandom.nextFloat() * w - 40 * mDp;
                }
                float x = mX[i] + mDp * 30 * (float) Math.sin(mTime * 0.7f * mSpeed[i] + mPhase[i]);
                float turn = (float) Math.cos(mTime * 1.6f * mSpeed[i] + mPhase[i]);
                float size = mDp * (6 + 7 * mSize[i]);
                mPaint.setColor(pure(i % 3 == 0 ? 0xFFFFE3EE : 0xFFFFA9CB, 0.35f + 0.25f * Math.abs(turn)));
                canvas.save();
                canvas.translate(x, mY[i]);
                canvas.rotate(mTime * 40 * mSpeed[i] + mPhase[i] * 57);
                canvas.scale(Math.max(0.2f, Math.abs(turn)), 1f);
                mPath.rewind();
                mPath.moveTo(0, -size);
                mPath.cubicTo(size * 0.9f, -size * 0.6f, size * 0.7f, size * 0.6f, 0, size);
                mPath.cubicTo(-size * 0.7f, size * 0.6f, -size * 0.9f, -size * 0.6f, 0, -size);
                canvas.drawPath(mPath, mPaint);
                canvas.restore();
            }
        }

        /** Snow: soft flakes drifting down, the near ones bigger and faster */
        private void snow(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 38; i++) {
                float near = mSize[i];
                mY[i] += step * mDp * (12 + 30 * near);
                if (mY[i] > h + 10 * mDp) {
                    mY[i] = -10 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float x = mX[i] + mDp * 16 * (float) Math.sin(mTime * 0.5f * mSpeed[i] + mPhase[i]);
                float radius = mDp * (1.2f + 3.2f * near);
                mPaint.setColor(pure(Color.WHITE, 0.08f * near));
                canvas.drawCircle(x, mY[i], radius * 3, mPaint);
                mPaint.setColor(pure(0xFFF2F7FF, 0.3f + 0.45f * near));
                canvas.drawCircle(x, mY[i], radius, mPaint);
            }
        }

        /** Lanterns: warm paper lanterns rising slowly into the night, glowing */
        private void lanterns(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 11; i++) {
                mY[i] -= step * mDp * (9 + 9 * mSpeed[i]);
                float scale = 0.55f + 0.7f * mSize[i];
                if (mY[i] < -40 * mDp) {
                    mY[i] = h + 40 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float x = mX[i] + mDp * 10 * (float) Math.sin(mTime * 0.4f + mPhase[i]);
                float life = Math.min(1f, Math.max(0f, mY[i] / (h * 0.3f)));
                float flicker = 0.85f + 0.15f * (float) Math.sin(mTime * 5f * mSpeed[i] + mPhase[i]);
                float bw = mDp * 11 * scale;
                float bh = mDp * 15 * scale;
                mPaint.setColor(pure(0xFFFF9A3D, 0.12f * life * flicker));
                canvas.drawCircle(x, mY[i], bh * 2.4f, mPaint);
                mPaint.setColor(pure(0xFFFF8A3D, 0.6f * life));
                mRect.set(x - bw, mY[i] - bh, x + bw, mY[i] + bh);
                canvas.drawRoundRect(mRect, bw * 0.6f, bw * 0.6f, mPaint);
                mPaint.setColor(pure(0xFFFFE0A0, 0.55f * life * flicker));
                mRect.set(x - bw * 0.5f, mY[i] - bh * 0.55f, x + bw * 0.5f, mY[i] + bh * 0.75f);
                canvas.drawRoundRect(mRect, bw * 0.4f, bw * 0.4f, mPaint);
            }
        }

        /** Neon: glowing lines of colour flowing across the screen like a sound wave */
        private void neon(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int[] colors = {0xFFFF4FD8, 0xFF4DD8FF, 0xFF8C7CFF, 0xFF7CFFB2};
            for (int line = 0; line < 4; line++) {
                float base = h * (0.62f + 0.07f * line);
                float amplitude = mDp * (18 + 10 * line) * (0.7f + 0.3f * (float) Math.sin(mTime * 1.3f + line));
                mPath.rewind();
                for (int x = 0; x <= w; x += Math.max(6, w / 60)) {
                    float y = base + amplitude * (float) Math.sin(x / (w * (0.18f + 0.05f * line)) + mTime * (1.1f + 0.3f * line) + line)
                            * (float) Math.sin(x / (float) w * Math.PI);
                    if (x == 0) {
                        mPath.moveTo(x, y);
                    } else {
                        mPath.lineTo(x, y);
                    }
                }
                mStroke.setStrokeWidth(9 * mDp);
                mStroke.setColor(pure(colors[line], 0.07f));
                canvas.drawPath(mPath, mStroke);
                mStroke.setStrokeWidth(2 * mDp);
                mStroke.setColor(pure(colors[line], 0.45f));
                canvas.drawPath(mPath, mStroke);
            }
        }

        /** Galaxy: a tilted spiral of stars turning slowly around the artwork */
        private void galaxy(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.36f;
            float reach = getWidth() * 0.62f;
            int[] colors = {0xFFB78CFF, 0xFF4DD8FF, 0xFFFF7AD9, 0xFFFFFFFF};
            for (int i = 0; i < COUNT; i++) {
                float r = reach * (0.25f + 0.75f * mSize[i]);
                float angle = mPhase[i] + (i % 2) * (float) Math.PI + mTime * 0.35f / (0.4f + mSize[i]) + mSize[i] * 4f;
                float x = cx + r * (float) Math.cos(angle);
                float y = cy + r * 0.42f * (float) Math.sin(angle);
                float twinkle = 0.6f + 0.4f * (float) Math.sin(mTime * 2f * mSpeed[i] + mPhase[i]);
                int color = colors[i % colors.length];
                mPaint.setColor(pure(color, 0.08f * twinkle));
                canvas.drawCircle(x, y, mDp * 7, mPaint);
                mPaint.setColor(pure(color, 0.55f * twinkle));
                canvas.drawCircle(x, y, mDp * (1.2f + 1.6f * (1 - mSize[i])), mPaint);
            }
        }

        /** Butterflies: a few bright butterflies fluttering about */
        private void butterflies(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 7; i++) {
                float a = mTime * 0.16f * mSpeed[i] + mPhase[i];
                float x = w * (0.5f + 0.42f * (float) Math.sin(a));
                float y = h * (0.5f + 0.38f * (float) Math.sin(a * 1.37f + i));
                float heading = (float) Math.toDegrees(Math.atan2(0.38f * h * 1.37f * Math.cos(a * 1.37f + i), 0.42f * w * Math.cos(a))) + 90;
                float flap = 0.25f + 0.75f * Math.abs((float) Math.sin(mTime * 9f * mSpeed[i] + mPhase[i]));
                float size = mDp * (9 + 6 * mSize[i]);
                int color = RAINBOW[(i * 3) % RAINBOW.length];
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(heading);
                for (int side = -1; side <= 1; side += 2) {
                    canvas.save();
                    canvas.scale(side * flap, 1f);
                    mPaint.setColor(pure(color, 0.6f));
                    mRect.set(0, -size, size * 1.1f, size * 0.15f);
                    canvas.drawOval(mRect, mPaint);
                    mPaint.setColor(pure(PhoneMusicPlayer.blend(color, Color.WHITE, 0.4f), 0.55f));
                    mRect.set(0, size * 0.05f, size * 0.75f, size * 0.8f);
                    canvas.drawOval(mRect, mPaint);
                    canvas.restore();
                }
                mPaint.setColor(pure(0xFF2A1A2E, 0.6f));
                mRect.set(-size * 0.09f, -size * 0.7f, size * 0.09f, size * 0.7f);
                canvas.drawRoundRect(mRect, size * 0.09f, size * 0.09f, mPaint);
                canvas.restore();
            }
        }

        /** Notes: music notes floating up in bright colours, swaying to the song */
        private void notes(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            String[] glyphs = {"♪", "♫", "♩", "♬"};
            for (int i = 0; i < 16; i++) {
                mY[i] -= step * mDp * (16 + 16 * mSpeed[i]);
                if (mY[i] < -40 * mDp) {
                    mY[i] = h + 40 * mDp;
                    mX[i] = mRandom.nextFloat() * w;
                }
                float x = mX[i] + mDp * 20 * (float) Math.sin(mTime * 0.9f * mSpeed[i] + mPhase[i]);
                float life = Math.min(1f, Math.max(0f, mY[i] / (h * 0.3f)));
                mText.setTextSize(mDp * (16 + 20 * mSize[i]));
                mText.setColor(pure(RAINBOW[i % RAINBOW.length], 0.5f * life));
                canvas.save();
                canvas.translate(x, mY[i]);
                canvas.rotate(18f * (float) Math.sin(mTime * 1.4f + mPhase[i]));
                canvas.drawText(glyphs[i % glyphs.length], 0, 0, mText);
                canvas.restore();
            }
        }

        /** Prism: bands of rainbow light sweeping slowly across, as if through glass, with glints */
        private void prism(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (mPrism == null) {
                mPrism = new LinearGradient(-w * 0.18f, 0, w * 0.18f, 0,
                        new int[] {0x00FF4F6D, 0x40FF4F6D, 0x40FFC23D, 0x407CFF8A, 0x404DD8FF, 0x40B78CFF, 0x00B78CFF},
                        null, Shader.TileMode.CLAMP);
            }
            mPaint.setShader(mPrism);
            for (int i = 0; i < 3; i++) {
                float p = (mTime * 0.045f + i / 3f) % 1f;
                float x = -w * 0.4f + p * w * 1.8f;
                mPaint.setAlpha(Math.round(255 * mFade * (float) Math.sin(p * Math.PI)));
                canvas.save();
                canvas.translate(x, h * 0.45f);
                canvas.rotate(24f);
                canvas.drawRect(-w * 0.18f, -h, w * 0.18f, h, mPaint);
                canvas.restore();
            }
            mPaint.setShader(null);
            for (int i = 0; i < 14; i++) {
                float twinkle = (float) Math.pow(Math.max(0, Math.sin(mTime * 1.3f * mSpeed[i] + mPhase[i])), 4);
                float size = mDp * (6 + 8 * mSize[i]) * twinkle;
                if (size < 0.5f) {
                    continue;
                }
                mPaint.setColor(pure(RAINBOW[i % RAINBOW.length], 0.7f * twinkle));
                canvas.save();
                canvas.translate(mX[i], mY[i]);
                canvas.scale(size, size);
                canvas.drawPath(mStar, mPaint);
                canvas.restore();
            }
        }
    }
}
