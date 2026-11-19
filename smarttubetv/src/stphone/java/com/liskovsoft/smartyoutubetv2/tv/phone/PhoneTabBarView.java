package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Random;

/**
 * The bottom tabs (Customize > Tab bar, see PhoneTabBar). Classic is a plain bar; the two others float over the feed:
 *
 * <p><b>Floating</b> (after Nuvio's tab bar, in YouTube's colours): a capsule of dark glass, the feed blurred behind
 * it, a fine light edge. The chosen tab sits in a bubble of YouTube red, and its red glows faintly across the bar.
 * Inside the bubble the tab is red, outside it white: as the bubble glides over a tab, the colour changes right at its
 * edge.
 *
 * <p><b>Liquid glass</b> (after MeeTube's, iOS 26's): glass that bends what's behind it - saturated, lightly
 * blurred and refracted along its rim with a rainbow of dispersion (Android 13+), a dark tint, fine grain and a
 * specular rim lit from the top left. The chosen tab sits on a soft pill. When the tab changes, or while a finger
 * slides along the bar, the pill swells into a clear lens taller than the bar: the bar shows through it magnified and
 * refracted, the lens stretching with its speed; then it settles back into the pill, and the bar wobbles toward the
 * new tab.
 *
 * <p>The glass shaders are Kyant's backdrop (github.com/Kyant0/backdrop, Copyright 2025 Kyant, Apache License 2.0),
 * as MeeTube (github.com/yashoncode/meetube, MIT) ported them; the lens and the wobble follow MeeTube's numbers.
 * The bubble moves on a spring; slide a finger along the bar and it follows the finger, ticking at each tab, and the
 * tab under it opens when the finger lifts.
 */
public class PhoneTabBarView extends LinearLayout {
    /** YouTube's red */
    static final int YOUTUBE_RED = 0xFFFF0033;

    // Kyant's backdrop: the refraction (rounded-rect lens with dispersion) and the specular highlight
    private static final String ROUNDED_RECT_SDF =
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    private static final String REFRACTION_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + ROUNDED_RECT_SDF
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)"
            + " + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    float dispersionIntensity = chromaticAberration"
            + " * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5;\n"
            + "    color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5;\n"
            + "    color.g += orange.g / 7.0;\n"
            + "    color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5;\n"
            + "    color.g += yellow.g / 3.5;\n"
            + "    color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5;\n"
            + "    color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5;\n"
            + "    color.b += cyan.b / 3.0;\n"
            + "    color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0;\n"
            + "    color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0;\n"
            + "    color.b += purple.b / 3.0;\n"
            + "    color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    private static final String HIGHLIGHT_AGSL =
            "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "layout(color) uniform half4 color;\n"
            + "uniform float angle;\n"
            + "uniform float falloff;\n"
            + ROUNDED_RECT_SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 normal = float2(cos(angle), sin(angle));\n"
            + "    float d = dot(grad, normal);\n"
            + "    float intensity = pow(abs(d), falloff);\n"
            + "    return color * intensity;\n"
            + "}\n";

    // MeeTube's glass and lens numbers
    private static final float BLUR_DP = 16f;
    private static final float SATURATION = 1.5f;
    private static final float REFRACTION_DP = 24f;
    private static final float LENS_REFRACTION_DP = 16f;
    private static final float MAGNIFY = 1.12f;
    private static final float LENS_GROW_W_DP = 12f;
    private static final float LENS_GROW_H_DP = 6f;
    private static final float STRETCH = 0.26f;

    private static Bitmap sNoise;

    private int mStyle = PhoneTabBar.CLASSIC;
    private boolean mDark = true;
    private final float mD;
    private int mSide;
    private int mBelow;
    private int mRise;

    private final RectF mBar = new RectF();
    private final Path mBarPath = new Path();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNoise = new Paint();
    private final Paint mRedText = new Paint();

    private View mBackdrop;
    private final int[] mHere = new int[2];
    private final int[] mThere = new int[2];
    private Object mBarNode;
    private Object mLensNode;
    private Object mRefraction;
    private Object mLensRefraction;
    private Object mHighlight;
    private Object mLensHighlight;
    private int mEffectW;
    private int mEffectH;
    private int mEffectStyle = -1;
    private final ViewTreeObserver.OnPreDrawListener mFollowBackdrop = () -> {
        if (mStyle != PhoneTabBar.CLASSIC) {
            invalidate(); // the feed moved or changed: the glass shows it
        }
        return true;
    };

    // The bubble on a spring; how much of a lens it is (glass), how far it's lifted (held, dragged)
    private View mChosen;
    private float mX = -1;
    private float mW;
    private float mTargetX;
    private float mTargetW;
    private float mVelocity;
    private float mPresence;
    private float mLift;
    private float mLiftTarget;
    private long mLastFrame;
    private boolean mAnimating;
    private ValueAnimator mWobble;

    private boolean mLogged;
    private boolean mDragging;
    private float mDownX;
    private View mHovered;

    public PhoneTabBarView(Context context) {
        this(context, null);
    }

    public PhoneTabBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mD = context.getResources().getDisplayMetrics().density;
        mEdge.setStyle(Paint.Style.STROKE);
        mRim.setStyle(Paint.Style.STROKE);
        mRim.setStrokeWidth(mD);
        mRim.setMaskFilter(new BlurMaskFilter(0.25f * mD, BlurMaskFilter.Blur.NORMAL));
        if (Build.VERSION.SDK_INT >= 29) {
            mRim.setBlendMode(BlendMode.PLUS);
        }
        mNoise.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoise.setAlpha(10);
        mRedText.setColorFilter(new PorterDuffColorFilter(YOUTUBE_RED, PorterDuff.Mode.SRC_IN));
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    // region Setup

    /** The look, and the navigation bar's height under the bar */
    void setStyle(int style, int navInset) {
        mStyle = style;
        mLogged = false;
        mDark = PhoneTabBar.isDark(getContext());
        mEffectStyle = -1;
        setScaleX(1f);
        setScaleY(1f);
        setTranslationX(0);
        if (style == PhoneTabBar.CLASSIC) {
            setBackgroundResource(R.drawable.phone_bottom_bar_bg);
            setPadding(0, 0, 0, navInset);
            setElevation(0);
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            invalidate();
            return;
        }
        mSide = Math.round(22 * mD);
        mBelow = navInset + Math.round(12 * mD);
        mRise = Math.round(5 * mD); // room above the bar for the lens rising out of it
        setBackground(null);
        setPadding(mSide + Math.round(4 * mD), mRise + Math.round(4 * mD), mSide + Math.round(4 * mD), mBelow + Math.round(4 * mD));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(mSide, mRise, view.getWidth() - mSide, view.getHeight() - mBelow,
                        (view.getHeight() - mBelow - mRise) / 2f);
                outline.setAlpha(0.55f);
            }
        });
        setElevation(10 * mD);
        invalidateOutline();
        invalidate();
    }

    /** The feed behind the bar: it shows through the glass */
    void setBackdrop(View backdrop) {
        if (mBackdrop != null) {
            mBackdrop.getViewTreeObserver().removeOnPreDrawListener(mFollowBackdrop);
        }
        mBackdrop = backdrop;
        if (backdrop != null) {
            backdrop.getViewTreeObserver().addOnPreDrawListener(mFollowBackdrop);
        }
    }

    /** The chosen tab: the bubble glides to it (or is there at once) */
    void select(View tab, boolean animate) {
        View before = mChosen;
        mChosen = tab;
        if (tab == null || mStyle == PhoneTabBar.CLASSIC) {
            return;
        }
        if (tab.getWidth() == 0) {
            post(() -> select(tab, false));
            return;
        }
        mTargetX = tab.getLeft() + tab.getWidth() / 2f;
        mTargetW = pillWidth(tab);
        if (!animate || mX < 0) {
            mX = mTargetX;
            mW = mTargetW;
            mVelocity = 0;
            mPresence = 0;
            invalidate();
            return;
        }
        if (before != null && before != tab && !mDragging) {
            wobble(mTargetX > mX ? 1 : -1);
        }
        startAnimation();
    }

    // endregion

    // region Motion

    private void startAnimation() {
        if (!mAnimating) {
            mAnimating = true;
            mLastFrame = 0;
            postOnAnimation(mStep);
        }
    }

    private final Runnable mStep = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = mLastFrame == 0 ? 1 / 60f : Math.min(1 / 30f, (now - mLastFrame) / 1e9f);
            mLastFrame = now;
            // MeeTube's glide: stiffness 1000, damping 0.82 (a ~1% overshoot as it lands); tighter under a finger
            float stiffness = mDragging ? 1500f : 1000f;
            float ratio = mDragging ? 0.95f : 0.82f;
            float damping = 2 * ratio * (float) Math.sqrt(stiffness);
            float accel = stiffness * (mTargetX - mX) - damping * mVelocity;
            mVelocity += accel * dt;
            mX += mVelocity * dt;
            mW += (mTargetW - mW) * Math.min(1f, dt * 16);
            mLift += (mLiftTarget - mLift) * Math.min(1f, dt * 14);
            // The lens: swells in ~90 ms while moving or held, settles back into the pill in ~140 ms
            boolean moving = Math.abs(mTargetX - mX) > 1.5f * mD || Math.abs(mVelocity) > 40 * mD;
            float want = mDragging || moving ? 1f : 0f;
            mPresence += (want - mPresence) * Math.min(1f, dt * (want > mPresence ? 11f : 7f));
            invalidate();
            boolean settled = !moving && !mDragging && Math.abs(mLiftTarget - mLift) < 0.01f && mPresence < 0.01f
                    && Math.abs(mTargetW - mW) < 0.5f;
            if (settled) {
                mX = mTargetX;
                mW = mTargetW;
                mVelocity = 0;
                mLift = mLiftTarget;
                mPresence = 0;
                mAnimating = false;
                invalidate();
                return;
            }
            postOnAnimation(this);
        }
    };

    /**
     * MeeTube's jelly wobble on a switch: the bar leans toward the new tab and wobbles - wider and flatter, then
     * narrower and taller - settling in 0.7 s
     */
    private void wobble(int direction) {
        if (mWobble != null) {
            mWobble.cancel();
        }
        float lean = 6 * mD * direction;
        mWobble = ValueAnimator.ofFloat(0f, 1f);
        mWobble.setDuration(700);
        mWobble.setInterpolator(new LinearInterpolator());
        mWobble.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float w = (float) (Math.exp(-4.2 * t) * Math.sin(2 * Math.PI * 2.25 * t));
            setScaleX(1f + 0.028f * w);
            setScaleY(1f - 0.04f * w);
            setTranslationX(lean * (float) (Math.exp(-3.5 * t) * Math.sin(2 * Math.PI * 1.4 * t)));
        });
        mWobble.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setScaleX(1f);
                setScaleY(1f);
                setTranslationX(0);
            }
        });
        mWobble.start();
    }

    // endregion

    // region Touch: press, and slide along the bar

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mStyle != PhoneTabBar.CLASSIC) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && insideBar(event.getY())) {
                mDownX = event.getX();
                mDragging = false;
                mHovered = null;
                mLiftTarget = 1f;
                startAnimation();
            } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !mDragging) {
                mLiftTarget = 0;
                startAnimation();
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mStyle == PhoneTabBar.CLASSIC) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !mDragging
                && Math.abs(event.getX() - mDownX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
            startDrag(event.getX());
            return true; // the tabs let go of the touch: the bar has it
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mStyle == PhoneTabBar.CLASSIC) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return insideBar(event.getY());
            case MotionEvent.ACTION_MOVE:
                if (!mDragging && Math.abs(event.getX() - mDownX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    startDrag(event.getX());
                }
                if (mDragging) {
                    follow(event.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    release(event.getActionMasked() == MotionEvent.ACTION_UP);
                }
                return true;
            default:
                return true;
        }
    }

    private boolean insideBar(float y) {
        return y >= mRise && y <= getHeight() - mBelow + 2 * mD;
    }

    private void startDrag(float x) {
        mDragging = true;
        mLiftTarget = 1f;
        follow(x);
        startAnimation();
    }

    /** The bubble follows the finger, between the first and the last tab */
    private void follow(float x) {
        View first = null;
        View last = null;
        View nearest = null;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View tab = getChildAt(i);
            if (tab.getVisibility() != VISIBLE) {
                continue;
            }
            if (first == null) {
                first = tab;
            }
            last = tab;
            float distance = Math.abs(tab.getLeft() + tab.getWidth() / 2f - x);
            if (distance < best) {
                best = distance;
                nearest = tab;
            }
        }
        if (first == null) {
            return;
        }
        float min = first.getLeft() + first.getWidth() / 2f;
        float max = last.getLeft() + last.getWidth() / 2f;
        mTargetX = Math.max(min, Math.min(max, x));
        if (nearest != mHovered) {
            mHovered = nearest;
            performHapticFeedback(Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
        }
        startAnimation();
    }

    /** Let go: the tab under the bubble opens; a tab that opens a screen of its own lets the bubble go back */
    private void release(boolean open) {
        mDragging = false;
        mLiftTarget = 0;
        View tab = mHovered;
        mHovered = null;
        if (tab != null) {
            mTargetX = tab.getLeft() + tab.getWidth() / 2f;
            mTargetW = pillWidth(tab);
        }
        startAnimation();
        if (open && tab != null) {
            View chosen = mChosen;
            tab.performClick();
            postDelayed(() -> {
                if (mChosen != null && mChosen != tab && !mDragging) {
                    select(mChosen, true); // Shorts or Notifications opened a screen of their own
                }
            }, 350);
            if (chosen != tab && mChosen == tab) {
                wobble(tab.getLeft() > (chosen != null ? chosen.getLeft() : 0) ? 1 : -1);
            }
        } else if (mChosen != null) {
            select(mChosen, true);
        }
    }

    // endregion

    // region Drawing

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mEffectStyle = -1;
        if (mChosen != null) {
            post(() -> select(mChosen, false));
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mStyle == PhoneTabBar.CLASSIC) {
            super.dispatchDraw(canvas);
            return;
        }
        mBar.set(mSide, mRise, getWidth() - mSide, getHeight() - mBelow);
        if (mX < 0 && mChosen != null && mChosen.getWidth() > 0) {
            // The chosen tab is laid out now: the bubble starts on it
            mX = mTargetX = mChosen.getLeft() + mChosen.getWidth() / 2f;
            mW = mTargetW = pillWidth(mChosen);
        }
        if (!mLogged) {
            mLogged = true;
            android.util.Log.d("PhoneTabBar", "first draw: style " + mStyle + ", bar " + mBar + ", bubble at " + mX
                    + " (" + mW + " wide), backdrop " + (mBackdrop != null) + ", hardware " + canvas.isHardwareAccelerated());
        }
        float radius = mBar.height() / 2f;
        mBarPath.reset();
        mBarPath.addRoundRect(mBar, radius, radius, Path.Direction.CW);
        if (mStyle == PhoneTabBar.FLOATING) {
            drawFloating(canvas, radius);
        } else {
            drawLiquid(canvas, radius);
        }
    }

    /** The bubble on the chosen tab (or under the finger): wide enough for the tab's name, the bar's height less 4 dp each side */
    private RectF pill(float inset) {
        if (mX < 0 || mW <= 0) {
            return null;
        }
        float h = mBar.height() - 2 * inset;
        float half = mW / 2f;
        // It stays inside the bar
        float x = Math.max(mBar.left + inset + half, Math.min(mBar.right - inset - half, mX));
        return new RectF(x - half, mBar.centerY() - h / 2f, x + half, mBar.centerY() + h / 2f);
    }

    /** The pill's corners: a rounded square (not a circle), so the icon and the name both sit inside */
    private float corner(RectF shape) {
        return Math.min(20 * mD, Math.min(shape.width(), shape.height()) / 2f);
    }

    /** A tab's pill width: the tab, or its name with room on both sides when the name is wider */
    private float pillWidth(View tab) {
        float width = tab.getWidth();
        if (tab instanceof android.view.ViewGroup && ((android.view.ViewGroup) tab).getChildCount() > 1
                && ((android.view.ViewGroup) tab).getChildAt(1) instanceof android.widget.TextView) {
            android.widget.TextView label = (android.widget.TextView) ((android.view.ViewGroup) tab).getChildAt(1);
            float text = label.getPaint().measureText(String.valueOf(label.getText()));
            width = Math.max(width, Math.min(text + 20 * mD, tab.getWidth() * 1.45f));
        }
        return width;
    }

    // Floating: Nuvio's look, YouTube's colours

    private void drawFloating(Canvas canvas, float radius) {
        boolean blurred = drawBackdrop(canvas, mBarPath, false);
        // Dark glass: YouTube's near black, see-through where the feed shows
        mFill.setShader(null);
        mFill.setColor(mDark ? (blurred ? 0xC70F0F0F : 0xEB0F0F0F) : (blurred ? 0xCCFFFFFF : 0xF2FFFFFF));
        canvas.drawPath(mBarPath, mFill);
        RectF pill = pill(4 * mD);
        float speed = Math.min(1f, Math.abs(mVelocity) / (1800 * mD));
        if (pill != null) {
            // The jelly: a little wider and flatter while it moves, a little bigger while pressed
            float grow = pill.width() * (0.18f * speed + 0.04f * mLift);
            pill.inset(-grow / 2f, -1.5f * mD * mLift);
            pill.inset(0, 1.5f * mD * speed);
            // YouTube red glowing faintly across the bar from the bubble
            canvas.save();
            canvas.clipPath(mBarPath);
            drawGlow(canvas, pill, 0.15f);
            canvas.restore();
        }
        // A fine light edge, bright at the top
        mEdge.setStrokeWidth(0.75f * mD);
        mEdge.setShader(new LinearGradient(0, mBar.top, 0, mBar.bottom,
                mDark ? 0x45FFFFFF : 0x1F000000, mDark ? 0x05FFFFFF : 0x08000000, Shader.TileMode.CLAMP));
        RectF edge = new RectF(mBar);
        edge.inset(0.375f * mD, 0.375f * mD);
        canvas.drawRoundRect(edge, radius, radius, mEdge);
        mEdge.setShader(null);
        if (pill == null) {
            super.dispatchDraw(canvas);
            return;
        }
        float r = corner(pill);
        Path bubble = new Path();
        bubble.addRoundRect(pill, r, r, Path.Direction.CW);
        // Outside the bubble: the tabs as they are (white)
        canvas.save();
        canvas.clipOutPath(bubble);
        super.dispatchDraw(canvas);
        canvas.restore();
        // The bubble: YouTube red, see-through, glowing; the tab in it red, its colour changing right at the edge
        canvas.save();
        canvas.clipPath(bubble);
        mFill.setShader(null);
        mFill.setColor((YOUTUBE_RED & 0x00FFFFFF) | (mDark ? 0x2E000000 : 0x24000000));
        canvas.drawRect(pill, mFill);
        drawGlow(canvas, pill, 0.3f);
        int layer = canvas.saveLayer(pill, mRedText);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(layer);
        canvas.restore();
        mEdge.setStrokeWidth(0.75f * mD);
        mEdge.setShader(new LinearGradient(0, pill.top, 0, pill.bottom,
                (YOUTUBE_RED & 0x00FFFFFF) | 0x80000000, (YOUTUBE_RED & 0x00FFFFFF) | 0x1A000000, Shader.TileMode.CLAMP));
        RectF ring = new RectF(pill);
        ring.inset(0.375f * mD, 0.375f * mD);
        canvas.drawRoundRect(ring, r, r, mEdge);
        mEdge.setShader(null);
    }

    /** YouTube red, glowing from the bubble's middle */
    private void drawGlow(Canvas canvas, RectF from, float strength) {
        int a = Math.round(255 * strength);
        mFill.setShader(new RadialGradient(from.centerX(), from.centerY(), 300 * mD,
                new int[] {(YOUTUBE_RED & 0x00FFFFFF) | (a << 24), (YOUTUBE_RED & 0x00FFFFFF) | (Math.round(a * 0.43f) << 24),
                        YOUTUBE_RED & 0x00FFFFFF}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(mBar.left - 48 * mD, mBar.top - 16 * mD, mBar.right + 48 * mD, mBar.bottom + 16 * mD, mFill);
        mFill.setShader(null);
    }

    // Liquid glass: MeeTube's

    private void drawLiquid(Canvas canvas, float radius) {
        drawGlassBar(canvas, radius);
        RectF pill = pill(4 * mD);
        // At rest: the chosen tab on a soft pill (it becomes the lens while it moves)
        if (pill != null && mPresence < 0.99f) {
            float r = corner(pill);
            mFill.setShader(null);
            int alpha = Math.round((mDark ? 0x30 : 0x1A) * (1f - mPresence));
            mFill.setColor((mDark ? 0x00FFFFFF : 0x00000000) | (alpha << 24));
            canvas.drawRoundRect(pill, r, r, mFill);
        }
        super.dispatchDraw(canvas);
        if (pill != null && mPresence > 0.01f) {
            drawLens(canvas, pill);
        }
    }

    /** The glass: what's behind it bent and blurred, a dark tint, grain and a lit rim */
    private void drawGlassBar(Canvas canvas, float radius) {
        boolean live = drawBackdrop(canvas, mBarPath, true);
        mFill.setShader(null);
        // Dark enough that the names stay readable over bright thumbnails
        mFill.setColor(mDark ? (live ? 0xC2121214 : 0xF0121214) : (live ? 0xC7F7F7F9 : 0xF5F7F7F9));
        canvas.drawPath(mBarPath, mFill);
        canvas.save();
        canvas.clipPath(mBarPath);
        canvas.drawRect(mBar, mNoise);
        canvas.restore();
        drawRim(canvas, mBar, radius, false);
    }

    /** Kyant's specular highlight (white at 50%, lit along a 45 degree normal); a top light before Android 13 */
    private void drawRim(Canvas canvas, RectF rect, float radius, boolean lens) {
        int w = Math.max(1, Math.round(rect.width()));
        int h = Math.max(1, Math.round(rect.height()));
        if (Build.VERSION.SDK_INT >= 33) {
            Object existing = lens ? mLensHighlight : mHighlight;
            RuntimeShader shader = existing instanceof RuntimeShader ? (RuntimeShader) existing : new RuntimeShader(HIGHLIGHT_AGSL);
            if (lens) {
                mLensHighlight = shader;
            } else {
                mHighlight = shader;
            }
            shader.setColorUniform("color", mDark ? 0x80FFFFFF : 0xB3FFFFFF);
            shader.setFloatUniform("angle", (float) Math.toRadians(45));
            shader.setFloatUniform("falloff", 1f);
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("radius", Math.min(radius, Math.min(w, h) / 2f));
            mRim.setShader(shader);
            canvas.save();
            canvas.translate(rect.left, rect.top);
            canvas.drawRoundRect(0, 0, w, h, radius, radius, mRim);
            canvas.restore();
        } else {
            mRim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {0x66FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF}, new float[] {0f, 0.35f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, mRim);
        }
        mRim.setShader(null);
    }

    /**
     * The lens: the pill swollen (12 dp wider, 6 dp taller than the tab), the bar under it magnified 1.12x and
     * refracted along its rim with dispersion, a faint white tint and the lit rim; it stretches with its speed
     */
    private void drawLens(Canvas canvas, RectF pill) {
        float p = mPresence;
        float speed = Math.min(1f, Math.abs(mVelocity) / (1800 * mD));
        float w = (pill.width() + LENS_GROW_W_DP * mD * p) * (1f + STRETCH * speed);
        float h = (pill.height() + (LENS_GROW_H_DP + 8) * mD * p) * (1f - STRETCH / 3f * speed);
        RectF lens = new RectF(mX - w / 2f, mBar.centerY() - h / 2f, mX + w / 2f, mBar.centerY() + h / 2f);
        float r = Math.min(24 * mD, Math.min(w, h) / 2f);
        float magnify = 1f + (MAGNIFY - 1f) * p;
        int alpha = Math.round(255 * Math.min(1f, p * 1.6f));
        int layer = alpha < 255 ? canvas.saveLayerAlpha(lens.left - 2 * mD, lens.top - 2 * mD, lens.right + 2 * mD, lens.bottom + 2 * mD, alpha) : -1;
        Path shape = new Path();
        shape.addRoundRect(lens, r, r, Path.Direction.CW);
        if (!drawLensContent(canvas, lens, r, magnify)) {
            // No runtime shaders: the bar under it, magnified, unrefracted
            canvas.save();
            canvas.clipPath(shape);
            canvas.scale(magnify, magnify, mX, mBar.centerY());
            canvas.clipPath(mBarPath);
            drawGlassBar(canvas, mBar.height() / 2f);
            super.dispatchDraw(canvas);
            canvas.restore();
        }
        canvas.save();
        canvas.clipPath(shape);
        canvas.drawColor(mDark ? 0x10FFFFFF : 0x14FFFFFF);
        canvas.restore();
        drawRim(canvas, lens, r, true);
        if (layer >= 0) {
            canvas.restoreToCount(layer);
        }
    }

    /** Android 13+: the bar recorded under the lens, magnified about its middle, through the refraction shader */
    private boolean drawLensContent(Canvas canvas, RectF lens, float r, float magnify) {
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()) {
            return false;
        }
        int w = Math.max(1, Math.round(lens.width()));
        int h = Math.max(1, Math.round(lens.height()));
        RenderNode node = mLensNode instanceof RenderNode ? (RenderNode) mLensNode : new RenderNode("tab lens");
        mLensNode = node;
        node.setPosition(0, 0, w, h);
        RuntimeShader refraction = mLensRefraction instanceof RuntimeShader ? (RuntimeShader) mLensRefraction : new RuntimeShader(REFRACTION_AGSL);
        mLensRefraction = refraction;
        refraction.setFloatUniform("size", w, h);
        refraction.setFloatUniform("radius", Math.min(r, Math.min(w, h) / 2f));
        refraction.setFloatUniform("refractionHeight", LENS_REFRACTION_DP * mD);
        refraction.setFloatUniform("refractionAmount", -LENS_REFRACTION_DP * mD);
        refraction.setFloatUniform("depthEffect", 1f);
        refraction.setFloatUniform("chromaticAberration", 1f);
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(refraction, "content"));
        Canvas recording = node.beginRecording();
        recording.translate(w / 2f, h / 2f);
        recording.scale(magnify, magnify);
        recording.translate(-mX, -mBar.centerY());
        recording.clipPath(mBarPath);
        drawGlassBar(recording, mBar.height() / 2f);
        super.dispatchDraw(recording);
        node.endRecording();
        Path shape = new Path();
        shape.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        canvas.save();
        canvas.translate(lens.left, lens.top);
        canvas.clipPath(shape);
        canvas.drawRenderNode(node);
        canvas.restore();
        return true;
    }

    /**
     * The feed behind the bar, drawn into the shape through the glass's effect (Android 12+): floating, blurred
     * 24 dp; liquid, saturated 1.5x, blurred 8 dp and refracted 24 dp deep along the rim (Android 13+)
     */
    private boolean drawBackdrop(Canvas canvas, Path shape, boolean liquid) {
        if (Build.VERSION.SDK_INT < 31 || mBackdrop == null || !canvas.isHardwareAccelerated() || !mBackdrop.isAttachedToWindow()) {
            return false;
        }
        RenderNode node = mBarNode instanceof RenderNode ? (RenderNode) mBarNode : new RenderNode("tab bar glass");
        mBarNode = node;
        int w = Math.max(1, Math.round(mBar.width()));
        int h = Math.max(1, Math.round(mBar.height()));
        node.setPosition(0, 0, w, h);
        if (w != mEffectW || h != mEffectH || mEffectStyle != mStyle) {
            mEffectW = w;
            mEffectH = h;
            mEffectStyle = mStyle;
            node.setRenderEffect(glassEffect(w, h, liquid));
        }
        getLocationInWindow(mHere);
        mBackdrop.getLocationInWindow(mThere);
        Canvas recording = node.beginRecording();
        recording.translate(mThere[0] - mHere[0] - mBar.left, mThere[1] - mHere[1] - mBar.top);
        mBackdrop.draw(recording);
        node.endRecording();
        canvas.save();
        canvas.clipPath(shape);
        canvas.translate(mBar.left, mBar.top);
        canvas.drawRenderNode(node);
        canvas.restore();
        return true;
    }

    private RenderEffect glassEffect(int w, int h, boolean liquid) {
        float blur = (liquid ? BLUR_DP : 24f) * mD;
        if (!liquid) {
            return RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
        }
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(SATURATION);
        RenderEffect frost = RenderEffect.createBlurEffect(blur, blur,
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturate)), Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT < 33) {
            return frost;
        }
        RuntimeShader lens = mRefraction instanceof RuntimeShader ? (RuntimeShader) mRefraction : new RuntimeShader(REFRACTION_AGSL);
        mRefraction = lens;
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("radius", Math.min(w, h) / 2f);
        lens.setFloatUniform("refractionHeight", REFRACTION_DP * mD);
        lens.setFloatUniform("refractionAmount", -REFRACTION_DP * mD);
        lens.setFloatUniform("depthEffect", 1f);
        lens.setFloatUniform("chromaticAberration", 1f);
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), frost);
    }

    /** Fine grain for the glass (about 4%) */
    private static Bitmap noise() {
        if (sNoise == null) {
            int size = 96;
            int[] px = new int[size * size];
            Random random = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = random.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            sNoise = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        }
        return sNoise;
    }

    // endregion
}
