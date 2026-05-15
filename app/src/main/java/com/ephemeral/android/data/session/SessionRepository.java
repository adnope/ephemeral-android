package com.ephemeral.android.data.session;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionRepository {
    private static final String PREFS = "ephemeral_session";
    private static final String KEY_AUTHENTICATED = "authenticated";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_SERVER_BASE_URL = "server_base_url";

    private final SharedPreferences preferences;

    public SessionRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasStoredSession() {
        return preferences.getBoolean(KEY_AUTHENTICATED, false);
    }

    public void markAuthenticated(String username) {
        preferences.edit()
                .putBoolean(KEY_AUTHENTICATED, true)
                .putString(KEY_USERNAME, username == null ? "" : username)
                .apply();
    }

    public String getUsername() {
        return preferences.getString(KEY_USERNAME, "");
    }

    public void setServerBaseUrl(String baseUrl) {
        preferences.edit().putString(KEY_SERVER_BASE_URL, baseUrl == null ? "" : baseUrl.trim()).apply();
    }

    public String getServerBaseUrl(String fallback) {
        String stored = preferences.getString(KEY_SERVER_BASE_URL, "");
        return stored == null || stored.isEmpty() ? fallback : stored;
    }

    public void clearSession() {
        preferences.edit()
                .putBoolean(KEY_AUTHENTICATED, false)
                .remove(KEY_USERNAME)
                .apply();
    }

    public void clearAll() {
        preferences.edit().clear().apply();
    }
}
