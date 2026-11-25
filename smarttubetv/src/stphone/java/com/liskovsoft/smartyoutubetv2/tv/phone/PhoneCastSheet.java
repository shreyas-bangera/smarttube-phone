package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * The cast button's sheet: the TVs to play on (the ones linked before, and those found on the Wi-Fi as the search goes
 * on), and Link with TV code for a TV that isn't found. While casting: which TV, and Stop casting.
 */
final class PhoneCastSheet {
    interface Listener {
        void onTvPicked(PhoneCast.Tv tv);

        void onStopCasting();
    }

    private PhoneCastSheet() {
    }

    static void show(Activity activity, Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        float d = PhoneSheetLayout.screen().density;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        PhoneSheetLayout root = new PhoneSheetLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.phone_sheet_bg);
        root.setPadding(0, dp(d, 8), 0, dp(d, 12));
        root.setClickable(true);
        root.setOnDismiss(dialog::dismiss);

        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.phone_handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(d, 40), dp(d, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(d, 8);
        root.addView(handle, handleParams);

        TextView heading = text(activity, PhoneCast.isCasting() ? R.string.phone_cast_casting : R.string.phone_cast_title, 17, true);
        heading.setPadding(dp(d, 20), dp(d, 6), dp(d, 20), dp(d, 4));
        root.addView(heading);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        if (PhoneCast.isCasting()) {
            rows.addView(row(activity, d, R.drawable.ic_phone_cast_on, PhoneCast.tvName(),
                    activity.getString(R.string.phone_cast_playing_there), null));
            rows.addView(row(activity, d, R.drawable.ic_phone_close, activity.getString(R.string.phone_cast_stop), null, () -> {
                dialog.dismiss();
                listener.onStopCasting();
            }));
        } else {
            TextView hint = text(activity, R.string.phone_cast_hint, 13, false);
            hint.setTextColor(activity.getResources().getColor(R.color.phone_text_2));
            hint.setPadding(dp(d, 20), 0, dp(d, 20), dp(d, 8));
            root.addView(hint, 2);

            LinearLayout tvs = new LinearLayout(activity);
            tvs.setOrientation(LinearLayout.VERTICAL);
            rows.addView(tvs);
            TextView searching = text(activity, R.string.phone_cast_searching, 14, false);
            searching.setTextColor(activity.getResources().getColor(R.color.phone_text_2));
            searching.setPadding(dp(d, 20), dp(d, 10), dp(d, 20), dp(d, 10));
            rows.addView(searching);
            rows.addView(row(activity, d, R.drawable.ic_phone_cast, activity.getString(R.string.phone_cast_link),
                    activity.getString(R.string.phone_cast_link_hint), () -> {
                        dialog.dismiss();
                        askCode(activity, listener);
                    }));

            PhoneCast.find(activity, found -> {
                if (!dialog.isShowing()) {
                    return;
                }
                fill(activity, d, tvs, found, tv -> {
                    dialog.dismiss();
                    listener.onTvPicked(tv);
                });
            });
            // The search ends after a few seconds: the line says so (or that none were found)
            root.postDelayed(() -> {
                if (dialog.isShowing()) {
                    searching.setText(tvs.getChildCount() == 0 ? R.string.phone_cast_none : R.string.phone_cast_search_done);
                }
            }, 6_000);
        }

        FrameLayout frame = new FrameLayout(activity);
        frame.setOnClickListener(v -> dialog.dismiss());
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        dialog.setContentView(frame);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.5f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
    }

    interface Pick {
        void pick(PhoneCast.Tv tv);
    }

    private static void fill(Activity activity, float d, LinearLayout list, List<PhoneCast.Tv> tvs, Pick pick) {
        list.removeAllViews();
        for (PhoneCast.Tv tv : tvs) {
            String where = activity.getString(tv.onWifi() ? R.string.phone_cast_on_wifi : R.string.phone_cast_linked);
            list.addView(row(activity, d, R.drawable.ic_phone_tv, tv.name, where, () -> pick.pick(tv)));
        }
    }

    /** Link with TV code: the code the TV shows (YouTube or SmartTube: Settings > Link with TV code) */
    private static void askCode(Activity activity, Listener listener) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("123 456 789 012");
        float d = PhoneSheetLayout.screen().density;
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(dp(d, 22), dp(d, 8), dp(d, 22), 0);
        box.addView(input);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.phone_cast_link)
                .setMessage(R.string.phone_cast_link_message)
                .setView(box)
                .setPositiveButton(R.string.phone_cast_link_button, (dialog, which) -> {
                    String code = input.getText().toString();
                    PhoneCast.link(activity, code, (ok, result) -> {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        if (!ok) {
                            android.widget.Toast.makeText(activity, R.string.phone_cast_link_failed, android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        // Linked: play on it now
                        for (PhoneCast.Tv tv : PhoneCast.saved(activity)) {
                            if (tv.name.equals(result)) {
                                listener.onTvPicked(tv);
                                return;
                            }
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static View row(Activity activity, float d, int icon, String title, String subtitle, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(d, subtitle != null ? 62 : 52));
        row.setPadding(dp(d, 20), dp(d, 6), dp(d, 20), dp(d, 6));
        if (onClick != null) {
            row.setBackgroundResource(R.drawable.phone_ripple);
            row.setOnClickListener(v -> onClick.run());
        }
        ImageView image = new ImageView(activity);
        image.setImageResource(icon);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getResources().getColor(
                icon == R.drawable.ic_phone_cast_on ? R.color.phone_accent : R.color.phone_text)));
        row.addView(image, new LinearLayout.LayoutParams(dp(d, 24), dp(d, 24)));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextColor(activity.getResources().getColor(R.color.phone_text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(15));
        words.addView(name);
        if (subtitle != null) {
            TextView sub = new TextView(activity);
            sub.setText(subtitle);
            sub.setTextColor(activity.getResources().getColor(R.color.phone_text_2));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(13));
            words.addView(sub);
        }
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.setMarginStart(dp(d, 24));
        row.addView(words, wordsParams);
        return row;
    }

    private static TextView text(Activity activity, int res, int sp, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(res);
        view.setTextColor(activity.getResources().getColor(R.color.phone_text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(sp));
        view.setTypeface(medium ? PhoneUi.medium(activity) : PhoneUi.regular(activity));
        return view;
    }

    private static int dp(float d, float value) {
        return Math.round(value * d);
    }
}
