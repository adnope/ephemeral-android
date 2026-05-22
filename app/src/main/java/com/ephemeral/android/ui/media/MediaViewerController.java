package com.ephemeral.android.ui.media;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.util.ByteFormatter;
import com.ephemeral.android.util.DateFormatter;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

public final class MediaViewerController implements ItemEventConsumer {
    private final View view;
    private final ScreenHost host;
    private final EphemeralApi api;
    private final List<Item> mediaItems;
    private final RecyclerView mediaPager;
    private final LinearLayoutManager layoutManager;
    private final PagerSnapHelper snapHelper;
    private final MediaViewerAdapter adapter;
    private final TextView title;
    private final TextView metadata;
    private final ImageButton previous;
    private final ImageButton next;
    private int index;

    public MediaViewerController(LayoutInflater inflater, ScreenHost host, EphemeralApi api, ImageLoader imageLoader,
            OkHttpClient httpClient, List<Item> mediaItems, int startIndex) {
        this.host = host;
        this.api = api;
        this.mediaItems = new ArrayList<>(mediaItems);
        index = clampIndex(startIndex);
        view = inflater.inflate(R.layout.screen_media_viewer, null, false);
        mediaPager = view.findViewById(R.id.list_media_pages);
        title = view.findViewById(R.id.text_media_title);
        metadata = view.findViewById(R.id.text_media_metadata);
        previous = view.findViewById(R.id.button_previous);
        next = view.findViewById(R.id.button_next);

        adapter = new MediaViewerAdapter(imageLoader, httpClient);
        layoutManager = new LinearLayoutManager(view.getContext(), RecyclerView.HORIZONTAL, false);
        snapHelper = new PagerSnapHelper();
        mediaPager.setLayoutManager(layoutManager);
        mediaPager.setAdapter(adapter);
        mediaPager.setHasFixedSize(true);
        mediaPager.setItemAnimator(null);
        snapHelper.attachToRecyclerView(mediaPager);
        mediaPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    adapter.setActivePosition(-1);
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    syncIndexFromPager();
                }
            }
        });

        previous.setOnClickListener(v -> moveTo(index - 1, true));
        next.setOnClickListener(v -> moveTo(index + 1, true));
        view.findViewById(R.id.button_close).setOnClickListener(v -> close());
        view.findViewById(R.id.button_download).setOnClickListener(v -> host.downloadItem(current()));
        view.findViewById(R.id.button_delete).setOnClickListener(v -> {
            Item target = current();
            host.confirmDelete(target, () -> removeMediaItem(target.getId()));
        });

        adapter.submit(this.mediaItems, index);
        updateChrome();
        mediaPager.scrollToPosition(index);
    }

    public View getView() {
        return view;
    }

    public boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            moveTo(index - 1, true);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            moveTo(index + 1, true);
            return true;
        }
        return false;
    }

    @Override
    public void onItemEvent(ItemEvent event) {
        if (event.getType() == ItemEventType.DELETED) {
            removeMediaItem(event.getItemId());
        } else if (event.getType() == ItemEventType.UPDATED) {
            refreshMediaItem(event.getItemId());
        }
    }

    public void release() {
        adapter.release();
    }

    private void moveTo(int nextIndex, boolean animate) {
        if (mediaItems.isEmpty()) {
            close();
            return;
        }
        int target = clampIndex(nextIndex);
        if (target == index) {
            adapter.setActivePosition(index);
            updateChrome();
            return;
        }
        adapter.setActivePosition(-1);
        index = target;
        updateChrome();
        if (animate) {
            mediaPager.smoothScrollToPosition(index);
        } else {
            mediaPager.scrollToPosition(index);
            adapter.setActivePosition(index);
        }
    }

    private void syncIndexFromPager() {
        View snapped = snapHelper.findSnapView(layoutManager);
        if (snapped == null) {
            adapter.setActivePosition(index);
            return;
        }
        int position = layoutManager.getPosition(snapped);
        if (position == RecyclerView.NO_POSITION || position >= mediaItems.size()) {
            adapter.setActivePosition(index);
            return;
        }
        index = position;
        updateChrome();
        adapter.setActivePosition(index);
    }

    private void removeMediaItem(long itemId) {
        int removedIndex = indexOfItem(itemId);
        if (removedIndex < 0) {
            return;
        }
        mediaItems.remove(removedIndex);
        if (mediaItems.isEmpty()) {
            close();
            return;
        }
        if (removedIndex < index || index >= mediaItems.size()) {
            index = Math.max(0, Math.min(index - 1, mediaItems.size() - 1));
        }
        adapter.submit(mediaItems, index);
        updateChrome();
        mediaPager.post(() -> mediaPager.scrollToPosition(index));
    }

    private void refreshMediaItem(long itemId) {
        if (indexOfItem(itemId) < 0) {
            return;
        }
        api.loadChatPage(0, new ApiCallback<Page<Item>>() {
            @Override
            public void onSuccess(Page<Item> value) {
                for (Item item : value.getItems()) {
                    if (item.getId() == itemId && item.isMedia()) {
                        replaceMediaItem(item);
                        return;
                    }
                }
            }

            @Override
            public void onError(ApiError error) {
                // Media refresh is best effort; normal list refresh still picks up the backend state.
            }
        });
    }

    private void replaceMediaItem(Item updatedItem) {
        int itemIndex = indexOfItem(updatedItem.getId());
        if (itemIndex < 0) {
            return;
        }
        mediaItems.set(itemIndex, updatedItem);
        adapter.submit(mediaItems, index);
        updateChrome();
        mediaPager.post(() -> {
            mediaPager.scrollToPosition(index);
            adapter.setActivePosition(index);
        });
    }

    private int indexOfItem(long itemId) {
        for (int i = 0; i < mediaItems.size(); i++) {
            if (mediaItems.get(i).getId() == itemId) {
                return i;
            }
        }
        return -1;
    }

    private int clampIndex(int requestedIndex) {
        if (mediaItems.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(requestedIndex, mediaItems.size() - 1));
    }

    private Item current() {
        return mediaItems.get(index);
    }

    private void close() {
        host.closeOverlay();
    }

    private void updateChrome() {
        if (mediaItems.isEmpty()) {
            return;
        }
        Item item = current();
        previous.setEnabled(index > 0);
        next.setEnabled(index < mediaItems.size() - 1);
        title.setText(item.getFilename());
        metadata.setText(metadataLine(item));
    }

    private String metadataLine(Item item) {
        ItemMetadata itemMetadata = item.getMetadata();
        StringBuilder builder = new StringBuilder();
        builder.append(ByteFormatter.format(item.getFilesizeBytes()));
        if (itemMetadata.hasDimensions()) {
            builder.append(" - ").append(itemMetadata.getWidth()).append("x").append(itemMetadata.getHeight());
        }
        if (!itemMetadata.getDuration().isEmpty()) {
            builder.append(" - ").append(itemMetadata.getDuration());
        }
        builder.append(" - ").append(DateFormatter.detail(item.getCreatedAtEpochMillis()));
        return builder.toString();
    }
}
