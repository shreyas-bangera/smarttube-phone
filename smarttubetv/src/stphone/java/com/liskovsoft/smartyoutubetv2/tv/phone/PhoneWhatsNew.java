package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Settings > What's new: what this version brings, in plain words (only this version, not older ones). A dot on the Settings row until the
 * page is read; once after an update, a card on Home with the newest ones.
 *
 * A new release: replace the release in {@link #RELEASES} with its own features and raise {@link #VERSION}.
 */
final class PhoneWhatsNew {
    /** The newest release below: raise it with each new one */
    static final int VERSION = 5;

    private static final String PREFS = "phone_whats_new";
    private static final String KEY_READ = "read";
    private static final String KEY_SHOWN = "shown";

    static final class Feature {
        final int icon;
        final String title;
        final String text;

        Feature(int icon, String title, String text) {
            this.icon = icon;
            this.title = title;
            this.text = text;
        }
    }

    static final class Release {
        final int version;
        final String name;
        final Feature[] features;

        Release(int version, String name, Feature... features) {
            this.version = version;
            this.name = name;
            this.features = features;
        }
    }

    static final Release[] RELEASES = {
            new Release(5, "This update",
                    new Feature(R.drawable.ic_phone_shorts, "Faster Shorts",
                            "The next Shorts get ready while you watch, so swiping starts them sooner."),
                    new Feature(R.drawable.ic_phone_tune, "Customize, all in one place",
                            "Settings › Customize gathers every way to change how StreamTube looks and works, each with a short explanation."),
                    new Feature(R.drawable.ic_phone_account, "Your profile at the top or bottom",
                            "Open You from the bottom bar, or move it to the top next to search. Pick one in Customize."),
                    new Feature(R.drawable.ic_phone_info, "What's new",
                            "This page. A dot in Settings tells you when there's something new to read."))
    };

    private PhoneWhatsNew() {
    }

    static boolean hasUnread(Context context) {
        return prefs(context).getInt(KEY_READ, 0) < VERSION;
    }

    static boolean isUnread(Context context, Release release) {
        return release.version > prefs(context).getInt(KEY_READ, 0);
    }

    static void markRead(Context context) {
        prefs(context).edit().putInt(KEY_READ, VERSION).putInt(KEY_SHOWN, VERSION).apply();
    }

    /**
     * Once after an update: a card with the newest features (not on a fresh install: everything is new then, and
     * the page is there to read).
     */
    static void showAfterUpdate(Activity activity) {
        SharedPreferences prefs = prefs(activity);
        if (prefs.getInt(KEY_SHOWN, 0) >= VERSION || activity.isFinishing()) {
            return;
        }
        prefs.edit().putInt(KEY_SHOWN, VERSION).apply();
        if (isFreshInstall(activity)) {
            prefs.edit().putInt(KEY_READ, VERSION).apply();
            return;
        }
        showCard(activity);
    }

    private static boolean isFreshInstall(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime == info.lastUpdateTime;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showCard(Activity activity) {
        Release newest = RELEASES[0];
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float d = activity.getResources().getDisplayMetrics().density;
        int text = activity.getResources().getColor(R.color.phone_text);
        int text2 = activity.getResources().getColor(R.color.phone_text_2);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.phone_sheet_bg);
        card.setPadding(px(d, 24), px(d, 24), px(d, 24), px(d, 16));

        TextView title = new TextView(activity);
        title.setText(R.string.phone_whats_new_title);
        title.setTextColor(text);
        title.setTextSize(20);
        title.setTypeface(PhoneUi.medium(activity));
        card.addView(title);

        TextView sub = new TextView(activity);
        sub.setText(R.string.phone_whats_new_updated);
        sub.setTextColor(text2);
        sub.setTextSize(14);
        sub.setPadding(0, px(d, 6), 0, px(d, 10));
        card.addView(sub);

        for (Feature feature : newest.features) {
            card.addView(featureRow(activity, feature, d, text, text2));
        }

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, px(d, 12), 0, 0);
        TextView all = button(activity, R.string.phone_whats_new_see_all, false, d);
        TextView ok = button(activity, R.string.phone_whats_new_got_it, true, d);
        all.setOnClickListener(v -> {
            dialog.dismiss();
            PhoneWhatsNewActivity.open(activity);
        });
        ok.setOnClickListener(v -> {
            markRead(activity);
            dialog.dismiss();
        });
        buttons.addView(all);
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(d, 40));
        okParams.leftMargin = px(d, 8);
        buttons.addView(ok, okParams);
        card.addView(buttons);

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    /** One feature: its icon, title and the line explaining it (the card and the page) */
    static View featureRow(Context context, Feature feature, float d, int text, int text2) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(d, 10), 0, px(d, 10));
        ImageView icon = new ImageView(context);
        icon.setImageResource(feature.icon);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(text));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(px(d, 24), px(d, 24));
        iconParams.rightMargin = px(d, 16);
        iconParams.topMargin = px(d, 2);
        row.addView(icon, iconParams);
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(feature.title);
        title.setTextColor(text);
        title.setTextSize(15);
        title.setTypeface(PhoneUi.medium(context));
        words.addView(title);
        TextView body = new TextView(context);
        body.setText(feature.text);
        body.setTextColor(text2);
        body.setTextSize(14);
        body.setLineSpacing(px(d, 2), 1f);
        body.setPadding(0, px(d, 3), 0, 0);
        words.addView(body);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static TextView button(Context context, int label, boolean primary, float d) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(PhoneUi.medium(context));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(px(d, 40));
        button.setPadding(px(d, 18), 0, px(d, 18), 0);
        if (primary) {
            button.setBackgroundResource(R.drawable.phone_button_bg);
            button.setTextColor(context.getResources().getColor(R.color.phone_bg));
        } else {
            button.setTextColor(context.getResources().getColor(R.color.phone_text));
        }
        return button;
    }

    private static int px(float density, int dp) {
        return Math.round(dp * density);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
