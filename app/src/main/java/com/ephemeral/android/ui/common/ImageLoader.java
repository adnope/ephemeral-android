package com.ephemeral.android.ui.common;

import android.content.ContentResolver;
import android.graphics.ImageDecoder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import com.ephemeral.android.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class ImageLoader {
    private static final int DISK_CACHE_MAX_DIMENSION_PX = 512;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long DISK_CACHE_MAX_BYTES = 100L * 1024L * 1024L;
    private static final int JPEG_THUMBNAIL_QUALITY = 85;
    private static final long ANIMATED_IMAGE_MAX_BYTES = 64L * 1024L * 1024L;
    private static final String THUMB_CACHE_SUFFIX = ".thumb";
    private static final String FULL_IMAGE_CACHE_SUFFIX = ".full";

    private final ContentResolver contentResolver;
    private final AppExecutors executors;
    private final OkHttpClient httpClient;
    private final File diskCacheDirectory;
    private final LruCache<String, Bitmap> cache;
    private final Map<String, Bitmap> sessionThumbnailCache = new ConcurrentHashMap<>();
    private final Map<ImageView, Future<?>> inFlight = new WeakHashMap<>();

    public ImageLoader(ContentResolver contentResolver, AppExecutors executors) {
        this(contentResolver, executors, null, null);
    }

    public ImageLoader(ContentResolver contentResolver, AppExecutors executors, OkHttpClient httpClient) {
        this(contentResolver, executors, httpClient, null);
    }

    public ImageLoader(ContentResolver contentResolver, AppExecutors executors, OkHttpClient httpClient,
            File diskCacheDirectory) {
        this.contentResolver = contentResolver;
        this.executors = executors;
        this.httpClient = httpClient;
        this.diskCacheDirectory = diskCacheDirectory;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 12L);
        cache = new LruCache<>(Math.max(1024, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void loadContentRef(ImageView target, String contentRef, int targetWidth, int targetHeight,
            int placeholderRes) {
        loadContentRef(target, contentRef, targetWidth, targetHeight, placeholderRes, false);
    }

    public void loadContentRef(ImageView target, String contentRef, int targetWidth, int targetHeight,
            int placeholderRes, boolean animateIfSupported) {
        if (contentRef == null || contentRef.isEmpty()) {
            setPlaceholder(target, placeholderRes);
            return;
        }
        if (animateIfSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            loadAnimatedContentRef(target, contentRef, targetWidth, targetHeight, placeholderRes);
            return;
        }
        Uri uri = Uri.parse(contentRef);
        String scheme = uri.getScheme();
        if ("content".equals(scheme) || "file".equals(scheme)) {
            loadContentUri(target, uri, targetWidth, targetHeight, placeholderRes);
            return;
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && httpClient != null) {
            loadHttpUrl(target, contentRef, targetWidth, targetHeight, placeholderRes);
            return;
        }
        setPlaceholder(target, placeholderRes);
    }

    public void loadProgressiveImage(ImageView target, String thumbnailRef, String fullRef, int targetWidth,
            int targetHeight, int placeholderRes, boolean animateIfSupported) {
        if (fullRef == null || fullRef.isEmpty()) {
            loadContentRef(target, thumbnailRef, targetWidth, targetHeight, placeholderRes, animateIfSupported);
            return;
        }
        String key = "progressive|" + cacheKey(fullRef, targetWidth, targetHeight) + "|" + animateIfSupported;
        if (key.equals(target.getTag()) && inFlight.containsKey(target)) {
            return;
        }
        cancel(target);
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Bitmap cachedThumbnail = cachedBitmap(thumbnailRef, targetWidth, targetHeight);
        if (cachedThumbnail != null) {
            target.setImageBitmap(cachedThumbnail);
        } else {
            target.setImageResource(placeholderRes);
        }
        Future<?> future = executors.image().submit(() -> {
            if (isFullImageLocallyAvailable(fullRef)) {
                if (animateIfSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Drawable drawable = decodeCachedAnimatedFullImage(fullRef, targetWidth, targetHeight);
                    if (drawable != null) {
                        deliverDrawable(target, key, drawable);
                        return;
                    }
                }
                Bitmap bitmap = decodeCachedFullImage(fullRef, targetWidth, targetHeight);
                if (bitmap != null) {
                    cache.put(key, bitmap);
                    deliver(target, key, bitmap);
                    return;
                }
            }
            deliverThumbnailIfAvailable(target, key, thumbnailRef, fullRef, targetWidth, targetHeight);
            if (animateIfSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Drawable drawable = decodeCachedAnimatedFullImage(fullRef, targetWidth, targetHeight);
                if (drawable != null) {
                    deliverDrawable(target, key, drawable);
                    return;
                }
            }
            Bitmap bitmap = decodeCachedFullImage(fullRef, targetWidth, targetHeight);
            if (bitmap == null) {
                finish(target, key);
                return;
            }
            cache.put(key, bitmap);
            deliver(target, key, bitmap);
        });
        inFlight.put(target, future);
    }

    public void loadContentUri(ImageView target, Uri uri, int targetWidth, int targetHeight, int placeholderRes) {
        cancel(target);
        target.setImageResource(placeholderRes);
        String key = cacheKey(uri.toString(), targetWidth, targetHeight);
        String sourceKey = sessionThumbnailKey(uri.toString());
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Bitmap sessionCached = sessionThumbnailCache.get(sourceKey);
        if (sessionCached == null) {
            sessionCached = sessionThumbnailCache.get(key);
        }
        if (sessionCached != null) {
            cache.put(key, sessionCached);
            target.setImageBitmap(sessionCached);
            return;
        }
        Future<?> future = executors.image().submit(() -> {
            Bitmap diskCached = readDiskCache(key, targetWidth, targetHeight);
            if (diskCached != null) {
                rememberSessionThumbnail(sourceKey, diskCached, targetWidth, targetHeight);
                rememberSessionThumbnail(key, diskCached, targetWidth, targetHeight);
                cache.put(key, diskCached);
                deliver(target, key, diskCached);
                return;
            }
            Bitmap bitmap = decode(uri, targetWidth, targetHeight);
            if (bitmap == null) {
                finish(target, key);
                return;
            }
            rememberSessionThumbnail(sourceKey, bitmap, targetWidth, targetHeight);
            rememberSessionThumbnail(key, bitmap, targetWidth, targetHeight);
            cache.put(key, bitmap);
            writeDiskCache(key, bitmap, targetWidth, targetHeight);
            deliver(target, key, bitmap);
        });
        inFlight.put(target, future);
    }

    private void loadHttpUrl(ImageView target, String url, int targetWidth, int targetHeight, int placeholderRes) {
        cancel(target);
        target.setImageResource(placeholderRes);
        String key = cacheKey(url, targetWidth, targetHeight);
        String sourceKey = sessionThumbnailKey(url);
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Bitmap sessionCached = sessionThumbnailCache.get(sourceKey);
        if (sessionCached == null) {
            sessionCached = sessionThumbnailCache.get(key);
        }
        if (sessionCached != null) {
            cache.put(key, sessionCached);
            target.setImageBitmap(sessionCached);
            return;
        }
        Future<?> future = executors.image().submit(() -> {
            Bitmap diskCached = readDiskCache(key, targetWidth, targetHeight);
            if (diskCached != null) {
                rememberSessionThumbnail(sourceKey, diskCached, targetWidth, targetHeight);
                rememberSessionThumbnail(key, diskCached, targetWidth, targetHeight);
                cache.put(key, diskCached);
                deliver(target, key, diskCached);
                return;
            }
            Bitmap bitmap = decodeHttp(url, targetWidth, targetHeight);
            if (bitmap == null) {
                finish(target, key);
                return;
            }
            rememberSessionThumbnail(sourceKey, bitmap, targetWidth, targetHeight);
            rememberSessionThumbnail(key, bitmap, targetWidth, targetHeight);
            cache.put(key, bitmap);
            writeDiskCache(key, bitmap, targetWidth, targetHeight);
            deliver(target, key, bitmap);
        });
        inFlight.put(target, future);
    }

    public void setPlaceholder(ImageView target, int placeholderRes) {
        cancel(target);
        target.setTag(null);
        target.setImageResource(placeholderRes);
    }

    public void cancel(ImageView target) {
        Future<?> future = inFlight.remove(target);
        if (future != null) {
            future.cancel(true);
        }
    }

    public static String cacheKey(String contentRef, int targetWidth, int targetHeight) {
        return contentRef + "|" + Math.max(1, targetWidth) + "x" + Math.max(1, targetHeight);
    }

    public static boolean isAnimatedGif(String mime, String filename, String contentRef) {
        if (mime != null && "image/gif".equalsIgnoreCase(mime.trim())) {
            return true;
        }
        return hasGifExtension(filename) || hasGifExtension(contentRef);
    }

    private static boolean hasGifExtension(String value) {
        if (value == null) {
            return false;
        }
        String clean = value.toLowerCase(Locale.US);
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        int fragment = clean.indexOf('#');
        if (fragment >= 0) {
            clean = clean.substring(0, fragment);
        }
        return clean.endsWith(".gif");
    }

    private void deliver(ImageView target, String key, Bitmap bitmap) {
        deliver(target, key, bitmap, true);
    }

    private void deliver(ImageView target, String key, Bitmap bitmap, boolean finalDelivery) {
        executors.main().execute(() -> {
            Object tag = target.getTag();
            if (key.equals(tag)) {
                target.setImageBitmap(bitmap);
                if (finalDelivery) {
                    inFlight.remove(target);
                }
            }
        });
    }

    private void finish(ImageView target, String key) {
        executors.main().execute(() -> {
            Object tag = target.getTag();
            if (key.equals(tag)) {
                inFlight.remove(target);
            }
        });
    }

    private void deliverThumbnailIfAvailable(ImageView target, String key, String thumbnailRef, String fullRef,
            int targetWidth, int targetHeight) {
        if (thumbnailRef == null || thumbnailRef.isEmpty() || thumbnailRef.equals(fullRef)) {
            return;
        }
        String thumbnailKey = cacheKey(thumbnailRef, targetWidth, targetHeight);
        String sourceKey = sessionThumbnailKey(thumbnailRef);
        Bitmap thumbnail = cachedBitmap(thumbnailRef, targetWidth, targetHeight);
        if (thumbnail == null) {
            thumbnail = readDiskCache(thumbnailKey, targetWidth, targetHeight);
            if (thumbnail != null) {
                rememberSessionThumbnail(sourceKey, thumbnail, targetWidth, targetHeight);
                rememberSessionThumbnail(thumbnailKey, thumbnail, targetWidth, targetHeight);
                cache.put(thumbnailKey, thumbnail);
            }
        }
        if (thumbnail == null) {
            thumbnail = decodeImageRef(thumbnailRef, targetWidth, targetHeight);
            if (thumbnail != null) {
                rememberSessionThumbnail(sourceKey, thumbnail, targetWidth, targetHeight);
                rememberSessionThumbnail(thumbnailKey, thumbnail, targetWidth, targetHeight);
                cache.put(thumbnailKey, thumbnail);
                writeDiskCache(thumbnailKey, thumbnail, targetWidth, targetHeight);
            }
        }
        if (thumbnail != null) {
            deliver(target, key, thumbnail, false);
        }
    }

    private Bitmap cachedBitmap(String contentRef, int targetWidth, int targetHeight) {
        if (contentRef == null || contentRef.isEmpty()) {
            return null;
        }
        String key = cacheKey(contentRef, targetWidth, targetHeight);
        String sourceKey = sessionThumbnailKey(contentRef);
        Bitmap bitmap = cache.get(key);
        if (bitmap == null) {
            bitmap = sessionThumbnailCache.get(sourceKey);
            if (bitmap == null) {
                bitmap = sessionThumbnailCache.get(key);
            }
            if (bitmap != null) {
                cache.put(key, bitmap);
            }
        }
        return bitmap;
    }

    private void deliverDrawable(ImageView target, String key, Drawable drawable) {
        executors.main().execute(() -> {
            Object tag = target.getTag();
            if (key.equals(tag)) {
                target.setImageDrawable(drawable);
                if (drawable instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) drawable).start();
                }
                inFlight.remove(target);
            }
        });
    }

    private void loadAnimatedContentRef(ImageView target, String contentRef, int targetWidth, int targetHeight,
            int placeholderRes) {
        cancel(target);
        target.setImageResource(placeholderRes);
        String key = "animated|" + cacheKey(contentRef, targetWidth, targetHeight);
        target.setTag(key);
        Uri uri = Uri.parse(contentRef);
        String scheme = uri.getScheme();
        Future<?> future = executors.image().submit(() -> {
            Drawable drawable = null;
            if ("http".equals(scheme) || "https".equals(scheme)) {
                drawable = decodeAnimatedHttp(contentRef, targetWidth, targetHeight);
            } else if ("content".equals(scheme) || "file".equals(scheme)) {
                drawable = decodeAnimatedUri(uri, targetWidth, targetHeight);
            }
            if (drawable != null) {
                deliverDrawable(target, key, drawable);
            } else {
                Bitmap bitmap = null;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    bitmap = decodeHttp(contentRef, targetWidth, targetHeight);
                } else if ("content".equals(scheme) || "file".equals(scheme)) {
                    bitmap = decode(uri, targetWidth, targetHeight);
                }
                if (bitmap != null) {
                    deliver(target, key, bitmap);
                } else {
                    finish(target, key);
                }
            }
        });
        inFlight.put(target, future);
    }

    private Drawable decodeAnimatedUri(Uri uri, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(contentResolver, uri);
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) ->
                    configureImageDecoder(decoder, info.getSize(), targetWidth, targetHeight));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Drawable decodeAnimatedHttp(String url, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || httpClient == null) {
            return null;
        }
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .header("Accept", "image/gif,image/*,*/*")
                    .get()
                    .build();
        } catch (IllegalArgumentException e) {
            return null;
        }
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            ResponseBody body = response.body();
            long length = body.contentLength();
            if (length > ANIMATED_IMAGE_MAX_BYTES) {
                return null;
            }
            byte[] bytes = body.bytes();
            if (bytes.length > ANIMATED_IMAGE_MAX_BYTES) {
                return null;
            }
            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) ->
                    configureImageDecoder(decoder, info.getSize(), targetWidth, targetHeight));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void configureImageDecoder(ImageDecoder decoder, Size sourceSize, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        int sample = sampleSize(sourceSize.getWidth(), sourceSize.getHeight(), targetWidth, targetHeight);
        decoder.setTargetSampleSize(sample);
    }

    private Bitmap decodeImageRef(String contentRef, int targetWidth, int targetHeight) {
        Uri uri = Uri.parse(contentRef);
        String scheme = uri.getScheme();
        if ("content".equals(scheme) || "file".equals(scheme)) {
            return decode(uri, targetWidth, targetHeight);
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && httpClient != null) {
            return decodeHttp(contentRef, targetWidth, targetHeight);
        }
        return null;
    }

    private Bitmap decodeCachedFullImage(String contentRef, int targetWidth, int targetHeight) {
        Uri uri = Uri.parse(contentRef);
        String scheme = uri.getScheme();
        if ("content".equals(scheme) || "file".equals(scheme)) {
            return decode(uri, targetWidth, targetHeight);
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        File file = cachedFullImageFile(contentRef);
        if (file == null) {
            return httpClient == null ? null : decodeHttp(contentRef, targetWidth, targetHeight);
        }
        if (!file.isFile() || file.length() == 0) {
            if (httpClient == null || !downloadHttpToFile(contentRef, file, "image/*,*/*")) {
                return null;
            }
        }
        Bitmap bitmap = decode(file, targetWidth, targetHeight);
        if (bitmap == null) {
            file.delete();
            return null;
        }
        file.setLastModified(System.currentTimeMillis());
        trimDiskCache();
        return bitmap;
    }

    private Drawable decodeCachedAnimatedFullImage(String contentRef, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        Uri uri = Uri.parse(contentRef);
        String scheme = uri.getScheme();
        if ("content".equals(scheme) || "file".equals(scheme)) {
            return decodeAnimatedUri(uri, targetWidth, targetHeight);
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        File file = cachedFullImageFile(contentRef);
        if (file == null) {
            return decodeAnimatedHttp(contentRef, targetWidth, targetHeight);
        }
        if (!file.isFile() || file.length() == 0) {
            if (httpClient == null || !downloadHttpToFile(contentRef, file, "image/gif,image/*,*/*")) {
                return null;
            }
        }
        Drawable drawable = decodeAnimatedFile(file, targetWidth, targetHeight);
        if (drawable == null) {
            file.delete();
            return null;
        }
        file.setLastModified(System.currentTimeMillis());
        trimDiskCache();
        return drawable;
    }

    private Bitmap decode(Uri uri, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) {
                return null;
            }
            BitmapFactory.decodeStream(stream, null, bounds);
        } catch (IOException e) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds, targetWidth, targetHeight);
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) {
                return null;
            }
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (IOException e) {
            return null;
        }
    }

    private Bitmap decode(File file, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds, targetWidth, targetHeight);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private Drawable decodeAnimatedFile(File file, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) ->
                    configureImageDecoder(decoder, info.getSize(), targetWidth, targetHeight));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Bitmap decodeHttp(String url, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (decodeHttp(url, bounds) == null && bounds.outWidth <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds, targetWidth, targetHeight);
        return decodeHttp(url, options);
    }

    private Bitmap decodeHttp(String url, BitmapFactory.Options options) {
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .header("Accept", "image/*,*/*")
                    .get()
                    .build();
        } catch (IllegalArgumentException e) {
            return null;
        }
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            try (InputStream stream = body.byteStream()) {
                return BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private int sampleSize(BitmapFactory.Options options, int targetWidth, int targetHeight) {
        return sampleSize(options.outWidth, options.outHeight, targetWidth, targetHeight);
    }

    private int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        int reqWidth = Math.max(1, targetWidth);
        int reqHeight = Math.max(1, targetHeight);
        while (height / sample > reqHeight * 2 || width / sample > reqWidth * 2) {
            sample *= 2;
        }
        return sample;
    }

    private Bitmap readDiskCache(String key, int targetWidth, int targetHeight) {
        File file = diskCacheFile(key, targetWidth, targetHeight);
        if (file == null || !file.isFile()) {
            return null;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            file.delete();
            return null;
        }
        file.setLastModified(System.currentTimeMillis());
        return bitmap;
    }

    private void writeDiskCache(String key, Bitmap bitmap, int targetWidth, int targetHeight) {
        File file = diskCacheFile(key, targetWidth, targetHeight);
        if (file == null || bitmap == null) {
            return;
        }
        File directory = file.getParentFile();
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            return;
        }
        File partial = new File(directory, file.getName() + ".partial");
        Bitmap.CompressFormat format = bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        int quality = format == Bitmap.CompressFormat.PNG ? 100 : JPEG_THUMBNAIL_QUALITY;
        try (FileOutputStream output = new FileOutputStream(partial)) {
            if (!bitmap.compress(format, quality, output)) {
                partial.delete();
                return;
            }
        } catch (IOException e) {
            partial.delete();
            return;
        }
        if (file.exists() && !file.delete()) {
            partial.delete();
            return;
        }
        if (!partial.renameTo(file)) {
            partial.delete();
            return;
        }
        file.setLastModified(System.currentTimeMillis());
        trimDiskCache();
    }

    private File diskCacheFile(String key, int targetWidth, int targetHeight) {
        if (diskCacheDirectory == null || !isDiskCacheEligible(targetWidth, targetHeight)) {
            return null;
        }
        return new File(diskCacheDirectory, sha256(key) + THUMB_CACHE_SUFFIX);
    }

    private File cachedFullImageFile(String contentRef) {
        if (diskCacheDirectory == null || contentRef == null || contentRef.isEmpty()) {
            return null;
        }
        return new File(diskCacheDirectory, sha256("full|" + contentRef) + FULL_IMAGE_CACHE_SUFFIX);
    }

    private boolean isFullImageLocallyAvailable(String contentRef) {
        if (contentRef == null || contentRef.isEmpty()) {
            return false;
        }
        Uri uri = Uri.parse(contentRef);
        String scheme = uri.getScheme();
        if ("content".equals(scheme) || "file".equals(scheme)) {
            return true;
        }
        File file = cachedFullImageFile(contentRef);
        return file != null && file.isFile() && file.length() > 0;
    }

    private boolean isDiskCacheEligible(int targetWidth, int targetHeight) {
        int width = Math.max(1, targetWidth);
        int height = Math.max(1, targetHeight);
        return Math.max(width, height) <= DISK_CACHE_MAX_DIMENSION_PX;
    }

    private void rememberSessionThumbnail(String key, Bitmap bitmap, int targetWidth, int targetHeight) {
        if (bitmap != null && isDiskCacheEligible(targetWidth, targetHeight)) {
            sessionThumbnailCache.put(key, bitmap);
        }
    }

    private String sessionThumbnailKey(String contentRef) {
        return "thumbnail|" + contentRef;
    }

    private void trimDiskCache() {
        if (diskCacheDirectory == null || !diskCacheDirectory.isDirectory()) {
            return;
        }
        File[] files = diskCacheDirectory.listFiles(file -> file.isFile()
                && (file.getName().endsWith(THUMB_CACHE_SUFFIX)
                || file.getName().endsWith(FULL_IMAGE_CACHE_SUFFIX)));
        if (files == null || files.length == 0) {
            return;
        }
        long total = 0;
        for (File file : files) {
            total += Math.max(0, file.length());
        }
        if (total <= DISK_CACHE_MAX_BYTES) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= DISK_CACHE_MAX_BYTES) {
                return;
            }
            long size = Math.max(0, file.length());
            if (file.delete()) {
                total -= size;
            }
        }
    }

    private boolean downloadHttpToFile(String url, File target, String acceptHeader) {
        File directory = target.getParentFile();
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            return false;
        }
        File partial = new File(directory, target.getName() + ".partial");
        if (partial.exists() && !partial.delete()) {
            return false;
        }
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .header("Accept", acceptHeader)
                    .get()
                    .build();
        } catch (IllegalArgumentException e) {
            return false;
        }
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            try (InputStream input = response.body().byteStream();
                    OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        partial.delete();
                        return false;
                    }
                    int read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            partial.delete();
            return false;
        }
        if (target.exists() && !target.delete()) {
            partial.delete();
            return false;
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            return false;
        }
        target.setLastModified(System.currentTimeMillis());
        return true;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
