package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiverImpl;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A live stream's chat, laid out as in the YouTube app:
 * <ul>
 *     <li>portrait: a sheet under the video (rounded top, a handle), with Live chat, Top messages and the number
 *     watching, the X; the messages (picture, the name in grey, the message); the account's picture and a round
 *     Chat... box, or why one can't chat (e.g. Subscribers-only mode)</li>
 *     <li>full screen: the same as a column on the right, the video moved over beside it</li>
 *     <li>an upright stream: on the video itself, the channel along the top, the messages and the Chat... box over
 *     the bottom</li>
 * </ul>
 * Sizes are in the phone's own scale (the player screen's resources are scaled for the TV in full screen).
 */
final class PhoneLiveChat {
    interface Host {
        View videoBox();

        boolean chatAllowed();

        boolean landscape();

        /** An upright stream filling the screen: the chat over the bottom of the video */
        boolean overlay();

        Video video();

        /** The chat was closed with its X */
        void onChatClosed();

        /** The video box needs room on its right (landscape), or none */
        void setVideoRightMargin(int px);

        /** The channel bar's back arrow (upright streams) */
        void onBack();

        void onChannel();

        void onSubscribe();

        boolean isSubscribed();
    }

    private static final int MAX_MESSAGES = 250;
    private static final int GREY = 0xFFAAAAAA;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Pattern WATCHING = Pattern.compile("([\\d.,]+\\s*[KkMmLl]?)\\s*watching");

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final float mDensity;
    private final LinearLayout mPanel;
    private final View mHandle;
    private final View mHeader;
    private final TextView mViewers;
    private final View mTopDivider;
    private final RecyclerView mList;
    private final Adapter mAdapter = new Adapter();
    private final View mBottomDivider;
    private final LinearLayout mInputRow;
    private final ImageView mMe;
    private final LinearLayout mPill;
    private final EditText mInput;
    private final ImageView mSend;
    private final TextView mNotice;
    // The channel bar over an upright stream
    private final LinearLayout mTopBar;
    private final ImageView mTopAvatar;
    private final TextView mTopName;
    private final TextView mTopViewers;
    private final TextView mTopSubscribe;

    private ChatReceiver mReceiver;
    private String mChatKey;
    private boolean mClosed;
    private int mMode = -1; // 0 sheet, 1 side, 2 on the video
    private String mTopState;

    PhoneLiveChat(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
        mDensity = PhoneSheetLayout.screen().density;

        mPanel = new LinearLayout(activity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setClickable(true); // nothing under it gets the touches
        mPanel.setVisibility(View.GONE);

        // The sheet's handle
        mHandle = new View(activity);
        GradientDrawable handle = new GradientDrawable();
        handle.setColor(0x66888888);
        handle.setCornerRadius(dp(2));
        mHandle.setBackground(handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(10);
        mPanel.addView(mHandle, handleParams);

        // Live chat / Top messages · 42K watching, and the X
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(8), dp(8), dp(10));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(activity.getString(R.string.phone_live_chat), 21, color(R.color.phone_text));
        title.setTypeface(PhoneUi.regular(activity));
        titles.addView(title);
        LinearLayout sub = new LinearLayout(activity);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        sub.setPadding(0, dp(4), 0, 0);
        sub.addView(text(activity.getString(R.string.phone_live_chat_top), 14, GREY));
        mViewers = text("", 14, GREY);
        mViewers.setCompoundDrawablePadding(dp(4));
        mViewers.setCompoundDrawablesRelative(icon(R.drawable.ic_phone_person, 16, GREY), null, null, null);
        LinearLayout.LayoutParams viewersParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        viewersParams.setMarginStart(dp(14));
        sub.addView(mViewers, viewersParams);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.ic_phone_close);
        close.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setBackgroundResource(R.drawable.phone_ripple);
        close.setContentDescription(activity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> {
            mClosed = true;
            hideKeyboard();
            layout();
            mHost.onChatClosed();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mHeader = header;
        mPanel.addView(header);
        mTopDivider = divider();
        mPanel.addView(mTopDivider);

        // The messages, newest at the bottom
        mList = new RecyclerView(activity);
        LinearLayoutManager manager = new LinearLayoutManager(activity);
        manager.setStackFromEnd(true);
        mList.setLayoutManager(manager);
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        mList.setPadding(0, dp(4), 0, dp(4));
        mList.setClipToPadding(false);
        mPanel.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mBottomDivider = divider();
        mPanel.addView(mBottomDivider);

        // The account's picture and a round Chat... box (send shows once there's something to send)
        mInputRow = new LinearLayout(activity);
        mInputRow.setGravity(Gravity.CENTER_VERTICAL);
        mInputRow.setPadding(dp(16), dp(10), dp(16), dp(10));
        mMe = new ImageView(activity);
        mMe.setContentDescription(activity.getString(R.string.phone_live_chat_as));
        mMe.setOnClickListener(v -> {
            if (com.liskovsoft.youtubeapi.chat.LiveChatWriter.hasWebLogin()) {
                loadChannels(this::pickChannel); // chat as another of the account's channels
            }
        });
        LinearLayout.LayoutParams meParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        meParams.setMarginEnd(dp(12));
        mInputRow.addView(mMe, meParams);
        mPill = new LinearLayout(activity);
        mPill.setGravity(Gravity.CENTER_VERTICAL);
        mPill.setPadding(dp(18), 0, dp(6), 0);
        mInput = new EditText(activity);
        mInput.setBackground(null);
        mInput.setPadding(0, 0, 0, 0);
        mInput.setHint(R.string.phone_live_chat_hint);
        mInput.setSingleLine(true);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(16));
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        mPill.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSend = new ImageView(activity);
        mSend.setImageResource(R.drawable.ic_phone_send);
        mSend.setPadding(dp(9), dp(9), dp(9), dp(9));
        mSend.setBackgroundResource(R.drawable.phone_ripple);
        mSend.setVisibility(View.GONE);
        mSend.setOnClickListener(v -> send());
        mPill.addView(mSend, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView emoji = new ImageView(activity);
        emoji.setImageResource(R.drawable.ic_phone_emoji);
        emoji.setImageTintList(ColorStateList.valueOf(GREY));
        emoji.setPadding(dp(8), dp(8), dp(8), dp(8));
        emoji.setBackgroundResource(R.drawable.phone_ripple);
        emoji.setContentDescription("Emoji");
        emoji.setOnClickListener(v -> {
            if (com.liskovsoft.youtubeapi.chat.LiveChatWriter.hasWebLogin()) {
                pickEmoji();
            } else {
                PhoneChatSignInActivity.open(mActivity);
            }
        });
        mPill.addView(emoji, mPill.getChildCount() - 1, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mSend.setVisibility(s.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        mInputRow.addView(mPill, new LinearLayout.LayoutParams(0, dp(44), 1f));
        mPanel.addView(mInputRow);
        // Or why one can't chat: (i) Subscribers-only mode
        mNotice = text("", 16, GREY);
        mNotice.setPadding(dp(18), dp(16), dp(18), dp(18));
        mNotice.setCompoundDrawablePadding(dp(22));
        mNotice.setGravity(Gravity.CENTER_VERTICAL);
        mNotice.setVisibility(View.GONE);
        mPanel.addView(mNotice);

        // Above the navigation bar, and above the keyboard while writing
        mPanel.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                bottom = insets.getInsets(android.view.WindowInsets.Type.ime() | android.view.WindowInsets.Type.navigationBars()).bottom;
            }
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });

        // The channel bar over an upright stream: back, the channel, the number watching, Subscribe
        mTopBar = new LinearLayout(activity);
        mTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mTopBar.setPadding(dp(4), dp(6), dp(14), dp(6));
        mTopBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000}));
        mTopBar.setVisibility(View.GONE);
        ImageView back = new ImageView(activity);
        back.setImageResource(R.drawable.ic_phone_back);
        back.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setBackgroundResource(R.drawable.phone_ripple);
        back.setOnClickListener(v -> mHost.onBack());
        mTopBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mTopAvatar = new ImageView(activity);
        mTopAvatar.setOnClickListener(v -> mHost.onChannel());
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        avatarParams.setMarginStart(dp(4));
        mTopBar.addView(mTopAvatar, avatarParams);
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setOnClickListener(v -> mHost.onChannel());
        mTopName = text("", 16, 0xFFFFFFFF);
        mTopName.setTypeface(PhoneUi.medium(activity));
        mTopName.setSingleLine(true);
        mTopName.setEllipsize(TextUtils.TruncateAt.END);
        mTopName.setShadowLayer(dp(3), 0, 0, 0x99000000);
        names.addView(mTopName);
        mTopViewers = text("", 13, 0xFFFFFFFF);
        mTopViewers.setCompoundDrawablePadding(dp(6));
        mTopViewers.setShadowLayer(dp(3), 0, 0, 0x99000000);
        mTopViewers.setPadding(0, dp(3), 0, 0);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFFF0033);
        dot.setBounds(0, 0, dp(8), dp(8));
        mTopViewers.setCompoundDrawablesRelative(dot, null, null, null);
        names.addView(mTopViewers);
        LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesParams.setMarginStart(dp(12));
        mTopBar.addView(names, namesParams);
        mTopSubscribe = text(activity.getString(R.string.phone_subscribe), 15, 0xFF0F0F0F);
        mTopSubscribe.setTypeface(PhoneUi.medium(activity));
        mTopSubscribe.setGravity(Gravity.CENTER);
        mTopSubscribe.setPadding(dp(16), 0, dp(16), 0);
        mTopSubscribe.setOnClickListener(v -> mHost.onSubscribe());
        mTopBar.addView(mTopSubscribe, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

        FrameLayout content = activity.findViewById(android.R.id.content);
        content.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        content.addView(mTopBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.videoBox().addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (t != ot || b != ob || l != ol || r != or) {
                mPanel.post(this::layout);
            }
        });
        setMode(0);
    }

    /** The chat the player opened for this stream (null: closed, or not a stream with chat) */
    void setReceiver(ChatReceiver receiver) {
        if (mReceiver != null) {
            mReceiver.setCallback(null);
        }
        mReceiver = receiver;
        String key = receiver instanceof ChatReceiverImpl ? ((ChatReceiverImpl) receiver).getChatKey() : null;
        if (key == null || !key.equals(mChatKey)) {
            mAdapter.clear(); // another stream
        }
        mChatKey = key;
        if (receiver != null) {
            mClosed = false;
            receiver.setCallback(item -> mMain.post(() -> {
                if (mReceiver == receiver) {
                    mAdapter.add(item);
                }
            }));
            checkWriting();
        }
        layout();
    }

    boolean isClosed() {
        return mClosed;
    }

    boolean hasChat() {
        return mReceiver != null;
    }

    /** The screen point is on the chat (its panel, or the channel bar over an upright stream) */
    boolean contains(float rawX, float rawY) {
        for (View view : new View[] {mPanel, mTopBar}) {
            if (view.getVisibility() != View.VISIBLE) {
                continue;
            }
            Rect rect = new Rect();
            if (view.getGlobalVisibleRect(rect) && rect.contains((int) rawX, (int) rawY)) {
                return true;
            }
        }
        return false;
    }

    boolean isShowing() {
        return mPanel.getVisibility() == View.VISIBLE;
    }

    /** Opens the chat again after its X (the chat button) */
    void reopen() {
        mClosed = false;
        layout();
    }

    void release() {
        if (mReceiver != null) {
            mReceiver.setCallback(null);
        }
        mReceiver = null;
    }

    // region Where it goes

    /** Places the chat for the screen as it is now: under the video, beside it, over it, or hidden */
    void layout() {
        boolean show = mReceiver != null && !mClosed && mHost.chatAllowed();
        boolean landscape = mHost.landscape();
        boolean overlay = !landscape && mHost.overlay();
        FrameLayout content = (FrameLayout) mPanel.getParent();
        int screenWidth = content != null && content.getWidth() > 0 ? content.getWidth() : PhoneSheetLayout.screen().widthPixels;
        // Beside the video in full screen: 40% of the screen, as in the YouTube app
        int sideWidth = show && landscape ? Math.max(dp(300), Math.round(screenWidth * 0.4f)) : 0;
        mHost.setVideoRightMargin(sideWidth);
        layoutTopBar(overlay && mHost.chatAllowed());
        if (!show || content == null) {
            if (mPanel.getVisibility() != View.GONE) {
                hideKeyboard();
                mPanel.setVisibility(View.GONE);
            }
            return;
        }
        setMode(overlay ? 2 : landscape ? 1 : 0);
        Rect video = new Rect();
        Rect root = new Rect();
        mHost.videoBox().getGlobalVisibleRect(video);
        content.getGlobalVisibleRect(root);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        int width;
        int height;
        int gravity;
        int top;
        if (overlay) {
            width = ViewGroup.LayoutParams.MATCH_PARENT;
            gravity = Gravity.TOP;
            height = Math.round(video.height() * 0.40f);
            top = Math.max(0, video.bottom - root.top - height);
        } else if (landscape) {
            width = sideWidth;
            height = ViewGroup.LayoutParams.MATCH_PARENT;
            gravity = Gravity.END | Gravity.TOP;
            top = 0;
        } else {
            width = ViewGroup.LayoutParams.MATCH_PARENT;
            gravity = Gravity.TOP;
            top = Math.max(0, video.bottom - root.top);
            height = Math.max(dp(200), root.height() - top);
        }
        if (params.width != width || params.height != height || params.gravity != gravity || params.topMargin != top) {
            params.width = width;
            params.height = height;
            params.gravity = gravity;
            params.topMargin = top;
            mPanel.setLayoutParams(params);
        }
        if (mPanel.getVisibility() != View.VISIBLE) {
            mPanel.setVisibility(View.VISIBLE);
        }
        updateViewers();
    }

    /** The look for where it is: a sheet, a side column, or on the video */
    private void setMode(int mode) {
        if (mode == mMode) {
            return;
        }
        mMode = mode;
        boolean onVideo = mode == 2;
        if (onVideo) {
            mPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x66000000}));
        } else if (mode == 0) {
            GradientDrawable sheet = new GradientDrawable();
            sheet.setColor(color(R.color.phone_bg));
            float r = dp(16);
            sheet.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
            mPanel.setBackground(sheet);
        } else {
            mPanel.setBackgroundColor(color(R.color.phone_bg));
        }
        mHandle.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        mHeader.setVisibility(onVideo ? View.GONE : View.VISIBLE);
        mTopDivider.setVisibility(onVideo ? View.GONE : View.VISIBLE);
        mBottomDivider.setVisibility(onVideo ? View.GONE : View.VISIBLE);
        mMe.setVisibility(onVideo ? View.GONE : View.VISIBLE);
        // The older messages fade out at the top over the video
        mList.setVerticalFadingEdgeEnabled(onVideo);
        mList.setFadingEdgeLength(onVideo ? dp(56) : 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(onVideo ? 0x59000000 : (isDark() ? 0xFF272727 : 0xFFF2F2F2));
        if (onVideo) {
            pill.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        }
        pill.setCornerRadius(dp(22));
        mPill.setBackground(pill);
        int text = onVideo ? 0xFFFFFFFF : color(R.color.phone_text);
        mInput.setTextColor(text);
        mInput.setHintTextColor(onVideo ? 0xCCFFFFFF : GREY);
        mSend.setImageTintList(ColorStateList.valueOf(text));
        mAdapter.notifyDataSetChanged();
    }

    private void layoutTopBar(boolean show) {
        if (!show) {
            if (mTopBar.getVisibility() != View.GONE) {
                mTopBar.setVisibility(View.GONE);
            }
            return;
        }
        Video video = mHost.video();
        String viewers = viewers();
        boolean subscribed = mHost.isSubscribed();
        String state = (video != null ? video.videoId + video.getAuthor() : "") + "|" + viewers + "|" + subscribed;
        if (!state.equals(mTopState)) {
            // (called often: only when something changed, not a new picture load every time)
            mTopState = state;
            if (video != null) {
                mTopName.setText(video.getAuthor());
                PhoneUi.bindAuthorAvatar(mTopAvatar, video);
            }
            mTopViewers.setText(viewers != null ? viewers : "");
            mTopViewers.setVisibility(viewers != null ? View.VISIBLE : View.GONE);
            mTopSubscribe.setText(subscribed ? R.string.phone_subscribed : R.string.phone_subscribe);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(subscribed ? 0x40FFFFFF : 0xFFFFFFFF);
            pill.setCornerRadius(dp(18));
            mTopSubscribe.setBackground(pill);
            mTopSubscribe.setTextColor(subscribed ? 0xFFFFFFFF : 0xFF0F0F0F);
        }
        Rect videoRect = new Rect();
        Rect root = new Rect();
        mHost.videoBox().getGlobalVisibleRect(videoRect);
        ((View) mTopBar.getParent()).getGlobalVisibleRect(root);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mTopBar.getLayoutParams();
        int top = Math.max(0, videoRect.top - root.top);
        if (params.topMargin != top) {
            params.topMargin = top;
            mTopBar.setLayoutParams(params);
        }
        if (mTopBar.getVisibility() != View.VISIBLE) {
            mTopBar.setVisibility(View.VISIBLE);
        }
    }

    /** "42K": how many are watching (from the video's own line, "… · 42K watching") */
    private String viewers() {
        Video video = mHost.video();
        CharSequence line = video != null ? video.getSecondTitle() : null;
        if (line == null) {
            return null;
        }
        Matcher matcher = WATCHING.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private void updateViewers() {
        String viewers = viewers();
        if (TextUtils.equals(viewers, mViewers.getText())) {
            return;
        }
        mViewers.setText(viewers != null ? viewers : "");
        mViewers.setVisibility(viewers != null ? View.VISIBLE : View.GONE);
    }

    // endregion

    // region Writing

    /** Back on the player (e.g. from signing in to chat): the box as it should be now */
    void refreshWriting() {
        if (mReceiver != null) {
            checkWriting();
        }
    }

    private void checkWriting() {
        // YouTube takes chat messages only from a YouTube website login (the app's chat sign-in), not the TV one
        boolean webLogin = com.liskovsoft.youtubeapi.chat.LiveChatWriter.hasWebLogin();
        setSignInBox(!webLogin);
        if (!webLogin) {
            showNotice(null);
            return;
        }
        ensureChannel(); // (and its picture by the box)
        showNotice(null);
        String key = mChatKey;
        if (key == null) {
            return;
        }
        WORKER.execute(() -> {
            String reason;
            try {
                reason = com.liskovsoft.youtubeapi.chat.LiveChatWriter.whyNot(key);
            } catch (Throwable e) {
                reason = null; // tried when sending
            }
            String why = reason;
            mMain.post(() -> {
                if (key.equals(mChatKey)) {
                    showNotice(why);
                }
            });
        });
    }

    // region Which channel writes

    /**
     * A Google account can have several channels: the chat writes as the one chosen. The first time it's the one
     * this app is signed in with (same name), or the only one; otherwise the user picks. Tapping one's own picture
     * by the box picks again.
     */
    private void ensureChannel() {
        String[] page = PhoneChatSignInActivity.readPage(mActivity);
        if (page != null) {
            showMe(page[1], page[2]);
            return;
        }
        String appName = null;
        try {
            com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                    com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount();
            appName = account != null ? account.getName() : null;
        } catch (Throwable e) {
            // no name to match
        }
        String match = appName;
        loadChannels(channels -> {
            if (channels.isEmpty()) {
                // No list: the account's main channel
                chooseChannel(new com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel(
                        mActivity.getString(R.string.phone_live_chat_main_channel), null, null, null, true));
                return;
            }
            for (com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel channel : channels) {
                if (match != null && match.trim().equalsIgnoreCase(channel.name.trim())) {
                    chooseChannel(channel);
                    return;
                }
            }
            // Not found by name: the one YouTube has as current, else the account's main channel (never none)
            com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel pick = null;
            for (com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel channel : channels) {
                if (channel.selected) {
                    pick = channel;
                }
            }
            for (com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel channel : channels) {
                if (pick == null && channel.pageId == null) {
                    pick = channel;
                }
            }
            chooseChannel(pick != null ? pick : channels.get(0));
        });
    }

    private interface Channels {
        void onChannels(List<com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel> channels);
    }

    /** Why the channel list couldn't be had (shown in the picker), or null */
    private String mChannelsError;

    private void loadChannels(Channels onChannels) {
        WORKER.execute(() -> {
            List<com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel> channels;
            String error = null;
            try {
                channels = com.liskovsoft.youtubeapi.chat.LiveChatWriter.channels();
            } catch (Throwable e) {
                android.util.Log.d("PhoneLiveChat", "channels: " + e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                channels = new ArrayList<>();
            }
            mChannelsError = error;
            List<com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel> result = channels;
            mMain.post(() -> {
                if (!mActivity.isFinishing() && !mActivity.isDestroyed()) {
                    onChannels.onChannels(result);
                }
            });
        });
    }

    /** Chat as: the login's channels, the current one ticked */
    private void pickChannel(List<com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel> found) {
        // Always at least the account's main channel (the list may not have come)
        List<com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel> channels = new ArrayList<>(found);
        boolean hasMain = false;
        for (com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel channel : channels) {
            hasMain |= channel.pageId == null;
        }
        if (!hasMain) {
            channels.add(0, new com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel(
                    mActivity.getString(R.string.phone_live_chat_main_channel), null, null, null, false));
        }
        String[] page = PhoneChatSignInActivity.readPage(mActivity);
        LinearLayout content = sheetContent(mActivity.getString(R.string.phone_live_chat_as), mChannelsError);
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        for (com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel channel : channels) {
            String id = channel.pageId != null ? channel.pageId : "";
            boolean current = page != null ? page[0].equals(id) : channel.selected;
            LinearLayout row = sheetRow();
            ImageView photo = new ImageView(mActivity);
            if (channel.photo != null) {
                PhoneUi.loadAvatar(photo, channel.photo);
            } else {
                photo.setImageResource(R.drawable.ic_phone_person);
                photo.setImageTintList(ColorStateList.valueOf(GREY));
            }
            row.addView(photo, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout names = new LinearLayout(mActivity);
            names.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(channel.name, 16, color(R.color.phone_text));
            name.setTypeface(PhoneUi.medium(mActivity));
            names.addView(name);
            if (channel.handle != null && !channel.handle.isEmpty()) {
                TextView handle = text(channel.handle, 14, GREY);
                handle.setPadding(0, dp(3), 0, 0);
                names.addView(handle);
            }
            LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            namesParams.setMarginStart(dp(16));
            row.addView(names, namesParams);
            if (current) {
                ImageView check = new ImageView(mActivity);
                check.setImageResource(R.drawable.ic_phone_check);
                check.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
                row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            row.setOnClickListener(v -> {
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                chooseChannel(channel);
            });
            content.addView(row);
        }
        dialog[0] = showSheet(content);
    }

    private void chooseChannel(com.liskovsoft.youtubeapi.chat.LiveChatWriter.WebChannel channel) {
        PhoneChatSignInActivity.choosePage(mActivity, channel.pageId, channel.photo, channel.name);
        showMe(channel.photo, channel.name);
        android.util.Log.d("PhoneLiveChat", "chatting as " + channel.name);
        String key = mChatKey;
        if (key != null) {
            checkWriting(); // (what this channel may do here)
        }
    }

    /** The channel that writes: its picture by the box, and its name in the box (Chat as …) */
    private void showMe(String photo, String name) {
        if (photo != null && !photo.isEmpty()) {
            PhoneUi.loadAvatar(mMe, photo);
        }
        if (name != null && !name.isEmpty()) {
            mInput.setHint(mActivity.getString(R.string.phone_live_chat_as_hint, name));
        }
    }

    // endregion

    /** Not signed in to chat: the box reads Sign in to chat, and a tap opens the sign-in */
    private void setSignInBox(boolean signIn) {
        mInput.setHint(signIn ? R.string.phone_chat_sign_in : R.string.phone_live_chat_hint);
        mInput.setFocusable(!signIn);
        mInput.setFocusableInTouchMode(!signIn);
        mInput.setCursorVisible(!signIn);
        View.OnClickListener open = signIn ? v -> PhoneChatSignInActivity.open(mActivity) : null;
        mInput.setOnClickListener(open);
        mPill.setOnClickListener(open);
        mPill.setClickable(signIn);
    }

    /** Why one can't write here (null: the box to write in) */
    private void showNotice(String why) {
        mInputRow.setVisibility(why == null ? View.VISIBLE : View.GONE);
        mNotice.setVisibility(why == null ? View.GONE : View.VISIBLE);
        if (why != null) {
            mNotice.setText(why);
            mNotice.setCompoundDrawablesRelative(icon(R.drawable.ic_phone_info, 26, GREY), null, null, null);
        }
    }

    private void send() {
        String text = mInput.getText().toString().trim();
        String key = mChatKey;
        Video video = mHost.video();
        if (TextUtils.isEmpty(text) || key == null || video == null || video.videoId == null) {
            return;
        }
        String videoId = video.videoId;
        String channelId = video.channelId;
        mSend.setEnabled(false);
        mInput.setEnabled(false);
        WORKER.execute(() -> {
            String error = null;
            try {
                com.liskovsoft.youtubeapi.chat.LiveChatWriter.send(key, videoId, channelId, text);
            } catch (Throwable e) {
                if (e instanceof com.liskovsoft.youtubeapi.chat.LiveChatWriter.SignInNeededException) {
                    // The website login expired (or was signed out elsewhere): sign in again
                    mMain.post(() -> {
                        PhoneChatSignInActivity.signOut(mActivity);
                        setSignInBox(true);
                        PhoneChatSignInActivity.open(mActivity);
                    });
                }
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                android.util.Log.d("PhoneLiveChat", "send: " + e);
            }
            String failed = error;
            mMain.post(() -> {
                mSend.setEnabled(true);
                mInput.setEnabled(true);
                if (failed == null) {
                    mInput.setText("");
                    hideKeyboard();
                    showSent();
                } else {
                    // In full (each way tried and YouTube's answer), so it can be read and reported
                    new android.app.AlertDialog.Builder(mActivity)
                            .setMessage(mActivity.getString(R.string.phone_live_chat_failed, failed))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        });
    }

    /** One's message, in the list as soon as YouTube took it (the chat's own feed brings it later, or not at all in Top messages) */
    private void showSent() {
        String[] sent = com.liskovsoft.youtubeapi.chat.LiveChatWriter.getLastSent();
        if (sent == null || sent.length < 4) {
            return;
        }
        mAdapter.add(new ChatItem() {
            @Override
            public String getId() {
                return sent[0];
            }

            @Override
            public String getMessage() {
                return sent[3];
            }

            @Override
            public String getAuthorName() {
                return sent[1];
            }

            @Override
            public String getAuthorPhoto() {
                return sent[2];
            }
        });
        mList.scrollToPosition(mAdapter.getItemCount() - 1);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        }
        mInput.clearFocus();
    }

    // endregion

    // region The messages

    /** One's own message held: the message at the top, then Remove (as in the YouTube app) */
    private void confirmDelete(ChatItem item) {
        LinearLayout content = sheetContent(null, null);
        LinearLayout message = new LinearLayout(mActivity);
        message.setPadding(dp(20), dp(8), dp(20), dp(14));
        ImageView photo = new ImageView(mActivity);
        PhoneUi.loadAvatar(photo, item.getAuthorPhoto());
        message.addView(photo, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView words = text("", 15, color(R.color.phone_text));
        words.setText(line(item, false, words));
        words.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.setMarginStart(dp(16));
        wordsParams.topMargin = dp(4);
        message.addView(words, wordsParams);
        content.addView(message);
        content.addView(divider());
        TextView section = text(mActivity.getString(R.string.phone_live_chat_message), 15, GREY);
        section.setPadding(dp(20), dp(16), dp(20), dp(6));
        content.addView(section);
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        LinearLayout remove = sheetRow();
        ImageView trash = new ImageView(mActivity);
        trash.setImageResource(R.drawable.ic_phone_delete);
        trash.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        remove.addView(trash, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView label = text(mActivity.getString(R.string.phone_live_chat_remove), 17, color(R.color.phone_text));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(24));
        remove.addView(label, labelParams);
        remove.setOnClickListener(v -> {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            WORKER.execute(() -> {
                String error = null;
                try {
                    com.liskovsoft.youtubeapi.chat.LiveChatWriter.delete(item.getId());
                } catch (Throwable e) {
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    android.util.Log.d("PhoneLiveChat", "delete: " + e);
                }
                String failed = error;
                mMain.post(() -> {
                    if (failed == null) {
                        mAdapter.remove(item.getId());
                    } else {
                        android.widget.Toast.makeText(mActivity, mActivity.getString(R.string.phone_live_chat_delete_failed, failed),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
        content.addView(remove);
        dialog[0] = showSheet(content);
    }

    // region Sheets (from the bottom, as the YouTube app's)

    private LinearLayout sheetContent(String title, String note) {
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        if (title != null) {
            TextView heading = text(title, 18, color(R.color.phone_text));
            heading.setTypeface(PhoneUi.medium(mActivity));
            heading.setPadding(dp(20), dp(6), dp(20), note != null ? dp(4) : dp(12));
            content.addView(heading);
        }
        if (note != null) {
            TextView why = text(note, 13, GREY);
            why.setMaxLines(3);
            why.setEllipsize(TextUtils.TruncateAt.END);
            why.setPadding(dp(20), 0, dp(20), dp(12));
            content.addView(why);
        }
        return content;
    }

    private LinearLayout sheetRow() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(20), dp(10));
        row.setMinimumHeight(dp(56));
        row.setBackgroundResource(R.drawable.phone_ripple);
        return row;
    }

    private android.app.Dialog showSheet(View content) {
        android.app.Dialog dialog = new android.app.Dialog(mActivity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        PhoneSheetLayout root = new PhoneSheetLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.phone_sheet_bg);
        root.setPadding(0, dp(8), 0, dp(16));
        root.setClickable(true);
        root.setOnDismiss(dialog::dismiss);
        View handle = new View(mActivity);
        handle.setBackgroundResource(R.drawable.phone_handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        root.addView(handle, handleParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(mActivity);
        scroll.addView(content);
        root.addView(scroll);
        FrameLayout frame = new FrameLayout(mActivity);
        frame.setOnClickListener(v -> dialog.dismiss());
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        dialog.setContentView(frame);
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.5f;
            window.setAttributes(params);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        return dialog;
    }

    // endregion

    // region Emoji

    /** The emoji picker: tap one to add it to the message */
    private void pickEmoji() {
        LinearLayout content = sheetContent(null, null);
        android.widget.GridLayout grid = new android.widget.GridLayout(mActivity);
        int columns = 8;
        grid.setColumnCount(columns);
        grid.setPadding(dp(10), 0, dp(10), dp(8));
        int size = (PhoneSheetLayout.screen().widthPixels - dp(20)) / columns;
        for (String emoji : EMOJI) {
            TextView cell = text(emoji, 26, color(R.color.phone_text));
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.phone_ripple);
            cell.setOnClickListener(v -> {
                int at = Math.max(0, mInput.getSelectionStart());
                mInput.getText().insert(at, emoji);
            });
            android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams();
            params.width = size;
            params.height = size;
            grid.addView(cell, params);
        }
        content.addView(grid);
        showSheet(content);
    }

    private static final String[] EMOJI = {
            "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤩", "🥳", "😇", "🙂", "😉", "😋", "😜", "🤪",
            "🤔", "🤨", "😐", "🙄", "😏", "😴", "😮", "😱", "😭", "😢", "😡", "🤯", "🥺", "😳", "🤗", "🤭",
            "👍", "👎", "👏", "🙌", "🙏", "💪", "👋", "🤝", "✌️", "🤞", "👌", "👀", "🫶", "❤️", "🧡", "💛",
            "💚", "💙", "💜", "🖤", "🤍", "💔", "💯", "🔥", "✨", "⭐", "🎉", "🎊", "🎁", "🏆", "⚽", "🎮",
            "🎵", "🎶", "📢", "💬", "✅", "❌", "⚡", "🌈", "☀️", "🌙", "🌸", "🍕", "🍿", "☕", "🚀", "🇮🇳"};

    /**
     * The message's line: the name in grey, then the message, YouTube's own emoji (the channel's, the green faces)
     * as their pictures, loaded into the line as they come
     */
    private CharSequence line(ChatItem item, boolean onVideo, TextView into) {
        String author = item.getAuthorName() != null ? item.getAuthorName() : "";
        SpannableStringBuilder line = new SpannableStringBuilder(author);
        line.setSpan(new ForegroundColorSpan(onVideo ? 0xD9FFFFFF : GREY), 0, author.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append(" ");
        List<String[]> parts = item.getMessageParts();
        if (parts == null) {
            line.append(item.getMessage() != null ? item.getMessage() : "");
            return line;
        }
        int size = Math.round(PhoneSheetLayout.sp(15) * 1.35f);
        for (String[] part : parts) {
            String url = part.length > 1 ? part[1] : null;
            if (url == null) {
                line.append(part[0] != null ? part[0] : "");
                continue;
            }
            int start = line.length();
            line.append("￼");
            RemoteEmoji emoji = new RemoteEmoji(size, into);
            line.setSpan(new android.text.style.ImageSpan(emoji, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM),
                    start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            emoji.load(mActivity, url);
        }
        return line;
    }

    /** An emoji's picture, drawn in the line once loaded */
    private static final class RemoteEmoji extends android.graphics.drawable.Drawable {
        private final int mSize;
        private final java.lang.ref.WeakReference<TextView> mView;
        private android.graphics.drawable.Drawable mImage;

        RemoteEmoji(int size, TextView view) {
            mSize = size;
            mView = new java.lang.ref.WeakReference<>(view);
            setBounds(0, 0, size, size);
        }

        void load(Activity activity, String url) {
            if (!PhoneUi.isAlive(activity)) {
                return;
            }
            com.bumptech.glide.Glide.with(activity).load(url).into(
                    new com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>(mSize, mSize) {
                        @Override
                        public void onResourceReady(@NonNull android.graphics.drawable.Drawable resource,
                                                    com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> transition) {
                            mImage = resource;
                            mImage.setBounds(0, 0, mSize, mSize);
                            TextView view = mView.get();
                            if (view != null) {
                                view.invalidate();
                            }
                        }

                        @Override
                        public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                            mImage = null;
                        }
                    });
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas canvas) {
            if (mImage != null) {
                mImage.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // endregion

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<ChatItem> mItems = new ArrayList<>();

        void add(ChatItem item) {
            if (item == null || (TextUtils.isEmpty(item.getMessage()) && item.getMessageParts() == null)) {
                return;
            }
            if (item.getId() != null) {
                for (int i = mItems.size() - 1; i >= 0 && i >= mItems.size() - 60; i--) {
                    if (item.getId().equals(mItems.get(i).getId())) {
                        return; // already shown (one's own, added when sent)
                    }
                }
            }
            boolean atBottom = !mList.canScrollVertically(1);
            mItems.add(item);
            notifyItemInserted(mItems.size() - 1);
            if (mItems.size() > MAX_MESSAGES) {
                mItems.remove(0);
                notifyItemRemoved(0);
            }
            if (atBottom) {
                mList.scrollToPosition(mItems.size() - 1); // following the chat (not while reading back)
            }
        }

        void clear() {
            mItems.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            ImageView avatar = new ImageView(parent.getContext());
            row.addView(avatar, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView text = new TextView(parent.getContext());
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(15));
            text.setTypeface(PhoneUi.regular(parent.getContext()));
            text.setLineSpacing(0, 1.05f);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(16));
            textParams.topMargin = dp(3);
            row.addView(text, textParams);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(row, avatar, text);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ChatItem item = mItems.get(position);
            boolean onVideo = mMode == 2;
            // The name in grey, a gap, then the message with its emoji (as in the YouTube app)
            holder.text.setText(line(item, onVideo, holder.text));
            holder.text.setTextColor(onVideo ? 0xFFFFFFFF : color(R.color.phone_text));
            holder.text.setShadowLayer(onVideo ? dp(3) : 0, 0, onVideo ? dp(1) : 0, 0xB3000000);
            PhoneUi.loadAvatar(holder.avatar, item.getAuthorPhoto());
            // One's own message (sent from here): hold it to delete it
            holder.itemView.setOnLongClickListener(com.liskovsoft.youtubeapi.chat.LiveChatWriter.canDelete(item.getId())
                    ? v -> {
                        confirmDelete(item);
                        return true;
                    } : null);
            holder.itemView.setLongClickable(com.liskovsoft.youtubeapi.chat.LiveChatWriter.canDelete(item.getId()));
        }

        void remove(String id) {
            for (int i = 0; i < mItems.size(); i++) {
                if (id.equals(mItems.get(i).getId())) {
                    mItems.remove(i);
                    notifyItemRemoved(i);
                    return;
                }
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView text;

        Holder(View view, ImageView avatar, TextView text) {
            super(view);
            this.avatar = avatar;
            this.text = text;
        }
    }

    // endregion

    private TextView text(String value, float sp, int textColor) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(sp));
        view.setTextColor(textColor);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Drawable icon(int res, int sizeDp, int tint) {
        Drawable drawable = mActivity.getDrawable(res);
        if (drawable == null) {
            return null;
        }
        drawable = drawable.mutate();
        drawable.setTint(tint);
        drawable.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return drawable;
    }

    private View divider() {
        View view = new View(mActivity);
        view.setBackgroundColor(isDark() ? 0x33FFFFFF : 0x1A000000);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return view;
    }

    private boolean isDark() {
        int bg = color(R.color.phone_bg);
        return (0.299 * ((bg >> 16) & 0xFF) + 0.587 * ((bg >> 8) & 0xFF) + 0.114 * (bg & 0xFF)) < 128;
    }

    private int color(int res) {
        return mActivity.getResources().getColor(res);
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }
}
