package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import android.graphics.drawable.Drawable;

/**
 * Shared helpers for binding videos into the phone cards.
 */
final class PhoneUi {
    private PhoneUi() {
    }

    static boolean isAlive(Context context) {
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            return !activity.isFinishing() && !activity.isDestroyed();
        }
        return context != null;
    }

    /**
     * SmartTube scales density so a 1920px wide TV layout fits any screen (MotherActivity.initDpi), which makes
     * phone UI ~20% too small and ignores the font size setting. Phone screens use the real device metrics.
     */
    static void applyPhoneDensity(android.app.Activity activity) {
        android.util.DisplayMetrics system = Resources.getSystem().getDisplayMetrics();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        metrics.density = system.density;
        metrics.densityDpi = system.densityDpi;
        metrics.scaledDensity = system.scaledDensity;
        metrics.xdpi = system.xdpi;
        metrics.ydpi = system.ydpi;
    }

    private static java.lang.ref.WeakReference<View> sLastClickedThumb;

    /**
     * Remember the thumbnail of the tapped card, so the player can expand from it.
     */
    static void rememberClick(View itemView) {
        View thumb = itemView.findViewById(R.id.phone_thumb_frame);
        if (thumb == null) {
            thumb = itemView.findViewById(R.id.phone_thumb);
        }
        sLastClickedThumb = new java.lang.ref.WeakReference<>(thumb != null ? thumb : itemView);
        PhoneMusic.onCardClick(); // a regular video, unless the Music page says otherwise right after
    }

    /**
     * The next player launch scales up from the tapped thumbnail (like the YouTube app) instead of opening a new page.
     */
    static void prepareExpandLaunch(Video video) {
        if (video != null && !TextUtils.isEmpty(video.videoId) && !video.isChannel()) {
            PhonePlaybackActivity.releaseDetachedPlayer();
        }
        if (video != null && video.isShorts && video.getGroup() != null) {
            PhoneShortsQueue.set(video.getGroup());
        }
        View view = sLastClickedThumb != null ? sLastClickedThumb.get() : null;
        sLastClickedThumb = null;
        if (view == null || video == null || TextUtils.isEmpty(video.videoId) || !view.isAttachedToWindow()) {
            return;
        }
        android.os.Bundle options = android.app.ActivityOptions
                .makeScaleUpAnimation(view, 0, 0, view.getWidth(), view.getHeight()).toBundle();
        com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager.instance(view.getContext())
                .setNextLaunchOptions(PhonePlaybackActivity.class, options);
    }

    static int screenWidthPx() {
        return Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    /**
     * Wide cards need a sharper image than the TV card url. Try the 720p thumbnail first and fall back to the original.
     */
    static void loadThumb(ImageView view, Video video, boolean vertical) {
        Context context = view.getContext();
        if (!isAlive(context)) {
            return;
        }

        String original = video != null ? video.getCardImageUrl() : null;
        if (TextUtils.isEmpty(original) && video != null && !TextUtils.isEmpty(video.videoId)) {
            original = "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg"; // e.g. opened from a link
        }
        String better = null;

        if (video != null && !TextUtils.isEmpty(video.videoId) && !video.isChannel()) {
            better = "https://i.ytimg.com/vi/" + video.videoId + (vertical ? "/oar2.jpg" : "/hq720.jpg");
        }

        RequestBuilder<Drawable> fallback = Glide.with(context).load(original).centerCrop();

        if (better != null) {
            Glide.with(context)
                    .load(better)
                    .centerCrop()
                    .error(fallback)
                    .transition(DrawableTransitionOptions.withCrossFade(150))
                    .into(view);
        } else {
            fallback.transition(DrawableTransitionOptions.withCrossFade(150)).into(view);
        }
    }

    /** Fetches a card thumbnail into the disk cache ahead of time (no decoding, no memory used) */
    static void prefetchThumb(Context context, Video video) {
        prefetchThumb(context, video, video != null && video.isShorts);
    }

    /** @param vertical the full height picture of a Short (items of a Shorts list don't always carry the flag) */
    static void prefetchThumb(Context context, Video video, boolean vertical) {
        if (video == null || TextUtils.isEmpty(video.videoId) || video.isChannel() || !isAlive(context)) {
            return;
        }
        String url = "https://i.ytimg.com/vi/" + video.videoId + (vertical ? "/oar2.jpg" : "/hq720.jpg");
        try {
            Glide.with(context).downloadOnly().load(url).submit();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    static void loadAvatar(ImageView view, String url) {
        Context context = view.getContext();
        if (!isAlive(context)) {
            return;
        }

        Glide.with(context)
                .load(url)
                .circleCrop()
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .into(view);
    }

    private static android.graphics.Typeface sRegular;
    private static android.graphics.Typeface sMedium;

    /** Roboto, as the YouTube app uses it (bundled: phones would show their own system font instead) */
    private static android.content.Context sApp;

    /** The app, for the fonts where no screen is at hand (drawables) */
    static void init(android.content.Context context) {
        sApp = context.getApplicationContext();
    }

    static android.graphics.Typeface regular(android.content.Context context) {
        if (context == null) {
            context = sApp;
        }
        if (sRegular == null && context != null) {
            sRegular = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.roboto_regular);
        }
        return sRegular;
    }

    static android.graphics.Typeface medium(android.content.Context context) {
        if (context == null) {
            context = sApp;
        }
        if (sMedium == null && context != null) {
            sMedium = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.roboto_medium);
        }
        return sMedium;
    }

    static CharSequence meta(Video video) {
        CharSequence second = video.getSecondTitle();
        if (TextUtils.isEmpty(second)) {
            return video.getAuthor();
        }
        // YouTube's small middle dot between the parts, not the big bullet the feeds come with
        return second.toString().replace("\n", " · ").replace(" • ", " · ").replace("•", "·").trim();
    }

    /**
     * Binds the shared big/shelf card (phone_thumb + phone_card_text).
     */
    static void bindCard(View root, Video video) {
        ImageView thumb = root.findViewById(R.id.phone_thumb);
        TextView badge = root.findViewById(R.id.phone_badge);
        View progress = root.findViewById(R.id.phone_progress);
        TextView title = root.findViewById(R.id.phone_title);
        TextView meta = root.findViewById(R.id.phone_meta);

        title.setText(video.getTitle());
        CharSequence metaText = meta(video);
        meta.setText(metaText);
        meta.setVisibility(TextUtils.isEmpty(metaText) ? View.GONE : View.VISIBLE);

        loadThumb(thumb, video, false);

        ImageView avatar = root.findViewById(R.id.phone_avatar);
        if (avatar != null) {
            bindAuthorAvatar(avatar, video);
        }

        if (badge != null) {
            if (video.isLive) {
                badge.setText(R.string.phone_live);
                badge.setBackgroundResource(R.drawable.phone_live_bg);
                badge.setVisibility(View.VISIBLE);
            } else if (!TextUtils.isEmpty(video.badge)) {
                badge.setText(video.badge);
                badge.setBackgroundResource(R.drawable.phone_badge_bg);
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        }

        bindProgress(root, video);
    }

    /**
     * The red bar under a thumbnail: how much of the video was watched. YouTube's own number when signed in, otherwise
     * (or when this app has a newer one) the position this app saved when the video was last played here.
     */
    static void bindProgress(View root, Video video) {
        View progress = root.findViewById(R.id.phone_progress);
        if (progress == null) {
            return;
        }
        float percent = video.isLive ? 0 : Math.max(video.percentWatched, savedPercent(root.getContext(), video));
        if (percent > 0) {
            percent = Math.max(3f, Math.min(100f, percent)); // a sliver at least, like YouTube
            setWeight(root.findViewById(R.id.phone_progress_fill), percent);
            setWeight(root.findViewById(R.id.phone_progress_rest), 100f - percent);
            progress.setVisibility(View.VISIBLE);
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    private static float savedPercent(android.content.Context context, Video video) {
        if (video.videoId == null) {
            return 0;
        }
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State state =
                com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.instance(context)
                        .getByVideoId(video.videoId);
        if (state == null || state.durationMs <= 0 || state.positionMs <= 0) {
            return 0;
        }
        return state.positionMs * 100f / state.durationMs;
    }

    /**
     * Channel picture next to the title (known pictures only, otherwise a colored initial). Tapping it opens the channel.
     */
    static void bindAuthorAvatar(ImageView view, Video video) {
        Context context = view.getContext();
        if (!isAlive(context)) {
            return;
        }

        String name = !TextUtils.isEmpty(video.getAuthor()) ? video.getAuthor() : video.getTitle();
        Drawable letter = new AvatarCache.LetterDrawable(name);
        String url = AvatarCache.find(context, video);

        final String key = AvatarFetcher.keyOf(video);
        view.setTag(R.id.phone_avatar, key);

        if (url != null) {
            Glide.with(context).load(url).circleCrop().placeholder(letter).error(letter).into(view);
        } else {
            Glide.with(context).clear(view);
            view.setImageDrawable(letter);
            // Look the channel picture up once, then show it if this view still shows the same channel
            AvatarFetcher.fetch(context, video, fetched -> {
                if (key != null && key.equals(view.getTag(R.id.phone_avatar)) && isAlive(view.getContext())) {
                    Glide.with(view.getContext()).load(fetched).circleCrop().placeholder(letter).into(view);
                }
            });
        }

        if (ChannelPresenter.canOpenChannel(video)) {
            view.setOnClickListener(v -> ChannelPresenter.instance(context).openChannel(video));
        } else {
            view.setOnClickListener(null);
            view.setClickable(false);
        }
    }

    private static void setWeight(View view, float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.weight = weight;
        view.setLayoutParams(params);
    }
}
