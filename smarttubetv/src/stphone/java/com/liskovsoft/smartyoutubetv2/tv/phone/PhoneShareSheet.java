package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The YouTube app's share sheet: "Share" with a switch to start the link where the video is now, the apps that take
 * a link (the usual messengers first), then Copy link and More (Android's own share list).
 */
final class PhoneShareSheet {
    /** These first, in this order, when they're installed */
    private static final List<String> FIRST = Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
            "com.facebook.orca", "com.facebook.katana", "com.instagram.android", "com.google.android.gm",
            "com.twitter.android", "com.snapchat.android", "com.discord", "com.reddit.frontpage", "com.linkedin.android",
            "com.samsung.android.messaging", "com.android.mms");
    private static final int MAX_APPS = 12;

    private PhoneShareSheet() {
    }

    /** startMs: where the video is now (the switch offers it from 1 second on), -1 for none (live) */
    static void showVideo(Activity activity, String title, String videoId, long startMs) {
        String link = "https://youtu.be/" + videoId;
        int startSec = startMs >= 1000 ? (int) (startMs / 1000) : 0;
        show(activity, title, link, startSec > 0 ? link + "?t=" + startSec : null, startSec);
    }

    static void showPlaylist(Activity activity, String title, String playlistId) {
        show(activity, title, "https://www.youtube.com/playlist?list=" + playlistId, null, 0);
    }

    /** Any other link (a channel's) */
    static void showLink(Activity activity, String link) {
        show(activity, null, link, null, 0);
    }

    /**
     * The app's other Share buttons (a video's menu, a channel's): this sheet, on the screen in front once that
     * button's menu has closed
     */
    static void install() {
        com.liskovsoft.smartyoutubetv2.common.utils.Utils.setShareHandler((context, videoId, posSec, playlistId, url) -> {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Activity front = context instanceof Activity && !((Activity) context).isFinishing() ? (Activity) context
                        : PhoneApplication.frontActivity();
                if (front == null || front.isFinishing() || front.isDestroyed()) {
                    Intent send = Intent.createChooser(sendIntent(null, String.valueOf(url)), null)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(send);
                } else if (videoId != null) {
                    showVideo(front, null, videoId, posSec * 1000L);
                } else if (playlistId != null) {
                    showPlaylist(front, null, playlistId);
                } else {
                    showLink(front, String.valueOf(url));
                }
            }, 300);
            return true;
        });
    }

    private static void show(Activity activity, String title, String link, String linkAtTime, int startSec) {
        PhonePlayerSheet.Sheet sheet = new PhonePlayerSheet.Sheet(activity, null);
        String[] chosen = {link};

        // Share ............ 0:42 [switch]
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(sheet.dp(24), sheet.dp(4), sheet.dp(18), sheet.dp(12));
        TextView heading = new TextView(activity);
        heading.setText(R.string.phone_share);
        heading.setTextColor(color(activity, R.color.phone_text));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(20));
        heading.setTypeface(PhoneUi.medium(activity));
        head.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (linkAtTime != null) {
            TextView time = new TextView(activity);
            time.setText(clock(startSec));
            time.setTextColor(color(activity, R.color.phone_text));
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(15));
            head.addView(time);
            Switch atTime = new Switch(activity);
            atTime.setContentDescription(activity.getString(R.string.phone_share_start_at, clock(startSec)));
            atTime.setOnCheckedChangeListener((button, on) -> chosen[0] = on ? linkAtTime : link);
            LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            switchParams.setMarginStart(sheet.dp(10));
            head.addView(atTime, switchParams);
            time.setOnClickListener(v -> atTime.toggle());
        }
        sheet.addView(head);

        // The apps
        List<ResolveInfo> apps = shareApps(activity);
        if (!apps.isEmpty()) {
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout row = new LinearLayout(activity);
            row.setPadding(sheet.dp(10), 0, sheet.dp(10), sheet.dp(14));
            PackageManager pm = activity.getPackageManager();
            for (ResolveInfo app : apps) {
                row.addView(appButton(activity, sheet, pm, app, () -> {
                    sheet.dismiss();
                    Intent send = sendIntent(title, chosen[0])
                            .setComponent(new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
                    try {
                        activity.startActivity(send);
                    } catch (Exception e) {
                        activity.startActivity(Intent.createChooser(sendIntent(title, chosen[0]), activity.getString(R.string.phone_share)));
                    }
                }));
            }
            scroll.addView(row);
            sheet.addView(scroll);
        }

        View divider = new View(activity);
        divider.setBackgroundColor(color(activity, R.color.phone_divider));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, sheet.dp(1)));
        dividerParams.bottomMargin = sheet.dp(6);
        divider.setLayoutParams(dividerParams);
        sheet.addView(divider);

        sheet.addRow(R.drawable.ic_phone_link, activity.getString(R.string.phone_share_copy), null, false, () -> {
            sheet.dismiss();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(title != null ? title : "YouTube", chosen[0]));
                if (Build.VERSION.SDK_INT < 33) { // Android 13+ shows its own "copied" note
                    Toast.makeText(activity, R.string.phone_share_copied, Toast.LENGTH_SHORT).show();
                }
            }
        });
        sheet.addRow(R.drawable.ic_phone_more, activity.getString(R.string.phone_share_more), null, false, () -> {
            sheet.dismiss();
            activity.startActivity(Intent.createChooser(sendIntent(title, chosen[0]), activity.getString(R.string.phone_share)));
        });
        sheet.show();
    }

    private static Intent sendIntent(String title, String link) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link);
        if (!TextUtils.isEmpty(title)) {
            send.putExtra(Intent.EXTRA_SUBJECT, title);
        }
        return send;
    }

    /** The apps that take a link: the usual messengers first, then the rest by name; never this app itself */
    private static List<ResolveInfo> shareApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> all;
        try {
            all = new ArrayList<>(pm.queryIntentActivities(new Intent(Intent.ACTION_SEND).setType("text/plain"), 0));
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<ResolveInfo> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo info : all) {
            String pkg = info.activityInfo != null ? info.activityInfo.packageName : null;
            if (pkg == null || pkg.equals(context.getPackageName()) || seen.contains(pkg)) {
                continue; // one entry per app (WhatsApp has "chat" and "status")
            }
            seen.add(pkg);
            apps.add(info);
        }
        Collections.sort(apps, (a, b) -> {
            int ia = FIRST.indexOf(a.activityInfo.packageName);
            int ib = FIRST.indexOf(b.activityInfo.packageName);
            if (ia != ib) {
                return (ia < 0 ? Integer.MAX_VALUE : ia) < (ib < 0 ? Integer.MAX_VALUE : ib) ? -1 : 1;
            }
            return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
        });
        return apps.size() > MAX_APPS ? apps.subList(0, MAX_APPS) : apps;
    }

    private static View appButton(Activity activity, PhonePlayerSheet.Sheet sheet, PackageManager pm, ResolveInfo app, Runnable onClick) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(sheet.dp(6), sheet.dp(4), sheet.dp(6), sheet.dp(4));
        column.setBackgroundResource(R.drawable.phone_ripple);
        column.setOnClickListener(v -> onClick.run());
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(app.loadIcon(pm));
        column.addView(icon, new LinearLayout.LayoutParams(sheet.dp(56), sheet.dp(56)));
        TextView label = new TextView(activity);
        label.setText(app.loadLabel(pm));
        label.setTextColor(color(activity, R.color.phone_text));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(12.5f));
        label.setSingleLine(true);
        label.setEllipsize(TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(sheet.dp(76), ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = sheet.dp(8);
        column.addView(label, labelParams);
        return column;
    }

    private static String clock(int seconds) {
        int h = seconds / 3600;
        int m = seconds / 60 % 60;
        int s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    private static int color(Context context, int res) {
        return context.getResources().getColor(res);
    }
}
