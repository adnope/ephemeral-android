package com.ephemeral.android;

import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.ApiErrorCategory;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.preview.PreviewLanguage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SessionAndPreviewStateTest {
    @Test
    public void unauthenticatedErrorIsSessionExpirySignal() {
        ApiError error = new ApiError(ApiErrorCategory.UNAUTHENTICATED, "expired");

        assertTrue(error.isAuthenticationFailure());
    }

    @Test
    public void previewLanguageSelectionUsesCanonicalIds() {
        assertEquals("auto", PreviewLanguage.idForPosition(0));
        assertEquals("plaintext", PreviewLanguage.idForPosition(1));
        assertEquals("typescript", PreviewLanguage.idForPosition(5));
        assertEquals("auto", PreviewLanguage.idForPosition(100));
    }

    @Test
    public void imageCacheKeyIncludesTargetSize() {
        assertEquals("ref|120x80", ImageLoader.cacheKey("ref", 120, 80));
        assertEquals("ref|1x1", ImageLoader.cacheKey("ref", 0, -20));
    }
}
