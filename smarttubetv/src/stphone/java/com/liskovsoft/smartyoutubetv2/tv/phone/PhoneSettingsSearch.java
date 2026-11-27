package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search in Settings: the rows of the Settings page, and what's inside SmartTube's settings pages (Player, Interface,
 * General, Subtitles, Search, SponsorBlock, DeArrow): their groups ("Video quality") and options ("Remember speed").
 * Those pages are read by building their dialogs without showing them (AppDialogPresenter's capture). A result opens
 * its page, straight on the group it's in.
 */
final class PhoneSettingsSearch {
    /** SmartTube's pages that are read (the others act when opened: accounts, backup) */
    private static final int[] DEEP = {R.string.settings_player, R.string.settings_main_ui, R.string.settings_general,
            R.string.subtitle_category_title, R.string.settings_search, R.string.content_block_provider,
            R.string.dearrow_provider, R.string.settings_language_country};

    private static final class Entry {
        final PhoneSetting row;
        final String words;

        Entry(PhoneSetting row, String words) {
            this.row = row;
            this.words = words.toLowerCase(Locale.getDefault());
        }
    }

    private final List<Entry> mEntries = new ArrayList<>();
    private final Context mContext;

    /** Everything that can be found, from the Settings page's rows (built once per search) */
    PhoneSettingsSearch(Context context, List<SettingsItem> rows) {
        mContext = context;
        Set<String> deep = new HashSet<>();
        for (int res : DEEP) {
            deep.add(context.getString(res));
        }
        Set<String> seen = new HashSet<>();
        String section = null;
        for (SettingsItem item : rows) {
            if (item instanceof PhoneSetting && ((PhoneSetting) item).header) {
                section = item.title;
                continue;
            }
            if (TextUtils.isEmpty(item.title)) {
                continue;
            }
            PhoneSetting found = copy(item, section);
            String summary = item instanceof PhoneSetting ? ((PhoneSetting) item).summary : null;
            add(seen, found, item.title + " " + (summary != null ? summary : "") + " " + (section != null ? section : ""));
            if (deep.contains(item.title) && item.onClick != null) {
                readPage(seen, item);
            }
        }
    }

    /** The rows matching every word of the query, those whose title starts with it first */
    List<SettingsItem> find(String query) {
        String[] words = query.trim().toLowerCase(Locale.getDefault()).split("\\s+");
        List<SettingsItem> first = new ArrayList<>();
        List<SettingsItem> rest = new ArrayList<>();
        for (Entry entry : mEntries) {
            boolean all = true;
            for (String word : words) {
                if (!word.isEmpty() && !matches(entry.words, word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                String title = entry.row.title.toLowerCase(Locale.getDefault());
                (title.startsWith(words[0]) || title.contains(" " + words[0]) ? first : rest).add(entry.row);
            }
        }
        first.addAll(rest);
        return first;
    }

    /** Everyday words for SmartTube's: "size" finds "Subtitle scale", "captions" finds Subtitles... */
    private static final String[][] SAME = {
            {"size", "scale"}, {"font", "text", "style"}, {"colour", "color"}, {"captions", "subtitle", "cc"},
            {"cc", "subtitle"}, {"caption", "subtitle"}, {"dark", "theme"}, {"light", "theme"},
            {"skip", "sponsorblock", "segment"}, {"ads", "sponsorblock", "segment"}, {"sponsor", "sponsorblock"},
            {"resolution", "quality", "preset"}, {"hd", "quality", "preset"}, {"1080p", "preset"}, {"4k", "preset"},
            {"autoplay", "playback mode"}, {"loop", "repeat", "playback mode"}, {"repeat", "playback mode"},
            {"background", "background play"}, {"pip", "picture"}, {"language", "locale", "country"},
            {"thumbnail", "dearrow"}, {"clickbait", "dearrow"}, {"volume", "audio"}, {"sound", "audio"}};

    private static boolean matches(String words, String word) {
        if (words.contains(word)) {
            return true;
        }
        for (String[] same : SAME) {
            if (same[0].equals(word)) {
                for (int i = 1; i < same.length; i++) {
                    if (words.contains(same[i])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void add(Set<String> seen, PhoneSetting row, String words) {
        String key = row.title + "|" + row.summary;
        if (seen.add(key)) {
            mEntries.add(new Entry(row, words));
        }
    }

    /** A row of the Settings page as a result: the same kind of row, the group it's in under its title */
    private static PhoneSetting copy(SettingsItem item, String section) {
        PhoneSetting found;
        if (item instanceof PhoneSetting) {
            PhoneSetting setting = (PhoneSetting) item;
            if (setting.onPick != null) {
                found = PhoneSetting.dropDown(setting.title, setting.value, setting.imageResId, setting.onPick);
            } else if (setting.checked != null) {
                found = PhoneSetting.toggle(setting.title, setting.checked, setting.imageResId, setting.onClick);
            } else if (setting.value != null) {
                found = PhoneSetting.value(setting.title, setting.value, setting.imageResId, setting.onClick);
            } else {
                found = PhoneSetting.row(setting.title, setting.imageResId, setting.onClick);
            }
            found.badge(setting.badge);
        } else {
            found = PhoneSetting.row(item.title, item.imageResId, item.onClick);
        }
        return found.summary(section);
    }

    /** What one of SmartTube's pages holds: its groups and their options */
    private void readPage(Set<String> seen, SettingsItem page) {
        List<OptionCategory> categories;
        AppDialogPresenter.startCapture();
        try {
            page.onClick.run();
        } catch (RuntimeException e) {
            android.util.Log.d("PhoneSettings", "search can't read " + page.title + ": " + e);
        } finally {
            categories = AppDialogPresenter.stopCapture();
        }
        for (OptionCategory category : categories) {
            String group = category.title != null ? category.title.toString() : null;
            if (group != null && PhoneTvOnly.isCategory(mContext, group)) {
                continue;
            }
            if (group != null) {
                add(seen, result(group, page.title, page, group), group + " " + page.title);
            }
            if (category.options == null) {
                continue;
            }
            for (OptionItem option : category.options) {
                CharSequence title = option != null ? option.getTitle() : null;
                if (TextUtils.isEmpty(title) || PhoneTvOnly.isOption(mContext, title) || PhoneTvOnly.isCategory(mContext, title)) {
                    continue;
                }
                String path = group != null ? page.title + " › " + group : page.title;
                CharSequence description = option.getDescription();
                add(seen, result(title.toString(), path, page, group),
                        title + " " + (description != null ? description : "") + " " + path);
            }
        }
    }

    /** Opens the page, straight on the group (see PhoneDialogActivity.focusCategory) */
    private static PhoneSetting result(String title, String path, SettingsItem page, String group) {
        return PhoneSetting.row(title, page.imageResId, () -> {
            PhoneDialogActivity.focusCategory(group);
            page.onClick.run();
        }).summary(path);
    }
}
