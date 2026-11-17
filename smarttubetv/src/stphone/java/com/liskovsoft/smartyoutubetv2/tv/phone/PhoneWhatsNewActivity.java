package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** Settings > What's new: every release's features, newest first; the ones not read yet marked "New" */
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
        for (PhoneWhatsNew.Release release : PhoneWhatsNew.RELEASES) {
            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(px(d, 20), px(d, 20), px(d, 20), px(d, 4));
            TextView name = new TextView(this);
            name.setText(release.name);
            name.setTextColor(text2);
            name.setTextSize(14);
            name.setTypeface(PhoneUi.medium(this));
            header.addView(name);
            if (PhoneWhatsNew.isUnread(this, release)) {
                TextView badge = new TextView(this);
                badge.setText(R.string.phone_whats_new_badge);
                badge.setTextColor(0xFFFFFFFF);
                badge.setTextSize(11);
                badge.setTypeface(PhoneUi.medium(this));
                badge.setBackgroundResource(R.drawable.phone_update_badge_bg);
                badge.setPadding(px(d, 8), px(d, 2), px(d, 8), px(d, 2));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.leftMargin = px(d, 10);
                header.addView(badge, params);
            }
            content.addView(header);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.phone_card_bg);
            card.setPadding(px(d, 16), px(d, 6), px(d, 16), px(d, 6));
            for (PhoneWhatsNew.Feature feature : release.features) {
                card.addView(PhoneWhatsNew.featureRow(this, feature, d, text, text2));
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.leftMargin = px(d, 12);
            cardParams.rightMargin = px(d, 12);
            content.addView(card, cardParams);
        }
        // Read now: the dot in Settings goes, "New" stays on these until the page is opened again
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
