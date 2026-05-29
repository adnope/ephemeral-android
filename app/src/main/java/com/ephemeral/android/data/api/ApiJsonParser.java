package com.ephemeral.android.data.api;

import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.PublicLink;
import com.ephemeral.android.util.SimpleJsonParser;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ApiJsonParser {
    private static final DateTimeFormatter BACKEND_PREVIEW_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    private ApiJsonParser() {
    }

    public static Item parseItem(String json) {
        return parseItem(json, "");
    }

    public static Item parseItem(String json, String baseUrl) {
        return itemFromObject(SimpleJsonParser.parseObject(json), baseUrl);
    }

    public static Page<Item> parseItemPage(String json) {
        return parseItemPage(json, "");
    }

    public static Page<Item> parseItemPage(String json, String baseUrl) {
        Map<String, Object> object = SimpleJsonParser.parseObject(json);
        List<Item> items = new ArrayList<>();
        for (Object value : getArray(object, "items")) {
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("items must contain objects");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> itemObject = (Map<String, Object>) value;
            items.add(itemFromObject(itemObject, baseUrl));
        }
        return new Page<>(items, getLong(object, "nextCursor", 0), getBoolean(object, "hasMore", false));
    }

    public static FilePreview parseFilePreview(String json) {
        return parseFilePreview(json, "");
    }

    public static FilePreview parseFilePreview(String json, String baseUrl) {
        Map<String, Object> object = SimpleJsonParser.parseObject(json);
        return new FilePreview(
                getLong(object, "id", 0),
                getString(object, "filename", ""),
                getString(object, "mime", ""),
                getString(object, "language", "plaintext"),
                getString(object, "content", ""),
                getLong(object, "filesizeBytes", getLong(object, "filesize", -1)),
                getCreatedAtEpochMillis(object),
                resolveRef(baseUrl, getString(object, "downloadRef", getString(object, "download_url", ""))));
    }

    public static AuthResult parseAuthResult(String json, String fallbackUsername) {
        Map<String, Object> object = SimpleJsonParser.parseObject(json);
        return new AuthResult(
                getBoolean(object, "authenticated", false),
                getString(object, "username", fallbackUsername));
    }

    public static RuntimeConfig parseRuntimeConfig(String json) {
        Map<String, Object> object = SimpleJsonParser.parseObject(json);
        return new RuntimeConfig(
                (int) getLong(object, "chatPageSize", 1),
                (int) getLong(object, "historyPageSize", 1),
                getLong(object, "maxUploadSizeBytes", 0),
                getLong(object, "textPreviewMaxBytes", 0),
                (int) getLong(object, "uploadConcurrency", 1));
    }

    public static ServerState parseServerState(String json) {
        Map<String, Object> object = SimpleJsonParser.parseObject(json);
        return new ServerState(getBoolean(object, "setupRequired", false));
    }

    public static PublicLink parsePublicLink(String json) {
        return parsePublicLink(json, "");
    }

    public static PublicLink parsePublicLink(String json, String baseUrl) {
        Map<String, Object> object = SimpleJsonParser.parseObject(json);
        String token = getString(object, "token", "");
        String status = getString(object, "status", token.isEmpty() ? "none" : "active");
        return new PublicLink(
                status,
                resolveRef(baseUrl, getString(object, "url", "")),
                token,
                getString(object, "expires_at", ""));
    }

    private static Item itemFromObject(Map<String, Object> object, String baseUrl) {
        Map<String, Object> metadataObject = getObject(object, "metadata");
        ItemType type = ItemType.fromWireName(getString(object, "type", "file"));
        String backendContentUrl = getString(object, "contentUrl", "");
        String backendDownloadUrl = getString(object, "downloadUrl", "");
        String contentRef = type == ItemType.TEXT
                ? getString(object, "text", getString(object, "contentRef", ""))
                : firstNonEmpty(getString(object, "contentRef", ""), backendContentUrl, backendDownloadUrl);
        ItemMetadata metadata = new ItemMetadata(
                (int) getLong(metadataObject, "width", 0),
                (int) getLong(metadataObject, "height", 0),
                getString(metadataObject, "duration", ""),
                getString(metadataObject, "mime", ""),
                resolveRef(baseUrl, getString(metadataObject, "thumbRef",
                        getString(metadataObject, "thumbnailUrl", getString(metadataObject, "thumb", "")))),
                resolveRef(baseUrl, getString(metadataObject, "playbackRef",
                        getString(metadataObject, "playbackUrl", getString(metadataObject, "playback", "")))),
                getString(metadataObject, "playbackMime", ""),
                resolveRef(baseUrl, getString(metadataObject, "hlsRef",
                        getString(metadataObject, "hlsUrl", getString(metadataObject, "hls", "")))),
                getBoolean(metadataObject, "processing", false));
        return new Item(
                getLong(object, "id", 0),
                type,
                type == ItemType.TEXT ? contentRef : resolveRef(baseUrl, contentRef),
                getString(object, "filename", ""),
                getLong(object, "filesizeBytes", -1),
                metadata,
                getCreatedAtEpochMillis(object),
                getBoolean(object, "previewable",
                        inferPreviewable(type, getString(object, "filename", ""), metadata.getMime())),
                getBoolean(object, "publicLinkActive", false));
    }

    private static Map<String, Object> getObject(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return java.util.Collections.emptyMap();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static List<Object> getArray(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return java.util.Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) value;
        return result;
    }

    private static String getString(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static long getLong(Map<String, Object> object, String key, long fallback) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static boolean getBoolean(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long getCreatedAtEpochMillis(Map<String, Object> object) {
        long millis = getLong(object, "createdAtEpochMillis", Long.MIN_VALUE);
        if (millis != Long.MIN_VALUE) {
            return millis;
        }
        String formatted = getString(object, "created_at", "");
        if (formatted.isEmpty()) {
            return 0;
        }
        try {
            return LocalDateTime.parse(formatted, BACKEND_PREVIEW_DATE)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private static String firstNonEmpty(String first, String second, String third) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        if (second != null && !second.isEmpty()) {
            return second;
        }
        return third == null ? "" : third;
    }

    private static String resolveRef(String baseUrl, String ref) {
        if (ref == null || ref.isEmpty() || isAlreadyResolved(ref)) {
            return ref == null ? "" : ref;
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ref;
        }
        try {
            URI base = new URI(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            return base.resolve(ref).toString();
        } catch (IllegalArgumentException | URISyntaxException e) {
            return ref;
        }
    }

    private static boolean isAlreadyResolved(String ref) {
        return ref.startsWith("http://")
                || ref.startsWith("https://")
                || ref.startsWith("content://")
                || ref.startsWith("file://");
    }

    private static boolean inferPreviewable(ItemType type, String filename, String mime) {
        if (type == ItemType.TEXT) {
            return true;
        }
        if (type != ItemType.FILE) {
            return false;
        }
        String cleanMime = mime == null ? "" : mime.toLowerCase(Locale.US);
        if (cleanMime.startsWith("text/")) {
            return true;
        }
        if (cleanMime.contains("json")
                || cleanMime.contains("xml")
                || cleanMime.contains("yaml")
                || cleanMime.contains("toml")
                || cleanMime.contains("javascript")
                || cleanMime.contains("typescript")
                || cleanMime.contains("x-sh")
                || cleanMime.contains("x-python")
                || cleanMime.contains("x-go")) {
            return true;
        }
        String cleanName = filename == null ? "" : filename.toLowerCase(Locale.US);
        return cleanName.endsWith(".txt")
                || cleanName.endsWith(".md")
                || cleanName.endsWith(".json")
                || cleanName.endsWith(".yaml")
                || cleanName.endsWith(".yml")
                || cleanName.endsWith(".toml")
                || cleanName.endsWith(".xml")
                || cleanName.endsWith(".html")
                || cleanName.endsWith(".css")
                || cleanName.endsWith(".scss")
                || cleanName.endsWith(".js")
                || cleanName.endsWith(".jsx")
                || cleanName.endsWith(".ts")
                || cleanName.endsWith(".tsx")
                || cleanName.endsWith(".sql")
                || cleanName.endsWith(".sh")
                || cleanName.endsWith(".py")
                || cleanName.endsWith(".go")
                || cleanName.endsWith(".rs")
                || cleanName.endsWith(".c")
                || cleanName.endsWith(".h")
                || cleanName.endsWith(".cpp")
                || cleanName.endsWith(".hpp")
                || cleanName.endsWith(".java")
                || cleanName.endsWith(".kt")
                || cleanName.endsWith(".rb")
                || cleanName.endsWith(".php")
                || cleanName.endsWith(".lua")
                || cleanName.endsWith("dockerfile")
                || cleanName.endsWith("makefile");
    }
}
