package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone home screen. Implements the same BrowseView the TV BrowseFragment does, so BrowsePresenter drives it unchanged.
 * SmartTube's sections are grouped into five bottom tabs, and the sections of a tab become chips.
 */
public class PhoneBrowseActivity extends PhoneActivity implements BrowseView, FeedAdapter.Listener,
        MediaServiceManager.AccountChangeListener {
    private static final int TAB_HOME = 0;
    private static final int TAB_SHORTS = 1;
    private static final int TAB_SUBS = 2;
    private static final int TAB_NOTIFICATIONS = 3;
    private static final int TAB_YOU = 4;
    private static final int TAB_COUNT = 5;

    private BrowsePresenter mPresenter;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout mRefresh;
    private boolean mUserRefreshing;
    private final List<BrowseSection> mSections = new ArrayList<>();
    private final int[] mTabSectionIds = {-1, -1, -1, -1, -1};
    private BrowseSection mCurrentSection;
    private int mCurrentTab = TAB_HOME;

    private LinearLayout mBottomBar;
    private LinearLayout mChips;
    private HorizontalScrollView mChipsScroll;
    private View mAccountHeader;
    private final View[] mTabViews = new View[TAB_COUNT];
    private boolean mIsFirstResume = true;
    private boolean mIsInitialSelection = true;
    private boolean mShortsAutoplay;
    private BrowseSection mShortsReturnSection;
    private BrowseSection mLoadedSection; // what the presenter actually loads (Home falls back when signed out)
    private View mSignInCard;
    private PhoneSubscriptionsHeader mSubsHeader; // channels row and filter chips above the Subscriptions feed
    private PhoneYouDashboard mYou; // the You tab's front page: places, then History and Playlists as rows of cards
    private boolean mYouDashboard; // the You tab shows its front page, not one of its sections
    /**
     * A page was opened from the You page (a playlist, Watch later, Liked videos, Downloads, a video): coming back
     * shows the You page again, whatever the presenter re-selects meanwhile (it would put Home back)
     */
    private boolean mBackToYou;
    private long mKeepYouUntil;
    private VideoGroup mSubsChannelGroup; // the selected channel's videos, shown instead of the feed of all channels
    private Object mSubsAllContent; // the feed of all channels, put back when the channel is unselected
    private io.reactivex.disposables.Disposable mSubsChannelAction;
    private ImageView mTopAvatar;
    private SettingsGroup mSettingsGroup; // last Settings content, rebuilt when the notification setting changes

    /** The task Home lives in: the player belongs in the same one (one card in recents) */
    static int sTaskId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sTaskId = getTaskId();
        setContentView(R.layout.phone_activity_browse);

        mBottomBar = findViewById(R.id.phone_bottom_bar);
        mChips = findViewById(R.id.phone_chips);
        mChipsScroll = findViewById(R.id.phone_chips_scroll);
        mAccountHeader = findViewById(R.id.phone_account_header);

        applyInsets(findViewById(R.id.phone_root), findViewById(R.id.phone_top_bar), mBottomBar);
        setupList(this);
        setupPullToRefresh();
        setupBars();
        setupTopBar();
        setupTabs();

        MediaServiceManager.instance().addAccountListener(this);

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        CrashReport.showIfAny(this);
        mList.postDelayed(() -> PhoneRebrandBanner.showIfPending(this), 900); // once, after the rename to StreamTube
        mList.postDelayed(this::askNotificationPermission, 1500);
        handleShortcut(getIntent(), 0);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleShortcut(intent, 0);
    }

    /** App shortcuts (long press the app icon), see res/xml/phone_shortcuts.xml */
    static final String EXTRA_SHORTCUT = "org.smarttube.phone.SHORTCUT";

    /** Opens what an app shortcut asks for, once Home's sections are there (they load right after start) */
    private void handleShortcut(android.content.Intent intent, int attempt) {
        String shortcut = intent != null ? intent.getStringExtra(EXTRA_SHORTCUT) : null;
        if (shortcut == null || isFinishing()) {
            return;
        }
        if ("search".equals(shortcut)) {
            intent.removeExtra(EXTRA_SHORTCUT);
            com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter.instance(this).startSearch(null);
            return;
        }
        // A tab tapped on the Shorts screen
        if ("tab_home".equals(shortcut) || "tab_you".equals(shortcut) || "tab_notifications".equals(shortcut)) {
            if (mSections.isEmpty()) {
                if (attempt < 20) {
                    mList.postDelayed(() -> handleShortcut(intent, attempt + 1), 250);
                }
                return;
            }
            intent.removeExtra(EXTRA_SHORTCUT);
            if ("tab_notifications".equals(shortcut)) {
                PhoneNotificationsActivity.open(this);
            } else {
                selectTab("tab_you".equals(shortcut) ? TAB_YOU : TAB_HOME);
            }
            return;
        }
        int sectionId = "shorts".equals(shortcut) ? MediaGroup.TYPE_SHORTS
                : "history".equals(shortcut) ? MediaGroup.TYPE_HISTORY : MediaGroup.TYPE_SUBSCRIPTIONS;
        BrowseSection section = sectionById(sectionId);
        if (section == null) {
            if (attempt < 20) {
                mList.postDelayed(() -> handleShortcut(intent, attempt + 1), 250);
            }
            return;
        }
        intent.removeExtra(EXTRA_SHORTCUT);
        if (sectionId == MediaGroup.TYPE_SHORTS) {
            playShorts();
        } else if (sectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
            selectTab(TAB_SUBS);
        } else {
            focusSection(section);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sTaskId = getTaskId();
        // New build waiting? A small Update card (can be turned off on the update screen)
        getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && !PhoneRebrandBanner.isShowing()) {
                PhoneUpdater.checkInBackground(this);
            }
        }, 2500);
        if (!mIsFirstResume) {
            mPresenter.onViewResumed();
        }
        if (mBackToYou) {
            mKeepYouUntil = android.os.SystemClock.uptimeMillis() + 2000;
            if (!mYouDashboard || mCurrentTab != TAB_YOU) {
                showYouDashboard();
            }
        }
        mIsFirstResume = false;
        updateAccountViews(); // also the tabs: the bell's dot for new videos
        refreshSettingsRows(); // back from Android's notification settings
        refreshIncognito();
        PhoneNewVideos.schedule(this); // the background look at the Subscriptions feed (kept if already planned)
        if (sectionById(MediaGroup.TYPE_SHORTS) != null) {
            // The Shorts tab opens at once: its feed loads now, after Home had its turn
            mList.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    PhoneShortsQueue.preload(this, null);
                }
            }, 2500);
        }
    }

    /** The incognito glasses next to search while watch history is paused; tap them to turn incognito off */
    private void refreshIncognito() {
        View icon = findViewById(R.id.phone_incognito);
        if (icon == null) {
            return;
        }
        icon.setVisibility(PhoneIncognito.isOn(this) ? View.VISIBLE : View.GONE);
        icon.setOnClickListener(v -> {
            PhoneIncognito.toggle(this);
            refreshIncognito();
            refreshSettingsRows();
        });
    }

    /**
     * YouTube links open in StreamTube only once Android allows it (Android 12+ never picks an app that isn't the link's
     * owner by itself): the app's "Open by default" settings, where "Add link" turns on youtube.com, youtu.be...
     */
    private void openLinkSettings() {
        android.content.Intent intent = Build.VERSION.SDK_INT >= 31
                ? new android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()))
                : new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
            android.widget.Toast.makeText(this, R.string.phone_open_links_hint, android.widget.Toast.LENGTH_LONG).show();
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", getPackageName(), null)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PhoneNotifications.REQUEST_FROM_SETTINGS) {
            PhoneNotifications.onPermissionResult(this);
        }
        refreshSettingsRows();
    }

    private void refreshSettingsRows() {
        if (mSettingsGroup != null && mCurrentSection != null && mCurrentSection.getId() == MediaGroup.TYPE_SETTINGS) {
            updateSection(mSettingsGroup);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mBackToYou = mCurrentTab == TAB_YOU && mYouDashboard;
        mPresenter.onViewPaused();
    }

    @Override
    protected int miniPlayerBottomOffset() {
        // Above the tabs (they include the navigation bar)
        int tabs = mBottomBar != null && mBottomBar.getVisibility() == View.VISIBLE && mBottomBar.getHeight() > 0
                ? mBottomBar.getHeight() : super.miniPlayerBottomOffset();
        return tabs + PhoneUpdateBanner.heightOn(this); // and above the "Update available" banner
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MediaServiceManager.instance().removeAccountListener(this);
        if (mYou != null) {
            mYou.release();
        }
        if (mSubsHeader != null) {
            mSubsHeader.release();
        }
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsChannelAction);
        mPresenter.onViewDestroyed();
    }

    @Override
    public void onBackPressed() {
        if (isSection(MediaGroup.TYPE_CHANNEL_UPLOADS) && sectionById(MediaGroup.TYPE_SUBSCRIPTIONS) != null) {
            // All channels was opened from the Subscriptions feed: go back to it
            focusSection(sectionById(MediaGroup.TYPE_SUBSCRIPTIONS));
            return;
        }
        if (mCurrentTab == TAB_YOU && !mYouDashboard) {
            showYouDashboard(); // from History, Settings...: back to the You page
            return;
        }
        if (mCurrentTab != TAB_HOME && hasTab(TAB_HOME)) {
            selectTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onAccountChanged(Account account) {
        runOnUiThread(() -> {
            updateAccountViews();
            if (mYouDashboard) {
                mYou.show(); // another account: its History and Playlists
            }
            if (mCurrentSection != null && mCurrentSection.getId() == MediaGroup.TYPE_HOME) {
                focusSection(mCurrentSection);
            }
        });
    }

    private static boolean isSignedIn() {
        Account account = MediaServiceManager.instance().getSelectedAccount();
        return account != null && !account.isEmpty();
    }

    private BrowseSection sectionById(int id) {
        int index = indexOfSection(id);
        return index != -1 ? mSections.get(index) : null;
    }

    /**
     * Signed out, the Home feed is empty: show popular videos there instead, with a sign in card on top.
     */
    private BrowseSection signedOutHomeSource() {
        int[] candidates = {MediaGroup.TYPE_TRENDING, MediaGroup.TYPE_MUSIC, MediaGroup.TYPE_GAMING, MediaGroup.TYPE_NEWS, MediaGroup.TYPE_SPORTS};
        for (int id : candidates) {
            BrowseSection section = sectionById(id);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    // region UI setup

    /**
     * The feed scrolls under the top and bottom bars. The top bar slides away while scrolling down
     * (like the YouTube app); in Liquid Glass mode both bars are translucent.
     */
    private void setupBars() {
        View topBar = findViewById(R.id.phone_top_bar);
        topBar.setBackgroundResource(R.color.phone_bg);
        mBottomBar.setBackgroundResource(R.drawable.phone_bottom_bar_bg);

        View.OnLayoutChangeListener sync = (v, l, t, r, b, ol, ot, or, ob) -> v.post(this::syncBarPadding);
        topBar.addOnLayoutChangeListener(sync);
        mBottomBar.addOnLayoutChangeListener(sync);

        mList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                int hideable = topBar.getHeight() - topBar.getPaddingTop(); // keep the status bar area
                float y = Math.max(-hideable, Math.min(0, topBar.getTranslationY() - dy));
                if (!recyclerView.canScrollVertically(-1)) {
                    y = 0;
                }
                topBar.setTranslationY(y);
                // The loading line sits under the top bar: move it with the bar
                View progress = findViewById(R.id.phone_progress_bar);
                if (progress != null) {
                    progress.setTranslationY(y);
                }
            }
        });
    }

    private void setupPullToRefresh() {
        mRefresh = findViewById(R.id.phone_refresh);
        if (mRefresh == null) {
            return;
        }
        mRefresh.setColorSchemeColors(getResources().getColor(R.color.phone_accent));
        mRefresh.setProgressBackgroundColorSchemeColor(getResources().getColor(R.color.phone_surface_2));
        mRefresh.setOnRefreshListener(() -> {
            if (mYouDashboard) {
                mRefresh.setRefreshing(false);
                mYou.show(); // History and Playlists again
                return;
            }
            if (mCurrentSection == null || mCurrentSection.getId() == MediaGroup.TYPE_SETTINGS) {
                mRefresh.setRefreshing(false);
                return;
            }
            mUserRefreshing = true;
            if (isSubscriptionsFeed()) {
                mSubsHeader.onShown(true);
            }
            showLoadingPlaceholders();
            mRefresh.setRefreshing(false); // the pulsing placeholders take over from the spinner (like YouTube)
            mPresenter.refresh(false);
            mRefresh.postDelayed(this::stopRefreshing, 15_000); // never spin forever
        });
    }

    private void stopRefreshing() {
        mUserRefreshing = false;
        if (mRefresh != null && mRefresh.isRefreshing()) {
            mRefresh.setRefreshing(false);
        }
    }

    @Override
    protected boolean wantsSkeleton() {
        // Settings isn't a video feed, and neither is the You page
        return !mYouDashboard && (mCurrentSection == null || mCurrentSection.getId() != MediaGroup.TYPE_SETTINGS);
    }

    private void syncBarPadding() {
        View topBar = findViewById(R.id.phone_top_bar);
        int top = topBar.getHeight();
        // The status bar area stays covered while the top bar slides away underneath
        View scrim = findViewById(R.id.phone_status_scrim);
        android.view.ViewGroup.LayoutParams scrimParams = scrim.getLayoutParams();
        if (scrimParams.height != topBar.getPaddingTop()) {
            scrimParams.height = topBar.getPaddingTop();
            scrim.setLayoutParams(scrimParams);
        }
        if (mRefresh != null && mRefresh.getProgressViewEndOffset() != top + dp(24)) {
            mRefresh.setProgressViewOffset(false, top - dp(40), top + dp(24)); // the spinner comes out under the top bar
        }
        int bottom = mBottomBar.getHeight();
        if (mList.getPaddingTop() != top || mList.getPaddingBottom() != bottom + dp(8)) {
            mList.setPadding(mList.getPaddingLeft(), top, mList.getPaddingRight(), bottom + dp(8));
        }
        View progress = findViewById(R.id.phone_progress_bar);
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) progress.getLayoutParams();
        if (params.topMargin != top) {
            params.topMargin = top;
            progress.setLayoutParams(params);
        }
    }

    private void setupTopBar() {
        findViewById(R.id.phone_search).setOnClickListener(v -> SearchPresenter.instance(this).startSearch(null));
        findViewById(R.id.phone_settings_button).setOnClickListener(v -> {
            BrowseSection settings = sectionById(MediaGroup.TYPE_SETTINGS);
            if (settings != null) {
                focusSection(settings);
            }
        });
        mTopAvatar = findViewById(R.id.phone_top_avatar);
        mTopAvatar.setOnClickListener(v -> selectTab(TAB_YOU));
        mSignInCard = getLayoutInflater().inflate(R.layout.phone_signin_card, mList, false);
        mSignInCard.findViewById(R.id.phone_signin_card_button).setOnClickListener(v -> YTSignInPresenter.instance(this).start());
        mYou = new PhoneYouDashboard(this, new PhoneYouDashboard.Callback() {
            @Override
            public boolean hasSection(int sectionId) {
                return sectionById(sectionId) != null;
            }

            @Override
            public String sectionTitle(int sectionId) {
                BrowseSection section = sectionById(sectionId);
                return section != null ? section.getTitle() : "";
            }

            @Override
            public void openSection(int sectionId) {
                BrowseSection section = sectionById(sectionId);
                if (section != null) {
                    focusSection(section);
                }
            }

            @Override
            public void openDownloads() {
                PhoneDownloadsActivity.open(PhoneBrowseActivity.this);
            }

            @Override
            public boolean isSignedIn() {
                return PhoneBrowseActivity.this.isSignedIn();
            }

            @Override
            public FeedAdapter.Listener listener() {
                return PhoneBrowseActivity.this;
            }
        });
        mSubsHeader = new PhoneSubscriptionsHeader(this, mList, new PhoneSubscriptionsHeader.Callback() {
            @Override
            public void onChannelSelected(Video channel) {
                showSubscriptionsChannel(channel);
            }

            @Override
            public void onChannelLongClicked(Video channel) {
                onVideoLongClicked(channel);
            }

            @Override
            public void onFilterChanged(FeedAdapter.VideoFilter filter) {
                mAdapter.setFilter(filter);
                mList.scrollToPosition(0);
                checkScrollEndLater(); // few matches: load more of the feed
            }
        });
        findViewById(R.id.phone_logo).setOnClickListener(v -> scrollToTopOrRefresh());
    }

    // Bottom tabs as in the YouTube app: its icons (2px lines), the chosen tab's icon filled, all in the text colour
    private static final int[] TAB_ICONS = {R.drawable.ic_phone_tab_home, R.drawable.ic_phone_tab_shorts,
            R.drawable.ic_phone_tab_subs, R.drawable.ic_phone_tab_bell, R.drawable.ic_phone_account};
    private static final int[] TAB_ICONS_ON = {R.drawable.ic_phone_tab_home_on, R.drawable.ic_phone_tab_shorts_on,
            R.drawable.ic_phone_tab_subs_on, R.drawable.ic_phone_tab_bell, R.drawable.ic_phone_account};

    /** A red dot at the bell's top right: new videos nobody has looked at yet */
    private android.graphics.drawable.Drawable unreadDot() {
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(getResources().getColor(R.color.phone_accent));
        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {dot});
        layer.setLayerSize(0, dp(8), dp(8));
        layer.setLayerGravity(0, Gravity.TOP | Gravity.END);
        return layer;
    }

    private void setupTabs() {
        int[] icons = TAB_ICONS;
        String[] labels = {
                getString(R.string.header_home),
                getString(R.string.header_shorts),
                getString(R.string.header_subscriptions),
                getString(R.string.phone_tab_notifications),
                getString(R.string.phone_tab_you)
        };

        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);

        for (int i = 0; i < TAB_COUNT; i++) {
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(9), 0, dp(7));
            if (ripple.resourceId != 0) {
                tab.setBackgroundResource(ripple.resourceId);
            }

            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            tab.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setMaxLines(1);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            // Each label stays inside its own fifth of the bar: on narrow phones or with a bigger font size, "Subscriptions"
            // and "Notifications" shrink to fit instead of running into each other
            label.setAutoSizeTextTypeUniformWithConfiguration(7, 11, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                    getResources().getDisplayMetrics()));
            labelParams.leftMargin = dp(2);
            labelParams.rightMargin = dp(2);
            labelParams.topMargin = dp(4);
            tab.addView(label, labelParams);

            final int index = i;
            tab.setOnClickListener(v -> {
                mKeepYouUntil = 0; // the user picks the tab now
                if (index == TAB_SHORTS) {
                    playShorts();
                } else if (index == TAB_NOTIFICATIONS) {
                    PhoneNotificationsActivity.open(this); // its own screen, like Shorts
                } else if (index == TAB_SUBS && mCurrentTab == TAB_SUBS && !isSubscriptionsFeed()) {
                    selectTab(TAB_SUBS); // from All channels back to the feed
                } else if (index == TAB_YOU && mCurrentTab == TAB_YOU && !mYouDashboard) {
                    showYouDashboard(); // from History, Settings...: back to the You page
                } else if (index == mCurrentTab) {
                    scrollToTopOrRefresh();
                } else {
                    selectTab(index);
                    // The chosen tab's icon pops
                    View tabIcon = ((LinearLayout) v).getChildAt(0);
                    tabIcon.setScaleX(0.8f);
                    tabIcon.setScaleY(0.8f);
                    tabIcon.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                }
            });

            mTabViews[i] = tab;
            mBottomBar.addView(tab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }

        updateTabs();
    }

    private void updateTabs() {
        for (int i = 0; i < TAB_COUNT; i++) {
            View tab = mTabViews[i];
            if (tab == null) {
                continue;
            }
            // Notifications is the app's own list of new videos (PhoneNewVideos), not a SmartTube section
            boolean visible = i == TAB_YOU || i == TAB_NOTIFICATIONS || hasTab(i);
            tab.setVisibility(visible ? View.VISIBLE : View.GONE);
            boolean selected = i == mCurrentTab;
            int color = getResources().getColor(R.color.phone_text);
            LinearLayout layout = (LinearLayout) tab;
            ImageView icon = (ImageView) layout.getChildAt(0);
            // The You tab shows the account picture untinted
            boolean picture = i == TAB_YOU && icon.getTag() != null;
            if (!picture) {
                icon.setImageResource(selected ? TAB_ICONS_ON[i] : TAB_ICONS[i]);
            }
            icon.setImageTintList(picture ? null : ColorStateList.valueOf(color));
            if (i == TAB_NOTIFICATIONS) {
                icon.setForeground(PhoneNewVideos.unread(this) > 0 ? unreadDot() : null);
            }
            TextView label = (TextView) layout.getChildAt(1);
            label.setTextColor(color);
            label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); // the filled icon marks the chosen tab
        }
    }

    private void updateChips() {
        mChips.removeAllViews();
        List<BrowseSection> sections = sectionsOfTab(mCurrentTab);

        // The Subscriptions feed has its own channels row and filter chips (All opens the Channels section).
        // The You tab has none: its page lists its places, and Back (or the You tab) leads back to it.
        View settingsButton = findViewById(R.id.phone_settings_button);
        settingsButton.setVisibility(mCurrentTab == TAB_YOU && sectionById(MediaGroup.TYPE_SETTINGS) != null ? View.VISIBLE : View.GONE);
        if ((sections.size() <= 1 && mCurrentTab != TAB_YOU) || isSubscriptionsFeed() || mCurrentTab == TAB_YOU) {
            mChipsScroll.setVisibility(View.GONE);
            return;
        }

        mChipsScroll.setVisibility(View.VISIBLE);

        for (BrowseSection section : sections) {
            TextView chip = new TextView(this);
            chip.setText(section.getTitle());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackgroundResource(R.drawable.phone_chip_bg);
            boolean selected = mCurrentSection != null && mCurrentSection.getId() == section.getId();
            chip.setSelected(selected);
            chip.setTextColor(getResources().getColor(selected ? R.color.phone_bg : R.color.phone_text));
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setOnClickListener(v -> {
                if (mCurrentSection == null || mCurrentSection.getId() != section.getId()) {
                    focusSection(section);
                } else {
                    scrollToTopOrRefresh();
                }
            });
            chip.setOnLongClickListener(v -> {
                mPresenter.onSectionLongPressed(section.getId());
                return true;
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
            params.setMarginStart(dp(4));
            params.setMarginEnd(dp(4));
            mChips.addView(chip, params);

            if (selected) {
                chip.post(() -> mChipsScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(16)), 0));
            }
        }
    }

    /**
     * Shorts tab: open the first Short full screen (swipe for more) and stay on the current tab underneath.
     */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || isFinishing()
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("phone_defaults", MODE_PRIVATE);
        if (!prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4712);
        }
    }

    private View mShortsCurtain;

    /**
     * The Shorts tab: straight into a Short. The Shorts feed is loaded ahead of time (see PhoneShortsQueue.preload),
     * so this normally opens at once; otherwise a black screen with the loading circle shows until it's there (not
     * Home's placeholder cards and error screen).
     */
    private void playShorts() {
        if (PhoneShortsQueue.isReady()) {
            openShortsQueue();
            return;
        }
        showShortsCurtain(true);
        PhoneShortsQueue.preload(this, () -> {
            showShortsCurtain(false);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (PhoneShortsQueue.isReady()) {
                openShortsQueue();
            } else {
                playShortsFromSection(); // the feed didn't come: SmartTube's Shorts section
            }
        });
    }

    private void openShortsQueue() {
        Video first = PhoneShortsQueue.startVideo();
        if (first == null) {
            playShortsFromSection();
            return;
        }
        PhoneShortsQueue.markPlayed(first);
        PhonePlaybackActivity.releaseDetachedPlayer();
        com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(this).openVideo(first);
    }

    private void showShortsCurtain(boolean show) {
        ViewGroup content = findViewById(android.R.id.content);
        if (show && mShortsCurtain == null && content != null) {
            FrameLayout curtain = new FrameLayout(this);
            curtain.setBackgroundColor(android.graphics.Color.BLACK);
            curtain.setClickable(true);
            android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
            curtain.addView(spinner, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
            curtain.setElevation(dp(30));
            content.addView(curtain, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mShortsCurtain = curtain;
        } else if (!show && mShortsCurtain != null) {
            View curtain = mShortsCurtain;
            mShortsCurtain = null;
            curtain.postDelayed(() -> {
                if (curtain.getParent() instanceof ViewGroup) {
                    ((ViewGroup) curtain.getParent()).removeView(curtain);
                }
            }, 600); // the player is opening over it by then
        }
    }

    /** The first way: SmartTube's Shorts section loads on Home, its first Short opens */
    private void playShortsFromSection() {
        BrowseSection shorts = sectionById(MediaGroup.TYPE_SHORTS);
        if (shorts == null) {
            return;
        }
        // Come back to where the user was (Home if they were already on Shorts), never to an empty Shorts tab
        BrowseSection back = mCurrentSection;
        if (back == null || back.getId() == MediaGroup.TYPE_SHORTS) {
            back = sectionById(MediaGroup.TYPE_HOME);
        }
        mShortsReturnSection = back;
        mShortsAutoplay = true;
        focusSection(shorts);
    }

    private void scrollToTopOrRefresh() {
        if (mList.canScrollVertically(-1)) {
            mList.smoothScrollToPosition(0);
        } else if (mYouDashboard) {
            mYou.show();
        } else {
            mPresenter.refresh(false);
        }
    }

    private void updateAccountViews() {
        Account account = MediaServiceManager.instance().getSelectedAccount();
        boolean signedIn = account != null && !account.isEmpty();

        if (mTopAvatar != null) {
            if (signedIn && account.getAvatarImageUrl() != null) {
                mTopAvatar.setImageTintList(null);
                PhoneUi.loadAvatar(mTopAvatar, account.getAvatarImageUrl());
            } else {
                mTopAvatar.setImageResource(R.drawable.ic_phone_account);
                mTopAvatar.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.phone_text)));
            }
        }

        // "You" tab icon becomes the account picture
        View youTab = mTabViews[TAB_YOU];
        if (youTab != null) {
            ImageView icon = (ImageView) ((LinearLayout) youTab).getChildAt(0);
            if (signedIn && account.getAvatarImageUrl() != null) {
                icon.setTag("avatar");
                icon.setImageTintList(null);
                PhoneUi.loadAvatar(icon, account.getAvatarImageUrl());
            } else {
                icon.setTag(null);
                icon.setImageResource(R.drawable.ic_phone_account);
            }
        }
        updateTabs();

        // Account card on the You tab
        ImageView avatar = findViewById(R.id.phone_account_avatar);
        TextView name = findViewById(R.id.phone_account_name);
        TextView email = findViewById(R.id.phone_account_email);
        TextView primary = findViewById(R.id.phone_account_primary);
        TextView secondary = findViewById(R.id.phone_account_secondary);

        if (signedIn) {
            avatar.setImageTintList(null);
            if (account.getAvatarImageUrl() != null) {
                PhoneUi.loadAvatar(avatar, account.getAvatarImageUrl());
            } else {
                avatar.setImageDrawable(new AvatarCache.LetterDrawable(account.getName()));
            }
            name.setText(!TextUtils.isEmpty(account.getName()) ? account.getName() : account.getEmail());
            email.setText(account.getEmail());
            email.setVisibility(TextUtils.isEmpty(account.getEmail()) ? View.GONE : View.VISIBLE);
            primary.setText(R.string.phone_switch_account);
            primary.setOnClickListener(v -> AccountSelectionPresenter.instance(this).show(true));
            secondary.setText(R.string.phone_manage_accounts);
            secondary.setOnClickListener(v -> AccountSettingsPresenter.instance(this).show());
        } else {
            avatar.setImageResource(R.drawable.ic_phone_account);
            avatar.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.phone_text_2)));
            name.setText(R.string.phone_not_signed_in);
            email.setText(R.string.phone_sign_in_prompt);
            email.setVisibility(View.VISIBLE);
            primary.setText(R.string.phone_sign_in);
            primary.setOnClickListener(v -> YTSignInPresenter.instance(this).start());
            secondary.setText(R.string.phone_manage_accounts);
            secondary.setOnClickListener(v -> AccountSettingsPresenter.instance(this).show());
        }
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // endregion

    // region Tabs and sections

    private static int tabOf(BrowseSection section) {
        switch (section.getId()) {
            case MediaGroup.TYPE_SHORTS:
                return TAB_SHORTS;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
            case MediaGroup.TYPE_CHANNEL_UPLOADS:
                return TAB_SUBS;
            case MediaGroup.TYPE_NOTIFICATIONS:
                return TAB_NOTIFICATIONS;
            case MediaGroup.TYPE_HISTORY:
            case MediaGroup.TYPE_USER_PLAYLISTS:
            case MediaGroup.TYPE_MY_VIDEOS:
            case MediaGroup.TYPE_PLAYBACK_QUEUE:
            case MediaGroup.TYPE_BLOCKED_CHANNELS:
            case MediaGroup.TYPE_SETTINGS:
                return TAB_YOU;
            default:
                return TAB_HOME;
        }
    }

    private List<BrowseSection> sectionsOfTab(int tab) {
        List<BrowseSection> result = new ArrayList<>();
        for (BrowseSection section : mSections) {
            if (tabOf(section) == tab) {
                result.add(section);
            }
        }
        return result;
    }

    private boolean hasTab(int tab) {
        for (BrowseSection section : mSections) {
            if (tabOf(section) == tab) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void updateEmptyState() {
        if (mYouDashboard) {
            hideMessage(); // the You page is the list's header: the list having no videos is how it should be
            return;
        }
        super.updateEmptyState();
    }

    /** The You tab's front page (see PhoneYouDashboard). Its sections open from there, in the same tab. */
    private void showYouDashboard() {
        clearSubscriptionsChannel();
        mYouDashboard = true;
        mCurrentTab = TAB_YOU;
        mCurrentSection = null;
        mLoadedSection = null;
        updateTabs();
        updateChips();
        mAccountHeader.setVisibility(View.VISIBLE);
        hideMessage();
        showProgress(false);
        mAdapter.setFilter(null);
        mAdapter.setMode(FeedAdapter.MODE_FLAT);
        mAdapter.setHeaderView(mYou.getView());
        mList.scrollToPosition(0);
        mYou.show();
    }

    private void selectTab(int tab) {
        if (tab == TAB_YOU) {
            showYouDashboard();
            return;
        }
        List<BrowseSection> sections = sectionsOfTab(tab);
        if (sections.isEmpty()) {
            return;
        }

        BrowseSection target = sections.get(0);
        if (tab == TAB_SUBS && sectionById(MediaGroup.TYPE_SUBSCRIPTIONS) != null) {
            // The Subscriptions tab always opens on the feed, like the YouTube app
            focusSection(sectionById(MediaGroup.TYPE_SUBSCRIPTIONS));
            return;
        }
        for (BrowseSection section : sections) {
            if (section.getId() == mTabSectionIds[tab]) {
                target = section;
                break;
            }
        }

        focusSection(target);
    }

    private void focusSection(BrowseSection section) {
        clearSubscriptionsChannel();
        mYouDashboard = false;
        mCurrentSection = section;
        mCurrentTab = tabOf(section);
        mTabSectionIds[mCurrentTab] = section.getId();

        updateTabs();
        updateChips();
        mAccountHeader.setVisibility(mCurrentTab == TAB_YOU ? View.VISIBLE : View.GONE);
        hideMessage();

        BrowseSection load = section;
        if (section.getId() == MediaGroup.TYPE_HOME && !isSignedIn()) {
            BrowseSection fallback = signedOutHomeSource();
            if (fallback != null) {
                load = fallback;
            }
        }
        mLoadedSection = load;

        boolean subsFeed = isSubscriptionsFeed() && isSignedIn();
        mAdapter.setFilter(null);
        mAdapter.setHeaderView(load != section ? mSignInCard : subsFeed ? mSubsHeader.getView() : null);
        if (subsFeed) {
            mSubsHeader.onShown(false);
        }
        mAdapter.setMode(load != section && load.getType() == BrowseSection.TYPE_ROW ? FeedAdapter.MODE_FEED : modeOf(load));
        mList.scrollToPosition(0);

        if (load.getType() == BrowseSection.TYPE_ERROR && load.getData() instanceof ErrorFragmentData) {
            showError((ErrorFragmentData) load.getData());
        }

        mPresenter.onSectionFocused(load.getId());
    }

    // region Subscriptions: one channel

    /**
     * Subscriptions: a channel tapped in the channels row. The feed shows only its videos and Shorts (more load while
     * scrolling, like any feed); null puts the feed of all channels back as it was.
     */
    private void showSubscriptionsChannel(Video channel) {
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsChannelAction);
        View progress = findViewById(R.id.phone_progress_bar);

        if (channel == null) {
            progress.setVisibility(View.GONE);
            mSubsChannelGroup = null;
            if (mSubsAllContent != null) {
                mAdapter.restoreContent(mSubsAllContent);
                mSubsAllContent = null;
                mList.scrollToPosition(0);
            }
            return;
        }

        if (mSubsAllContent == null) {
            mSubsAllContent = mAdapter.saveContent();
        }
        BrowseSection section = mCurrentSection;
        VideoGroup placeholder = VideoGroup.from(section);
        mSubsChannelGroup = placeholder; // until the videos arrive, late results of the full feed stay out
        mAdapter.restoreContent(null);
        progress.setVisibility(View.VISIBLE);

        com.liskovsoft.mediaserviceinterfaces.ContentService service =
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService();
        io.reactivex.Observable<com.liskovsoft.mediaserviceinterfaces.data.MediaGroup> uploads = channel.hasReloadPageKey()
                ? service.getGroupObserve(channel.getReloadPageKey())
                : service.getGroupObserve(channel.mediaItem != null ? channel.mediaItem
                        : com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem.from(channel));

        mSubsChannelAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(uploads, mediaGroup -> {
            progress.setVisibility(View.GONE);
            if (mSubsChannelGroup != placeholder || !isSubscriptionsFeed()) {
                return; // another channel was picked meanwhile, or the user left
            }
            VideoGroup group = VideoGroup.from(mediaGroup, section);
            group.setAction(VideoGroup.ACTION_REPLACE);
            mSubsChannelGroup = group; // scrolling continues this group through the presenter, like the feed
            mAdapter.update(group);
            mList.scrollToPosition(0);
            checkScrollEndLater();
        }, error -> {
            progress.setVisibility(View.GONE);
            if (mSubsChannelGroup == placeholder) {
                android.widget.Toast.makeText(this, R.string.phone_subs_channel_error, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Leaving or reloading the Subscriptions feed: forget the shown channel (the feed is loaded anew) */
    private void clearSubscriptionsChannel() {
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsChannelAction);
        mSubsChannelGroup = null;
        mSubsAllContent = null;
    }

    // endregion

    private boolean isSection(int id) {
        return mCurrentSection != null && mCurrentSection.getId() == id;
    }

    private boolean isSubscriptionsFeed() {
        return isSection(MediaGroup.TYPE_SUBSCRIPTIONS);
    }

    private static int modeOf(BrowseSection section) {
        switch (section.getType()) {
            case BrowseSection.TYPE_ROW:
                return section.getId() == MediaGroup.TYPE_HOME ? FeedAdapter.MODE_FEED : FeedAdapter.MODE_SHELVES;
            case BrowseSection.TYPE_SHORTS_GRID:
                return FeedAdapter.MODE_SHORTS;
            case BrowseSection.TYPE_MULTI_GRID:
                return FeedAdapter.MODE_MULTI;
            case BrowseSection.TYPE_SETTINGS_GRID:
                return FeedAdapter.MODE_SETTINGS;
            default:
                return FeedAdapter.MODE_FLAT;
        }
    }

    private int indexOfSection(int id) {
        for (int i = 0; i < mSections.size(); i++) {
            if (mSections.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    // endregion

    // region BrowseView

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }

        int existing = indexOfSection(section.getId());
        if (existing != -1 && (index == -1 || existing == index)) {
            return;
        }
        if (existing != -1) {
            mSections.remove(existing);
        }

        if (index == -1 || index > mSections.size()) {
            mSections.add(section);
        } else {
            mSections.add(index, section);
        }

        updateTabs();
        if (mCurrentSection != null && tabOf(section) == mCurrentTab) {
            updateChips();
        }
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }
        int index = indexOfSection(section.getId());
        if (index != -1) {
            mSections.remove(index);
        }
        updateTabs();
        updateChips();
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        updateTabs();
        updateChips();
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (mSections.isEmpty() || index < 0) {
            return;
        }
        if (mBackToYou && android.os.SystemClock.uptimeMillis() < mKeepYouUntil) {
            return; // back from a page opened on the You page: it stays (the presenter re-selects its last section)
        }
        BrowseSection target = mSections.get(Math.min(index, mSections.size() - 1));

        if (mIsInitialSelection) {
            // Phone app always starts on Home (SmartTube boots signed-out users into Music)
            mIsInitialSelection = false;
            BrowseSection home = sectionById(MediaGroup.TYPE_HOME);
            if (home != null) {
                focusSection(home);
                return;
            }
        }

        // The presenter restores the section it last loaded. When Home shows a stand-in feed (signed out),
        // that is the stand-in: keep Home selected instead of jumping to it.
        if (mLoadedSection != null && mCurrentSection != null && target.getId() == mLoadedSection.getId()
                && mLoadedSection != mCurrentSection) {
            return;
        }
        focusSection(mSections.get(Math.min(index, mSections.size() - 1)));
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }
        BrowseSection section = group.getSection();
        if (section != null && section.getId() == MediaGroup.TYPE_SHORTS && !mShortsAutoplay && PhoneShortsQueue.isActive()) {
            PhoneShortsQueue.append(group); // next batch for the Shorts player, even though Home is behind it
        }
        BrowseSection expected = mLoadedSection != null ? mLoadedSection : mCurrentSection;
        if (mYouDashboard || (section != null && expected != null && section.getId() != expected.getId())) {
            return; // late result of a section the user already left
        }
        if (mSubsChannelGroup != null && group.getId() != mSubsChannelGroup.getId()) {
            return; // the feed of all channels, while one channel is shown (it comes back when the channel is unselected)
        }
        hideMessage();

        if (mShortsAutoplay && group.getVideos() != null && !group.getVideos().isEmpty()
                && mCurrentSection != null && mCurrentSection.getId() == MediaGroup.TYPE_SHORTS) {
            mShortsAutoplay = false;
            PhoneShortsQueue.set(group);
            Video first = group.getVideos().get(0);
            PhonePlaybackActivity.releaseDetachedPlayer();
            mPresenter.onVideoItemSelected(first);
            mPresenter.onVideoItemClicked(first);
            Video last = group.getVideos().get(group.getVideos().size() - 1);
            boolean fetchMore = group.getVideos().size() < 5; // only the first Short came in (so it starts fast)
            BrowseSection back = mShortsReturnSection;
            if (back != null && back.getId() != MediaGroup.TYPE_SHORTS) {
                mList.postDelayed(() -> focusSection(back), 400);
            }
            if (fetchMore) {
                // After Home is back in place (switching sections cancels pending loads): the next Shorts
                mList.postDelayed(() -> mPresenter.onScrollEnd(last), 900);
            }
            return;
        }

        mAdapter.update(group);
        checkScrollEndLater();
        if (group.getVideos() != null && !group.getVideos().isEmpty()) {
            PhoneWarmup.startOnce(); // the first feed is on screen: get YouTube's session ready for the first video
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        if (group == null) {
            return;
        }
        mSettingsGroup = group;
        if (mYouDashboard) {
            return; // late result: Settings was left for the You page
        }
        hideMessage();
        mAdapter.setSettings(groupedSettings(group));
    }

    /**
     * The Settings list in groups with headings: SmartTube's settings pages (found by their titles) next to the
     * phone's own rows. TV-only pages are left out, and SmartTube's About page too (its updater and voice search
     * bridge aren't part of this app; "About StreamTube" is the phone's). A page this list doesn't know (added
     * upstream later) lands in a last group, so nothing gets lost.
     */
    private java.util.List<com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem> groupedSettings(SettingsGroup group) {
        java.util.Map<String, com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem> engine = new java.util.LinkedHashMap<>();
        if (group.getItems() != null) {
            for (com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem item : group.getItems()) {
                if (item != null && item.title != null && !PhoneTvOnly.isSetting(this, item.title)) {
                    engine.put(item.title, item);
                }
            }
        }
        engine.remove(getString(R.string.settings_about));

        java.util.List<com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem> items = new ArrayList<>();

        // Always first: the app updates itself from here
        items.add(PhoneSetting.row(PhoneUpdater.settingsTitle(this), R.drawable.ic_phone_update, () -> PhoneUpdater.open(this)));

        items.add(PhoneSetting.header(getString(R.string.phone_settings_account)));
        addEngineSetting(items, engine, R.string.settings_accounts);
        items.add(PhoneSetting.toggle(getString(R.string.phone_incognito), PhoneIncognito.isOn(this), R.drawable.ic_phone_incognito, () -> {
            PhoneIncognito.toggle(this);
            refreshIncognito();
            refreshSettingsRows();
        }));

        items.add(PhoneSetting.header(getString(R.string.phone_settings_appearance)));
        items.add(PhoneSetting.dropDown(getString(R.string.phone_theme), getString(PhoneTheme.valueRes(PhoneTheme.get(this))),
                R.drawable.ic_phone_brightness, this::pickTheme));
        addEngineSetting(items, engine, R.string.settings_main_ui);
        addEngineSetting(items, engine, R.string.settings_language_country);

        items.add(PhoneSetting.header(getString(R.string.phone_settings_playback)));
        addEngineSetting(items, engine, R.string.settings_player);
        if (android.os.Build.VERSION.SDK_INT >= 26 && !PhonePip.systemAllows(this)) {
            // Picture in picture is always on in the app; only Android's own switch for the app can stop it
            items.add(PhoneSetting.value(getString(R.string.phone_pip), getString(R.string.phone_pip_blocked),
                    R.drawable.ic_phone_collapse, () -> PhonePip.openSystemSettings(this)));
        }
        addEngineSetting(items, engine, R.string.subtitle_category_title);
        addEngineSetting(items, engine, R.string.content_block_provider);
        addEngineSetting(items, engine, R.string.dearrow_provider);

        items.add(PhoneSetting.header(getString(R.string.phone_settings_general)));
        addEngineSetting(items, engine, R.string.settings_general);
        addEngineSetting(items, engine, R.string.settings_search);
        items.add(PhoneSetting.row(getString(R.string.phone_open_links), R.drawable.ic_phone_link, this::openLinkSettings));
        items.add(PhoneSetting.value(getString(R.string.phone_notifications),
                getString(PhoneNotifications.isEnabled(this) ? R.string.phone_value_on : R.string.phone_value_off),
                R.drawable.ic_phone_bell, () -> PhoneNotifications.open(this)));

        items.add(PhoneSetting.toggle(getString(R.string.phone_new_videos), PhoneNewVideos.isEnabled(this), R.drawable.ic_phone_bell, () -> {
            PhoneNewVideos.setEnabled(this, !PhoneNewVideos.isEnabled(this));
            refreshSettingsRows();
        }));

        items.add(PhoneSetting.header(getString(R.string.phone_settings_backup)));
        addEngineSetting(items, engine, R.string.app_backup_restore);

        items.add(PhoneSetting.header(getString(R.string.phone_settings_about)));
        items.add(PhoneSetting.row(getString(R.string.phone_about_title), R.drawable.ic_phone_info, () -> PhoneAbout.show(this)));
        items.add(PhoneSetting.row(getString(R.string.phone_debug_log), R.drawable.ic_phone_info, () -> PhoneDebugLog.share(this)));

        if (!engine.isEmpty()) {
            items.add(PhoneSetting.header(getString(R.string.phone_settings_more)));
            items.addAll(engine.values());
        }

        // A heading with no rows under it (its pages are missing in this SmartTube version) is dropped
        for (int i = items.size() - 1; i >= 0; i--) {
            boolean header = items.get(i) instanceof PhoneSetting && ((PhoneSetting) items.get(i)).header;
            boolean lastOrBeforeHeader = i == items.size() - 1
                    || (items.get(i + 1) instanceof PhoneSetting && ((PhoneSetting) items.get(i + 1)).header);
            if (header && lastOrBeforeHeader) {
                items.remove(i);
            }
        }
        return items;
    }

    /** Theme: a drop-down under the row with the three choices, the current one ticked */
    private void pickTheme(View anchor) {
        int current = PhoneTheme.get(this);
        int[] modes = {PhoneTheme.DARK, PhoneTheme.LIGHT, PhoneTheme.SYSTEM};
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor, Gravity.END);
        for (int i = 0; i < modes.length; i++) {
            menu.getMenu().add(0, i, i, PhoneTheme.valueRes(modes[i])).setCheckable(true).setChecked(modes[i] == current);
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(item -> {
            int mode = modes[item.getItemId()];
            if (mode != current) {
                PhoneTheme.set(this, mode);
                recreate();
            }
            return true;
        });
        menu.show();
    }

    private void addEngineSetting(java.util.List<com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem> items,
            java.util.Map<String, com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem> engine, int titleRes) {
        com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem item = engine.remove(getString(titleRes));
        if (item != null) {
            items.add(item);
        }
    }

    @Override
    public void clearSection(BrowseSection section) {
        mAdapter.clear();
    }

    @Override
    public void selectSectionItem(int index) {
        if (index >= 0 && index < mAdapter.getItemCount()) {
            mList.scrollToPosition(index);
        }
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item == null) {
            return;
        }
        int index = mAdapter.indexOf(item);
        if (index != -1) {
            mList.scrollToPosition(index);
        }
    }

    @Override
    public void showError(ErrorFragmentData data) {
        if (mYouDashboard) {
            return; // late result of a section that was left for the You page
        }
        super.showError(data);
    }

    @Override
    public void showProgressBar(boolean show) {
        if (mYouDashboard && show) {
            return; // nothing of the presenter's loads on the You page
        }
        if (!show) {
            stopRefreshing();
        }
        showProgress(show);
    }

    @Override
    public boolean isProgressBarShowing() {
        return isProgressShowing();
    }

    @Override
    public void focusOnContent() {
        // Touch UI, nothing to focus
    }

    @Override
    public boolean isEmpty() {
        return mAdapter == null || mAdapter.isEmpty();
    }

    @Override
    public void updateBadge() {
        updateAccountViews();
    }

    // endregion

    // region FeedAdapter.Listener

    @Override
    public void onVideoClicked(Video video) {
        PhoneUi.prepareExpandLaunch(video);
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemClicked(video);
    }

    @Override
    public void onVideoLongClicked(Video video) {
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemLongClicked(video);
    }

    @Override
    public void onStripClicked(Video channel) {
        mAdapter.setSelectedStripItem(channel);
        // Selecting a channel loads its uploads below the strip (same as focusing it on TV).
        mPresenter.onVideoItemSelected(channel);
        if (!MainUIData.instance(this).isUploadsAutoLoadEnabled()) {
            mPresenter.onVideoItemClicked(channel);
        }
    }

    @Override
    public void onShelfScrollEnd(Video lastVideo) {
        if (!mPresenter.hasPendingActions()) {
            mPresenter.onScrollEnd(lastVideo);
        }
    }

    @Override
    protected boolean hasPendingActions() {
        return mPresenter.hasPendingActions();
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
        mPresenter.onScrollEnd(lastVideo);
    }

    // endregion
}
