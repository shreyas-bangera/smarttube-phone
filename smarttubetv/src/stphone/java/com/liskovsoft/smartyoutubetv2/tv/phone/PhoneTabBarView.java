package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The bottom tabs (Customize > Tab bar, see PhoneTabBar). Classic is a plain bar. The two floating looks are a bar of
 * glass over the feed: what scrolls behind it shows through, blurred (Android 12 and up; a frosted tint before), with
 * a sheen on top and an edge catching the light. A bubble sits on the chosen tab:
 * <ul>
 *     <li>Floating: a bubble of the app's red.</li>
 *     <li>Liquid glass: a clear lens, rising a little out of the bar, the tab under it magnified, a soft highlight and
 *     a rim catching prism colours.</li>
 * </ul>
 * The bubble moves on a spring, stretching sideways the faster it goes, like a drop. Slide a finger along the bar and
 * it follows the finger, swelling while it's held, ticking at each tab; let go and the tab under it opens.
 */
public class PhoneTabBarView extends LinearLayout {
    private int mStyle = PhoneTabBar.CLASSIC;
    private int mSide;
    private int mBelow;
    private int mRise;
    private boolean mDark;
    private final float mD;
    private final int mAccent;

    private final RectF mBar = new RectF();
    private final Path mBarPath = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** What scrolls behind the bar (the feed), drawn blurred into it */
    private View mBackdrop;
    private Object mBlurNode;
    private Object mLensNode;
    private final Paint mRed = new Paint();
    private final Paint mCyan = new Paint();
    private final int[] mHere = new int[2];
    private final int[] mThere = new int[2];
    private final ViewTreeObserver.OnPreDrawListener mFollowBackdrop = () -> {
        if (mStyle != PhoneTabBar.CLASSIC) {
            invalidate(); // the feed moved or changed: the glass shows it
        }
        return true;
    };

    // The bubble: where it is, where it's going, its speed; how far it's lifted (held or dragged)
    private View mChosen;
    private float mX = -1;
    private float mW;
    private float mTargetX;
    private float mTargetW;
    private float mVelocity;
    private float mLift;
    private float mLiftTarget;
    private long mLastFrame;
    private boolean mAnimating;

    // A finger sliding along the bar
    private boolean mDragging;
    private float mDownX;
    private View mHovered;

    public PhoneTabBarView(Context context) {
        this(context, null);
    }

    public PhoneTabBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mD = context.getResources().getDisplayMetrics().density;
        mAccent = context.getResources().getColor(R.color.phone_accent);
        mStroke.setStyle(Paint.Style.STROKE);
        // The lens's colour fringes: the red and the green-blue of what's under its rim, a little apart
        mRed.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] {
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0}));
        mRed.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD));
        mCyan.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] {
                0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0}));
        mCyan.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD));
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** The look, and the navigation bar's height under the bar */
    void setStyle(int style, int navInset) {
        mStyle = style;
        mDark = PhoneTabBar.isDark(getContext());
        if (style == PhoneTabBar.CLASSIC) {
            setBackgroundResource(R.drawable.phone_bottom_bar_bg);
            setPadding(0, 0, 0, navInset);
            setElevation(0);
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            invalidate();
            return;
        }
        mSide = Math.round(16 * mD);
        mBelow = navInset + Math.round(10 * mD);
        mRise = Math.round(7 * mD); // room above the bar for the lens rising out of it
        setBackground(null);
        setPadding(mSide + Math.round(6 * mD), mRise + Math.round(7 * mD), mSide + Math.round(6 * mD), mBelow + Math.round(7 * mD));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(mSide, mRise, view.getWidth() - mSide, view.getHeight() - mBelow,
                        (view.getHeight() - mBelow - mRise) / 2f);
                outline.setAlpha(0.6f);
            }
        });
        setElevation(8 * mD);
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
        mChosen = tab;
        if (tab == null || mStyle == PhoneTabBar.CLASSIC) {
            return;
        }
        if (tab.getWidth() == 0) {
            post(() -> select(tab, false));
            return;
        }
        mTargetX = tab.getLeft() + tab.getWidth() / 2f;
        mTargetW = tab.getWidth();
        if (!animate || mX < 0) {
            mX = mTargetX;
            mW = mTargetW;
            mVelocity = 0;
            invalidate();
            return;
        }
        startAnimation();
    }

    // region The spring

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
            // A spring a little under critical damping: it arrives with a small, soft overshoot
            float stiffness = mDragging ? 900 : 420;
            float damping = 2 * (float) Math.sqrt(stiffness) * (mDragging ? 0.95f : 0.72f);
            float accel = stiffness * (mTargetX - mX) - damping * mVelocity;
            mVelocity += accel * dt;
            mX += mVelocity * dt;
            mW += (mTargetW - mW) * Math.min(1f, dt * 14);
            mLift += (mLiftTarget - mLift) * Math.min(1f, dt * 12);
            invalidate();
            boolean settled = Math.abs(mTargetX - mX) < 0.5f && Math.abs(mVelocity) < 5
                    && Math.abs(mLiftTarget - mLift) < 0.01f && Math.abs(mTargetW - mW) < 0.5f;
            if (settled && !mDragging) {
                mX = mTargetX;
                mW = mTargetW;
                mVelocity = 0;
                mLift = mLiftTarget;
                mAnimating = false;
                invalidate();
                return;
            }
            postOnAnimation(this);
        }
    };

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
                mLiftTarget = 0.45f; // a press: the bubble swells a little
                startAnimation();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!mDragging) {
                    mLiftTarget = 0;
                    startAnimation();
                }
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
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); // a tick at each tab
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
            mTargetW = tab.getWidth();
        }
        startAnimation();
        if (open && tab != null) {
            tab.performClick();
            postDelayed(() -> {
                if (mChosen != null && mChosen != tab && !mDragging) {
                    select(mChosen, true); // Shorts or Notifications opened over this screen
                }
            }, 350);
        } else if (mChosen != null) {
            select(mChosen, true);
        }
    }

    // endregion

    // region Drawing

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
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
        float radius = mBar.height() / 2f;
        mBarPath.reset();
        mBarPath.addRoundRect(mBar, radius, radius, Path.Direction.CW);

        drawGlass(canvas, mBarPath, mBar, radius, false);
        RectF bubble = bubble();
        if (bubble != null && mStyle == PhoneTabBar.FLOATING) {
            drawRedBubble(canvas, bubble);
        }
        super.dispatchDraw(canvas);
        if (bubble != null && mStyle == PhoneTabBar.GLASS) {
            drawLens(canvas, bubble);
        }
    }

    /** The bubble's shape now: on the chosen tab (or under the finger), wider while it moves, bigger while held */
    private RectF bubble() {
        if (mX < 0 || mW <= 0) {
            return null;
        }
        float speed = Math.min(1f, Math.abs(mVelocity) / 2600f);
        float width = mW * (1f + 0.32f * speed + 0.10f * mLift);
        float inset = mStyle == PhoneTabBar.GLASS ? 2 * mD : 5 * mD;
        float top = mBar.top + inset - mLift * (mStyle == PhoneTabBar.GLASS ? mRise + 2 * mD : 3 * mD) + speed * 2 * mD;
        float bottom = mBar.bottom - inset + mLift * (mStyle == PhoneTabBar.GLASS ? 6 * mD : 3 * mD) - speed * 2 * mD;
        return new RectF(mX - width / 2f, top, mX + width / 2f, bottom);
    }

    /** The feed behind, blurred, inside the shape; then the glass's tint, sheen and edge */
    private void drawGlass(Canvas canvas, Path shape, RectF bounds, float radius, boolean lens) {
        boolean blurred = drawBackdrop(canvas, shape, lens ? 6 : 28);
        mPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                mDark ? (blurred ? 0x59202024 : 0xD91C1C1F) : (blurred ? 0x66FFFFFF : 0xE6F7F7F9),
                mDark ? (blurred ? 0x80101012 : 0xE6101012) : (blurred ? 0x80F2F2F7 : 0xF2F2F2F7), Shader.TileMode.CLAMP));
        canvas.drawPath(shape, mPaint);
        if (mStyle == PhoneTabBar.FLOATING && !lens) {
            // A hint of the app's red in the glass, as in the reference
            mPaint.setShader(new LinearGradient(bounds.left, 0, bounds.right, 0,
                    (mAccent & 0x00FFFFFF) | 0x14000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawPath(shape, mPaint);
        }
        mPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.centerY(),
                mDark ? 0x1AFFFFFF : 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, mPaint);
        mPaint.setShader(null);
        mStroke.setStrokeWidth(1.1f * mD);
        mStroke.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                mDark ? 0x5CFFFFFF : 0xB3FFFFFF, mDark ? 0x12FFFFFF : 0x1F000000, Shader.TileMode.CLAMP));
        RectF edge = new RectF(bounds);
        edge.inset(0.55f * mD, 0.55f * mD);
        canvas.drawRoundRect(edge, radius, radius, mStroke);
        mStroke.setShader(null);
    }

    /** The feed under the bar, blurred, clipped to the shape (Android 12+, on the GPU); false when it can't be */
    private boolean drawBackdrop(Canvas canvas, Path shape, float blur) {
        Object node = canvas.isHardwareAccelerated() ? backdropNode(false, blur) : null;
        if (node == null) {
            return false;
        }
        canvas.save();
        canvas.clipPath(shape);
        canvas.drawRenderNode((RenderNode) node);
        canvas.restore();
        return true;
    }

    /** The feed under the bar recorded (from where it is on screen), blurred this much; null before Android 12 */
    private Object backdropNode(boolean lens, float blur) {
        if (Build.VERSION.SDK_INT < 31 || mBackdrop == null || !isHardwareAccelerated() || !mBackdrop.isAttachedToWindow()) {
            return null;
        }
        Object existing = lens ? mLensNode : mBlurNode;
        RenderNode node = existing instanceof RenderNode ? (RenderNode) existing : new RenderNode(lens ? "tab lens" : "tab bar glass");
        if (lens) {
            mLensNode = node;
        } else {
            mBlurNode = node;
        }
        node.setPosition(0, 0, getWidth(), getHeight());
        node.setRenderEffect(blur > 0 ? RenderEffect.createBlurEffect(blur * mD, blur * mD, Shader.TileMode.CLAMP) : null);
        getLocationInWindow(mHere);
        mBackdrop.getLocationInWindow(mThere);
        android.graphics.RecordingCanvas recording = node.beginRecording();
        recording.translate(mThere[0] - mHere[0], mThere[1] - mHere[1]);
        mBackdrop.draw(recording);
        node.endRecording();
        return node;
    }

    /** Floating: a bubble of the app's red, see-through, lighter at its top edge */
    private void drawRedBubble(Canvas canvas, RectF bubble) {
        float r = bubble.height() / 2f;
        mPaint.setShader(new LinearGradient(0, bubble.top, 0, bubble.bottom,
                (mAccent & 0x00FFFFFF) | (mDark ? 0x66000000 : 0x40000000),
                (mAccent & 0x00FFFFFF) | (mDark ? 0x40000000 : 0x29000000), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bubble, r, r, mPaint);
        mPaint.setShader(null);
        mStroke.setStrokeWidth(1f * mD);
        mStroke.setShader(new LinearGradient(0, bubble.top, 0, bubble.bottom,
                (mAccent & 0x00FFFFFF) | 0x99000000, (mAccent & 0x00FFFFFF) | 0x26000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bubble, r, r, mStroke);
        mStroke.setShader(null);
    }

    /**
     * Liquid glass: a clear lens. What's under it (the feed and the tabs) shows magnified, as through a drop of water;
     * near its rim it bends more, with red and blue fringes; light pools in it, a reflection runs along its top and
     * its rim catches prism colours.
     */
    private void drawLens(Canvas canvas, RectF lens) {
        float r = lens.height() / 2f;
        Path shape = new Path();
        shape.addRoundRect(lens, r, r, Path.Direction.CW);
        float cx = lens.centerX();
        float cy = mBar.centerY();
        float zoom = 1.16f + 0.10f * mLift;
        Object clear = canvas.isHardwareAccelerated() ? backdropNode(true, 1.2f) : null;

        canvas.save();
        canvas.clipPath(shape);
        // The middle: clear, magnified
        canvas.save();
        canvas.scale(zoom, zoom, cx, cy);
        if (clear != null) {
            canvas.drawRenderNode((RenderNode) clear);
        }
        canvas.restore();
        mPaint.setShader(null);
        mPaint.setColor(mDark ? (clear != null ? 0x38000000 : 0xCC1A1A1D) : (clear != null ? 0x1FFFFFFF : 0xD9F7F7F9));
        canvas.drawRoundRect(lens, r, r, mPaint);
        canvas.save();
        canvas.scale(zoom, zoom, cx, cy);
        super.dispatchDraw(canvas);
        canvas.restore();

        // The rim: a ring that bends more, its colours split
        Path ring = new Path();
        RectF inner = new RectF(lens);
        float band = Math.min(lens.height(), lens.width()) * 0.2f;
        inner.inset(band, band);
        float ir = Math.max(0, r - band);
        ring.addRoundRect(lens, r, r, Path.Direction.CW);
        ring.addRoundRect(inner, ir, ir, Path.Direction.CCW);
        ring.setFillType(Path.FillType.WINDING);
        canvas.clipPath(ring);
        float edgeZoom = zoom * 1.18f;
        float split = 1.3f * mD;
        for (int pass = 0; pass < 2; pass++) {
            int layer = canvas.saveLayer(lens, pass == 0 ? mRed : mCyan);
            canvas.translate(pass == 0 ? split : -split, 0);
            canvas.scale(edgeZoom, edgeZoom, cx, cy);
            if (clear != null) {
                canvas.drawRenderNode((RenderNode) clear);
            }
            super.dispatchDraw(canvas);
            canvas.restoreToCount(layer);
        }
        canvas.restore();

        // Light pooling in it
        mPaint.setShader(new RadialGradient(cx, lens.top + lens.height() * 0.3f, lens.width() * 0.6f,
                new int[] {mDark ? 0x24FFFFFF : 0x4DFFFFFF, mDark ? 0x08FFFFFF : 0x14FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(lens, r, r, mPaint);
        // The reflection: a bright band along the top, and a faint one at the bottom
        mPaint.setShader(new LinearGradient(0, lens.top, 0, lens.top + lens.height() * 0.42f,
                mDark ? 0x47FFFFFF : 0x8CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        RectF shine = new RectF(lens.left + r * 0.4f, lens.top + 1.5f * mD, lens.right - r * 0.4f, lens.top + lens.height() * 0.46f);
        canvas.drawRoundRect(shine, shine.height() / 2f, shine.height() / 2f, mPaint);
        mPaint.setShader(new LinearGradient(0, lens.bottom - lens.height() * 0.22f, 0, lens.bottom,
                0x00FFFFFF, mDark ? 0x1FFFFFFF : 0x40FFFFFF, Shader.TileMode.CLAMP));
        RectF under = new RectF(lens.left + r * 0.7f, lens.bottom - lens.height() * 0.22f, lens.right - r * 0.7f, lens.bottom - 1.5f * mD);
        canvas.drawRoundRect(under, under.height() / 2f, under.height() / 2f, mPaint);
        mPaint.setShader(null);
        // The rim: bright where the light hits, prism colours along the curves
        mStroke.setStrokeWidth(1.4f * mD);
        mStroke.setShader(new SweepGradient(cx, lens.centerY(),
                new int[] {0xB3FFFFFF, 0x80FF6FD8, 0x8055D6FF, 0xE6FFFFFF, 0x80FFE066, 0x8070FFB0, 0xB3FFFFFF}, null));
        RectF rim = new RectF(lens);
        rim.inset(0.7f * mD, 0.7f * mD);
        canvas.drawRoundRect(rim, r, r, mStroke);
        mStroke.setShader(null);
    }

    // endregion
}
