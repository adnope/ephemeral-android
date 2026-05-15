package com.ephemeral.android.ui.media;

import android.net.Uri;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.util.ByteFormatter;
import com.ephemeral.android.util.DateFormatter;

import java.util.ArrayList;
import java.util.List;

public final class MediaViewerController implements ItemEventConsumer {
    private final View view;
    private final ScreenHost host;
    private final ImageLoader imageLoader;
    private final List<Item> mediaItems;
    private final ImageView image;
    private final VideoView video;
    private final TextView title;
    private final TextView metadata;
    private final TextView error;
    private final ImageButton previous;
    private final ImageButton next;
    private int index;

    public MediaViewerController(LayoutInflater inflater, ScreenHost host, ImageLoader imageLoader,
            List<Item> mediaItems, int startIndex) {
        this.host = host;
        this.imageLoader = imageLoader;
        this.mediaItems = new ArrayList<>(mediaItems);
        index = Math.max(0, Math.min(startIndex, Math.max(0, mediaItems.size() - 1)));
        view = inflater.inflate(R.layout.screen_media_viewer, null, false);
        image = view.findViewById(R.id.image_media);
        video = view.findViewById(R.id.video_media);
        title = view.findViewById(R.id.text_media_title);
        metadata = view.findViewById(R.id.text_media_metadata);
        error = view.findViewById(R.id.text_media_error);
        previous = view.findViewById(R.id.button_previous);
        next = view.findViewById(R.id.button_next);
        previous.setOnClickListener(v -> show(index - 1));
        next.setOnClickListener(v -> show(index + 1));
        view.findViewById(R.id.button_close).setOnClickListener(v -> close());
        view.findViewById(R.id.button_download).setOnClickListener(v -> host.downloadItem(current()));
        view.findViewById(R.id.button_delete).setOnClickListener(v ->
                host.confirmDelete(current(), () -> {
                    mediaItems.remove(index);
                    if (mediaItems.isEmpty()) {
                        close();
                    } else {
                        show(Math.min(index, mediaItems.size() - 1));
                    }
                }));
        video.setMediaController(new MediaController(view.getContext()));
        show(index);
    }

    public View getView() {
        return view;
    }

    public boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            show(index - 1);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            show(index + 1);
            return true;
        }
        return false;
    }

    @Override
    public void onItemEvent(ItemEvent event) {
        if (event.getType() != ItemEventType.DELETED) {
            return;
        }
        for (int i = mediaItems.size() - 1; i >= 0; i--) {
            if (mediaItems.get(i).getId() == event.getItemId()) {
                mediaItems.remove(i);
            }
        }
        if (mediaItems.isEmpty()) {
            close();
        } else {
            show(Math.min(index, mediaItems.size() - 1));
        }
    }

    public void release() {
        stopVideo();
        imageLoader.cancel(image);
    }

    private void show(int nextIndex) {
        if (mediaItems.isEmpty()) {
            close();
            return;
        }
        index = Math.max(0, Math.min(nextIndex, mediaItems.size() - 1));
        Item item = current();
        previous.setEnabled(index > 0);
        next.setEnabled(index < mediaItems.size() - 1);
        title.setText(item.getFilename());
        metadata.setText(metadataLine(item));
        error.setVisibility(View.GONE);
        stopVideo();
        video.setVisibility(View.GONE);
        image.setVisibility(View.VISIBLE);
        if (item.getType() == ItemType.IMAGE) {
            imageLoader.loadContentRef(image, item.getContentRef(), targetWidth(image), targetHeight(image),
                    R.drawable.ic_image_placeholder);
        } else {
            imageLoader.setPlaceholder(image, R.drawable.ic_video_placeholder);
            Uri uri = parsePlayableUri(item.getContentRef());
            if (uri != null) {
                playVideo(uri);
            } else {
                showError("Video source is unavailable until the mobile API contract is connected.");
            }
        }
    }

    private Item current() {
        return mediaItems.get(index);
    }

    private void close() {
        release();
        host.closeOverlay();
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void playVideo(Uri uri) {
        try {
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setOnErrorListener((mp, what, extra) -> {
                showError("Video playback failed.");
                return true;
            });
            video.setVideoURI(uri);
            video.start();
        } catch (RuntimeException e) {
            stopVideo();
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            showError("Video playback failed.");
        }
    }

    private void stopVideo() {
        try {
            video.stopPlayback();
        } catch (RuntimeException e) {
            // VideoView can throw while tearing down a failed platform decoder.
        }
    }

    private int targetWidth(ImageView imageView) {
        int width = imageView.getWidth();
        return width > 0 ? width : imageView.getResources().getDisplayMetrics().widthPixels;
    }

    private int targetHeight(ImageView imageView) {
        int height = imageView.getHeight();
        return height > 0 ? height : imageView.getResources().getDisplayMetrics().heightPixels;
    }

    private Uri parsePlayableUri(String contentRef) {
        if (contentRef == null) {
            return null;
        }
        if (contentRef.startsWith("content://") || contentRef.startsWith("file://")) {
            return Uri.parse(contentRef);
        }
        if (contentRef.startsWith("http://") || contentRef.startsWith("https://")) {
            return Uri.parse(contentRef);
        }
        return null;
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
