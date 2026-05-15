package com.ephemeral.android.ui.preview;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.ephemeral.android.AppExecutors;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class SyntaxHighlighter {
    interface Callback {
        void onRendered(CharSequence text, boolean highlighted);
    }

    private static final int MAX_HIGHLIGHT_CHARS = 80_000;
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "class", "interface", "enum", "public", "private", "protected", "final", "static",
            "void", "int", "long", "boolean", "if", "else", "for", "while", "return", "new",
            "function", "const", "let", "var", "type", "import", "from", "package", "func",
            "def", "struct", "impl", "match", "select", "case"));

    private final AppExecutors executors;
    private final AtomicInteger generation = new AtomicInteger();

    SyntaxHighlighter(AppExecutors executors) {
        this.executors = executors;
    }

    void render(String content, String language, Callback callback) {
        int token = generation.incrementAndGet();
        String safeContent = content == null ? "" : content;
        String safeLanguage = language == null ? "plaintext" : language;
        if ("plaintext".equals(safeLanguage) || safeContent.length() > MAX_HIGHLIGHT_CHARS) {
            callback.onRendered(safeContent, false);
            return;
        }
        executors.compute().execute(() -> {
            CharSequence rendered = highlight(safeContent);
            executors.main().execute(() -> {
                if (generation.get() == token) {
                    callback.onRendered(rendered, true);
                }
            });
        });
    }

    void cancel() {
        generation.incrementAndGet();
    }

    private CharSequence highlight(String content) {
        SpannableString span = new SpannableString(content);
        highlightStrings(span, content);
        highlightComments(span, content);
        highlightKeywords(span, content);
        return span;
    }

    private void highlightStrings(SpannableString span, String content) {
        int start = -1;
        char quote = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (start < 0 && (c == '"' || c == '\'' || c == '`')) {
                start = i;
                quote = c;
            } else if (start >= 0 && c == quote && !isEscaped(content, i)) {
                span.setSpan(new ForegroundColorSpan(Color.rgb(11, 103, 74)), start, i + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = -1;
            }
        }
    }

    private void highlightComments(SpannableString span, String content) {
        String[] lines = content.split("\n", -1);
        int offset = 0;
        for (String line : lines) {
            int comment = line.indexOf("//");
            if (comment < 0) {
                comment = line.indexOf("#");
            }
            if (comment >= 0) {
                span.setSpan(new ForegroundColorSpan(Color.rgb(96, 108, 118)),
                        offset + comment, offset + line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            offset += line.length() + 1;
        }
    }

    private void highlightKeywords(SpannableString span, String content) {
        int start = -1;
        for (int i = 0; i <= content.length(); i++) {
            boolean word = i < content.length() && Character.isJavaIdentifierPart(content.charAt(i));
            if (word && start < 0) {
                start = i;
            } else if (!word && start >= 0) {
                String token = content.substring(start, i);
                if (KEYWORDS.contains(token)) {
                    span.setSpan(new ForegroundColorSpan(Color.rgb(10, 102, 194)), start, i,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                start = -1;
            }
        }
    }

    private boolean isEscaped(String content, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && content.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }
}
