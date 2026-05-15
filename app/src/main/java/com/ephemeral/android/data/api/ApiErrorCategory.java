package com.ephemeral.android.data.api;

public enum ApiErrorCategory {
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    VALIDATION_ERROR,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_PREVIEW,
    SERVER_ERROR,
    CANCELED,
    UNKNOWN
}
