package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * The video's chapters, like the YouTube app's: a sheet from the bottom listing each chapter with its picture, name
 * and start time, the one playing marked; tap one to go there. Opened from the chapter's name by the time.
 */
final class PhoneChapterSheet {
    interface OnPick {
        void seekTo(long positionMs);
    }

    private PhoneChapterSheet() {
    }

    static void show(Activity activity, List<Video> chapters, long positionMs, OnPick onPick) {
        if (chapters == null || chapters.isEmpty() || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        PhoneSheetLayout root = new PhoneSheetLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.phone_sheet_bg);
        root.setPadding(0, dp(d, 8), 0, dp(d, 10));
        root.setClickable(true);
        root.setOnDismiss(dialog::dismiss);

        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.phone_handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(d, 40), dp(d, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(d, 8);
        root.addView(handle, handleParams);

        TextView heading = new TextView(activity);
        heading.setText(R.string.phone_chapters);
        heading.setTextColor(color(activity, R.color.phone_text));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heading.setTypeface(PhoneUi.medium(activity));
        heading.setPadding(dp(d, 20), dp(d, 6), dp(d, 20), dp(d, 10));
        root.addView(heading);
        View divider = new View(activity);
        divider.setBackgroundColor(color(activity, R.color.phone_divider));
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(d, 1))));

        // The chapter playing: the last one started
        int playing = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).startTimeMs <= positionMs) {
                playing = i;
            }
        }

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(d, 6), 0, dp(d, 6));
        View[] made = new View[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            Video chapter = chapters.get(i);
            made[i] = row(activity, d, chapter, i == playing, () -> {
                dialog.dismiss();
                onPick.seekTo(chapter.startTimeMs);
            });
            rows.addView(made[i]);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        int rowHeight = dp(d, 74);
        int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
        int height = Math.min(max, rowHeight * chapters.size() + dp(d, 12));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        int first = playing;
        // The chapter playing in view (a little below the top)
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, made[first].getTop() - rowHeight)));

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

    private static View row(Activity activity, float d, Video chapter, boolean playing, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 16), dp(d, 7), dp(d, 16), dp(d, 7));
        row.setMinimumHeight(dp(d, 74));
        if (playing) {
            // The one playing: a soft tint of the app's red behind it
            GradientDrawable lit = new GradientDrawable();
            lit.setColor((color(activity, R.color.phone_accent) & 0x00FFFFFF) | 0x26000000);
            row.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                    lit, activity.getDrawable(R.drawable.phone_ripple)}));
        } else {
            row.setBackgroundResource(R.drawable.phone_ripple);
        }
        row.setOnClickListener(v -> onClick.run());

        FrameLayout art = new FrameLayout(activity);
        art.setBackgroundColor(0x22888888);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * d);
            }
        });
        art.setClipToOutline(true);
        ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!TextUtils.isEmpty(chapter.cardImageUrl) && PhoneUi.isAlive(activity)) {
            Glide.with(activity).load(chapter.cardImageUrl).centerCrop().into(picture);
        }
        row.addView(art, new LinearLayout.LayoutParams(dp(d, 104), dp(d, 58)));

        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(chapter.title);
        title.setTextColor(color(activity, R.color.phone_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(playing ? PhoneUi.medium(activity) : PhoneUi.regular(activity));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        TextView time = new TextView(activity);
        time.setText(chapter.badge != null ? chapter.badge : "");
        time.setTextColor(color(activity, playing ? R.color.phone_accent : R.color.phone_text_2));
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        time.setPadding(0, dp(d, 3), 0, 0);
        words.addView(time);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.setMarginStart(dp(d, 14));
        row.addView(words, wordsParams);
        return row;
    }

    private static int color(Activity activity, int res) {
        return activity.getResources().getColor(res);
    }

    private static int dp(float d, float value) {
        return Math.round(value * d);
    }
}
