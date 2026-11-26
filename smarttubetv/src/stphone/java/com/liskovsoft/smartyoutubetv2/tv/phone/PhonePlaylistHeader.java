package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The top of a playlist page, like YouTube's: the artwork's colours blurred behind everything, the big square artwork,
 * title, owner, "Playlist · 50 videos", the description, then Play all, Shuffle and Share.
 */
final class PhonePlaylistHeader {
    interface Actions {
        void playAll();

        void shuffle();
    }

    private final FrameLayout mRoot;
    private final ImageView mBackdrop;
    private final ImageView mArt;
    private final TextView mTitle;
    private final TextView mOwner;
    private final TextView mMeta;
    private final TextView mDescription;
    private Video mPlaylist;
    private final ImageView mSave;
    /** Saved in the account's library (the You tab's Playlists) */
    private boolean mSaved;
    private boolean mSaveBusy;
    private io.reactivex.disposables.Disposable mSavedCheck;

    PhonePlaylistHeader(Context context, Actions actions) {
        mRoot = new FrameLayout(context);

        mBackdrop = new ImageView(context);
        mBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (Build.VERSION.SDK_INT >= 31) {
            mBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(context, 60), dp(context, 60),
                    android.graphics.Shader.TileMode.CLAMP));
        }
        mBackdrop.setAlpha(0.85f);
        mRoot.addView(mBackdrop, match());
        View fade = new View(context);
        int bg = context.getResources().getColor(R.color.phone_bg);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {(bg & 0x00FFFFFF) | 0x66000000, (bg & 0x00FFFFFF) | 0xAA000000, bg}));
        mRoot.addView(fade, match());

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 20));
        mRoot.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout artFrame = new FrameLayout(context);
        artFrame.setElevation(dp(context, 12));
        int radius = dp(context, 16);
        artFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        artFrame.setClipToOutline(true);
        mArt = new ImageView(context);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(context.getResources().getColor(R.color.phone_surface_2));
        artFrame.addView(mArt, match());
        int artSize = Math.min(dp(context, 260), context.getResources().getDisplayMetrics().widthPixels - dp(context, 100));
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(artSize, artSize);
        artParams.gravity = Gravity.CENTER_HORIZONTAL;
        artParams.topMargin = dp(context, 8);
        column.addView(artFrame, artParams);

        int text = context.getResources().getColor(R.color.phone_text);
        int text2 = context.getResources().getColor(R.color.phone_text_2);
        mTitle = text(context, 28, text, true);
        mTitle.setPadding(0, dp(context, 22), 0, 0);
        column.addView(mTitle);
        mOwner = text(context, 15, text, true);
        mOwner.setPadding(0, dp(context, 6), 0, 0);
        column.addView(mOwner);
        mMeta = text(context, 14, text2, false);
        mMeta.setPadding(0, dp(context, 6), 0, 0);
        column.addView(mMeta);
        mDescription = text(context, 14, text2, false);
        mDescription.setMaxLines(3);
        mDescription.setEllipsize(TextUtils.TruncateAt.END);
        mDescription.setPadding(0, dp(context, 10), 0, 0);
        mDescription.setOnClickListener(v -> mDescription.setMaxLines(mDescription.getMaxLines() == 3 ? 40 : 3));
        column.addView(mDescription);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(context, 18), 0, 0);
        TextView play = new TextView(context);
        play.setText(R.string.phone_music_play_all);
        play.setGravity(Gravity.CENTER);
        play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        play.setTypeface(PhoneUi.medium(context));
        int bgColor = context.getResources().getColor(R.color.phone_bg);
        play.setTextColor(bgColor);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(context, 24));
        pill.setColor(text);
        play.setBackground(pill);
        android.graphics.drawable.Drawable icon = context.getResources().getDrawable(R.drawable.ic_phone_play).mutate();
        icon.setTint(bgColor);
        icon.setBounds(0, 0, dp(context, 24), dp(context, 24));
        play.setCompoundDrawablesRelative(icon, null, null, null);
        play.setCompoundDrawablePadding(dp(context, 6));
        play.setPadding(dp(context, 20), 0, dp(context, 24), 0);
        play.setOnClickListener(v -> actions.playAll());
        buttons.addView(play, new LinearLayout.LayoutParams(0, dp(context, 48), 1f));
        mSave = (ImageView) circle(context, R.drawable.ic_phone_library_add, v -> toggleSave(context));
        mSave.setContentDescription(context.getString(R.string.phone_playlist_save));
        mSave.setVisibility(View.GONE); // until it's known the playlist can be saved
        buttons.addView(mSave);
        buttons.addView(circle(context, R.drawable.ic_phone_mode_shuffle, v -> actions.shuffle()));
        buttons.addView(circle(context, R.drawable.ic_phone_share, v -> share(context)));
        column.addView(buttons);
    }

    View view() {
        return mRoot;
    }

    void bind(Video playlist, int loadedCount) {
        if (playlist == null) {
            return;
        }
        Context context = mRoot.getContext();
        boolean changed = mPlaylist != playlist;
        mPlaylist = playlist;
        mTitle.setText(playlist.getTitle());
        String owner = playlist.getAuthor();
        mOwner.setText(owner);
        mOwner.setVisibility(TextUtils.isEmpty(owner) ? View.GONE : View.VISIBLE);
        String count = !TextUtils.isEmpty(playlist.badge) ? playlist.badge : null;
        mMeta.setText(count != null ? context.getString(R.string.phone_music_videos, count) : context.getString(R.string.phone_music_playlist));
        String description = playlist.description;
        mDescription.setText(description);
        mDescription.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);
        if (changed) {
            checkSaved(context);
        }
        if (changed && PhoneUi.isAlive(context)) {
            String url = playlist.getCardImageUrl();
            Glide.with(context).load(url).centerCrop().transition(DrawableTransitionOptions.withCrossFade(200)).into(mArt);
            // Behind it, the same picture blurred (Android 12+), or just tiny and stretched (soft) on older phones
            Glide.with(context).load(url).override(Build.VERSION.SDK_INT >= 31 ? 200 : 24).centerCrop().into(mBackdrop);
        }
    }

    // region Save to library

    /**
     * Only a playlist someone else made can be saved (the user's own are in the library already; a mix can't be),
     * and only with an account: saved playlists are the account's (the You tab's Playlists, as in the YouTube app).
     */
    private boolean canSave() {
        String id = mPlaylist != null ? mPlaylist.playlistId : null;
        if (TextUtils.isEmpty(id) || id.length() < 5 || id.startsWith("RD")
                || "WL".equals(id) || "LL".equals(id)) {
            return false;
        }
        String author = mPlaylist.getAuthor();
        com.liskovsoft.mediaserviceinterfaces.oauth.Account account = YouTubeServiceManager.instance().getSignInService().getSelectedAccount();
        return account == null || author == null || !author.equals(account.getName());
    }

    /** Shown at once (signed out it asks to sign in); saved or not comes from the account's playlists */
    private void checkSaved(Context context) {
        RxHelper.disposeActions(mSavedCheck);
        mSaved = false;
        boolean can = canSave();
        mSave.setVisibility(can ? View.VISIBLE : View.GONE);
        showSaved(context);
        if (!can || !YouTubeServiceManager.instance().getSignInService().isSigned()) {
            return;
        }
        String id = mPlaylist.playlistId;
        mSavedCheck = RxHelper.execute(YouTubeServiceManager.instance().getContentService().getPlaylistsObserve(), group -> {
            if (mPlaylist == null || !id.equals(mPlaylist.playlistId)) {
                return;
            }
            if (isIn(group, id)) {
                mSaved = true;
                showSaved(context);
            }
        }, error -> { });
    }

    private static boolean isIn(MediaGroup group, String playlistId) {
        if (group == null || group.getMediaItems() == null) {
            return false;
        }
        for (MediaItem item : group.getMediaItems()) {
            if (playlistId.equals(item.getPlaylistId())) {
                return true;
            }
        }
        return false;
    }

    private void showSaved(Context context) {
        mSave.setImageResource(mSaved ? R.drawable.ic_phone_library_added : R.drawable.ic_phone_library_add);
        mSave.setContentDescription(context.getString(mSaved ? R.string.phone_playlist_saved : R.string.phone_playlist_save));
    }

    private void toggleSave(Context context) {
        if (mPlaylist == null || mSaveBusy) {
            return;
        }
        if (!YouTubeServiceManager.instance().getSignInService().isSigned()) {
            android.widget.Toast.makeText(context, R.string.phone_playlist_sign_in, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String id = mPlaylist.playlistId;
        com.liskovsoft.mediaserviceinterfaces.MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        if (mSaved) {
            new android.app.AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.phone_playlist_remove_ask, mPlaylist.getTitle()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.phone_playlist_remove, (d, w) -> run(context, service.removePlaylistObserve(id), false))
                    .show();
            return;
        }
        run(context, mPlaylist.mediaItem != null && id.equals(mPlaylist.mediaItem.getPlaylistId())
                ? service.savePlaylistObserve(mPlaylist.mediaItem) : service.savePlaylistObserve(id), true);
    }

    private void run(Context context, io.reactivex.Observable<Void> action, boolean save) {
        mSaveBusy = true;
        mSaved = save; // right away, as in the YouTube app; put back if it fails
        showSaved(context);
        RxHelper.execute(action, error -> {
            mSaveBusy = false;
            mSaved = !save;
            showSaved(context);
            android.widget.Toast.makeText(context, R.string.phone_playlist_save_failed, android.widget.Toast.LENGTH_SHORT).show();
        }, () -> {
            mSaveBusy = false;
            android.widget.Toast.makeText(context, save ? R.string.phone_playlist_saved_toast : R.string.phone_playlist_removed,
                    android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    void release() {
        RxHelper.disposeActions(mSavedCheck);
    }

    // endregion

    private void share(Context context) {
        if (mPlaylist == null || TextUtils.isEmpty(mPlaylist.playlistId)) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/playlist?list=" + mPlaylist.playlistId);
        context.startActivity(Intent.createChooser(send, context.getString(R.string.phone_music_share)));
    }

    private static View circle(Context context, int drawable, View.OnClickListener listener) {
        ImageView button = new ImageView(context);
        button.setImageResource(drawable);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(context.getResources().getColor(R.color.phone_text)));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(context, 12);
        button.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor((context.getResources().getColor(R.color.phone_text) & 0x00FFFFFF) | 0x26000000);
        button.setBackground(shape);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        params.setMarginStart(dp(context, 10));
        button.setLayoutParams(params);
        return button;
    }

    private static TextView text(Context context, int sp, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(medium ? PhoneUi.medium(context) : PhoneUi.regular(context));
        return view;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
