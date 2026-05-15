package com.ephemeral.android.data.api;

public final class AuthResult {
    private final boolean authenticated;
    private final String username;

    public AuthResult(boolean authenticated, String username) {
        this.authenticated = authenticated;
        this.username = username == null ? "" : username;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getUsername() {
        return username;
    }
}
