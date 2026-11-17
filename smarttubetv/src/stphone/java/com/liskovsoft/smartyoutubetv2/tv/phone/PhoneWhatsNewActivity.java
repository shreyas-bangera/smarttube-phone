package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** Settings > What's new: this version's new features and fixes, in plain words, one card each */
public class PhoneWhatsNewActivity extends PhoneActivity {
    static void open(Activity activity) {
        activity.startActivity(new Intent(activity, PhoneWhatsNewActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_activity_pane);
        ((TextView) findViewById(R.id.phone_screen_title)).setText(R.string.phone_whats_new);
        applyInsets(findViewById(R.id.phone_root), findViewById(R.id.phone_top_bar), findViewById(R.id.phone_pane_scroll));
        findViewById(R.id.phone_back).setOnClickListener(v -> finish());

        LinearLayout content = findViewById(R.id.phone_pane_content);
        float d = getResources().getDisplayMetrics().density;
        int text = getResources().getColor(R.color.phone_text);
        int text2 = getResources().getColor(R.color.phone_text_2);
        PhoneWhatsNew.Release release = PhoneWhatsNew.RELEASES[0]; // this version only

        // A short hero: the app's mark, what this is, and which version
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(px(d, 24), px(d, 16), px(d, 24), px(d, 20));
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(R.drawable.ic_phone_brand);
        hero.addView(mark, new LinearLayout.LayoutParams(px(d, 56), px(d, 56)));
        TextView heading = new TextView(this);
        heading.setText(R.string.phone_whats_new_title);
        heading.setTextColor(text);
        heading.setTextSize(22);
        heading.setTypeface(PhoneUi.medium(this));
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, px(d, 14), 0, px(d, 4));
        hero.addView(heading);
        TextView version = new TextView(this);
        version.setText(getString(R.string.phone_whats_new_version, com.liskovsoft.smartyoutubetv2.tv.BuildConfig.VERSION_NAME
                .replace("-phone-", " · build ").replace("-beta-", " · beta ")));
        version.setTextColor(text2);
        version.setTextSize(13);
        version.setGravity(Gravity.CENTER);
        hero.addView(version);
        content.addView(hero);

        // One card per feature: icon in a soft circle, a title, and what it means for you
        for (PhoneWhatsNew.Feature feature : release.features) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundResource(R.drawable.phone_card_bg);
            card.setPadding(px(d, 16), px(d, 16), px(d, 16), px(d, 16));

            android.widget.FrameLayout badge = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0x33FF0033);
            badge.setBackground(circle);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(feature.icon);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFF4E6A));
            badge.addView(icon, new android.widget.FrameLayout.LayoutParams(px(d, 22), px(d, 22), Gravity.CENTER));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(px(d, 44), px(d, 44));
            badgeParams.rightMargin = px(d, 14);
            card.addView(badge, badgeParams);

            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(feature.title);
            title.setTextColor(text);
            title.setTextSize(16);
            title.setTypeface(PhoneUi.medium(this));
            words.addView(title);
            TextView body = new TextView(this);
            body.setText(feature.text);
            body.setTextColor(text2);
            body.setTextSize(14);
            body.setLineSpacing(px(d, 3), 1f);
            body.setPadding(0, px(d, 4), 0, 0);
            words.addView(body);
            card.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(px(d, 16), 0, px(d, 16), px(d, 12));
            content.addView(card, cardParams);
        }
        // Read now: the dot in Settings goes
        PhoneWhatsNew.markRead(this);
    }

    @Override
    protected boolean hostsMiniPlayer() {
        return false;
    }

    @Override
    protected boolean hasPendingActions() {
        return false;
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private static int px(float density, int dp) {
        return Math.round(dp * density);
    }
}
