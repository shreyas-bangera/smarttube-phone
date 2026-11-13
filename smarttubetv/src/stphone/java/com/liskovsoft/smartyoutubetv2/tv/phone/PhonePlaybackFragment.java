package com.liskovsoft.smartyoutubetv2.tv.phone;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackFragment;

/**
 * SmartTube's player with a phone hook: in portrait the TV overlay (title, control rows, suggestion rows) stays hidden
 * and the phone screen shows its own controls and details instead. In landscape it is the normal TV player.
 */
public class PhonePlaybackFragment extends PlaybackFragment {
    public interface Host {
        boolean isCompactPlayer();

        void onPlayerControlsVisible(boolean visible);

        void onPlayerVideo(Video video);

        void onPlayerSuggestions(VideoGroup group);

        void onPlayerSuggestionsCleared();

        void onPlayerButtonState(int buttonId, int buttonState);

        void onPlayerChannelIcon(String iconUrl);
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        super.setChannelIcon(iconUrl);
        Host host = getPhoneHost();
        if (host != null) {
            host.onPlayerChannelIcon(iconUrl);
        }
    }

    @Override
    public void onViewCreated(@androidx.annotation.NonNull android.view.View view, @androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        hideTvLayers(view);
    }

    @Override
    public void onResume() {
        super.onResume();
        hideTvLayers(getView());
    }

    /**
     * The TV overlay (title, control rows, Recommended, Chapters) and its dim layer never show on the phone.
     */
    private static void hideTvLayers(android.view.View root) {
        if (root == null) {
            return;
        }
        android.view.View dock = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.playback_controls_dock);
        if (dock != null) {
            dock.setVisibility(android.view.View.GONE);
        }
        android.view.View background = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.playback_fragment_background);
        if (background != null) {
            background.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * SmartTube's own media session (connected to the player): used for the background playback notification.
     */
    public android.support.v4.media.session.MediaSessionCompat.Token getMediaSessionToken() {
        try {
            java.lang.reflect.Field field = com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackFragment.class.getDeclaredField("mMediaSession");
            field.setAccessible(true);
            Object session = field.get(this);
            return session instanceof android.support.v4.media.session.MediaSessionCompat
                    ? ((android.support.v4.media.session.MediaSessionCompat) session).getSessionToken() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private Host getPhoneHost() {
        return getActivity() instanceof Host ? (Host) getActivity() : null;
    }

    private boolean isCompact() {
        Host host = getPhoneHost();
        return host != null && host.isCompactPlayer();
    }

    @Override
    public void showControlsOverlay(boolean runAnimation) {
        if (isCompact()) {
            Host host = getPhoneHost();
            if (host != null) {
                host.onPlayerControlsVisible(true);
            }
            return;
        }
        super.showControlsOverlay(runAnimation);
    }

    @Override
    public void hideControlsOverlay(boolean runAnimation) {
        super.hideControlsOverlay(runAnimation);
        Host host = getPhoneHost();
        if (host != null && isCompact()) {
            host.onPlayerControlsVisible(false);
        }
    }

    @Override
    public void setVideo(Video video) {
        super.setVideo(video);
        Host host = getPhoneHost();
        if (host != null) {
            host.onPlayerVideo(video);
        }
    }

    @Override
    public void updateSuggestions(VideoGroup group) {
        super.updateSuggestions(group);
        Host host = getPhoneHost();
        if (host != null) {
            host.onPlayerSuggestions(group);
        }
    }

    @Override
    public void clearSuggestions() {
        super.clearSuggestions();
        Host host = getPhoneHost();
        if (host != null) {
            host.onPlayerSuggestionsCleared();
        }
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        super.setButtonState(buttonId, buttonState);
        Host host = getPhoneHost();
        if (host != null) {
            host.onPlayerButtonState(buttonId, buttonState);
        }
    }

    // region The video's picture: the one shared texture (see PhoneVideoSurface)

    private android.view.TextureView mVideoView;
    private android.view.ViewGroup mVideoParent;
    private android.view.SurfaceHolder.Callback mSurfaceCallback;
    /** Called when the video view shows a new frame (the mini player hand-off waits for one) */
    private Runnable mOnVideoFrame;

    @Override
    public android.view.View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container,
            android.os.Bundle savedInstanceState) {
        android.view.View root = super.onCreateView(inflater, container, savedInstanceState);
        installVideoView(root);
        return root;
    }

    /**
     * SmartTube's video view (a SurfaceView) is swapped for a TextureView that shows the shared texture. The player
     * gets that texture's Surface once and keeps it: moving the picture to the mini player and back only moves the
     * texture between views.
     */
    private void installVideoView(android.view.View root) {
        android.view.ViewGroup surfaceRoot = root != null ? root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root) : null;
        android.view.View original = getSurfaceView();
        if (surfaceRoot == null || original == null || original.getParent() != surfaceRoot) {
            return; // SmartTube's own surface then
        }
        surfaceRoot.removeView(original);
        mVideoParent = surfaceRoot;
        mVideoView = new android.view.TextureView(root.getContext());
        mVideoView.setSurfaceTextureListener(new PhoneVideoSurface.Listener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int width, int height) {
                if (PhoneVideoSurface.texture() == null) {
                    deliverSurface(PhoneVideoSurface.adopt(texture, mVideoView));
                } else if (!PhoneVideoSurface.isShared(texture) && !PhoneVideoSurface.showIn(mVideoView)) {
                    // The shared texture is still on another (closing) player screen: this player gets its own, and
                    // it becomes the shared one
                    deliverSurface(PhoneVideoSurface.adopt(texture, mVideoView));
                }
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) {
                PhonePip.frameShown();
                Runnable onFrame = mOnVideoFrame;
                if (onFrame != null) {
                    mOnVideoFrame = null;
                    onFrame.run();
                }
            }
        });
        // Before it's on screen: the view uses the shared texture right away, no throwaway one
        PhoneVideoSurface.showIn(mVideoView);
        surfaceRoot.addView(mVideoView, 0, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER));
        deliverSurface(PhoneVideoSurface.surface()); // a texture from an earlier player screen: the player gets it now
    }

    private void deliverSurface(android.view.Surface surface) {
        if (mSurfaceCallback != null && surface != null) {
            mSurfaceCallback.surfaceCreated(new com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface.TextureViewSurfaceHolder(surface));
        }
    }

    /** True when the picture is the shared texture (the mini player shows it too) */
    public boolean usesSharedVideo() {
        return mVideoView != null && PhoneVideoSurface.texture() != null;
    }

    /** The player screen goes behind the mini player: its view lets go of the texture so the card can show it */
    public void detachVideo() {
        if (mVideoView != null && mVideoView.getParent() != null) {
            mVideoParent.removeView(mVideoView);
        }
    }

    /** Back from the mini player: the texture comes back here; onFrame runs at the first frame drawn (or soon after) */
    public void reattachVideo(Runnable onFrame) {
        if (mVideoView == null || mVideoParent == null) {
            return;
        }
        mOnVideoFrame = onFrame;
        if (mVideoView.getParent() == null) {
            PhoneVideoSurface.showIn(mVideoView);
            mVideoParent.addView(mVideoView, 0, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER));
        }
    }

    /** The video's picture as it is now (for the mini player's first moment) */
    public android.graphics.Bitmap videoFrame() {
        return mVideoView != null && mVideoView.isAvailable() ? mVideoView.getBitmap() : null;
    }

    // endregion

    /**
     * The player's own surface goes away when the player screen goes behind the mini player card. The card's surface
     * shows the video by then: don't take the video off it.
     */
    @Override
    public void setSurfaceHolderCallback(android.view.SurfaceHolder.Callback callback) {
        mSurfaceCallback = callback; // also before the view exists: installVideoView hands it the surface then
        if (mVideoView != null) {
            // The shared texture's Surface, for good: never taken back (the decoder keeps drawing into it)
            mSurfaceCallback = callback;
            deliverSurface(PhoneVideoSurface.surface());
            return;
        }
        super.setSurfaceHolderCallback(callback == null ? null : new android.view.SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(android.view.SurfaceHolder holder) {
                callback.surfaceCreated(holder); // back on screen: the video comes back here
            }

            @Override
            public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
                callback.surfaceChanged(holder, format, width, height);
            }

            @Override
            public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                if (!PhoneMiniPlayer.ownsSurface()) {
                    callback.surfaceDestroyed(holder);
                }
            }
        });
    }

    /**
     * Hide the TV overlay right away (used when switching back to the portrait player).
     */
    public void hideTvOverlay() {
        super.hideControlsOverlay(false);
    }
}
