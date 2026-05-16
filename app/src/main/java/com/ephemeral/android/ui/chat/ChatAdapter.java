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
import java.util.List;
import java.util.Locale;

final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Callback {
        void retry(ChatEntry entry);

        void delete(Item item);

        void openMedia(Item item);

        void openPreview(Item item);

        void download(Item item);
    }

    private static final int TYPE_TEXT = 1;
    private static final int TYPE_IMAGE = 2;
    private static final int TYPE_VIDEO = 3;
    private static final int TYPE_FILE = 4;

    private final ImageLoader imageLoader;
    private final Callback callback;
    private final List<ChatEntry> entries = new ArrayList<>();

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
        Linkify.addLinks(holder.body, Linkify.WEB_URLS);
        holder.body.setMovementMethod(LinkMovementMethod.getInstance());
        holder.timestamp.setText(DateFormatter.chat(entry.getCreatedAtEpochMillis()));
        holder.retry.setVisibility(entry.getSendStatus() == SendStatus.FAILED ? View.VISIBLE : View.GONE);
        holder.retry.setOnClickListener(v -> callback.retry(entry));
        holder.copy.setOnClickListener(v -> copyText(holder.copy.getContext(), entry.getText()));
        holder.status.setText(entry.isOptimistic() ? entry.getSendStatus().name().toLowerCase(Locale.US) : "");
        holder.more.setVisibility(entry.isOptimistic() ? View.GONE : View.VISIBLE);
        if (!entry.isOptimistic()) {
            holder.more.setOnClickListener(v -> showDeleteMenu(holder.more, entry.getItem()));
        }
    }

    private void bindMedia(MediaHolder holder, Item item) {
        holder.filename.setText(item.getFilename());
        holder.metadata.setText(metadataLine(item));
        holder.timestamp.setText(DateFormatter.chat(item.getCreatedAtEpochMillis()));
        int placeholder = item.getType() == ItemType.IMAGE
                ? R.drawable.ic_image_placeholder : R.drawable.ic_video_placeholder;
        boolean animatedGif = isAnimatedGif(item);
        imageLoader.loadContentRef(holder.thumbnail, animatedGif ? item.getContentRef() : preferredImageRef(item),
                targetWidth(holder.thumbnail), targetHeight(holder.thumbnail), placeholder, animatedGif);
        holder.itemView.setOnClickListener(v -> callback.openMedia(item));
        holder.more.setOnClickListener(v -> showDeleteMenu(holder.more, item));
    }

    private void bindFile(FileHolder holder, Item item) {
        holder.filename.setText(item.getFilename());
        holder.metadata.setText(metadataLine(item) + " - " + DateFormatter.chat(item.getCreatedAtEpochMillis()));
        holder.view.setEnabled(item.isPreviewable());
        holder.view.setOnClickListener(v -> {
            if (item.isPreviewable()) {
                callback.openPreview(item);
            } else {
                callback.download(item);
            }
        });
        holder.download.setOnClickListener(v -> callback.download(item));
        holder.more.setOnClickListener(v -> showDeleteMenu(holder.more, item));
    }

    private void showDeleteMenu(View anchor, Item item) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(R.string.delete);
        menu.setOnMenuItemClickListener(menuItem -> {
            callback.delete(item);
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

        MediaHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.image_thumb);
            filename = itemView.findViewById(R.id.text_filename);
            metadata = itemView.findViewById(R.id.text_metadata);
            timestamp = itemView.findViewById(R.id.text_timestamp);
            more = itemView.findViewById(R.id.button_more);
        }
    }

    static final class FileHolder extends RecyclerView.ViewHolder {
        final TextView filename;
        final TextView metadata;
        final ImageButton view;
        final ImageButton download;
        final ImageButton more;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            filename = itemView.findViewById(R.id.text_filename);
            metadata = itemView.findViewById(R.id.text_metadata);
            view = itemView.findViewById(R.id.button_view);
            download = itemView.findViewById(R.id.button_download);
            more = itemView.findViewById(R.id.button_more);
        }
    }
}
