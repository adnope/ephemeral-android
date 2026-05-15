package com.ephemeral.android.ui.common;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import com.ephemeral.android.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Future;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class ImageLoader {
    private static final int DISK_CACHE_MAX_DIMENSION_PX = 512;
    private static final long DISK_CACHE_MAX_BYTES = 96L * 1024L * 1024L;
    private static final int JPEG_THUMBNAIL_QUALITY = 85;

    private final ContentResolver contentResolver;
    private final AppExecutors executors;
    private final OkHttpClient httpClient;
    private final File diskCacheDirectory;
    private final LruCache<String, Bitmap> cache;
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
        if (contentRef == null || contentRef.isEmpty()) {
            setPlaceholder(target, placeholderRes);
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

    public void loadContentUri(ImageView target, Uri uri, int targetWidth, int targetHeight, int placeholderRes) {
        cancel(target);
        target.setImageResource(placeholderRes);
        String key = cacheKey(uri.toString(), targetWidth, targetHeight);
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Future<?> future = executors.image().submit(() -> {
            Bitmap diskCached = readDiskCache(key, targetWidth, targetHeight);
            if (diskCached != null) {
                cache.put(key, diskCached);
                deliver(target, key, diskCached);
                return;
            }
            Bitmap bitmap = decode(uri, targetWidth, targetHeight);
            if (bitmap == null) {
                return;
            }
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
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Future<?> future = executors.image().submit(() -> {
            Bitmap diskCached = readDiskCache(key, targetWidth, targetHeight);
            if (diskCached != null) {
                cache.put(key, diskCached);
                deliver(target, key, diskCached);
                return;
            }
            Bitmap bitmap = decodeHttp(url, targetWidth, targetHeight);
            if (bitmap == null) {
                return;
            }
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

    private void deliver(ImageView target, String key, Bitmap bitmap) {
        executors.main().execute(() -> {
            Object tag = target.getTag();
            if (key.equals(tag)) {
                target.setImageBitmap(bitmap);
                inFlight.remove(target);
            }
        });
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
        int sample = 1;
        int width = options.outWidth;
        int height = options.outHeight;
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
        return new File(diskCacheDirectory, sha256(key) + ".thumb");
    }

    private boolean isDiskCacheEligible(int targetWidth, int targetHeight) {
        int width = Math.max(1, targetWidth);
        int height = Math.max(1, targetHeight);
        return Math.max(width, height) <= DISK_CACHE_MAX_DIMENSION_PX;
    }

    private void trimDiskCache() {
        if (diskCacheDirectory == null || !diskCacheDirectory.isDirectory()) {
            return;
        }
        File[] files = diskCacheDirectory.listFiles(file -> file.isFile() && file.getName().endsWith(".thumb"));
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
