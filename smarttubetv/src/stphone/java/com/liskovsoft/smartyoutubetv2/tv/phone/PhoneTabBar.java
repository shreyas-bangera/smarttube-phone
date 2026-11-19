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
 *     <li>Floating: a capsule of dark glass over the feed (after Nuvio's), the chosen tab in a bubble of YouTube red.</li>
 *     <li>Liquid glass: iOS 26's glass (after MeeTube's), a clear lens gliding between the tabs.</li>
 * </ul>
 * The bar draws itself: see {@link PhoneTabBarView}.
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

    /** Lays the bar out for the style (the navigation bar's height under it) */
    static void apply(LinearLayout bar, int style, int navInset) {
        if (bar instanceof PhoneTabBarView) {
            ((PhoneTabBarView) bar).setStyle(style, navInset);
        }
    }

    /** The chosen tab: the bubble glides to it (or is there at once) */
    static void select(LinearLayout bar, View tab, boolean animate) {
        if (bar instanceof PhoneTabBarView) {
            ((PhoneTabBarView) bar).select(tab, animate);
        }
    }

    /** The colour of a tab's icon and words */
    static int tabColor(Context context, int style, boolean selected) {
        boolean dark = isDark(context);
        if (style == FLOATING) {
            // White (YouTube's near black in light theme); the chosen tab turns YouTube red inside its bubble
            return dark ? Color.WHITE : 0xFF0F0F0F;
        }
        if (style == GLASS) {
            return dark ? Color.WHITE : 0xFF0F0F0F;
        }
        return context.getResources().getColor(R.color.phone_text);
    }
}
