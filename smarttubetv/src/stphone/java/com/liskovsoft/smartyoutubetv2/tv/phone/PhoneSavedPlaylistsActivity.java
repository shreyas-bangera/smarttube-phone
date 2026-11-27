package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * You › Saved playlists: the playlists of other channels saved to the library, in one place (the You page keeps
 * only the user's own). Tap one to open it.
 */
public class PhoneSavedPlaylistsActivity extends PhoneListActivity {
    /** The saved playlists, handed over by the You page (they were just loaded there) */
    private static List<Video> sPlaylists = new ArrayList<>();

    static void open(Context context, List<Video> playlists) {
        sPlaylists = new ArrayList<>(playlists);
        context.startActivity(new Intent(context, PhoneSavedPlaylistsActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdapter.setMode(FeedAdapter.MODE_FLAT); // big cards, each a stack (a playlist)
        if (sPlaylists.isEmpty()) {
            showMessage(getString(R.string.phone_saved_playlists_none), null, null);
        } else {
            mAdapter.update(VideoGroup.from(new ArrayList<>(sPlaylists)));
        }
    }

    @Override
    protected Video getChannel() {
        return null;
    }

    @Override
    protected void updateHeader() {
        super.updateHeader();
        TextView title = findViewById(R.id.phone_screen_title);
        if (title != null) {
            title.setText(R.string.phone_saved_playlists);
        }
    }

    @Override
    public void onVideoClicked(Video video) {
        PhoneUi.prepareExpandLaunch(video);
        ChannelUploadsPresenter.instance(this).openChannel(video);
    }

    /** Long press or ⋮: open it, or remove it from the library */
    @Override
    public void onVideoLongClicked(Video video) {
        PhonePlayerSheet.Sheet sheet = new PhonePlayerSheet.Sheet(this, video.getTitle());
        sheet.addRow(R.drawable.ic_phone_playlist, getString(R.string.phone_open), null, false, () -> {
            sheet.dismiss();
            onVideoClicked(video);
        });
        sheet.addRow(R.drawable.ic_phone_delete, getString(R.string.phone_playlist_remove_library), null, false, () -> {
            sheet.dismiss();
            new android.app.AlertDialog.Builder(this)
                    .setMessage(getString(R.string.phone_playlist_remove_ask, video.getTitle()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.phone_playlist_remove, (d, w) -> remove(video))
                    .show();
        });
        sheet.show();
    }

    private void remove(Video playlist) {
        String id = playlist.playlistId;
        if (id == null) {
            return;
        }
        if (id.startsWith("VL")) {
            id = id.substring(2);
        }
        // Gone at once (back if it fails)
        sPlaylists.remove(playlist);
        VideoGroup gone = VideoGroup.from(playlist);
        gone.setAction(VideoGroup.ACTION_REMOVE);
        mAdapter.update(gone);
        if (sPlaylists.isEmpty()) {
            showMessage(getString(R.string.phone_saved_playlists_none), null, null);
        }
        com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getMediaItemService().removePlaylistObserve(id),
                error -> {
                    android.widget.Toast.makeText(this, R.string.phone_playlist_save_failed, android.widget.Toast.LENGTH_SHORT).show();
                    sPlaylists.add(playlist);
                    hideMessage();
                    mAdapter.update(VideoGroup.from(playlist));
                },
                () -> android.widget.Toast.makeText(this, R.string.phone_playlist_removed, android.widget.Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onStripClicked(Video channel) {
        onVideoClicked(channel);
    }

    @Override
    public void onShelfScrollEnd(Video lastVideo) {
    }

    @Override
    protected boolean hasPendingActions() {
        return false;
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
    }
}
