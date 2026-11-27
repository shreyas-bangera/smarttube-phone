package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.AbstractCommentsReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.CommentsReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppDialogActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * All of SmartTube's menus (video menu, quality, speed, accounts, settings...) as a phone bottom sheet.
 * Same OptionCategory/OptionItem model and click semantics as the TV dialog (AppPreferenceManager).
 */
public class PhoneDialogActivity extends MotherActivity implements AppDialogView {
    private static final int ROW_CATEGORY = 0;
    private static final int ROW_BUTTON = 1;
    private static final int ROW_SWITCH = 2;
    private static final int ROW_RADIO = 3;
    private static final int ROW_CHECK = 4;
    private static final int ROW_STRING = 5;
    private static final int ROW_TEXT = 6;

    private static final class Row {
        final int kind;
        final CharSequence title;
        final CharSequence description;
        final OptionItem item;
        final OptionCategory category;
        boolean selected;

        Row(int kind, CharSequence title, CharSequence description, OptionItem item, OptionCategory category, boolean selected) {
            this.kind = kind;
            this.title = title;
            this.description = description;
            this.item = item;
            this.category = category;
            this.selected = selected;
        }
    }

    private static final class Page {
        final CharSequence title;
        final List<Row> rows;
        CommentsState comments;

        Page(CharSequence title, List<Row> rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    private AppDialogPresenter mPresenter;
    private final List<Page> mStack = new ArrayList<>();
    private final RowsAdapter mAdapter = new RowsAdapter();
    private TextView mTitle;
    private View mBack;
    private TextView mCount; // comments total next to the title
    private View mSort; // comments: Top / Newest
    private TextView mSortLabel;
    private View mSortChevron;
    private View mComposer; // comments: write a comment or a reply
    private android.widget.EditText mComposerInput;
    private ImageView mComposerSend;
    private ImageView mComposerAvatar;
    private boolean mPosting;
    private RecyclerView mList;
    private com.liskovsoft.smartyoutubetv2.tv.phone.widget.MaxHeightFrameLayout mListFrame;
    private int mVideoLimit;
    private boolean mIsTransparent;
    private boolean mIsOverlay;
    private boolean mIsPaused;
    private boolean mIsDelegated;
    private boolean mIsFinishing;
    private int mId;

    @Override
    protected void attachBaseContext(android.content.Context context) {
        super.attachBaseContext(PhoneTheme.wrap(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this);
        setContentView(R.layout.phone_dialog);

        mTitle = findViewById(R.id.phone_sheet_title);
        mBack = findViewById(R.id.phone_sheet_back);
        mCount = findViewById(R.id.phone_sheet_count);
        mSort = findViewById(R.id.phone_sheet_sort);
        mSortLabel = findViewById(R.id.phone_sheet_sort_label);
        mSortChevron = findViewById(R.id.phone_sheet_sort_chevron);
        mSort.setOnClickListener(v -> showSortMenu());
        mComposer = findViewById(R.id.phone_sheet_composer);
        mComposerInput = findViewById(R.id.phone_sheet_composer_input);
        mComposerSend = findViewById(R.id.phone_sheet_composer_send);
        mComposerAvatar = findViewById(R.id.phone_sheet_composer_avatar);
        mComposerSend.setOnClickListener(v -> postComment());
        RecyclerView list = findViewById(R.id.phone_sheet_list);
        mList = list;
        mListFrame = findViewById(R.id.phone_sheet_list_frame);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setAdapter(mAdapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                Page page = currentPage();
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (page != null && page.comments != null && manager != null
                        && manager.findLastVisibleItemPosition() >= page.comments.adapter.getItemCount() - 4) {
                    page.comments.loadMore();
                }
            }
        });

        // Over the player in portrait: the sheet starts below the video, which stays clear and undimmed (like YouTube).
        // No blur behind sheets: it also redrew the playing video small and off to the side.
        mVideoLimit = PhonePlaybackActivity.sheetTopLimit();
        if (mVideoLimit > 0) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            View root = findViewById(R.id.phone_dialog_root);
            root.setPadding(root.getPaddingLeft(), mVideoLimit, root.getPaddingRight(), root.getPaddingBottom());
            mListFrame.setMaxScreenFraction(1f); // limited by the space under the video instead
        }

        findViewById(R.id.phone_dialog_root).setOnClickListener(v -> finish());
        mBack.setOnClickListener(v -> goBack());

        View sheet = findViewById(R.id.phone_sheet);
        ((PhoneSheetLayout) sheet).setOnDismiss(this::finish); // swiped down: closed

        final int sheetBottom = sheet.getPaddingBottom();
        findViewById(R.id.phone_dialog_root).setOnApplyWindowInsetsListener((v, insets) -> {
            sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(), sheet.getPaddingRight(),
                    sheetBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        mPresenter = AppDialogPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void initTheme() {
        // Keep the translucent sheet theme from the manifest (no TV settings theme)
    }

    @Override
    protected void onResume() {
        super.onResume();
        PhoneTheme.apply(this);
        PhoneUi.applyPhoneDensity(this);
        mIsPaused = false;
        showSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsPaused = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!mIsDelegated && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(getResources().getColor(R.color.phone_sheet));
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsAppearance(PhoneTheme.isNight(this) ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** The group the next settings page opens on (a settings search result's), or null */
    private static String sFocus;

    static void focusCategory(String title) {
        sFocus = title;
    }

    private static OptionCategory find(List<OptionCategory> categories, String title) {
        for (OptionCategory category : categories) {
            boolean list = category.type == OptionCategory.TYPE_RADIO_LIST || category.type == OptionCategory.TYPE_CHECKBOX_LIST
                    || category.type == OptionCategory.TYPE_STRING_LIST;
            if (list && category.options != null && category.title != null && title.contentEquals(category.title)) {
                return category;
            }
        }
        return null;
    }

    // region AppDialogView

    @Override
    public void show(List<OptionCategory> categories, CharSequence title, boolean isExpandable, boolean isTransparent, boolean isOverlay, int id) {
        if (isFinishing()) {
            return;
        }

        mIsTransparent = mStack.isEmpty() ? isTransparent : mIsTransparent;
        mIsOverlay = isOverlay;
        mId = id;

        if (categories != null) {
            for (OptionCategory category : categories) {
                if (category.type == OptionCategory.TYPE_CHAT) {
                    delegateToTvDialog();
                    return;
                }
            }
        }

        Page page;
        if (isExpandable && categories != null && categories.size() == 1 && categories.get(0).options != null) {
            OptionCategory category = categories.get(0);
            page = category.type == OptionCategory.TYPE_COMMENTS
                    ? commentsPage(category)
                    : categoryPage(category, category.title != null ? category.title : title);
        } else {
            page = rootPage(categories, title);
            // Opened from a settings search result: straight on its group (Back shows the page)
            OptionCategory focus = sFocus != null && categories != null ? find(categories, sFocus) : null;
            if (focus != null) {
                mStack.add(page);
                page = categoryPage(focus, focus.title);
            }
        }
        sFocus = null;

        mStack.add(page);
        render();
        if (page.comments != null) {
            page.comments.start();
        }
    }

    @Override
    public void finish() {
        if (mIsFinishing) {
            return;
        }
        mIsFinishing = true;
        for (Page page : mStack) {
            if (page.comments != null) {
                page.comments.finish();
            }
        }
        if (!mIsDelegated) {
            mPresenter.onFinish();
        }
        finishReally();
    }

    @Override
    public void goBack() {
        if (canGoBack()) {
            Page removed = mStack.remove(mStack.size() - 1);
            if (removed.comments != null) {
                removed.comments.finish();
            }
            render();
        } else {
            finish();
        }
    }

    @Override
    public void clearBackstack() {
        mStack.clear();
    }

    @Override
    public boolean canGoBack() {
        return mStack.size() > 1;
    }

    @Override
    public boolean isShown() {
        return !isFinishing() && !mIsPaused;
    }

    @Override
    public boolean isTransparent() {
        return mIsTransparent;
    }

    @Override
    public boolean isOverlay() {
        return mIsOverlay;
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public int getViewId() {
        return mId;
    }

    // endregion

    /**
     * Comments and live chat need the streaming TV panel for now.
     */
    private void delegateToTvDialog() {
        mIsDelegated = true;
        Intent intent = new Intent(this, AppDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finishReally();
    }

    // region Pages

    private Page rootPage(List<OptionCategory> categories, CharSequence title) {
        List<Row> rows = new ArrayList<>();
        if (categories != null) {
            for (OptionCategory category : categories) {
                if (category.options == null || category.options.isEmpty()) {
                    continue;
                }
                OptionItem first = category.options.get(0);
                if (PhoneTvOnly.isCategory(this, category.title) || PhoneTvOnly.isCategory(this, first.getTitle())
                        || PhoneTvOnly.isOption(this, first.getTitle())) {
                    continue; // only does something on a TV (PhoneTvOnly)
                }
                switch (category.type) {
                    case OptionCategory.TYPE_SINGLE_BUTTON:
                        rows.add(new Row(ROW_BUTTON, first.getTitle(), first.getDescription(), first, category, false));
                        break;
                    case OptionCategory.TYPE_SINGLE_SWITCH:
                        rows.add(new Row(ROW_SWITCH, first.getTitle(), first.getDescription(), first, category, first.isSelected()));
                        break;
                    default:
                        if (!PhoneTvOnly.isCategory(this, category.title)) {
                            rows.add(new Row(ROW_CATEGORY, category.title, selectedSummary(category), null, category, false));
                        }
                        break;
                }
            }
        }
        return new Page(title, rows);
    }

    private static CharSequence selectedSummary(OptionCategory category) {
        if (category.type != OptionCategory.TYPE_RADIO_LIST || category.options == null) {
            return null;
        }
        for (OptionItem item : category.options) {
            if (item.isSelected()) {
                return item.getTitle();
            }
        }
        return null;
    }

    private Page categoryPage(OptionCategory category, CharSequence title) {
        List<Row> rows = new ArrayList<>();
        for (OptionItem item : category.options) {
            if (category.type != OptionCategory.TYPE_RADIO_LIST && item != null && PhoneTvOnly.isOption(this, item.getTitle())) {
                continue; // a TV-only option in a list of options
            }
            switch (category.type) {
                case OptionCategory.TYPE_RADIO_LIST:
                    rows.add(new Row(ROW_RADIO, item.getTitle(), item.getDescription(), item, category, item.isSelected()));
                    break;
                case OptionCategory.TYPE_CHECKBOX_LIST:
                    rows.add(new Row(ROW_CHECK, item.getTitle(), item.getDescription(), item, category, item.isSelected()));
                    break;
                case OptionCategory.TYPE_LONG_TEXT:
                    rows.add(new Row(ROW_TEXT, item.getTitle(), null, item, category, false));
                    break;
                case OptionCategory.TYPE_SINGLE_SWITCH:
                    rows.add(new Row(ROW_SWITCH, item.getTitle(), item.getDescription(), item, category, item.isSelected()));
                    break;
                case OptionCategory.TYPE_SINGLE_BUTTON:
                    rows.add(new Row(ROW_BUTTON, item.getTitle(), item.getDescription(), item, category, false));
                    break;
                default:
                    rows.add(new Row(ROW_STRING, item.getTitle(), item.getDescription(), item, category, item.isSelected()));
                    break;
            }
        }
        return new Page(title, rows);
    }

    private Page currentPage() {
        return mStack.isEmpty() ? null : mStack.get(mStack.size() - 1);
    }

    private void render() {
        Page page = currentPage();
        if (page == null) {
            return;
        }
        mTitle.setText(page.title);
        mTitle.setVisibility(TextUtils.isEmpty(page.title) ? View.INVISIBLE : View.VISIBLE);
        mBack.setVisibility(mStack.size() > 1 ? View.VISIBLE : View.GONE);
        renderCommentsHeader(page);
        renderComposer(page);
        if (page.comments != null) {
            // Comments keep a steady, tall sheet (over the player: all the space under the video). The list takes
            // what the header and the comment bar leave, so the bar stays on screen (and above the keyboard)
            fillSheet(true);
            mList.setAdapter(page.comments.adapter);
        } else {
            fillSheet(false);
            if (mList.getAdapter() != mAdapter) {
                mList.setAdapter(mAdapter);
            }
            mAdapter.setRows(page.rows);
        }
    }

    /** Comments: a tall sheet whose list fills it above the comment bar. Other sheets wrap their rows. */
    private void fillSheet(boolean fill) {
        View sheet = findViewById(R.id.phone_sheet);
        android.widget.FrameLayout.LayoutParams sheetParams = (android.widget.FrameLayout.LayoutParams) sheet.getLayoutParams();
        int screen = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
        int height = !fill ? ViewGroup.LayoutParams.WRAP_CONTENT
                : mVideoLimit > 0 ? ViewGroup.LayoutParams.MATCH_PARENT : (int) (screen * 0.8f);
        if (sheetParams.height != height) {
            sheetParams.height = height;
            sheet.setLayoutParams(sheetParams);
        }
        LinearLayout.LayoutParams listParams = (LinearLayout.LayoutParams) mListFrame.getLayoutParams();
        int listHeight = fill ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        float weight = fill ? 1f : 0f;
        if (listParams.height != listHeight || listParams.weight != weight) {
            listParams.height = listHeight;
            listParams.weight = weight;
            mListFrame.setLayoutParams(listParams);
        }
        mListFrame.setMinimumHeight(0);
    }

    /** Comments: "Comments 24" and the Top / Newest button (replies and other sheets show neither) */
    private void renderCommentsHeader(Page page) {
        AbstractCommentsReceiver receiver = page.comments != null && page.comments.receiver instanceof AbstractCommentsReceiver
                ? (AbstractCommentsReceiver) page.comments.receiver : null;
        String count = receiver != null ? receiver.getCommentsCount() : null;
        mCount.setText(count);
        mCount.setVisibility(TextUtils.isEmpty(count) ? View.GONE : View.VISIBLE);
        boolean sortable = receiver != null && receiver.canSortByNewest();
        mSort.setVisibility(sortable ? View.VISIBLE : View.GONE);
        if (sortable) {
            mSortLabel.setText(receiver.isSortedByNewest() ? R.string.phone_comments_newest : R.string.phone_comments_top);
        }
    }

    // region Writing comments

    private static AbstractCommentsReceiver writableReceiver(Page page) {
        if (page == null || page.comments == null || !(page.comments.receiver instanceof AbstractCommentsReceiver)) {
            return null;
        }
        AbstractCommentsReceiver receiver = (AbstractCommentsReceiver) page.comments.receiver;
        return receiver.getVideoId() != null ? receiver : null;
    }

    /** The comment bar under the comments (and replies): the account's picture, the text field and Post */
    private void renderComposer(Page page) {
        AbstractCommentsReceiver receiver = writableReceiver(page);
        if (receiver == null) {
            mComposer.setVisibility(View.GONE);
            return;
        }
        mComposer.setVisibility(View.VISIBLE);
        com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount();
        boolean signedIn = account != null && !account.isEmpty();
        boolean reply = receiver.getParentCommentId() != null;

        if (signedIn && account.getAvatarImageUrl() != null) {
            mComposerAvatar.setImageTintList(null);
            PhoneUi.loadAvatar(mComposerAvatar, account.getAvatarImageUrl());
        } else {
            mComposerAvatar.setImageResource(R.drawable.ic_phone_account);
            mComposerAvatar.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.phone_text_2)));
        }

        mComposerInput.setText(null);
        mComposerInput.setHint(!signedIn ? R.string.phone_comment_sign_in : reply ? R.string.phone_comment_reply : R.string.phone_comment_add);
        mComposerInput.setFocusable(signedIn);
        mComposerInput.setFocusableInTouchMode(signedIn);
        mComposerInput.setOnClickListener(signedIn ? null : v -> {
            // Signed out: the field leads to signing in
            com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter.instance(this).start();
            finish();
        });
        mComposerSend.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        setPosting(false);
    }

    private void setPosting(boolean posting) {
        mPosting = posting;
        mComposerSend.setEnabled(!posting);
        mComposerSend.setAlpha(posting ? 0.4f : 1f);
        mComposerInput.setEnabled(!posting);
    }

    /** Post: YouTube adds the comment (or reply) to the video with the signed-in account; it shows on top */
    private void postComment() {
        Page page = currentPage();
        AbstractCommentsReceiver receiver = writableReceiver(page);
        String text = mComposerInput.getText() != null ? mComposerInput.getText().toString().trim() : "";
        if (receiver == null || mPosting || text.isEmpty()) {
            return;
        }
        String videoId = receiver.getVideoId();
        String parentId = receiver.getParentCommentId();
        com.liskovsoft.mediaserviceinterfaces.CommentsService service =
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getCommentsService();
        io.reactivex.Observable<CommentItem> post = parentId != null
                ? service.createReplyObserve(videoId, parentId, text)
                : service.createCommentObserve(videoId, text);
        setPosting(true);
        com.liskovsoft.sharedutils.rx.RxHelper.execute(post, comment -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            setPosting(false);
            mComposerInput.setText(null);
            hideKeyboard();
            Page current = currentPage();
            if (current == page && page.comments != null) {
                if (comment != null && !TextUtils.isEmpty(comment.getMessage())) {
                    if (comment.getId() != null) {
                        sPostedIds.add(comment.getId()); // long press offers Delete
                    }
                    page.comments.items.add(0, comment);
                    page.comments.adapter.notifyItemInserted(0);
                    mList.scrollToPosition(0);
                } else {
                    page.comments.restart(); // posted, but YouTube didn't describe it: load the list again
                }
            }
            android.widget.Toast.makeText(this, parentId != null ? R.string.phone_comment_reply_posted : R.string.phone_comment_posted,
                    android.widget.Toast.LENGTH_SHORT).show();
        }, error -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            setPosting(false);
            String message = error.getMessage() != null ? error.getMessage().replace("ErrorResponse: ", "") : "";
            if (message.contains("Not signed in")) {
                message = getString(R.string.phone_comment_sign_in);
            }
            android.widget.Toast.makeText(this, getString(R.string.phone_comment_failed, message), android.widget.Toast.LENGTH_LONG).show();
        });
    }

    private static final java.util.regex.Pattern TIMESTAMP =
            java.util.regex.Pattern.compile("(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\d:])");

    /**
     * Times in a comment ("2:31", "1:02:45") are links that jump the playing video there, like the YouTube app. Only for
     * the comments of the video that's playing. A tap anywhere else on the text still opens the replies.
     */
    private void linkTimestamps(TextView message, String text) {
        message.setOnTouchListener(null);
        if (TextUtils.isEmpty(text) || !commentsOfPlayingVideo()) {
            message.setText(text);
            return;
        }
        android.text.SpannableString spannable = new android.text.SpannableString(text);
        java.util.regex.Matcher matcher = TIMESTAMP.matcher(text);
        boolean found = false;
        while (matcher.find()) {
            long hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
            long minutes = Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            if (seconds > 59 || (hours > 0 && minutes > 59)) {
                continue;
            }
            long positionMs = ((hours * 60 + minutes) * 60 + seconds) * 1000;
            spannable.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    seekPlayingVideo(positionMs);
                }

                @Override
                public void updateDrawState(@NonNull android.text.TextPaint paint) {
                    paint.setColor(getResources().getColor(R.color.phone_link));
                    paint.setUnderlineText(false);
                }
            }, matcher.start(), matcher.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            found = true;
        }
        message.setText(spannable);
        if (!found) {
            return;
        }
        // Only the times take the tap: the rest of the row keeps its click (replies) and long press
        message.setOnTouchListener((v, event) -> {
            TextView view = (TextView) v;
            android.text.Layout layout = view.getLayout();
            if (layout == null || !(view.getText() instanceof android.text.Spanned)) {
                return false;
            }
            int x = (int) event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
            int y = (int) event.getY() - view.getTotalPaddingTop() + view.getScrollY();
            int line = layout.getLineForVertical(y);
            if (x > layout.getLineRight(line)) {
                return false;
            }
            int offset = layout.getOffsetForHorizontal(line, x);
            android.text.style.ClickableSpan[] links =
                    ((android.text.Spanned) view.getText()).getSpans(offset, offset, android.text.style.ClickableSpan.class);
            if (links.length == 0) {
                return false;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                links[0].onClick(view);
            }
            return true;
        });
    }

    private boolean commentsOfPlayingVideo() {
        AbstractCommentsReceiver receiver = writableReceiver(currentPage());
        com.liskovsoft.smartyoutubetv2.common.app.models.data.Video playing =
                com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(this).getVideo();
        return receiver != null && playing != null && Helpers.equals(receiver.getVideoId(), playing.videoId);
    }

    private void seekPlayingVideo(long positionMs) {
        com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView player =
                com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(this).getView();
        if (player != null) {
            player.setPositionMs(positionMs);
            player.setPlayWhenReady(true);
        }
    }

    /** Comments posted from this app (YouTube may show them under another name until the list reloads) */
    private static final java.util.Set<String> sPostedIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** "@Some_Handle" and "some_handle" are the same person */
    private static String normalizeHandle(String name) {
        if (name == null) {
            return null;
        }
        String handle = name.trim();
        if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }
        return handle.isEmpty() ? null : handle.toLowerCase(java.util.Locale.ROOT);
    }

    /** Written by the signed-in account (its channel handle or name is the comment's author) */
    private boolean isOwnComment(CommentItem item) {
        if (item == null || item.getId() == null || writableReceiver(currentPage()) == null) {
            return false;
        }
        com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount();
        if (account == null || account.isEmpty()) {
            return false;
        }
        if (sPostedIds.contains(item.getId())) {
            return true;
        }
        String author = normalizeHandle(item.getAuthorName());
        if (author == null) {
            return false;
        }
        String channel = account instanceof com.liskovsoft.googlecommon.service.oauth.YouTubeAccount
                ? normalizeHandle(((com.liskovsoft.googlecommon.service.oauth.YouTubeAccount) account).getChannelName()) : null;
        return author.equals(channel) || author.equals(normalizeHandle(account.getName()));
    }

    private void confirmDelete(CommentItem item) {
        Page page = currentPage();
        boolean reply = item.getId().contains(".");
        new android.app.AlertDialog.Builder(this, PhoneTheme.isNight(this)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(reply ? R.string.phone_comment_delete_reply_title : R.string.phone_comment_delete_title)
                .setMessage(R.string.phone_comment_delete_message)
                .setNegativeButton(R.string.phone_comment_delete_cancel, null)
                .setPositiveButton(R.string.phone_comment_delete, (dialog, which) -> deleteComment(page, item))
                .show();
    }

    /** YouTube removes the comment (or reply); it leaves the list right away */
    private void deleteComment(Page page, CommentItem item) {
        AbstractCommentsReceiver receiver = writableReceiver(page);
        if (receiver == null) {
            return;
        }
        com.liskovsoft.mediaserviceinterfaces.CommentsService service =
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getCommentsService();
        com.liskovsoft.sharedutils.rx.RxHelper.execute(service.deleteCommentObserve(receiver.getVideoId(), item.getId()), error -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            String message = error.getMessage() != null ? error.getMessage().replace("ErrorResponse: ", "") : "";
            android.widget.Toast.makeText(this, getString(R.string.phone_comment_delete_failed, message), android.widget.Toast.LENGTH_LONG).show();
        }, () -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            sPostedIds.remove(item.getId());
            if (page.comments != null) {
                int index = page.comments.items.indexOf(item);
                if (index >= 0) {
                    page.comments.items.remove(index);
                    page.comments.adapter.notifyItemRemoved(index);
                }
            }
            android.widget.Toast.makeText(this, R.string.phone_comment_deleted, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(mComposerInput.getWindowToken(), 0);
        }
        mComposerInput.clearFocus();
    }

    // endregion

    private void showSortMenu() {
        Page page = currentPage();
        if (page == null || page.comments == null || !(page.comments.receiver instanceof AbstractCommentsReceiver)) {
            return;
        }
        AbstractCommentsReceiver receiver = (AbstractCommentsReceiver) page.comments.receiver;
        boolean newest = receiver.isSortedByNewest();
        float density = getResources().getDisplayMetrics().density;

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(getResources().getColor(R.color.phone_surface_2));
        background.setCornerRadius(14 * density);
        menu.setBackground(background);

        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu,
                (int) (264 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(12 * density);
        popup.setOutsideTouchable(true);
        menu.addView(sortRow(R.string.phone_comments_top, R.string.phone_comments_top_hint, !newest, () -> {
            popup.dismiss();
            setCommentsOrder(page, false);
        }));
        menu.addView(sortRow(R.string.phone_comments_newest, R.string.phone_comments_newest_hint, newest, () -> {
            popup.dismiss();
            setCommentsOrder(page, true);
        }));
        mSortChevron.setRotation(-90);
        popup.setOnDismissListener(() -> mSortChevron.setRotation(90));
        popup.showAsDropDown(mSort, 0, 0, android.view.Gravity.END);
    }

    private View sortRow(int title, int hint, boolean checked, Runnable onClick) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), (int) (10 * density));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (ripple.resourceId != 0) {
            row.setBackgroundResource(ripple.resourceId);
        }

        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_phone_check);
        check.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.phone_text)));
        check.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16);
        name.setTextColor(getResources().getColor(R.color.phone_text));
        TextView description = new TextView(this);
        description.setText(hint);
        description.setTextSize(13);
        description.setTextColor(getResources().getColor(R.color.phone_text_2));
        texts.addView(name);
        texts.addView(description);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textsParams.setMarginStart((int) (16 * density));
        row.addView(texts, textsParams);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /** Top or Newest: the list starts over from the first page of that order */
    private void setCommentsOrder(Page page, boolean newest) {
        AbstractCommentsReceiver receiver = (AbstractCommentsReceiver) page.comments.receiver;
        if (receiver.isSortedByNewest() == newest) {
            return;
        }
        receiver.setSortedByNewest(newest);
        page.comments.restart();
        mList.scrollToPosition(0);
        renderCommentsHeader(page);
    }

    private Page commentsPage(OptionCategory category) {
        boolean nested = false;
        for (Page page : mStack) {
            if (page.comments != null) {
                nested = true;
                break;
            }
        }
        Page page = new Page(getString(nested ? R.string.phone_replies_title : R.string.phone_comments), new ArrayList<>());
        page.comments = new CommentsState(category.options.get(0).getCommentsReceiver());
        return page;
    }

    private void onRowClicked(Row row) {
        int depth = mStack.size();

        switch (row.kind) {
            case ROW_CATEGORY: {
                Page page = row.category.type == OptionCategory.TYPE_COMMENTS
                        ? commentsPage(row.category)
                        : categoryPage(row.category, row.category.title);
                mStack.add(page);
                render();
                if (page.comments != null) {
                    page.comments.start();
                }
                return;
            }
            case ROW_BUTTON:
            case ROW_STRING:
                row.item.onSelect(true);
                return;
            case ROW_SWITCH:
                row.selected = !row.selected;
                row.item.onSelect(row.selected);
                mAdapter.notifyDataSetChanged();
                return;
            case ROW_RADIO: {
                Page page = mStack.get(mStack.size() - 1);
                for (Row other : page.rows) {
                    other.selected = other == row;
                }
                mAdapter.notifyDataSetChanged();
                row.item.onSelect(true);
                // Like the TV list dialog: a choice closes this list (unless the app already opened something else)
                if (!isFinishing() && mStack.size() == depth) {
                    goBack();
                }
                return;
            }
            case ROW_CHECK: {
                boolean selected = !row.selected;
                if (selected) {
                    OptionItem[] required = row.item.getRequired();
                    if (required != null) {
                        for (OptionItem item : required) {
                            if (!item.isSelected()) {
                                MessageHelpers.showMessage(this, getString(R.string.require_checked, item.getTitle()));
                            }
                        }
                    }
                    OptionItem[] radio = row.item.getRadio();
                    if (radio != null) {
                        Page page = mStack.get(mStack.size() - 1);
                        for (OptionItem item : radio) {
                            item.onSelect(false);
                            for (Row other : page.rows) {
                                if (other.item == item) {
                                    other.selected = false;
                                }
                            }
                        }
                    }
                }
                row.selected = selected;
                row.item.onSelect(selected);
                mAdapter.notifyDataSetChanged();
                return;
            }
            default:
                // Long text: nothing to do
        }
    }

    // endregion

    // region Comments

    private static final class PhoneCommentsBackup implements CommentsReceiver.Backup {
        final List<CommentItem> items;
        final CommentGroup group;

        PhoneCommentsBackup(List<CommentItem> items, CommentGroup group) {
            this.items = items;
            this.group = group;
        }
    }

    private final class CommentsState implements CommentsReceiver.Callback {
        final CommentsReceiver receiver;
        final List<CommentItem> items = new ArrayList<>();
        final CommentsAdapter adapter;
        CommentGroup group;
        String footer;
        boolean loading = true;
        boolean finished;

        CommentsState(CommentsReceiver receiver) {
            this.receiver = receiver;
            this.adapter = new CommentsAdapter(this);
            footer = receiver != null ? receiver.getLoadingMessage() : null;
        }

        void start() {
            if (receiver == null) {
                return;
            }
            receiver.setCallback(this);
            receiver.onStart();
        }

        /** Another order picked: empty list, loading line, first page again */
        void restart() {
            items.clear();
            group = null;
            loading = true;
            finished = false;
            footer = receiver != null ? receiver.getLoadingMessage() : null;
            adapter.notifyDataSetChanged();
            start();
        }

        @Override
        public void onCommentGroup(CommentGroup commentGroup) {
            runOnUiThread(() -> {
                loading = false;
                if (commentGroup == null || commentGroup.getComments() == null) {
                    footer = items.isEmpty() ? receiver.getErrorMessage() : null;
                    adapter.notifyDataSetChanged();
                    return;
                }
                for (CommentItem item : commentGroup.getComments()) {
                    // Note: isEmpty() means "no replies to open", not "no content": replies (and comments without
                    // replies) are all "empty", so filtering on it hid every reply
                    if (item != null && (!TextUtils.isEmpty(item.getMessage()) || !TextUtils.isEmpty(item.getAuthorName()))) {
                        items.add(item);
                    }
                }
                group = commentGroup;
                footer = items.isEmpty() ? receiver.getErrorMessage()
                        : commentGroup.getNextCommentsKey() != null ? receiver.getLoadingMessage() : null;
                adapter.notifyDataSetChanged();
                if (items.size() <= 10 && commentGroup.getNextCommentsKey() != null) {
                    loadMore();
                }
            });
        }

        @Override
        public void onBackup(CommentsReceiver.Backup backup) {
            runOnUiThread(() -> {
                loading = false;
                if (backup instanceof PhoneCommentsBackup) {
                    items.clear();
                    items.addAll(((PhoneCommentsBackup) backup).items);
                    group = ((PhoneCommentsBackup) backup).group;
                    footer = items.isEmpty() ? receiver.getErrorMessage()
                            : group != null && group.getNextCommentsKey() != null ? receiver.getLoadingMessage() : null;
                } else {
                    footer = receiver.getErrorMessage();
                }
                adapter.notifyDataSetChanged();
            });
        }

        @Override
        public void onSync(CommentItem commentItem) {
            runOnUiThread(() -> {
                for (int i = 0; i < items.size(); i++) {
                    if (Helpers.equals(items.get(i).getId(), commentItem.getId())) {
                        items.set(i, commentItem);
                        adapter.notifyItemChanged(i);
                    }
                }
            });
        }

        void loadMore() {
            if (loading || finished || group == null || group.getNextCommentsKey() == null) {
                return;
            }
            loading = true;
            receiver.onLoadMore(group);
        }

        void finish() {
            if (finished || receiver == null) {
                return;
            }
            finished = true;
            receiver.onFinish(new PhoneCommentsBackup(new ArrayList<>(items), group));
        }
    }

    private final class CommentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_COMMENT = 0;
        private static final int TYPE_FOOTER = 1;
        private final CommentsState mState;

        CommentsAdapter(CommentsState state) {
            mState = state;
        }

        @Override
        public int getItemCount() {
            return mState.items.size() + (TextUtils.isEmpty(mState.footer) ? 0 : 1);
        }

        @Override
        public int getItemViewType(int position) {
            return position < mState.items.size() ? TYPE_COMMENT : TYPE_FOOTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_FOOTER) {
                TextView text = new TextView(parent.getContext());
                text.setTextColor(getResources().getColor(R.color.phone_text_2));
                text.setTextSize(14);
                text.setGravity(android.view.Gravity.CENTER);
                int pad = (int) (24 * getResources().getDisplayMetrics().density);
                text.setPadding(pad, pad, pad, pad);
                text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                view = text;
            } else {
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.phone_comment_row, parent, false);
            }
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == TYPE_FOOTER) {
                ((TextView) holder.itemView).setText(mState.footer);
                return;
            }

            CommentItem item = mState.items.get(position);
            View view = holder.itemView;
            android.widget.ImageView avatar = view.findViewById(R.id.phone_thumb);
            TextView meta = view.findViewById(R.id.phone_meta);
            TextView message = view.findViewById(R.id.phone_title);
            TextView likes = view.findViewById(R.id.phone_comment_likes);
            android.widget.ImageView likeIcon = view.findViewById(R.id.phone_comment_like_icon);
            TextView replies = view.findViewById(R.id.phone_comment_replies);

            String author = item.getAuthorName() != null ? item.getAuthorName() : "";
            String date = item.getPublishedDate();
            meta.setText(TextUtils.isEmpty(date) ? author : author + "  ·  " + date);
            linkTimestamps(message, item.getMessage());
            likes.setText(item.getLikeCount());
            likeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(
                    item.isLiked() ? R.color.phone_accent : R.color.phone_text_2)));

            boolean hasReplies = item.getNestedCommentsKey() != null && !TextUtils.isEmpty(item.getReplyCount());
            String count = item.getReplyCount();
            // YouTube sends "68 replies" (already worded) or just "68"
            boolean worded = count != null && count.matches(".*[^0-9.,KkMm\\s].*");
            replies.setText(hasReplies ? (worded ? count : getString(R.string.phone_replies, count)) : null);
            replies.setVisibility(hasReplies ? View.VISIBLE : View.GONE);

            if (PhoneUi.isAlive(PhoneDialogActivity.this)) {
                if (!TextUtils.isEmpty(item.getAuthorPhoto())) {
                    Glide.with(PhoneDialogActivity.this).load(item.getAuthorPhoto()).circleCrop()
                            .placeholder(new AvatarCache.LetterDrawable(author)).into(avatar);
                } else {
                    Glide.with(PhoneDialogActivity.this).clear(avatar);
                    avatar.setImageDrawable(new AvatarCache.LetterDrawable(author));
                }
            }

            view.setOnClickListener(v -> {
                if (item.getNestedCommentsKey() != null) {
                    mState.receiver.onCommentClicked(item);
                }
            });
            view.findViewById(R.id.phone_comment_like).setOnClickListener(v -> {
                com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                        com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount();
                if (account == null || account.isEmpty()) {
                    android.widget.Toast.makeText(PhoneDialogActivity.this, R.string.phone_comment_like_sign_in,
                            android.widget.Toast.LENGTH_SHORT).show();
                } else if (item.getNestedCommentsKey() != null) {
                    mState.receiver.onCommentLongClicked(item); // SmartTube's like toggle: the row updates at once
                }
            });
            view.setOnLongClickListener(v -> {
                if (isOwnComment(item)) {
                    confirmDelete(item); // the account's own comment: delete it
                } else {
                    mState.receiver.onCommentLongClicked(item);
                }
                return true;
            });
        }
    }

    // endregion

    private final class RowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Row> mRows = new ArrayList<>();

        void setRows(List<Row> rows) {
            mRows.clear();
            mRows.addAll(rows);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.phone_dialog_row, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            View view = holder.itemView;
            TextView title = view.findViewById(R.id.phone_title);
            TextView meta = view.findViewById(R.id.phone_meta);
            View check = view.findViewById(R.id.phone_row_check);
            Switch toggle = view.findViewById(R.id.phone_row_switch);
            View chevron = view.findViewById(R.id.phone_row_chevron);

            title.setText(row.title);
            title.setTextSize(row.kind == ROW_TEXT ? 15 : 16);
            title.setMaxLines(row.kind == ROW_TEXT ? Integer.MAX_VALUE : 3);
            meta.setText(row.description);
            meta.setVisibility(TextUtils.isEmpty(row.description) ? View.GONE : View.VISIBLE);

            boolean isList = row.kind == ROW_RADIO || row.kind == ROW_CHECK;
            check.setVisibility(isList && row.selected ? View.VISIBLE : isList ? View.INVISIBLE : View.GONE);
            toggle.setVisibility(row.kind == ROW_SWITCH ? View.VISIBLE : View.GONE);
            toggle.setChecked(row.selected);
            chevron.setVisibility(row.kind == ROW_CATEGORY ? View.VISIBLE : View.GONE);

            view.setOnClickListener(row.kind == ROW_TEXT ? null : v -> onRowClicked(row));
            view.setClickable(row.kind != ROW_TEXT);
        }
    }
}
