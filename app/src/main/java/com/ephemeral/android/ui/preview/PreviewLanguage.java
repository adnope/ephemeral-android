package com.ephemeral.android.ui.preview;

import com.ephemeral.android.data.api.ApiModels;

public final class PreviewLanguage {
    private PreviewLanguage() {
    }

    public static String idForPosition(int position) {
        if (position < 0 || position >= ApiModels.PREVIEW_LANGUAGE_IDS.size()) {
            return "auto";
        }
        return ApiModels.PREVIEW_LANGUAGE_IDS.get(position);
    }
}
