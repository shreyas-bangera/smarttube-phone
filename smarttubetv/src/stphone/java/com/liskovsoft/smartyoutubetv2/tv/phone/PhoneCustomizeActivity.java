package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Settings > Customize: every tweak of the app on one page, in groups, each row with a line saying what it does.
 * New tweaks: a setting in {@link PhoneTweaks} and a row in {@link #build()}.
 */
public class PhoneCustomizeActivity extends PhoneActivity {
    private LinearLayout mContent;

    static void open(Activity activity) {
        activity.startActivity(new Intent(activity, PhoneCustomizeActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_activity_pane);
        ((TextView) findViewById(R.id.phone_screen_title)).setText(R.string.phone_settings_customize);
        mContent = findViewById(R.id.phone_pane_content);
        applyInsets(findViewById(R.id.phone_root), findViewById(R.id.phone_top_bar), findViewById(R.id.phone_pane_scroll));
        findViewById(R.id.phone_back).setOnClickListener(v -> finish());
        build();
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
    protected void onListScrollEnd(com.liskovsoft.smartyoutubetv2.common.app.models.data.Video lastVideo) {
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private void build() {
        mContent.removeAllViews();
        TextView intro = new TextView(this);
        intro.setText(R.string.phone_customize_intro);
        intro.setTextColor(getResources().getColor(R.color.phone_text_2));
        intro.setTextSize(14);
        intro.setPadding(dp(20), dp(4), dp(20), dp(8));
        mContent.addView(intro);

        header(R.string.phone_customize_layout);
        boolean youOnTop = PhoneTweaks.youOnTop(this);
        row(PhoneSetting.dropDown(getString(R.string.phone_tweak_you), getString(youOnTop
                ? R.string.phone_tweak_you_top : R.string.phone_tweak_you_bottom), R.drawable.ic_phone_account, this::pickYouPlace)
                .summary(getString(R.string.phone_tweak_you_summary)));

        if (PhoneTweaks.TAB_STYLES_SHOWN) {
            int tabs = PhoneTweaks.tabStyle(this);
            row(PhoneSetting.dropDown(getString(R.string.phone_tabs), getString(PhoneTabBar.nameRes(tabs)),
                    R.drawable.ic_phone_tab_home, this::pickTabStyle).summary(getString(R.string.phone_tabs_summary)));
        }

        header(R.string.phone_customize_player);
        row(PhoneSetting.toggle(getString(R.string.phone_tweak_prev_next), PhoneTweaks.prevNextButtons(this), R.drawable.ic_phone_next, () -> {
            PhoneTweaks.setPrevNextButtons(this, !PhoneTweaks.prevNextButtons(this));
            build();
        }).summary(getString(R.string.phone_tweak_prev_next_summary)));
        row(PhoneSetting.toggle(getString(R.string.phone_tweak_lock_lyrics), PhoneTweaks.lockScreenLyrics(this), R.drawable.ic_phone_cc, () -> {
            PhoneTweaks.setLockScreenLyrics(this, !PhoneTweaks.lockScreenLyrics(this));
            build();
        }).summary(getString(R.string.phone_tweak_lock_lyrics_summary)));
        boolean ambient = PhoneTweaks.ambient(this);
        row(PhoneSetting.toggle(getString(R.string.phone_tweak_ambient), ambient, R.drawable.ic_phone_brightness, () -> {
            PhoneTweaks.setAmbient(this, !PhoneTweaks.ambient(this));
            build();
        }).summary(getString(R.string.phone_tweak_ambient_summary)));
        if (ambient) {
            row(PhoneSetting.dropDown(getString(R.string.phone_tweak_glow), getString(PhoneTweaks.glowNameRes(PhoneTweaks.ambientGlow(this))),
                    R.drawable.ic_phone_brightness, this::pickGlow).summary(getString(R.string.phone_tweak_glow_summary)));
        }
    }

    private void pickGlow(View anchor) {
        int current = PhoneTweaks.ambientGlow(this);
        int[] glows = {PhoneTweaks.GLOW_SUBTLE, PhoneTweaks.GLOW_BALANCED, PhoneTweaks.GLOW_VIVID};
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor, Gravity.END);
        for (int i = 0; i < glows.length; i++) {
            menu.getMenu().add(0, i, i, PhoneTweaks.glowNameRes(glows[i])).setCheckable(true).setChecked(glows[i] == current);
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(item -> {
            int glow = glows[item.getItemId()];
            if (glow != current) {
                PhoneTweaks.setAmbientGlow(this, glow); // the player picks it up on its next frame
                build();
            }
            return true;
        });
        menu.show();
    }

    private void header(int title) {
        View view = LayoutInflater.from(this).inflate(R.layout.phone_item_setting_header, mContent, false);
        ((TextView) view.findViewById(R.id.phone_title)).setText(title);
        mContent.addView(view);
    }

    private void row(PhoneSetting setting) {
        View view = LayoutInflater.from(this).inflate(R.layout.phone_item_setting, mContent, false);
        FeedAdapter.bindSetting(view, setting);
        mContent.addView(view);
    }

    private void pickTabStyle(View anchor) {
        int current = PhoneTweaks.tabStyle(this);
        int[] styles = {PhoneTabBar.CLASSIC, PhoneTabBar.FLOATING, PhoneTabBar.GLASS};
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor, Gravity.END);
        for (int i = 0; i < styles.length; i++) {
            menu.getMenu().add(0, i, i, PhoneTabBar.nameRes(styles[i])).setCheckable(true).setChecked(styles[i] == current);
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(item -> {
            int style = styles[item.getItemId()];
            if (style != current) {
                PhoneTweaks.setTabStyle(this, style); // Home picks it up when it comes back to the front
                build();
            }
            return true;
        });
        menu.show();
    }

    private void pickYouPlace(View anchor) {
        boolean onTop = PhoneTweaks.youOnTop(this);
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add(0, 0, 0, R.string.phone_tweak_you_bottom).setCheckable(true).setChecked(!onTop);
        menu.getMenu().add(0, 1, 1, R.string.phone_tweak_you_top).setCheckable(true).setChecked(onTop);
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(item -> {
            boolean top = item.getItemId() == 1;
            if (top != onTop) {
                PhoneTweaks.setYouOnTop(this, top); // Home picks it up when it comes back to the front
                build();
            }
            return true;
        });
        menu.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
