package com.ephemeral.android.util;

public final class Result<T> {
    private final T value;
    private final Throwable error;

    private Result(T value, Throwable error) {
        this.value = value;
        this.error = error;
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null);
    }

    public static <T> Result<T> failure(Throwable error) {
        if (error == null) {
            throw new IllegalArgumentException("error is required");
        }
        return new Result<>(null, error);
    }

    public boolean isSuccess() {
        return error == null;
    }

    public T getValue() {
        if (!isSuccess()) {
            throw new IllegalStateException("Result has no value", error);
        }
        return value;
    }

    public Throwable getError() {
        if (isSuccess()) {
            throw new IllegalStateException("Result has no error");
        }
        return error;
    }
}
