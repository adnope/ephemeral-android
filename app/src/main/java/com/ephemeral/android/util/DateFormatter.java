package com.ephemeral.android.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DateFormatter {
    private static final ThreadLocal<DateFormat> CHAT_FORMAT = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        }
    };
    private static final ThreadLocal<DateFormat> DETAIL_FORMAT = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("MMM d, yyyy, HH:mm", Locale.getDefault());
        }
    };

    private DateFormatter() {
    }

    public static String chat(long epochMillis) {
        return CHAT_FORMAT.get().format(new Date(epochMillis));
    }

    public static String detail(long epochMillis) {
        return DETAIL_FORMAT.get().format(new Date(epochMillis));
    }
}
