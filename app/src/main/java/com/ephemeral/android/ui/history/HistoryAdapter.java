package com.ephemeral.android.ui.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.util.ByteFormatter;
import com.ephemeral.android.util.DateFormatter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Callback {
        void openMedia(Item item);

        void openPreview(Item item);

        void unsupportedPreview(Item item);

        void download(Item item);

        void select(Item item);
    }

    static final int TYPE_IMAGE = 1;
    static final int TYPE_VIDEO = 2;
    static final int TYPE_FILE = 3;

    private final ImageLoader imageLoader;
    private final Callback callback;
    private final List<Item> items = new ArrayList<>();
    private final Set<Long> selectedItemIds = new HashSet<>();

    HistoryAdapter(ImageLoader imageLoader, Callback callback) {
        this.imageLoader = imageLoader;
        this.callback = callback;
        setHasStableIds(true);
    }

    void submit(List<Item> nextItems) {
        items.clear();
        items.addAll(nextItems);
        notifyDataSetChanged();
    }

    void setSelectedItemIds(Set<Long> nextSelectedItemIds) {
        selectedItemIds.clear();
        selectedItemIds.addAll(nextSelectedItemIds);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).getId();
    }

    @Override
    public int getItemViewType(int position) {
        Item item = items.get(position);
        if (item.getType() == ItemType.IMAGE) {
            return TYPE_IMAGE;
        }
        if (item.getType() == ItemType.VIDEO) {
            return TYPE_VIDEO;
        }
        return TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_IMAGE) {
            return new MediaHolder(inflater.inflate(R.layout.row_history_image, parent, false));
        }
        if (viewType == TYPE_VIDEO) {
            return new MediaHolder(inflater.inflate(R.layout.row_history_video, parent, false));
        }
        return new FileHolder(inflater.inflate(R.layout.row_history_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof MediaHolder) {
            bindMedia((MediaHolder) holder, item);
        } else if (holder instanceof FileHolder) {
            bindFile((FileHolder) holder, item);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof MediaHolder) {
            imageLoader.cancel(((MediaHolder) holder).thumbnail);
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private void bindMedia(MediaHolder holder, Item item) {
        bindSelection(holder.itemView, item);
        holder.filename.setText(item.getFilename());
        holder.metadata.setText(metadataLine(item));
        int placeholder = item.getType() == ItemType.IMAGE
                ? R.drawable.ic_image_placeholder : R.drawable.ic_video_placeholder;
        boolean animatedGif = isAnimatedGif(item);
        imageLoader.loadContentRef(holder.thumbnail, animatedGif ? item.getContentRef() : preferredImageRef(item),
                targetWidth(holder.thumbnail), targetHeight(holder.thumbnail), placeholder, animatedGif);
        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode()) {
                callback.select(item);
            } else {
                callback.openMedia(item);
            }
        });
    }

    private void bindFile(FileHolder holder, Item item) {
        bindSelection(holder.itemView, item);
        String title = item.getType() == ItemType.TEXT ? "Text message" : item.getFilename();
        holder.filename.setText(title);
        holder.metadata.setText(metadataLine(item) + " - " + DateFormatter.chat(item.getCreatedAtEpochMillis()));
        boolean selectionMode = isSelectionMode();
        boolean previewable = item.getType() == ItemType.TEXT || item.isPreviewable();
        holder.view.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.download.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.view.setEnabled(true);
        holder.view.setOnClickListener(v -> {
            if (previewable) {
                callback.openPreview(item);
            } else {
                callback.unsupportedPreview(item);
            }
        });
        holder.download.setEnabled(item.getType() != ItemType.TEXT);
        holder.download.setOnClickListener(v -> callback.download(item));
        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode()) {
                callback.select(item);
            }
        });
    }

    private void bindSelection(View itemView, Item item) {
        boolean selected = selectedItemIds.contains(item.getId());
        itemView.setSelected(selected);
        itemView.setForeground(selected ? itemView.getContext().getDrawable(R.drawable.bg_multi_select_foreground) : null);
        itemView.setOnLongClickListener(v -> {
            callback.select(item);
            return true;
        });
    }

    private boolean isSelectionMode() {
        return !selectedItemIds.isEmpty();
    }

    private String metadataLine(Item item) {
        if (item.getType() == ItemType.TEXT) {
            return "Text";
        }
        ItemMetadata metadata = item.getMetadata();
        StringBuilder builder = new StringBuilder(ByteFormatter.format(item.getFilesizeBytes()));
        if (metadata.hasDimensions()) {
            builder.append(" - ").append(metadata.getWidth()).append("x").append(metadata.getHeight());
        }
        if (!metadata.getDuration().isEmpty()) {
            builder.append(" - ").append(metadata.getDuration());
        }
        return builder.toString();
    }

    private String preferredImageRef(Item item) {
        String thumb = item.getMetadata().getThumbRef();
        return thumb.isEmpty() ? item.getContentRef() : thumb;
    }

    private boolean isAnimatedGif(Item item) {
        return item.getType() == ItemType.IMAGE && ImageLoader.isAnimatedGif(
                item.getMetadata().getMime(), item.getFilename(), item.getContentRef());
    }

    private int targetWidth(ImageView imageView) {
        int width = imageView.getWidth();
        if (width > 0) {
            return width;
        }
        int layoutWidth = imageView.getLayoutParams() == null ? 0 : imageView.getLayoutParams().width;
        return layoutWidth > 0 ? layoutWidth : imageView.getResources().getDimensionPixelSize(R.dimen.history_thumb_size);
    }

    private int targetHeight(ImageView imageView) {
        int height = imageView.getHeight();
        if (height > 0) {
            return height;
        }
        int layoutHeight = imageView.getLayoutParams() == null ? 0 : imageView.getLayoutParams().height;
        return layoutHeight > 0 ? layoutHeight : imageView.getResources().getDimensionPixelSize(R.dimen.history_thumb_size);
    }

    static final class MediaHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView filename;
        final TextView metadata;

        MediaHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.image_thumb);
            filename = itemView.findViewById(R.id.text_filename);
            metadata = itemView.findViewById(R.id.text_metadata);
        }
    }

    static final class FileHolder extends RecyclerView.ViewHolder {
        final TextView filename;
        final TextView metadata;
        final ImageButton view;
        final ImageButton download;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            filename = itemView.findViewById(R.id.text_filename);
            metadata = itemView.findViewById(R.id.text_metadata);
            view = itemView.findViewById(R.id.button_view);
            download = itemView.findViewById(R.id.button_download);
        }
    }
}
