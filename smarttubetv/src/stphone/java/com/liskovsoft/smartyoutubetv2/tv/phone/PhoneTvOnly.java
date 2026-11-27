package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * SmartTube settings that only do something on a TV: a remote's buttons, the TV's cards and grids, the display's
 * refresh rate, pairing as a cast target. The phone UI never reads them, so they're left out of the phone's settings.
 */
final class PhoneTvOnly {
    /** Rows of the Settings list */
    private static final int[] SETTINGS = {
            R.string.settings_remote_control, // this device as a cast target, paired with a TV code
            R.string.auto_frame_rate,         // switches the TV's refresh rate to the video's
    };

    /**
     * Categories (and single buttons) inside the settings pages: a remote's buttons, the TV's screen, grid and cards,
     * or something the phone does its own way
     */
    private static final int[] CATEGORIES = {
            R.string.key_remapping,              // General: buttons of the remote
            R.string.app_exit_shortcut,          // General: what the remote's Back does on the home screen
            R.string.screen_dimming,             // General: the TV's screensaver
            R.string.player_ok_button_behavior,  // Player: the remote's OK button
            R.string.player_buttons,             // Player: the TV player's row of buttons (the phone has its own)
            R.string.player_seek_type,           // Player: how the remote's arrows seek
            R.string.player_show_ending_time,    // Player: the TV player's clock
            R.string.player_pixel_ratio,         // Player: the TV panel's pixel shape
            R.string.player_volume,              // Player: master volume (the phone's volume swipe and keys)
            R.string.player_sleep_timer,         // Player: the TV's sleep timer (the phone's is in the player's gear)
            R.string.various_buttons,            // Interface: buttons above the TV's video grid
            R.string.color_scheme,               // Interface: the TV's colours (the phone has Theme)
            R.string.card_preview,               // Interface: animated previews on a focused TV card
            R.string.cards_style,                // Interface: the TV's card shapes
            R.string.card_content,               // Interface: which frame the TV's cards show
            R.string.card_text_scroll_factor,    // Interface: title scrolling on a focused TV card
            R.string.scale_ui,                   // Interface: the TV screen's scale
            R.string.video_grid_scale,           // Interface: size of the TV's video grid
    };

    /** Single options inside the pages' lists ("Other" and so on) that only do something on a TV */
    private static final int[] OPTIONS = {
            R.string.disable_screensaver, R.string.disable_ok_long_press, R.string.player_exit_shortcut,
            R.string.search_exit_shortcut, R.string.return_to_launcher, R.string.app_corner_clock,
            R.string.player_corner_clock, R.string.player_corner_ending_time, R.string.channels_old_look,
            R.string.fullscreen_mode, R.string.time_format_24, R.string.player_number_key_seek, R.string.player_show_clock,
            R.string.player_global_focus, R.string.player_ui_animations, R.string.player_show_tooltips,
            R.string.player_button_long_click, R.string.long_press_for_options, R.string.player_speed_button_old_behavior,
            R.string.suggestions_horizontally_scrolled, R.string.dont_resize_video_to_fit_dialog, R.string.place_chat_left,
            R.string.place_comments_left, R.string.keyboard_fix, R.string.keyboard_auto_show,
            R.string.focus_on_search_results, R.string.pinned_channel_rows, R.string.playlists_rows,
            R.string.player_show_quality_info, R.string.player_show_quality_info_bitrate, R.string.player_ui_on_next,
            R.string.select_channel_section,
    };

    private PhoneTvOnly() {
    }

    static boolean isSetting(Context context, CharSequence title) {
        return matches(context, title, SETTINGS);
    }

    static boolean isCategory(Context context, CharSequence title) {
        return matches(context, title, CATEGORIES);
    }

    static boolean isOption(Context context, CharSequence title) {
        return matches(context, title, OPTIONS);
    }

    private static boolean matches(Context context, CharSequence title, int[] titles) {
        if (TextUtils.isEmpty(title)) {
            return false;
        }
        for (int resId : titles) {
            if (TextUtils.equals(title, context.getString(resId))) {
                return true;
            }
        }
        return false;
    }
}
