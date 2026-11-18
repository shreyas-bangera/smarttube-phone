package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.phone.widget.RatioFrameLayout;

import java.util.List;

/**
 * Horizontal row inside the feed: video cards, Shorts tiles or channel avatars.
 */
class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {
    static final int STYLE_CARD = 10;
    static final int STYLE_SHORT = 11;
    static final int STYLE_AVATAR = 12;
    /** A small video card, about two and a half to a screen, like the YouTube app's History row in You */
    static final int STYLE_COMPACT = 13;

    interface SelectionProvider {
        Video getSelected();
    }

    private final List<Video> mVideos;
    private final int mStyle;
    private final FeedAdapter.Listener mListener;
    private final SelectionProvider mSelection;

    ShelfAdapter(List<Video> videos, int style, FeedAdapter.Listener listener, SelectionProvider selection) {
        mVideos = videos;
        mStyle = style;
        mListener = listener;
        mSelection = selection;
    }

    int getStyle() {
        return mStyle;
    }

    void onDataChanged(int oldCount) {
        int newCount = mVideos.size();
        if (newCount > oldCount && oldCount > 0) {
            notifyItemRangeInserted(oldCount, newCount - oldCount);
        } else {
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        return mVideos.size();
    }

    @Override
    public int getItemViewType(int position) {
        return mStyle;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int parentWidth = parent.getWidth() > 0 ? parent.getWidth() : PhoneUi.screenWidthPx();
        View view;

        if (viewType == STYLE_AVATAR) {
            view = inflater.inflate(R.layout.phone_item_avatar, parent, false);
        } else if (viewType == STYLE_SHORT) {
            view = inflater.inflate(R.layout.phone_item_short, parent, false);
            ((RatioFrameLayout) view).setHeightToWidth(16f / 9f);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.width = (int) (parentWidth * 0.40f);
            view.setLayoutParams(params);
        } else if (viewType == STYLE_COMPACT) {
            view = inflater.inflate(R.layout.phone_item_shelf_card, parent, false);
            float dp = parent.getResources().getDisplayMetrics().density;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.width = Math.max((int) (150 * dp), (int) (parentWidth * 0.42f));
            params.setMarginEnd((int) (10 * dp));
            view.setLayoutParams(params);
            compact(view, dp);
        } else {
            view = inflater.inflate(R.layout.phone_item_shelf_card, parent, false);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = (int) (parentWidth * 0.80f);
            view.setLayoutParams(params);
        }

        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Video video = mVideos.get(position);
        View view = holder.itemView;

        if (mStyle == STYLE_AVATAR) {
            TextView title = view.findViewById(R.id.phone_title);
            ImageView avatar = view.findViewById(R.id.phone_thumb);
            title.setText(!TextUtils.isEmpty(video.getTitle()) ? video.getTitle() : video.getAuthor());
            PhoneUi.loadAvatar(avatar, video.getCardImageUrl());
            AvatarCache.remember(view.getContext(), video);
            boolean selected = video.equals(mSelection.getSelected());
            title.setTextColor(view.getResources().getColor(selected ? R.color.phone_text : R.color.phone_text_2));
            avatar.setAlpha(mSelection.getSelected() == null || selected ? 1f : 0.55f);
            View dot = view.findViewById(R.id.phone_new_dot);
            if (dot != null) {
                dot.setVisibility(video.hasNewContent ? View.VISIBLE : View.GONE); // new uploads (like the YouTube app)
            }
            view.setOnClickListener(v -> mListener.onStripClicked(video));
            view.setOnLongClickListener(v -> {
                mListener.onVideoLongClicked(video);
                return true;
            });
            return;
        }

        if (mStyle == STYLE_SHORT) {
            TextView title = view.findViewById(R.id.phone_title);
            TextView meta = view.findViewById(R.id.phone_meta);
            title.setText(video.getTitle());
            CharSequence metaText = PhoneUi.meta(video);
            meta.setText(metaText);
            meta.setVisibility(TextUtils.isEmpty(metaText) ? View.GONE : View.VISIBLE);
            PhoneUi.loadThumb(view.findViewById(R.id.phone_thumb), video, true);
        } else {
            PhoneUi.bindCard(view, video);
            View more = view.findViewById(R.id.phone_more);
            if (more != null) {
                more.setOnClickListener(v -> mListener.onVideoLongClicked(video));
            }
        }

        view.setOnClickListener(v -> {
            PhoneUi.rememberClick(view);
            mListener.onVideoClicked(video);
        });
        view.setOnLongClickListener(v -> {
            mListener.onVideoLongClicked(video);
            return true;
        });
    }

    /** Smaller words under the picture: the title in two lines, one line under it, a small menu button */
    private static void compact(View card, float dp) {
        TextView title = card.findViewById(R.id.phone_title);
        TextView meta = card.findViewById(R.id.phone_meta);
        View more = card.findViewById(R.id.phone_more);
        if (title != null) {
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            ((View) title.getParent().getParent()).setPadding((int) (2 * dp), (int) (8 * dp), 0, 0);
        }
        if (meta != null) {
            meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            meta.setMaxLines(1);
            ((ViewGroup.MarginLayoutParams) meta.getLayoutParams()).topMargin = (int) (2 * dp);
        }
        if (more != null) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) more.getLayoutParams();
            params.width = (int) (30 * dp);
            params.height = (int) (30 * dp);
            params.setMarginEnd(0);
            params.topMargin = (int) (-6 * dp);
            more.setPadding((int) (7 * dp), (int) (7 * dp), (int) (7 * dp), (int) (7 * dp));
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View view) {
            super(view);
        }
    }
}
