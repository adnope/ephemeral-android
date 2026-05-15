package com.ephemeral.android.ui.common;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.ephemeral.android.data.api.UploadRequest;

import java.util.ArrayList;
import java.util.List;

public final class FileResolver {
    private final ContentResolver contentResolver;

    public FileResolver(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    public List<UploadRequest> toUploadRequests(List<Uri> uris) {
        List<UploadRequest> requests = new ArrayList<>(uris.size());
        for (Uri uri : uris) {
            requests.add(toUploadRequest(uri));
        }
        return requests;
    }

    public UploadRequest toUploadRequest(Uri uri) {
        String name = displayName(uri);
        long size = size(uri);
        String mime = contentResolver.getType(uri);
        return new UploadRequest(uri, name, size, mime);
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = contentResolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "Upload" : last;
    }

    private long size(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = contentResolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }
}
