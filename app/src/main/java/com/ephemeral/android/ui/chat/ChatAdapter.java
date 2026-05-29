package com.ephemeral.android.ui.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;

import com.ephemeral.android.ui.common.PopupMenus;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.data.model.SendStatus;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.util.ByteFormatter;
import com.ephemeral.android.util.DateFormatter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Callback {
        void retry(ChatEntry entry);

        void delete(Item item);

        void select(Item item);

        void openMedia(Item item);

        void openPreview(Item item);

        void download(Item item);

        void managePublicLink(Item item);
    }

    private static final int TYPE_TEXT = 1;
    private static final int TYPE_IMAGE = 2;
    private static final int TYPE_VIDEO = 3;
    private static final int TYPE_FILE = 4;

    private final ImageLoader imageLoader;
    private final Callback callback;
    private final List<ChatEntry> entries = new ArrayList<>();
    private final Set<Long> selectedItemIds = new HashSet<>();

    ChatAdapter(ImageLoader imageLoader, Callback callback) {
        this.imageLoader = imageLoader;
        this.callback = callback;
        setHasStableIds(true);
    }

    void submit(List<ChatEntry> nextEntries) {
        entries.clear();
        entries.addAll(nextEntries);
        notifyDataSetChanged();
    }

    void setSelectedItemIds(Set<Long> nextSelectedItemIds) {
        selectedItemIds.clear();
        selectedItemIds.addAll(nextSelectedItemIds);
        notifyDataSetChanged();
    }

    void updatePublicLinkActive(long itemId, boolean active) {
        for (int i = 0; i < entries.size(); i++) {
            ChatEntry entry = entries.get(i);
            if (!entry.isOptimistic() && entry.getItem().getId() == itemId) {
                Item updated = entry.getItem().withPublicLinkActive(active);
                entries.set(i, entry.withItem(updated));
                notifyItemChanged(i, "PUBLIC_LINK_UPDATE");
                return;
            }
        }
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).getStableId();
    }

    @Override
    public int getItemViewType(int position) {
        ChatEntry entry = entries.get(position);
        if (entry.isOptimistic() || entry.getItem().getType() == ItemType.TEXT) {
            return TYPE_TEXT;
        }
        if (entry.getItem().getType() == ItemType.IMAGE) {
            return TYPE_IMAGE;
        }
        if (entry.getItem().getType() == ItemType.VIDEO) {
            return TYPE_VIDEO;
        }
        return TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TEXT) {
            return new TextHolder(inflater.inflate(R.layout.row_chat_text, parent, false));
        }
        if (viewType == TYPE_IMAGE) {
            return new MediaHolder(inflater.inflate(R.layout.row_chat_image, parent, false));
        }
        if (viewType == TYPE_VIDEO) {
            return new MediaHolder(inflater.inflate(R.layout.row_chat_video, parent, false));
        }
        return new FileHolder(inflater.inflate(R.layout.row_chat_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && "PUBLIC_LINK_UPDATE".equals(payloads.get(0))) {
            ChatEntry entry = entries.get(position);
            if (!entry.isOptimistic() && entry.getItem() != null) {
                if (holder instanceof MediaHolder) {
                    ((MediaHolder) holder).publicIndicator.setVisibility(entry.getItem().isPublicLinkActive() ? View.VISIBLE : View.GONE);
                } else if (holder instanceof FileHolder) {
                    // ((FileHolder) holder).publicIndicator.setVisibility(...) if it exists
                } else if (holder instanceof TextHolder) {
                    // ((TextHolder) holder).publicIndicator.setVisibility(...) if it exists
                }
            }
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatEntry entry = entries.get(position);
        if (holder instanceof TextHolder) {
            bindText((TextHolder) holder, entry);
        } else if (holder instanceof MediaHolder) {
            bindMedia((MediaHolder) holder, entry.getItem());
        } else if (holder instanceof FileHolder) {
            bindFile((FileHolder) holder, entry.getItem());
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
        return entries.size();
    }

    private void bindText(TextHolder holder, ChatEntry entry) {
        holder.body.setText(entry.getText());
        holder.body.setTextIsSelectable(false);
        Linkify.addLinks(holder.body, Linkify.WEB_URLS);
        holder.body.setMovementMethod(isSelectionMode() ? null : LinkMovementMethod.getInstance());
        holder.timestamp.setText(DateFormatter.chat(entry.getCreatedAtEpochMillis()));
        holder.retry.setVisibility(!isSelectionMode() && entry.getSendStatus() == SendStatus.FAILED
                ? View.VISIBLE : View.GONE);
        holder.retry.setOnClickListener(v -> callback.retry(entry));
        holder.copy.setVisibility(isSelectionMode() ? View.GONE : View.VISIBLE);
        holder.copy.setOnClickListener(v -> copyText(holder.copy.getContext(), entry.getText()));
        holder.status.setText(entry.isOptimistic() ? entry.getSendStatus().name().toLowerCase(Locale.US) : "");
        boolean selectable = !entry.isOptimistic();
        if (selectable) {
            bindSelection(holder.itemView, holder.body, entry.getItem());
            holder.more.setVisibility(isSelectionMode() ? View.GONE : View.VISIBLE);
            holder.more.setOnClickListener(v -> showOptionsMenu(holder.more, entry.getItem()));
        } else {
            clearSelectionUi(holder.itemView, holder.body);
            holder.more.setVisibility(View.GONE);
        }
    }

    private void bindMedia(MediaHolder holder, Item item) {
        bindSelection(holder.itemView, null, item);
        holder.filename.setText(item.getFilename());
        holder.metadata.setText(metadataLine(item));
        holder.timestamp.setText(DateFormatter.chat(item.getCreatedAtEpochMillis()));
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
        holder.more.setVisibility(isSelectionMode() ? View.GONE : View.VISIBLE);
        holder.more.setOnClickListener(v -> showOptionsMenu(holder.more, item));
        holder.publicIndicator.setVisibility(item.isPublicLinkActive() ? View.VISIBLE : View.GONE);
    }

    private void bindFile(FileHolder holder, Item item) {
        bindSelection(holder.itemView, null, item);
        holder.filename.setText(item.getFilename());
        holder.metadata.setText(metadataLine(item));
        holder.timestamp.setText(DateFormatter.chat(item.getCreatedAtEpochMillis()));
        boolean selectionMode = isSelectionMode();
        holder.view.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.download.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.view.setEnabled(item.isPreviewable());
        holder.view.setOnClickListener(v -> {
            if (item.isPreviewable()) {
                callback.openPreview(item);
            } else {
                callback.download(item);
            }
        });
        holder.download.setOnClickListener(v -> callback.download(item));
        holder.more.setOnClickListener(v -> showOptionsMenu(holder.more, item));
        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode()) {
                callback.select(item);
            }
        });
        holder.publicIndicator.setVisibility(item.isPublicLinkActive() ? View.VISIBLE : View.GONE);
    }

    private void bindSelection(View itemView, View childView, Item item) {
        boolean selected = selectedItemIds.contains(item.getId());
        itemView.setSelected(selected);
        itemView.setForeground(selected ? itemView.getContext().getDrawable(R.drawable.bg_multi_select_foreground) : null);
        itemView.setOnLongClickListener(v -> {
            callback.select(item);
            return true;
        });
        if (isSelectionMode()) {
            itemView.setOnClickListener(v -> callback.select(item));
        }
        if (childView != null) {
            childView.setOnLongClickListener(v -> {
                callback.select(item);
                return true;
            });
            childView.setOnClickListener(isSelectionMode() ? v -> callback.select(item) : null);
        }
    }

    private void clearSelectionUi(View itemView, View childView) {
        itemView.setSelected(false);
        itemView.setForeground(null);
        itemView.setOnLongClickListener(null);
        itemView.setOnClickListener(null);
        if (childView != null) {
            childView.setOnLongClickListener(null);
            childView.setOnClickListener(null);
        }
    }

    private boolean isSelectionMode() {
        return !selectedItemIds.isEmpty();
    }

    private void showOptionsMenu(View anchor, Item item) {
        PopupMenu menu = PopupMenus.create(anchor);
        if (item.getType() != ItemType.TEXT) {
            String label = item.isPublicLinkActive() ? "Manage link" : "Share link";
            menu.getMenu().add(label);
        }
        menu.getMenu().add(R.string.delete);
        menu.setOnMenuItemClickListener(menuItem -> {
            CharSequence title = menuItem.getTitle();
            if ("Share link".equals(title) || "Manage link".equals(title)) {
                callback.managePublicLink(item);
            } else {
                callback.delete(item);
            }
            return true;
        });
        menu.show();
    }

    private void copyText(Context context, String value) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copy), value));
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private String metadataLine(Item item) {
        ItemMetadata metadata = item.getMetadata();
        StringBuilder builder = new StringBuilder(ByteFormatter.format(item.getFilesizeBytes()));
        if (metadata.hasDimensions()) {
            builder.append(" - ").append(metadata.getWidth()).append("x").append(metadata.getHeight());
        }
        if (!metadata.getDuration().isEmpty()) {
            builder.append(" - ").append(metadata.getDuration());
        }
        if (!metadata.getMime().isEmpty()) {
            builder.append(" - ").append(metadata.getMime());
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
        return layoutWidth > 0 ? layoutWidth : imageView.getResources().getDimensionPixelSize(R.dimen.thumb_size);
    }

    private int targetHeight(ImageView imageView) {
        int height = imageView.getHeight();
        if (height > 0) {
            return height;
        }
        int layoutHeight = imageView.getLayoutParams() == null ? 0 : imageView.getLayoutParams().height;
        return layoutHeight > 0 ? layoutHeight : imageView.getResources().getDimensionPixelSize(R.dimen.thumb_size);
    }

    static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView body;
        final TextView timestamp;
        final TextView status;
        final Button retry;
        final ImageButton copy;
        final ImageButton more;

        TextHolder(@NonNull View itemView) {
            super(itemView);
            body = itemView.findViewById(R.id.text_body);
            timestamp = itemView.findViewById(R.id.text_timestamp);
            status = itemView.findViewById(R.id.text_send_status);
            retry = itemView.findViewById(R.id.button_retry);
            copy = itemView.findViewById(R.id.button_copy);
            more = itemView.findViewById(R.id.button_more);
        }
    }

    static class MediaHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView filename;
        final TextView metadata;
        final TextView timestamp;
        final ImageButton more;
        final ImageView publicIndicator;

        MediaHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.image_thumb);
            filename = itemView.findViewById(R.id.text_filename);
            metadata = itemView.findViewById(R.id.text_metadata);
            timestamp = itemView.findViewById(R.id.text_timestamp);
            more = itemView.findViewById(R.id.button_more);
            publicIndicator = itemView.findViewById(R.id.image_public_indicator);
        }
    }

    static final class FileHolder extends RecyclerView.ViewHolder {
        final TextView filename;
        final TextView metadata;
        final TextView timestamp;
        final ImageButton view;
        final ImageButton download;
        final ImageButton more;
        final ImageView publicIndicator;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            filename = itemView.findViewById(R.id.text_filename);
            metadata = itemView.findViewById(R.id.text_metadata);
            timestamp = itemView.findViewById(R.id.text_timestamp);
            view = itemView.findViewById(R.id.button_view);
            download = itemView.findViewById(R.id.button_download);
            more = itemView.findViewById(R.id.button_more);
            publicIndicator = itemView.findViewById(R.id.image_public_indicator);
        }
    }
}
