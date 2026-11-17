package com.liskovsoft.smartyoutubetv2.tv.phone;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;

/**
 * A row of the phone's Settings list: SmartTube's {@link SettingsItem} (icon, title, action) plus what a phone
 * settings screen shows on the right: the current value ("Dark") or a switch. Also the heading of a group of rows.
 */
final class PhoneSetting extends SettingsItem {
    /** Shown on the right, e.g. the chosen theme; null for none */
    final String value;
    /** A switch in this state; null for a row without a switch */
    final Boolean checked;
    final boolean header;
    /** Rows that open a drop-down under themselves: called with the row's value view, in place of onClick */
    OnPick onPick;
    /** A short line under the title saying what the setting does; null for none */
    String summary;
    /** A dot before the arrow: something new to see behind this row */
    boolean badge;

    PhoneSetting badge(boolean show) {
        badge = show;
        return this;
    }

    /** Adds the line under the title */
    PhoneSetting summary(String text) {
        summary = text;
        return this;
    }

    interface OnPick {
        void pick(android.view.View anchor);
    }

    private PhoneSetting(String title, Runnable onClick, int icon, String value, Boolean checked, boolean header) {
        super(title, onClick, icon);
        this.value = value;
        this.checked = checked;
        this.header = header;
    }

    static PhoneSetting header(String title) {
        return new PhoneSetting(title, null, -1, null, null, true);
    }

    static PhoneSetting row(String title, int icon, Runnable onClick) {
        return new PhoneSetting(title, onClick, icon, null, null, false);
    }

    static PhoneSetting value(String title, String value, int icon, Runnable onClick) {
        return new PhoneSetting(title, onClick, icon, value, null, false);
    }

    /** A row whose value is picked from a drop-down list that opens under it */
    static PhoneSetting dropDown(String title, String value, int icon, OnPick onPick) {
        PhoneSetting setting = new PhoneSetting(title, null, icon, value, null, false);
        setting.onPick = onPick;
        return setting;
    }

    static PhoneSetting toggle(String title, boolean checked, int icon, Runnable onClick) {
        return new PhoneSetting(title, onClick, icon, null, checked, false);
    }
}
