package com.ephemeral.android.ui.media;

import android.net.Uri;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.util.ByteFormatter;
import com.ephemeral.android.util.DateFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public final class MediaViewerController implements ItemEventConsumer {
    private static final int SWIPE_THRESHOLD_DP = 72;

    private final View view;
    private final ScreenHost host;
    private final ImageLoader imageLoader;
    private final OkHttpClient httpClient;
    private final List<Item> mediaItems;
    private final FrameLayout mediaContainer;
    private final ImageView image;
    private final VideoView video;
    private final TextView title;
    private final TextView metadata;
    private final TextView error;
    private final ImageButton previous;
    private final ImageButton next;
    private final MediaController videoControls;
    private float swipeStartX;
    private float swipeStartY;
    private int index;
    private int loadGeneration;

    public MediaViewerController(LayoutInflater inflater, ScreenHost host, ImageLoader imageLoader,
            OkHttpClient httpClient, List<Item> mediaItems, int startIndex) {
        this.host = host;
        this.imageLoader = imageLoader;
        this.httpClient = httpClient;
        this.mediaItems = new ArrayList<>(mediaItems);
        index = Math.max(0, Math.min(startIndex, Math.max(0, mediaItems.size() - 1)));
        view = inflater.inflate(R.layout.screen_media_viewer, null, false);
        mediaContainer = view.findViewById(R.id.container_media);
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
        videoControls = new MediaController(view.getContext());
        videoControls.setAnchorView(video);
        video.setMediaController(videoControls);
        mediaContainer.setOnTouchListener(this::handleSwipeTouch);
        image.setOnTouchListener(this::handleSwipeTouch);
        video.setOnTouchListener(this::handleSwipeTouch);
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
        loadGeneration++;
        stopVideo();
        imageLoader.cancel(image);
    }

    private void show(int nextIndex) {
        if (mediaItems.isEmpty()) {
            close();
            return;
        }
        index = Math.max(0, Math.min(nextIndex, mediaItems.size() - 1));
        int generation = ++loadGeneration;
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
            boolean animatedGif = ImageLoader.isAnimatedGif(
                    item.getMetadata().getMime(), item.getFilename(), item.getContentRef());
            imageLoader.loadProgressiveImage(image, item.getMetadata().getThumbRef(), item.getContentRef(),
                    targetWidth(image), targetHeight(image), R.drawable.ic_image_placeholder, animatedGif);
        } else {
            imageLoader.setPlaceholder(image, R.drawable.ic_video_placeholder);
            loadVideo(item, generation);
        }
    }

    private Item current() {
        return mediaItems.get(index);
    }

    private void close() {
        host.closeOverlay();
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void loadVideo(Item item, int generation) {
        showError("Loading video...");
        Uri direct = parsePlayableUri(item.getContentRef());
        if (direct == null) {
            showError("Video playback failed.");
            return;
        }
        String scheme = direct.getScheme();
        if ("content".equals(scheme) || "file".equals(scheme)) {
            playVideo(direct, Collections.emptyMap());
            return;
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            showError("Video playback failed.");
            return;
        }
        Map<String, String> headers = streamingHeaders(item.getContentRef());
        if (generation == loadGeneration) {
            error.setVisibility(View.GONE);
            playVideo(direct, headers);
        }
    }

    private void playVideo(Uri uri, Map<String, String> headers) {
        try {
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setOnPreparedListener(mp -> {
                error.setVisibility(View.GONE);
                video.start();
            });
            video.setOnErrorListener((mp, what, extra) -> {
                showError("Video playback failed.");
                return true;
            });
            video.setVideoURI(uri, headers);
            video.requestFocus();
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
        video.setOnPreparedListener(null);
        video.setOnErrorListener(null);
    }

    private boolean handleSwipeTouch(View touched, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartX = event.getX();
                swipeStartY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - swipeStartX;
                float dy = event.getY() - swipeStartY;
                float threshold = SWIPE_THRESHOLD_DP * view.getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx < 0) {
                        show(index + 1);
                    } else {
                        show(index - 1);
                    }
                    return true;
                }
                touched.performClick();
                if (touched == video) {
                    videoControls.show();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
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

    private Map<String, String> streamingHeaders(String contentRef) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "video/*,*/*");
        if (httpClient == null) {
            return headers;
        }
        HttpUrl url;
        try {
            url = HttpUrl.get(contentRef);
        } catch (IllegalArgumentException e) {
            return headers;
        }
        List<Cookie> cookies = httpClient.cookieJar().loadForRequest(url);
        if (cookies.isEmpty()) {
            return headers;
        }
        StringBuilder cookieHeader = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (cookieHeader.length() > 0) {
                cookieHeader.append("; ");
            }
            cookieHeader.append(cookie.name()).append('=').append(cookie.value());
        }
        headers.put("Cookie", cookieHeader.toString());
        return headers;
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
