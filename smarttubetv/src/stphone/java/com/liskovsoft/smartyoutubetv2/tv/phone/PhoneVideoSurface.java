package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

/**
 * The video's one picture surface for the whole app run (the technique of the YouTube app and NewTube).
 *
 * The decoder draws into ONE SurfaceTexture. The player's video view and the mini player card only show that texture,
 * handing it over to each other: the decoder's output surface never changes, so it is never stopped and rebuilt, and
 * there is no moment where the sound plays on while the picture waits for the next key frame.
 *
 * A texture is shown by one view at a time: a view lets go of it (removed from its window) before another one takes
 * it. A view's own throwaway texture is swapped for this one as soon as it appears.
 */
final class PhoneVideoSurface {
    private static final String TAG = "PhoneMiniPlayer";
    private static SurfaceTexture sTexture;
    private static Surface sSurface;
    /** The view showing the texture now (a texture can only be on screen in one view) */
    private static java.lang.ref.WeakReference<TextureView> sOwner = new java.lang.ref.WeakReference<>(null);

    private PhoneVideoSurface() {
    }

    /** The shared texture, null before the player made one (or after it was lost) */
    static SurfaceTexture texture() {
        if (sTexture != null && Build.VERSION.SDK_INT >= 26 && sTexture.isReleased()) {
            Log.d(TAG, "video texture was released: a new one");
            sTexture = null;
            sSurface = null;
        }
        return sTexture;
    }

    static boolean ownerIs(TextureView view) {
        return sOwner.get() == view && view != null && view.getSurfaceTexture() == sTexture && sTexture != null;
    }

    /** What the decoder draws into */
    static Surface surface() {
        return texture() != null ? sSurface : null;
    }

    static boolean isShared(SurfaceTexture texture) {
        return texture != null && texture == sTexture;
    }

    /** The player's video view showed its first texture: from now on it's the one for the whole run */
    static Surface adopt(SurfaceTexture texture) {
        return adopt(texture, null);
    }

    /** Also when the shared texture is held by a screen that's going away: the new player starts a texture of its own */
    static Surface adopt(SurfaceTexture texture, TextureView owner) {
        sTexture = texture;
        sSurface = new Surface(texture);
        sOwner = new java.lang.ref.WeakReference<>(owner);
        Log.d(TAG, "video texture adopted");
        return sSurface;
    }

    /**
     * Shows the shared texture in this view (instead of a texture of its own). False when there is none, or the view
     * can't take it.
     */
    static boolean showIn(TextureView view) {
        SurfaceTexture texture = texture();
        if (texture == null || view == null) {
            return false;
        }
        if (view.getSurfaceTexture() == texture) {
            sOwner = new java.lang.ref.WeakReference<>(view);
            return true;
        }
        TextureView owner = sOwner.get();
        if (owner != null && owner != view && owner.isAttachedToWindow() && owner.getSurfaceTexture() == texture) {
            // Still on screen in another view (e.g. the previous player screen, not gone yet): taking it now would leave
            // this view blank
            Log.d(TAG, "video texture busy in another view");
            return false;
        }
        try {
            view.setSurfaceTexture(texture); // releases the view's own (throwaway) texture
            sOwner = new java.lang.ref.WeakReference<>(view);
            return true;
        } catch (RuntimeException e) {
            Log.d(TAG, "video texture not shown: " + e);
            return false;
        }
    }

    /** Listener for any view that shows the shared texture: never lets the view release it */
    abstract static class Listener implements TextureView.SurfaceTextureListener {
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return !isShared(texture); // a throwaway texture goes, the shared one stays
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    }
}
