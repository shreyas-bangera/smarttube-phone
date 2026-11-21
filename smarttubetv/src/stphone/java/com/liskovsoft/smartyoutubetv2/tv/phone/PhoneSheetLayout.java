package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/**
 * A bottom sheet that closes by being swiped down: from its handle or title, or from its list once the list is at
 * its top (as in the YouTube app). It follows the finger and closes when let go past a quarter of its height or
 * flicked down; otherwise it settles back. In landscape (and on tablets) it's narrower, centred.
 */
public class PhoneSheetLayout extends LinearLayout {
    /** The widest the sheet gets in landscape, so it doesn't stretch across the whole screen */
    private static final int MAX_WIDTH_DP = 520;

    private Runnable mOnDismiss;
    private float mDownY;
    private float mDownX;
    private boolean mDragging;
    private VelocityTracker mVelocity;
    private final int mSlop;

    public PhoneSheetLayout(Context context) {
        this(context, null);
    }

    public PhoneSheetLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** What closes the sheet (finish the screen, dismiss the dialog) */
    void setOnDismiss(Runnable onDismiss) {
        mOnDismiss = onDismiss;
    }

    /**
     * The phone's real screen (density, width, height): the player's screen also carries SmartTube's TV layout scale,
     * which on some phones reaches the menus' context and made them full width and oversized in landscape
     */
    static android.util.DisplayMetrics screen() {
        return android.content.res.Resources.getSystem().getDisplayMetrics();
    }

    /** dp in real pixels, on the phone's own density */
    static int dp(float value) {
        return Math.round(value * screen().density);
    }

    /** A text size in sp, as real pixels (the phone's density and font size): use with COMPLEX_UNIT_PX */
    static float sp(float value) {
        return value * screen().scaledDensity;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        android.util.DisplayMetrics screen = screen();
        int max = Math.round(MAX_WIDTH_DP * screen.density);
        boolean wide = screen.widthPixels > screen.heightPixels // landscape, whatever the context says
                || Math.min(screen.widthPixels, screen.heightPixels) / screen.density >= 600;
        if (wide && MeasureSpec.getSize(widthMeasureSpec) > max) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mOnDismiss == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = event.getRawY();
                mDownX = event.getRawX();
                mDragging = false;
                track(event, true);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(event, false);
                float dy = event.getRawY() - mDownY;
                float dx = Math.abs(event.getRawX() - mDownX);
                if (dy > mSlop && dy > dx && !childCanScrollUp(this, event.getRawX(), event.getRawY())) {
                    mDragging = true; // down, with nothing above to scroll back to: the sheet moves
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mOnDismiss == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = event.getRawY();
                mDownX = event.getRawX();
                mDragging = false;
                track(event, true);
                return true; // the handle, the title, empty parts: a drag can start here
            case MotionEvent.ACTION_MOVE:
                track(event, false);
                float dy = event.getRawY() - mDownY;
                if (!mDragging && dy > mSlop) {
                    mDragging = true;
                }
                if (mDragging) {
                    setTranslationY(Math.max(0, dy));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                track(event, false);
                float velocity = 0;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    velocity = mVelocity.getYVelocity();
                    mVelocity.recycle();
                    mVelocity = null;
                }
                if (mDragging) {
                    boolean close = getTranslationY() > getHeight() * 0.25f || velocity > 1200 * getResources().getDisplayMetrics().density;
                    if (close && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        animate().translationY(getHeight()).setDuration(160).withEndAction(mOnDismiss).start();
                    } else {
                        animate().translationY(0).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                }
                mDragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void track(MotionEvent event, boolean start) {
        if (start && mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
        if (mVelocity == null) {
            mVelocity = VelocityTracker.obtain();
        }
        MotionEvent copy = MotionEvent.obtain(event);
        copy.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(copy);
        copy.recycle();
    }

    /** A list under the finger that isn't at its top yet: the drag scrolls it back up first */
    private static boolean childCanScrollUp(View view, float rawX, float rawY) {
        if (!view.isShown()) {
            return false;
        }
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        if (rawX < at[0] || rawX > at[0] + view.getWidth() || rawY < at[1] || rawY > at[1] + view.getHeight()) {
            return false;
        }
        if (view != null && view.canScrollVertically(-1)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (childCanScrollUp(group.getChildAt(i), rawX, rawY)) {
                    return true;
                }
            }
        }
        return false;
    }
}
