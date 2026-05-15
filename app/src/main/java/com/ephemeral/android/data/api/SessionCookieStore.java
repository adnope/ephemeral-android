package com.ephemeral.android.data.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

public final class SessionCookieStore implements CookieJar {
    private static final String PREFS = "ephemeral_cookies";
    private static final String KEY_COOKIE = "session_cookie";

    private final SharedPreferences preferences;
    private final Map<String, Cookie> memoryCookies = new HashMap<>();

    public SessionCookieStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restore();
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            if (cookie.persistent()) {
                memoryCookies.put(cookie.name(), cookie);
            }
        }
        persistFirstCookie();
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        if (memoryCookies.isEmpty()) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        List<Cookie> valid = new ArrayList<>();
        Iterator<Map.Entry<String, Cookie>> iterator = memoryCookies.entrySet().iterator();
        while (iterator.hasNext()) {
            Cookie cookie = iterator.next().getValue();
            if (cookie.expiresAt() <= now) {
                iterator.remove();
            } else if (cookie.matches(url)) {
                valid.add(cookie);
            }
        }
        persistFirstCookie();
        return valid;
    }

    public synchronized void clear() {
        memoryCookies.clear();
        preferences.edit().clear().apply();
    }

    private void restore() {
        String encoded = preferences.getString(KEY_COOKIE, "");
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 5) {
            preferences.edit().remove(KEY_COOKIE).apply();
            return;
        }
        long expiresAt = parseLong(parts[4]);
        if (expiresAt <= System.currentTimeMillis()) {
            preferences.edit().remove(KEY_COOKIE).apply();
            return;
        }
        try {
            Cookie cookie = new Cookie.Builder()
                    .name(decode(parts[0]))
                    .value(decode(parts[1]))
                    .domain(decode(parts[2]))
                    .path(decode(parts[3]).isEmpty() ? "/" : decode(parts[3]))
                    .expiresAt(expiresAt)
                    .httpOnly()
                    .build();
            memoryCookies.put(cookie.name(), cookie);
        } catch (IllegalArgumentException e) {
            preferences.edit().remove(KEY_COOKIE).apply();
        }
    }

    private void persistFirstCookie() {
        if (memoryCookies.isEmpty()) {
            preferences.edit().remove(KEY_COOKIE).apply();
            return;
        }
        Cookie cookie = memoryCookies.values().iterator().next();
        String encoded = encode(cookie.name()) + "|" + encode(cookie.value()) + "|" + encode(cookie.domain()) + "|"
                + encode(cookie.path()) + "|" + cookie.expiresAt();
        preferences.edit().putString(KEY_COOKIE, encoded).apply();
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        byte[] decoded = Base64.decode(value, Base64.NO_WRAP);
        return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
