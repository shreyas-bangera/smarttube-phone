package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.HapticFeedbackConstants;
import android.view.VelocityTracker;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.ref.WeakReference;

/**
 * In-app mini player, like the YouTube app: dragging the player down (or Back while a video plays) shrinks the video
 * into a small card at the bottom right of the screen underneath (Home, Search, a channel). The video never stops:
 * the player screen stays alive behind and its player draws into the card's own surface (codecs switch surfaces
 * without a new start, Android 6+). Tap the card to open the player again, X to close it, drag it anywhere.
 *
 * Android's picture in picture is only used when leaving the app.
 */
final class PhoneMiniPlayer {
    private static final String TAG = "PhoneMiniPlayer";
    private static WeakReference<PhonePlaybackActivity> sPlayer = new WeakReference<>(null);
    private static WeakReference<PhoneActivity> sHost = new WeakReference<>(null);
    private static WeakReference<Card> sCard = new WeakReference<>(null);
    private static boolean sActive;
    /** The last frame, shown by the next card until its own frames arrive */
    private static Bitmap sStill;
    /** The card surface the player draws into */
    private static Surface sSurface;
    /** Card shape (width / height): the player's video box, so the video lands on it exactly */
    private static float sAspect = 16f / 9f;
    /** Where the user moved the card in this session (screen pixels), NaN: bottom right */
    private static float sCardX = Float.NaN;
    private static float sCardY = Float.NaN;

    private PhoneMiniPlayer() {
    }

    // region Session

    // region The host screen in Android's floating window (the app was left while the card played)

    /** Leaving the app now should keep the video on screen: the card is up on this screen and playing */
    static boolean wantsPip(PhoneActivity host) {
        Card card = sCard.get();
        if (!sActive || card == null || card.mHost != host || card.getParent() == null) {
            return false;
        }
        PhonePlaybackActivity player = sPlayer.get();
        SimpleExoPlayer exo = player != null && !player.isFinishing() ? player.exoPlayer() : null;
        return exo != null && exo.getPlayWhenReady() && PhonePlaybackActivity.pipOnLeave(host) && PhonePip.systemAllows(host)
                && !host.isAppLeaving();
    }

    /** Where the card is on screen, null without a card */
    static Rect cardRect(PhoneActivity host) {
        Card card = sCard.get();
        if (card == null || card.mHost != host || card.getParent() == null || card.getWidth() == 0 || card.mInPip) {
            return null;
        }
        int[] spot = new int[2];
        card.getLocationOnScreen(spot);
        return new Rect(spot[0], spot[1], spot[0] + card.getWidth(), spot[1] + card.getHeight());
    }

    /** The floating window's shape: the video's, within what Android accepts */
    static android.util.Rational pipAspect() {
        float aspect = Math.max(0.45f, Math.min(2.3f, sAspect));
        return new android.util.Rational(Math.round(aspect * 1000), 1000);
    }

    static boolean isPlaying() {
        PhonePlaybackActivity player = sPlayer.get();
        SimpleExoPlayer exo = player != null ? player.exoPlayer() : null;
        return exo != null && exo.getPlayWhenReady();
    }

    /** Why the mini player wouldn't float now (for the log) */
    static String pipBlocker(PhoneActivity host) {
        Card card = sCard.get();
        if (!sActive) {
            return "no mini player session";
        }
        if (card == null || card.mHost != host || card.getParent() == null) {
            return "no card on " + host.getClass().getSimpleName();
        }
        PhonePlaybackActivity player = sPlayer.get();
        SimpleExoPlayer exo = player != null && !player.isFinishing() ? player.exoPlayer() : null;
        if (exo == null) {
            return "no player";
        }
        if (!exo.getPlayWhenReady()) {
            return "paused (state " + exo.getPlaybackState() + ")";
        }
        if (!PhonePip.systemAllows(host)) {
            return "blocked in Android settings";
        }
        if (host.isAppLeaving()) {
            return "the app is opening a screen";
        }
        return "none";
    }

    /** This screen is in the floating window because it shows the mini player's video there */
    static boolean isFloating(PhoneActivity host) {
        Card card = sCard.get();
        return card != null && card.mHost == host && card.mInPip;
    }

    static void onHostPip(PhoneActivity host, boolean inPip, boolean closed) {
        Card card = sCard.get();
        if (card == null || card.mHost != host) {
            return;
        }
        card.setInPip(inPip);
        if (closed) {
            close(); // the floating window was closed: the video stops
        }
    }

    // endregion

    /** Something on the host screen changed the room at the bottom (the update banner came or went) */
    static void replace() {
        Card card = sCard.get();
        if (card != null && card.getParent() != null && !card.mDragging) {
            card.place();
        }
    }

    /** A screen that can show the card came to the front */
    static void onHostResumed(PhoneActivity host) {
        if (!host.hostsMiniPlayer()) {
            return;
        }
        sHost = new WeakReference<>(host);
        if (isActive()) {
            showCard(host);
        }
    }

    static void onHostPaused(PhoneActivity host) {
        Card card = sCard.get();
        if (card != null && card.mHost == host) {
            card.keepStill(); // the next screen (or the player) starts from this frame
        }
    }

    static void onHostStopped(PhoneActivity host) {
        Card card = sCard.get();
        if (card != null && card.mHost == host && !card.mInPip) {
            // (In the floating window it stays: some phones stop a floating screen that is still showing the video)
            card.remove();
        }
    }

    /** The screen the player was opened from: the card shows there */
    static Class<? extends Activity> hostClass() {
        PhoneActivity host = sHost.get();
        return host != null && !host.isFinishing() && !host.isDestroyed() ? host.getClass() : null;
    }

    static boolean isActive() {
        if (!sActive) {
            return false;
        }
        PhonePlaybackActivity player = sPlayer.get();
        if (player == null || player.isFinishing() || player.isDestroyed() || player.exoPlayer() == null) {
            end(); // the player was closed behind our back
            return false;
        }
        return true;
    }

    /** The player shrank into the card: from now on the card shows its video */
    static void begin(PhonePlaybackActivity player, Bitmap still, float aspect) {
        sPlayer = new WeakReference<>(player);
        sAspect = aspect > 0 ? aspect : 16f / 9f;
        sActive = true;
        sStill = still;
        sCardX = Float.NaN; // always lands at the bottom right
        sCardY = Float.NaN;
    }

    /**
     * The player takes its video back (opened again or closed). The card stays until its screen is covered (it shows
     * the last frame meanwhile), so it doesn't vanish while the player opens over it.
     */
    static void end() {
        sActive = false;
        sPlayer = new WeakReference<>(null);
    }

    /** True while a card's surface shows the video (the player's own surface may go away meanwhile) */
    static boolean ownsSurface() {
        return sActive && sSurface != null;
    }

    /** The player screen takes the video's texture back (also when it came to the front some other way) */
    static void letGoOfVideo() {
        Card card = sCard.get();
        if (card != null) {
            card.letGoOfVideo();
        }
    }

    static Bitmap takeStill() {
        Bitmap still = sStill;
        sStill = null;
        return still;
    }

    /**
     * The floating window's expand button (or the app opened again while it floats): the video opens full screen, like
     * the YouTube app, not just the app with the card.
     */
    static void openFromWindow(PhoneActivity host) {
        Card card = sCard.get();
        PhonePlaybackActivity player = sPlayer.get();
        if (!isActive() || card == null || card.mHost != host || player == null) {
            return;
        }
        android.util.Log.d("PhonePip", "floating window expanded: opening the player");
        if (player.getTaskId() == host.getTaskId()) {
            expand();
            return;
        }
        // Android left this screen in a task of its own: bring the player's task to the front instead
        card.keepStill();
        card.letGoOfVideo();
        android.app.ActivityManager manager = (android.app.ActivityManager) host.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (manager != null && android.os.Build.VERSION.SDK_INT >= 23) {
            for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
                    if (task.getTaskInfo().taskId == player.getTaskId()) {
                        task.moveToFront();
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // the task went away meanwhile
                }
            }
        }
        expand();
    }

    private static void expand() {
        PhonePlaybackActivity player = sPlayer.get();
        Card card = sCard.get();
        if (player == null || card == null) {
            end();
            return;
        }
        card.keepStill();
        card.letGoOfVideo(); // the player screen shows the video's texture again
        PhoneActivity host = card.mHost;
        host.startActivity(new android.content.Intent(host, PhonePlaybackActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private static void close() {
        close(false);
    }

    /** @param swipedDown the card was thrown off the bottom of the screen: it keeps going that way */
    private static void close(boolean swipedDown) {
        PhonePlaybackActivity player = sPlayer.get();
        Card card = sCard.get();
        sStill = null;
        if (card != null) {
            if (swipedDown) {
                card.slideAway();
            } else {
                card.animateAway();
            }
        }
        sActive = false;
        sPlayer = new WeakReference<>(null);
        if (player != null && !player.isFinishing()) {
            player.closeFromMiniPlayer();
        }
    }

    // endregion

    // region Geometry

    /** Card size for a shape (width / height), screen pixels */
    private static int[] cardSize(Activity activity, float aspect) {
        View root = activity.getWindow().getDecorView();
        int screen = Math.min(root.getWidth(), root.getHeight());
        if (screen <= 0) {
            screen = activity.getResources().getDisplayMetrics().widthPixels;
        }
        int width = Math.round(screen * (aspect >= 1f ? 0.5f : 0.3f));
        return new int[] {width, Math.round(width / aspect)};
    }

    /** The bottom right spot the card lands on (window = screen pixels: the phone screens are edge to edge) */
    static Rect dockRect(Activity activity, float aspect) {
        View root = activity.getWindow().getDecorView();
        int[] size = cardSize(activity, aspect);
        float density = activity.getResources().getDisplayMetrics().density;
        int margin = Math.round(12 * density);
        PhoneActivity host = sHost.get();
        int bottom = host != null ? host.miniPlayerBottomOffset() : navigationInset(root) + Math.round(56 * density);
        int right = root.getWidth() - margin;
        int bottomEdge = root.getHeight() - bottom - margin;
        return new Rect(right - size[0], bottomEdge - size[1], right, bottomEdge);
    }

    @SuppressWarnings("deprecation")
    static int navigationInset(View view) {
        android.view.WindowInsets insets = view.getRootWindowInsets();
        return insets != null ? insets.getSystemWindowInsetBottom() : 0;
    }

    @SuppressWarnings("deprecation")
    private static int statusInset(View view) {
        android.view.WindowInsets insets = view.getRootWindowInsets();
        return insets != null ? insets.getSystemWindowInsetTop() : 0;
    }

    /** The video's own shape (width / height), 16:9 when not known yet */
    private static float videoAspect(SimpleExoPlayer exo) {
        Format format = exo != null ? exo.getVideoFormat() : null;
        if (format == null || format.width <= 0 || format.height <= 0) {
            return 16f / 9f;
        }
        float ratio = format.pixelWidthHeightRatio > 0 ? format.pixelWidthHeightRatio : 1f;
        boolean rotated = format.rotationDegrees == 90 || format.rotationDegrees == 270;
        return rotated ? format.height / (format.width * ratio) : format.width * ratio / format.height;
    }

    // endregion

    // region Card

    private static void showCard(PhoneActivity host) {
        Card card = sCard.get();
        if (card != null && card.mHost == host && card.getParent() != null) {
            card.refresh();
            return;
        }
        if (card != null) {
            card.remove();
        }
        ViewGroup content = host.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        card = new Card(host);
        content.addView(card, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        sCard = new WeakReference<>(card);
        card.place();
    }

    private static final class Card extends FrameLayout implements TextureView.SurfaceTextureListener {
        private final PhoneActivity mHost;
        private final TextureView mVideo;
        private final ImageView mStill;
        private final ImageView mPlay;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final float mDensity;
        private Surface mSurface;
        /** The player drawing into mSurface (kept: the session may end before this card goes away) */
        private SimpleExoPlayer mAttachedTo;
        private boolean mGotFrame;
        private float mDownX;
        private float mDownY;
        private float mStartX;
        private float mStartY;
        private boolean mDragging;
        /** Shows the video's own texture (see PhoneVideoSurface), not a surface of its own */
        private boolean mShared;
        /** The host screen is Android's floating window: the card is all of it, without its buttons */
        private boolean mInPip;
        private ImageView mClose;
        private ValueAnimator mMove;
        private VelocityTracker mVelocity;

        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                if (!isActive()) {
                    if (mHost.hasWindowFocus()) {
                        remove(); // the player closed while this screen is in front
                        return;
                    }
                } else {
                    refresh();
                }
                mHost.updateMiniPlayerPip();
                mHandler.postDelayed(this, 500);
            }
        };

        Card(PhoneActivity host) {
            super(host);
            mHost = host;
            mDensity = host.getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.BLACK);
            setElevation(24 * mDensity); // above Home's cards and banners
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * mDensity);
                }
            });
            setClipToOutline(true);

            mVideo = new TextureView(host);
            mVideo.setSurfaceTextureListener(this);
            // The video's own texture (see PhoneVideoSurface): the decoder goes on drawing into it, nothing restarts
            mShared = PhoneVideoSurface.showIn(mVideo);
            addView(mVideo, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

            mStill = new ImageView(host);
            mStill.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mStill.setBackgroundColor(Color.BLACK);
            Bitmap still = sStill;
            if (still != null) {
                mStill.setImageBitmap(still);
            } else {
                // No copy of the last frame: the video's thumbnail until the video draws here
                PhonePlaybackActivity player = sPlayer.get();
                com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video = player != null ? player.miniVideo() : null;
                if (video != null) {
                    mStill.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    PhoneUi.loadThumb(mStill, video, false);
                }
            }
            android.util.Log.d(TAG, "card: still " + (still != null) + ", shared video " + mShared);
            addView(mStill, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (mShared) {
                // The texture already holds the current frame: it shows as soon as the card is drawn (a paused video
                // sends no new frame to wait for)
                mHandler.postDelayed(this::revealVideo, 200);
            }

            int button = Math.round(30 * mDensity);
            int inset = Math.round(6 * mDensity);
            ImageView close = roundButton(host, R.drawable.ic_phone_close, R.string.phone_mini_close);
            LayoutParams closeParams = new LayoutParams(button, button, Gravity.TOP | Gravity.END);
            closeParams.setMargins(inset, inset, inset, inset);
            addView(close, closeParams);
            close.setOnClickListener(v -> close());
            mClose = close;

            mPlay = roundButton(host, R.drawable.ic_phone_pause, R.string.phone_mini_play_pause);
            LayoutParams playParams = new LayoutParams(button, button, Gravity.BOTTOM | Gravity.START);
            playParams.setMargins(inset, inset, inset, inset);
            addView(mPlay, playParams);
            mPlay.setOnClickListener(v -> {
                PhonePlaybackActivity player = sPlayer.get();
                SimpleExoPlayer exo = player != null ? player.exoPlayer() : null;
                if (exo != null) {
                    exo.setPlayWhenReady(!exo.getPlayWhenReady());
                    refresh();
                }
            });

            setContentDescription(host.getString(R.string.phone_mini_open));
            mHandler.post(mTick);
        }

        private ImageView roundButton(Activity activity, int icon, int label) {
            ImageView view = new ImageView(activity);
            view.setImageResource(icon);
            view.setColorFilter(Color.WHITE);
            int pad = Math.round(6 * mDensity);
            view.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0x99000000);
            view.setBackground(circle);
            view.setContentDescription(activity.getString(label));
            return view;
        }

        void setInPip(boolean inPip) {
            if (mInPip == inPip) {
                return;
            }
            mInPip = inPip;
            if (mMove != null) {
                mMove.cancel();
            }
            mDragging = false;
            mClose.setVisibility(inPip ? View.GONE : View.VISIBLE);
            mPlay.setVisibility(inPip ? View.GONE : View.VISIBLE);
            setClipToOutline(!inPip);
            setAlpha(1f);
            if (inPip) {
                ViewGroup.LayoutParams params = getLayoutParams();
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                setLayoutParams(params);
                setTranslationX(0);
                setTranslationY(0);
            } else {
                post(this::place); // the window is its full size again by then
            }
        }

        /** Size and spot: bottom right, or where the user moved it */
        void place() {
            if (mInPip) {
                return;
            }
            View root = mHost.getWindow().getDecorView();
            if (root.getWidth() == 0) {
                root.post(this::place);
                return;
            }
            Rect dock = dockRect(mHost, sAspect);
            ViewGroup.LayoutParams params = getLayoutParams();
            params.width = dock.width();
            params.height = dock.height();
            setLayoutParams(params);
            float x = Float.isNaN(sCardX) ? dock.left : sCardX;
            float y = Float.isNaN(sCardY) ? dock.top : sCardY;
            float[] clamped = clamp(x, y, dock.width(), dock.height());
            setTranslationX(clamped[0]);
            setTranslationY(clamped[1]);
            fitVideo(dock.width(), dock.height());
        }

        /** The video keeps its shape inside the card (black bars instead of a stretched picture) */
        private void fitVideo(int width, int height) {
            PhonePlaybackActivity player = sPlayer.get();
            float aspect = videoAspect(player != null ? player.exoPlayer() : null);
            int videoWidth = width;
            int videoHeight = Math.round(width / aspect);
            if (videoHeight > height) {
                videoHeight = height;
                videoWidth = Math.round(height * aspect);
            }
            LayoutParams params = (LayoutParams) mVideo.getLayoutParams();
            if (params.width != videoWidth || params.height != videoHeight) {
                params.width = videoWidth;
                params.height = videoHeight;
                mVideo.setLayoutParams(params);
            }
        }

        private float[] clamp(float x, float y, int width, int height) {
            View root = mHost.getWindow().getDecorView();
            float margin = 8 * mDensity;
            float minY = statusInset(root) + margin;
            float maxY = root.getHeight() - mHost.miniPlayerBottomOffset() - height - margin;
            float maxX = root.getWidth() - width - margin;
            return new float[] {Math.max(margin, Math.min(maxX, x)), Math.max(minY, Math.min(Math.max(minY, maxY), y))};
        }

        void refresh() {
            PhonePlaybackActivity player = sPlayer.get();
            SimpleExoPlayer exo = player != null ? player.exoPlayer() : null;
            if (exo == null) {
                return;
            }
            mPlay.setImageResource(exo.getPlayWhenReady() ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null && parent.getChildAt(parent.getChildCount() - 1) != this) {
                bringToFront(); // a banner added later stays under the card
            }
            if (mSurface != null && exo != mAttachedTo && mHost.hasWindowFocus()) {
                attach(); // the player restarted its engine (e.g. after a network error): a new player to draw here
                return;
            }
            if (getWidth() > 0) {
                fitVideo(getWidth(), getHeight()); // the next video may have another shape
            }
            if (mSurface != null && sSurface != mSurface && mHost.hasWindowFocus()) {
                attach(); // e.g. the player let go of its surface when it closed a dialog
            }
        }

        private void attach() {
            PhonePlaybackActivity player = sPlayer.get();
            SimpleExoPlayer exo = player != null ? player.exoPlayer() : null;
            if (exo == null || mSurface == null) {
                return;
            }
            sSurface = mSurface;
            mAttachedTo = exo;
            exo.setVideoSurface(mSurface);
            android.util.Log.d(TAG, "attached");
        }

        /** Remember the frame on screen for whatever shows the video next */
        /** The shared texture holds a picture of this video (not an empty one: still loading, or it never played) */
        private boolean hasVideo() {
            PhonePlaybackActivity player = sPlayer.get();
            SimpleExoPlayer exo = player != null ? player.exoPlayer() : null;
            return exo != null && exo.getVideoFormat() != null;
        }

        private void revealVideo() {
            if (mGotFrame || mVideo.getParent() == null) {
                return;
            }
            if (!hasVideo()) {
                mHandler.postDelayed(this::revealVideo, 300); // the thumbnail stays until the video has a picture
                return;
            }
            mGotFrame = true;
            mStill.animate().alpha(0f).setDuration(120).withEndAction(() -> mStill.setVisibility(View.GONE)).start();
        }

        /** The card stops showing the video's texture (its last frame stays up) so the player screen can show it */
        void letGoOfVideo() {
            if (!mShared || mVideo.getParent() == null) {
                return;
            }
            Bitmap frame = sStill;
            if (frame != null) {
                mStill.setImageBitmap(frame);
                mStill.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            mStill.animate().cancel();
            mStill.setAlpha(1f);
            mStill.setVisibility(View.VISIBLE);
            removeView(mVideo);
            mGotFrame = false;
        }

        void keepStill() {
            if (mGotFrame && mVideo.isAvailable()) {
                try {
                    Bitmap frame = mVideo.getBitmap();
                    if (frame != null) {
                        sStill = frame;
                    }
                } catch (RuntimeException ignored) {
                    // no frame: the next screen shows black until the video draws
                }
            }
        }

        void remove() {
            mHandler.removeCallbacksAndMessages(null);
            if (mMove != null) {
                mMove.cancel();
            }
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) {
                parent.removeView(this); // releases the surface (onSurfaceTextureDestroyed)
            }
            if (sCard.get() == this) {
                sCard = new WeakReference<>(null);
            }
            mHost.updateMiniPlayerPip(); // leaving the app no longer opens the floating window
        }

        void animateAway() {
            mHandler.removeCallbacksAndMessages(null);
            animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(180).withEndAction(this::remove).start();
        }

        /** Swiped down: off the bottom of the screen, fading */
        void slideAway() {
            mHandler.removeCallbacksAndMessages(null);
            if (mMove != null) {
                mMove.cancel();
            }
            float bottom = mHost.getWindow().getDecorView().getHeight();
            animate().translationY(bottom).alpha(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(this::remove).start();
        }

        // region Surface

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            if (mShared || PhoneVideoSurface.texture() != null) {
                // Its own texture appeared (the view was re-created): the video's texture instead
                mShared = PhoneVideoSurface.showIn(mVideo);
                if (mShared) {
                    return;
                }
            }
            mSurface = new Surface(texture);
            mGotFrame = false;
            attach();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            if (PhoneVideoSurface.isShared(texture)) {
                return false; // the video's texture is never released by a view
            }
            Surface surface = mSurface;
            mSurface = null;
            if (surface != null) {
                if (mAttachedTo != null) {
                    mAttachedTo.clearVideoSurface(surface); // only if the player still draws here
                    mAttachedTo = null;
                }
                if (sSurface == surface) {
                    sSurface = null;
                }
                surface.release();
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            PhonePip.frameShown();
            if (!mGotFrame && (!mShared || hasVideo())) {
                mGotFrame = true;
                android.util.Log.d(TAG, "first frame");
                mStill.animate().alpha(0f).setDuration(120).withEndAction(() -> mStill.setVisibility(View.GONE)).start();
            }
        }

        // endregion

        // region Touch: tap opens, drag moves, swipe down closes

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            // Drags that start on a button still move the card
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startTouch(event);
                return false;
            }
            track(event);
            return event.getActionMasked() == MotionEvent.ACTION_MOVE && passedSlop(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startTouch(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    track(event);
                    if (!mDragging && passedSlop(event)) {
                        mDragging = true;
                        // The drag is the card's: no screen underneath (edge swipe back, pull to refresh) takes it over
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    if (mDragging) {
                        follow(event);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    track(event);
                    if (mDragging) {
                        follow(event); // where the finger lifted, not where the last move was reported
                        release();
                    } else {
                        expand();
                    }
                    endTouch();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    // The system took the touch (e.g. the finger reached the navigation area at the bottom edge):
                    // the card is let go where it is, without a throw
                    android.util.Log.d(TAG, "touch cancelled, dragging " + mDragging);
                    if (mDragging) {
                        if (mVelocity != null) {
                            mVelocity.clear();
                        }
                        release();
                    }
                    endTouch();
                    return true;
                default:
                    return true;
            }
        }

        private void startTouch(MotionEvent event) {
            if (mMove != null) {
                mMove.cancel();
            }
            mDownX = event.getRawX();
            mDownY = event.getRawY();
            mStartX = getTranslationX();
            mStartY = getTranslationY();
            mDragging = false;
            if (mVelocity != null) {
                mVelocity.recycle();
            }
            mVelocity = VelocityTracker.obtain();
            track(event);
        }

        private void follow(MotionEvent event) {
            setTranslationX(mStartX + event.getRawX() - mDownX);
            setTranslationY(mStartY + event.getRawY() - mDownY);
            // Below its resting line the card fades: letting go there closes it
            setAlpha(1f - 0.6f * Math.min(1f, pastBottom() / (getHeight() * 0.8f)));
        }

        private void endTouch() {
            mDragging = false;
            if (mVelocity != null) {
                mVelocity.recycle();
                mVelocity = null;
            }
        }

        /** Finger speed in screen coordinates (the card itself moves under the finger) */
        private void track(MotionEvent event) {
            if (mVelocity == null) {
                return;
            }
            MotionEvent onScreen = MotionEvent.obtain(event);
            onScreen.setLocation(event.getRawX(), event.getRawY());
            mVelocity.addMovement(onScreen);
            onScreen.recycle();
        }

        /** How far the card is below the lowest spot it can rest on, pixels (0 when above it) */
        private float pastBottom() {
            View root = mHost.getWindow().getDecorView();
            float lowest = root.getHeight() - mHost.miniPlayerBottomOffset() - getHeight() - 8 * mDensity;
            return Math.max(0f, getTranslationY() - lowest);
        }

        /** Let go: dragged or flicked off the bottom closes the video, anything else glides to a side */
        private void release() {
            float velocityX = 0;
            float velocityY = 0;
            if (mVelocity != null) {
                mVelocity.computeCurrentVelocity(1000);
                velocityX = mVelocity.getXVelocity();
                velocityY = mVelocity.getYVelocity();
            }
            float past = pastBottom();
            boolean flickedDown = velocityY > 900 * mDensity && velocityY > Math.abs(velocityX) && past > 0;
            android.util.Log.d(TAG, "release: past " + Math.round(past) + " of " + getHeight() + ", velocity " + Math.round(velocityY));
            if (past > getHeight() * 0.4f || flickedDown) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                close(true);
            } else {
                settle(velocityX, velocityY);
            }
        }

        private boolean passedSlop(MotionEvent event) {
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            return Math.abs(event.getRawX() - mDownX) > slop || Math.abs(event.getRawY() - mDownY) > slop;
        }

        /**
         * Glides to a side, kept clear of the bars. A throw carries on where it was heading (the side and the height it
         * would reach) and lands with a small bounce.
         */
        private void settle(float velocityX, float velocityY) {
            View root = mHost.getWindow().getDecorView();
            float margin = 12 * mDensity;
            float carry = 0.18f; // seconds the throw keeps going
            float centerX = getTranslationX() + getWidth() / 2f + velocityX * carry;
            float targetX = centerX < root.getWidth() / 2f ? margin : root.getWidth() - getWidth() - margin;
            float[] target = clamp(targetX, getTranslationY() + velocityY * carry, getWidth(), getHeight());
            float fromX = getTranslationX();
            float fromY = getTranslationY();
            float fromAlpha = getAlpha();
            sCardX = target[0];
            sCardY = target[1];
            boolean thrown = Math.hypot(velocityX, velocityY) > 600 * mDensity;
            mMove = ValueAnimator.ofFloat(0f, 1f);
            mMove.setDuration(thrown ? 320 : 240);
            mMove.setInterpolator(thrown ? new OvershootInterpolator(0.9f) : new DecelerateInterpolator(1.8f));
            mMove.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                setTranslationX(fromX + (target[0] - fromX) * f);
                setTranslationY(fromY + (target[1] - fromY) * f);
                setAlpha(fromAlpha + (1f - fromAlpha) * Math.min(1f, a.getAnimatedFraction() * 2f));
            });
            mMove.start();
        }

        // endregion
    }

    // endregion
}
