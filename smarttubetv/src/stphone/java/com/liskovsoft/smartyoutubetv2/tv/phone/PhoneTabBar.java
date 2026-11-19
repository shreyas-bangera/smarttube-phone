package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The bottom tabs' look (Customize > Tab bar):
 * <ul>
 *     <li>Classic: across the bottom, as in the YouTube app.</li>
 *     <li>Floating: a rounded bar floating over the feed, the chosen tab on a tinted pill in the app's red.</li>
 *     <li>Liquid glass: a see-through glass bar with a light edge, the chosen tab under a glossy lens (a soft
 *     highlight, a rim catching the colours) that glides to the next tab, stretching on the way like a drop.</li>
 * </ul>
 * The floating bars are drawn by {@link Background} on the tab bar itself; the tabs stay its children.
 */
final class PhoneTabBar {
    static final int CLASSIC = 0;
    static final int FLOATING = 1;
    static final int GLASS = 2;

    private PhoneTabBar() {
    }

    static int nameRes(int style) {
        return style == FLOATING ? R.string.phone_tabs_floating : style == GLASS ? R.string.phone_tabs_glass : R.string.phone_tabs_classic;
    }

    static boolean isDark(Context context) {
        int bg = context.getResources().getColor(R.color.phone_bg);
        return (Color.red(bg) + Color.green(bg) + Color.blue(bg)) / 3 < 128;
    }

    /** Lays the bar out for the style: its padding, background and shadow (the navigation bar's height under it) */
    static void apply(LinearLayout bar, int style, int navInset) {
        Context context = bar.getContext();
        float d = context.getResources().getDisplayMetrics().density;
        if (style == CLASSIC) {
            bar.setBackgroundResource(R.drawable.phone_bottom_bar_bg);
            bar.setPadding(0, 0, 0, navInset);
            bar.setElevation(0);
            bar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            return;
        }
        int side = Math.round(16 * d);
        int below = navInset + Math.round(10 * d);
        Background background = bar.getBackground() instanceof Background ? (Background) bar.getBackground() : new Background(context);
        background.set(style, side, below, isDark(context));
        bar.setBackground(background);
        bar.setPadding(side + Math.round(6 * d), Math.round(7 * d), side + Math.round(6 * d), below + Math.round(7 * d));
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(side, 0, view.getWidth() - side, view.getHeight() - below, (view.getHeight() - below) / 2f);
                outline.setAlpha(style == GLASS ? 0.5f : 0.8f);
            }
        });
        bar.setElevation(style == GLASS ? 6 * d : 10 * d);
        bar.invalidateOutline();
    }

    /** The chosen tab: the pill or the lens moves to it (with an animation when the bar is already showing one) */
    static void select(LinearLayout bar, View tab, boolean animate) {
        if (bar.getBackground() instanceof Background && tab != null) {
            if (tab.getWidth() == 0) {
                tab.post(() -> select(bar, tab, false));
                return;
            }
            ((Background) bar.getBackground()).moveTo(tab, animate);
        }
    }

    /** The colour of a tab's icon and words */
    static int tabColor(Context context, int style, boolean selected) {
        boolean dark = isDark(context);
        if (style == FLOATING) {
            return selected ? context.getResources().getColor(R.color.phone_accent) : (dark ? 0xFFB3B3B3 : 0xFF5F5F5F);
        }
        if (style == GLASS) {
            return dark ? (selected ? Color.WHITE : 0xD9FFFFFF) : (selected ? 0xFF000000 : 0xCC1C1C1E);
        }
        return context.getResources().getColor(R.color.phone_text);
    }

    static final class Background extends Drawable {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mD;
        private final int mAccent;
        private int mStyle;
        private int mSide;
        private int mBelow;
        private boolean mDark;
        private final RectF mBar = new RectF();
        /** Where the pill or lens is now (in the bar's coordinates), and where it's going */
        private final RectF mSel = new RectF();
        private final RectF mFrom = new RectF();
        private final RectF mTo = new RectF();
        private ValueAnimator mMove;
        private float mStretch;

        Background(Context context) {
            mD = context.getResources().getDisplayMetrics().density;
            mAccent = context.getResources().getColor(R.color.phone_accent);
            mEdge.setStyle(Paint.Style.STROKE);
            mRim.setStyle(Paint.Style.STROKE);
        }

        void set(int style, int side, int below, boolean dark) {
            mStyle = style;
            mSide = side;
            mBelow = below;
            mDark = dark;
            onBoundsChange(getBounds());
            invalidateSelf();
        }

        void moveTo(View tab, boolean animate) {
            float inset = mStyle == GLASS ? -2 * mD : 2 * mD;
            mTo.set(tab.getLeft() + inset, mBar.top + (mStyle == GLASS ? 3 * mD : 5 * mD),
                    tab.getRight() - inset, mBar.bottom - (mStyle == GLASS ? 3 * mD : 5 * mD));
            if (mMove != null) {
                mMove.cancel();
            }
            if (!animate || mSel.isEmpty()) {
                mSel.set(mTo);
                mStretch = 0;
                invalidateSelf();
                return;
            }
            mFrom.set(mSel);
            mMove = ValueAnimator.ofFloat(0f, 1f);
            mMove.setDuration(mStyle == GLASS ? 420 : 300);
            mMove.setInterpolator(new OvershootInterpolator(mStyle == GLASS ? 1.4f : 1f));
            mMove.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                mSel.set(lerp(mFrom.left, mTo.left, t), lerp(mFrom.top, mTo.top, t),
                        lerp(mFrom.right, mTo.right, t), lerp(mFrom.bottom, mTo.bottom, t));
                // Liquid: it stretches while it moves, most in the middle of the way
                mStretch = mStyle == GLASS ? (float) Math.sin(Math.min(1f, Math.max(0f, t)) * Math.PI) : 0;
                invalidateSelf();
            });
            mMove.start();
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            mBar.set(bounds.left + mSide, bounds.top, bounds.right - mSide, bounds.bottom - mBelow);
            if (!mTo.isEmpty()) {
                float h = mStyle == GLASS ? 3 * mD : 5 * mD;
                mTo.top = mBar.top + h;
                mTo.bottom = mBar.bottom - h;
                mSel.top = mTo.top;
                mSel.bottom = mTo.bottom;
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mBar.isEmpty()) {
                return;
            }
            float radius = mBar.height() / 2f;
            if (mStyle == GLASS) {
                drawGlass(canvas, radius);
            } else {
                drawFloating(canvas, radius);
            }
        }

        private void drawFloating(Canvas canvas, float radius) {
            mFill.setShader(null);
            mFill.setColor(mDark ? 0xF21A1718 : 0xF7FFFFFF);
            canvas.drawRoundRect(mBar, radius, radius, mFill);
            mEdge.setShader(null);
            mEdge.setStrokeWidth(mD);
            mEdge.setColor(mDark ? 0x24FFFFFF : 0x14000000);
            canvas.drawRoundRect(mBar, radius, radius, mEdge);
            if (!mSel.isEmpty()) {
                // The chosen tab on a soft pill of the app's red
                mPill.setShader(null);
                mPill.setColor((mAccent & 0x00FFFFFF) | (mDark ? 0x38000000 : 0x24000000));
                float r = mSel.height() / 2f;
                canvas.drawRoundRect(mSel, r, r, mPill);
            }
        }

        private void drawGlass(Canvas canvas, float radius) {
            // The glass: see-through, a little darker (or lighter) at the bottom, a sheen on the top half
            mFill.setShader(new LinearGradient(0, mBar.top, 0, mBar.bottom,
                    mDark ? 0x9E2A2A2E : 0xB8FFFFFF, mDark ? 0xB8101012 : 0xCCF2F2F7, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(mBar, radius, radius, mFill);
            mFill.setShader(new LinearGradient(0, mBar.top, 0, mBar.centerY(),
                    mDark ? 0x1FFFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(mBar, radius, radius, mFill);
            // Its edge catches the light at the top
            mEdge.setStrokeWidth(1.2f * mD);
            mEdge.setShader(new LinearGradient(0, mBar.top, 0, mBar.bottom,
                    mDark ? 0x66FFFFFF : 0x99FFFFFF, mDark ? 0x14FFFFFF : 0x1A000000, Shader.TileMode.CLAMP));
            RectF edge = new RectF(mBar);
            edge.inset(0.6f * mD, 0.6f * mD);
            canvas.drawRoundRect(edge, radius, radius, mEdge);
            if (mSel.isEmpty()) {
                return;
            }
            // The lens over the chosen tab: wider and flatter while it glides
            RectF lens = new RectF(mSel);
            float grow = mStretch * 0.22f * lens.width();
            lens.inset(-grow / 2f, mStretch * 2 * mD);
            float r = lens.height() / 2f;
            mPill.setShader(new RadialGradient(lens.centerX(), lens.top + lens.height() * 0.35f, lens.width() * 0.6f,
                    new int[] {mDark ? 0x40FFFFFF : 0x80FFFFFF, mDark ? 0x14FFFFFF : 0x33FFFFFF, mDark ? 0x08FFFFFF : 0x1AFFFFFF},
                    new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(lens, r, r, mPill);
            // Its rim: bright at the top, the colours of a prism along the sides
            mRim.setStrokeWidth(1.3f * mD);
            mRim.setShader(new SweepGradient(lens.centerX(), lens.centerY(),
                    new int[] {0x88FFFFFF, 0x55FF7AD9, 0x5566E0FF, 0xCCFFFFFF, 0x55FFE27A, 0x557AFFB0, 0x88FFFFFF},
                    null));
            RectF rim = new RectF(lens);
            rim.inset(0.65f * mD, 0.65f * mD);
            canvas.drawRoundRect(rim, r, r, mRim);
            // A small highlight high on the lens
            mPill.setShader(new LinearGradient(0, lens.top, 0, lens.top + lens.height() * 0.45f,
                    mDark ? 0x33FFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            RectF shine = new RectF(lens.left + r * 0.5f, lens.top + 2 * mD, lens.right - r * 0.5f, lens.top + lens.height() * 0.5f);
            canvas.drawRoundRect(shine, shine.height() / 2f, shine.height() / 2f, mPill);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
