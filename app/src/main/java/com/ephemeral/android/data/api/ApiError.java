package com.ephemeral.android.data.api;

public final class ApiError extends Exception {
    private final ApiErrorCategory category;
    private final int httpStatusCode;

    public ApiError(ApiErrorCategory category, String message) {
        this(category, message, 0, null);
    }

    public ApiError(ApiErrorCategory category, String message, int httpStatusCode, Throwable cause) {
        super(message, cause);
        this.category = category == null ? ApiErrorCategory.UNKNOWN : category;
        this.httpStatusCode = httpStatusCode;
    }

    public ApiErrorCategory getCategory() {
        return category;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public boolean isAuthenticationFailure() {
        return category == ApiErrorCategory.UNAUTHENTICATED;
    }
}
