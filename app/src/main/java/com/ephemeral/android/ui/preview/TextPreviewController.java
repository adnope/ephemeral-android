package com.ephemeral.android.ui.preview;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.ephemeral.android.AppExecutors;
import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.ApiErrorCategory;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.data.model.FilePreview;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemMetadata;
import com.ephemeral.android.data.model.ItemType;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.ui.common.ViewUi;
import com.ephemeral.android.util.ByteFormatter;
import com.ephemeral.android.util.DateFormatter;

public final class TextPreviewController implements ItemEventConsumer {
    private final View view;
    private final EphemeralApi api;
    private final ScreenHost host;
    private final SyntaxHighlighter highlighter;
    private final TextView title;
    private final TextView metadata;
    private final TextView status;
    private final TextView lineNumbers;
    private final TextView content;
    private final Button copy;
    private final Spinner language;
    private final long itemId;
    private final boolean localTextPreview;
    private FilePreview preview;

    public TextPreviewController(LayoutInflater inflater, EphemeralApi api, AppExecutors executors,
            ScreenHost host, Item item) {
        this.api = api;
        this.host = host;
        this.itemId = item.getId();
        this.localTextPreview = item.getType() == ItemType.TEXT;
        highlighter = new SyntaxHighlighter(executors);
        view = inflater.inflate(R.layout.screen_text_preview, null, false);
        title = view.findViewById(R.id.text_preview_title);
        metadata = view.findViewById(R.id.text_preview_metadata);
        status = view.findViewById(R.id.text_preview_status);
        lineNumbers = view.findViewById(R.id.text_preview_line_numbers);
        content = view.findViewById(R.id.text_preview_content);
        copy = view.findViewById(R.id.button_copy);
        language = view.findViewById(R.id.spinner_language);
        ViewUi.applyInstantDropdownAnimation(language);
        view.findViewById(R.id.button_close).setOnClickListener(v -> close());
        copy.setOnClickListener(v -> copyContent());
        view.findViewById(R.id.button_download).setOnClickListener(v -> {
            if (preview != null && !preview.getDownloadRef().isEmpty()) {
                host.downloadItem(toItem(preview));
            }
        });
        view.findViewById(R.id.button_delete).setOnClickListener(v -> {
            if (preview != null) {
                host.confirmDelete(toItem(preview), this::close);
            }
        });
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                renderContent();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (localTextPreview) {
            showTextItem(item);
        } else {
            load(item.getId());
        }
    }

    public View getView() {
        return view;
    }

    public void release() {
        highlighter.cancel();
        lineNumbers.setText("");
        content.setText("");
        preview = null;
    }

    @Override
    public void onItemEvent(ItemEvent event) {
        if (event.getItemId() != itemId) {
            return;
        }
        if (event.getType() == ItemEventType.DELETED) {
            host.showMessage("Item deleted.");
            close();
        } else if (!localTextPreview) {
            load(itemId);
        }
    }

    private void load(long itemId) {
        setLoading(true, "Loading preview...");
        api.loadTextPreview(itemId, new ApiCallback<FilePreview>() {
            @Override
            public void onSuccess(FilePreview value) {
                preview = value;
                title.setText(value.getFilename());
                metadata.setText(metadataLine(value));
                renderContent();
            }

            @Override
            public void onError(ApiError error) {
                if (error.getCategory() == ApiErrorCategory.UNSUPPORTED_PREVIEW
                        || error.getCategory() == ApiErrorCategory.PAYLOAD_TOO_LARGE) {
                    host.showMessage(error.getMessage());
                    close();
                    return;
                }
                if (error.isAuthenticationFailure()) {
                    host.onSessionExpired();
                } else {
                    setLoading(false, error.getMessage());
                }
            }
        });
    }

    private void showTextItem(Item item) {
        String body = item.getContentRef();
        preview = new FilePreview(item.getId(), "Text message", "text/plain", "plaintext",
                body, body.length(), item.getCreatedAtEpochMillis(), "");
        title.setText(preview.getFilename());
        metadata.setText(metadataLine(preview));
        renderContent();
    }

    private void renderContent() {
        if (preview == null) {
            return;
        }
        String selected = PreviewLanguage.idForPosition(language.getSelectedItemPosition());
        String effective = "auto".equals(selected) ? preview.getLanguage() : selected;
        setLoading(true, "Rendering " + effective + "...");
        lineNumbers.setText(lineNumbersFor(preview.getContent()));
        highlighter.render(preview.getContent(), effective, (rendered, highlighted) -> {
            content.setText(rendered);
            setLoading(false, highlighted ? "Highlighted as " + effective : "Plain text");
        });
    }

    private String lineNumbersFor(String value) {
        String source = value == null ? "" : value;
        if (source.isEmpty()) {
            return "";
        }
        String[] lines = source.split("\n", -1);
        int width = String.valueOf(lines.length).length();
        StringBuilder builder = new StringBuilder(lines.length * (width + 1));
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            appendPaddedLineNumber(builder, i + 1, width);
        }
        return builder.toString();
    }

    private void appendPaddedLineNumber(StringBuilder builder, int lineNumber, int width) {
        String value = String.valueOf(lineNumber);
        for (int i = value.length(); i < width; i++) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private void copyContent() {
        if (preview == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(preview.getFilename(), preview.getContent()));
            copy.setText(R.string.copied);
            view.postDelayed(() -> copy.setText(R.string.copy), 1200);
        }
    }

    private void close() {
        release();
        host.closeOverlay();
    }

    private void setLoading(boolean loading, String message) {
        status.setText(message);
        copy.setEnabled(!loading && preview != null);
    }

    private String metadataLine(FilePreview value) {
        return ByteFormatter.format(value.getFilesizeBytes()) + " - " + value.getMime()
                + " - " + DateFormatter.detail(value.getCreatedAtEpochMillis());
    }

    private Item toItem(FilePreview value) {
        return new Item(value.getId(), ItemType.FILE, value.getDownloadRef(), value.getFilename(),
                value.getFilesizeBytes(), new ItemMetadata(0, 0, "", value.getMime(), ""),
                value.getCreatedAtEpochMillis(), true);
    }
}
