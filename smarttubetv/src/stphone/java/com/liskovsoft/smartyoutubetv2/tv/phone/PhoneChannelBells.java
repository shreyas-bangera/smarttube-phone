package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings › Channel notifications: every subscribed channel with its bell as a switch (the same bell as on the
 * channel's page), to turn many of them on or off in one place.
 */
final class PhoneChannelBells {
    private PhoneChannelBells() {
    }

    static void show(Activity activity) {
        if (!YouTubeServiceManager.instance().getSignInService().isSigned()) {
            android.widget.Toast.makeText(activity, R.string.phone_bells_sign_in, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        PhonePlayerSheet.Sheet sheet = new PhonePlayerSheet.Sheet(activity, activity.getString(R.string.phone_channel_notifications));
        TextView about = new TextView(activity);
        about.setText(R.string.phone_bells_about);
        about.setTextColor(activity.getResources().getColor(R.color.phone_text_2));
        about.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(13));
        about.setPadding(sheet.dp(24), sheet.dp(10), sheet.dp(24), sheet.dp(6));
        sheet.addView(about);
        ProgressBar loading = new ProgressBar(activity);
        LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(sheet.dp(32), sheet.dp(32));
        loadingParams.gravity = Gravity.CENTER_HORIZONTAL;
        loadingParams.setMargins(0, sheet.dp(24), 0, sheet.dp(24));
        loading.setLayoutParams(loadingParams);
        sheet.addView(loading);
        sheet.show();

        new Thread(() -> {
            List<MediaItem> channels = new ArrayList<>();
            try {
                MediaGroup group = YouTubeServiceManager.instance().getContentService().getSubscribedChannelsWithIds();
                if (group == null || group.getMediaItems() == null || group.getMediaItems().isEmpty()) {
                    group = YouTubeServiceManager.instance().getContentService().getSubscribedChannels();
                }
                if (group != null && group.getMediaItems() != null) {
                    for (MediaItem item : group.getMediaItems()) {
                        if (item != null && !TextUtils.isEmpty(item.getChannelId()) && !TextUtils.isEmpty(title(item))) {
                            channels.add(item);
                        }
                    }
                }
            } catch (Throwable e) {
                android.util.Log.e("PhoneNewVideos", "bells: " + e);
            }
            Collections.sort(channels, (a, b) -> title(a).compareToIgnoreCase(title(b)));
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                if (channels.isEmpty()) {
                    about.setText(R.string.phone_bells_none);
                    return;
                }
                for (MediaItem channel : channels) {
                    sheet.addView(row(activity, sheet, channel));
                }
            });
        }, "channel-bells").start();
    }

    private static String title(MediaItem item) {
        String title = item.getTitle();
        return title != null ? title : (item.getAuthor() != null ? item.getAuthor() : "");
    }

    /** Picture, name, switch */
    private static View row(Activity activity, PhonePlayerSheet.Sheet sheet, MediaItem channel) {
        String channelId = channel.getChannelId();
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(sheet.dp(56));
        row.setPadding(sheet.dp(24), sheet.dp(4), sheet.dp(16), sheet.dp(4));
        row.setBackgroundResource(R.drawable.phone_ripple);

        ImageView avatar = new ImageView(activity);
        String photo = channel.getCardImageUrl();
        if (PhoneUi.isAlive(activity) && !TextUtils.isEmpty(photo)) {
            com.bumptech.glide.Glide.with(activity).load(photo.startsWith("//") ? "https:" + photo : photo).circleCrop().into(avatar);
        }
        row.addView(avatar, new LinearLayout.LayoutParams(sheet.dp(36), sheet.dp(36)));

        TextView name = new TextView(activity);
        name.setText(title(channel));
        name.setTextColor(activity.getResources().getColor(R.color.phone_text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(15));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(sheet.dp(16));
        nameParams.setMarginEnd(sheet.dp(8));
        row.addView(name, nameParams);

        Switch bell = new Switch(activity);
        bell.setChecked(PhoneNewVideos.isBellOn(activity, channelId));
        bell.setContentDescription(activity.getString(R.string.phone_channel_notifications) + ": " + title(channel));
        bell.setOnCheckedChangeListener((button, on) -> PhoneNewVideos.setBell(activity, channelId, on));
        row.addView(bell);
        row.setOnClickListener(v -> bell.toggle());
        return row;
    }
}
