package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's gear, as in the YouTube app: a bottom sheet with Quality, Playback speed, Captions and More settings
 * (SmartTube's full list). Quality is its own sheet: "Auto (recommended)" and the video's resolutions, the chosen one
 * ticked.
 */
final class PhonePlayerSheet {
    interface Host {
        PhonePlaybackFragment player();

        /** The resolution playing now, e.g. "1080p60", or null */
        String playingQuality();

        void openSpeed();

        void openCaptions();

        void openMoreSettings();

        /** The video can float in Android's picture in picture window now (a regular video, allowed by Android) */
        boolean canFloat();

        /** Float the video over the app (it stays floating when the app is left), as in NewTube's menu */
        void floatVideo();
    }

    private PhonePlayerSheet() {
    }

    static void show(Activity activity, Host host) {
        Sheet sheet = new Sheet(activity, null);
        PhonePlaybackFragment player = host.player();
        String quality = qualityLabel(activity, host);
        sheet.addRow(R.drawable.ic_phone_tune, activity.getString(R.string.phone_quality), quality, false, () -> {
            sheet.dismiss();
            showQuality(activity, host);
        });
        float speed = player != null ? player.getSpeed() : 1f;
        sheet.addRow(R.drawable.ic_phone_speed, activity.getString(R.string.phone_playback_speed), speedLabel(speed), false, () -> {
            sheet.dismiss();
            host.openSpeed();
        });
        sheet.addRow(R.drawable.ic_phone_cc, activity.getString(R.string.phone_captions), null, false, () -> {
            sheet.dismiss();
            host.openCaptions();
        });
        if (host.canFloat()) {
            sheet.addRow(R.drawable.ic_phone_pip, activity.getString(R.string.phone_pip), null, false, () -> {
                sheet.dismiss();
                host.floatVideo();
            });
        }
        sheet.addRow(R.drawable.ic_phone_settings, activity.getString(R.string.phone_more_settings), null, false, () -> {
            sheet.dismiss();
            host.openMoreSettings();
        });
        sheet.show();
    }

    /** "Auto (1080p60)" or the chosen resolution */
    private static String qualityLabel(Activity activity, Host host) {
        FormatItem chosen = chosenFormat(activity, host.player());
        if (chosen == null) {
            String playing = host.playingQuality();
            String auto = activity.getString(R.string.phone_quality_auto_short);
            return playing != null ? auto + " (" + playing + ")" : auto;
        }
        return label(chosen);
    }

    static void showQuality(Activity activity, Host host) {
        PhonePlaybackFragment player = host.player();
        List<FormatItem> formats = player != null ? player.getVideoFormats() : null;
        Sheet sheet = new Sheet(activity, activity.getString(R.string.phone_quality));

        FormatItem chosen = chosenFormat(activity, player);
        Map<String, FormatItem> byLabel = new LinkedHashMap<>();
        List<FormatItem> sorted = formats != null ? new ArrayList<>(formats) : new ArrayList<>();
        Collections.sort(sorted, (a, b) -> {
            int byHeight = Integer.compare(b.getHeight(), a.getHeight());
            return byHeight != 0 ? byHeight : Float.compare(b.getFrameRate(), a.getFrameRate());
        });
        for (FormatItem format : sorted) {
            if (format == null) {
                continue;
            }
            if (format.isDefault() || format.getHeight() <= 0) {
                continue; // SmartTube's "default" entry switches the video off (sound only): not a quality
            }
            String label = label(format);
            // One row per resolution (the same one comes in several codecs): the chosen one wins, else the first
            if (!byLabel.containsKey(label) || format == chosen) {
                byLabel.put(label, format);
            }
        }

        sheet.addRow(0, activity.getString(R.string.phone_quality_auto), null, chosen == null, () -> {
            sheet.dismiss();
            setAuto(activity, true);
            apply(activity, player, autoFormat());
        });
        for (Map.Entry<String, FormatItem> entry : byLabel.entrySet()) {
            FormatItem format = entry.getValue();
            boolean selected = chosen != null && label(chosen).equals(entry.getKey());
            sheet.addRow(0, entry.getKey(), null, selected, () -> {
                sheet.dismiss();
                setAuto(activity, false);
                apply(activity, player, format);
            });
        }
        sheet.show();
    }

    /**
     * Auto: the phone's own choice, the best quality up to 1080p (60 fps where the phone decodes VP9 at 1080p), the
     * same as the app's first-start default. SmartTube has no adaptive choice; its "default" entry turns the video off.
     */
    private static FormatItem autoFormat() {
        return com.liskovsoft.sharedutils.helpers.DeviceHelpers.isVP9ResolutionSupported(1080)
                ? FormatItem.VIDEO_FHD_VP9_60 : FormatItem.VIDEO_FHD_AVC_30;
    }

    private static boolean isAuto(Activity activity) {
        return activity.getSharedPreferences("phone_defaults", Activity.MODE_PRIVATE).getBoolean("quality_auto", true);
    }

    private static void setAuto(Activity activity, boolean auto) {
        activity.getSharedPreferences("phone_defaults", Activity.MODE_PRIVATE).edit().putBoolean("quality_auto", auto).apply();
    }

    /** The resolution the user picked, null for Auto */
    private static FormatItem chosenFormat(Activity activity, PhonePlaybackFragment player) {
        List<FormatItem> formats = player != null ? player.getVideoFormats() : null;
        if (formats == null || isAuto(activity)) {
            return null;
        }
        for (FormatItem format : formats) {
            if (format != null && format.isSelected()) {
                return format.isDefault() || format.getHeight() <= 0 ? null : format;
            }
        }
        return null;
    }

    private static void apply(Activity activity, PhonePlaybackFragment player, FormatItem format) {
        if (player == null || format == null) {
            return;
        }
        player.setFormat(format);
        PlayerData.instance(activity).setFormat(format); // also for the next videos, like SmartTube's list
        if (!player.containsMedia()) {
            player.reloadPlayback();
        }
    }

    /** 1080p60, 720p, ... */
    static String label(FormatItem format) {
        int fps = Math.round(format.getFrameRate());
        return format.getHeight() + "p" + (fps >= 48 ? String.valueOf(fps) : "");
    }

    private static String speedLabel(float speed) {
        if (Math.abs(speed - 1f) < 0.01f) {
            return "Normal";
        }
        String text = String.valueOf(speed);
        return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + "x";
    }

    /** A bottom sheet: a handle, an optional title, rows */
    private static final class Sheet {
        private final Activity mActivity;
        private final Dialog mDialog;
        private final LinearLayout mRows;
        private final float mDensity;

        Sheet(Activity activity, String title) {
            mActivity = activity;
            mDensity = activity.getResources().getDisplayMetrics().density;
            mDialog = new Dialog(activity);
            mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.phone_sheet_bg);
            root.setPadding(0, dp(10), 0, dp(12));

            View handle = new View(activity);
            handle.setBackgroundResource(R.drawable.phone_handle);
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.bottomMargin = dp(8);
            root.addView(handle, handleParams);

            if (!TextUtils.isEmpty(title)) {
                TextView heading = new TextView(activity);
                heading.setText(title);
                heading.setTextColor(color(R.color.phone_text));
                heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                heading.setPadding(dp(24), dp(10), dp(24), dp(14));
                root.addView(heading);
                View divider = new View(activity);
                divider.setBackgroundColor(color(R.color.phone_divider));
                root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
            }

            mRows = new LinearLayout(activity);
            mRows.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(mRows);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            mDialog.setContentView(root);
            Window window = mDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.BOTTOM);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setWindowAnimations(android.R.style.Animation_InputMethod); // slides up from the bottom
                WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = 0.5f;
                window.setAttributes(params);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        }

        /** icon 0: the tick column (ticked when selected); value: shown on the right */
        void addRow(int icon, String text, String value, boolean selected, Runnable onClick) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            row.setPadding(dp(24), 0, dp(24), 0);
            row.setBackgroundResource(R.drawable.phone_ripple);
            row.setOnClickListener(v -> onClick.run());

            ImageView image = new ImageView(mActivity);
            image.setImageResource(icon != 0 ? icon : R.drawable.ic_phone_check);
            image.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
            image.setVisibility(icon != 0 || selected ? View.VISIBLE : View.INVISIBLE);
            row.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView label = new TextView(mActivity);
            label.setText(text);
            label.setTextColor(color(R.color.phone_text));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.setMarginStart(dp(32));
            row.addView(label, labelParams);

            if (!TextUtils.isEmpty(value)) {
                TextView right = new TextView(mActivity);
                right.setText(value);
                right.setTextColor(color(R.color.phone_text_2));
                right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rightParams.setMarginStart(dp(12));
                row.addView(right, rightParams);
            }
            mRows.addView(row);
        }

        void show() {
            if (!mActivity.isFinishing() && !mActivity.isDestroyed()) {
                mDialog.show();
            }
        }

        void dismiss() {
            mDialog.dismiss();
        }

        private int color(int res) {
            return mActivity.getResources().getColor(res);
        }

        private int dp(float value) {
            return Math.round(value * mDensity);
        }
    }
}
