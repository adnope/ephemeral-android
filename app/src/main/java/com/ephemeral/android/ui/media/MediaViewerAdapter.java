package com.ephemeral.android.ui.media;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.ui.common.ImageLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

final class MediaViewerAdapter extends RecyclerView.Adapter<MediaViewerAdapter.MediaHolder> {
    private static final long VIDEO_CACHE_MAX_BYTES = 64L * 1024L * 1024L;

    private final ImageLoader imageLoader;
    private final OkHttpClient httpClient;
    private final List<Item> items = new ArrayList<>();
    private final Set<MediaHolder> attachedHolders = Collections.newSetFromMap(new IdentityHashMap<>());
    private int activePosition = RecyclerView.NO_POSITION;

    MediaViewerAdapter(ImageLoader imageLoader, OkHttpClient httpClient) {
        this.imageLoader = imageLoader;
        this.httpClient = httpClient;
        setHasStableIds(true);
    }

    void submit(List<Item> nextItems, int activePosition) {
        items.clear();
        items.addAll(nextItems);
        this.activePosition = validPosition(activePosition) ? activePosition : RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    void setActivePosition(int position) {
        int nextPosition = validPosition(position) ? position : RecyclerView.NO_POSITION;
        if (activePosition == nextPosition) {
            if (needsPlaybackRebind(nextPosition)) {
                notifyItemChanged(nextPosition);
            }
            return;
        }
        int previousPosition = activePosition;
        activePosition = nextPosition;
        if (needsPlaybackRebind(previousPosition)) {
            notifyItemChanged(previousPosition);
        }
        if (needsPlaybackRebind(activePosition)) {
            notifyItemChanged(activePosition);
        }
    }

    void release() {
        for (MediaHolder holder : new ArrayList<>(attachedHolders)) {
            holder.release();
        }
        attachedHolders.clear();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).getId();
    }

    @NonNull
    @Override
    public MediaHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_media_viewer_page, parent, false);
        return new MediaHolder(view, imageLoader, httpClient);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaHolder holder, int position) {
        holder.bind(items.get(position), position == activePosition);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull MediaHolder holder) {
        attachedHolders.add(holder);
        super.onViewAttachedToWindow(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull MediaHolder holder) {
        attachedHolders.remove(holder);
        holder.release();
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull MediaHolder holder) {
        attachedHolders.remove(holder);
        holder.release();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean validPosition(int position) {
        return position >= 0 && position < items.size();
    }

    private boolean needsPlaybackRebind(int position) {
        return validPosition(position) && items.get(position).getType() == ItemType.VIDEO;
    }

    static final class MediaHolder extends RecyclerView.ViewHolder {
        private final ImageLoader imageLoader;
        private final OkHttpClient httpClient;
        private final ZoomableImageView image;
        private final VideoView video;
        private final TextView error;
        private final MediaController videoControls;
        private Future<?> videoCacheFuture;
        private int generation;
        private boolean videoPrepared;

        MediaHolder(@NonNull View itemView, ImageLoader imageLoader, OkHttpClient httpClient) {
            super(itemView);
            this.imageLoader = imageLoader;
            this.httpClient = httpClient;
            image = itemView.findViewById(R.id.image_media_page);
            video = itemView.findViewById(R.id.video_media_page);
            error = itemView.findViewById(R.id.text_media_page_error);
            videoControls = new MediaController(itemView.getContext());
            videoControls.setAnchorView(itemView);
            video.setMediaController(videoControls);
        }

        void bind(Item item, boolean active) {
            generation++;
            stopVideo();
            videoPrepared = false;
            error.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            video.setVisibility(View.GONE);
            image.resetZoom();
            itemView.setOnClickListener(v -> {
                if (active && item.getType() == ItemType.VIDEO) {
                    showVideoControlsIfReady();
                }
            });
            if (item.getType() == ItemType.IMAGE) {
                image.setZoomEnabled(true);
                boolean animatedGif = ImageLoader.isAnimatedGif(
                        item.getMetadata().getMime(), item.getFilename(), item.getContentRef());
                imageLoader.loadProgressiveImage(image, item.getMetadata().getThumbRef(), item.getContentRef(),
                        imageTargetWidth(), imageTargetHeight(), R.drawable.ic_image_placeholder, animatedGif);
                return;
            }
            image.setZoomEnabled(false);
            boolean cachedVideo = imageLoader.cachedVideoUri(item.getContentRef(), VIDEO_CACHE_MAX_BYTES) != null;
            if (active && cachedVideo) {
                imageLoader.clear(image);
            } else {
                loadVideoPoster(item);
            }
            if (active) {
                loadVideo(item, generation);
            }
        }

        void release() {
            generation++;
            imageLoader.cancel(image);
            stopVideo();
            error.setVisibility(View.GONE);
        }

        private void loadVideoPoster(Item item) {
            String thumbnail = item.getMetadata().getThumbRef();
            if (thumbnail.isEmpty()) {
                imageLoader.setPlaceholder(image, R.drawable.ic_video_placeholder);
                return;
            }
            imageLoader.loadContentRef(image, thumbnail, targetWidth(), targetHeight(),
                    R.drawable.ic_video_placeholder);
        }

        private void loadVideo(Item item, int videoGeneration) {
            error.setVisibility(View.GONE);
            Uri direct = parsePlayableUri(item.getContentRef());
            if (direct == null) {
                showError("Video playback failed.");
                return;
            }
            String scheme = direct.getScheme();
            if ("content".equals(scheme) || "file".equals(scheme)) {
                playVideo(direct, Collections.emptyMap(), videoGeneration);
                return;
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                showError("Video playback failed.");
                return;
            }
            Uri cached = imageLoader.cachedVideoUri(item.getContentRef(), VIDEO_CACHE_MAX_BYTES);
            if (cached != null) {
                playVideo(cached, Collections.emptyMap(), videoGeneration);
                return;
            }
            if (shouldCacheVideo(item)) {
                videoCacheFuture = imageLoader.cacheVideoForPlayback(item.getContentRef(),
                        item.getFilesizeBytes(), VIDEO_CACHE_MAX_BYTES, new ImageLoader.VideoCacheCallback() {
                            @Override
                            public void onCachedVideo(Uri uri) {
                                videoCacheFuture = null;
                                if (videoGeneration == generation) {
                                    playVideo(uri, Collections.emptyMap(), videoGeneration);
                                }
                            }

                            @Override
                            public void onCacheUnavailable() {
                                videoCacheFuture = null;
                                if (videoGeneration == generation) {
                                    playVideo(direct, streamingHeaders(item.getContentRef()), videoGeneration);
                                }
                            }
                        });
                return;
            }
            playVideo(direct, streamingHeaders(item.getContentRef()), videoGeneration);
        }

        private void playVideo(Uri uri, Map<String, String> headers, int videoGeneration) {
            try {
                image.setVisibility(View.VISIBLE);
                video.setVisibility(View.VISIBLE);
                video.setOnPreparedListener(mp -> {
                    if (videoGeneration != generation) {
                        return;
                    }
                    videoPrepared = true;
                    error.setVisibility(View.GONE);
                    image.setVisibility(View.GONE);
                    video.setVisibility(View.VISIBLE);
                    video.start();
                });
                video.setOnErrorListener((mp, what, extra) -> {
                    if (videoGeneration == generation) {
                        videoPrepared = false;
                        showError("Video playback failed.");
                        video.setVisibility(View.GONE);
                        image.setVisibility(View.VISIBLE);
                    }
                    return true;
                });
                video.setVideoURI(uri, headers);
                video.requestFocus();
            } catch (RuntimeException e) {
                stopVideo();
                videoPrepared = false;
                video.setVisibility(View.GONE);
                image.setVisibility(View.VISIBLE);
                showError("Video playback failed.");
            }
        }

        private void showVideoControlsIfReady() {
            if (!videoPrepared || video.getVisibility() != View.VISIBLE || !video.isShown()) {
                return;
            }
            try {
                videoControls.show();
            } catch (RuntimeException e) {
                // MediaController uses a PopupWindow and can throw if the VideoView window is not ready.
            }
        }

        private void showError(String message) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
        }

        private void stopVideo() {
            if (videoCacheFuture != null) {
                videoCacheFuture.cancel(true);
                videoCacheFuture = null;
            }
            try {
                video.stopPlayback();
            } catch (RuntimeException e) {
                // Platform decoders can throw while a failed VideoView is being torn down.
            }
            try {
                videoControls.hide();
            } catch (RuntimeException e) {
                // Hiding the platform popup can also throw during teardown on some devices.
            }
            videoPrepared = false;
            video.setOnPreparedListener(null);
            video.setOnErrorListener(null);
            video.setVisibility(View.GONE);
        }

        private int targetWidth() {
            int width = itemView.getWidth();
            return width > 0 ? width : itemView.getResources().getDisplayMetrics().widthPixels;
        }

        private int targetHeight() {
            int height = itemView.getHeight();
            return height > 0 ? height : itemView.getResources().getDisplayMetrics().heightPixels;
        }

        private int imageTargetWidth() {
            return targetWidth() * 2;
        }

        private int imageTargetHeight() {
            return targetHeight() * 2;
        }

        private boolean shouldCacheVideo(Item item) {
            long filesizeBytes = item.getFilesizeBytes();
            return filesizeBytes > 0 && filesizeBytes <= VIDEO_CACHE_MAX_BYTES;
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
    }
}
