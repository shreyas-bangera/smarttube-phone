package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

/**
 * A "Save to library" button for a playlist (its page, the player's playlist panel): saved playlists are the
 * account's, the You tab's Playlists, as in the YouTube app. Shows a tick once saved.
 */
final class PhonePlaylistSaver {
    private final ImageView mButton;
    /** Unsaving is offered only where it's sure the playlist isn't the user's own (its page knows its owner) */
    private final boolean mCanRemove;
    private String mPlaylistId;
    private String mTitle;
    private MediaItem mItem;
    private boolean mSaved;
    private boolean mBusy;
    private Disposable mCheck;

    PhonePlaylistSaver(ImageView button, boolean canRemove) {
        mButton = button;
        mCanRemove = canRemove;
        mButton.setOnClickListener(v -> toggle(v.getContext()));
    }

    /** A playlist someone else made can be saved; a mix, Watch later, Liked videos or one's own can't */
    static boolean canSave(String playlistId, String author) {
        if (TextUtils.isEmpty(playlistId) || playlistId.length() < 5 || playlistId.startsWith("RD")
                || "WL".equals(playlistId) || "LL".equals(playlistId)) {
            return false;
        }
        com.liskovsoft.mediaserviceinterfaces.oauth.Account account = YouTubeServiceManager.instance().getSignInService().getSelectedAccount();
        return account == null || author == null || !author.equals(account.getName());
    }

    /** The playlist shown now (null: none): the button shows when it can be saved, ticked when it's saved already */
    void bind(Context context, String playlistId, String title, String author, MediaItem item) {
        if (TextUtils.equals(playlistId, mPlaylistId)) {
            return;
        }
        RxHelper.disposeActions(mCheck);
        mPlaylistId = playlistId;
        mTitle = title;
        mItem = item;
        mSaved = false;
        boolean can = canSave(playlistId, author);
        mButton.setVisibility(can ? View.VISIBLE : View.GONE);
        show(context);
        if (!can || !YouTubeServiceManager.instance().getSignInService().isSigned()) {
            return;
        }
        String id = playlistId;
        mCheck = RxHelper.execute(YouTubeServiceManager.instance().getContentService().getPlaylistsObserve(), group -> {
            if (id.equals(mPlaylistId) && isIn(group, id)) {
                mSaved = true;
                show(context);
            }
        }, error -> { });
    }

    void release() {
        RxHelper.disposeActions(mCheck);
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

    private void show(Context context) {
        mButton.setImageResource(mSaved ? R.drawable.ic_phone_library_added : R.drawable.ic_phone_library_add);
        mButton.setContentDescription(context.getString(mSaved ? R.string.phone_playlist_saved : R.string.phone_playlist_save));
    }

    private void toggle(Context context) {
        if (mPlaylistId == null || mBusy) {
            return;
        }
        if (!YouTubeServiceManager.instance().getSignInService().isSigned()) {
            Toast.makeText(context, R.string.phone_playlist_sign_in, Toast.LENGTH_LONG).show();
            return;
        }
        String id = mPlaylistId;
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        if (mSaved) {
            if (!mCanRemove) {
                Toast.makeText(context, R.string.phone_playlist_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            new android.app.AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.phone_playlist_remove_ask, mTitle != null ? mTitle : ""))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.phone_playlist_remove, (d, w) -> run(context, service.removePlaylistObserve(id), false))
                    .show();
            return;
        }
        run(context, mItem != null && id.equals(mItem.getPlaylistId())
                ? service.savePlaylistObserve(mItem) : service.savePlaylistObserve(id), true);
    }

    private void run(Context context, Observable<Void> action, boolean save) {
        mBusy = true;
        mSaved = save; // right away, as in the YouTube app; put back if it fails
        show(context);
        RxHelper.execute(action, error -> {
            mBusy = false;
            mSaved = !save;
            show(context);
            Toast.makeText(context, R.string.phone_playlist_save_failed, Toast.LENGTH_SHORT).show();
        }, () -> {
            mBusy = false;
            Toast.makeText(context, save ? R.string.phone_playlist_saved_toast : R.string.phone_playlist_removed,
                    Toast.LENGTH_SHORT).show();
        });
    }
}
