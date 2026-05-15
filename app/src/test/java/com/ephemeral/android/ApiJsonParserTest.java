package com.ephemeral.android;

import com.ephemeral.android.data.api.ApiJsonParser;
import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.data.model.Page;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ApiJsonParserTest {
    @Test
    public void parsesTypedItemPage() {
        String json = "{"
                + "\"items\":[{\"id\":42,\"type\":\"image\",\"contentRef\":\"ref\","
                + "\"filename\":\"a.jpg\",\"filesizeBytes\":2048,\"createdAtEpochMillis\":123,"
                + "\"previewable\":false,\"metadata\":{\"width\":640,\"height\":480,"
                + "\"mime\":\"image/jpeg\",\"thumbRef\":\"thumb\"}}],"
                + "\"nextCursor\":42,\"hasMore\":true}";

        Page<Item> page = ApiJsonParser.parseItemPage(json);

        assertEquals(1, page.getItems().size());
        assertEquals(42, page.getNextCursor());
        assertTrue(page.hasMore());
        Item item = page.getItems().get(0);
        assertEquals(ItemType.IMAGE, item.getType());
        assertEquals(640, item.getMetadata().getWidth());
        assertEquals("image/jpeg", item.getMetadata().getMime());
    }

    @Test
    public void parsesFilePreview() {
        String json = "{\"id\":7,\"filename\":\"main.go\",\"mime\":\"text/plain\","
                + "\"language\":\"go\",\"content\":\"package main\",\"filesizeBytes\":12,"
                + "\"createdAtEpochMillis\":99,\"downloadRef\":\"ref\"}";

        FilePreview preview = ApiJsonParser.parseFilePreview(json);

        assertEquals(7, preview.getId());
        assertEquals("go", preview.getLanguage());
        assertEquals("package main", preview.getContent());
    }

    @Test
    public void parsesBackendMobileItemShape() {
        String json = "{"
                + "\"items\":[{\"id\":51,\"type\":\"image\",\"text\":\"\","
                + "\"filename\":\"photo.jpg\",\"filesizeBytes\":2048,"
                + "\"contentUrl\":\"/api/files/photo.jpg\","
                + "\"downloadUrl\":\"/api/files/photo.jpg\","
                + "\"createdAtEpochMillis\":123,"
                + "\"metadata\":{\"width\":640,\"height\":480,"
                + "\"mime\":\"image/jpeg\",\"thumbnailUrl\":\"/api/files/thumbs/photo.jpg\"}}],"
                + "\"nextCursor\":51,\"hasMore\":true}";

        Page<Item> page = ApiJsonParser.parseItemPage(json, "https://example.test");
        Item item = page.getItems().get(0);

        assertEquals("https://example.test/api/files/photo.jpg", item.getContentRef());
        assertEquals("https://example.test/api/files/thumbs/photo.jpg", item.getMetadata().getThumbRef());
    }

    @Test
    public void parsesBackendTextItemWithoutResolvingAsUrl() {
        String json = "{\"items\":[{\"id\":52,\"type\":\"text\",\"text\":\"abc\","
                + "\"filename\":\"\",\"filesizeBytes\":-1,\"createdAtEpochMillis\":123,"
                + "\"metadata\":{}}],\"nextCursor\":0,\"hasMore\":false}";

        Page<Item> page = ApiJsonParser.parseItemPage(json, "http://arch:8080");

        assertEquals("abc", page.getItems().get(0).getContentRef());
    }

    @Test
    public void infersPreviewableOnlyForTextLikeFilesWhenFlagMissing() {
        String json = "{\"items\":["
                + "{\"id\":53,\"type\":\"file\",\"filename\":\"manual.pdf\",\"filesizeBytes\":99,"
                + "\"createdAtEpochMillis\":123,\"metadata\":{\"mime\":\"application/pdf\"}},"
                + "{\"id\":54,\"type\":\"file\",\"filename\":\"main.go\",\"filesizeBytes\":99,"
                + "\"createdAtEpochMillis\":123,\"metadata\":{\"mime\":\"text/x-go\"}}],"
                + "\"nextCursor\":0,\"hasMore\":false}";

        Page<Item> page = ApiJsonParser.parseItemPage(json, "http://arch:8080");

        assertFalse(page.getItems().get(0).isPreviewable());
        assertTrue(page.getItems().get(1).isPreviewable());
    }

    @Test
    public void parsesBackendPreviewShape() {
        String json = "{\"id\":9,\"filename\":\"main.go\",\"mime\":\"text/x-go\","
                + "\"language\":\"go\",\"content\":\"package main\",\"filesize\":12,"
                + "\"created_at\":\"May 15, 2026 10:30 AM\","
                + "\"download_url\":\"/api/files/main.go\"}";

        FilePreview preview = ApiJsonParser.parseFilePreview(json, "https://example.test");

        assertEquals(12, preview.getFilesizeBytes());
        assertEquals("https://example.test/api/files/main.go", preview.getDownloadRef());
        assertTrue(preview.getCreatedAtEpochMillis() > 0);
    }
}
