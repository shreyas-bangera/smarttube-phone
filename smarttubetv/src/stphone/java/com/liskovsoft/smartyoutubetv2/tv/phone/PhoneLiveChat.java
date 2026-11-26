package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
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
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiverImpl;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A live stream's chat, laid out as in the YouTube app: in portrait a panel under the video (in place of the
 * details), in full screen a column beside the video (the video moves over to make room), never over the video.
 * A header (Live chat, Top messages, close), the messages as they come, and a box to write in when signed in (or why
 * one can't: e.g. Subscribers-only mode).
 */
final class PhoneLiveChat {
    interface Host {
        /** Where the video box is on screen, and whether the screen is in a mode where the chat shows at all */
        View videoBox();

        boolean chatAllowed();

        boolean landscape();

        /** An upright stream filling the screen: the chat over the bottom of the video, as in the YouTube app */
        boolean overlay();

        String videoId();

        String channelId();

        /** The chat was closed with its X */
        void onChatClosed();

        /** The video box needs room on its right (landscape), or none */
        void setVideoRightMargin(int px);
    }

    private static final int MAX_MESSAGES = 250;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final float mDensity;
    private final LinearLayout mPanel;
    private final RecyclerView mList;
    private final Adapter mAdapter = new Adapter();
    private final EditText mInput;
    private final View mSend;
    private final TextView mNotice;
    private final View mInputRow;
    private final View mHeader;
    private final View mTopDivider;
    private final View mBottomDivider;
    private final ImageView mMe;
    private ChatReceiver mReceiver;
    private boolean mOverlay;
    private String mChatKey;
    private boolean mClosed;

    PhoneLiveChat(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
        // The phone's own scale (the player screen's resources are scaled for the TV in full screen)
        mDensity = PhoneSheetLayout.screen().density;

        mPanel = new LinearLayout(activity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(color(R.color.phone_bg));
        mPanel.setClickable(true); // nothing under it gets the touches
        mPanel.setVisibility(View.GONE);

        // Header: Live chat / Top messages, and the X
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(6), dp(10));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(R.string.phone_live_chat, 18, R.color.phone_text);
        title.setTypeface(PhoneUi.medium(activity));
        titles.addView(title);
        titles.addView(text(R.string.phone_live_chat_top, 13, R.color.phone_text_2));
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
        mList.setPadding(0, dp(6), 0, dp(6));
        mList.setClipToPadding(false);
        mPanel.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mBottomDivider = divider();
        mPanel.addView(mBottomDivider);

        // Writing: a "Chat…" box and send, or why one can't
        mInputRow = new LinearLayout(activity);
        ((LinearLayout) mInputRow).setGravity(Gravity.CENTER_VERTICAL);
        mInputRow.setPadding(dp(14), dp(8), dp(6), dp(8));
        // Who's writing: the signed-in account's picture, as in the YouTube app
        mMe = new ImageView(activity);
        LinearLayout.LayoutParams meParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        meParams.setMarginEnd(dp(10));
        ((LinearLayout) mInputRow).addView(mMe, meParams);
        mInput = new EditText(activity);
        mInput.setHint(R.string.phone_live_chat_hint);
        mInput.setSingleLine(true);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mInput.setTextColor(color(R.color.phone_text));
        mInput.setHintTextColor(color(R.color.phone_text_2));
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(15));
        GradientDrawable box = new GradientDrawable();
        box.setColor(color(R.color.phone_surface));
        box.setCornerRadius(dp(22));
        mInput.setBackground(box);
        mInput.setPadding(dp(16), 0, dp(16), 0);
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        ((LinearLayout) mInputRow).addView(mInput, new LinearLayout.LayoutParams(0, dp(40), 1f));
        ImageView send = new ImageView(activity);
        send.setImageResource(R.drawable.ic_phone_send);
        send.setImageTintList(ColorStateList.valueOf(color(R.color.phone_text)));
        send.setPadding(dp(12), dp(12), dp(12), dp(12));
        send.setBackgroundResource(R.drawable.phone_ripple);
        send.setOnClickListener(v -> send());
        mSend = send;
        ((LinearLayout) mInputRow).addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mPanel.addView(mInputRow);
        mNotice = text(0, 14, R.color.phone_text_2);
        mNotice.setPadding(dp(18), dp(14), dp(18), dp(16));
        mNotice.setCompoundDrawablePadding(dp(12));
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

        FrameLayout content = activity.findViewById(android.R.id.content);
        content.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        host.videoBox().addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (t != ot || b != ob || l != ol || r != or) {
                mPanel.post(this::layout);
            }
        });
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

    boolean isShowing() {
        return mPanel.getVisibility() == View.VISIBLE;
    }

    /** Places the panel for the screen as it is now: under the video, beside it, or hidden */
    void layout() {
        boolean show = mReceiver != null && !mClosed && mHost.chatAllowed();
        boolean landscape = mHost.landscape();
        int sideWidth = show && landscape ? Math.round(mActivity.getResources().getDisplayMetrics().widthPixels * 0.36f) : 0;
        mHost.setVideoRightMargin(sideWidth);
        if (!show) {
            if (mPanel.getVisibility() != View.GONE) {
                hideKeyboard();
                mPanel.setVisibility(View.GONE);
            }
            return;
        }
        FrameLayout content = (FrameLayout) mPanel.getParent();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        int width;
        int height;
        int gravity;
        int top;
        boolean overlay = mHost.overlay() && !landscape;
        if (overlay != mOverlay) {
            setOverlay(overlay);
        }
        if (overlay) {
            // Over the bottom of the upright video
            Rect video = new Rect();
            Rect root = new Rect();
            mHost.videoBox().getGlobalVisibleRect(video);
            content.getGlobalVisibleRect(root);
            width = ViewGroup.LayoutParams.MATCH_PARENT;
            gravity = Gravity.TOP;
            height = Math.round(video.height() * 0.42f);
            top = Math.max(0, video.bottom - root.top - height);
        } else if (landscape) {
            width = sideWidth;
            height = ViewGroup.LayoutParams.MATCH_PARENT;
            gravity = Gravity.END | Gravity.TOP;
            top = 0;
        } else {
            // Right under the video, down to the bottom
            Rect video = new Rect();
            Rect root = new Rect();
            mHost.videoBox().getGlobalVisibleRect(video);
            content.getGlobalVisibleRect(root);
            width = ViewGroup.LayoutParams.MATCH_PARENT;
            gravity = Gravity.TOP;
            top = Math.max(0, video.bottom - root.top);
            height = Math.max(dp(160), root.height() - top);
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
    }

    /** On the video (no panel, a shade behind the messages) or as a panel */
    private void setOverlay(boolean overlay) {
        mOverlay = overlay;
        if (overlay) {
            mPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x99000000, 0xCC000000}));
        } else {
            mPanel.setBackgroundColor(color(R.color.phone_bg));
        }
        mHeader.setVisibility(overlay ? View.GONE : View.VISIBLE);
        mTopDivider.setVisibility(overlay ? View.GONE : View.VISIBLE);
        mBottomDivider.setVisibility(overlay ? View.GONE : View.VISIBLE);
        // The older messages fade out at the top over the video
        mList.setVerticalFadingEdgeEnabled(overlay);
        mList.setFadingEdgeLength(overlay ? dp(48) : 0);
        mAdapter.notifyDataSetChanged();
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

    // region Writing

    private void checkWriting() {
        boolean signedIn;
        try {
            signedIn = com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getSignInService().isSigned();
        } catch (Throwable e) {
            signedIn = false;
        }
        if (!signedIn) {
            showNotice(mActivity.getString(R.string.phone_live_chat_sign_in));
            return;
        }
        try {
            com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                    com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount();
            if (account != null && account.getAvatarImageUrl() != null) {
                PhoneUi.loadAvatar(mMe, account.getAvatarImageUrl());
            }
        } catch (Throwable e) {
            // no picture
        }
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
                reason = null; // try when sending
            }
            String why = reason;
            mMain.post(() -> {
                if (key.equals(mChatKey)) {
                    showNotice(why);
                }
            });
        });
    }

    /** Why one can't write here (null: the box to write in) */
    private void showNotice(String why) {
        mInputRow.setVisibility(why == null ? View.VISIBLE : View.GONE);
        mNotice.setVisibility(why == null ? View.GONE : View.VISIBLE);
        if (why != null) {
            mNotice.setText(why);
            android.graphics.drawable.Drawable info = mActivity.getDrawable(R.drawable.ic_phone_info);
            if (info != null) {
                info = info.mutate();
                info.setTint(color(R.color.phone_text_2));
                info.setBounds(0, 0, dp(22), dp(22));
            }
            mNotice.setCompoundDrawablesRelative(info, null, null, null);
        }
    }

    private void send() {
        String text = mInput.getText().toString().trim();
        String key = mChatKey;
        String videoId = mHost.videoId();
        if (TextUtils.isEmpty(text) || key == null || videoId == null) {
            return;
        }
        String channelId = mHost.channelId();
        mSend.setEnabled(false);
        mInput.setEnabled(false);
        WORKER.execute(() -> {
            String error = null;
            try {
                com.liskovsoft.youtubeapi.chat.LiveChatWriter.send(key, videoId, channelId, text);
            } catch (Throwable e) {
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
                } else {
                    android.widget.Toast.makeText(mActivity, mActivity.getString(R.string.phone_live_chat_failed, failed),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
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

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<ChatItem> mItems = new ArrayList<>();

        void add(ChatItem item) {
            if (item == null || TextUtils.isEmpty(item.getMessage())) {
                return;
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
            row.setPadding(dp(16), dp(6), dp(16), dp(6));
            ImageView avatar = new ImageView(parent.getContext());
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            row.addView(avatar, avatarParams);
            TextView text = new TextView(parent.getContext());
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(14));
            text.setTextColor(color(R.color.phone_text));
            text.setLineSpacing(0, 1.08f);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(14));
            row.addView(text, textParams);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(row, avatar, text);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ChatItem item = mItems.get(position);
            String author = item.getAuthorName() != null ? item.getAuthorName() : "";
            // The name a little smaller and grey, then the message (on video: white names, shadowed)
            SpannableStringBuilder line = new SpannableStringBuilder(author);
            line.setSpan(new ForegroundColorSpan(mOverlay ? 0xCCFFFFFF : color(R.color.phone_text_2)), 0, author.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new android.text.style.RelativeSizeSpan(0.93f), 0, author.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append("  ").append(item.getMessage());
            holder.text.setText(line);
            holder.text.setTextColor(mOverlay ? 0xFFFFFFFF : color(R.color.phone_text));
            holder.text.setShadowLayer(mOverlay ? dp(3) : 0, 0, 0, 0xCC000000);
            PhoneUi.loadAvatar(holder.avatar, item.getAuthorPhoto());
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

    private TextView text(int res, float sp, int colorRes) {
        TextView view = new TextView(mActivity);
        if (res != 0) {
            view.setText(res);
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneSheetLayout.sp(sp));
        view.setTextColor(color(colorRes));
        return view;
    }

    private View divider() {
        View view = new View(mActivity);
        view.setBackgroundColor(color(R.color.phone_divider));
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return view;
    }

    private int color(int res) {
        return mActivity.getResources().getColor(res);
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }
}
