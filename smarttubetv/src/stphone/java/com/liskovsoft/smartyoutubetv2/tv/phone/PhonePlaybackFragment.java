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
        refreshCarInfo();
    }

    // region What cars, Bluetooth devices and watches show

    private Object mInfoConnector;
    private Object mInfoPlayer;
    private String mArtVideoId;
    private android.graphics.Bitmap mArt;

    /**
     * Sends the song's (or video's) title, artist, length and artwork to the media session again. SmartTube sent
     * them only when the stream's timeline changed, often before the video was known, so a car's Bluetooth screen
     * showed "Unavailable"; now they go again when the video changes, when it starts playing and when the
     * artwork loads, never empty.
     */
    public void refreshCarInfo() {
        com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector connector = connector();
        if (connector == null) {
            return;
        }
        if (connector != mInfoConnector) {
            mInfoConnector = connector;
            connector.setMediaMetadataProvider(player -> carInfo(player));
        }
        com.google.android.exoplayer2.SimpleExoPlayer exo = getExoPlayer();
        if (exo != null && exo != mInfoPlayer) {
            mInfoPlayer = exo;
            exo.addListener(new com.google.android.exoplayer2.Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
                        refreshCarInfo(); // the length is known now
                    }
                }
            });
        }
        connector.invalidateMediaSessionMetadata();
        loadArt();
    }

    private com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector connector() {
        try {
            java.lang.reflect.Field field = PlaybackFragment.class.getDeclaredField("mMediaSessionConnector");
            field.setAccessible(true);
            Object connector = field.get(this);
            return connector instanceof com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
                    ? (com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector) connector : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The lyric line being sung (music, with lyrics in time): on the lock screen and the media controls */
    private String mLyricLine;

    /** A new lyric line (or none): the lock screen and the media controls show it under the song's name */
    public void setLyricLine(String line) {
        if (android.text.TextUtils.equals(line, mLyricLine)) {
            return;
        }
        mLyricLine = line;
        com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector connector = connector();
        if (connector != null && connector == mInfoConnector) {
            connector.invalidateMediaSessionMetadata();
        }
    }

    private android.graphics.Bitmap mLyricsArt;
    private android.graphics.Bitmap mLyricsArtFrom;

    /**
     * The artwork with a lyrics badge: a round glow of the artwork's own colour in the bottom right corner, a dark disc
     * in it and a white note (made once per song)
     */
    private android.graphics.Bitmap lyricsArt(android.graphics.Bitmap art) {
        if (art == mLyricsArtFrom && mLyricsArt != null) {
            return mLyricsArt;
        }
        int w = art.getWidth();
        int h = art.getHeight();
        android.graphics.Bitmap badged = art.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas canvas = new android.graphics.Canvas(badged);
        int[] palette = PhoneMusicFx.palette(android.graphics.Bitmap.createScaledBitmap(art, 48, 27, true));
        float size = Math.min(w, h) * 0.34f;
        // In the square middle of the picture (where the lock screen crops it), bottom right
        float cx = (w + Math.min(w, h)) / 2f - size * 0.62f;
        float cy = (h + Math.min(w, h)) / 2f - size * 0.62f;
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        int glow = palette[0] & 0x00FFFFFF;
        paint.setShader(new android.graphics.RadialGradient(cx, cy, size, new int[] {glow | 0xE6000000, glow | 0x66000000, glow},
                new float[] {0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size, paint);
        paint.setShader(null);
        paint.setColor(0xD9000000 | (PhoneMusicPlayer.darken(palette[0], 0.7f) & 0x00FFFFFF));
        canvas.drawCircle(cx, cy, size * 0.42f, paint);
        paint.setColor(android.graphics.Color.WHITE);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTextSize(size * 0.5f);
        paint.setFakeBoldText(true);
        android.graphics.Paint.FontMetrics m = paint.getFontMetrics();
        canvas.drawText("\u266A", cx, cy - (m.ascent + m.descent) / 2f, paint);
        mLyricsArtFrom = art;
        mLyricsArt = badged;
        return badged;
    }

    private android.support.v4.media.MediaMetadataCompat carInfo(com.google.android.exoplayer2.Player player) {
        Video video = getVideo();
        if (video == null) {
            return null;
        }
        String app = getString(com.liskovsoft.smartyoutubetv2.tv.R.string.app_name);
        String title = video.getTitleFull();
        if (android.text.TextUtils.isEmpty(title)) {
            title = video.getTitle();
        }
        String artist = video.getAuthor();
        if (android.text.TextUtils.isEmpty(artist) && video.getSecondTitleFull() != null) {
            artist = video.getSecondTitleFull().toString().split(" \u2022 ")[0].trim();
        }
        if (PhoneMusic.wantsMusic()) {
            // A song: its own name and singer, not "Song (Official Video) | Film | Label"
            String song = PhoneLyrics.displayTitle(title);
            String singer = PhoneLyrics.cleanArtist(artist);
            title = !android.text.TextUtils.isEmpty(song) ? song : title;
            artist = !android.text.TextUtils.isEmpty(singer) ? singer : artist;
        }
        if (android.text.TextUtils.isEmpty(title)) {
            title = app;
        }
        if (android.text.TextUtils.isEmpty(artist)) {
            artist = app;
        }
        // The words being sung as the first line (short pieces: they pop in whole, no scrolling), the song and the
        // singer under them
        String album = app;
        if (!android.text.TextUtils.isEmpty(mLyricLine)) {
            album = artist;
            artist = getString(com.liskovsoft.smartyoutubetv2.tv.R.string.phone_music_lyrics) + " \u00B7 " + title + " \u00B7 " + artist;
            title = mLyricLine;
        }
        android.support.v4.media.MediaMetadataCompat.Builder info = new android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_ID, video.videoId)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                        "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg");
        long duration = player != null ? player.getDuration() : 0;
        if (duration > 0) {
            info.putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        }
        if (mArt != null && video.videoId != null && video.videoId.equals(mArtVideoId)) {
            // Lyrics showing: the artwork with a glowing note in its corner, so the line reads as lyrics
            android.graphics.Bitmap art = !android.text.TextUtils.isEmpty(mLyricLine) ? lyricsArt(mArt) : mArt;
            info.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
            info.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art);
        }
        return info.build();
    }

    /** The artwork as a picture (cars and watches show it; most can't fetch a link) */
    private void loadArt() {
        Video video = getVideo();
        android.content.Context context = getContext();
        if (video == null || video.videoId == null || context == null || video.videoId.equals(mArtVideoId)) {
            return;
        }
        String id = video.videoId;
        mArtVideoId = id;
        mArt = null;
        com.bumptech.glide.Glide.with(context.getApplicationContext())
                .asBitmap()
                // The 16:9 picture (no black bars), cut square like a song's cover
                .load("https://i.ytimg.com/vi/" + id + "/hq720.jpg")
                .error(com.bumptech.glide.Glide.with(context.getApplicationContext()).asBitmap()
                        .load("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").centerCrop().override(320, 320))
                .centerCrop()
                .override(400, 400)
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap bitmap,
                            @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        if (id.equals(mArtVideoId)) {
                            mArt = bitmap;
                            com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector connector = connector();
                            if (connector != null) {
                                connector.invalidateMediaSessionMetadata();
                            }
                        }
                    }

                    @Override
                    public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    // endregion

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

    /**
     * Sound only while nothing of the app is on screen (screen off, another app in front): the video track is switched
     * off, so no picture is downloaded or decoded for nobody. With the picture decoded into a texture no one draws,
     * the decoder filled up and playback got stuck after a while. Back on screen the picture returns at the next key
     * frame. Safe to call again and again (a restarted engine has a new selector).
     */
    public void setVideoTrackDisabled(boolean disabled) {
        com.google.android.exoplayer2.trackselection.DefaultTrackSelector selector = mCurrentTrackSelector;
        if (selector == null) {
            return;
        }
        try {
            int video = com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager.RENDERER_INDEX_VIDEO;
            if (selector.getParameters().getRendererDisabled(video) != disabled) {
                selector.setParameters(selector.buildUponParameters().setRendererDisabled(video, disabled));
                android.util.Log.d("PhonePlayback", disabled ? "in the background: sound only" : "on screen again: picture back");
            }
        } catch (RuntimeException e) {
            android.util.Log.d("PhonePlayback", "video track: " + e);
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

    /** The video's picture as it is now, this small (for ambient mode's glow) */
    public android.graphics.Bitmap videoFrame(int width, int height) {
        return mVideoView != null && mVideoView.isAvailable() ? mVideoView.getBitmap(width, height) : null;
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
