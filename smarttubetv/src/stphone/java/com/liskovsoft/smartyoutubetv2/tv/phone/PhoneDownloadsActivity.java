package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.phone.PhoneDownloads.Kind;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * You > Downloads: what StreamTube saved to Download/StreamTube (newest first). Tap to play it in the app, the menu to
 * share or delete it.
 */
public class PhoneDownloadsActivity extends PhoneActivity {
    static final String EXTRA_KIND = "download_kind";
    private static final String SCROLL_STATE = "download_scroll_";
    private static final Kind[] KINDS = Kind.values();
    private static final int REQUEST_DELETE = 4801;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private List<Item> mAllItems = Collections.emptyList();
    private final List<DownloadPage> mPages = new ArrayList<>();
    private final List<TextView> mTabs = new ArrayList<>();
    private final Runnable mReload = this::load;
    private final ContentObserver mDownloadObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mHandler.removeCallbacks(mReload);
            mHandler.postDelayed(mReload, 300);
        }
    };
    private final ArgbEvaluator mTabColors = new ArgbEvaluator();
    private ViewPager mPager;
    private View mIndicator;
    private int mNormalText;
    private int mSelectedText;
    private boolean mRtl;
    private volatile int mLoadGeneration;
    private Item mPendingDelete;

    static final class Item {
        final Uri uri;
        final String title;
        final long size;
        final long addedMs;
        final Kind kind;
        final long durationMs;
        final String author;

        Item(Uri uri, String title, long size, long addedMs, Kind kind, long durationMs) {
            this(uri, title, "", size, addedMs, kind, durationMs);
        }

        Item(Uri uri, String title, String author, long size, long addedMs, Kind kind, long durationMs) {
            this.uri = uri;
            this.title = title;
            this.author = author;
            this.size = size;
            this.addedMs = addedMs;
            this.kind = kind;
            this.durationMs = durationMs;
        }

        Item withDuration(long duration) {
            return new Item(uri, title, author, size, addedMs, kind, duration);
        }
    }

    static void open(Activity activity) {
        activity.startActivity(new Intent(activity, PhoneDownloadsActivity.class),
                android.app.ActivityOptions.makeCustomAnimation(activity, R.anim.phone_screen_open_enter,
                        R.anim.phone_hold).toBundle());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_downloads_library);
        mPager = findViewById(R.id.phone_download_pager);
        mIndicator = findViewById(R.id.phone_download_indicator);
        mRtl = (getApplicationInfo().flags & ApplicationInfo.FLAG_SUPPORTS_RTL) != 0
                && getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        mNormalText = getColor(R.color.phone_text_2);
        mSelectedText = getColor(R.color.phone_text);
        applyInsets(findViewById(R.id.phone_root), findViewById(R.id.phone_top_bar), mPager);
        findViewById(R.id.phone_back).setOnClickListener(v -> finish());

        LinearLayout tabs = findViewById(R.id.phone_download_tabs);
        tabs.setLayoutDirection(mRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        for (Kind kind : KINDS) {
            DownloadPage page = new DownloadPage(kind);
            if (savedInstanceState != null) {
                page.scrollState = savedInstanceState.getParcelable(SCROLL_STATE + kind.name());
            }
            mPages.add(page);
            TextView tab = new TextView(this);
            tab.setText(kind.label);
            tab.setTypeface(PhoneUi.medium(this));
            tab.setTextSize(14);
            tab.setGravity(Gravity.CENTER);
            tab.setFocusable(true);
            tab.setPadding(dp(4), dp(8), dp(4), dp(8));
            tab.setMinHeight(dp(48));
            tab.setBackgroundResource(R.drawable.phone_ripple);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mTabs.add(tab);
            tab.setOnClickListener(v -> select(kind, true));
        }

        mPager.setOffscreenPageLimit(KINDS.length - 1);
        mPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() { return KINDS.length; }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View page = mPages.get(pagerPosition(position)).root;
                container.addView(page);
                return page;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        });
        mPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float offset, int pixels) {
                updateIndicator(position, offset);
            }

            @Override
            public void onPageSelected(int position) {
                updateSelectedTab();
            }
        });
        mPager.setPageTransformer(false, (page, position) ->
                page.setAlpha(1f - 0.18f * Math.min(1f, Math.abs(position))));
        tabs.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) ->
                updateIndicator(mPager.getCurrentItem(), 0f));
        select(readKind(savedInstanceState != null ? savedInstanceState.getString(EXTRA_KIND)
                : getIntent().getStringExtra(EXTRA_KIND)), false);
        updateSelectedTab();
        mHandler.postDelayed(this::showLoading, 180);
    }

    private void showLoading() {
        if (!isFinishing() && !isDestroyed()) {
            for (DownloadPage page : mPages) {
                page.showLoading();
            }
        }
    }

    private int pagerPosition(int index) {
        // ViewPager scrolls in physical coordinates; map its page order to the tab row in RTL.
        return mRtl ? KINDS.length - 1 - index : index;
    }

    private void select(Kind kind, boolean animate) {
        mPager.setCurrentItem(pagerPosition(kind.ordinal()), animate && ValueAnimator.areAnimatorsEnabled());
    }

    private void updateSelectedTab() {
        int selected = pagerPosition(mPager.getCurrentItem());
        for (int index = 0; index < mTabs.size(); index++) {
            mTabs.get(index).setSelected(index == selected);
        }
    }

    private void updateIndicator(int position, float offset) {
        int from = pagerPosition(position);
        int to = pagerPosition(Math.min(position + 1, KINDS.length - 1));
        TextView first = mTabs.get(from);
        TextView second = mTabs.get(to);
        float start = first.getLeft() + first.getWidth() / 2f;
        float end = second.getLeft() + second.getWidth() / 2f;
        mIndicator.setTranslationX(start + (end - start) * offset - mIndicator.getMeasuredWidth() / 2f);
        mIndicator.setScaleX(1f + 0.36f * (1f - Math.abs(1f - 2f * offset)));
        for (int index = 0; index < mTabs.size(); index++) {
            float weight = index == from ? 1f - offset : index == to ? offset : 0f;
            mTabs.get(index).setTextColor((Integer) mTabColors.evaluate(weight, mNormalText, mSelectedText));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        select(readKind(intent.getStringExtra(EXTRA_KIND)), false);
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(EXTRA_KIND, KINDS[pagerPosition(mPager.getCurrentItem())].name());
        for (DownloadPage page : mPages) {
            state.putParcelable(SCROLL_STATE + page.kind.name(), page.savedScroll());
        }
    }

    private static Kind readKind(String value) {
        for (Kind kind : Kind.values()) {
            if (kind.name().equals(value)) {
                return kind;
            }
        }
        return Kind.VIDEO;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= 29) {
            getContentResolver().registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mDownloadObserver);
        }
    }

    @Override
    protected void onStop() {
        if (Build.VERSION.SDK_INT >= 29) {
            getContentResolver().unregisterContentObserver(mDownloadObserver);
            mHandler.removeCallbacks(mReload);
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // a download may have finished meanwhile
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.phone_screen_close_enter, R.anim.phone_screen_close_exit);
    }

    @Override
    protected boolean hasPendingActions() {
        return false;
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
    }

    // region Files

    private void load() {
        int generation = ++mLoadGeneration;
        List<Item> previous = mAllItems;
        LOADER.execute(() -> {
            if (generation != mLoadGeneration) return;
            List<Item> items = readLibrary(this);
            reuseDurations(items, previous);
            publish(generation, items);

            // Files can be browsed while missing durations are read from the media itself.
            List<Item> enriched = new ArrayList<>(items);
            boolean changed = false;
            for (int index = 0; index < items.size() && generation == mLoadGeneration; index++) {
                Item item = items.get(index);
                if (item.durationMs > 0) {
                    continue;
                }
                long duration = readDuration(item.uri);
                if (duration > 0) {
                    enriched.set(index, item.withDuration(duration));
                    changed = true;
                }
            }
            if (changed) {
                publish(generation, enriched);
            }
        });
    }

    private static void reuseDurations(List<Item> items, List<Item> previous) {
        Map<Uri, Item> known = new HashMap<>();
        for (Item item : previous) {
            if (item.durationMs > 0) known.put(item.uri, item);
        }
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            Item prior = known.get(item.uri);
            if (item.durationMs <= 0 && prior != null && item.size == prior.size && item.addedMs == prior.addedMs) {
                items.set(index, item.withDuration(prior.durationMs));
            }
        }
    }

    private void publish(int generation, List<Item> items) {
        mHandler.post(() -> {
            if (generation == mLoadGeneration) {
                show(items);
            }
        });
    }

    static final java.util.Comparator<Item> NEWEST_FIRST = (a, b) -> {
        int byDate = Long.compare(b.addedMs, a.addedMs);
        return byDate != 0 ? byDate : a.uri.compareTo(b.uri);
    };

    private static List<Item> readLibrary(Context context) {
        List<Item> items = Build.VERSION.SDK_INT >= 29 ? queryMediaStore(context) : listFolder(context);
        Collections.sort(items, NEWEST_FIRST);
        return items;
    }

    static void loadQueue(Context context, Kind kind, java.util.function.Consumer<List<Item>> callback) {
        Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            List<Item> items = readLibrary(app);
            items.removeIf(item -> item.kind != kind);
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(items));
        });
    }

    /** Android 10+: the files this app put in Download/StreamTube (an app sees its own downloads without permission) */
    private static List<Item> queryMediaStore(Context context) {
        List<Item> items = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) {
            return items;
        }
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " IN (?, ?, ?) AND "
                + MediaStore.MediaColumns.IS_PENDING + " = 0";
        String[] args = {Kind.VIDEO.relativePath(), Kind.SHORT.relativePath(), Kind.MUSIC.relativePath()};
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, args, null)) {
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(4);
                Kind folder = Kind.SHORT.relativePath().equals(cursor.getString(6)) ? Kind.SHORT : Kind.VIDEO;
                String volume = cursor.getString(7);
                Uri uri = ContentUris.withAppendedId(volume == null ? collection : MediaStore.Downloads.getContentUri(volume), id);
                addFile(context, items, uri, name, mime, cursor.getLong(2), cursor.getLong(3) * 1000,
                        cursor.isNull(5) ? 0 : cursor.getLong(5), folder);
            }
        } catch (RuntimeException e) {
            // no list: shows the empty state
        }
        return items;
    }

    /** Android 8 and 9: the folder itself (the app already has the storage permission it downloaded with) */
    @SuppressWarnings("deprecation")
    private static List<Item> listFolder(Context context) {
        List<Item> items = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            File folder = new File(Environment.getExternalStorageDirectory(), kind.relativePath());
            File[] files = folder.listFiles(File::isFile);
            if (files != null) {
                for (File file : files) {
                    addFile(context, items, Uri.fromFile(file), file.getName(), null, file.length(), file.lastModified(), 0, kind);
                }
            }
        }
        return items;
    }

    private static void addFile(Context context, List<Item> items, Uri uri, String name, String mime, long size, long added, long duration, Kind folder) {
        boolean audio = isAudio(name, mime);
        if (!audio && (mime == null || !mime.startsWith("video/"))
                && (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))) {
            return;
        }
        PhoneDownloadDetails details = PhoneDownloadDetails.read(context, uri, title(name));
        items.add(new Item(uri, details.title, details.author, size, added, audio ? Kind.MUSIC : folder, duration));
    }

    private long readDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null ? Long.parseLong(duration) : 0;
        } catch (RuntimeException e) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // released
            }
        }
    }

    private static String title(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isAudio(String name, String mime) {
        return (mime != null && mime.startsWith("audio")) || (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".m4a"));
    }

    private void show(List<Item> items) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mAllItems = items;
        for (DownloadPage page : mPages) {
            List<Item> filtered = new ArrayList<>();
            for (Item item : items) {
                if (item.kind == page.kind) {
                    filtered.add(item);
                }
            }
            page.submit(filtered);
        }
    }

    // endregion

    // region Actions

    private void play(Item item) {
        PhoneLocalPlayerActivity.open(this, item);
    }

    private void share(Item item) {
        if ("file".equals(item.uri.getScheme())) {
            return; // Android 8 and 9: no shareable link for a plain file
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(item.kind == Kind.MUSIC ? "audio/mp4" : "video/mp4")
                .putExtra(Intent.EXTRA_STREAM, item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, item.title));
    }

    private void confirmDelete(Item item) {
        new AlertDialog.Builder(this, PhoneTheme.isNight(this)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(R.string.phone_downloads_delete_title)
                .setMessage(item.title)
                .setNegativeButton(R.string.phone_comment_delete_cancel, null)
                .setPositiveButton(R.string.phone_comment_delete, (dialog, which) -> delete(item))
                .show();
    }

    private void delete(Item item) {
        try {
            if ("file".equals(item.uri.getScheme())) {
                File file = new File(item.uri.getPath());
                if (!file.delete()) {
                    throw new SecurityException("not deleted");
                }
            } else {
                getContentResolver().delete(item.uri, null, null);
            }
            removed(item);
        } catch (SecurityException e) {
            // Downloaded before a reinstall: the file belongs to the old install, Android asks the user first
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    mPendingDelete = item;
                    startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(),
                            Collections.singletonList(item.uri)).getIntentSender(), REQUEST_DELETE, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) {
                    // falls through to the message
                }
            } else if (Build.VERSION.SDK_INT == 29 && e instanceof android.app.RecoverableSecurityException) {
                try {
                    mPendingDelete = item;
                    startIntentSenderForResult(((android.app.RecoverableSecurityException) e).getUserAction()
                            .getActionIntent().getIntentSender(), REQUEST_DELETE, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) {
                    // falls through to the message
                }
            }
            android.widget.Toast.makeText(this, R.string.phone_downloads_delete_failed, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DELETE && mPendingDelete != null) {
            Item item = mPendingDelete;
            mPendingDelete = null;
            if (resultCode == RESULT_OK) {
                if (Build.VERSION.SDK_INT == 29) {
                    delete(item); // allowed now: delete it for real
                } else {
                    removed(item); // Android deleted it
                }
            }
        }
    }

    private void removed(Item item) {
        PhoneDownloadDetails.remove(this, item.uri);
        List<Item> remaining = new ArrayList<>(mAllItems);
        remaining.removeIf(other -> other.uri.equals(item.uri));
        show(remaining);
        load();
        android.widget.Toast.makeText(this, R.string.phone_downloads_deleted, android.widget.Toast.LENGTH_SHORT).show();
    }

    // endregion

    private final class DownloadPage {
        final Kind kind;
        final ViewGroup root;
        final RecyclerView list;
        final TextView summary;
        final View content;
        final View empty;
        final View loading;
        final Adapter adapter = new Adapter();
        Parcelable scrollState;
        boolean loaded;

        DownloadPage(Kind kind) {
            this.kind = kind;
            root = (ViewGroup) getLayoutInflater().inflate(R.layout.phone_download_page, mPager, false);
            root.setLayoutDirection(mRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            list = root.findViewById(R.id.phone_downloads_list);
            summary = root.findViewById(R.id.phone_downloads_total);
            content = root.findViewById(R.id.phone_download_content);
            empty = root.findViewById(R.id.phone_download_empty_panel);
            loading = root.findViewById(R.id.phone_download_loading);

            ImageView icon = root.findViewById(R.id.phone_download_empty_icon);
            TextView title = root.findViewById(R.id.phone_download_empty_title);
            TextView hint = root.findViewById(R.id.phone_downloads_empty);
            switch (kind) {
                case VIDEO:
                    icon.setImageResource(R.drawable.ic_phone_download);
                    title.setText(R.string.phone_downloads_empty_title);
                    hint.setText(R.string.phone_downloads_video_hint);
                    break;
                case SHORT:
                    icon.setImageResource(R.drawable.ic_phone_shorts);
                    title.setText(R.string.phone_downloads_shorts_empty_title);
                    hint.setText(R.string.phone_downloads_short_hint);
                    break;
                case MUSIC:
                    icon.setImageResource(R.drawable.ic_phone_music_note);
                    title.setText(R.string.phone_downloads_music_empty_title);
                    hint.setText(R.string.phone_downloads_music_hint);
                    break;
            }

            list.setLayoutManager(new LinearLayoutManager(PhoneDownloadsActivity.this));
            list.setAdapter(adapter);
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false); // metadata updates keep the same artwork visible
            animator.setAddDuration(180);
            animator.setRemoveDuration(160);
            animator.setMoveDuration(210);
            list.setItemAnimator(animator);
        }

        void showLoading() {
            if (!loaded) {
                loading.setVisibility(View.VISIBLE);
            }
        }

        Parcelable savedScroll() {
            return scrollState != null ? scrollState : list.getLayoutManager().onSaveInstanceState();
        }

        void submit(List<Item> items) {
            loaded = true;
            adapter.submitList(items, () -> settle(items));
        }

        private void settle(List<Item> items) {
            if (scrollState != null && !items.isEmpty()) {
                list.getLayoutManager().onRestoreInstanceState(scrollState);
                scrollState = null;
            }
            View visible = items.isEmpty() ? empty : content;
            if (visible.getVisibility() != View.VISIBLE) {
                Fade fade = new Fade();
                fade.addTarget(content).addTarget(empty).addTarget(loading);
                fade.setDuration(180);
                TransitionManager.beginDelayedTransition(root, fade);
            }
            loading.setVisibility(View.GONE);
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            content.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
            long bytes = 0;
            for (Item item : items) {
                bytes += item.size;
            }
            int count = items.size();
            summary.setText(getResources().getQuantityString(R.plurals.phone_downloads_summary, count, count,
                    Formatter.formatShortFileSize(PhoneDownloadsActivity.this, bytes)));
        }
    }

    private static final DiffUtil.ItemCallback<Item> ITEM_DIFF = new DiffUtil.ItemCallback<Item>() {
        @Override
        public boolean areItemsTheSame(@NonNull Item oldItem, @NonNull Item newItem) {
            return oldItem.uri.equals(newItem.uri);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Item oldItem, @NonNull Item newItem) {
            return oldItem.durationMs == newItem.durationMs && oldItem.size == newItem.size
                    && oldItem.addedMs == newItem.addedMs && oldItem.title.equals(newItem.title)
                    && oldItem.author.equals(newItem.author);
        }
    };

    private final class Adapter extends ListAdapter<Item, RecyclerView.ViewHolder> {
        Adapter() {
            super(ITEM_DIFF);
        }

        @Override
        public int getItemViewType(int position) {
            return getItem(position).kind == Kind.VIDEO ? R.layout.phone_item_row : R.layout.phone_download_row;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = getItem(position);
            View view = holder.itemView;
            ImageView thumb = view.findViewById(R.id.phone_thumb);
            TextView badge = view.findViewById(R.id.phone_badge);
            ((TextView) view.findViewById(R.id.phone_title)).setText(item.title);
            String size = Formatter.formatShortFileSize(view.getContext(), item.size);
            CharSequence metadata = item.addedMs > 0
                    ? size + " · " + DateUtils.getRelativeTimeSpanString(item.addedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    : size;
            if (item.kind == Kind.VIDEO) {
                view.findViewById(R.id.phone_progress).setVisibility(View.GONE);
                if (!TextUtils.isEmpty(item.author)) {
                    metadata = TextUtils.concat(item.author, "\n", metadata);
                }
            } else {
                View cover = (View) thumb.getParent();
                ViewGroup.LayoutParams coverParams = cover.getLayoutParams();
                coverParams.width = dp(item.kind == Kind.SHORT ? 56 : 72);
                coverParams.height = dp(item.kind == Kind.SHORT ? 88 : 72);
                cover.setLayoutParams(coverParams);
                TextView author = view.findViewById(R.id.phone_download_author);
                author.setText(item.author);
                author.setVisibility(TextUtils.isEmpty(item.author) ? View.GONE : View.VISIBLE);
            }
            ((TextView) view.findViewById(R.id.phone_meta)).setText(metadata);

            badge.setText(DateUtils.formatElapsedTime(item.durationMs / 1000));
            badge.setVisibility(item.durationMs > 0 ? View.VISIBLE : View.GONE);
            if (PhoneUi.isAlive(PhoneDownloadsActivity.this)) {
                if (item.kind == Kind.MUSIC) {
                    PhoneDownloadArt.bind(thumb, item.uri);
                } else {
                    Glide.with(PhoneDownloadsActivity.this).load(item.uri).centerCrop()
                            .transition(DrawableTransitionOptions.withCrossFade(160))
                            .into(thumb);
                }
            }

            view.setOnClickListener(v -> play(item));
            View more = view.findViewById(R.id.phone_more);
            more.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(PhoneDownloadsActivity.this, more);
                menu.getMenu().add(0, 1, 0, R.string.phone_downloads_play);
                if (!"file".equals(item.uri.getScheme())) {
                    menu.getMenu().add(0, 2, 1, R.string.phone_share);
                }
                menu.getMenu().add(0, 3, 2, R.string.phone_comment_delete);
                menu.setOnMenuItemClickListener(menuItem -> {
                    switch (menuItem.getItemId()) {
                        case 1:
                            play(item);
                            return true;
                        case 2:
                            share(item);
                            return true;
                        case 3:
                            confirmDelete(item);
                            return true;
                        default:
                            return false;
                    }
                });
                menu.show();
            });
            view.setOnLongClickListener(v -> {
                confirmDelete(item);
                return true;
            });
        }
    }
}
