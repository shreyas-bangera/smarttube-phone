package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Collections;
import java.util.List;

public class PhoneMomentsActivity extends PhoneActivity {
    private final MomentsAdapter adapter = new MomentsAdapter();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.phone_activity_downloads);
        ((TextView) findViewById(R.id.phone_screen_title)).setText(R.string.phone_moments);
        ((TextView) findViewById(R.id.phone_downloads_empty)).setText(R.string.phone_moments_empty);
        findViewById(R.id.phone_back).setOnClickListener(v -> finish());
        RecyclerView list = findViewById(R.id.phone_downloads_list);
        applyInsets(findViewById(R.id.phone_root), findViewById(R.id.phone_top_bar), list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        adapter.rows = PhoneMoments.get(this).list();
        adapter.notifyDataSetChanged();
        findViewById(R.id.phone_downloads_empty).setVisibility(adapter.rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void play(PhoneMoments.Moment moment) {
        Video video = Video.from(moment.videoId);
        video.title = moment.title;
        // The player uses zero to mean "restore history".
        video.pendingPosMs = Math.max(1, moment.position);
        PhoneMusic.onCardClick();
        PhoneUi.prepareExpandLaunch(video);
        PlaybackPresenter.instance(this).openVideo(video);
    }

    private void options(View anchor, PhoneMoments.Moment moment) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(moment.note.isEmpty() ? R.string.phone_moment_add_note : R.string.phone_moment_edit_note)
                .setOnMenuItemClickListener(item -> {
                    PhoneMoments.editNote(this, moment, this::refresh);
                    return true;
                });
        menu.getMenu().add(R.string.phone_moment_remove).setOnMenuItemClickListener(item -> {
            PhoneMoments.get(this).remove(moment);
            refresh();
            return true;
        });
        menu.show();
    }

    private final class MomentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<PhoneMoments.Moment> rows = Collections.emptyList();

        private boolean startsVideo(int position) {
            return position == 0 || !rows.get(position - 1).videoId.equals(rows.get(position).videoId);
        }

        @Override
        public int getItemViewType(int position) {
            return startsVideo(position) ? R.layout.phone_item_row : R.layout.phone_moment_row;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int layout) {
            View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            View row = holder.itemView;
            PhoneMoments.Moment moment = rows.get(position);
            String time = moment.time();
            String playLabel = getString(R.string.phone_moment_play, time);
            row.setOnClickListener(v -> play(moment));
            boolean first = holder.getItemViewType() == R.layout.phone_item_row;
            TextView timestamp;
            TextView note;
            if (first) {
                ((TextView) row.findViewById(R.id.phone_title)).setText(moment.title);
                ImageView thumb = row.findViewById(R.id.phone_thumb);
                PhoneUi.loadThumb(thumb, Video.from(moment.videoId), false);
                row.findViewById(R.id.phone_progress).setVisibility(View.GONE);
                timestamp = row.findViewById(R.id.phone_badge);
                android.graphics.drawable.Drawable playIcon = getDrawable(R.drawable.ic_phone_play).mutate();
                int iconSize = Math.round(12 * getResources().getDisplayMetrics().density);
                playIcon.setBounds(0, 0, iconSize, iconSize);
                playIcon.setTint(getColor(R.color.phone_accent));
                timestamp.setCompoundDrawablesRelative(playIcon, null, null, null);
                timestamp.setCompoundDrawablePadding(Math.round(3 * getResources().getDisplayMetrics().density));
                note = row.findViewById(R.id.phone_meta);
            } else {
                timestamp = row.findViewById(R.id.phone_moment_time);
                note = row.findViewById(R.id.phone_moment_note);
            }
            timestamp.setText(time);
            note.setText(moment.note);
            note.setVisibility(TextUtils.isEmpty(moment.note) ? View.GONE : View.VISIBLE);
            String description = moment.title + ", " + playLabel;
            row.setContentDescription(TextUtils.isEmpty(moment.note) ? description : description + ", " + moment.note);
            View more = row.findViewById(first ? R.id.phone_more : R.id.phone_moment_options);
            more.setContentDescription(getString(R.string.phone_moment_menu, time));
            more.setOnClickListener(v -> options(v, moment));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    @Override
    protected boolean hasPendingActions() {
        return false;
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
    }
}
