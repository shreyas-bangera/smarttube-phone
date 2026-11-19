package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings > Customize: small changes to how the app looks and behaves. Each tweak is one setting here, shown as one
 * row of the Customize group (PhoneBrowseActivity's settings list), with a line saying what it does.
 */
final class PhoneTweaks {
    private static final String PREFS = "phone_tweaks";
    private static final String KEY_YOU_ON_TOP = "you_on_top";
    private static final String KEY_TAB_STYLE = "tab_style";

    private PhoneTweaks() {
    }

    /** The You page opens from the account picture at the top, next to search, instead of from the bottom bar */
    static boolean youOnTop(Context context) {
        return prefs(context).getBoolean(KEY_YOU_ON_TOP, false);
    }

    static void setYouOnTop(Context context, boolean onTop) {
        prefs(context).edit().putBoolean(KEY_YOU_ON_TOP, onTop).apply();
    }

    /** The bottom tabs' look: Classic, Floating or Liquid glass (see PhoneTabBar) */
    static int tabStyle(Context context) {
        return prefs(context).getInt(KEY_TAB_STYLE, PhoneTabBar.CLASSIC);
    }

    static void setTabStyle(Context context, int style) {
        prefs(context).edit().putInt(KEY_TAB_STYLE, style).apply();
    }

    /** The player's previous / next buttons (kept where they were stored before) */
    static boolean prevNextButtons(Context context) {
        return PhonePlaybackActivity.showPrevNext(context);
    }

    static void setPrevNextButtons(Context context, boolean show) {
        PhonePlaybackActivity.setShowPrevNext(context, show);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
