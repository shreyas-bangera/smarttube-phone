package com.liskovsoft.smartyoutubetv2.tv.phone;


import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.tv.ui.adddevice.AddDeviceActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.main.MainApplication;
import com.liskovsoft.smartyoutubetv2.tv.ui.webbrowser.WebBrowserActivity;

/**
 * Phone build entry point: same app, but home, search and channel screens use the phone UI.
 */
public class PhoneApplication extends MainApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReport.install(this);
        PhoneRebrandBanner.init(this); // before the phone defaults: tells an update from SmartTube from a new install
        // Videos open faster: formats requested on tap, playback after 0.5 s of media, recommendations alongside
        com.liskovsoft.smartyoutubetv2.common.misc.FastStart.enable();
        // A missing seek preview strip is fetched once the video plays, not with one more request before it starts
        com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService.setDeferStoryboardFix(true);
        setupPhoneViews();
        applyPhoneDefaults();
        // Updates come from this project's GitHub releases: a manual check opens the update screen. The automatic
        // check at start is done by Home (PhoneUpdater.checkInBackground), so SmartTube's own one does nothing here.
        com.liskovsoft.smartyoutubetv2.common.misc.AppUpdateHook.setHandler((context, forceCheck) -> {
            if (forceCheck) {
                PhoneUpdater.open(context);
            }
        });
    }

    private void applyPhoneDefaults() {
        // "Remote control" (this device as a cast target) is a TV feature the phone build doesn't carry. A backup made
        // on a TV can still have it switched on: off, so the player doesn't keep listening for a remote.
        com.liskovsoft.smartyoutubetv2.common.prefs.RemoteControlData remote =
                com.liskovsoft.smartyoutubetv2.common.prefs.RemoteControlData.instance(this);
        if (remote.isDeviceLinkEnabled()) {
            remote.enableDeviceLink(false);
        }

        // Sheets open below the video on phones, so never shrink the video aside for them (a TV layout feature: the TV
        // settings panel covers the right part of the screen)
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData tweaks =
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.instance(this);
        if (!tweaks.isDontResizeVideoToFitDialogEnabled()) {
            tweaks.setDontResizeVideoToFitDialogEnabled(true);
        }
        // SmartTube's TV default: a new video decoder every time the picture moves to another surface (player to mini
        // player and back). The new decoder waits for the next key frame, so the picture froze for a moment while the
        // sound went on. Phones (Android 8+) switch the surface of the running decoder instead.
        if (tweaks.isSetOutputSurfaceWorkaroundEnabled()) {
            tweaks.setSetOutputSurfaceWorkaroundEnabled(false);
        }

        android.content.SharedPreferences prefs = getSharedPreferences("phone_defaults", MODE_PRIVATE);
        if (!prefs.getBoolean("background_playback_v1", false)) {
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData =
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            if (playerData.getBackgroundMode() == com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.BACKGROUND_MODE_DEFAULT) {
                playerData.setBackgroundMode(com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.BACKGROUND_MODE_SOUND);
            }
            prefs.edit().putBoolean("background_playback_v1", true).apply();
        }

        // Phones open videos in 1080p at most by default. SmartTube's TV default is 4K 60 fps on any device that can
        // decode it: every video then starts by downloading 4K pieces for a phone screen (slow, heavy on data).
        // Once: a quality picked in the player afterwards stays.
        if (prefs.getBoolean("background_pip_v1", false) && !prefs.getBoolean("background_pip_undone", false)) {
            // Build 92 switched Background playback from "sound only" to Android's floating window, which stuttered.
            // Back to sound only (Settings > General > Background playback still offers the floating window).
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData =
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            if (playerData.getBackgroundMode() == com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.BACKGROUND_MODE_PIP) {
                playerData.setBackgroundMode(com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.BACKGROUND_MODE_SOUND);
            }
            prefs.edit().putBoolean("background_pip_undone", true).apply();
        }

        {
            // SmartTube's "Picture in picture" background mode (set by builds 94 and 95) also opens the floating window
            // when the player closes (Back, the back swipe, the end of a video) and then opens Home inside it. The phone
            // keeps SmartTube on "sound only"; its own picture in picture (leaving the app with Home on a regular video)
            // is PhonePlaybackActivity.pipOnLeave, a switch in Settings.
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData =
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            if (playerData.getBackgroundMode() == com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.BACKGROUND_MODE_PIP) {
                playerData.setBackgroundMode(com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.BACKGROUND_MODE_SOUND);
            }
        }

        {
            // Build 95's Quality > Auto saved SmartTube's "default" video entry, which turns the video off (sound only,
            // a blank screen). Back to the phone's quality.
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData =
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem video =
                    playerData.getFormat(com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.TYPE_VIDEO);
            if (video != null && (video.isDefault() || video.getHeight() <= 0)) {
                playerData.setFormat(com.liskovsoft.sharedutils.helpers.DeviceHelpers.isVP9ResolutionSupported(1080)
                        ? com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VIDEO_FHD_VP9_60
                        : com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VIDEO_FHD_AVC_30);
            }
        }

        if (!prefs.getBoolean("phone_quality_v1", false)) {
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData =
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this);
            com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem video =
                    playerData.getFormat(com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.TYPE_VIDEO);
            if (video == null || video.getHeight() > 1080) {
                playerData.setFormat(com.liskovsoft.sharedutils.helpers.DeviceHelpers.isVP9ResolutionSupported(1080)
                        ? com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VIDEO_FHD_VP9_60
                        : com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VIDEO_FHD_AVC_30);
            }
            prefs.edit().putBoolean("phone_quality_v1", true).apply();
        }

        // No Notifications tab for now: hide the section again on builds that turned it on (SmartTube's default)
        if (!prefs.getBoolean("notifications_section_off_v2", false)) {
            com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService sidebar =
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService.instance(this);
            if (sidebar != null) {
                sidebar.enableSection(com.liskovsoft.mediaserviceinterfaces.data.MediaGroup.TYPE_NOTIFICATIONS, false);
            }
            prefs.edit().putBoolean("notifications_section_off_v2", true).apply();
        }
    }

    private void setupPhoneViews() {
        ViewManager viewManager = ViewManager.instance(this);

        viewManager.setRoot(PhoneBrowseActivity.class);
        viewManager.register(BrowseView.class, PhoneBrowseActivity.class); // root, no parent
        viewManager.register(SearchView.class, PhoneSearchActivity.class, PhoneBrowseActivity.class);
        viewManager.register(ChannelView.class, PhoneChannelActivity.class, PhoneBrowseActivity.class);
        viewManager.register(ChannelUploadsView.class, PhoneChannelUploadsActivity.class, PhoneBrowseActivity.class);

        // Remaining TV screens now return to the phone home
        viewManager.register(PlaybackView.class, PhonePlaybackActivity.class, PhoneBrowseActivity.class);
        viewManager.register(AppDialogView.class, PhoneDialogActivity.class, PhoneBrowseActivity.class);
        viewManager.register(SignInView.class, PhoneSignInActivity.class, PhoneBrowseActivity.class);
        viewManager.register(AddDeviceView.class, AddDeviceActivity.class, PhoneBrowseActivity.class);
        viewManager.register(WebBrowserView.class, WebBrowserActivity.class, PhoneBrowseActivity.class);
        // Phone only screens: Back returns to the screen they were opened from (without a parent, opening one
        // cleared the back history and Back closed the app)
        viewManager.register(PhoneUpdateActivity.class, PhoneUpdateActivity.class, PhoneBrowseActivity.class);
        viewManager.register(PhoneDownloadsActivity.class, PhoneDownloadsActivity.class, PhoneBrowseActivity.class);
        viewManager.register(PhoneNotificationsActivity.class, PhoneNotificationsActivity.class, PhoneBrowseActivity.class);

        // Search, channels and sign in open on top of the screen in front, in the app's own task. Without this they
        // could open inside the picture-in-picture player's window.
        viewManager.setOpenInCallerTask(PhoneSearchActivity.class, PhoneChannelActivity.class,
                PhoneChannelUploadsActivity.class, PhoneSignInActivity.class, PhoneBrowseActivity.class,
                PhonePlaybackActivity.class);
        // One Home and one player in the task (both singleTop). While the mini player plays, Home comes to the front
        // without closing the player behind it; otherwise Home closes the screens above it, as before.
        // Every screen the app opens itself is marked "no user action": Android then doesn't treat it as the user leaving
        // the app, so the player's floating window (Android 12+ automatic entry) never opens on an in-app screen change
        int inApp = android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
        viewManager.setLaunchFlags(activityClass -> {
            if (activityClass == PhonePlaybackActivity.class) {
                return inApp | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
            }
            if (activityClass == PhoneBrowseActivity.class) {
                return inApp | (PhoneMiniPlayer.isActive() ? android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        : android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            return inApp;
        });
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity activity) {
                lifecycle(activity, "resumed");
                if (android.os.Build.VERSION.SDK_INT < 24 || !activity.isInPictureInPictureMode()) {
                    viewManager.setCallerActivity(activity);
                }
            }

            @Override
            public void onActivityDestroyed(android.app.Activity activity) {
                lifecycle(activity, "destroyed");
                viewManager.clearCallerActivity(activity);
            }

            @Override
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {
            }

            @Override
            public void onActivityStarted(android.app.Activity activity) {
                lifecycle(activity, "started");
                if (!(activity instanceof PhonePlaybackActivity)) {
                    PhonePip.onScreenStarted();
                }
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
                lifecycle(activity, "paused");
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {
                lifecycle(activity, "stopped");
                if (!(activity instanceof PhonePlaybackActivity)) {
                    PhonePip.onScreenStopped(activity);
                }
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) {
            }
        });
    }
    /** Every screen's lifecycle, for the debug log (where the floating window and the mini player go wrong) */
    private static void lifecycle(android.app.Activity activity, String event) {
        boolean pip = android.os.Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode();
        android.util.Log.d("PhonePip", activity.getClass().getSimpleName() + " " + event + " (task " + activity.getTaskId()
                + (pip ? ", floating" : "") + ")");
    }
}
