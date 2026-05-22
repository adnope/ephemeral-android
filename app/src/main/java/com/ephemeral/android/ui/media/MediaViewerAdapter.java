package com.ephemeral.android.ui.media;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.ui.common.ImageLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

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
        private final PlayerView playerView;
        private final TextView error;
        private Future<?> videoCacheFuture;
        private ExoPlayer player;
        private int generation;
        private boolean videoPrepared;

        MediaHolder(@NonNull View itemView, ImageLoader imageLoader, OkHttpClient httpClient) {
            super(itemView);
            this.imageLoader = imageLoader;
            this.httpClient = httpClient;
            image = itemView.findViewById(R.id.image_media_page);
            playerView = itemView.findViewById(R.id.player_media_page);
            error = itemView.findViewById(R.id.text_media_page_error);
            playerView.setUseController(true);
            playerView.setControllerAutoShow(true);
        }

        void bind(Item item, boolean active) {
            generation++;
            stopVideo();
            videoPrepared = false;
            error.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            playerView.setVisibility(View.GONE);
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
            if (item.getMetadata().isProcessing()) {
                loadVideoPoster(item);
                if (active) {
                    showError(itemView.getContext().getString(R.string.video_processing));
                }
                return;
            }
            PlaybackSource source = playbackSource(item);
            boolean cachedVideo = source.cacheable
                    && imageLoader.cachedVideoUri(source.ref, VIDEO_CACHE_MAX_BYTES) != null;
            if (active && cachedVideo) {
                imageLoader.clear(image);
            } else {
                loadVideoPoster(item);
            }
            if (active) {
                loadVideo(item, source, generation);
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

        private void loadVideo(Item item, PlaybackSource source, int videoGeneration) {
            error.setVisibility(View.GONE);
            Uri direct = parsePlayableUri(source.ref);
            if (direct == null) {
                showPlaybackFailed();
                return;
            }
            String scheme = direct.getScheme();
            if ("content".equals(scheme) || "file".equals(scheme)) {
                playVideo(direct, source.mimeType, videoGeneration);
                return;
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                showPlaybackFailed();
                return;
            }
            Uri cached = source.cacheable
                    ? imageLoader.cachedVideoUri(source.ref, VIDEO_CACHE_MAX_BYTES)
                    : null;
            if (cached != null) {
                playVideo(cached, source.mimeType, videoGeneration);
                return;
            }
            if (shouldCacheVideo(item, source)) {
                videoCacheFuture = imageLoader.cacheVideoForPlayback(source.ref,
                        item.getFilesizeBytes(), VIDEO_CACHE_MAX_BYTES, new ImageLoader.VideoCacheCallback() {
                            @Override
                            public void onCachedVideo(Uri uri) {
                                videoCacheFuture = null;
                                if (videoGeneration == generation) {
                                    playVideo(uri, source.mimeType, videoGeneration);
                                }
                            }

                            @Override
                            public void onCacheUnavailable() {
                                videoCacheFuture = null;
                                if (videoGeneration == generation) {
                                    playVideo(direct, source.mimeType, videoGeneration);
                                }
                            }
                        });
                return;
            }
            playVideo(direct, source.mimeType, videoGeneration);
        }

        private void playVideo(Uri uri, String mimeType, int videoGeneration) {
            try {
                image.setVisibility(View.VISIBLE);
                playerView.setVisibility(View.VISIBLE);
                Context context = itemView.getContext();
                player = new ExoPlayer.Builder(context)
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory(context)))
                        .build();
                playerView.setPlayer(player);
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (videoGeneration != generation || playbackState != Player.STATE_READY) {
                            return;
                        }
                        videoPrepared = true;
                        error.setVisibility(View.GONE);
                        image.setVisibility(View.GONE);
                        playerView.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        if (videoGeneration == generation) {
                            showPlaybackFailed();
                        }
                    }
                });
                player.setMediaItem(mediaItem(uri, mimeType));
                player.setPlayWhenReady(true);
                player.prepare();
            } catch (RuntimeException e) {
                showPlaybackFailed();
            }
        }

        private MediaItem mediaItem(Uri uri, String mimeType) {
            MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
            if (mimeType != null && !mimeType.isEmpty()) {
                builder.setMimeType(mimeType);
            }
            return builder.build();
        }

        private DataSource.Factory dataSourceFactory(Context context) {
            if (httpClient == null) {
                return new DefaultDataSource.Factory(context);
            }
            OkHttpDataSource.Factory okHttpFactory = new OkHttpDataSource.Factory(httpClient)
                    .setDefaultRequestProperties(Collections.singletonMap(
                            "Accept", "video/*,application/vnd.apple.mpegurl,*/*"));
            return new DefaultDataSource.Factory(context, okHttpFactory);
        }

        private void showVideoControlsIfReady() {
            if (!videoPrepared || playerView.getVisibility() != View.VISIBLE || !playerView.isShown()) {
                return;
            }
            playerView.showController();
        }

        private void showPlaybackFailed() {
            stopVideo();
            showError(itemView.getContext().getString(R.string.video_playback_failed));
            playerView.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
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
            if (player != null) {
                playerView.setPlayer(null);
                player.release();
                player = null;
            }
            videoPrepared = false;
            playerView.setVisibility(View.GONE);
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

        private boolean shouldCacheVideo(Item item, PlaybackSource source) {
            long filesizeBytes = item.getFilesizeBytes();
            return source.cacheable && filesizeBytes > 0 && filesizeBytes <= VIDEO_CACHE_MAX_BYTES;
        }

        private PlaybackSource playbackSource(Item item) {
            ItemMetadata metadata = item.getMetadata();
            if (!metadata.getHlsRef().isEmpty()) {
                return new PlaybackSource(metadata.getHlsRef(), MimeTypes.APPLICATION_M3U8, false);
            }
            if (!metadata.getPlaybackRef().isEmpty()) {
                String mime = metadata.getPlaybackMime().isEmpty()
                        ? MimeTypes.VIDEO_MP4 : metadata.getPlaybackMime();
                return new PlaybackSource(metadata.getPlaybackRef(), mime, true);
            }
            return new PlaybackSource(item.getContentRef(), metadata.getMime(), true);
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

        private static final class PlaybackSource {
            final String ref;
            final String mimeType;
            final boolean cacheable;

            PlaybackSource(String ref, String mimeType, boolean cacheable) {
                this.ref = ref == null ? "" : ref;
                this.mimeType = mimeType == null ? "" : mimeType;
                this.cacheable = cacheable;
            }
        }
    }
}
