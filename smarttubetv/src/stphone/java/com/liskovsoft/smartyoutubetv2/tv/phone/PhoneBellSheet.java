package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The bell of a subscribed channel, as the YouTube app: "Notifications" with All, Personalised and None (the one
 * chosen ticked), then Unsubscribe. Its icon is the button's too ({@link #icon}).
 */
final class PhoneBellSheet {
    private PhoneBellSheet() {
    }

    /** The bell's picture for a channel's current choice */
    static int icon(int bell) {
        switch (bell) {
            case PhoneNewVideos.BELL_ALL:
                return R.drawable.ic_phone_bell_all;
            case PhoneNewVideos.BELL_NONE:
                return R.drawable.ic_phone_bell_off;
            default:
                return R.drawable.ic_phone_bell_on;
        }
    }

    /** onChanged: the choice changed (the button's bell follows); onUnsubscribe: Unsubscribe was tapped */
    static void show(Activity activity, String channelId, Runnable onChanged, Runnable onUnsubscribe) {
        if (channelId == null) {
            return;
        }
        int current = PhoneNewVideos.bell(activity, channelId);
        PhonePlayerSheet.Sheet sheet = new PhonePlayerSheet.Sheet(activity, null);

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(sheet.dp(24), sheet.dp(8), sheet.dp(24), sheet.dp(10));
        TextView title = new TextView(activity);
        title.setText(R.string.phone_bell_title);
        title.setTextColor(color(activity, R.color.phone_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(20));
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        head.addView(title);
        TextView about = new TextView(activity);
        about.setText(R.string.phone_bell_about);
        about.setTextColor(color(activity, R.color.phone_text_2));
        about.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(14));
        about.setPadding(0, sheet.dp(4), 0, 0);
        head.addView(about);
        sheet.addView(head);

        int[] levels = {PhoneNewVideos.BELL_ALL, PhoneNewVideos.BELL_PERSONALIZED, PhoneNewVideos.BELL_NONE};
        int[] names = {R.string.phone_bell_all, R.string.phone_bell_personalized, R.string.phone_bell_none};
        int[] abouts = {R.string.phone_bell_all_about, R.string.phone_bell_personalized_about, R.string.phone_bell_none_about};
        for (int i = 0; i < levels.length; i++) {
            int level = levels[i];
            sheet.addView(row(activity, sheet, icon(level), activity.getString(names[i]), activity.getString(abouts[i]), level == current, () -> {
                sheet.dismiss();
                if (level != current) {
                    PhoneNewVideos.setBell(activity, channelId, level);
                    if (onChanged != null) {
                        onChanged.run();
                    }
                }
            }));
        }
        sheet.addView(row(activity, sheet, R.drawable.ic_phone_unsubscribe, activity.getString(R.string.phone_unsubscribe), null, false, () -> {
            sheet.dismiss();
            if (onUnsubscribe != null) {
                onUnsubscribe.run();
            }
        }));
        sheet.show();
    }

    /** Icon, name, and a tick on the right when it's the choice */
    private static View row(Activity activity, PhonePlayerSheet.Sheet sheet, int icon, String text, String about, boolean ticked,
            Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(sheet.dp(56));
        row.setPadding(sheet.dp(24), 0, sheet.dp(24), 0);
        row.setBackgroundResource(R.drawable.phone_ripple);
        row.setOnClickListener(v -> onClick.run());
        ColorStateList tint = ColorStateList.valueOf(color(activity, R.color.phone_text));

        ImageView image = new ImageView(activity);
        image.setImageResource(icon);
        image.setImageTintList(tint);
        row.addView(image, new LinearLayout.LayoutParams(sheet.dp(26), sheet.dp(26)));

        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(color(activity, R.color.phone_text));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(17));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(sheet.dp(32));
        if (about == null) {
            row.addView(label, labelParams);
        } else {
            // what the choice does, under its name
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(0, sheet.dp(8), sheet.dp(8), sheet.dp(8));
            texts.addView(label);
            TextView line = new TextView(activity);
            line.setText(about);
            line.setTextColor(color(activity, R.color.phone_text_2));
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(13));
            texts.addView(line);
            row.addView(texts, labelParams);
        }

        if (ticked) {
            ImageView tick = new ImageView(activity);
            tick.setImageResource(R.drawable.ic_phone_check);
            tick.setImageTintList(tint);
            row.addView(tick, new LinearLayout.LayoutParams(sheet.dp(26), sheet.dp(26)));
            row.setSelected(true);
        }
        return row;
    }

    private static int color(Activity activity, int res) {
        return activity.getResources().getColor(res);
    }
}
