package com.ephemeral.android.data.model;

public final class PublicLink {
    private final String status;
    private final String url;
    private final String token;
    private final String expiresAt;

    public PublicLink(String status, String url, String token, String expiresAt) {
        this.status = status == null ? "none" : status;
        this.url = url;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public String getStatus() {
        return status;
    }

    public String getUrl() {
        return url;
    }

    public String getToken() {
        return token;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return "active".equals(status);
    }

    public boolean isExpired() {
        return "expired".equals(status);
    }

    public boolean hasLink() {
        return !"none".equals(status);
    }
}
