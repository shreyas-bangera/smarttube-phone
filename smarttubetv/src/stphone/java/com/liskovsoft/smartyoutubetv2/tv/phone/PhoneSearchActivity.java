package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone search: a search field with live suggestions, results in the same feed as the home screen.
 */
public class PhoneSearchActivity extends PhoneActivity implements SearchView, FeedAdapter.Listener {
    private static final int REQUEST_VOICE = 4242;

    private SearchPresenter mPresenter;
    private EditText mQuery;
    private View mClear;
    private RecyclerView mSuggestionsList;
    private final SuggestionsAdapter mSuggestions = new SuggestionsAdapter();
    private MediaServiceSearchTagProvider mTagsProvider;
    private String mLastSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_activity_search);

        applyInsets(findViewById(R.id.phone_root), findViewById(R.id.phone_top_bar), findViewById(R.id.phone_content));
        setupList(this);

        mQuery = findViewById(R.id.phone_query);
        mClear = findViewById(R.id.phone_clear);
        mSuggestionsList = findViewById(R.id.phone_suggestions);
        mSuggestionsList.setLayoutManager(new LinearLayoutManager(this));
        mSuggestionsList.setAdapter(mSuggestions);

        findViewById(R.id.phone_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.phone_mic).setOnClickListener(v -> startVoiceRecognition());
        // Filters sheet: picking an option searches again with it
        findViewById(R.id.phone_filter).setOnClickListener(v -> mPresenter.onSearchSettingsClicked());
        setupMusicSwitch();
        mClear.setOnClickListener(v -> {
            mQuery.setText("");
            showSuggestions(true);
            showKeyboard();
        });

        mQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (mQuery.hasFocus()) {
                    showSuggestions(true);
                    loadSuggestions(s.toString());
                }
            }
        });
        mQuery.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showSuggestions(true);
                loadSuggestions(mQuery.getText().toString());
            }
        });
        mQuery.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(mQuery.getText().toString());
                return true;
            }
            return false;
        });

        mPresenter = SearchPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }

    @Override
    public void onBackPressed() {
        if (mSuggestionsList.getVisibility() == View.VISIBLE && !mAdapter.isEmpty()) {
            showSuggestions(false);
            hideKeyboard();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void finishReally() {
        super.finishReally();
        mPresenter.onFinish();
    }

    // region Music search

    private ImageView mMusicSwitch;

    /**
     * Next to the filters: the music switch. On (lit up in red), songs and playlists from the results open in the
     * music player, and the box asks for songs, artists or playlists. It stays as it was left.
     */
    private void setupMusicSwitch() {
        View filter = findViewById(R.id.phone_filter);
        ViewGroup row = (ViewGroup) filter.getParent();
        mMusicSwitch = new ImageView(this);
        mMusicSwitch.setImageResource(R.drawable.ic_phone_music_note);
        int pad = Math.round(10 * getResources().getDisplayMetrics().density);
        mMusicSwitch.setPadding(pad, pad, pad, pad);
        mMusicSwitch.setContentDescription(getString(R.string.phone_music_search_toggle));
        mMusicSwitch.setOnClickListener(v -> {
            boolean on = !PhoneMusic.isMusicSearch(this);
            PhoneMusic.setMusicSearch(this, on);
            showMusicSwitch();
            android.widget.Toast.makeText(this, on ? R.string.phone_music_search_on : R.string.phone_music_search_off,
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        int size = Math.round(44 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(size, size);
        params.setMarginStart(Math.round(4 * getResources().getDisplayMetrics().density));
        row.addView(mMusicSwitch, row.indexOfChild(filter), params);
        showMusicSwitch();
    }

    private void showMusicSwitch() {
        boolean on = PhoneMusic.isMusicSearch(this);
        if (on) {
            android.graphics.drawable.GradientDrawable lit = new android.graphics.drawable.GradientDrawable();
            lit.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            lit.setColor(getResources().getColor(R.color.phone_accent));
            mMusicSwitch.setBackground(lit);
            mMusicSwitch.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        } else {
            mMusicSwitch.setBackgroundResource(R.drawable.phone_circle_bg);
            mMusicSwitch.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.phone_text)));
        }
        mQuery.setHint(on ? R.string.phone_music_search_hint : R.string.phone_search_hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mMusicSwitch != null) {
            showMusicSwitch(); // (turned on from the Music page meanwhile)
        }
    }

    // endregion

    // region Search flow

    private void submit(String text) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            return;
        }
        text = text.trim();
        mLastSearch = text;
        if (!text.equals(mQuery.getText().toString())) {
            mQuery.setText(text);
            mQuery.setSelection(text.length());
        }
        mQuery.clearFocus();
        hideKeyboard();
        showSuggestions(false);
        hideMessage();
        mPresenter.onSearch(text);
    }

    private void loadSuggestions(String query) {
        if (mTagsProvider == null) {
            return;
        }
        mTagsProvider.search(query, results -> runOnUiThread(() -> mSuggestions.set(results)));
    }

    private void showSuggestions(boolean show) {
        mSuggestionsList.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showKeyboard() {
        mQuery.requestFocus();
        mQuery.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(mQuery, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mQuery.getWindowToken(), 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                submit(results.get(0));
            }
        }
    }

    // endregion

    // region SearchView

    @Override
    public void updateSearch(VideoGroup group) {
        hideMessage();
        mAdapter.update(group);
        checkScrollEndLater();
    }

    @Override
    public void clearSearch() {
        mAdapter.clear();
    }

    @Override
    public void clearSearchTags() {
        mSuggestions.set(null);
    }

    @Override
    public void removeSearchTag(Tag tag) {
        mSuggestions.remove(tag);
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        mTagsProvider = provider;
    }

    @Override
    public void showProgressBar(boolean show) {
        showProgress(show);
    }

    @Override
    public void startSearch(String searchText) {
        if (!TextUtils.isEmpty(searchText)) {
            submit(searchText);
        } else if (mAdapter.isEmpty()) {
            showSuggestions(true);
            showKeyboard();
            loadSuggestions(mQuery.getText().toString());
        }
    }

    @Override
    public String getSearchText() {
        return mQuery != null ? mQuery.getText().toString() : mLastSearch;
    }

    @Override
    public void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.phone_search_hint));
        try {
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (ActivityNotFoundException e) {
            MessageHelpers.showMessage(this, e.getLocalizedMessage());
            showKeyboard();
        }
    }

    // finishReally() from SearchView is implemented above

    // endregion

    // region FeedAdapter.Listener

    @Override
    public void onVideoClicked(Video video) {
        if (PhoneMusic.isMusicSearch(this)) {
            // Music search: songs play in the music player, playlists open as music playlists
            PhoneMusic.markMusic(PhoneMusicFeed.isPlaylist(video) ? video.getTitle() : getString(R.string.phone_music_search));
        }
        PhoneUi.prepareExpandLaunch(video);
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemClicked(video);
    }

    @Override
    public void onVideoLongClicked(Video video) {
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemLongClicked(video);
    }

    @Override
    public void onStripClicked(Video channel) {
        onVideoClicked(channel);
    }

    @Override
    public void onShelfScrollEnd(Video lastVideo) {
        if (!mPresenter.hasPendingActions()) {
            mPresenter.onScrollEnd(lastVideo);
        }
    }

    @Override
    protected boolean hasPendingActions() {
        return mPresenter.hasPendingActions();
    }

    @Override
    protected void onListScrollEnd(Video lastVideo) {
        mPresenter.onScrollEnd(lastVideo);
    }

    // endregion

    private final class SuggestionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Tag> mTags = new ArrayList<>();

        void set(List<Tag> tags) {
            mTags.clear();
            if (tags != null) {
                mTags.addAll(tags);
            }
            notifyDataSetChanged();
        }

        void remove(Tag tag) {
            int index = mTags.indexOf(tag);
            if (index != -1) {
                mTags.remove(index);
                notifyItemRemoved(index);
            }
        }

        @Override
        public int getItemCount() {
            return mTags.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.phone_item_suggestion, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Tag tag = mTags.get(position);
            TextView title = holder.itemView.findViewById(R.id.phone_title);
            title.setText(tag.tag);
            holder.itemView.setOnClickListener(v -> submit(tag.tag));
            holder.itemView.setOnLongClickListener(v -> {
                mPresenter.onTagLongClicked(tag);
                return true;
            });
            holder.itemView.findViewById(R.id.phone_more).setOnClickListener(v -> {
                mQuery.setText(tag.tag + " ");
                mQuery.setSelection(mQuery.getText().length());
                showKeyboard();
            });
        }
    }
}
