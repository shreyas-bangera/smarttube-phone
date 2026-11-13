package com.liskovsoft.smartyoutubetv2.common.app.views;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;

import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.locale.LocaleUpdater;
import com.liskovsoft.sharedutils.misc.WeakHashSet;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class ViewManager {
    private static final String TAG = ViewManager.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static ViewManager sInstance;
    private final Context mContext;
    private final Map<Class<?>, Class<? extends Activity>> mViewMapping;
    private final Map<Class<? extends Activity>, Class<? extends Activity>> mParentMapping;
    private final Stack<Class<? extends Activity>> mActivityStack;
    private Class<? extends Activity> mRootActivity;
    private Class<? extends Activity> mDefaultTop;
    private long mPrevThrottleTimeMS;
    private boolean mIsMoveToBackEnabled;
    private boolean mIsFinished;
    private boolean mIsPlayerOnlyModeEnabled;
    private long mPendingActivityMs;
    private Class<?> mPendingActivityClass;
    private WeakHashSet<Runnable> mOnFinish;
    private java.lang.ref.WeakReference<Activity> mCallerActivity;
    private final java.util.Set<Class<?>> mCallerTaskActivities = new java.util.HashSet<>();

    private ViewManager(Context context) {
        mContext = context;
        mViewMapping = new HashMap<>();
        mParentMapping = new HashMap<>();
        mActivityStack = new Stack<>();
    }

    public static ViewManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new ViewManager(context.getApplicationContext());
        }

        return sInstance;
    }

    public void register(Class<?> viewClass, Class<? extends Activity> activityClass) {
        register(viewClass, activityClass, null);
    }

    public void register(Class<?> viewClass, Class<? extends Activity> activityClass, Class<? extends Activity> parentActivityClass) {
        mViewMapping.put(viewClass, activityClass);

        if (parentActivityClass != null) {
            mParentMapping.put(activityClass, parentActivityClass);
        }
    }

    public void unregister(Class<?> viewClass) {
        mViewMapping.remove(viewClass);
    }

    public Class<? extends Activity> getActivity(Class<?> viewClass) {
        return mViewMapping.get(viewClass);
    }
    
    public Class<?> getView(Class<? extends Activity> activityClass) {
        return Helpers.getKey(mViewMapping, activityClass);
    }

    public Class<? extends Activity> getRootActivity() {
        return mRootActivity;
    }

    /**
     * Use carefully<br/>
     * On running activity, this method invokes standard activity lifecycle: pause, resume etc...
     */
    public void startView(Class<?> viewClass) {
        startView(viewClass, false);
    }

    /**
     * Use carefully<br/>
     * On running activity, this method invokes standard activity lifecycle: pause, resume etc...
     */
    public void startView(Class<?> viewClass, boolean forceStart) {
        // Skip starting activity twice to get rid of pausing/resuming activity cycle
        if (Utils.isAppInForegroundFixed() && getTopView() == viewClass) {
            return;
        }

        mIsMoveToBackEnabled = false; // Essential part or new view will be pause immediately

        //if (!forceStart && doThrottle()) {
        //    Log.d(TAG, "Too many events. Skipping startView...");
        //    return;
        //}

        Class<?> activityClass = mViewMapping.get(viewClass);

        if (activityClass != null) {
            startActivity(activityClass);
        } else {
            Log.e(TAG, "Activity not registered for view " + viewClass.getSimpleName());
        }
    }

    private long mSkipNextParentStartMs;

    /**
     * Phone UI: the next startParentView only forgets the closing screen and opens nothing. Used when the app closes a
     * detached player (picture in picture, own task): the screen underneath (e.g. search) must stay as it is.
     */
    public void skipNextParentStart() {
        mSkipNextParentStartMs = System.currentTimeMillis();
    }

    public void startParentView(Activity activity) {
        if (mSkipNextParentStartMs != 0 && System.currentTimeMillis() - mSkipNextParentStartMs < 2_000) {
            mSkipNextParentStartMs = 0;
            removeTop(activity);
            return;
        }

        if (activity.getIntent() != null) {
            removeTopActivity();

            Class<?> parentActivity = getTopActivity();

            if (parentActivity == null && !isPlayerOnlyModeEnabled()) {
                parentActivity = getDefaultParent(activity);
            }

            if (parentActivity == null || isPlayerOnlyModeEnabled()) {
                Log.d(TAG, "Parent activity name doesn't stored in registry. Exiting to Home...");

                mIsMoveToBackEnabled = true;

                if (isPlayerOnlyModeEnabled()) {
                    safeMoveTaskToBack(activity);
                }
            } else {
                try {
                    Log.d(TAG, "Launching parent activity: " + parentActivity.getSimpleName());
                    // Phone UI: the same launch flags as any start, so the screen underneath comes back as it was
                    // (Home's singleTop + clear top) instead of a new copy on top (a fresh Home on its first tab)
                    Intent intent = new Intent(activity, parentActivity).addFlags(launchFlags(parentActivity));

                    safeStartActivity(activity, intent);
                } catch (ActivityNotFoundException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Parent activity not found.");
                }
            }
        }
    }

    public boolean hasParentView(Activity activity) {
        return mActivityStack.size() > 1 || getDefaultParent(activity) != null;
    }

    public void startDefaultView() {
        mIsMoveToBackEnabled = false;
        mIsPlayerOnlyModeEnabled = false;

        Class<?> lastActivity;

        // Check that PIP window isn't closed by the user
        if (mDefaultTop != null && PlaybackPresenter.instance(mContext).isRunningInBackground()) {
            lastActivity = mDefaultTop;
        } else if (!mActivityStack.isEmpty()) {
            lastActivity = mActivityStack.peek();
        } else {
            lastActivity = mRootActivity;
        }

        Log.d(TAG, "Launching default activity: " + lastActivity.getSimpleName());

        startActivity(lastActivity);
    }

    private Class<?> mNextLaunchClass;
    private Bundle mNextLaunchOptions;
    private long mNextLaunchTimeMs;

    /**
     * Activity options (e.g. a scale up animation from a thumbnail) for the next launch of the given activity.
     * Used by the phone UI to open the player from the tapped video. Expires after a few seconds.
     */
    public void setNextLaunchOptions(Class<?> activityClass, Bundle options) {
        mNextLaunchClass = activityClass;
        mNextLaunchOptions = options;
        mNextLaunchTimeMs = System.currentTimeMillis();
    }

    private Bundle takeLaunchOptions(Class<?> activityClass) {
        Bundle options = null;
        if (mNextLaunchOptions != null && activityClass == mNextLaunchClass
                && System.currentTimeMillis() - mNextLaunchTimeMs < 3_000) {
            options = mNextLaunchOptions;
        }
        mNextLaunchOptions = null;
        mNextLaunchClass = null;
        return options;
    }

    /**
     * Phone UI: these screens open inside the task of the screen in front (like any app's back stack) instead of
     * a task looked up by affinity, which could be the picture-in-picture player's own task.
     */
    public void setOpenInCallerTask(Class<?>... activityClasses) {
        java.util.Collections.addAll(mCallerTaskActivities, activityClasses);
    }

    /** Phone UI: the app screen in front, the one new screens open on top of */
    public void setCallerActivity(Activity activity) {
        mCallerActivity = activity != null ? new java.lang.ref.WeakReference<>(activity) : null;
    }

    public void clearCallerActivity(Activity activity) {
        if (mCallerActivity != null && mCallerActivity.get() == activity) {
            mCallerActivity = null;
        }
    }

    private Activity getCallerActivity(Class<?> activityClass) {
        Activity caller = mCallerActivity != null ? mCallerActivity.get() : null;
        if (caller == null || caller.isFinishing() || !mCallerTaskActivities.contains(activityClass)) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 24 && caller.isInPictureInPictureMode()) {
            return null;
        }
        return caller;
    }

    /** Phone UI: extra intent flags for a screen (e.g. bring an existing one to the front instead of a new one) */
    public interface LaunchFlags {
        int flagsFor(Class<?> activityClass);
    }

    private LaunchFlags mLaunchFlags;

    public void setLaunchFlags(LaunchFlags launchFlags) {
        mLaunchFlags = launchFlags;
    }

    private int launchFlags(Class<?> activityClass) {
        return mLaunchFlags != null ? mLaunchFlags.flagsFor(activityClass) : 0;
    }

    private void startActivity(Class<?> activityClass) {
        Log.d(TAG, "Launching activity: " + activityClass.getSimpleName());

        mPendingActivityMs = System.currentTimeMillis();
        mPendingActivityClass = activityClass;

        Activity caller = getCallerActivity(activityClass);
        if (caller != null) {
            Intent intent = new Intent(caller, activityClass).addFlags(launchFlags(activityClass));
            safeStartActivity(caller, intent, takeLaunchOptions(activityClass));
            return;
        }

        Intent intent = new Intent(mContext, activityClass).addFlags(launchFlags(activityClass));

        // Fix: Calling startActivity() from outside of an Activity  context requires the FLAG_ACTIVITY_NEW_TASK flag
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        safeStartActivity(mContext, intent, takeLaunchOptions(activityClass));
    }

    private boolean doThrottle() {
        long currentTimeMS = System.currentTimeMillis();
        boolean skipEvent = currentTimeMS - mPrevThrottleTimeMS < 1_000;

        mPrevThrottleTimeMS = currentTimeMS;

        return skipEvent;
    }

    public void addTop(Activity activity) {
        if (checkMoveViewsToBack(activity)) {
            // NOTE: Unknown purpose of commented code!

            // Maybe finish whole app?
            // Move task to back is active.
            // So finishing the activity only.
            //((MotherActivity) activity).finishReally();
            return;
        }

        Class<? extends Activity> activityClass = activity.getClass();

        // Open from phone's history fix. Not parent? Make the root then.
        if (mParentMapping.get(activityClass) == null) {
            mActivityStack.clear();
        }

        // reorder activity
        mActivityStack.remove(activityClass);
        mActivityStack.push(activityClass);
    }

    private void removeTopActivity() {
        if (!mActivityStack.isEmpty()) {
            mActivityStack.pop();
        }
    }

    private Class<? extends Activity> getTopActivity() {
        Class<? extends Activity> result = null;

        if (!mActivityStack.isEmpty()) {
            result = mActivityStack.peek();
        }

        return result;
    }

    public void setRoot(@NonNull Class<? extends Activity> rootActivity) {
        mRootActivity = rootActivity;
    }

    private Class<?> getDefaultParent(Activity activity) {
        // An exact match wins over a parent class (e.g. the phone player extends the TV player)
        if (mParentMapping.containsKey(activity.getClass())) {
            return mParentMapping.get(activity.getClass());
        }

        Class<?> parentActivity = null;

        for (Class<?> activityClass : mParentMapping.keySet()) {
            if (activityClass.isInstance(activity)) {
                parentActivity = mParentMapping.get(activityClass);
            }
        }

        return parentActivity;
    }

    public void blockTop(Activity activity) {
        mDefaultTop = activity == null ? null : activity.getClass();
    }

    public Class<? extends Activity> getBlockedTop() {
        return mDefaultTop;
    }

    public void removeTop(Activity activity) {
        mActivityStack.remove(activity.getClass());
    }

    private boolean checkMoveViewsToBack(Activity activity) {
        if (mIsMoveToBackEnabled) {
            safeMoveTaskToBack(activity);

            return true;
        }

        return false;
    }

    public void enablePlayerOnlyMode(boolean enable) {
        // Ensure that we're not opening tube link from description dialog
        if (enable && AppDialogPresenter.instance(mContext).isDialogShown()) {
            return;
        }

        mIsPlayerOnlyModeEnabled = enable;
    }

    public boolean isPlayerOnlyModeEnabled() {
        //return mIsPlayerOnlyModeEnabled && PlaybackPresenter.instance(mContext).getBackgroundMode() != PlayerEngine.BACKGROUND_MODE_PIP;
        return mIsPlayerOnlyModeEnabled && !PlaybackPresenter.instance(mContext).isInPipMode();
    }

    public void clearCaches() {
        YouTubeServiceManager.instance().invalidateCache();
        // Note, also deletes cached flags (internal cache)
        // Note, deletes cached apks (external cache)
        FileHelpers.deleteCache(mContext);
        LocaleUpdater.clearCache();
    }

    /**
     * Finishes the app without killing it (by moves tasks to back).<br/>
     * The app continue to run in the background.
     * @param activity this activity
     */
    public void properlyFinishTheApp(Context activity) {
        if (activity instanceof MotherActivity) {
            Log.d(TAG, "Trying finish the app...");
            mIsMoveToBackEnabled = true; // close all activities below current one
            mIsFinished = true;

            mActivityStack.clear();

            ((MotherActivity) activity).finishReally();

            PlaybackPresenter.instance(activity).forceFinish();

            // NOTE: The device may hung (SecurityException: requires android.permission.BIND_ACCESSIBILITY_SERVICE)
            //exitToHomeScreen(); // fix open another app from the history stack

            // Fix: can't start finished app activity from history.
            // Do reset state because the app should continue to run in the background.
            // NOTE: Don't rely on MotherActivity.onDestroy() because activity can be killed silently.
            RxHelper.runAsync(() -> {
                clearCaches();
                SplashPresenter.unhold();
                BrowsePresenter.unhold();
                AppUpdatePresenter.unhold();
                MotherActivity.invalidate();
                runOnFinish();
                mIsMoveToBackEnabled = false;
            }, 1_000);
        }
    }

    /**
     * SecurityException: requires android.permission.BIND_ACCESSIBILITY_SERVICE
     */
    private void exitToHomeScreen() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStartActivity(mContext, intent);
    }

    public Class<?> getTopView() {
        Class<? extends Activity> topActivity = getTopActivity();

        if (topActivity == null) {
            return null;
        }

        for (Map.Entry<Class<?>, Class<? extends Activity>> entry: mViewMapping.entrySet()) {
            if (entry.getValue() == topActivity) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * Fix: java.lang.NullPointerException Attempt to read from field<br/>
     * 'com.android.server.am.TaskRecord com.android.server.am.ActivityRecord.task'<br/>
     * on a null object reference<br/>
     * <br/>
     * Fix: java.lang.IllegalArgumentException<br/>
     * View=android.widget.TextView not attached to window manager
     */
    private void safeMoveTaskToBack(Activity activity) {
        try {
            activity.moveTaskToBack(true);
        } catch (NullPointerException | IllegalArgumentException | IllegalStateException e) {
            // Pinned stack isn't top stack (IllegalStateException)
            Log.e(TAG, "Error when moving task to back: %s", e.getMessage());
        }
    }

    /**
     * Small delay to fix PIP transition bug (UI become unresponsive)
     */
    private void safeStartActivity(Context context, Intent intent) {
        safeStartActivity(context, intent, null);
    }

    private void safeStartActivity(Context context, Intent intent, Bundle options) {
        mIsFinished = false;

        if (PlaybackPresenter.instance(mContext).isEngineBlocked()) { // Android 14 no-pip fix???
            Utils.postDelayed(() -> safeStartActivityInt(context, intent, options), 0); // 50 ms old val
        } else {
            safeStartActivityInt(context, intent, options);
        }
    }

    /**
     * Fix: java.lang.IllegalArgumentException<br/>
     * View=android.widget.TextView not attached to window manager
     */
    private void safeStartActivityInt(Context context, Intent intent, Bundle options) {
        try {
            context.startActivity(intent, options);
        } catch (IllegalArgumentException | ActivityNotFoundException | IndexOutOfBoundsException | NullPointerException | SecurityException e) {
            // NPE: Attempt to write to field 'boolean com.android.server.am.ActivityStack.mConfigWillChange' on a null object reference
            Log.e(TAG, "Error when starting activity: %s", e.getMessage());
            MessageHelpers.showLongMessage(context, e.getLocalizedMessage());
        }
    }

    public boolean isFinished() {
        return mIsFinished;
    }

    //public void enableMoveToBack(boolean enable) {
    //    mIsMoveToBackEnabled = enable;
    //}

    public boolean isNewViewPending() {
        return System.currentTimeMillis() - mPendingActivityMs < 1_000;
    }

    public boolean isViewPending(Class<?> viewClass) {
        return isNewViewPending() && mViewMapping.get(viewClass) == mPendingActivityClass;
    }

    public void refreshCurrentView() {
        Class<?> topView = getTopView();
        if (topView == BrowseView.class) {
            BrowsePresenter.instance(mContext).refresh();
        } else if (topView == ChannelUploadsView.class) {
            ChannelUploadsPresenter.instance(mContext).refresh();
        }
    }

    public boolean isVisible(Object view) {
        if (view instanceof Fragment) {
            return ((Fragment) view).isVisible();
        }

        if (view instanceof androidx.fragment.app.Fragment) {
            // Proper way to check the visibility
            return ((androidx.fragment.app.Fragment) view).getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        }

        return false;
    }

    public boolean isTopViewVisible() {
        BasePresenter<?> presenter = getTopPresenter();

        return presenter != null && isVisible(presenter.getView());
    }

    public static MotherActivity getMotherActivity(Object view) {
        MotherActivity motherActivity = null;

        if (view instanceof Fragment && ((Fragment) view).getActivity() instanceof MotherActivity) {
            motherActivity = ((MotherActivity) ((Fragment) view).getActivity());
        }

        if (view instanceof androidx.fragment.app.Fragment && ((androidx.fragment.app.Fragment) view).getActivity() instanceof MotherActivity) {
            motherActivity = ((MotherActivity) ((androidx.fragment.app.Fragment) view).getActivity());
        }

        return motherActivity;
    }

    public boolean isPlayerInForeground() {
        return Utils.isAppInForegroundFixed() && getTopView() == PlaybackView.class;
    }

    public void moveAppToForeground() {
        if (!Utils.isAppInForegroundFixed()) {
            startView(SplashView.class);
        }
    }

    public void movePlayerToForeground() {
        Utils.turnScreenOn(mContext);

        if (!isPlayerInForeground()) {
            startView(PlaybackView.class);
        }
    }

    public BasePresenter<?> getTopPresenter() {
        Class<?> topView = getTopView();

        if (topView == BrowseView.class) {
            return BrowsePresenter.instance(mContext);
        } else if (topView == SearchView.class) {
            return SearchPresenter.instance(mContext);
        } else if (topView == ChannelView.class) {
            return ChannelPresenter.instance(mContext);
        } else if (topView == ChannelUploadsView.class) {
            return ChannelUploadsPresenter.instance(mContext);
        } else if (topView == PlaybackView.class) {
            return PlaybackPresenter.instance(mContext);
        }

        return null;
    }

    public void cancelOnFinish() {
        if (mOnFinish != null) {
            mOnFinish.clear();
        }
    }

    public void addOnFinish(Runnable onFinish) {
        if (mOnFinish == null) {
            mOnFinish = new WeakHashSet<>();
        }

        mOnFinish.add(onFinish);
    }

    private void runOnFinish() {
        if (mOnFinish == null) {
            return;
        }

        mOnFinish.forEach(Runnable::run);
    }
}
