package com.ephemeral.android.ui.login;

import android.graphics.Rect;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ephemeral.android.BuildVariantApiFactory;
import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.ApiErrorCategory;
import com.ephemeral.android.data.api.AuthResult;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.session.SessionRepository;

public final class LoginController {
    public interface Callback {
        void onAuthenticated(AuthResult result);
    }

    private final View view;
    private final EphemeralApi api;
    private final SessionRepository sessionRepository;
    private final boolean setupMode;
    private final Callback callback;
    private final EditText serverUrl;
    private final EditText username;
    private final EditText password;
    private final ScrollView scroll;
    private final TextView subtitle;
    private final TextView error;
    private final Button submit;
    private final ProgressBar progress;
    private boolean passwordVisible;

    public LoginController(LayoutInflater inflater, EphemeralApi api, SessionRepository sessionRepository,
            boolean setupMode, Callback callback) {
        this.api = api;
        this.sessionRepository = sessionRepository;
        this.setupMode = setupMode;
        this.callback = callback;
        view = inflater.inflate(R.layout.screen_login, null, false);
        serverUrl = view.findViewById(R.id.input_server_url);
        username = view.findViewById(R.id.input_username);
        password = view.findViewById(R.id.input_password);
        scroll = (ScrollView) view;
        subtitle = view.findViewById(R.id.login_subtitle);
        error = view.findViewById(R.id.text_login_error);
        submit = view.findViewById(R.id.button_submit_login);
        progress = view.findViewById(R.id.progress_login);
        ImageButton toggle = view.findViewById(R.id.button_toggle_password);
        subtitle.setText(setupMode ? R.string.login_setup_subtitle : R.string.login_sign_in_subtitle);
        submit.setText(setupMode ? R.string.create_account : R.string.login);
        serverUrl.setText(sessionRepository.getServerBaseUrl(BuildVariantApiFactory.defaultBaseUrl()));
        applyPasswordTypeface();
        toggle.setOnClickListener(v -> togglePassword(toggle));
        submit.setOnClickListener(v -> submit());
        attachKeyboardScroll(serverUrl);
        attachKeyboardScroll(username);
        attachKeyboardScroll(password);
    }

    public View getView() {
        return view;
    }

    public void showInitialError(String message) {
        showError(message);
    }

    private void submit() {
        String cleanUsername = username.getText().toString().trim();
        String cleanPassword = password.getText().toString();
        String cleanServerUrl = serverUrl.getText().toString().trim();
        if (cleanServerUrl.isEmpty()) {
            showError("Server URL is required.");
            serverUrl.requestFocus();
            return;
        }
        if (cleanUsername.isEmpty()) {
            showError("Username is required.");
            username.requestFocus();
            return;
        }
        if (cleanPassword.isEmpty()) {
            showError("Password is required.");
            password.requestFocus();
            return;
        }
        sessionRepository.setServerBaseUrl(cleanServerUrl);
        setSubmitting(true);
        ApiCallback<AuthResult> apiCallback = new ApiCallback<AuthResult>() {
            @Override
            public void onSuccess(AuthResult value) {
                setSubmitting(false);
                error.setVisibility(View.GONE);
                callback.onAuthenticated(value);
            }

            @Override
            public void onError(ApiError apiError) {
                setSubmitting(false);
                password.requestFocus();
                showError(loginErrorMessage(apiError));
            }
        };
        if (setupMode) {
            api.createFirstAccount(cleanUsername, cleanPassword, apiCallback);
        } else {
            api.login(cleanUsername, cleanPassword, apiCallback);
        }
    }

    private void togglePassword(ImageButton toggle) {
        passwordVisible = !passwordVisible;
        int type = passwordVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        password.setInputType(type);
        applyPasswordTypeface();
        password.setSelection(password.getText().length());
        toggle.setImageResource(passwordVisible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
    }

    private void applyPasswordTypeface() {
        password.setTypeface(username.getTypeface());
    }

    private void setSubmitting(boolean submitting) {
        submit.setEnabled(!submitting);
        username.setEnabled(!submitting);
        password.setEnabled(!submitting);
        serverUrl.setEnabled(!submitting);
        progress.setVisibility(submitting ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        error.setText(message == null || message.isEmpty() ? "Request failed." : message);
        error.setVisibility(View.VISIBLE);
        ensureFocusedFieldVisible();
    }

    private String loginErrorMessage(ApiError apiError) {
        ApiErrorCategory category = apiError.getCategory();
        if (category == ApiErrorCategory.NETWORK_UNAVAILABLE || category == ApiErrorCategory.TIMEOUT) {
            return "Could not reach the backend. Check the server URL and that the server is running.";
        }
        if (category == ApiErrorCategory.UNAUTHENTICATED || category == ApiErrorCategory.FORBIDDEN) {
            return "Invalid username or password.";
        }
        if (category == ApiErrorCategory.NOT_FOUND || category == ApiErrorCategory.UNKNOWN) {
            return "The URL is reachable, but it does not look like an Ephemeral backend.";
        }
        return apiError.getMessage();
    }

    private void attachKeyboardScroll(EditText field) {
        field.setOnFocusChangeListener((focusedView, hasFocus) -> {
            if (hasFocus) {
                ensureFieldVisible(focusedView);
            }
        });
        field.setOnClickListener(this::ensureFieldVisible);
    }

    private void ensureFocusedFieldVisible() {
        View focused = view.findFocus();
        if (focused != null) {
            ensureFieldVisible(focused);
        }
    }

    private void ensureFieldVisible(View field) {
        scroll.postDelayed(() -> {
            Rect rect = new Rect();
            field.getDrawingRect(rect);
            scroll.offsetDescendantRectToMyCoords(field, rect);
            int targetBottom = rect.bottom + scroll.getPaddingBottom() + field.getHeight();
            int scrollY = Math.max(0, targetBottom - scroll.getHeight());
            scroll.smoothScrollTo(0, scrollY);
        }, 250);
    }
}
