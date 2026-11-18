package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.phone.widget.RatioFrameLayout;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Music section, laid out like YouTube's own Music page: a swipeable hero of featured music videos, the Music
 * header with Play and Shuffle, then each shelf the way it suits: playlists as rows (square art, description, track
 * count), charts as big cards with their video count, songs as the usual horizontal shelf.
 */
final class PhoneMusicFeed {
    static final int VT_HERO = 101;
    static final int VT_HEADER = 102;
    static final int VT_TITLE = 103;
    static final int VT_PLAYLIST = 104;
    static final int VT_CHART = 105;

    private static final int HERO_SIZE = 6;
    private static final int ROWS_PER_SHELF = 6;
    private static final long HERO_AUTO_MS = 5_000;

    static final class Hero {
        final List<Video> videos = new ArrayList<>();
    }

    static final class Header {
        final List<Video> pool = new ArrayList<>();
    }

    static final class Title {
        final String text;

        Title(String text) {
            this.text = text;
        }
    }

    static final class Playlist {
        final Video video;

        Playlist(Video video) {
            this.video = video;
        }
    }

    static final class Chart {
        final Video video;

        Chart(Video video) {
            this.video = video;
        }
    }

    private final Hero mHero = new Hero();
    private final Header mHeader = new Header();
    /** The same item objects across rebuilds (more pages loading only appends to the list) */
    private final Map<Object, Object> mItems = new IdentityHashMap<>();

    // region Items

    List<Object> build(List<FeedAdapter.Group> groups) {
        List<Object> items = new ArrayList<>();

        // The hero: the first shelf of music videos
        FeedAdapter.Group heroGroup = null;
        for (FeedAdapter.Group group : groups) {
            if (countVideos(group) >= 3) {
                heroGroup = group;
                break;
            }
        }
        mHero.videos.clear();
        if (heroGroup != null) {
            for (Video video : heroGroup.videos) {
                if (video != null && video.hasVideo()) {
                    mHero.videos.add(video);
                    if (mHero.videos.size() == HERO_SIZE) {
                        break;
                    }
                }
            }
            items.add(mHero);
        }

        mHeader.pool.clear();
        for (FeedAdapter.Group group : groups) {
            for (Video video : group.videos) {
                if (video != null && video.hasVideo()) {
                    mHeader.pool.add(video);
                }
            }
        }
        if (!groups.isEmpty()) {
            items.add(mHeader);
        }

        for (FeedAdapter.Group group : groups) {
            if (group == heroGroup || group.videos.isEmpty()) {
                continue;
            }
            int playlists = 0;
            for (Video video : group.videos) {
                if (isPlaylist(video)) {
                    playlists++;
                }
            }
            if (playlists * 2 < group.videos.size()) {
                items.add(group.shelf); // songs and videos: the usual horizontal shelf, with its title
                continue;
            }
            String title = group.ref != null ? group.ref.getTitle() : null;
            if (!TextUtils.isEmpty(title)) {
                items.add(item(group, () -> new Title(title)));
            }
            boolean charts = title != null && title.toLowerCase().contains("chart");
            int count = Math.min(ROWS_PER_SHELF, group.videos.size());
            for (int i = 0; i < count; i++) {
                Video video = group.videos.get(i);
                if (video == null) {
                    continue;
                }
                items.add(charts ? item(video, () -> new Chart(video)) : item(video, () -> new Playlist(video)));
            }
        }
        return items;
    }

    private interface Maker {
        Object make();
    }

    private Object item(Object key, Maker maker) {
        Object item = mItems.get(key);
        if (item == null) {
            item = maker.make();
            mItems.put(key, item);
        }
        return item;
    }

    void clear() {
        mItems.clear();
    }

    private static int countVideos(FeedAdapter.Group group) {
        int count = 0;
        for (Video video : group.videos) {
            if (video != null && video.hasVideo() && !video.isShorts) {
                count++;
            }
        }
        return count;
    }

    static boolean isPlaylist(Video video) {
        return video != null && !video.hasVideo() && !TextUtils.isEmpty(video.playlistId);
    }

    /** The view type of a Music item, or 0 */
    static int viewType(Object item) {
        if (item instanceof Hero) {
            return VT_HERO;
        } else if (item instanceof Header) {
            return VT_HEADER;
        } else if (item instanceof Title) {
            return VT_TITLE;
        } else if (item instanceof Playlist) {
            return VT_PLAYLIST;
        } else if (item instanceof Chart) {
            return VT_CHART;
        }
        return 0;
    }

    static boolean isMusicType(int viewType) {
        return viewType >= VT_HERO && viewType <= VT_CHART;
    }

    // endregion

    // region Views

    static View create(ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        View view;
        switch (viewType) {
            case VT_HERO:
                view = createHero(context);
                break;
            case VT_HEADER:
                view = createHeader(context);
                break;
            case VT_TITLE:
                view = createTitle(context);
                break;
            case VT_CHART:
                view = createChart(context);
                break;
            case VT_PLAYLIST:
            default:
                view = createPlaylist(context);
                break;
        }
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    static void bind(View view, Object item, FeedAdapter.Listener listener) {
        if (item instanceof Hero) {
            bindHero(view, (Hero) item, listener);
        } else if (item instanceof Header) {
            bindHeader(view, (Header) item, listener);
        } else if (item instanceof Title) {
            ((TextView) view).setText(((Title) item).text);
        } else if (item instanceof Playlist) {
            bindPlaylist(view, ((Playlist) item).video, listener);
        } else if (item instanceof Chart) {
            bindChart(view, ((Chart) item).video, listener);
        }
    }

    // Hero: big featured music videos, swiped sideways, moving on by themselves

    private static View createHero(Context context) {
        RatioFrameLayout root = new RatioFrameLayout(context);
        root.setHeightToWidth(1f);
        RecyclerView pager = new RecyclerView(context);
        pager.setId(R.id.phone_music_pager);
        pager.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        new PagerSnapHelper().attachToRecyclerView(pager);
        root.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout dots = new LinearLayout(context);
        dots.setId(R.id.phone_music_dots);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams dotsParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        dotsParams.setMargins(0, 0, dp(context, 20), dp(context, 18));
        root.addView(dots, dotsParams);
        return root;
    }

    private static void bindHero(View view, Hero hero, FeedAdapter.Listener listener) {
        RecyclerView pager = view.findViewById(R.id.phone_music_pager);
        LinearLayout dots = view.findViewById(R.id.phone_music_dots);
        Context context = view.getContext();
        List<Video> videos = new ArrayList<>(hero.videos);
        pager.setAdapter(new HeroAdapter(videos, listener));
        pager.clearOnScrollListeners();

        dots.removeAllViews();
        for (int i = 0; i < videos.size(); i++) {
            View dot = new View(context);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.WHITE);
            dot.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 7), dp(context, 7));
            params.setMarginStart(dp(context, 5));
            dots.addView(dot, params);
        }
        int[] page = {0};
        Runnable showPage = () -> {
            for (int i = 0; i < dots.getChildCount(); i++) {
                dots.getChildAt(i).setAlpha(i == page[0] ? 1f : 0.4f);
            }
        };
        showPage.run();
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    int position = manager != null ? manager.findFirstCompletelyVisibleItemPosition() : -1;
                    if (position >= 0) {
                        page[0] = position;
                        showPage.run();
                    }
                }
            }
        });

        // Moves on by itself every few seconds, not while a finger is on it
        boolean[] touching = {false};
        pager.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            touching[0] = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
            return false;
        });
        Object old = view.getTag(R.id.phone_music_pager);
        if (old instanceof Runnable) {
            view.removeCallbacks((Runnable) old);
        }
        Runnable auto = new Runnable() {
            @Override
            public void run() {
                if (videos.size() > 1 && view.isAttachedToWindow() && !touching[0]
                        && pager.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                    pager.smoothScrollToPosition((page[0] + 1) % videos.size());
                }
                view.postDelayed(this, HERO_AUTO_MS);
            }
        };
        view.setTag(R.id.phone_music_pager, auto);
        view.postDelayed(auto, HERO_AUTO_MS);
    }

    private static final class HeroAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Video> mVideos;
        private final FeedAdapter.Listener mListener;

        HeroAdapter(List<Video> videos, FeedAdapter.Listener listener) {
            mVideos = videos;
            mListener = listener;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            FrameLayout page = new FrameLayout(context);
            page.setLayoutParams(new RecyclerView.LayoutParams(parent.getWidth() > 0 ? parent.getWidth()
                    : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView image = new ImageView(context);
            image.setId(R.id.phone_thumb);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(context.getResources().getColor(R.color.phone_surface_2));
            page.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(context);
            scrim.setBackgroundResource(R.drawable.phone_scrim_bottom);
            page.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 260), Gravity.BOTTOM));

            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(context, 20), 0, dp(context, 20), dp(context, 44));
            TextView meta = new TextView(context);
            meta.setId(R.id.phone_meta);
            meta.setTextColor(0xE6FFFFFF);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            meta.setMaxLines(2);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setTypeface(PhoneUi.regular(context));
            text.addView(meta);
            TextView title = new TextView(context);
            title.setId(R.id.phone_title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(PhoneUi.medium(context));
            title.setPadding(0, dp(context, 6), 0, 0);
            title.setShadowLayer(dp(context, 6), 0, 0, 0x66000000);
            text.addView(title);
            page.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            return new RecyclerView.ViewHolder(page) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Video video = mVideos.get(position);
            View page = holder.itemView;
            ((TextView) page.findViewById(R.id.phone_title)).setText(video.getTitle());
            ((TextView) page.findViewById(R.id.phone_meta)).setText(PhoneUi.meta(video));
            ImageView image = page.findViewById(R.id.phone_thumb);
            if (PhoneUi.isAlive(image.getContext())) {
                String fallback = video.getCardImageUrl();
                Glide.with(image.getContext())
                        .load("https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg")
                        .centerCrop()
                        .error(Glide.with(image.getContext()).load("https://i.ytimg.com/vi/" + video.videoId + "/hq720.jpg")
                                .centerCrop()
                                .error(Glide.with(image.getContext()).load(fallback).centerCrop()))
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .into(image);
            }
            page.setOnClickListener(v -> {
                PhoneUi.rememberClick(page);
                mListener.onVideoClicked(video);
            });
            page.setOnLongClickListener(v -> {
                mListener.onVideoLongClicked(video);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mVideos.size();
        }
    }

    // Header: the Music mark, with Play and Shuffle

    private static View createHeader(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 16), dp(context, 20), dp(context, 16), dp(context, 4));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout mark = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFFFF5C7A, 0xFFFF0033, 0xFFB0005A});
        circle.setShape(GradientDrawable.OVAL);
        mark.setBackground(circle);
        mark.setElevation(dp(context, 4));
        ImageView note = new ImageView(context);
        note.setImageResource(R.drawable.ic_phone_music_note);
        mark.addView(note, new FrameLayout.LayoutParams(dp(context, 36), dp(context, 36), Gravity.CENTER));
        row.addView(mark, new LinearLayout.LayoutParams(dp(context, 72), dp(context, 72)));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(context, 18), 0, 0, 0);
        TextView name = new TextView(context);
        name.setText(R.string.phone_music_title);
        name.setTextColor(context.getResources().getColor(R.color.phone_text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        name.setTypeface(PhoneUi.medium(context));
        words.addView(name);
        TextView sub = new TextView(context);
        sub.setText(R.string.phone_music_subtitle);
        sub.setTextColor(context.getResources().getColor(R.color.phone_text_2));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setTypeface(PhoneUi.regular(context));
        words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(context, 18), 0, 0);
        TextView play = pill(context, R.string.phone_music_play, R.drawable.ic_phone_play, true);
        play.setId(R.id.phone_music_play);
        TextView shuffle = pill(context, R.string.phone_music_shuffle, R.drawable.ic_phone_mode_shuffle, false);
        shuffle.setId(R.id.phone_music_shuffle);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(context, 44), 1f);
        first.setMarginEnd(dp(context, 6));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(context, 44), 1f);
        second.setMarginStart(dp(context, 6));
        buttons.addView(play, first);
        buttons.addView(shuffle, second);
        root.addView(buttons);
        return root;
    }

    private static TextView pill(Context context, int label, int icon, boolean filled) {
        TextView pill = new TextView(context);
        pill.setText(label);
        pill.setGravity(Gravity.CENTER);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        pill.setTypeface(PhoneUi.medium(context));
        int text = context.getResources().getColor(filled ? R.color.phone_bg : R.color.phone_text);
        pill.setTextColor(text);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 22));
        if (filled) {
            shape.setColor(context.getResources().getColor(R.color.phone_text));
        } else {
            shape.setColor(Color.TRANSPARENT);
            shape.setStroke(dp(context, 1), context.getResources().getColor(R.color.phone_surface_2) | 0xFF000000);
            shape.setColor(context.getResources().getColor(R.color.phone_surface));
        }
        pill.setBackground(shape);
        android.graphics.drawable.Drawable drawable = context.getResources().getDrawable(icon).mutate();
        drawable.setTint(text);
        drawable.setBounds(0, 0, dp(context, 22), dp(context, 22));
        pill.setCompoundDrawablesRelative(drawable, null, null, null);
        pill.setCompoundDrawablePadding(dp(context, 6));
        pill.setPadding(dp(context, 18), 0, dp(context, 22), 0);
        return pill;
    }

    private static void bindHeader(View view, Header header, FeedAdapter.Listener listener) {
        List<Video> pool = new ArrayList<>(header.pool);
        View play = view.findViewById(R.id.phone_music_play);
        View shuffle = view.findViewById(R.id.phone_music_shuffle);
        play.setEnabled(!pool.isEmpty());
        shuffle.setEnabled(!pool.isEmpty());
        play.setOnClickListener(v -> {
            if (!pool.isEmpty()) {
                listener.onVideoClicked(pool.get(0));
            }
        });
        shuffle.setOnClickListener(v -> {
            if (!pool.isEmpty()) {
                listener.onVideoClicked(pool.get(new Random().nextInt(pool.size())));
            }
        });
    }

    // Shelf title

    private static View createTitle(Context context) {
        TextView title = new TextView(context);
        title.setTextColor(context.getResources().getColor(R.color.phone_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(PhoneUi.medium(context));
        title.setPadding(dp(context, 16), dp(context, 28), dp(context, 16), dp(context, 10));
        return title;
    }

    // Playlist row: square art with a play mark, title, description and the number of tracks

    private static View createPlaylist(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        row.setBackgroundResource(selectable(context));

        FrameLayout art = new FrameLayout(context);
        rounded(art, dp(context, 14));
        ImageView image = new ImageView(context);
        image.setId(R.id.phone_thumb);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(context.getResources().getColor(R.color.phone_surface_2));
        art.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout mark = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xB3000000);
        circle.setStroke(dp(context, 1), 0x66FFFFFF);
        mark.setBackground(circle);
        ImageView play = new ImageView(context);
        play.setImageResource(R.drawable.ic_phone_play);
        mark.addView(play, new FrameLayout.LayoutParams(dp(context, 14), dp(context, 14), Gravity.CENTER));
        FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(dp(context, 24), dp(context, 24));
        markParams.setMargins(dp(context, 8), dp(context, 8), 0, 0);
        art.addView(mark, markParams);
        row.addView(art, new LinearLayout.LayoutParams(dp(context, 136), dp(context, 136)));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(context, 18), 0, 0, 0);
        TextView title = new TextView(context);
        title.setId(R.id.phone_title);
        title.setTextColor(context.getResources().getColor(R.color.phone_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(PhoneUi.regular(context));
        words.addView(title);
        TextView description = new TextView(context);
        description.setId(R.id.phone_meta);
        description.setTextColor(context.getResources().getColor(R.color.phone_text_2));
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setPadding(0, dp(context, 4), 0, 0);
        description.setTypeface(PhoneUi.regular(context));
        words.addView(description);
        TextView count = new TextView(context);
        count.setId(R.id.phone_badge);
        count.setTextColor(context.getResources().getColor(R.color.phone_text_2));
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        count.setPadding(0, dp(context, 4), 0, 0);
        count.setTypeface(PhoneUi.regular(context));
        words.addView(count);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static void bindPlaylist(View view, Video video, FeedAdapter.Listener listener) {
        Context context = view.getContext();
        ((TextView) view.findViewById(R.id.phone_title)).setText(video.getTitle());
        TextView description = view.findViewById(R.id.phone_meta);
        CharSequence text = !TextUtils.isEmpty(video.description) ? video.description : PhoneUi.meta(video);
        description.setText(text);
        description.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        TextView count = view.findViewById(R.id.phone_badge);
        String tracks = tracks(context, video.badge);
        count.setText(tracks);
        count.setVisibility(TextUtils.isEmpty(tracks) || (text != null && text.toString().contains(tracks)) ? View.GONE : View.VISIBLE);
        loadArt(view.findViewById(R.id.phone_thumb), video);
        clicks(view, video, listener);
    }

    // Chart: a big card with the number of videos on the picture

    private static View createChart(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 14));
        root.setBackgroundResource(selectable(context));

        RatioFrameLayout art = new RatioFrameLayout(context);
        art.setHeightToWidth(9f / 16f);
        rounded(art, dp(context, 16));
        ImageView image = new ImageView(context);
        image.setId(R.id.phone_thumb);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(context.getResources().getColor(R.color.phone_surface_2));
        art.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView badge = new TextView(context);
        badge.setId(R.id.phone_badge);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badge.setTypeface(PhoneUi.medium(context));
        badge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(context, 8));
        pill.setColor(0xB3000000);
        badge.setBackground(pill);
        badge.setPadding(dp(context, 8), dp(context, 4), dp(context, 10), dp(context, 4));
        android.graphics.drawable.Drawable icon = context.getResources().getDrawable(R.drawable.ic_phone_playlist).mutate();
        icon.setBounds(0, 0, dp(context, 18), dp(context, 18));
        badge.setCompoundDrawablesRelative(icon, null, null, null);
        badge.setCompoundDrawablePadding(dp(context, 6));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        badgeParams.setMargins(0, 0, dp(context, 10), dp(context, 10));
        art.addView(badge, badgeParams);
        root.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout below = new LinearLayout(context);
        below.setOrientation(LinearLayout.HORIZONTAL);
        below.setPadding(0, dp(context, 12), 0, 0);
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setId(R.id.phone_title);
        title.setTextColor(context.getResources().getColor(R.color.phone_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(PhoneUi.medium(context));
        words.addView(title);
        TextView meta = new TextView(context);
        meta.setId(R.id.phone_meta);
        meta.setTextColor(context.getResources().getColor(R.color.phone_text_2));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        meta.setMaxLines(2);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(context, 2), 0, 0);
        meta.setTypeface(PhoneUi.regular(context));
        words.addView(meta);
        below.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView more = new ImageView(context);
        more.setId(R.id.phone_more);
        more.setImageResource(R.drawable.ic_phone_more);
        more.setImageTintList(android.content.res.ColorStateList.valueOf(context.getResources().getColor(R.color.phone_text)));
        more.setPadding(dp(context, 6), 0, 0, 0);
        below.addView(more, new LinearLayout.LayoutParams(dp(context, 30), dp(context, 24)));
        root.addView(below);
        return root;
    }

    private static void bindChart(View view, Video video, FeedAdapter.Listener listener) {
        Context context = view.getContext();
        ((TextView) view.findViewById(R.id.phone_title)).setText(video.getTitle());
        String author = video.getAuthor();
        String playlist = context.getString(R.string.phone_music_playlist);
        ((TextView) view.findViewById(R.id.phone_meta)).setText(TextUtils.isEmpty(author) ? playlist : author + " · " + playlist);
        TextView badge = view.findViewById(R.id.phone_badge);
        badge.setText(video.badge);
        badge.setVisibility(TextUtils.isEmpty(video.badge) ? View.GONE : View.VISIBLE);
        loadArt(view.findViewById(R.id.phone_thumb), video);
        clicks(view, video, listener);
        view.findViewById(R.id.phone_more).setOnClickListener(v -> listener.onVideoLongClicked(video));
    }

    // Helpers

    /** "50 videos" → "50 tracks" (a playlist of songs) */
    private static String tracks(Context context, String badge) {
        if (TextUtils.isEmpty(badge)) {
            return null;
        }
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("^([\\d.,]+\\+?)\\s+\\S+$").matcher(badge.trim());
        return number.matches() ? context.getString(R.string.phone_music_tracks, number.group(1)) : badge;
    }

    private static void loadArt(ImageView image, Video video) {
        if (!PhoneUi.isAlive(image.getContext())) {
            return;
        }
        String url = video.getCardImageUrl();
        if (TextUtils.isEmpty(url) && !TextUtils.isEmpty(video.videoId)) {
            url = "https://i.ytimg.com/vi/" + video.videoId + "/hq720.jpg";
        }
        Glide.with(image.getContext())
                .load(url)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .into(image);
    }

    private static void clicks(View view, Video video, FeedAdapter.Listener listener) {
        view.setOnClickListener(v -> {
            PhoneUi.rememberClick(view);
            listener.onVideoClicked(video);
        });
        view.setOnLongClickListener(v -> {
            listener.onVideoLongClicked(video);
            return true;
        });
    }

    private static void rounded(View view, int radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private static int selectable(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // endregion
}
