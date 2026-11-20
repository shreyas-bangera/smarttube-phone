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
 * page is read; once after every update of the app, a card on Home with the newest ones.
 *
 * A new release: replace the release in {@link #RELEASES} with its own features and raise {@link #VERSION}.
 */
final class PhoneWhatsNew {
    /** The newest release below: raise it with each new one */
    static final int VERSION = 9;

    private static final String PREFS = "phone_whats_new";
    private static final String KEY_READ = "read";
    private static final String KEY_SHOWN = "shown";
    /** The app build the card was last shown for: it shows once after each update */
    private static final String KEY_SHOWN_BUILD = "shown_build";

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
            new Release(9, "This update",
                    new Feature(R.drawable.ic_phone_music_note, "A home screen widget",
                            "The song's artwork fills it edge to edge, fading into the song's own colour under its name and the buttons. It fits any size, and a tap opens the player. Long press your home screen, tap Widgets and find StreamTube."),
                    new Feature(R.drawable.ic_phone_playlist, "Move songs in Up next",
                            "Hold the handle on the right of a song and drag it up or down. The queue plays in your order."),
                    new Feature(R.drawable.ic_phone_cc, "Chapters on the seek bar",
                            "Videos with chapters show them as segments on the seek bar, with the chapter's name next to the time. Tap the name to see all the chapters and jump to any of them."),
                    new Feature(R.drawable.ic_phone_play, "See where you're going",
                            "While you drag the seek bar, or along the line at the bottom of a video, a picture of the video at that point shows above your finger, with its time.")),
            new Release(8, "Cover Flow",
                    new Feature(R.drawable.ic_phone_playlist, "Cover Flow",
                            "Turn your phone sideways in the music player to see your songs as a row of covers. Swipe through them and tap one to play it. In portrait, press and hold the artwork for the same view."),
                    new Feature(R.drawable.ic_phone_music_note, "A new animation for every song",
                            "Behind the music player, each song now gets its own animation: confetti, fireflies, soap bubbles, shooting stars, blossom petals, snow, lanterns, neon waves, a galaxy, butterflies, music notes, rainbow light and more."),
                    new Feature(R.drawable.ic_phone_playlist, "See what plays next",
                            "In the music player, tap Up next under the controls to see the songs coming after this one, in the order they'll play. Tap any of them to play it now."),
                    new Feature(R.drawable.ic_phone_headphones, "Lights that follow the song",
                            "A living background of the artwork's own colours drifts behind the music player, with beams of light, flowing ribbons and soft specks of light rising, soft lights circle the artwork and breathe while the song plays, and the play button glows in the song's colour."),
                    new Feature(R.drawable.ic_phone_collapse, "Swipe down to close menus",
                            "The player's menus (Settings, Quality, Speed, Captions and the rest) close with a swipe down, from the handle or the list, or with a tap outside them. They're smaller and neater too, and narrower in full screen."),
                    new Feature(R.drawable.ic_phone_music_note, "A cleaner music player",
                            "Music video thumbnails show whole on a soft blurred backdrop, song covers still fill the frame, and the lyric line has no box around it."),
                    new Feature(R.drawable.ic_phone_search, "Search always at hand",
                            "The top bar stays in place while you scroll Settings, Subscriptions and every other page. On Home it still tucks away as you scroll down."),
                    new Feature(R.drawable.ic_phone_brightness, "Brightness back to normal",
                            "Brightness set by swiping in full screen goes back to your phone's own when you leave full screen.")),
            new Release(7, "Earlier",
                    new Feature(R.drawable.ic_phone_speed, "Hold for 2x",
                            "Press and hold a video to play it at 2x speed while you hold, like in the YouTube app. Let go and it's back to normal. Works in Shorts and full screen too."),
                    new Feature(R.drawable.ic_phone_search, "Search in Settings",
                            "Tap the search icon on the Settings page and type what you're looking for. It finds any setting, also the ones inside Player, Subtitles, SponsorBlock and the other pages, and shows where each one is. Tap a result to go straight to it."),
                    new Feature(R.drawable.ic_phone_mode_shuffle, "Shuffle that really shuffles",
                            "In the music player, songs from Music or a search now play in a random order, without repeating the ones you just heard."),
                    new Feature(R.drawable.ic_phone_mode_repeat_one, "Repeat this song",
                            "With Repeat on, the song starts over when it ends, also from the music bar. Shuffle and Repeat show clearly when they're on: a soft circle behind the button and a dot under it."),
                    new Feature(R.drawable.ic_phone_update, "Updates at the bottom",
                            "What's new and Check for updates are now at the bottom of Settings, under Updates.")),
            new Release(6, "Music",
                    new Feature(R.drawable.ic_phone_music_note, "A new Music page",
                            "Music now sits right after Home. Swipe through featured music videos, tap Play or Shuffle, and browse playlists and the top charts."),
                    new Feature(R.drawable.ic_phone_headphones, "A real music player",
                            "Songs from Music open in a player made for listening: big artwork that you swipe for the next song, a background that glows in the song's colours, shuffle and repeat. Go small and it becomes a music bar at the bottom of the app."),
                    new Feature(R.drawable.ic_phone_cc, "Lyrics as you listen",
                            "The line being sung shows under the song's name, in the song's own language. Songs play as the song itself (no music video intro), so the lyrics stay in time. Tap the line for all the lyrics."),
                    new Feature(R.drawable.ic_phone_brightness, "A moving background",
                            "A quiet animation plays behind the music player and pauses with the song."),
                    new Feature(R.drawable.ic_phone_search, "Search for music",
                            "Turn on the music note next to the search box (or search from the Music page): songs and playlists you find open in the music player. Any video can switch to it too: tap Listen under the video."),
                    new Feature(R.drawable.ic_phone_library, "Better playlists",
                            "Playlists show their artwork, title and description at the top, with Play all, Shuffle and Share."),
                    new Feature(R.drawable.ic_phone_volume, "Song names in your car",
                            "Car and Bluetooth screens show the title and artist of what's playing, with its artwork, instead of \"Unavailable\"."),
                    new Feature(R.drawable.ic_phone_history, "A tidier You tab",
                            "History, Settings and the rest of You open as their own pages, and History's cards are smaller."))
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

    private static long buildNumber(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Once after every update of the app (each new build, once): a card with the newest features. Not on a fresh
     * install: everything is new then, and the page is there to read.
     */
    static void showAfterUpdate(Activity activity) {
        SharedPreferences prefs = prefs(activity);
        long build = buildNumber(activity);
        long shownFor = prefs.getLong(KEY_SHOWN_BUILD, 0);
        if (build <= 0 || shownFor >= build || activity.isFinishing()) {
            return;
        }
        prefs.edit().putLong(KEY_SHOWN_BUILD, build).putInt(KEY_SHOWN, VERSION).apply();
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

        // The first four (the card fits any screen); See all has the rest
        for (int i = 0; i < Math.min(4, newest.features.length); i++) {
            card.addView(featureRow(activity, newest.features[i], d, text, text2));
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
