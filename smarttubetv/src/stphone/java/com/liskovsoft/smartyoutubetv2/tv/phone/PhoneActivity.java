package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Base for the portrait phone screens. Keeps SmartTube's activity plumbing (locale, dpi, view manager stack)
 * but shows the system bars and lays the content out edge to edge.
 */
public abstract class PhoneActivity extends MotherActivity {
    protected RecyclerView mList;
    protected FeedAdapter mAdapter;
    private View mProgressBar;
    private PhoneSkeleton mSkeleton;
    private View mEmptyView;
    private TextView mEmptyText;
    private TextView mEmptyAction;

    @Override
    protected void attachBaseContext(android.content.Context context) {
        super.attachBaseContext(PhoneTheme.wrap(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this); // before the subclass inflates its layout
        getWindow().setWindowAnimations(R.style.PhoneScreenAnimation); // open/close in place instead of sliding pages
        setupSystemBars();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (leaveFloatingWindow()) {
            return;
        }
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this); // MotherActivity re-applies the TV density on resume
        // MotherActivity may switch to immersive mode (TV fullscreen setting). Phones keep their bars.
        setupSystemBars();
        getViewManager().addTop(this);
        PhoneMiniPlayer.onHostResumed(this);
        if (mAdapter != null) {
            mAdapter.refreshProgress(); // the red bars of videos watched meanwhile
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PhoneMiniPlayer.onHostPaused(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode()) {
            android.util.Log.d("PhonePip", getClass().getSimpleName() + " stopped in the floating window");
        }
        PhoneMiniPlayer.onHostStopped(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mClosedCheckPending) {
            mClosedCheckPending = false;
            mHandler.removeCallbacks(mClosedCheck); // back on screen: the floating window was expanded, not closed
            openPlayerFromWindow();
        }
    }

    /** Out of the floating window onto the full screen: the video opens in the player, like the YouTube app */
    private void openPlayerFromWindow() {
        mHandler.removeCallbacks(mOpenPlayer);
        mOpenAttempts = 0;
        mHandler.postDelayed(mOpenPlayer, 200);
    }

    private int mOpenAttempts;

    /**
     * Only once Android has finished taking this screen out of the floating window: it is back in front with focus
     * and in the player's task again. Opening the player earlier lets Android put this screen back over it (and the
     * player then shrinks into a floating window of its own).
     */
    private final Runnable mOpenPlayer = new Runnable() {
        @Override
        public void run() {
            boolean onScreen = getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
            if (!onScreen || isFinishing() || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode())) {
                return;
            }
            boolean settled = getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    && hasWindowFocus() && PhoneMiniPlayer.inPlayerTask(PhoneActivity.this);
            if (!settled && ++mOpenAttempts < 12) {
                mHandler.postDelayed(this, 200);
                return;
            }
            PhoneMiniPlayer.openFromWindow(PhoneActivity.this);
        }
    };

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The floating window left picture in picture while this screen was stopped. Usually the user closed it, but some
     * phones (ColorOS) stop the screen for a moment while expanding it too: the video only stops if the screen
     * doesn't come back.
     */
    private boolean mClosedCheckPending;

    private final Runnable mClosedCheck = () -> {
        mClosedCheckPending = false;
        boolean started = getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
        boolean inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
        android.util.Log.d("PhonePip", getClass().getSimpleName() + " floating window " + (started || inPip ? "expanded" : "closed"));
        if (!started && !inPip && !isFinishing()) {
            PhoneMiniPlayer.onHostPip(this, false, true);
        } else if (started && !inPip) {
            openPlayerFromWindow();
        }
    };

    /**
     * Only the player belongs in Android's floating window. A screen started from a player that is in it lands in the
     * floating window's task (the whole app shrunk into the corner): it closes itself straight away.
     */
    private boolean leaveFloatingWindow() {
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode() && !isFinishing() && !PhoneMiniPlayer.isFloating(this)) {
            android.util.Log.d("PhonePip", getClass().getSimpleName() + " in the floating window: closed");
            finish();
            return true;
        }
        return false;
    }

    // region Leaving the app with the mini player on: the video goes on in Android's floating window

    private String mPipState;

    /**
     * Set while this screen opens another one itself: Android reports that as the user leaving too, and the mini
     * player's floating window must not open then.
     */
    private boolean mAppLeaving;

    boolean isAppLeaving() {
        return mAppLeaving;
    }

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode, Bundle options) {
        mAppLeaving = true;
        getWindow().getDecorView().postDelayed(() -> {
            mAppLeaving = false;
            updateMiniPlayerPip();
        }, 1500);
        updateMiniPlayerPip(); // disarmed before Android moves this screen back
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        }
        super.startActivityForResult(intent, requestCode, options);
    }

    /** The mini player calls this when it appears, moves, pauses or goes away: keeps Android's picture told */
    void updateMiniPlayerPip() {
        if (Build.VERSION.SDK_INT < 26 || !hostsMiniPlayer()) {
            return;
        }
        boolean wanted = PhoneMiniPlayer.wantsPip(this);
        boolean inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
        // In the floating window the card is the whole window: no source spot to report, and changing the window's
        // settings there again and again makes Android re-lay it out (the video stutters)
        android.graphics.Rect card = inPip ? null : PhoneMiniPlayer.cardRect(this);
        String state = wanted + " " + PhoneMiniPlayer.isPlaying() + " " + (card != null ? card.flattenToString() : "") + " "
                + PhoneMiniPlayer.pipAspect();
        if (state.equals(mPipState)) {
            return;
        }
        mPipState = state;
        android.util.Log.d("PhonePip", getClass().getSimpleName() + " mini player window " + (wanted ? "armed" : "off"));
        try {
            setPictureInPictureParams(miniPlayerPipParams(wanted, card));
        } catch (RuntimeException e) {
            android.util.Log.d("PhoneMiniPlayer", "pip params: " + e); // no picture-in-picture on this device
        }
    }

    @androidx.annotation.RequiresApi(26)
    private android.app.PictureInPictureParams miniPlayerPipParams(boolean wanted, android.graphics.Rect card) {
        android.app.PictureInPictureParams.Builder builder = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(PhoneMiniPlayer.pipAspect());
        if (card != null && !card.isEmpty()) {
            builder.setSourceRectHint(card); // the floating window grows out of the card
        }
        // Play / pause in the window, like the player's own (handled by the player screen behind the app)
        boolean playing = PhoneMiniPlayer.isPlaying();
        android.app.PendingIntent toggle = android.app.PendingIntent.getBroadcast(this, 4721,
                new android.content.Intent("org.smarttube.phone.PIP_TOGGLE").setPackage(getPackageName()),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        String label = getString(R.string.phone_mini_play_pause);
        builder.setActions(java.util.Collections.singletonList(new android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, playing ? R.drawable.ic_phone_pause : R.drawable.ic_phone_play),
                label, label, toggle)));
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(wanted); // Android 12+: on its own, smoothly, when the app is left
            builder.setSeamlessResizeEnabled(false);
        }
        return builder.build();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Asked for right away as well (as NewTube does): the automatic entry alone doesn't open the window everywhere
        if (Build.VERSION.SDK_INT >= 26 && hostsMiniPlayer() && PhoneMiniPlayer.wantsPip(this) && !isInPictureInPictureMode()) {
            try {
                boolean entered = enterPictureInPictureMode(miniPlayerPipParams(true, PhoneMiniPlayer.cardRect(this)));
                android.util.Log.d("PhonePip", getClass().getSimpleName() + " leaving: mini player window " + (entered ? "opened" : "refused"));
            } catch (RuntimeException e) {
                android.util.Log.d("PhonePip", "mini player window: " + e);
            }
        } else if (hostsMiniPlayer()) {
            android.util.Log.d("PhonePip", getClass().getSimpleName() + " leaving: no mini player window ("
                    + PhoneMiniPlayer.pipBlocker(this) + ")");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        // Out of the floating window while this screen isn't coming back to the front: the user closed it
        boolean stopped = !inPip && !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
        android.util.Log.d("PhonePip", getClass().getSimpleName() + " floating window " + (inPip ? "open" : "left") + (stopped ? " (stopped)" : ""));
        mHandler.removeCallbacks(mClosedCheck);
        mClosedCheckPending = false;
        PhoneMiniPlayer.onHostPip(this, inPip, false);
        if (stopped) {
            mClosedCheckPending = true;
            mHandler.postDelayed(mClosedCheck, 1200);
        } else if (!inPip && !isFinishing()) {
            openPlayerFromWindow(); // expanded
        }
        if (inPip) {
            PhonePip.watch(this);
            leaveFloatingWindow(); // anything but the mini player's video doesn't belong there
        }
    }

    // endregion

    /** Shows the mini player card while a video plays in it (not on full screen pages like sign in or updates) */
    protected boolean hostsMiniPlayer() {
        return true;
    }

    /** Space the mini player card keeps clear at the bottom of the screen (navigation bar, Home's tabs) */
    protected int miniPlayerBottomOffset() {
        return PhoneMiniPlayer.navigationInset(getWindow().getDecorView());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupSystemBars();
        }
    }

    private void setupSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                int light = PhoneTheme.isNight(this) ? 0
                        : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!PhoneTheme.isNight(this)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * Pads the top bar below the status bar and the bottom view (or the list) above the navigation bar.
     */
    @SuppressWarnings("deprecation")
    protected void applyInsets(View root, View topBar, View bottomBar) {
        final int topBase = topBar != null ? topBar.getPaddingTop() : 0;
        final int bottomBase = bottomBar != null ? bottomBar.getPaddingBottom() : 0;

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();

            v.setPadding(left, 0, right, 0);

            if (topBar != null) {
                topBar.setPadding(topBar.getPaddingLeft(), topBase + top, topBar.getPaddingRight(), topBar.getPaddingBottom());
            }

            if (bottomBar != null) {
                bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(), bottomBar.getPaddingRight(), bottomBase + bottom);
            }

            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    // region Shared list plumbing

    protected void setupList(FeedAdapter.Listener listener) {
        mList = findViewById(R.id.phone_list);
        mProgressBar = findViewById(R.id.phone_progress_bar);
        mEmptyView = findViewById(R.id.phone_empty);
        mEmptyText = findViewById(R.id.phone_empty_text);
        mEmptyAction = findViewById(R.id.phone_empty_action);

        mAdapter = new FeedAdapter(listener);
        GridLayoutManager manager = new GridLayoutManager(this, 2);
        manager.setSpanSizeLookup(mAdapter.getSpanSizeLookup());
        mList.setLayoutManager(manager);
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        // Smoother scrolling: keep more ready-made rows around and prefetch the next thumbnails
        mList.setItemViewCacheSize(8);
        manager.setInitialPrefetchItemCount(4);
        mAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                onFeedChanged();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                onFeedChanged();
            }
        });
        final int edge = (int) (8 * getResources().getDisplayMetrics().density);
        mList.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                // Shorts tiles: line the 2 column grid up with the screen edges
                int position = parent.getChildAdapterPosition(view);
                if (!mAdapter.isShortTile(position) || !(view.getLayoutParams() instanceof GridLayoutManager.LayoutParams)) {
                    return;
                }
                if (((GridLayoutManager.LayoutParams) view.getLayoutParams()).getSpanIndex() == 0) {
                    outRect.left = edge;
                } else {
                    outRect.right = edge;
                }
            }
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                checkScrollEnd();
                if (dy > 0) {
                    prefetchThumbs();
                }
            }
        });
    }

    protected void checkScrollEnd() {
        if (mList == null || mAdapter == null) {
            return;
        }
        GridLayoutManager manager = (GridLayoutManager) mList.getLayoutManager();
        if (manager == null) {
            return;
        }
        int last = manager.findLastVisibleItemPosition();
        if (last >= mAdapter.getItemCount() - 6) {
            Video video = mAdapter.getScrollEndVideo();
            if (video != null && !hasPendingActions()) {
                onListScrollEnd(video);
            }
        }
    }

    /**
     * Short lists never scroll, so ask for more right after an update.
     */
    protected void checkScrollEndLater() {
        if (mList != null) {
            mList.post(this::checkScrollEnd);
        }
    }

    protected abstract boolean hasPendingActions();

    protected abstract void onListScrollEnd(Video lastVideo);

    protected void showProgress(boolean show) {
        // An empty feed shows placeholder cards; a feed that is loading more keeps the thin line
        boolean skeleton = show && wantsSkeleton() && mAdapter != null && mAdapter.isEmpty();
        if (skeleton) {
            showSkeleton();
        } else if (!show) {
            hideSkeleton();
        }
        if (mProgressBar != null) {
            mProgressBar.setVisibility(show && !skeleton ? View.VISIBLE : View.GONE);
        }
        if (!show) {
            updateEmptyState();
        }
    }

    /** Placeholder cards while the feed is empty (screens whose list isn't a video feed turn this off) */
    protected boolean wantsSkeleton() {
        return true;
    }

    /** Placeholders right away (pull to refresh: the old cards are about to be replaced) */
    protected void showLoadingPlaceholders() {
        if (wantsSkeleton()) {
            showSkeleton();
            if (mProgressBar != null) {
                mProgressBar.setVisibility(View.GONE);
            }
        }
    }

    private void showSkeleton() {
        if (mList == null || mProgressBar == null || !(mProgressBar.getParent() instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup container = (android.view.ViewGroup) mProgressBar.getParent();
        if (mSkeleton == null) {
            mSkeleton = new PhoneSkeleton(this);
            View anchor = mList;
            while (anchor.getParent() != container && anchor.getParent() instanceof View) {
                anchor = (View) anchor.getParent();
            }
            int index = anchor.getParent() == container ? container.indexOfChild(anchor) + 1 : container.getChildCount();
            container.addView(mSkeleton, index, PhoneSkeleton.layoutParams());
        }
        mSkeleton.setPadding(0, mList.getPaddingTop() + (int) (4 * getResources().getDisplayMetrics().density), 0, 0);
        hideMessage();
        mSkeleton.start();
    }

    private void hideSkeleton() {
        if (mSkeleton != null && mSkeleton.getVisibility() == View.VISIBLE) {
            mSkeleton.stop();
            // Let the real cards fade in where the placeholders were
            if (mList != null && mAdapter != null && !mAdapter.isEmpty()) {
                mList.setAlpha(0f);
                mList.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    private void onFeedChanged() {
        if (mAdapter == null) {
            return;
        }
        // Loading started before the old cards were cleared (a refresh): switch the thin line to placeholders
        if (mAdapter.isEmpty() && mProgressBar != null && mProgressBar.getVisibility() == View.VISIBLE && wantsSkeleton()) {
            showSkeleton();
            mProgressBar.setVisibility(View.GONE);
            return;
        }
        if (!mAdapter.isEmpty()) {
            hideSkeleton();
            if (mProgressBar != null && mSkeleton != null && mSkeleton.getVisibility() != View.VISIBLE
                    && mProgressBar.getVisibility() == View.VISIBLE && !hasPendingActions()) {
                mProgressBar.setVisibility(View.GONE);
            }
        }
    }

    private int mPrefetchedUpTo = -1;

    /** Downloads the thumbnails just below the screen so they are ready when they scroll in */
    private void prefetchThumbs() {
        if (mList == null || mAdapter == null || !(mList.getLayoutManager() instanceof GridLayoutManager)) {
            return;
        }
        int last = ((GridLayoutManager) mList.getLayoutManager()).findLastVisibleItemPosition();
        if (last < 0 || last + 6 <= mPrefetchedUpTo) {
            return;
        }
        java.util.List<Video> videos = mAdapter.videosFrom(last + 1, 6);
        for (Video video : videos) {
            PhoneUi.prefetchThumb(this, video);
        }
        mPrefetchedUpTo = last + 6;
    }

    protected boolean isProgressShowing() {
        return mProgressBar != null && mProgressBar.getVisibility() == View.VISIBLE;
    }

    protected void updateEmptyState() {
        if (mEmptyView == null) {
            return;
        }
        boolean empty = mAdapter == null || mAdapter.isEmpty();
        if (!empty) {
            mEmptyView.setVisibility(View.GONE);
        } else if (!isProgressShowing() && mEmptyView.getVisibility() != View.VISIBLE) {
            showMessage(getString(R.string.phone_empty), null, null);
        }
    }

    protected void hideMessage() {
        if (mEmptyView != null) {
            mEmptyView.setVisibility(View.GONE);
        }
    }

    protected void showMessage(String message, String action, Runnable onAction) {
        if (mEmptyView == null) {
            return;
        }
        mEmptyText.setText(message);
        if (action != null && onAction != null) {
            mEmptyAction.setText(action);
            mEmptyAction.setVisibility(View.VISIBLE);
            mEmptyAction.setOnClickListener(v -> onAction.run());
        } else {
            mEmptyAction.setVisibility(View.GONE);
        }
        mEmptyView.setVisibility(View.VISIBLE);
    }

    protected void showError(ErrorFragmentData data) {
        if (data == null) {
            return;
        }
        if (mAdapter != null) {
            mAdapter.clear();
        }
        showMessage(data.getMessage(), data.getActionText(), data::onAction);
    }

    // endregion

    // region Navigation

    @Override
    public void finish() {
        // Back on the root screen leaves the app, otherwise return to the previous screen.
        if (!getViewManager().hasParentView(this)) {
            Utils.properlyFinishTheApp(this);
        } else {
            finishReally();
        }
    }

    @Override
    public void finishReally() {
        // Mandatory, keeps SmartTube's view stack in order (same as LeanbackActivity).
        getViewManager().startParentView(this);
        super.finishReally();
    }

    // endregion
}
