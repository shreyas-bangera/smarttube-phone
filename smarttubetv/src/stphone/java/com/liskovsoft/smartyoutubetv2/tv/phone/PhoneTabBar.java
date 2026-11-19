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
            return selected ? context.getResources().getColor(R.color.phone_accent) : (dark ? 0xFFB3B3B3 : 0xFF5F5F5F);
        }
        if (style == GLASS) {
            return dark ? (selected ? Color.WHITE : 0xD9FFFFFF) : (selected ? 0xFF000000 : 0xCC1C1C1E);
        }
        return context.getResources().getColor(R.color.phone_text);
    }
}
