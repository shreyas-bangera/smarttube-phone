package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The feel of a song, read from its title and lyrics (English, Hindi and Punjabi words, in Latin letters,
 * Devanagari and Gurmukhi), and a quiet animation behind the music player for it: hearts rising for a love song,
 * rain for a sad one, sparkles for a party, aurora for a calm one, rings for an energetic one, embers for a
 * devotional one; slow bokeh when it can't tell. No service is asked: the words are already there.
 */
final class PhoneMood {
    enum Mood { AMBIENT, LOVE, SAD, PARTY, CALM, ENERGY, DEVOTION }

    private PhoneMood() {
    }

    // region Reading the mood

    private static final Map<String, float[]> WORDS = new HashMap<>();

    private static void words(Mood mood, float weight, String... words) {
        for (String word : words) {
            float[] scores = WORDS.get(word);
            if (scores == null) {
                scores = new float[Mood.values().length];
                WORDS.put(word, scores);
            }
            scores[mood.ordinal()] += weight;
        }
    }

    static {
        words(Mood.LOVE, 1f, "love", "lover", "loving", "kiss", "darling", "romance", "romantic", "honey", "valentine",
                "pyaar", "pyar", "ishq", "ishqa", "mohabbat", "muhabbat", "sanam", "sajna", "sajni", "saajna", "mahi",
                "maahi", "mahiya", "maahiya", "dilbar", "jaanu", "chahat", "prem", "pritam", "sohniye", "sohneya",
                "heeriye", "raanjhana", "ranjha", "humsafar", "dildaara", "piya", "jaaniya", "aashiqui", "aashiq",
                "प्यार", "इश्क़", "इश्क", "मोहब्बत", "सनम", "सजना", "सजनी", "माही", "दिलबर", "प्रेम", "चाहत", "पिया",
                "आशिक़ी", "आशिकी", "हमसफ़र", "हीरिये", "सोहणिआ", "सोहणी", "ਪਿਆਰ", "ਇਸ਼ਕ", "ਸੋਹਣੀ", "ਸੋਹਣਿਆ", "ਮਾਹੀ",
                "ਹੀਰੇ", "ਸੱਜਣਾ");
        words(Mood.LOVE, 0.5f, "heart", "baby", "dil", "jaan", "jaana", "दिल", "जान", "जाना", "ਦਿਲ", "ਜਾਨ");
        words(Mood.SAD, 1f, "sad", "cry", "crying", "tears", "alone", "lonely", "goodbye", "broken", "hurt", "pain",
                "sorry", "lost", "tanha", "tanhai", "judaa", "juda", "judaai", "judai", "aansu", "aansoo", "dard",
                "bewafa", "alvida", "rona", "roya", "toota", "tuta", "gham", "intezaar", "dooriyan", "bichhad",
                "bichde", "khaali", "adhoora", "adhura", "tanhaiyan", "hanju",
                "तन्हा", "तन्हाई", "जुदा", "जुदाई", "आँसू", "आंसू", "दर्द", "बेवफ़ा", "बेवफा", "अलविदा", "रोना", "टूटा",
                "ग़म", "गम", "दूरियाँ", "बिछड़", "अधूरा", "इंतज़ार", "ਦਰਦ", "ਹੰਝੂ", "ਜੁਦਾ", "ਤਨਹਾ", "ਵਿਛੋੜਾ");
        words(Mood.SAD, 0.4f, "miss", "yaad", "yaadein", "याद", "यादें", "ਯਾਦ");
        words(Mood.PARTY, 1f, "party", "dance", "dancing", "celebrate", "club", "shake", "tonight", "disco", "nach",
                "nachle", "naach", "nacho", "nachna", "thumka", "thumke", "bhangra", "balle", "shava", "dhol", "masti",
                "jashn", "shaadi", "mehendi", "mehndi", "baraat", "daaru", "peg", "naachle", "jhoom", "dj",
                "नाच", "नाचो", "नाचे", "ठुमका", "भांगड़ा", "ढोल", "मस्ती", "जश्न", "शादी", "पार्टी", "झूम",
                "ਨੱਚ", "ਨੱਚਦੀ", "ਭੰਗੜਾ", "ਬੱਲੇ", "ਢੋਲ", "ਸ਼ਾਵਾ");
        words(Mood.PARTY, 0.5f, "happy", "fun", "sunshine", "khushi", "खुशी", "ख़ुशी");
        words(Mood.DEVOTION, 1f, "god", "lord", "jesus", "pray", "prayer", "hallelujah", "krishna", "shyam", "shiva",
                "shiv", "bhole", "mahadev", "hanuman", "ganpati", "ganesh", "maiya", "ambe", "durga", "waheguru",
                "allah", "maula", "aarti", "bhajan", "kirtan", "mantra", "govind", "gopal", "radhe", "narayan",
                "राम", "श्याम", "कृष्ण", "शिव", "भोले", "महादेव", "हनुमान", "गणपति", "मैया", "आरती", "भजन", "राधे",
                "गोविंद", "नारायण", "ॐ", "मौला", "ਵਾਹਿਗੁਰੂ", "ਗੁਰੂ", "ਸਤਿਨਾਮ");
        words(Mood.DEVOTION, 0.4f, "ram", "mata", "sai", "jai", "om", "khuda", "rab", "रब", "ख़ुदा", "खुदा", "जय", "माता",
                "ਰੱਬ");
        words(Mood.ENERGY, 1f, "fire", "power", "fight", "rise", "beast", "warrior", "war", "champion", "workout",
                "gym", "rap", "phonk", "hustle", "unstoppable", "jung", "josh", "sher", "lalkaar", "dhaakad", "dangal",
                "jalwa", "badshah", "aag", "जोश", "शेर", "जंग", "आग", "ललकार", "ਸ਼ੇਰ", "ਜੰਗ", "ਅੱਗ");
        words(Mood.CALM, 1f, "lofi", "chill", "slowed", "reverb", "acoustic", "unplugged", "calm", "sleep", "relax",
                "peaceful", "lullaby", "meditation", "sukoon", "khamoshi", "baarish", "barish", "rain", "lori",
                "सुकून", "ख़ामोशी", "खामोशी", "बारिश", "लोरी", "ਸਕੂਨ");
    }

    /** The song's feel; the title counts most (a "lofi", "slowed" or "bhajan" in it settles it) */
    static Mood of(String title, List<PhoneLyrics.Line> lyrics) {
        float[] scores = new float[Mood.values().length];
        add(scores, title, 4f);
        if (lyrics != null) {
            StringBuilder all = new StringBuilder();
            for (PhoneLyrics.Line line : lyrics) {
                all.append(line.text).append(' ');
            }
            add(scores, all.toString(), 1f);
        }
        Mood best = Mood.AMBIENT;
        float top = 0;
        float second = 0;
        for (Mood mood : Mood.values()) {
            float score = scores[mood.ordinal()];
            if (score > top) {
                second = top;
                top = score;
                best = mood;
            } else if (score > second) {
                second = score;
            }
        }
        // Clear enough: a few words of it, more than of the next feel
        return top >= 3f && top >= second * 1.3f ? best : Mood.AMBIENT;
    }

    private static void add(float[] scores, String text, float weight) {
        if (text == null) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT).replace("lo-fi", "lofi");
        for (String word : lower.split("[^\\p{L}\\p{M}\\p{N}ॐ]+")) {
            float[] found = WORDS.get(word);
            if (found != null) {
                for (int i = 0; i < scores.length; i++) {
                    scores[i] += found[i] * weight;
                }
            }
        }
    }

    // endregion

    // region The animation

    /**
     * Drawn behind everything in the player, faint (a tenth to a quarter opaque) and slow. It runs while the song
     * plays, eases to a stop when it pauses, rests when the player is hidden, and stands still when the phone's
     * animations are off.
     */
    static final class Backdrop extends View {
        private static final int COUNT = 40;
        private final Random mRandom = new Random(7);
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final Path mHeart = new Path();
        private final Path mStar = new Path();
        private final float[] mX = new float[COUNT];
        private final float[] mY = new float[COUNT];
        private final float[] mSize = new float[COUNT];
        private final float[] mSpeed = new float[COUNT];
        private final float[] mPhase = new float[COUNT];
        private final float mDp;
        private final LinearGradient[] mBands = new LinearGradient[3];
        private boolean mBandsStale = true;

        private Mood mMood = Mood.AMBIENT;
        private Mood mNext;
        private int mColor = 0xFF8888AA;
        private boolean mRunning;
        /** 0 to 1: how fast time goes (eases in on play, out on pause) */
        private float mSpeedNow;
        /** 0 to 1: how much of the mood shows (fades out and in when it changes) */
        private float mFade;
        private float mTime;
        private long mLastFrame;

        Backdrop(Context context) {
            super(context);
            mDp = context.getResources().getDisplayMetrics().density;
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeCap(Paint.Cap.ROUND);
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

        void setMood(Mood mood) {
            if (mood == null || mood == mMood && mNext == null) {
                return;
            }
            mNext = mood;
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
        }

        /** The particles' places, sizes, speeds and phases for this mood */
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
            // Changing mood: the old one fades out, the new one in
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

            switch (mMood) {
                case LOVE: hearts(canvas, step); break;
                case SAD: rain(canvas, step); break;
                case PARTY: sparkles(canvas, step); break;
                case CALM: aurora(canvas); break;
                case ENERGY: rings(canvas, step); break;
                case DEVOTION: embers(canvas, step); break;
                default: bokeh(canvas, step); break;
            }
            boolean settled = mSpeedNow == 0f && (mNext == null && Math.abs(mFade - 1f) < 0.01f);
            if (!settled && isShown()) {
                postInvalidateOnAnimation();
            }
        }

        /** The song's colour turned towards the mood's, this faint (and fading with the mood) */
        private int tint(int towards, float amount, float alpha) {
            return solid(towards, amount, alpha * mFade);
        }

        private int solid(int towards, float amount, float alpha) {
            int color = PhoneMusicPlayer.blend(PhoneMusicPlayer.lighten(mColor), towards, amount);
            return (color & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255) << 24);
        }

        /** Love: soft hearts rising, swaying a little, fading out near the top */
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
                mPaint.setColor(tint(0xFFFF6F9F, 0.55f, (0.10f + 0.12f * mSize[i]) * life));
                canvas.save();
                canvas.translate(x, mY[i]);
                canvas.rotate(12f * (float) Math.sin(mTime * 0.5f + mPhase[i]));
                canvas.scale(size, size);
                canvas.drawPath(mHeart, mPaint);
                canvas.restore();
            }
        }

        /** Sad: thin rain falling slowly on a slant, rings spreading where it lands */
        private void rain(Canvas canvas, float step) {
            int w = getWidth();
            int h = getHeight();
            mStroke.setStrokeWidth(mDp);
            for (int i = 0; i < 34; i++) {
                float fall = step * mDp * (150 + 110 * mSpeed[i]);
                mY[i] += fall;
                mX[i] -= fall * 0.12f;
                if (mY[i] > h + 40 * mDp || mX[i] < -20 * mDp) {
                    mY[i] = -40 * mDp - mRandom.nextFloat() * h * 0.3f;
                    mX[i] = mRandom.nextFloat() * (w + 60 * mDp);
                }
                float length = mDp * (16 + 30 * mSize[i]);
                mStroke.setColor(tint(0xFF8FB4FF, 0.6f, 0.08f + 0.12f * mSize[i]));
                canvas.drawLine(mX[i], mY[i], mX[i] + length * 0.12f, mY[i] - length, mStroke);
            }
            // Three rings at a time, low on the screen
            for (int i = 34; i < 37; i++) {
                float p = ((mTime * 0.45f + mPhase[i] / 6.2832f) % 1f);
                if (p < 0.02f) {
                    mX[i] = mRandom.nextFloat() * w;
                    mY[i] = h * (0.72f + 0.25f * mRandom.nextFloat());
                }
                float radius = mDp * (6 + 46 * p);
                mStroke.setColor(tint(0xFF8FB4FF, 0.6f, 0.16f * (1 - p) * (1 - p)));
                canvas.drawOval(mX[i] - radius, mY[i] - radius * 0.32f, mX[i] + radius, mY[i] + radius * 0.32f, mStroke);
            }
        }

        /** Party: sparkles twinkling in warm colours, drifting up */
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
                mPaint.setColor(tint(colors[i % 3], 0.6f, 0.06f + 0.30f * twinkle));
                canvas.save();
                canvas.translate(mX[i], mY[i]);
                canvas.rotate(mTime * 20 * (i % 2 == 0 ? 1 : -1));
                canvas.scale(size, size);
                canvas.drawPath(mStar, mPaint);
                canvas.restore();
            }
        }

        /** Calm: three bands of aurora, waving slowly across the top half */
        private void aurora(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int[] tones = {0xFF6FE3D0, 0xFF8F9BFF, 0xFFC08FFF};
            for (int band = 0; band < 3; band++) {
                float top = h * (0.12f + 0.16f * band);
                float depth = h * 0.22f;
                if (mBandsStale || mBands[band] == null) {
                    mBands[band] = new LinearGradient(0, top - 30 * mDp, 0, top + depth,
                            solid(tones[band], 0.5f, 0.16f), Color.TRANSPARENT, Shader.TileMode.CLAMP);
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

        /** Energy: rings pulsing out from behind the artwork on the beat, and sparks flying out */
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

        /** Devotion: embers rising and flickering, under slow beams of light */
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

        /** Not sure: a few big soft circles of the song's colour, drifting */
        private void bokeh(Canvas canvas, float step) {
            mPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 12; i++) {
                float x = mX[i] + mDp * 40 * (float) Math.sin(mTime * 0.12f * mSpeed[i] + mPhase[i]);
                float y = mY[i] + mDp * 30 * (float) Math.cos(mTime * 0.1f * mSpeed[i] + mPhase[i]);
                float radius = mDp * (18 + 44 * mSize[i]);
                float breathe = 0.5f + 0.5f * (float) Math.sin(mTime * 0.5f + mPhase[i]);
                mPaint.setColor(tint(Color.WHITE, 0.25f, 0.035f + 0.04f * breathe));
                canvas.drawCircle(x, y, radius, mPaint);
            }
        }
    }

    // endregion
}
