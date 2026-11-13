package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.phone.widget.RatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

/**
 * Phone player built around SmartTube's playback engine:
 * <ul>
 *     <li>Portrait: video on top with phone controls, video details below</li>
 *     <li>Landscape: full screen video with the same minimal controls (no TV overlay)</li>
 *     <li>Shorts: full screen vertical video, swipe up/down for next/previous, tap to pause</li>
 * </ul>
 */
public class PhonePlaybackActivity extends PlaybackActivity implements PhonePlaybackFragment.Host, FeedAdapter.Listener {
    private static final long HIDE_CONTROLS_MS = 3_500;
    private static final long TICK_MS = 500;
    private static final long SHORT_TICK_MS = 50;
    private static final long DOUBLE_TAP_MS = 230;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private PhonePlaybackFragment mPlayer;
    private View mRoot;
    private RatioFrameLayout mVideoBox;
    private RecyclerView mDetails;
    private FeedAdapter mAdapter;
    private View mControls;
    private ImageView mPlayButton;
    private ImageView mFullscreenButton;
    private TextView mControlsTitle;
    private TextView mTime;
    private SeekBar mSeek;
    private ProgressBar mMiniProgress;
    private View mHeader;
    private View mShorts;
    private View mShortsPaused;
    private boolean mPortrait = true;
    private boolean mShortsMode;
    private boolean mIsSeeking;
    private Boolean mRouteTouchToPlayer;
    private static final int LEVEL_NONE = 0;
    private static final int LEVEL_BRIGHTNESS = 1;
    private static final int LEVEL_VOLUME = 2;
    /** Not a level: a swipe down in the middle of the full screen video, which leaves full screen */
    private static final int LEVEL_EXIT = 3;
    private int mLevelMode = LEVEL_NONE;
    private float mLevelStartX;
    private float mLevelStartY;
    private float mLevelStartValue;
    private View mLevelView;
    private ImageView mLevelIcon;
    private ProgressBar mLevelBar;
    private TextView mLevelText;
    private final Runnable mHideLevel = () -> {
        if (mLevelView != null) {
            mLevelView.animate().alpha(0f).setDuration(200).withEndAction(() -> mLevelView.setVisibility(View.GONE)).start();
        }
    };
    private boolean mTouchStartedOnVideo;
    private int mStatusInset;
    private boolean mDescriptionExpanded;
    private Video mLastScrollEndVideo;
    private ImageView mPoster;
    private ImageView mIncomingPoster; // the next Short's picture while swiping
    private android.widget.ProgressBar mLoading;
    private boolean mAwaitingNewVideo;
    private String mExpectedVideoId; // the Short a swipe is opening
    private Video mSwipedTo; // the Short a swipe opened, until SmartTube's presenter reports it too
    private long mPosterSince;
    private final java.util.List<Video> mShortsHistory = new java.util.ArrayList<>();
    private android.content.BroadcastReceiver mPlayPauseReceiver;
    private static final String ACTION_PLAY_PAUSE = "org.smarttube.phone.PLAY_PAUSE";
    private static final String NOTIFICATION_CHANNEL = "phone_playback";
    private static final int NOTIFICATION_ID = 4711;
    private android.view.ScaleGestureDetector mPinchDetector;
    private float mPinchScale = 1f;
    private boolean mPinching;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            // A Short's picture is still over the video: look often, so the video shows the moment it plays
            boolean waitingForShort = mShortsMode && mPoster != null && mPoster.getVisibility() == View.VISIBLE;
            mHandler.postDelayed(this, waitingForShort ? SHORT_TICK_MS : TICK_MS);
        }
    };

    private final Runnable mAutoHide = () -> {
        if (mPlayer != null && mPlayer.getPlayWhenReady() && !mIsSeeking) {
            showPhoneControls(false);
        }
    };

    @Override
    protected void attachBaseContext(android.content.Context context) {
        super.attachBaseContext(PhoneTheme.wrap(context));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // inflates the phone fragment_playback layout (TV player keeps TV density)
        sInstance = new java.lang.ref.WeakReference<>(this);
        registerPipReceiver();
        PhoneTheme.apply(this); // details panel follows the Appearance setting
        PhoneUi.applyPhoneDensity(this); // phone views use the phone's real density

        Fragment fragment = getSupportFragmentManager().findFragmentByTag(getString(R.string.playback_tag));
        if (fragment instanceof PhonePlaybackFragment) {
            mPlayer = (PhonePlaybackFragment) fragment;
        }

        // Open/close like a sheet sliding up from the app, not a new page
        getWindow().setWindowAnimations(R.style.PhonePlayerAnimation);

        mRoot = findViewById(R.id.phone_player_root);
        mVideoBox = findViewById(R.id.phone_video_box);
        mDetails = findViewById(R.id.phone_player_details);

        // Inflated here (not in the layout) so they get the phone density, not the TV one
        LayoutInflater inflater = LayoutInflater.from(this);
        inflater.inflate(R.layout.phone_mini_progress, mVideoBox, true);
        inflater.inflate(R.layout.phone_player_controls, mVideoBox, true);
        inflater.inflate(R.layout.phone_shorts_overlay, mVideoBox, true);
        createLevelIndicator();

        // The tapped video's thumbnail fills the player until playback starts: the open feels continuous
        mPoster = new ImageView(this);
        mPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mPoster.setBackgroundColor(Color.BLACK);
        mVideoBox.addView(mPoster, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mIncomingPoster = new ImageView(this);
        mIncomingPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mIncomingPoster.setBackgroundColor(Color.BLACK);
        mIncomingPoster.setVisibility(View.GONE);
        mVideoBox.addView(mIncomingPoster, 2, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Spinning circle over the thumbnail while the video loads, and while it buffers
        mLoading = new android.widget.ProgressBar(this);
        mLoading.setIndeterminate(true);
        mLoading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mLoading.setVisibility(View.GONE);
        mLoading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int spinner = dp(44);
        mVideoBox.addView(mLoading, 3, new FrameLayout.LayoutParams(spinner, spinner, Gravity.CENTER));
        showPoster(presenter().getVideo());

        setupNotification();
        allowLockScreen();

        mPinchDetector = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                mPinchScale = 1f;
                return true;
            }

            @Override
            public boolean onScale(android.view.ScaleGestureDetector detector) {
                mPinchScale *= detector.getScaleFactor();
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                if (mPlayer == null) {
                    return;
                }
                if (mPinchScale > 1.08f) {
                    mPlayer.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    showHint(R.string.phone_zoom_fill);
                } else if (mPinchScale < 0.92f) {
                    mPlayer.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    showHint(R.string.phone_zoom_fit);
                }
            }
        });

        mControls = findViewById(R.id.phone_controls);
        mPlayButton = findViewById(R.id.phone_ctl_play);
        mFullscreenButton = findViewById(R.id.phone_ctl_fullscreen);
        mControlsTitle = findViewById(R.id.phone_ctl_title);
        mTime = findViewById(R.id.phone_ctl_time);
        mSeek = findViewById(R.id.phone_ctl_seek);
        mMiniProgress = findViewById(R.id.phone_mini_progress);
        mShorts = findViewById(R.id.phone_shorts);
        mShortsPaused = findViewById(R.id.phone_shorts_paused);

        setupControls();
        setupDetails();
        setupShorts();


        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            mStatusInset = insets.getSystemWindowInsetTop();
            mNavInset = insets.getSystemWindowInsetBottom();
            applyLayoutMode();
            return insets;
        });

        mPortrait = getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        Video video = presenter().getVideo();
        mShortsMode = isShort(video);
        applyLayoutMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mPipEnterPending = false;
        mAppLeaving = false;
        backFromMiniPlayer();
        moveBackToHomeTaskIfNeeded();
        allowLockScreen();
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this);
        cancelPlaybackNotification();
        applySystemBars();
        refreshPlaybackMode(); // the playback mode sheet may have changed it
        mHandler.removeCallbacks(mTicker);
        mHandler.post(mTicker);
        refreshDetails();
        mPipState = null;
        updatePipParams(); // Android 12+: arms the automatic floating window for the home gesture
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        finishPageAnimation();
        mHandler.removeCallbacks(mTicker);
        mHandler.removeCallbacks(mAutoHide);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStopped = false;
        boolean keptForLock = mKeptForLock;
        mKeptForLock = false;
        if (isPip()) {
            // Phone unlocked with the floating window still open: the window has the controls again, and closing it
            // stops the video as before
            cancelPlaybackNotification();
            if (keptForLock && mPlayer != null) {
                mPlayer.blockEngine(false);
            }
        }
    }

    @Override
    protected void onStop() {
        boolean screenOff = com.liskovsoft.smartyoutubetv2.common.utils.Utils.isHardScreenOff(this);
        if (screenOff && !mShortsMode && mPlayer != null && mPlayer.getPlayWhenReady() && !isChangingConfigurations()
                && com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this).getBackgroundMode() != PlayerConstants.BACKGROUND_MODE_DEFAULT) {
            // Phone locked: keep the player alive so the video goes on playing (also from the floating window, which
            // otherwise releases the player when it stops)
            mKeptForLock = !mPlayer.isEngineBlocked();
            mPlayer.blockEngine(true);
        }
        boolean closedPip = mLeftPip && !isChangingConfigurations() && !screenOff;
        super.onStop();
        mStopped = true;
        mLeftPip = false;
        if (closedPip && mPlayer != null) {
            // The floating window was swiped away (it left picture in picture and stopped, without coming back): the
            // video stops, like the YouTube app
            mPlayer.setPlayWhenReady(false);
            closePlayer();
            return;
        }
        if (mMinimized) {
            // Behind the app now, the mini player shows the video: back to the full layout for the next open
            mMinimizing = false;
            resetShrink();
            setSeeThrough(false);
            if (mDetails != null) {
                mDetails.setAlpha(1f);
            }
        }
        if ((isPip() && !screenOff) || isChangingConfigurations() || mPlayer == null) {
            return;
        }
        if (mShortsMode) {
            // No background playback for Shorts
            mPlayer.setPlayWhenReady(false);
        } else if (mPlayer.getPlayWhenReady() && !isFinishing()) {
            // Home pressed or phone locked: media controls in the notification shade and on the lock screen
            showPlaybackNotification();
        }
    }

    /**
     * Locking the phone must lock it: the player never shows over the lock screen or wakes the screen up (SmartTube
     * for TV turns those on to come to the front for casting). The video keeps playing in the background and the lock
     * screen shows its media controls.
     */
    @SuppressWarnings("deprecation")
    private void allowLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false);
            setTurnScreenOn(false);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }

    @Override
    protected void onDestroy() {
        if (sInstance != null && sInstance.get() == this) {
            sInstance = null;
        }
        unregisterPipReceiver();
        cancelPlaybackNotification();
        if (mPlayPauseReceiver != null) {
            try {
                unregisterReceiver(mPlayPauseReceiver);
            } catch (IllegalArgumentException ignored) {
                // not registered
            }
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemBars();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this);
        boolean portrait = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (portrait != mPortrait) {
            mPortrait = portrait;
            if (mVideoBox != null) {
                // A swipe into or out of full screen may still be letting the video settle
                mVideoBox.animate().cancel();
                mVideoBox.setScaleX(1f);
                mVideoBox.setScaleY(1f);
            }
            applyLayoutMode();
            applySystemBars();
        }
    }

    @Override
    public void onBackPressed() {
        if (!mPortrait && !isPip()) {
            // Leave full screen first, like the YouTube app
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            return;
        }
        if (!isPip() && !mShortsMode && canMinimizeToCard()) {
            minimizeToCard(); // the video goes on in the mini player over the app
            return;
        }
        if (!isPip() && !mShortsMode && mPlayer != null && mPlayer.getPlayWhenReady()) {
            // Android 10 and older: shrink to the floating window instead of stopping
            enterMiniPlayer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        mPipEnterPending = false;
        if (isInPictureInPictureMode) {
            if (mPlayer != null) {
                mPlayer.blockEngine(true); // the floating window plays on while the app is in the background
            }
            getViewManager().blockTop(this); // back in the app: this player, not the screen under it
            mPipState = null;
            updatePipParams(); // its play/pause button
            showPhoneControls(false);
            // Android animates the floating window out of the video exactly where the drag left it. Moving the video
            // back (or relaying out, or turning the window opaque, which recreates its surface) during that animation
            // shows black inside the window: tidy up only once the window has its floating size.
            afterPipResize(() -> {
                if (isPip()) {
                    resetShrink(); // the floating window shows the plain video
                    applyLayoutMode();
                }
            });
        } else {
            mLeftPip = true; // expanded (onResume follows) or closed (onStop follows)
            setSeeThrough(false); // opaque again for the full player (stayed translucent in the floating window)
            resetShrink();
            applyLayoutMode();
        }
    }

    /**
     * Runs the action once the player window has been resized into the floating window (its size changes), or after
     * the enter animation at the latest.
     */
    private void afterPipResize(Runnable action) {
        View root = getWindow().getDecorView();
        int startWidth = root.getWidth();
        int startHeight = root.getHeight();
        boolean[] done = {false};
        Runnable once = () -> {
            if (!done[0]) {
                done[0] = true;
                action.run();
            }
        };
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != startWidth || b - t != startHeight) {
                    root.removeOnLayoutChangeListener(this);
                    // A frame later: the floating window shows its first frame at the new size before we move things
                    root.post(once);
                }
            }
        };
        root.addOnLayoutChangeListener(listener);
        mHandler.postDelayed(() -> {
            root.removeOnLayoutChangeListener(listener);
            once.run();
        }, 700);
    }

    // region One task with Home

    private static java.lang.ref.WeakReference<PhonePlaybackActivity> sInstance;
    private boolean mLeftPip;

    /**
     * Android moves a picture in picture window into a task of its own. Reusing that player for the next video
     * would open it from the floating window (not from the tapped thumbnail) and leave a second card in recents.
     * Called right before a video opens from Home, Search or a channel: close the detached player so a fresh one
     * expands from the thumbnail inside Home's task.
     */
    static void releaseDetachedPlayer() {
        PhonePlaybackActivity activity = sInstance != null ? sInstance.get() : null;
        if (activity == null || activity.isFinishing() || (!activity.isPip() && !activity.isInOwnTask())) {
            return;
        }
        activity.closePlayer();
    }

    private boolean isInOwnTask() {
        return PhoneBrowseActivity.sTaskId != -1 && getTaskId() != PhoneBrowseActivity.sTaskId;
    }

    private void closePlayer() {
        cancelPlaybackNotification();
        // A detached player closing itself must not reopen Home: that would also close the screen the user is on
        // (search, a channel...)
        getViewManager().skipNextParentStart();
        if (mPlayer != null) {
            mPlayer.finishReally();
        } else {
            finishReally();
        }
        com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(this).setView(null);
    }

    /**
     * The floating window was expanded but the player still sits in its own task (some phones don't move it
     * back): reopen the video inside Home's task so there's one SmartTube card in recents.
     */
    private void moveBackToHomeTaskIfNeeded() {
        if (!mLeftPip) {
            return;
        }
        mLeftPip = false;
        if (!isInOwnTask() || isFinishing() || mPlayer != null) {
            // The player keeps playing where it is: closing it and opening the video again would stop the picture
            // and load everything again. Only a player screen without its player is reopened.
            return;
        }
        Video video = currentVideo();
        if (video == null) {
            return;
        }
        android.content.Context app = getApplicationContext();
        closePlayer();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(app).openVideo(video), 300);
    }

    // endregion

    private boolean mStopped;
    private boolean mKeptForLock; // the player was kept alive only because the phone was locked

    /**
     * Where a bottom sheet opened over the player may start (the bottom of the video, in screen pixels), so menus and
     * comments never cover the video in portrait. 0 when the player isn't on screen in portrait.
     */
    static int sheetTopLimit() {
        PhonePlaybackActivity activity = sInstance != null ? sInstance.get() : null;
        if (activity == null || activity.isFinishing() || activity.mStopped || activity.isPip() || !activity.mPortrait
                || activity.mShortsMode || activity.mVideoBox == null || activity.mVideoBox.getHeight() == 0) {
            return 0;
        }
        int[] location = new int[2];
        activity.mVideoBox.getLocationOnScreen(location);
        return location[1] + activity.mVideoBox.getHeight();
    }

    private boolean isPip() {
        // From the moment Android accepted the floating window: the screen is already only the video
        return mPipEnterPending || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode());
    }

    // region Picture in picture when leaving the app (as in NewTube and the YouTube app)

    private static final String ACTION_PIP_TOGGLE = "org.smarttube.phone.PIP_TOGGLE";
    private boolean mResumed;
    /** Between Android accepting the floating window and saying it's in it (a few frames) */
    private boolean mPipEnterPending;
    private String mPipState;
    private android.content.BroadcastReceiver mPipReceiver;

    /** Settings > Picture in picture (on by default). SmartTube's own background mode stays "sound only". */
    static boolean pipOnLeave(android.content.Context context) {
        return Build.VERSION.SDK_INT >= 26 && context.getSharedPreferences("phone_defaults", MODE_PRIVATE).getBoolean("pip_on_leave", true);
    }

    static void setPipOnLeave(android.content.Context context, boolean on) {
        context.getSharedPreferences("phone_defaults", MODE_PRIVATE).edit().putBoolean("pip_on_leave", on).apply();
    }

    /**
     * Leaving the app now opens the floating window: the full player is on screen (not on its way to or in the mini
     * player, not Shorts) and a video plays.
     */
    private boolean canEnterPip() {
        return Build.VERSION.SDK_INT >= 26 && mResumed && !isFinishing() && !mAppLeaving && !mShortsMode
                && !isShort(currentVideo()) && !mMinimized && !mMinimizing
                && !mShrinkDragging && mPlayer != null && mPlayer.getPlayWhenReady() && pipOnLeave(this)
                && PhonePip.systemAllows(this)
                && !(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode())
                && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /**
     * Keeps Android told what the floating window looks like (the video's shape, growing out of the video, a play /
     * pause button) and, on Android 12+, whether to open it on its own when the app is left: Android reads this at
     * that moment, there is no callback to set it in.
     */
    private void updatePipParams() {
        if (Build.VERSION.SDK_INT < 26 || isFinishing() || isDestroyed()) {
            return;
        }
        boolean auto = canEnterPip();
        boolean playing = mPlayer != null && mPlayer.getPlayWhenReady();
        Rect video = isPip() ? null : videoRectOnScreen();
        String state = auto + " " + playing + " " + pipAspect() + " " + (video != null ? video.flattenToString() : "");
        if (state.equals(mPipState)) {
            return;
        }
        mPipState = state;
        android.util.Log.d("PhonePip", "player window " + (auto ? "armed" : "off") + " (resumed " + mResumed + ", shorts "
                + mShortsMode + ", mini " + mMinimized + ", playing " + playing + ", setting " + pipOnLeave(this)
                + ", android allows " + PhonePip.systemAllows(this) + ")");
        try {
            setPictureInPictureParams(pipParams(auto, video));
        } catch (RuntimeException e) {
            android.util.Log.d("PhonePip", "params: " + e); // no picture in picture on this phone
        }
    }

    @androidx.annotation.RequiresApi(26)
    private android.app.PictureInPictureParams pipParams(boolean auto, Rect video) {
        android.app.PictureInPictureParams.Builder builder = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(pipAspect());
        if (video != null && !video.isEmpty()) {
            builder.setSourceRectHint(video);
        }
        builder.setActions(java.util.Collections.singletonList(playPauseAction()));
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: the floating window opens on its own when the user leaves the app (newer Android versions
            // no longer honour entering it from onUserLeaveHint with gesture navigation). Armed only while a regular
            // video plays on the full player, and disarmed before any screen the app opens itself (see
            // startActivityForResult and the launch flags in PhoneApplication), so in-app screen changes never open it.
            builder.setAutoEnterEnabled(auto);
            builder.setSeamlessResizeEnabled(false); // a cross-fade instead of half drawn frames while resizing
        }
        return builder.build();
    }

    /** The floating window's play / pause button */
    @androidx.annotation.RequiresApi(26)
    private android.app.RemoteAction playPauseAction() {
        boolean playing = mPlayer != null && mPlayer.getPlayWhenReady();
        android.app.PendingIntent toggle = android.app.PendingIntent.getBroadcast(this, 4721,
                new android.content.Intent(ACTION_PIP_TOGGLE).setPackage(getPackageName()),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        String label = getString(R.string.phone_mini_play_pause);
        return new android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play),
                label, label, toggle);
    }

    /** The video's own shape, within what Android accepts for the floating window */
    private android.util.Rational pipAspect() {
        com.google.android.exoplayer2.SimpleExoPlayer exo = exoPlayer();
        com.google.android.exoplayer2.Format format = exo != null ? exo.getVideoFormat() : null;
        if (format == null || format.width <= 0 || format.height <= 0) {
            return mShortsMode ? new android.util.Rational(9, 16) : new android.util.Rational(16, 9);
        }
        float ratio = (float) format.width / format.height;
        if (ratio < 0.5f) {
            return new android.util.Rational(1, 2);
        }
        if (ratio > 2.3f) {
            return new android.util.Rational(23, 10);
        }
        return new android.util.Rational(format.width, format.height);
    }

    /**
     * Set while this screen opens another one itself (Home for the mini player, a channel, comments, the share
     * sheet...): Android reports that as the user leaving too, and it must not open the floating window.
     */
    private boolean mAppLeaving;
    private final Runnable mClearAppLeaving = () -> mAppLeaving = false;

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode, Bundle options) {
        mAppLeaving = true;
        mHandler.removeCallbacks(mClearAppLeaving);
        mHandler.postDelayed(mClearAppLeaving, 1500);
        updatePipParams(); // disarms the automatic floating window before Android moves this screen back
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        }
        super.startActivityForResult(intent, requestCode, options);
    }

    /** Home pressed or the app switched away from: the floating window (regular videos only, never Shorts) */
    @Override
    public void onUserLeaveHint() {
        // Android 12+ opens the window itself (automatic entry, armed in updatePipParams); older versions from here
        if (Build.VERSION.SDK_INT < 31 && !mAppLeaving && !getViewManager().isNewViewPending() && canEnterPip()
                && enterFloatingWindow()) {
            return;
        }
        super.onUserLeaveHint(); // as before: SmartTube's background mode (sound only)
    }

    private void registerPipReceiver() {
        if (Build.VERSION.SDK_INT < 26 || mPipReceiver != null) {
            return;
        }
        mPipReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (mPlayer != null) {
                    mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
                    updatePlayButton();
                }
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(this, mPipReceiver,
                new android.content.IntentFilter(ACTION_PIP_TOGGLE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterPipReceiver() {
        if (mPipReceiver != null) {
            try {
                unregisterReceiver(mPipReceiver);
            } catch (RuntimeException ignored) {
                // not registered
            }
            mPipReceiver = null;
        }
    }

    // endregion

    private static boolean isShort(Video video) {
        return video != null && (video.isShorts || video.belongsToShorts() || PhoneShortsQueue.contains(video));
    }

    // region Layout modes

    private void applyLayoutMode() {
        if (mVideoBox == null) {
            return;
        }

        boolean pip = isPip();
        boolean shorts = mShortsMode && !pip;
        boolean withDetails = mPortrait && !shorts && !pip;

        mVideoBox.setRatioEnabled(withDetails);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mVideoBox.getLayoutParams();
        params.height = withDetails ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
        params.weight = withDetails ? 0 : 1;
        mVideoBox.setLayoutParams(params);

        mDetails.setVisibility(withDetails ? View.VISIBLE : View.GONE);
        // Shorts have their own progress line (with a red dot) above their tabs
        mMiniProgress.setVisibility(mPortrait && !pip && !shorts ? View.VISIBLE : View.GONE);
        mRoot.setPadding(0, withDetails ? mStatusInset : 0, 0, 0);

        mShorts.setVisibility(shorts ? View.VISIBLE : View.GONE);
        if (shorts) {
            layoutShorts();
        }

        mControlsTitle.setVisibility(mPortrait ? View.GONE : View.VISIBLE);
        if (mPortrait && mPlayer != null) {
            mPlayer.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        // Full screen: keep the seek bar and time clear of the screen edges
        View bottom = findViewById(R.id.phone_ctl_bottom);
        int side = mPortrait ? 0 : dp(28);
        bottom.setPadding(side, 0, side, mPortrait ? 0 : dp(48));
        mFullscreenButton.setImageResource(mPortrait ? R.drawable.ic_phone_fullscreen : R.drawable.ic_phone_fullscreen_exit);

        if (shorts || pip) {
            showPhoneControls(false);
        }

        if (mPlayer != null && !pip) {
            mPlayer.hideTvOverlay();
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        Window window = getWindow();
        boolean fullscreen = !mPortrait;

        if (!fullscreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(mShortsMode ? Color.TRANSPARENT : Color.BLACK);
            window.setNavigationBarColor(mShortsMode ? Color.BLACK : getResources().getColor(R.color.phone_bg));
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.systemBars());
                    controller.setSystemBarsAppearance(
                            !mShortsMode && !PhoneTheme.isNight(this) ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } else {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
        mRoot.requestApplyInsets();
    }

    private void toggleFullscreen() {
        showPhoneControls(false);
        setRequestedOrientation(mPortrait
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    // endregion

    // region Touch routing

    /**
     * The TV player listens to every touch on the screen. Only touches on the bare video reach it (tap shows the
     * controls, double tap seeks); the details list, the phone controls and the Shorts overlay handle their own.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mMinimizing) {
            return true; // on its way into the mini player
        }
        if (mVideoBox == null || isPip()) {
            return super.dispatchTouchEvent(event);
        }

        // Full screen: pinch to fill the screen or fit the whole video
        if (!mPortrait && !mShortsMode && mPinchDetector != null) {
            mPinchDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1 || mPinchDetector.isInProgress()) {
                if (!mPinching) {
                    mPinching = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    routeTouch(cancel);
                    cancel.recycle();
                }
                mLevelMode = LEVEL_NONE;
                return true;
            }
            if (mPinching) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mPinching = false;
                }
                return true;
            }
        }

        // Full screen: swipe up/down on the left for brightness, on the right for volume
        if (!mPortrait && !mShortsMode && handleLevelGesture(event)) {
            return true;
        }

        return routeTouch(event);
    }

    private float mDragStartY;
    private float mDragStartX;
    private long mDragStartTime;

    /** Whatever got this touch so far (the player, the controls, the list) stops handling it: a gesture took over */
    private void cancelRoutedTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        if (Boolean.TRUE.equals(mRouteTouchToPlayer)) {
            super.dispatchTouchEvent(cancel);
        } else {
            getWindow().superDispatchTouchEvent(cancel);
        }
        cancel.recycle();
        mRouteTouchToPlayer = null;
    }

    private boolean mFullscreenSwipe;

    /**
     * Portrait: swipe up on the video for full screen (the video grows a little under the finger). The start of the
     * touch is the one the drag down remembers.
     */
    private boolean handleFullscreenSwipe(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mFullscreenSwipe = false;
            return false;
        }
        float up = mDragStartY - event.getRawY();
        float dx = Math.abs(event.getRawX() - mDragStartX);
        if (action == MotionEvent.ACTION_MOVE) {
            if (!mFullscreenSwipe) {
                int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2;
                if (up <= slop || up <= dx * 1.5f || mShrinkDragging) {
                    return false;
                }
                mFullscreenSwipe = true;
                cancelRoutedTouch(event);
                showPhoneControls(false);
            }
            float pull = Math.max(0f, Math.min(1f, up / dp(160)));
            mVideoBox.setScaleX(1f + 0.06f * pull);
            mVideoBox.setScaleY(1f + 0.06f * pull);
            return true;
        }
        if (!mFullscreenSwipe) {
            return false;
        }
        mFullscreenSwipe = false;
        mRouteTouchToPlayer = null;
        mVideoBox.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        if (action == MotionEvent.ACTION_UP && up > dp(56)) {
            toggleFullscreen();
        }
        return true;
    }

    private boolean mLineScrub;
    private boolean mLineCandidate;
    private float mLineStartX;
    private float mLineStartY;

    /**
     * A sideways drag that starts on the progress line (the bottom edge of the video) seeks right away: the controls
     * come up showing where it is going, and letting go jumps there. A tap or a vertical drag there stays what it was.
     */
    private boolean handleLineScrub(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mLineScrub = false;
            Rect box = new Rect();
            mVideoBox.getGlobalVisibleRect(box);
            float strip = dp(mPortrait ? 30 : 64);
            mLineCandidate = mControls.getVisibility() != View.VISIBLE && mPlayer != null && mPlayer.getDurationMs() > 0
                    && event.getRawY() > box.bottom - strip && event.getRawY() <= box.bottom;
            mLineStartX = event.getRawX();
            mLineStartY = event.getRawY();
            return false;
        }
        if (!mLineCandidate && !mLineScrub) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!mLineScrub) {
                float dx = Math.abs(event.getRawX() - mLineStartX);
                float dy = Math.abs(event.getRawY() - mLineStartY);
                int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
                if (dy > slop * 2) {
                    mLineCandidate = false; // going up or down: another gesture's
                    return false;
                }
                if (dx <= slop || dx <= dy) {
                    return false;
                }
                mLineScrub = true;
                mLineCandidate = false;
                cancelRoutedTouch(event);
                showPhoneControls(true);
                mIsSeeking = true;
                mHandler.removeCallbacks(mAutoHide);
            }
            scrubTo(event.getRawX());
            return true;
        }
        mLineCandidate = false;
        if (!mLineScrub) {
            return false;
        }
        mLineScrub = false;
        mRouteTouchToPlayer = null;
        mIsSeeking = false;
        if (action == MotionEvent.ACTION_UP && mPlayer != null) {
            long duration = mPlayer.getDurationMs();
            if (duration > 0) {
                mPlayer.setPositionMs(duration * mSeek.getProgress() / 1000);
            }
        }
        scheduleAutoHide();
        return true;
    }

    private void scrubTo(float rawX) {
        Rect box = new Rect();
        mVideoBox.getGlobalVisibleRect(box);
        int progress = Math.max(0, Math.min(1000, Math.round((rawX - box.left) * 1000f / Math.max(1, box.width()))));
        mSeek.setProgress(progress);
        mMiniProgress.setProgress(progress);
        if (mPlayer != null) {
            long duration = mPlayer.getDurationMs();
            updateTimeText(duration * progress / 1000, duration);
        }
    }

    /** True when a quick downward drag that started on the video ends (YouTube: drag the player down) */
    private boolean mShrinkDragging;
    private android.animation.ValueAnimator mShrinkAnimator;

    /**
     * Drag the video down: it moves and shrinks with the finger while the details fade, then becomes the floating
     * mini player (picture in picture) from exactly where it is, or springs back if the drag was short.
     */
    private boolean handleShrinkDrag(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            trackCloseDrag(event);
            mShrinkDragging = false;
            return false;
        }
        float dy = event.getRawY() - mDragStartY;
        float dx = Math.abs(event.getRawX() - mDragStartX);
        if (action == MotionEvent.ACTION_MOVE) {
            if (!mShrinkDragging) {
                int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2;
                if (dy > slop && dy > dx * 1.5f) {
                    mShrinkDragging = true;
                    if (mShrinkAnimator != null) {
                        mShrinkAnimator.cancel();
                    }
                    // The player and the details stop handling this touch (no seek, no tap)
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    if (Boolean.TRUE.equals(mRouteTouchToPlayer)) {
                        super.dispatchTouchEvent(cancel);
                    } else {
                        getWindow().superDispatchTouchEvent(cancel);
                    }
                    cancel.recycle();
                    mRouteTouchToPlayer = null;
                    showPhoneControls(false);
                    setSeeThrough(true); // the screen underneath shows while the video shrinks
                } else {
                    return false;
                }
            }
            setShrinkProgress(Math.max(0, dy));
            return true;
        }
        if (!mShrinkDragging) {
            return false;
        }
        mShrinkDragging = false;
        mRouteTouchToPlayer = null;
        long dt = Math.max(1, event.getEventTime() - mDragStartTime);
        boolean shrink = action == MotionEvent.ACTION_UP && (dy > dp(160) || (dy > dp(60) && dy / dt > 0.6f));
        if (shrink && canMinimizeToCard()) {
            minimizeToCard();
        } else if (shrink) {
            growThenEnterMiniPlayer();
        } else {
            animateShrink(0);
        }
        return true;
    }

    private float mShrinkOffset;

    /**
     * Android animates the floating window out of the video (source rect hint) only when that rectangle is larger
     * than the floating window; a smaller one (a video shrunk by the drag) gets a plain black box that fades to the
     * video once it redraws. So the video first grows back to full width where the drag left it (quickly), then the
     * floating window takes it from there.
     */
    private void growThenEnterMiniPlayer() {
        if (mShrinkAnimator != null) {
            mShrinkAnimator.cancel();
        }
        View root = getWindow().getDecorView();
        float startScale = mVideoBox.getScaleX();
        float startY = mVideoBox.getTranslationY();
        // Full size, as low as the drag left it but still entirely on screen
        float endY = Math.min(startY, Math.max(0, root.getHeight() - mVideoBox.getTop() - mVideoBox.getHeight()));
        mShrinkAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        mShrinkAnimator.setDuration(120);
        mShrinkAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mShrinkAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float scale = startScale + (1f - startScale) * f;
            mVideoBox.setScaleX(scale);
            mVideoBox.setScaleY(scale);
            mVideoBox.setTranslationY(startY + (endY - startY) * f);
        });
        mShrinkAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!mCancelled && !isFinishing()) {
                    enterMiniPlayer();
                }
            }
        });
        mShrinkAnimator.start();
    }

    private void setShrinkProgress(float offset) {
        mShrinkOffset = offset;
        View root = getWindow().getDecorView();
        float travel = Math.max(1, root.getHeight() * 0.55f);
        float progress = Math.min(1f, offset / travel);
        float scale = 1f - 0.45f * progress;
        mVideoBox.setPivotX(mVideoBox.getWidth() / 2f);
        mVideoBox.setPivotY(0);
        mVideoBox.setScaleX(scale);
        mVideoBox.setScaleY(scale);
        mVideoBox.setTranslationY(offset * 0.85f);
        if (mDetails != null) {
            mDetails.setAlpha(Math.max(0f, 1f - progress * 2.2f));
            mDetails.setTranslationY(offset * 0.85f);
        }
    }

    private void animateShrink(float target) {
        if (mShrinkAnimator != null) {
            mShrinkAnimator.cancel();
        }
        mShrinkAnimator = android.animation.ValueAnimator.ofFloat(mShrinkOffset, target);
        mShrinkAnimator.setDuration(220);
        mShrinkAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        mShrinkAnimator.addUpdateListener(a -> setShrinkProgress((float) a.getAnimatedValue()));
        if (target == 0) {
            mShrinkAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!mShrinkDragging) {
                        setSeeThrough(false); // back to the normal opaque player
                    }
                }
            });
        }
        mShrinkAnimator.start();
    }

    private boolean mSeeThrough;

    /**
     * While the video is dragged down, the player window turns translucent so the screen it was opened from (home,
     * search...) shows behind it, like the YouTube app. Android 11+ (Activity.setTranslucent).
     */
    private void setSeeThrough(boolean seeThrough) {
        if (seeThrough == mSeeThrough || Build.VERSION.SDK_INT < 30 || mRoot == null) {
            return;
        }
        mSeeThrough = seeThrough;
        if (seeThrough) {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            mRoot.setBackgroundColor(Color.TRANSPARENT);
            try {
                setTranslucent(true);
            } catch (Throwable ignored) {
                // stays opaque: black behind the video, as before
            }
        } else {
            try {
                setTranslucent(false);
            } catch (Throwable ignored) {
                // already opaque
            }
            mRoot.setBackgroundColor(Color.BLACK);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        }
    }

    private void resetShrink() {
        if (mShrinkAnimator != null) {
            mShrinkAnimator.cancel();
        }
        mShrinkDragging = false;
        setShrinkProgress(0);
        if (mVideoBox != null) {
            mVideoBox.setTranslationX(0); // the mini player shrink also moves it sideways
        }
    }

    /** Where the video is on screen right now (after the drag transform), for a seamless picture-in-picture start */
    private Rect videoRectOnScreen() {
        if (mVideoBox == null || mVideoBox.getWidth() == 0) {
            return null;
        }
        android.graphics.RectF rect = new android.graphics.RectF(0, 0, mVideoBox.getWidth(), mVideoBox.getHeight());
        mVideoBox.getMatrix().mapRect(rect);
        int[] parent = new int[2];
        ((View) mVideoBox.getParent()).getLocationInWindow(parent);
        rect.offset(parent[0] + mVideoBox.getLeft(), parent[1] + mVideoBox.getTop());
        Rect result = new Rect();
        rect.round(result);
        return result;
    }

    private boolean trackCloseDrag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragStartY = event.getRawY();
                mDragStartX = event.getRawX();
                mDragStartTime = event.getEventTime();
                return false;
            case MotionEvent.ACTION_UP: {
                float dy = event.getRawY() - mDragStartY;
                float dx = Math.abs(event.getRawX() - mDragStartX);
                long dt = Math.max(1, event.getEventTime() - mDragStartTime);
                return dy > dp(100) && dy > dx * 1.5f && (dy / dt > 0.5f || dy > dp(220));
            }
            default:
                return false;
        }
    }

    private boolean routeTouch(MotionEvent event) {

        // Mid drag the finger is often below the shrinking video already: the drag keeps the touch to its end
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || (mRouteTouchToPlayer == null && !mShrinkDragging && !mFullscreenSwipe && !mLineScrub)) {
            Rect box = new Rect();
            mVideoBox.getGlobalVisibleRect(box);
            boolean onVideo = box.contains((int) event.getRawX(), (int) event.getRawY());
            mTouchStartedOnVideo = onVideo;
            mRouteTouchToPlayer = onVideo && !mShortsMode && mControls.getVisibility() != View.VISIBLE;
        }

        // Drag along the progress line at the bottom of the video to seek, without bringing the controls up first
        if (mTouchStartedOnVideo && !mShortsMode && handleLineScrub(event)) {
            return true;
        }

        // Swipe the video up: full screen
        if (mTouchStartedOnVideo && mPortrait && !mShortsMode && handleFullscreenSwipe(event)) {
            return true;
        }

        // Swipe the video down to shrink the player (portrait, regular videos): the video follows the finger
        if (mTouchStartedOnVideo && mPortrait && !mShortsMode && handleShrinkDrag(event)) {
            return true;
        }

        boolean result;
        if (mRouteTouchToPlayer) {
            result = super.dispatchTouchEvent(event);
        } else {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                onUserInteraction();
            }
            result = getWindow().superDispatchTouchEvent(event) || onTouchEvent(event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mRouteTouchToPlayer = null;
        }

        return result;
    }

    // endregion

    private void showPoster(Video video) {
        if (mPoster == null || video == null || mPaging || mPageAnimating) {
            return; // mid swipe the poster belongs to the swipe
        }
        mPoster.animate().cancel();
        mPoster.setAlpha(1f);
        mPoster.setTranslationY(0);
        mPoster.setVisibility(View.VISIBLE);
        mPosterSince = android.os.SystemClock.uptimeMillis();
        mPosterUntilFrame = false; // a new video: black with the spinner until it plays
        com.bumptech.glide.Glide.with(this).clear(mPoster); // no thumbnail, only the loading spinner
        mPoster.setImageDrawable(null);
        updateLoading();
    }

    /**
     * The spinner shows while the video should play but can't yet: its thumbnail still covers it (loading) or it ran out
     * of data (buffering). Not over the controls (their play button sits in the middle), nor in the floating window.
     */
    private void updateLoading() {
        if (mLoading == null) {
            return;
        }
        com.google.android.exoplayer2.SimpleExoPlayer exo = exoPlayer();
        boolean wantsToPlay = exo == null || exo.getPlayWhenReady();
        boolean buffering = exo != null && exo.getPlaybackState() == com.google.android.exoplayer2.Player.STATE_BUFFERING;
        boolean covered = mPoster != null && mPoster.getVisibility() == View.VISIBLE;
        boolean show = wantsToPlay && (covered || buffering) && !isPip() && !mMinimizing && !mMinimized
                && (mControls == null || mControls.getVisibility() != View.VISIBLE);
        if (show == (mLoading.getVisibility() == View.VISIBLE)) {
            return;
        }
        mLoading.animate().cancel();
        if (show) {
            mLoading.setAlpha(0f);
            mLoading.setVisibility(View.VISIBLE);
            mLoading.animate().alpha(1f).setDuration(150).start();
        } else {
            mLoading.setVisibility(View.GONE);
        }
    }

    private void showHint(int textResId) {
        if (mLevelView == null) {
            return;
        }
        mHandler.removeCallbacks(mHideLevel);
        mLevelView.animate().cancel();
        mLevelView.setAlpha(1f);
        mLevelView.setVisibility(View.VISIBLE);
        mLevelIcon.setImageResource(R.drawable.ic_phone_fullscreen);
        mLevelBar.setVisibility(View.GONE);
        mLevelText.setText(textResId);
        mHandler.postDelayed(mHideLevel, 900);
    }

    // region Mini player (in the app)

    /** Shrinking into the mini player (touches ignored) */
    private boolean mMinimizing;
    /** The video plays in the mini player card, this screen is behind the app */
    private boolean mMinimized;
    private String mMinimizedVideoId;
    private com.google.android.exoplayer2.video.VideoListener mFirstFrameListener;

    /** The video in the mini player (its thumbnail when there's no frame to show) */
    Video miniVideo() {
        return currentVideo();
    }

    com.google.android.exoplayer2.SimpleExoPlayer exoPlayer() {
        return mPlayer != null ? mPlayer.getExoPlayer() : null;
    }

    /** X on the mini player card */
    void closeFromMiniPlayer() {
        com.google.android.exoplayer2.SimpleExoPlayer exo = exoPlayer();
        if (exo != null) {
            exo.setPlayWhenReady(false);
        }
        mMinimized = false;
        closePlayer();
    }

    /** Android 11+ (a see-through player window); videos, not Shorts, in the portrait player */
    private boolean canMinimizeToCard() {
        return Build.VERSION.SDK_INT >= 30 && !mShortsMode && mPortrait && !isPip() && !mMinimizing
                && mVideoBox != null && mVideoBox.getWidth() > 0 && exoPlayer() != null && currentVideo() != null;
    }

    /**
     * The video shrinks onto the mini player's spot (bottom right of the screen underneath), then that screen comes to
     * the front with the card already showing this frame. The player keeps playing and draws into the card.
     */
    private void minimizeToCard() {
        if (mShrinkAnimator != null) {
            mShrinkAnimator.cancel();
        }
        mShrinkDragging = false;
        mMinimizing = true;
        showPhoneControls(false);
        setSeeThrough(true);

        Rect from = videoRectOnScreen();
        int[] parent = new int[2];
        ((View) mVideoBox.getParent()).getLocationInWindow(parent);
        float baseLeft = parent[0] + mVideoBox.getLeft();
        float baseTop = parent[1] + mVideoBox.getTop();
        float width = mVideoBox.getWidth();
        float aspect = width / Math.max(1, mVideoBox.getHeight());
        Rect to = PhoneMiniPlayer.dockRect(this, aspect);
        if (from == null) {
            from = new Rect((int) baseLeft, (int) baseTop, (int) (baseLeft + width), (int) (baseTop + mVideoBox.getHeight()));
        }
        float startScale = from.width() / width;
        float startX = from.left - baseLeft;
        float startY = from.top - baseTop;
        float endScale = to.width() / width;
        float endX = to.left - baseLeft;
        float endY = to.top - baseTop;
        float detailsAlpha = mDetails != null ? mDetails.getAlpha() : 0f;

        mVideoBox.setPivotX(0);
        mVideoBox.setPivotY(0);
        mShrinkAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        mShrinkAnimator.setDuration(260);
        mShrinkAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        mShrinkAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float scale = startScale + (endScale - startScale) * f;
            mVideoBox.setScaleX(scale);
            mVideoBox.setScaleY(scale);
            mVideoBox.setTranslationX(startX + (endX - startX) * f);
            mVideoBox.setTranslationY(startY + (endY - startY) * f);
            if (mDetails != null) {
                mDetails.setAlpha(Math.max(0f, detailsAlpha * (1f - f * 2f)));
            }
        });
        mShrinkAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!mCancelled && !isFinishing()) {
                    copyFrame(still -> handOffToCard(still, aspect));
                } else {
                    restoreAfterFailedMinimize();
                }
            }
        });
        mShrinkAnimator.start();
    }

    /** The frame on screen, for the card to show until the video draws there */
    private interface FrameReady {
        void accept(android.graphics.Bitmap frame);
    }

    private void copyFrame(FrameReady done) {
        if (mPlayer != null && mPlayer.usesSharedVideo()) {
            done.accept(mPlayer.videoFrame());
            return;
        }
        View surface = mPlayer != null ? mPlayer.getSurfaceView() : null;
        android.util.Log.d("PhoneMiniPlayer", "frame copy from " + (surface != null ? surface.getClass().getSimpleName()
                + " " + surface.getWidth() + "x" + surface.getHeight() : "nothing"));
        try {
            if (surface instanceof android.view.TextureView) {
                done.accept(((android.view.TextureView) surface).getBitmap());
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && surface instanceof android.view.SurfaceView && surface.getWidth() > 0 && surface.getHeight() > 0
                    && ((android.view.SurfaceView) surface).getHolder().getSurface().isValid()) {
                float shrink = Math.min(1f, 640f / surface.getWidth());
                android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(Math.max(1, Math.round(surface.getWidth() * shrink)),
                        Math.max(1, Math.round(surface.getHeight() * shrink)), android.graphics.Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request((android.view.SurfaceView) surface, frame, result -> {
                    android.util.Log.d("PhoneMiniPlayer", "frame copy: " + result);
                    done.accept(result == android.view.PixelCopy.SUCCESS ? frame : null);
                }, mHandler);
                return;
            }
        } catch (RuntimeException e) {
            android.util.Log.d("PhoneMiniPlayer", "frame copy failed: " + e);
        }
        done.accept(null);
    }

    /** Something went wrong on the way into the card: the full player again */
    private void restoreAfterFailedMinimize() {
        mMinimizing = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        resetShrink();
        setSeeThrough(false);
        if (mDetails != null) {
            mDetails.setAlpha(1f);
        }
    }

    private void handOffToCard(android.graphics.Bitmap still, float aspect) {
        if (isFinishing() || isDestroyed() || exoPlayer() == null) {
            restoreAfterFailedMinimize();
            return;
        }
        Video video = currentVideo();
        mMinimizedVideoId = video != null ? video.videoId : null;
        mMinimized = true;
        updatePipParams(); // not into the floating window on the way to the mini player
        if (mPlayer.usesSharedVideo()) {
            // The card shows the video's texture from now on: this screen lets go of it (its last frame stays up here)
            if (still != null && mPoster != null) {
                mPoster.animate().cancel();
                mPoster.setImageBitmap(still);
                mPoster.setAlpha(1f);
                mPoster.setVisibility(View.VISIBLE);
            }
            mPlayer.detachVideo();
        }
        PhoneMiniPlayer.begin(this, still, aspect);
        mPlayer.blockEngine(true); // keeps playing behind the app
        getViewManager().removeTop(this); // Back on the screen underneath doesn't come back here
        Class<? extends android.app.Activity> host = PhoneMiniPlayer.hostClass();
        startActivity(new android.content.Intent(this, host != null ? host : PhoneBrowseActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
    }

    /** Opened again from the card (or for another video): the video comes back here, from the card's last frame */
    private void backFromMiniPlayer() {
        if (!mMinimized) {
            return;
        }
        mMinimized = false;
        mMinimizing = false;
        android.graphics.Bitmap still = PhoneMiniPlayer.takeStill();
        PhoneMiniPlayer.end();
        setSeeThrough(false);
        resetShrink();
        if (mDetails != null) {
            mDetails.setAlpha(1f);
        }
        Video video = currentVideo();
        boolean sameVideo = video != null && mMinimizedVideoId != null && mMinimizedVideoId.equals(video.videoId);
        com.google.android.exoplayer2.SimpleExoPlayer exo = exoPlayer();
        if (mPlayer != null && mPlayer.usesSharedVideo()) {
            // The video's texture comes back to this screen (the decoder kept drawing into it: nothing restarts).
            // The card's last frame covers the video box until the view has drawn it.
            if (sameVideo && still != null && mPoster != null) {
                mPoster.animate().cancel();
                mPoster.setImageBitmap(still);
                mPoster.setAlpha(1f);
                mPoster.setVisibility(View.VISIBLE);
            }
            // Whatever covers the video box now (this frame, or the one left at the hand-off) lifts at the first frame
            mPosterUntilFrame = mPoster != null && mPoster.getVisibility() == View.VISIBLE;
            PhoneMiniPlayer.letGoOfVideo(); // came back from a notification or recents: the card may still show it
            mPlayer.reattachVideo(this::uncoverVideo);
            mHandler.postDelayed(this::uncoverVideo, 250); // paused: no new frame comes, the texture has the last one
            return;
        }
        if (!sameVideo || still == null || mPoster == null || exo == null) {
            return; // another video: its thumbnail shows until it plays
        }
        // The card's frame until the player draws here again (no black frame)
        mPoster.animate().cancel();
        mPoster.setImageBitmap(still);
        mPoster.setAlpha(1f);
        mPoster.setVisibility(View.VISIBLE);
        mPosterUntilFrame = true;
        if (mFirstFrameListener != null) {
            exo.removeVideoListener(mFirstFrameListener);
        }
        mFirstFrameListener = new com.google.android.exoplayer2.video.VideoListener() {
            @Override
            public void onRenderedFirstFrame() {
                exo.removeVideoListener(this);
                if (mFirstFrameListener == this) {
                    mFirstFrameListener = null;
                }
                uncoverVideo();
            }
        };
        exo.addVideoListener(mFirstFrameListener);
        // Paused videos may not draw a new frame: the still stays until they play
        mHandler.postDelayed(() -> {
            com.google.android.exoplayer2.SimpleExoPlayer player = exoPlayer();
            if (mPosterUntilFrame && player != null && player.getPlayWhenReady()) {
                uncoverVideo();
            }
        }, 800);
    }

    /** The poster waits for the video's first frame on this screen's surface */
    private boolean mPosterUntilFrame;

    private void uncoverVideo() {
        if (!mPosterUntilFrame || mPoster == null) {
            return;
        }
        mPosterUntilFrame = false;
        mPoster.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                mPoster.setVisibility(View.GONE);
                updateLoading();
            }).start();
    }

    // endregion

    // region Picture-in-picture

    /**
     * Drag the player down: keep watching in a floating window and go back to the app.
     */
    private void enterMiniPlayer() {
        if (!enterFloatingWindow()) {
            finish();
        }
    }

    /**
     * Android's floating window, the way the earlier builds entered it (the one that worked without glitches): straight
     * from the video as it is on screen, the layout tidied up only once the window has its small size (see
     * onPictureInPictureModeChanged). Plus a play/pause button in the window.
     */
    private boolean enterFloatingWindow() {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }
        try {
            android.app.PictureInPictureParams.Builder builder = new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(mShortsMode ? new android.util.Rational(9, 16) : new android.util.Rational(16, 9));
            // The floating window grows out of the video itself (where the drag left it), not the whole screen
            Rect hint = videoRectOnScreen();
            if (hint != null && !hint.isEmpty()) {
                builder.setSourceRectHint(hint);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                // Video doesn't resize seamlessly: Android cross-fades instead of showing half-drawn (black) frames
                builder.setSeamlessResizeEnabled(false);
                builder.setAutoEnterEnabled(false);
            }
            builder.setActions(java.util.Collections.singletonList(playPauseAction()));
            showPhoneControls(false);
            return enterPictureInPictureMode(builder.build());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }
    }

    // endregion

    // region Brightness and volume gestures

    private void createLevelIndicator() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.phone_level_bg);
        box.setPadding(dp(22), dp(16), dp(22), dp(16));
        box.setVisibility(View.GONE);

        mLevelIcon = new ImageView(this);
        mLevelIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        box.addView(mLevelIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        mLevelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mLevelBar.setMax(100);
        mLevelBar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        mLevelBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(120), dp(4));
        barParams.topMargin = dp(12);
        box.addView(mLevelBar, barParams);

        mLevelText = new TextView(this);
        mLevelText.setTextColor(Color.WHITE);
        mLevelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mLevelText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(8);
        box.addView(mLevelText, textParams);

        mVideoBox.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mLevelView = box;
    }

    private boolean handleLevelGesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLevelMode = LEVEL_NONE;
                mLevelStartX = event.getRawX();
                mLevelStartY = event.getRawY();
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (mLevelMode == LEVEL_NONE) {
                    float dx = event.getRawX() - mLevelStartX;
                    float dy = event.getRawY() - mLevelStartY;
                    int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2;
                    if (Math.abs(dy) < slop || Math.abs(dy) < Math.abs(dx) * 1.5f) {
                        return false;
                    }
                    Rect box = new Rect();
                    mVideoBox.getGlobalVisibleRect(box);
                    float across = (mLevelStartX - box.left) / Math.max(1f, box.width());
                    if (dy > 0 && across > 0.33f && across < 0.67f) {
                        mLevelMode = LEVEL_EXIT; // down the middle: out of full screen (the sides stay brightness and volume)
                    } else {
                        mLevelMode = mLevelStartX < box.centerX() ? LEVEL_BRIGHTNESS : LEVEL_VOLUME;
                        mLevelStartValue = mLevelMode == LEVEL_BRIGHTNESS ? currentBrightness() : currentVolume();
                    }
                    mLevelStartY = event.getRawY();

                    // Whatever got the touch so far (player, controls) must not see a tap
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    routeTouch(cancel);
                    cancel.recycle();
                }
                if (mLevelMode == LEVEL_EXIT) {
                    // The video gives a little under the finger
                    float pull = Math.max(0f, Math.min(1f, (event.getRawY() - mLevelStartY) / dp(180)));
                    mVideoBox.setScaleX(1f - 0.08f * pull);
                    mVideoBox.setScaleY(1f - 0.08f * pull);
                    return true;
                }
                float height = Math.max(1, mVideoBox.getHeight() * 0.7f);
                float value = Math.max(0f, Math.min(1f, mLevelStartValue + (mLevelStartY - event.getRawY()) / height));
                if (mLevelMode == LEVEL_BRIGHTNESS) {
                    setBrightness(value);
                } else {
                    setVolume(value);
                }
                showLevel(value);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mLevelMode == LEVEL_EXIT) {
                    mLevelMode = LEVEL_NONE;
                    mVideoBox.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getRawY() - mLevelStartY > dp(48)) {
                        toggleFullscreen();
                    }
                    return true;
                }
                if (mLevelMode != LEVEL_NONE) {
                    mLevelMode = LEVEL_NONE;
                    mHandler.removeCallbacks(mHideLevel);
                    mHandler.postDelayed(mHideLevel, 700);
                    return true;
                }
                return false;
            default:
                return mLevelMode != LEVEL_NONE;
        }
    }

    private void showLevel(float value) {
        if (mLevelView == null) {
            return;
        }
        mHandler.removeCallbacks(mHideLevel);
        mLevelView.animate().cancel();
        mLevelView.setAlpha(1f);
        mLevelView.setVisibility(View.VISIBLE);
        mLevelBar.setVisibility(View.VISIBLE);
        mLevelIcon.setImageResource(mLevelMode == LEVEL_BRIGHTNESS ? R.drawable.ic_phone_brightness
                : value <= 0f ? R.drawable.ic_phone_volume_off : R.drawable.ic_phone_volume);
        int percent = Math.round(value * 100);
        mLevelBar.setProgress(percent);
        mLevelText.setText(percent + "%");
    }

    private float currentBrightness() {
        float brightness = getWindow().getAttributes().screenBrightness;
        if (brightness >= 0) {
            return brightness;
        }
        try {
            return android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = Math.max(0.01f, value);
        getWindow().setAttributes(attrs);
    }

    private float currentVolume() {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return 0.5f;
        }
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        return max > 0 ? audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max : 0.5f;
    }

    private void setVolume(float value) {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(value * max), 0);
        } catch (SecurityException ignored) {
            // Do Not Disturb can refuse volume changes
        }
    }

    // endregion

    // region Phone controls

    private void setupControls() {
        mControls.setOnClickListener(v -> showPhoneControls(false));
        mPlayButton.setOnClickListener(v -> {
            togglePlay();
            scheduleAutoHide();
        });
        findViewById(R.id.phone_ctl_next).setOnClickListener(v -> {
            presenter().onNextClicked();
            scheduleAutoHide();
        });
        findViewById(R.id.phone_ctl_prev).setOnClickListener(v -> {
            presenter().onPreviousClicked();
            scheduleAutoHide();
        });
        mFullscreenButton.setOnClickListener(v -> toggleFullscreen());
        findViewById(R.id.phone_ctl_collapse).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.phone_ctl_settings).setOnClickListener(v -> openPlayerSheet());
        findViewById(R.id.phone_ctl_cc).setOnClickListener(v -> clickButton(R.id.lb_control_closed_captioning));
        findViewById(R.id.phone_ctl_speed).setOnClickListener(v -> openSpeedMenu());
        findViewById(R.id.phone_ctl_autoplay).setOnClickListener(v -> toggleAutoplay());
        // Same list as SmartTube's playback mode button: play next, pause, repeat, shuffle, play the list, close
        findViewById(R.id.phone_ctl_mode).setOnClickListener(v ->
                presenter().onButtonLongClicked(R.id.action_repeat, getButtonState(R.id.action_repeat)));
        refreshPlaybackMode();

        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mPlayer != null) {
                    long duration = mPlayer.getDurationMs();
                    updateTimeText(duration * progress / 1000, duration);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsSeeking = true;
                mHandler.removeCallbacks(mAutoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mIsSeeking = false;
                if (mPlayer != null) {
                    long duration = mPlayer.getDurationMs();
                    if (duration > 0) {
                        mPlayer.setPositionMs(duration * seekBar.getProgress() / 1000);
                    }
                }
                scheduleAutoHide();
            }
        });
    }

    private void togglePlay() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
            updatePlayButton();
        }
    }

    private void showPhoneControls(boolean show) {
        if (mControls == null) {
            return;
        }
        boolean visible = show && !mShortsMode && !isPip();
        mControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            updatePlayButton();
            updateProgress();
            scheduleAutoHide();
        } else {
            mHandler.removeCallbacks(mAutoHide);
        }
        updateLoading();
    }

    private void scheduleAutoHide() {
        mHandler.removeCallbacks(mAutoHide);
        mHandler.postDelayed(mAutoHide, HIDE_CONTROLS_MS);
    }

    private void updatePlayButton() {
        if (mPlayer == null) {
            return;
        }
        updatePipParams();
        boolean playing = mPlayer.getPlayWhenReady();
        mPlayButton.setImageResource(playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play);
        if (mShortsPaused != null) {
            mShortsPaused.setVisibility(mShortsMode && !playing ? View.VISIBLE : View.GONE);
        }
    }

    private void updateProgress() {
        if (mPlayer == null) {
            return;
        }
        long duration = mPlayer.getDurationMs();
        long position = mPlayer.getPositionMs();
        int progress = duration > 0 ? (int) Math.max(0, Math.min(1000, position * 1000 / duration)) : 0;

        setMiniProgress(progress);
        if (mShortsMode && !mIsSeeking) {
            ((SeekBar) findViewById(R.id.phone_shorts_seek)).setProgress(progress);
        }
        updateLoading();

        boolean settled = !mAwaitingNewVideo && !mPaging && !mPageAnimating;
        long now = android.os.SystemClock.uptimeMillis();
        // One fade per poster: this runs many times a second while a Short's picture is up, and starting the fade
        // again each time would never let it finish (the picture stayed, invisible, with the loading circle on top).
        // A fade something else stopped halfway is started again after a moment.
        boolean fading = mPoster != null && mPoster.getAlpha() < 1f && now - mPosterFadeStart < 600;
        if (mPoster != null && mPoster.getVisibility() == View.VISIBLE && !mPosterUntilFrame && settled && !fading
                && position > (mShortsMode ? 150 : 350)
                && mPlayer.getPlayWhenReady() && (!mShortsMode || newShortIsPlaying(position))) {
            mPosterFadeStart = now;
            ImageView poster = mPoster;
            poster.animate().alpha(0f).setDuration(mShortsMode ? 150 : 220).withEndAction(() -> {
                poster.setVisibility(View.GONE);
                updateLoading();
            }).start();
        }
        if (mShortsMode && settled && position > 150 && mPlayer.getPlayWhenReady()) {
            preloadNextShort(); // this one plays: get the next one ready
        }

        if (mControls.getVisibility() == View.VISIBLE && !mIsSeeking) {
            mSeek.setProgress(progress);
            updateTimeText(position, duration);
        }
        updatePlayButton();
    }

    private long mPosterFadeStart;
    private android.animation.ObjectAnimator mProgressAnimator;

    /** Shorts are short: their line glides between updates instead of jumping twice a second */
    private void setMiniProgress(int progress) {
        if (mProgressAnimator != null) {
            mProgressAnimator.cancel();
            mProgressAnimator = null;
        }
        int ahead = progress - mMiniProgress.getProgress();
        boolean playing = mPlayer != null && mPlayer.getPlayWhenReady();
        if (mShortsMode && playing && ahead > 0 && ahead < 250) {
            mProgressAnimator = android.animation.ObjectAnimator.ofInt(mMiniProgress, "progress", progress);
            mProgressAnimator.setDuration(TICK_MS);
            mProgressAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
            mProgressAnimator.start();
        } else {
            mMiniProgress.setProgress(progress);
        }
    }

    private void updateTimeText(long position, long duration) {
        mTime.setText(duration > 0 ? formatTime(position) + " / " + formatTime(duration) : formatTime(position));
    }

    private static String formatTime(long ms) {
        long total = Math.max(0, ms / 1000);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, seconds) : String.format("%d:%02d", minutes, seconds);
    }

    // endregion

    // region Shorts

    /** The Shorts screen around the video: room for the status bar at the top, the tabs and the progress line below */
    private void layoutShorts() {
        View tabs = findViewById(R.id.phone_shorts_tabs);
        int tabsHeight = dp(54) + mNavInset;
        if (tabs.getPaddingBottom() != mNavInset) {
            tabs.setPadding(0, 0, 0, mNavInset);
        }
        setBottomMargin(findViewById(R.id.phone_shorts_seek), tabsHeight - dp(9)); // the line sits on the tabs' edge
        setBottomMargin(findViewById(R.id.phone_shorts_info), tabsHeight + dp(22));
        setBottomMargin(findViewById(R.id.phone_shorts_side), tabsHeight + dp(18));
        setBottomMargin(findViewById(R.id.phone_shorts_scrim), tabsHeight);
        View top = findViewById(R.id.phone_shorts_top);
        FrameLayout.LayoutParams topParams = (FrameLayout.LayoutParams) top.getLayoutParams();
        if (topParams.topMargin != dp(6) + mStatusInset) {
            topParams.topMargin = dp(6) + mStatusInset;
            top.setLayoutParams(topParams);
        }
    }

    private static void setBottomMargin(View view, int margin) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin;
            view.setLayoutParams(params);
        }
    }

    /** The app's tabs under the Shorts (Shorts chosen): another tab closes the Shorts and opens it on Home's screen */
    private void setupShortsTabs() {
        LinearLayout tabs = findViewById(R.id.phone_shorts_tabs);
        int[] icons = {R.drawable.ic_phone_tab_home, R.drawable.ic_phone_tab_shorts_on, R.drawable.ic_phone_tab_subs,
                R.drawable.ic_phone_tab_bell, R.drawable.ic_phone_account};
        int[] labels = {R.string.header_home, R.string.header_shorts, R.string.header_subscriptions,
                R.string.phone_tab_notifications, R.string.phone_tab_you};
        String[] targets = {"tab_home", null, "subscriptions", "tab_notifications", "tab_you"};
        for (int i = 0; i < icons.length; i++) {
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(7), 0, dp(5));
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            tab.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            label.setMaxLines(1);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(4);
            tab.addView(label, labelParams);
            String target = targets[i];
            tab.setOnClickListener(v -> {
                if (target == null) {
                    return; // Shorts: here already
                }
                startActivity(new android.content.Intent(this, PhoneBrowseActivity.class)
                        .putExtra(PhoneBrowseActivity.EXTRA_SHORTCUT, target)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                closePlayer();
            });
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(54), 1f));
        }
    }

    private void setupShorts() {
        findViewById(R.id.phone_shorts_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.phone_shorts_channel).setOnClickListener(v -> clickButton(R.id.action_channel));
        findViewById(R.id.phone_shorts_channel_tile).setOnClickListener(v -> clickButton(R.id.action_channel));
        findViewById(R.id.phone_shorts_subscribe).setOnClickListener(v -> clickButton(R.id.action_subscribe));
        findViewById(R.id.phone_shorts_search).setOnClickListener(v ->
                com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter.instance(this).startSearch(null));
        findViewById(R.id.phone_shorts_more).setOnClickListener(v -> clickButton(R.id.lb_control_high_quality));
        setupShortsTabs();
        SeekBar shortsSeek = findViewById(R.id.phone_shorts_seek);
        shortsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mIsSeeking = false;
                if (mPlayer != null && mPlayer.getDurationMs() > 0) {
                    mPlayer.setPositionMs(mPlayer.getDurationMs() * seekBar.getProgress() / 1000);
                }
            }
        });

        LinearLayout actions = findViewById(R.id.phone_shorts_actions);
        addShortsAction(actions, R.id.action_thumbs_up, R.drawable.ic_phone_like, getString(R.string.phone_like));
        addShortsAction(actions, R.id.action_thumbs_down, R.drawable.ic_phone_dislike, getString(R.string.phone_dislike));
        addShortsAction(actions, R.id.action_chat, R.drawable.ic_phone_comments, getString(R.string.phone_comments));
        addShortsAction(actions, R.id.action_share, R.drawable.ic_phone_share, getString(R.string.phone_share));
        addShortsAction(actions, R.id.action_playlist_add, R.drawable.ic_phone_save, getString(R.string.phone_save));

        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePlay();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || Math.abs(velocityY) < Math.abs(velocityX) || Math.abs(velocityY) < 600) {
                    return false;
                }
                float distance = e2.getY() - e1.getY();
                if (Math.abs(distance) < dp(60)) {
                    return false;
                }
                swipeTo(distance < 0);
                return true;
            }
        });
        mShorts.setOnTouchListener((v, event) -> handleShortsTouch(event));
    }

    // TikTok style paging: the current Short follows the finger, the next one comes in right behind it

    private int mNavInset;
    private float mPageStartY;
    private boolean mPaging;
    private int mPageDirection; // 1 next, -1 previous
    private Video mIncoming;
    private android.view.VelocityTracker mVelocity;
    private boolean mPageAnimating;

    private boolean handleShortsTouch(MotionEvent event) {
        if (mPageAnimating) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                android.util.Log.d("PhoneShorts", "touch ignored: page still animating");
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPageStartY = event.getRawY();
                mPaging = false;
                if (mVelocity != null) {
                    mVelocity.recycle();
                }
                mVelocity = android.view.VelocityTracker.obtain();
                trackPageVelocity(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                trackPageVelocity(event);
                float dy = event.getRawY() - mPageStartY;
                if (!mPaging && Math.abs(dy) > android.view.ViewConfiguration.get(this).getScaledTouchSlop()) {
                    mPaging = true;
                    mPageDirection = dy < 0 ? 1 : -1;
                    mIncoming = mPageDirection > 0 ? nextShort() : previousShortInQueue();
                    Video playing = currentVideo();
                    android.util.Log.d("PhoneShorts", "swipe " + (mPageDirection > 0 ? "next" : "previous") + " from "
                            + (playing != null ? playing.videoId : null) + " to " + (mIncoming != null ? mIncoming.videoId : null)
                            + ", queue " + PhoneShortsQueue.videos().size());
                    preparePage(mIncoming);
                }
                if (mPaging) {
                    if (mIncoming == null && mPageDirection > 0) {
                        // The next Shorts may have come in meanwhile (the feed loads more while the user swipes)
                        mIncoming = nextShort();
                        if (mIncoming != null) {
                            preparePage(mIncoming);
                        }
                    }
                    // Only follow the finger in the chosen direction; rubber band when there is nothing there
                    float offset = mPageDirection > 0 ? Math.min(0, dy) : Math.max(0, dy);
                    if (mIncoming == null) {
                        offset *= 0.25f;
                    }
                    setPageOffset(offset);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!mPaging) {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        onShortTapped(event.getX(), event.getY());
                    }
                } else {
                    float dy = event.getRawY() - mPageStartY;
                    float velocity = 0;
                    if (mVelocity != null) {
                        trackPageVelocity(event);
                        mVelocity.computeCurrentVelocity(1000);
                        velocity = mVelocity.getYVelocity();
                    }
                    float height = mVideoBox.getHeight();
                    // A short flick is enough (like the YouTube app), or a drag past an eighth of the screen
                    boolean flicked = mPageDirection > 0 ? velocity < -dp(250) : velocity > dp(250);
                    boolean farEnough = Math.abs(dy) > height * 0.12f || flicked;
                    boolean rightWay = mPageDirection > 0 ? dy < 0 : dy > 0;
                    android.util.Log.d("PhoneShorts", "release: moved " + Math.round(dy) + " of " + Math.round(height)
                            + ", speed " + Math.round(velocity) + (mIncoming == null ? ", nothing to go to" : ""));
                    if (mIncoming != null && farEnough && rightWay) {
                        commitPage(velocity);
                    } else {
                        settlePage();
                    }
                }
                if (mVelocity != null) {
                    mVelocity.recycle();
                    mVelocity = null;
                }
                mPaging = false;
                return true;
            }
            default:
                return true;
        }
    }

    /**
     * The finger's speed in screen coordinates: the Shorts overlay moves with the finger while paging, so its own
     * coordinates barely change and a flick looked like no speed at all (a swipe had to be long and hard).
     */
    private void trackPageVelocity(MotionEvent event) {
        if (mVelocity == null) {
            return;
        }
        MotionEvent onScreen = MotionEvent.obtain(event);
        onScreen.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(onScreen);
        onScreen.recycle();
    }

    private View playerView() {
        return mPlayer != null ? mPlayer.getView() : null;
    }

    // A tap pauses, two quick taps like. The pause waits a moment for a second tap.

    private long mLastShortTap;
    private final Runnable mShortSingleTap = this::togglePlay;

    private void onShortTapped(float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastShortTap < DOUBLE_TAP_MS) {
            mHandler.removeCallbacks(mShortSingleTap);
            likeShort(x, y);
        } else {
            mHandler.postDelayed(mShortSingleTap, DOUBLE_TAP_MS);
        }
        mLastShortTap = now; // a third quick tap is another like, not a pause
    }

    /** Double tap: likes the Short (never takes a like back) and pops a thumb where the finger was */
    private void likeShort(float x, float y) {
        if (getButtonState(R.id.action_thumbs_up) != PlayerUI.BUTTON_ON) {
            clickButton(R.id.action_thumbs_up);
        }
        mShorts.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);

        if (!(mShorts instanceof FrameLayout)) {
            return;
        }
        FrameLayout overlay = (FrameLayout) mShorts;
        int size = dp(92);
        ImageView thumb = new ImageView(this);
        thumb.setImageResource(R.drawable.ic_phone_like);
        thumb.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        thumb.setAlpha(0f);
        thumb.setScaleX(0.3f);
        thumb.setScaleY(0.3f);
        thumb.setRotation(x < overlay.getWidth() / 2f ? -12 : 12);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = Math.round(x - size / 2f);
        params.topMargin = Math.round(y - size / 2f);
        overlay.addView(thumb, params);
        thumb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f))
                .withEndAction(() -> thumb.animate().alpha(0f).translationYBy(-dp(40)).scaleX(1.25f).scaleY(1.25f)
                        .setStartDelay(220).setDuration(260)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> overlay.removeView(thumb)).start())
                .start();
    }

    // The next Short's stream info and picture are fetched while this one plays, so a swipe starts it right away

    private String mPreloadedShortId;

    private void preloadNextShort() {
        Video next = nextShort();
        if (next == null || next.videoId == null || next.videoId.equals(mPreloadedShortId)) {
            return;
        }
        String videoId = next.videoId;
        mPreloadedShortId = videoId;
        PhoneUi.prefetchThumb(this, next, true);
        PhoneUi.prefetchThumb(this, PhoneShortsQueue.next(next), true);
        com.liskovsoft.sharedutils.rx.RxHelper.runAsync(
                () -> com.liskovsoft.youtubeapi.service.YouTubeMediaItemService.preloadFormatInfo(videoId));
    }

    /**
     * Right after a swipe the player can still report the previous Short's position for a moment. Only uncover the
     * video once its position fits the time since the new Short arrived, i.e. its own frames are really playing.
     */
    private boolean newShortIsPlaying(long position) {
        long shownFor = android.os.SystemClock.uptimeMillis() - mPosterSince;
        return shownFor > 200 && position <= shownFor + 1500;
    }

    private void preparePage(Video incoming) {
        // The current Short keeps its own picture (or video); the incoming one gets the second poster
        if (incoming != null) {
            mIncomingPoster.setAlpha(1f);
            mIncomingPoster.setVisibility(View.VISIBLE);
            PhoneUi.loadThumb(mIncomingPoster, incoming, true);
        } else {
            mIncomingPoster.setVisibility(View.GONE); // nothing there: just a small rubber band
        }
        setPageOffset(0);
    }

    private void setPageOffset(float offset) {
        float height = mVideoBox.getHeight();
        View video = playerView();
        if (video != null) {
            video.setTranslationY(offset);
        }
        mShorts.setTranslationY(offset);
        mPoster.setTranslationY(offset); // the current Short's picture moves with it
        mIncomingPoster.setTranslationY((mPageDirection > 0 ? height : -height) + offset);
    }

    /** @param velocity finger speed at release, pixels per second: a quick flick turns the page quicker */
    private void commitPage(float velocity) {
        float height = mVideoBox.getHeight();
        float target = mPageDirection > 0 ? -height : height;
        View video = playerView();
        float left = Math.abs(target - mShorts.getTranslationY());
        long duration = 230;
        if (Math.abs(velocity) > 1) {
            duration = Math.max(130, Math.min(230, Math.round(left / Math.abs(velocity) * 1000 * 1.7f)));
        }
        animatePage(target, duration, () -> {
            // The new Short's thumbnail now fills the screen and stays until its video plays: it becomes the poster
            ImageView old = mPoster;
            mPoster = mIncomingPoster;
            mIncomingPoster = old;
            mIncomingPoster.animate().cancel();
            mIncomingPoster.setVisibility(View.GONE);
            mIncomingPoster.setTranslationY(0);
            mPoster.setTranslationY(0);
            mPoster.setAlpha(1f);
            mPoster.setVisibility(View.VISIBLE);
            mPosterSince = android.os.SystemClock.uptimeMillis();
            if (video != null) {
                video.setTranslationY(0);
            }
            mShorts.setTranslationY(0);
            // The overlay already shows the incoming Short, so title and channel match the picture
            mShorts.setAlpha(1f);
            if (mIncoming != null) {
                refreshShorts(mIncoming);
                mSwipedTo = mIncoming;
                String expected = mIncoming.videoId;
                mExpectedVideoId = expected;
                // Safety net: never wait forever if the player reports the Short under another id
                mShorts.postDelayed(() -> {
                    if (mSwipedTo != null && expected.equals(mSwipedTo.videoId)) {
                        mSwipedTo = null;
                    }
                    if (mAwaitingNewVideo && expected.equals(mExpectedVideoId)) {
                        mExpectedVideoId = null;
                        onPlayerVideo(currentVideo());
                    }
                }, 5000);
            }
            mAwaitingNewVideo = true;
            openShort(mIncoming);
        });
    }

    private void settlePage() {
        animatePage(0, 200, () -> {
            View video = playerView();
            if (video != null) {
                video.setTranslationY(0);
            }
            mShorts.setTranslationY(0);
            mPoster.setTranslationY(0);
            mIncomingPoster.setVisibility(View.GONE);
            mIncomingPoster.setTranslationY(0);
        });
    }

    private android.animation.ValueAnimator mPageAnimator;

    /**
     * Slides the current Short and the incoming one together to the given offset. Owned by this screen, so nothing
     * else (poster fades, overlay fades, player updates) can cancel it halfway; the cleanup runs even if it's ended
     * early, so the video can never stay stuck off screen.
     */
    private void animatePage(float target, long duration, Runnable onDone) {
        if (mPageAnimator != null) {
            mPageAnimator.end();
        }
        mPageAnimating = true;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(mShorts.getTranslationY(), target);
        animator.setDuration(duration);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        animator.addUpdateListener(a -> setPageOffset((float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mDone;

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (mDone) {
                    return;
                }
                mDone = true;
                if (mPageAnimator == a) {
                    mPageAnimator = null;
                }
                mPageAnimating = false;
                onDone.run();
            }
        });
        mPageAnimator = animator;
        animator.start();
    }

    /** Leaving the screen mid swipe: finish the swipe right away so nothing stays half moved */
    private void finishPageAnimation() {
        if (mPageAnimator != null) {
            mPageAnimator.end();
        }
        if (mPaging) {
            mPaging = false;
            settlePageNow();
        }
    }

    private void settlePageNow() {
        View video = playerView();
        if (video != null) {
            video.setTranslationY(0);
        }
        mShorts.setTranslationY(0);
        mPoster.setTranslationY(0);
        mIncomingPoster.setVisibility(View.GONE);
        mIncomingPoster.setTranslationY(0);
        mPageAnimating = false;
    }

    private void openShort(Video video) {
        if (video == null) {
            return;
        }
        android.util.Log.d("PhoneShorts", "open " + video.videoId);
        presenter().openVideo(video);
        if (PhoneShortsQueue.hasOwnFeed()) {
            PhoneShortsQueue.markPlayed(video); // also loads the next page near the end
        } else if (PhoneShortsQueue.isNearEnd(video)) {
            // Load more Shorts into the same list
            Video last = PhoneShortsQueue.last();
            if (last != null) {
                com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.instance(this).onScrollEnd(last);
            }
        }
    }

    private Video nextShort() {
        Video current = currentVideo();
        Video next = PhoneShortsQueue.next(current);
        if (next != null) {
            return next;
        }
        // Not from a Shorts list: suggested Shorts only (never a regular video)
        for (Video video : mAdapter.allVideos()) {
            if (video.videoId == null || !video.isShorts || (current != null && video.videoId.equals(current.videoId))) {
                continue;
            }
            boolean seen = false;
            for (Video played : mShortsHistory) {
                if (video.videoId.equals(played.videoId)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                return video;
            }
        }
        return null;
    }

    private Video previousShortInQueue() {
        Video previous = PhoneShortsQueue.previous(currentVideo());
        return previous != null ? previous : previousShort();
    }

    /**
     * Seamless swipe: the next Short's thumbnail slides over the current video right away and stays until
     * the new video is playing, so there is never a blank frame.
     */
    private void swipeTo(boolean next) {
        Video incoming = next ? nextShortGuess() : previousShort();
        float height = mVideoBox.getHeight();

        mAwaitingNewVideo = true;
        mPoster.animate().cancel();
        mPoster.setAlpha(1f);
        mPoster.setVisibility(View.VISIBLE);
        if (incoming != null) {
            PhoneUi.loadThumb(mPoster, incoming, true);
        } else {
            mPoster.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        }
        mPoster.setTranslationY(next ? height : -height);
        mPoster.animate()
                .translationY(0)
                .setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();

        mShorts.animate().alpha(0f).setDuration(120).start();

        if (next) {
            presenter().onNextClicked();
        } else {
            presenter().onPreviousClicked();
        }
    }

    private Video nextShortGuess() {
        Video current = currentVideo();
        for (Video video : mAdapter.allVideos()) {
            if (video.videoId == null || (current != null && video.videoId.equals(current.videoId))) {
                continue;
            }
            boolean seen = false;
            for (Video played : mShortsHistory) {
                if (video.videoId.equals(played.videoId)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                return video;
            }
        }
        return null;
    }

    private Video previousShort() {
        return mShortsHistory.size() >= 2 ? mShortsHistory.get(mShortsHistory.size() - 2) : null;
    }

    private void preloadNextShorts() {
        if (!PhoneUi.isAlive(this)) {
            return;
        }
        int count = 0;
        Video next = currentVideo();
        for (int i = 0; i < 3 && next != null; i++) {
            next = PhoneShortsQueue.next(next);
            if (next != null && next.videoId != null) {
                com.bumptech.glide.Glide.with(this).load("https://i.ytimg.com/vi/" + next.videoId + "/oar2.jpg").preload();
                if (!TextUtils.isEmpty(next.getCardImageUrl())) {
                    com.bumptech.glide.Glide.with(this).load(next.getCardImageUrl()).preload();
                }
            }
        }
        for (Video video : mAdapter.allVideos()) {
            if (video.videoId == null) {
                continue;
            }
            com.bumptech.glide.Glide.with(this).load("https://i.ytimg.com/vi/" + video.videoId + "/oar2.jpg").preload();
            if (++count >= 3) {
                break;
            }
        }
    }

    // region Background playback notification

    private void setupNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        NOTIFICATION_CHANNEL, getString(R.string.phone_notification_channel), android.app.NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }

        mPlayPauseReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (mPlayer != null) {
                    mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
                    showPlaybackNotification();
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_PLAY_PAUSE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mPlayPauseReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mPlayPauseReceiver, filter);
        }
    }

    /**
     * Media notification while a video keeps playing in the background. It is tied to the player's media session,
     * so the lock screen, media controls and Live Alerts show the video with its thumbnail.
     */
    private void showPlaybackNotification() {
        Video video = currentVideo();
        if (video == null || mPlayer == null) {
            return;
        }

        boolean playing = mPlayer.getPlayWhenReady();
        android.content.Intent open = new android.content.Intent(this, PhonePlaybackActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent contentIntent = android.app.PendingIntent.getActivity(this, 0, open,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.PendingIntent toggleIntent = android.app.PendingIntent.getBroadcast(this, 1,
                new android.content.Intent(ACTION_PLAY_PAUSE).setPackage(getPackageName()),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        androidx.media.app.NotificationCompat.MediaStyle style = new androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0);
        android.support.v4.media.session.MediaSessionCompat.Token token = mPlayer.getMediaSessionToken();
        if (token != null) {
            style.setMediaSession(token);
        }

        final androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_phone_play)
                .setContentTitle(video.getTitle())
                .setContentText(video.getAuthor())
                .setContentIntent(contentIntent)
                .setOngoing(playing)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play,
                        getString(R.string.phone_play_pause), toggleIntent)
                .setStyle(style);

        notifyPlayback(builder);

        // Thumbnail as the large icon (shown in media controls and Live Alerts)
        com.bumptech.glide.Glide.with(getApplicationContext())
                .asBitmap()
                .load("https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg")
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap resource,
                                                @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        builder.setLargeIcon(resource);
                        notifyPlayback(builder);
                    }

                    @Override
                    public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    private void notifyPlayback(androidx.core.app.NotificationCompat.Builder builder) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
            // notifications blocked
        }
    }

    private void cancelPlaybackNotification() {
        androidx.core.app.NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
    }

    // endregion

    private void addShortsAction(LinearLayout container, int buttonId, int icon, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setTag(buttonId);
        item.setPadding(dp(8), dp(9), dp(8), dp(9));

        // As in the YouTube app: plain white icons (no circle behind them), the count under each
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setPadding(dp(2), dp(2), dp(2), dp(2));
        image.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        image.setElevation(dp(2));
        item.addView(image, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setShadowLayer(4, 0, 1, 0x99000000);
        text.setGravity(Gravity.CENTER);
        text.setVisibility(label != null ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(4);
        item.addView(text, textParams);

        item.setOnClickListener(v -> clickButton(buttonId));
        container.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void refreshShorts(Video video) {
        if (mShorts == null || video == null) {
            return;
        }
        ((TextView) findViewById(R.id.phone_shorts_title)).setText(video.getTitle());
        ((TextView) findViewById(R.id.phone_shorts_author)).setText(video.getAuthor());
        PhoneUi.bindAuthorAvatar(findViewById(R.id.phone_shorts_avatar), video);
        PhoneUi.bindAuthorAvatar(findViewById(R.id.phone_shorts_channel_tile), video);

        boolean subscribed = getButtonState(R.id.action_subscribe) == PlayerUI.BUTTON_ON;
        TextView subscribe = findViewById(R.id.phone_shorts_subscribe);
        subscribe.setText(subscribed ? R.string.phone_subscribed : R.string.phone_subscribe);
        subscribe.setBackgroundResource(subscribed ? R.drawable.phone_pill_bg : R.drawable.phone_button_bg);
        subscribe.setTextColor(subscribed ? Color.WHITE : getResources().getColor(R.color.phone_bg));

        LinearLayout actions = findViewById(R.id.phone_shorts_actions);
        for (int i = 0; i < actions.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) actions.getChildAt(i);
            int buttonId = (Integer) item.getTag();
            boolean on = getButtonState(buttonId) == PlayerUI.BUTTON_ON;
            boolean isRating = buttonId == R.id.action_thumbs_up || buttonId == R.id.action_thumbs_down;
            ((ImageView) item.getChildAt(0)).setImageTintList(ColorStateList.valueOf(
                    isRating && on ? getResources().getColor(R.color.phone_accent) : Color.WHITE));
            if (buttonId == R.id.action_thumbs_up) {
                ((TextView) item.getChildAt(1)).setText(
                        !TextUtils.isEmpty(video.likeCount) ? video.likeCount : getString(R.string.phone_like));
            } else if (buttonId == R.id.action_thumbs_down) {
                ((TextView) item.getChildAt(1)).setText(
                        !TextUtils.isEmpty(video.dislikeCount) ? video.dislikeCount : getString(R.string.phone_dislike));
            }
        }
    }

    // endregion

    // region Details

    private PlaybackPresenter presenter() {
        return PlaybackPresenter.instance(this);
    }

    private int getButtonState(int buttonId) {
        return mPlayer != null ? mPlayer.getButtonState(buttonId) : PlayerUI.BUTTON_OFF;
    }

    // region Autoplay and playback mode

    private static boolean isAutoplay(int mode) {
        return mode != PlayerConstants.PLAYBACK_MODE_PAUSE && mode != PlayerConstants.PLAYBACK_MODE_CLOSE;
    }

    private void toggleAutoplay() {
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData data =
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
        int mode = isAutoplay(data.getPlaybackMode()) ? PlayerConstants.PLAYBACK_MODE_PAUSE : PlayerConstants.PLAYBACK_MODE_ALL;
        data.setPlaybackMode(mode);
        if (mPlayer != null) {
            mPlayer.setButtonState(R.id.action_repeat, mode);
        }
        refreshPlaybackMode();
        android.widget.Toast.makeText(this, isAutoplay(mode) ? R.string.phone_autoplay_on : R.string.phone_autoplay_off,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Autoplay switch and playback mode icon follow the current mode */
    private void refreshPlaybackMode() {
        ImageView autoplay = findViewById(R.id.phone_ctl_autoplay);
        ImageView modeButton = findViewById(R.id.phone_ctl_mode);
        if (autoplay == null || modeButton == null) {
            return;
        }
        int mode = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this).getPlaybackMode();
        autoplay.setImageResource(isAutoplay(mode) ? R.drawable.ic_phone_autoplay_on : R.drawable.ic_phone_autoplay_off);
        int icon;
        boolean special = true;
        switch (mode) {
            case PlayerConstants.PLAYBACK_MODE_SHUFFLE:
                icon = R.drawable.ic_phone_mode_shuffle;
                break;
            case PlayerConstants.PLAYBACK_MODE_ONE:
                icon = R.drawable.ic_phone_mode_repeat_one;
                break;
            case PlayerConstants.PLAYBACK_MODE_LIST:
            case PlayerConstants.PLAYBACK_MODE_REVERSE_LIST:
                icon = R.drawable.ic_phone_mode_repeat;
                break;
            default:
                icon = R.drawable.ic_phone_mode_next;
                special = false;
                break;
        }
        modeButton.setImageResource(icon);
        // Shuffle and repeat modes light up in the accent color
        modeButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                special ? getResources().getColor(R.color.phone_accent) : Color.WHITE));
    }

    // endregion

    /** The gear: Quality, Playback speed, Captions, More settings (see PhonePlayerSheet) */
    private void openPlayerSheet() {
        if (mPlayer == null) {
            return;
        }
        mHandler.removeCallbacks(mAutoHide);
        PhonePlayerSheet.show(this, new PhonePlayerSheet.Host() {
            @Override
            public PhonePlaybackFragment player() {
                return mPlayer;
            }

            @Override
            public String playingQuality() {
                com.google.android.exoplayer2.SimpleExoPlayer exo = exoPlayer();
                com.google.android.exoplayer2.Format format = exo != null ? exo.getVideoFormat() : null;
                if (format == null || format.height <= 0) {
                    return null;
                }
                int fps = Math.round(format.frameRate);
                return Math.min(format.width, format.height) + "p" + (fps >= 48 ? String.valueOf(fps) : "");
            }

            @Override
            public void openSpeed() {
                openSpeedMenu();
            }

            @Override
            public void openCaptions() {
                clickButton(R.id.lb_control_closed_captioning);
            }

            @Override
            public void openMoreSettings() {
                clickButton(R.id.lb_control_high_quality);
            }
        });
    }

    private void clickButton(int buttonId) {
        if (buttonId == R.id.action_thumbs_up || buttonId == R.id.action_thumbs_down || buttonId == R.id.action_subscribe) {
            mRoot.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); // a tick under the finger
        }
        presenter().onButtonClicked(buttonId, getButtonState(buttonId));
    }

    /**
     * Always the speed list. SmartTube's speed button (a TV remote shortcut) opens the list only at 1x; at any other
     * speed a press just switches between 1x and that speed, which on a phone looks like the button stopped working.
     */
    private void openSpeedMenu() {
        presenter().onButtonLongClicked(R.id.action_video_speed, getButtonState(R.id.action_video_speed));
    }

    private void setupDetails() {
        // The layout manager must exist before anything is inflated against the RecyclerView
        mDetails.setLayoutManager(new LinearLayoutManager(this));
        mHeader = LayoutInflater.from(this).inflate(R.layout.phone_player_header, mDetails, false);
        mHeader.findViewById(R.id.phone_player_title).setOnClickListener(v -> {
            mDescriptionExpanded = !mDescriptionExpanded;
            refreshDetails();
        });
        mHeader.findViewById(R.id.phone_player_channel).setOnClickListener(v -> clickButton(R.id.action_channel));
        mHeader.findViewById(R.id.phone_player_subscribe).setOnClickListener(v -> clickButton(R.id.action_subscribe));

        LinearLayout actions = mHeader.findViewById(R.id.phone_player_actions);
        addAction(actions, R.id.action_thumbs_up, R.drawable.ic_phone_like, getString(R.string.phone_like));
        addAction(actions, R.id.action_thumbs_down, R.drawable.ic_phone_dislike, ""); // Return YouTube Dislike count
        addAction(actions, R.id.action_chat, R.drawable.ic_phone_comments, getString(R.string.phone_comments));
        addAction(actions, R.id.action_share, R.drawable.ic_phone_share, getString(R.string.phone_share));
        addAction(actions, R.id.action_playlist_add, R.drawable.ic_phone_save, getString(R.string.phone_save));
        addAction(actions, R.id.phone_action_download, R.drawable.ic_phone_download, getString(R.string.phone_download));
        addAction(actions, R.id.lb_control_high_quality, R.drawable.ic_phone_settings, getString(R.string.phone_quality));
        addAction(actions, R.id.action_video_speed, R.drawable.ic_phone_speed, getString(R.string.phone_speed));

        // Details on top, recommended videos below (TV overlay rows stay hidden)
        mAdapter = new FeedAdapter(this);
        mAdapter.setHeaderView(mHeader);
        mDetails.setItemAnimator(null);
        mDetails.setAdapter(mAdapter);
        mDetails.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 4) {
                    Video last = mAdapter.getScrollEndVideo();
                    if (last != null && last != mLastScrollEndVideo) {
                        mLastScrollEndVideo = last;
                        presenter().onScrollEnd(last);
                    }
                }
            }
        });
    }

    private void addAction(LinearLayout container, int buttonId, int icon, String label) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundResource(R.drawable.phone_pill_bg);
        pill.setPadding(dp(14), 0, label != null ? dp(16) : dp(14), 0);
        pill.setTag(buttonId);

        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.phone_text)));
        pill.addView(image, new LinearLayout.LayoutParams(dp(20), dp(20)));

        if (label != null) {
            TextView text = new TextView(this);
            text.setText(label);
            if (label.isEmpty()) {
                text.setVisibility(View.GONE); // filled in later (dislike count)
            }
            text.setTextColor(getResources().getColor(R.color.phone_text));
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.setMarginStart(dp(8));
            pill.addView(text, textParams);
        }

        pill.setOnClickListener(v -> {
            if (buttonId == R.id.action_video_speed) {
                openSpeedMenu();
            } else if (buttonId == R.id.phone_action_download) {
                PhoneDownloads.showOptions(this, currentVideo());
            } else {
                clickButton(buttonId);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        container.addView(pill, params);
    }

    private Video currentVideo() {
        Video video = presenter().getVideo();
        if (video == null && mPlayer != null) {
            video = mPlayer.getVideo();
        }
        // Right after a swipe SmartTube still reports the previous Short for a moment: use the one swiped to
        if (mSwipedTo != null) {
            if (video != null && Helpers_equals(video.videoId, mSwipedTo.videoId)) {
                mSwipedTo = null; // caught up, SmartTube's copy carries the full details
            } else {
                return mSwipedTo;
            }
        }
        return video;
    }

    private void refreshDetails() {
        if (mAwaitingNewVideo && mExpectedVideoId != null) {
            return; // mid swipe: the player still reports the old Short, keep the incoming one on screen
        }
        refreshDetails(currentVideo());
    }

    private void refreshDetails(Video video) {
        if (video == null || mHeader == null) {
            return;
        }

        mControlsTitle.setText(video.getTitle());
        refreshShorts(video);

        TextView title = mHeader.findViewById(R.id.phone_player_title);
        TextView meta = mHeader.findViewById(R.id.phone_player_meta);
        TextView description = mHeader.findViewById(R.id.phone_player_description);
        TextView author = mHeader.findViewById(R.id.phone_player_author);
        TextView subs = mHeader.findViewById(R.id.phone_player_subs);
        TextView subscribe = mHeader.findViewById(R.id.phone_player_subscribe);
        ImageView avatar = mHeader.findViewById(R.id.phone_avatar);

        title.setText(video.getTitle());
        title.setMaxLines(mDescriptionExpanded ? 10 : 2);

        CharSequence metaText = PhoneUi.meta(video);
        meta.setText(metaText);
        meta.setVisibility(TextUtils.isEmpty(metaText) ? View.GONE : View.VISIBLE);

        boolean showDescription = mDescriptionExpanded && !TextUtils.isEmpty(video.description);
        description.setText(showDescription ? video.description : null);
        description.setVisibility(showDescription ? View.VISIBLE : View.GONE);

        author.setText(video.getAuthor());
        subs.setText(video.subscriberCount);
        subs.setVisibility(TextUtils.isEmpty(video.subscriberCount) ? View.GONE : View.VISIBLE);
        PhoneUi.bindAuthorAvatar(avatar, video);
        avatar.setOnClickListener(v -> clickButton(R.id.action_channel));

        boolean subscribed = getButtonState(R.id.action_subscribe) == PlayerUI.BUTTON_ON;
        subscribe.setText(subscribed ? R.string.phone_subscribed : R.string.phone_subscribe);
        subscribe.setBackgroundResource(subscribed ? R.drawable.phone_pill_bg : R.drawable.phone_button_bg);
        subscribe.setTextColor(getResources().getColor(subscribed ? R.color.phone_text : R.color.phone_bg));

        LinearLayout actions = mHeader.findViewById(R.id.phone_player_actions);
        for (int i = 0; i < actions.getChildCount(); i++) {
            LinearLayout pill = (LinearLayout) actions.getChildAt(i);
            int buttonId = (Integer) pill.getTag();
            boolean on = getButtonState(buttonId) == PlayerUI.BUTTON_ON;
            boolean isRating = buttonId == R.id.action_thumbs_up || buttonId == R.id.action_thumbs_down;
            ((ImageView) pill.getChildAt(0)).setImageTintList(ColorStateList.valueOf(
                    getResources().getColor(isRating && on ? R.color.phone_accent : R.color.phone_text)));
            if (buttonId == R.id.action_thumbs_up && pill.getChildCount() > 1) {
                ((TextView) pill.getChildAt(1)).setText(
                        !TextUtils.isEmpty(video.likeCount) ? video.likeCount : getString(R.string.phone_like));
            } else if (buttonId == R.id.action_thumbs_down && pill.getChildCount() > 1) {
                // Dislikes from Return YouTube Dislike (SmartTube's likes counter setting, on by default)
                boolean count = !TextUtils.isEmpty(video.dislikeCount);
                TextView text = (TextView) pill.getChildAt(1);
                text.setText(video.dislikeCount);
                text.setVisibility(count ? View.VISIBLE : View.GONE);
                pill.setPadding(dp(14), 0, count ? dp(16) : dp(14), 0);
            }
        }
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // endregion

    // region PhonePlaybackFragment.Host

    @Override
    public boolean isCompactPlayer() {
        // Phone controls everywhere; the TV overlay is never shown (except picture-in-picture)
        return !isPip();
    }

    @Override
    public void onPlayerControlsVisible(boolean visible) {
        showPhoneControls(visible);
    }

    @Override
    public void onPlayerVideo(Video video) {
        if (mAwaitingNewVideo && video != null && mExpectedVideoId != null && !mExpectedVideoId.equals(video.videoId)) {
            return; // late update of the Short we just swiped away from
        }
        if (mSwipedTo != null && video != null && !Helpers_equals(mSwipedTo.videoId, video.videoId)) {
            mSwipedTo = null; // another video was opened
        }
        if (mAwaitingNewVideo && video != null) {
            mExpectedVideoId = null;
            // Swipe finished loading: keep the poster (now the real thumbnail) until frames play
            mAwaitingNewVideo = false;
            mPosterSince = android.os.SystemClock.uptimeMillis();
            PhoneUi.loadThumb(mPoster, video, true);
            mShorts.animate().alpha(1f).setDuration(180).start();
        } else if (video != null && mPoster != null && mPlayer != null && mPlayer.getPositionMs() < 350) {
            showPoster(video);
        }
        if (video != null && isShort(video)) {
            if (mShortsHistory.isEmpty() || !Helpers_equals(mShortsHistory.get(mShortsHistory.size() - 1).videoId, video.videoId)) {
                // Went back: drop the newer entry
                if (mShortsHistory.size() >= 2 && Helpers_equals(mShortsHistory.get(mShortsHistory.size() - 2).videoId, video.videoId)) {
                    mShortsHistory.remove(mShortsHistory.size() - 1);
                } else {
                    mShortsHistory.add(video);
                }
            }
        }
        boolean shorts = isShort(video);
        if (shorts != mShortsMode) {
            mShortsMode = shorts;
            applyLayoutMode();
            applySystemBars();
        }
        mDescriptionExpanded = false;
        if (mAdapter != null && video != null) {
            mAdapter.setExcludedVideoId(video.videoId);
        }
        refreshDetails();
        if (mShortsMode) {
            preloadNextShorts();
        }
        if (mDetails != null) {
            mDetails.scrollToPosition(0);
        }
    }

    @Override
    public void onPlayerSuggestions(VideoGroup group) {
        if (mAdapter == null || group == null || group.isChapters()) {
            return; // chapters stay out of the list
        }
        Video playing = currentVideo();
        mAdapter.setExcludedVideoId(playing != null ? playing.videoId : null);
        mAdapter.update(group);
        if (mShortsMode) {
            preloadNextShorts();
        }
    }

    private static boolean Helpers_equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public void onPlayerSuggestionsCleared() {
        if (mAdapter != null) {
            mAdapter.clear();
        }
        mLastScrollEndVideo = null;
    }

    @Override
    public void onPlayerButtonState(int buttonId, int buttonState) {
        refreshDetails();
    }

    @Override
    public void onPlayerChannelIcon(String iconUrl) {
        Video video = currentVideo();
        if (video != null && !TextUtils.isEmpty(iconUrl)) {
            AvatarCache.rememberUrl(this, video.channelId, video.getAuthor(), AvatarFetcher.handleOf(video), iconUrl);
            refreshDetails();
        }
    }

    // endregion

    // region FeedAdapter.Listener (header only, no items)

    @Override
    public void onVideoClicked(Video video) {
        presenter().onSuggestionItemClicked(video);
    }

    @Override
    public void onVideoLongClicked(Video video) {
        presenter().onSuggestionItemLongClicked(video);
    }

    @Override
    public void onStripClicked(Video channel) {
        onVideoClicked(channel);
    }

    @Override
    public void onShelfScrollEnd(Video lastVideo) {
        if (lastVideo != mLastScrollEndVideo) {
            mLastScrollEndVideo = lastVideo;
            presenter().onScrollEnd(lastVideo);
        }
    }

    // endregion
}
