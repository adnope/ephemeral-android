package com.ephemeral.android.data.api;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.ephemeral.android.AppExecutors;
import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemTypeFilter;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.RecentFilter;
import com.ephemeral.android.data.model.PublicLink;
import com.ephemeral.android.data.model.VisibilityFilter;
import com.ephemeral.android.data.session.SessionRepository;
import com.ephemeral.android.util.SimpleJsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

public final class OkHttpEphemeralApi implements EphemeralApi {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String DOWNLOADS_RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS;

    private final Context context;
    private final OkHttpClient client;
    private final AppExecutors executors;
    private final SessionRepository sessionRepository;
    private final SessionCookieStore cookieStore;

    public OkHttpEphemeralApi(Context context, OkHttpClient client, AppExecutors executors,
            SessionRepository sessionRepository, SessionCookieStore cookieStore) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.executors = executors;
        this.sessionRepository = sessionRepository;
        this.cookieStore = cookieStore;
    }

    @Override
    public void getServerState(ApiCallback<ServerState> callback) {
        if (currentBaseUrlRaw().isEmpty()) {
            postSuccess(callback, new ServerState(false));
            return;
        }
        Request request;
        try {
            request = jsonRequest("api/auth/state").get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parseServerState(body));
    }

    @Override
    public void createFirstAccount(String username, String password, ApiCallback<AuthResult> callback) {
        login(username, password, callback);
    }

    @Override
    public void login(String username, String password, ApiCallback<AuthResult> callback) {
        Request request;
        try {
            request = jsonRequest("api/login")
                    .post(RequestBody.create(authJson(username, password), JSON))
                    .build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> {
            AuthResult result = ApiJsonParser.parseAuthResult(body, username);
            if (!result.isAuthenticated()) {
                throw new ApiError(ApiErrorCategory.UNAUTHENTICATED, "Invalid credentials.");
            }
            String authenticatedUsername = result.getUsername().isEmpty() ? username : result.getUsername();
            sessionRepository.markAuthenticated(authenticatedUsername);
            return new AuthResult(true, authenticatedUsername);
        });
    }

    @Override
    public void logout(ApiCallback<Void> callback) {
        Request request;
        try {
            HttpUrl url = endpoint("api/logout");
            Request.Builder builder = jsonRequest(url)
                    .post(RequestBody.create(new byte[0], null));
            String cookieHeader = cookieHeader(cookieStore.loadForRequest(url));
            if (!cookieHeader.isEmpty()) {
                builder.header("Cookie", cookieHeader);
            }
            request = builder.build();
        } catch (ApiError error) {
            clearLocalSession();
            postSuccess(callback, null);
            return;
        }
        clearLocalSession();
        executeVoid(request, callback, false);
    }

    @Override
    public void validateSession(ApiCallback<AuthResult> callback) {
        Request request;
        try {
            request = jsonRequest("api/config").get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> new AuthResult(true, sessionRepository.getUsername()));
    }

    @Override
    public void getRuntimeConfig(ApiCallback<RuntimeConfig> callback) {
        Request request;
        try {
            request = jsonRequest("api/config").get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parseRuntimeConfig(body));
    }

    @Override
    public void loadChatPage(long cursor, ApiCallback<Page<Item>> callback) {
        Request request;
        String baseUrl;
        try {
            baseUrl = baseUrl().toString();
            HttpUrl url = endpointBuilder("api/items")
                    .addQueryParameter("cursor", String.valueOf(Math.max(0, cursor)))
                    .build();
            request = jsonRequest(url).get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parseItemPage(body, baseUrl));
    }

    @Override
    public void sendTextMessage(String text, ApiCallback<Item> callback) {
        Request request;
        String baseUrl;
        try {
            baseUrl = baseUrl().toString();
            request = jsonRequest("api/message")
                    .post(RequestBody.create(textJson(text), JSON))
                    .build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parseItem(body, baseUrl));
    }

    @Override
    public Cancellable uploadFile(UploadRequest request, UploadProgressListener progress, ApiCallback<Item> callback) {
        CallCancellable cancellable = new CallCancellable();
        executors.network().execute(() -> uploadFileInternal(request, progress, callback, cancellable));
        return cancellable;
    }

    @Override
    public void deleteItem(long itemId, ApiCallback<Void> callback) {
        Request request;
        try {
            request = jsonRequest("api/items/" + itemId).delete().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeVoid(request, callback, false);
    }

    @Override
    public void loadHistoryPage(HistoryQuery query, ApiCallback<Page<Item>> callback) {
        Request request;
        String baseUrl;
        try {
            baseUrl = baseUrl().toString();
            request = jsonRequest(historyUrl(query)).get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parseItemPage(body, baseUrl));
    }

    @Override
    public void loadTextPreview(long itemId, ApiCallback<FilePreview> callback) {
        Request request;
        String baseUrl;
        try {
            baseUrl = baseUrl().toString();
            request = jsonRequest("api/file-preview/" + itemId).get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parseFilePreview(body, baseUrl));
    }

    @Override
    public Cancellable downloadFile(FileDownloadRequest request, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback) {
        CallCancellable cancellable = new CallCancellable();
        executors.network().execute(() -> downloadFileInternal(request, progress, callback, cancellable));
        return cancellable;
    }

    @Override
    public Cancellable downloadZip(String ids, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback) {
        CallCancellable cancellable = new CallCancellable();
        executors.network().execute(() -> downloadZipInternal(ids, progress, callback, cancellable));
        return cancellable;
    }

    @Override
    public void getPublicLink(long itemId, ApiCallback<PublicLink> callback) {
        Request request;
        String baseUrl;
        try {
            baseUrl = baseUrl().toString();
            request = jsonRequest("api/items/" + itemId + "/public-link").get().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parsePublicLink(body, baseUrl));
    }

    @Override
    public void createPublicLink(long itemId, Long expiresInSeconds, ApiCallback<PublicLink> callback) {
        Request request;
        String baseUrl;
        try {
            baseUrl = baseUrl().toString();
            request = jsonRequest("api/items/" + itemId + "/public-link")
                    .post(RequestBody.create(publicLinkRequestJson(expiresInSeconds), JSON))
                    .build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeJson(request, callback, body -> ApiJsonParser.parsePublicLink(body, baseUrl));
    }

    @Override
    public void revokePublicLink(long itemId, ApiCallback<Void> callback) {
        Request request;
        try {
            request = jsonRequest("api/items/" + itemId + "/public-link").delete().build();
        } catch (ApiError error) {
            postError(callback, error);
            return;
        }
        executeVoid(request, callback, false);
    }

    @Override
    public EventSubscription observeItemEvents(ItemEventListener listener) {
        try {
            SseSubscription subscription = new SseSubscription(endpoint("api/events"), listener);
            subscription.start();
            return subscription;
        } catch (ApiError error) {
            executors.main().execute(() -> listener.onError(error));
            return () -> {
            };
        }
    }

    private void uploadFileInternal(UploadRequest uploadRequest, UploadProgressListener progress,
            ApiCallback<Item> callback, CallCancellable cancellable) {
        try {
            String baseUrl = baseUrl().toString();
            ProgressRequestBody fileBody = new ProgressRequestBody(
                    context.getContentResolver(),
                    uploadRequest,
                    progress,
                    cancellable);
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", uploadRequest.getDisplayName(), fileBody)
                    .build();
            Request httpRequest = jsonRequest("api/upload").post(body).build();
            Call call = client.newCall(httpRequest);
            cancellable.setCall(call);
            try (Response response = call.execute()) {
                String responseBody = readBody(response);
                if (!response.isSuccessful()) {
                    throw errorFromResponse(response, responseBody);
                }
                postSuccess(callback, ApiJsonParser.parseItem(responseBody, baseUrl));
            }
        } catch (ApiError error) {
            postError(callback, error);
        } catch (IOException error) {
            postError(callback, errorFromIOException(error, cancellable));
        } catch (RuntimeException error) {
            postError(callback, new ApiError(ApiErrorCategory.UNKNOWN, "Upload response could not be parsed.", 0, error));
        }
    }

    private void downloadFileInternal(FileDownloadRequest downloadRequest, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback, CallCancellable cancellable) {
        File partial = null;
        try {
            HttpUrl url = resolveContentUrl(downloadRequest.getContentRef());
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Accept", "*/*")
                    .get()
                    .build();
            Call call = client.newCall(httpRequest);
            cancellable.setCall(call);
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    throw errorFromResponse(response, readBody(response));
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new ApiError(ApiErrorCategory.SERVER_ERROR, "Download response was empty.",
                            response.code(), null);
                }
                String filename = safeFilename(downloadRequest.getFilename());
                FileDownloadResult result = saveDownload(body, response, filename, progress, cancellable);
                postSuccess(callback, result);
            }
        } catch (ApiError error) {
            deletePartial(partial);
            postError(callback, error);
        } catch (IOException error) {
            deletePartial(partial);
            postError(callback, errorFromIOException(error, cancellable));
        }
    }

    private void downloadZipInternal(String ids, DownloadProgressListener progress,
            ApiCallback<FileDownloadResult> callback, CallCancellable cancellable) {
        File partial = null;
        try {
            HttpUrl url = endpointBuilder("api/items/download-zip")
                    .addQueryParameter("ids", ids)
                    .build();
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Accept", "*/*")
                    .get()
                    .build();
            Call call = client.newCall(httpRequest);
            cancellable.setCall(call);
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    throw errorFromResponse(response, readBody(response));
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new ApiError(ApiErrorCategory.SERVER_ERROR, "Download response was empty.",
                            response.code(), null);
                }
                String filename = filenameFromResponse(response, "ephemeral_download.zip");
                filename = safeFilename(filename);
                FileDownloadResult result = saveDownload(body, response, filename, progress, cancellable);
                postSuccess(callback, result);
            }
        } catch (ApiError error) {
            deletePartial(partial);
            postError(callback, error);
        } catch (IOException error) {
            deletePartial(partial);
            postError(callback, errorFromIOException(error, cancellable));
        }
    }

    private <T> void executeJson(Request request, ApiCallback<T> callback, JsonParser<T> parser) {
        executors.network().execute(() -> {
            try (Response response = client.newCall(request).execute()) {
                String body = readBody(response);
                if (!response.isSuccessful()) {
                    throw errorFromResponse(response, body);
                }
                postSuccess(callback, parser.parse(body));
            } catch (ApiError error) {
                postError(callback, error);
            } catch (IOException error) {
                postError(callback, errorFromIOException(error, null));
            } catch (RuntimeException error) {
                postError(callback, new ApiError(ApiErrorCategory.UNKNOWN,
                        "Server response could not be parsed.", 0, error));
            }
        });
    }

    private void executeVoid(Request request, ApiCallback<Void> callback, boolean clearSession) {
        executors.network().execute(() -> {
            try (Response response = client.newCall(request).execute()) {
                String body = readBody(response);
                if (!response.isSuccessful() && response.code() != 404) {
                    throw errorFromResponse(response, body);
                }
                if (clearSession) {
                    clearLocalSession();
                }
                postSuccess(callback, null);
            } catch (ApiError error) {
                if (clearSession) {
                    clearLocalSession();
                }
                postError(callback, error);
            } catch (IOException error) {
                if (clearSession) {
                    clearLocalSession();
                }
                postError(callback, errorFromIOException(error, null));
            }
        });
    }

    private HttpUrl historyUrl(HistoryQuery query) throws ApiError {
        HttpUrl.Builder builder = endpointBuilder("api/history")
                .addQueryParameter("cursor", String.valueOf(query.getCursor()));
        if (query.getTypeFilter() != ItemTypeFilter.ALL) {
            builder.addQueryParameter("type", typeFilterValue(query.getTypeFilter()));
        }
        if (!query.getQuery().isEmpty()) {
            builder.addQueryParameter("q", query.getQuery());
        }
        if (query.isSearchBody()) {
            builder.addQueryParameter("body", "1");
        }
        if (!query.getDateFromIso().isEmpty()) {
            builder.addQueryParameter("from", query.getDateFromIso());
        }
        if (!query.getDateToIso().isEmpty()) {
            builder.addQueryParameter("to", query.getDateToIso());
        }
        RecentFilter recent = query.getRecent();
        if (recent != RecentFilter.ANY_TIME && !recent.getWireValue().isEmpty()) {
            builder.addQueryParameter("recent", recent.getWireValue());
        }
        VisibilityFilter visibility = query.getVisibility();
        if (visibility != VisibilityFilter.ALL && !visibility.getWireValue().isEmpty()) {
            builder.addQueryParameter("visibility", visibility.getWireValue());
        }
        return builder.build();
    }

    private String typeFilterValue(ItemTypeFilter filter) {
        if (filter == ItemTypeFilter.IMAGES) {
            return "image";
        }
        if (filter == ItemTypeFilter.VIDEOS) {
            return "video";
        }
        return "file";
    }

    private String cookieHeader(List<Cookie> cookies) {
        if (cookies.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie.name()).append('=').append(cookie.value());
        }
        return builder.toString();
    }

    private Request.Builder jsonRequest(String path) throws ApiError {
        return jsonRequest(endpoint(path));
    }

    private Request.Builder jsonRequest(HttpUrl url) {
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/json");
    }

    private HttpUrl endpoint(String path) throws ApiError {
        return endpointBuilder(path).build();
    }

    private HttpUrl.Builder endpointBuilder(String path) throws ApiError {
        HttpUrl.Builder builder = baseUrl().newBuilder();
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                builder.addPathSegment(segment);
            }
        }
        return builder;
    }

    private HttpUrl resolveContentUrl(String contentRef) throws ApiError {
        String cleanRef = contentRef == null ? "" : contentRef.trim();
        if (cleanRef.isEmpty()) {
            throw new ApiError(ApiErrorCategory.VALIDATION_ERROR, "Download URL is unavailable.");
        }
        HttpUrl resolved = baseUrl().resolve(cleanRef);
        if (resolved == null) {
            throw new ApiError(ApiErrorCategory.VALIDATION_ERROR, "Download URL is invalid.");
        }
        return resolved;
    }

    private HttpUrl baseUrl() throws ApiError {
        String raw = currentBaseUrlRaw();
        if (raw.isEmpty()) {
            throw new ApiError(ApiErrorCategory.VALIDATION_ERROR, "Server URL is required.");
        }
        try {
            return HttpUrl.get(raw);
        } catch (IllegalArgumentException error) {
            throw new ApiError(ApiErrorCategory.VALIDATION_ERROR,
                    "Server URL must start with http:// or https://.", 0, error);
        }
    }

    private String currentBaseUrlRaw() {
        return sessionRepository.getServerBaseUrl("").trim();
    }

    private String readBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private ApiError errorFromResponse(Response response, String body) {
        ErrorPayload payload = parseErrorPayload(body);
        ApiErrorCategory category = categoryFromStatusAndCode(response.code(), payload.code);
        String message = payload.message.isEmpty() ? defaultMessage(category, response.code()) : payload.message;
        return new ApiError(category, message, response.code(), null);
    }

    private ErrorPayload parseErrorPayload(String body) {
        if (body == null || body.isEmpty()) {
            return new ErrorPayload("", "");
        }
        try {
            Map<String, Object> object = SimpleJsonParser.parseObject(body);
            return new ErrorPayload(stringValue(object.get("code")), stringValue(object.get("message")));
        } catch (IllegalArgumentException error) {
            return new ErrorPayload("", "");
        }
    }

    private ApiErrorCategory categoryFromStatusAndCode(int status, String code) {
        String cleanCode = code == null ? "" : code.toLowerCase(Locale.US);
        if ("validation_error".equals(cleanCode)) {
            return ApiErrorCategory.VALIDATION_ERROR;
        }
        if ("unauthenticated".equals(cleanCode)) {
            return ApiErrorCategory.UNAUTHENTICATED;
        }
        if ("forbidden".equals(cleanCode)) {
            return ApiErrorCategory.FORBIDDEN;
        }
        if ("not_found".equals(cleanCode)) {
            return ApiErrorCategory.NOT_FOUND;
        }
        if ("payload_too_large".equals(cleanCode)) {
            return ApiErrorCategory.PAYLOAD_TOO_LARGE;
        }
        if ("unsupported_preview".equals(cleanCode)) {
            return ApiErrorCategory.UNSUPPORTED_PREVIEW;
        }
        if ("server_error".equals(cleanCode)) {
            return ApiErrorCategory.SERVER_ERROR;
        }
        if (status == 400 || status == 422) {
            return ApiErrorCategory.VALIDATION_ERROR;
        }
        if (status == 401) {
            return ApiErrorCategory.UNAUTHENTICATED;
        }
        if (status == 403) {
            return ApiErrorCategory.FORBIDDEN;
        }
        if (status == 404) {
            return ApiErrorCategory.NOT_FOUND;
        }
        if (status == 413) {
            return ApiErrorCategory.PAYLOAD_TOO_LARGE;
        }
        if (status == 415) {
            return ApiErrorCategory.UNSUPPORTED_PREVIEW;
        }
        if (status >= 500) {
            return ApiErrorCategory.SERVER_ERROR;
        }
        return ApiErrorCategory.UNKNOWN;
    }

    private String defaultMessage(ApiErrorCategory category, int status) {
        if (category == ApiErrorCategory.UNAUTHENTICATED) {
            return "Session expired. Sign in again.";
        }
        if (category == ApiErrorCategory.PAYLOAD_TOO_LARGE) {
            return "File exceeds server limit.";
        }
        if (category == ApiErrorCategory.UNSUPPORTED_PREVIEW) {
            return "Preview is not supported.";
        }
        if (category == ApiErrorCategory.NOT_FOUND) {
            return "Item not found.";
        }
        if (category == ApiErrorCategory.SERVER_ERROR) {
            return "Server error.";
        }
        return status > 0 ? "Request failed with HTTP " + status + "." : "Request failed.";
    }

    private ApiError errorFromIOException(IOException error, CallCancellable cancellable) {
        if ((cancellable != null && cancellable.isCanceled()) || "Canceled".equalsIgnoreCase(error.getMessage())) {
            return new ApiError(ApiErrorCategory.CANCELED, "Request canceled.", 0, error);
        }
        if (error instanceof SocketTimeoutException) {
            return new ApiError(ApiErrorCategory.TIMEOUT, "Request timed out.", 0, error);
        }
        return new ApiError(ApiErrorCategory.NETWORK_UNAVAILABLE,
                "Network request failed. Check the server URL and connection.", 0, error);
    }

    private String authJson(String username, String password) {
        return "{\"username\":" + quote(username) + ",\"password\":" + quote(password) + "}";
    }

    private String textJson(String text) {
        return "{\"text\":" + quote(text) + "}";
    }

    private String publicLinkRequestJson(Long expiresInSeconds) {
        return "{\"expires_in_seconds\":" + (expiresInSeconds == null ? "null" : String.valueOf(expiresInSeconds)) + "}";
    }

    private String quote(String value) {
        String clean = value == null ? "" : value;
        StringBuilder builder = new StringBuilder(clean.length() + 2);
        builder.append('"');
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private FileDownloadResult saveDownload(ResponseBody body, Response response, String filename,
            DownloadProgressListener progress, CallCancellable cancellable) throws IOException, ApiError {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveDownloadToMediaStore(body, response, filename, progress, cancellable);
        }
        return saveDownloadToAppExternalDownloads(body, filename, progress, cancellable);
    }

    private FileDownloadResult saveDownloadToMediaStore(ResponseBody body, Response response, String filename,
            DownloadProgressListener progress, CallCancellable cancellable) throws IOException, ApiError {
        ContentResolver resolver = context.getContentResolver();
        deleteExistingMediaStoreDownload(resolver, filename);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, downloadMime(response, body));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_RELATIVE_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create download entry.");
        }
        try {
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) {
                    throw new IOException("Could not open download output stream.");
                }
                streamDownload(body, output, progress, cancellable);
            }
            if (cancellable.isCanceled()) {
                throw new ApiError(ApiErrorCategory.CANCELED, "Download canceled.");
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
            return new FileDownloadResult(uri, filename);
        } catch (IOException | ApiError error) {
            resolver.delete(uri, null, null);
            throw error;
        }
    }

    private FileDownloadResult saveDownloadToAppExternalDownloads(ResponseBody body, String filename,
            DownloadProgressListener progress, CallCancellable cancellable) throws IOException, ApiError {
        File directory = downloadDirectory();
        File target = new File(directory, filename);
        File partial = new File(directory, filename + ".partial");
        try {
            if (partial.exists() && !partial.delete()) {
                throw new IOException("Could not replace partial download file.");
            }
            streamDownload(body, partial, progress, cancellable);
            if (cancellable.isCanceled()) {
                throw new ApiError(ApiErrorCategory.CANCELED, "Download canceled.");
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Could not replace existing download file.");
            }
            if (!partial.renameTo(target)) {
                throw new IOException("Could not finalize downloaded file.");
            }
        } catch (IOException | ApiError error) {
            deletePartial(partial);
            throw error;
        }
        return new FileDownloadResult(Uri.fromFile(target), filename);
    }

    private void deleteExistingMediaStoreDownload(ContentResolver resolver, String filename) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] args = {filename, DOWNLOADS_RELATIVE_PATH};
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                selection, args, null)) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            while (cursor.moveToNext()) {
                Uri existing = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn));
                resolver.delete(existing, null, null);
            }
        }
    }

    private String downloadMime(Response response, ResponseBody body) {
        MediaType responseType = body.contentType();
        if (responseType != null) {
            return responseType.toString();
        }
        String contentType = response.header("Content-Type", "");
        return contentType == null || contentType.trim().isEmpty() ? OCTET_STREAM.toString() : contentType.trim();
    }

    private void streamDownload(ResponseBody body, File target, DownloadProgressListener progress,
            CallCancellable cancellable) throws IOException, ApiError {
        try (OutputStream output = new FileOutputStream(target)) {
            streamDownload(body, output, progress, cancellable);
        }
    }

    private void streamDownload(ResponseBody body, OutputStream output, DownloadProgressListener progress,
            CallCancellable cancellable) throws IOException, ApiError {
        long total = body.contentLength();
        long downloaded = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = body.byteStream()) {
            while (true) {
                if (cancellable.isCanceled()) {
                    throw new ApiError(ApiErrorCategory.CANCELED, "Download canceled.");
                }
                int read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                output.write(buffer, 0, read);
                downloaded += read;
                long current = downloaded;
                executors.main().execute(() -> progress.onProgress(current, total));
            }
        }
    }

    private File downloadDirectory() throws IOException {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = new File(context.getCacheDir(), "downloads");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create download directory.");
        }
        return directory;
    }

    private File uniqueFile(File directory, String filename) {
        String safe = safeFilename(filename);
        File candidate = new File(directory, safe);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = safe.lastIndexOf('.');
        String name = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            candidate = new File(directory, name + "-" + i + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, name + "-" + System.currentTimeMillis() + extension);
    }

    private String safeFilename(String filename) {
        String clean = filename == null ? "" : filename.trim();
        if (clean.isEmpty()) {
            clean = "download";
        }
        clean = clean.replaceAll("[\\\\/\\p{Cntrl}]+", "_");
        if (".".equals(clean) || "..".equals(clean)) {
            return "download";
        }
        return clean;
    }

    private String filenameFromResponse(Response response, String fallback) {
        String disposition = response.header("Content-Disposition", "");
        String parsed = parseContentDispositionFilename(disposition);
        return parsed.isEmpty() ? fallback : parsed;
    }

    private String parseContentDispositionFilename(String disposition) {
        if (disposition == null || disposition.isEmpty()) {
            return "";
        }
        String[] parts = disposition.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.US).startsWith("filename*=")) {
                String value = trimmed.substring("filename*=".length()).trim();
                int marker = value.indexOf("''");
                if (marker >= 0) {
                    value = value.substring(marker + 2);
                }
                try {
                    return URLDecoder.decode(unquote(value), "UTF-8");
                } catch (UnsupportedEncodingException error) {
                    return unquote(value);
                }
            }
            if (trimmed.toLowerCase(Locale.US).startsWith("filename=")) {
                return unquote(trimmed.substring("filename=".length()).trim());
            }
        }
        return "";
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void deletePartial(File partial) {
        if (partial != null && partial.exists()) {
            partial.delete();
        }
    }

    private void clearLocalSession() {
        sessionRepository.clearSession();
        cookieStore.clear();
    }

    private <T> void postSuccess(ApiCallback<T> callback, T value) {
        executors.main().execute(() -> callback.onSuccess(value));
    }

    private <T> void postError(ApiCallback<T> callback, ApiError error) {
        executors.main().execute(() -> callback.onError(error));
    }

    private void postEventError(ItemEventListener listener, ApiError error) {
        executors.main().execute(() -> listener.onError(error));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public OkHttpClient getClient() {
        return client;
    }

    public OkHttpClient getClientForTests() {
        return client;
    }

    private interface JsonParser<T> {
        T parse(String body) throws ApiError;
    }

    private static final class ErrorPayload {
        final String code;
        final String message;

        ErrorPayload(String code, String message) {
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
        }
    }

    private static final class CallCancellable implements Cancellable {
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private volatile Call call;

        @Override
        public void cancel() {
            canceled.set(true);
            Call currentCall = call;
            if (currentCall != null) {
                currentCall.cancel();
            }
        }

        @Override
        public boolean isCanceled() {
            return canceled.get();
        }

        void setCall(Call nextCall) {
            call = nextCall;
            if (isCanceled() && nextCall != null) {
                nextCall.cancel();
            }
        }
    }

    private final class ProgressRequestBody extends RequestBody {
        private final ContentResolver contentResolver;
        private final UploadRequest request;
        private final UploadProgressListener progress;
        private final CallCancellable cancellable;
        private final MediaType contentType;

        ProgressRequestBody(ContentResolver contentResolver, UploadRequest request,
                UploadProgressListener progress, CallCancellable cancellable) {
            this.contentResolver = contentResolver;
            this.request = request;
            this.progress = progress;
            this.cancellable = cancellable;
            MediaType parsed = MediaType.parse(request.getMimeType());
            contentType = parsed == null ? OCTET_STREAM : parsed;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return request.getSizeBytes() >= 0 ? request.getSizeBytes() : -1;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            long total = contentLength();
            long uploaded = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = contentResolver.openInputStream(request.getSourceUri())) {
                if (input == null) {
                    throw new IOException("Could not open upload file.");
                }
                while (true) {
                    if (cancellable.isCanceled()) {
                        throw new IOException("Canceled");
                    }
                    int read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    sink.write(buffer, 0, read);
                    uploaded += read;
                    long current = uploaded;
                    executors.main().execute(() -> progress.onProgress(current, total));
                }
            }
        }
    }

    private final class SseSubscription implements EventSubscription, Runnable {
        private static final long INITIAL_BACKOFF_MS = 1_000L;
        private static final long MAX_BACKOFF_MS = 30_000L;

        private final HttpUrl url;
        private final ItemEventListener listener;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile Call call;
        private volatile Future<?> future;
        private boolean reportedConnectionError;

        SseSubscription(HttpUrl url, ItemEventListener listener) {
            this.url = url;
            this.listener = listener;
        }

        void start() {
            future = executors.network().submit(this);
        }

        @Override
        public void run() {
            long backoff = INITIAL_BACKOFF_MS;
            while (!stopped.get()) {
                Request request = new Request.Builder()
                        .url(url)
                        .header("Accept", "text/event-stream")
                        .get()
                        .build();
                call = client.newCall(request);
                try (Response response = call.execute()) {
                    if (!response.isSuccessful()) {
                        ApiError error = errorFromResponse(response, readBody(response));
                        reportConnectionError(error);
                        if (error.isAuthenticationFailure()) {
                            stop();
                            return;
                        }
                        sleepBeforeReconnect(backoff);
                        backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                        continue;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("Empty event stream.");
                    }
                    reportedConnectionError = false;
                    backoff = INITIAL_BACKOFF_MS;
                    readEvents(body.byteStream());
                    if (!stopped.get()) {
                        sleepBeforeReconnect(backoff);
                        backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                    }
                } catch (IOException error) {
                    if (!stopped.get()) {
                        reportConnectionError(errorFromIOException(error, null));
                        sleepBeforeReconnect(backoff);
                        backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                    }
                }
            }
        }

        private void reportConnectionError(ApiError error) {
            if (error.isAuthenticationFailure() || !reportedConnectionError) {
                postEventError(listener, error);
            }
            reportedConnectionError = true;
        }

        @Override
        public void stop() {
            stopped.set(true);
            Call currentCall = call;
            if (currentCall != null) {
                currentCall.cancel();
            }
            Future<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
        }

        private void readEvents(InputStream stream) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String eventName = "";
                StringBuilder data = new StringBuilder();
                String line;
                while (!stopped.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatchEvent(eventName, data.toString());
                        eventName = "";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            }
        }

        private void dispatchEvent(String eventName, String data) {
            ItemEventType type = eventType(eventName);
            if (type == null) {
                return;
            }
            long itemId = parseEventItemId(data);
            if (itemId <= 0) {
                return;
            }
            executors.main().execute(() -> listener.onEvent(new ItemEvent(type, itemId)));
        }

        private ItemEventType eventType(String eventName) {
            if ("item:new".equals(eventName)) {
                return ItemEventType.NEW;
            }
            if ("item:updated".equals(eventName)) {
                return ItemEventType.UPDATED;
            }
            if ("item:deleted".equals(eventName)) {
                return ItemEventType.DELETED;
            }
            return null;
        }

        private long parseEventItemId(String data) {
            String clean = data == null ? "" : data.trim();
            if (clean.isEmpty()) {
                return 0;
            }
            try {
                if (clean.startsWith("{")) {
                    Map<String, Object> object = SimpleJsonParser.parseObject(clean);
                    Object itemId = object.containsKey("itemId") ? object.get("itemId") : object.get("id");
                    return Long.parseLong(String.valueOf(itemId));
                }
                return Long.parseLong(clean);
            } catch (IllegalArgumentException error) {
                return 0;
            }
        }

        private void sleepBeforeReconnect(long backoff) {
            long jitter = Math.min(1_000L, Math.max(0L, backoff / 4L));
            long sleepMillis = backoff + (long) (Math.random() * (jitter + 1L));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                stopped.set(true);
            }
        }
    }
}
