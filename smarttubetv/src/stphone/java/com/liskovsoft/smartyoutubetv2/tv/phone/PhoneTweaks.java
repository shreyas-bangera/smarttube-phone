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
    private static final String KEY_AMBIENT = "ambient";
    private static final String KEY_AMBIENT_GLOW = "ambient_glow";
    static final int GLOW_SUBTLE = 0;
    static final int GLOW_BALANCED = 1;
    static final int GLOW_VIVID = 2;

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
        return TAB_STYLES_SHOWN ? prefs(context).getInt(KEY_TAB_STYLE, PhoneTabBar.CLASSIC) : PhoneTabBar.CLASSIC;
    }

    /** Customize > Tab bar is hidden for now: the bar is Classic (the Floating and Liquid glass bars stay in the code) */
    static final boolean TAB_STYLES_SHOWN = false;

    static void setTabStyle(Context context, int style) {
        prefs(context).edit().putInt(KEY_TAB_STYLE, style).apply();
    }

    /** Ambient mode: the video's colours glowing around the player (dark appearance) */
    static boolean ambient(Context context) {
        return prefs(context).getBoolean(KEY_AMBIENT, true);
    }

    static void setAmbient(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_AMBIENT, on).apply();
    }

    /** How bright ambient mode's glow is: Subtle, Balanced or Vivid */
    static int ambientGlow(Context context) {
        return prefs(context).getInt(KEY_AMBIENT_GLOW, GLOW_BALANCED);
    }

    static void setAmbientGlow(Context context, int glow) {
        prefs(context).edit().putInt(KEY_AMBIENT_GLOW, glow).apply();
    }

    /** The glow's strength (how opaque the video's colours are around it) */
    static float ambientStrength(Context context) {
        int glow = ambientGlow(context);
        return glow == GLOW_SUBTLE ? 0.34f : glow == GLOW_VIVID ? 0.72f : 0.52f;
    }

    static int glowNameRes(int glow) {
        return glow == GLOW_SUBTLE ? com.liskovsoft.smartyoutubetv2.tv.R.string.phone_tweak_glow_subtle
                : glow == GLOW_VIVID ? com.liskovsoft.smartyoutubetv2.tv.R.string.phone_tweak_glow_vivid
                : com.liskovsoft.smartyoutubetv2.tv.R.string.phone_tweak_glow_balanced;
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
