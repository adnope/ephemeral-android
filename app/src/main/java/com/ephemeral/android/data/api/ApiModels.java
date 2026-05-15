package com.ephemeral.android.data.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ApiModels {
    public static final List<String> PREVIEW_LANGUAGE_IDS = Collections.unmodifiableList(Arrays.asList(
            "auto", "plaintext", "go", "python", "javascript", "typescript", "jsx", "tsx",
            "json", "markdown", "yaml", "toml", "html", "css", "scss", "xml", "sql",
            "shellscript", "make", "dockerfile", "rust", "c", "cpp", "java", "kotlin",
            "ruby", "php", "lua"));

    private ApiModels() {
    }
}
