package com.ephemeral.android.ui.history;

import android.app.DatePickerDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.api.ApiCallback;
import com.ephemeral.android.data.api.ApiError;
import com.ephemeral.android.data.api.EphemeralApi;
import com.ephemeral.android.data.api.ItemEvent;
import com.ephemeral.android.data.api.ItemEventType;
import com.ephemeral.android.data.model.HistoryQuery;
import com.ephemeral.android.data.model.Item;
import com.ephemeral.android.data.model.ItemTypeFilter;
import com.ephemeral.android.data.model.Page;
import com.ephemeral.android.data.model.RecentFilter;
import com.ephemeral.android.ui.common.ImageLoader;
import com.ephemeral.android.ui.common.ItemEventConsumer;
import com.ephemeral.android.ui.common.ScreenHost;
import com.ephemeral.android.util.PaginationMerger;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class HistoryController implements ItemEventConsumer {
    private final View view;
    private final EphemeralApi api;
    private final ScreenHost host;
    private final EditText search;
    private final EditText dateFrom;
    private final EditText dateTo;
    private final Spinner recent;
    private final CheckBox searchBody;
    private final RadioGroup typeFilter;
    private final RecyclerView list;
    private final ProgressBar loading;
    private final TextView empty;
    private final HistoryAdapter adapter;
    private final List<Item> items = new ArrayList<>();
    private HistoryQuery query = HistoryQuery.empty();
    private long nextCursor;
    private boolean hasMore;
    private boolean requestInFlight;

    public HistoryController(LayoutInflater inflater, EphemeralApi api, ScreenHost host, ImageLoader imageLoader) {
        this.api = api;
        this.host = host;
        view = inflater.inflate(R.layout.screen_history, null, false);
        search = view.findViewById(R.id.input_search);
        dateFrom = view.findViewById(R.id.input_date_from);
        dateTo = view.findViewById(R.id.input_date_to);
        recent = view.findViewById(R.id.spinner_recent);
        searchBody = view.findViewById(R.id.check_search_body);
        typeFilter = view.findViewById(R.id.group_type_filter);
        list = view.findViewById(R.id.list_history);
        loading = view.findViewById(R.id.progress_history);
        empty = view.findViewById(R.id.text_history_empty);
        adapter = new HistoryAdapter(imageLoader, new HistoryAdapter.Callback() {
            @Override
            public void openMedia(Item item) {
                HistoryController.this.openMedia(item);
            }

            @Override
            public void openPreview(Item item) {
                host.openTextPreview(item);
            }

            @Override
            public void unsupportedPreview(Item item) {
                host.showMessage("Preview is not supported for this file.");
            }

            @Override
            public void download(Item item) {
                host.downloadItem(item);
            }

            @Override
            public void delete(Item item) {
                host.confirmDelete(item, () -> removeItem(item.getId()));
            }
        });
        GridLayoutManager layoutManager = new GridLayoutManager(view.getContext(), 3);
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!requestInFlight && hasMore && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) {
                    loadMore();
                }
            }
        });
        view.findViewById(R.id.button_nav_chat).setOnClickListener(v -> host.showChat());
        view.findViewById(R.id.button_refresh).setOnClickListener(v -> refreshFromBackend());
        view.findViewById(R.id.button_logout).setOnClickListener(v -> host.logout());
        view.findViewById(R.id.button_search).setOnClickListener(v -> applyFilters());
        view.findViewById(R.id.button_clear_search).setOnClickListener(v -> clearSearchPreservingType());
        configureDateInput(dateFrom);
        configureDateInput(dateTo);
        configureRecentSpinner();
        typeFilter.setOnCheckedChangeListener((group, checkedId) -> applyFilters());
        loadFirst();
    }

    public View getView() {
        return view;
    }

    @Override
    public void onItemEvent(ItemEvent event) {
        if (event.getType() == ItemEventType.DELETED) {
            removeItem(event.getItemId());
        } else {
            loadFirst();
        }
    }

    private void applyFilters() {
        hideKeyboard();
        query = new HistoryQuery(0, selectedType(), search.getText().toString(), searchBody.isChecked(),
                dateFrom.getText().toString(), dateTo.getText().toString(), selectedRecent());
        loadFirst();
    }

    private void clearSearchPreservingType() {
        hideKeyboard();
        search.setText("");
        dateFrom.setText("");
        dateTo.setText("");
        recent.setSelection(0);
        searchBody.setChecked(false);
        query = query.clearSearchPreservingType();
        loadFirst();
    }

    private void refreshFromBackend() {
        if (!requestInFlight) {
            loadFirst();
        }
    }

    private void loadFirst() {
        requestInFlight = true;
        loading.setVisibility(View.VISIBLE);
        api.loadHistoryPage(query, new ApiCallback<Page<Item>>() {
            @Override
            public void onSuccess(Page<Item> page) {
                requestInFlight = false;
                loading.setVisibility(View.GONE);
                items.clear();
                items.addAll(page.getItems());
                nextCursor = page.getNextCursor();
                hasMore = page.hasMore();
                render();
            }

            @Override
            public void onError(ApiError error) {
                requestInFlight = false;
                loading.setVisibility(View.GONE);
                handleApiError(error);
            }
        });
    }

    private void loadMore() {
        if (nextCursor == 0) {
            return;
        }
        requestInFlight = true;
        api.loadHistoryPage(query.withCursor(nextCursor), new ApiCallback<Page<Item>>() {
            @Override
            public void onSuccess(Page<Item> page) {
                requestInFlight = false;
                List<Item> merged = PaginationMerger.appendIgnoringDuplicates(items, page.getItems());
                items.clear();
                items.addAll(merged);
                nextCursor = page.getNextCursor();
                hasMore = page.hasMore();
                render();
            }

            @Override
            public void onError(ApiError error) {
                requestInFlight = false;
                handleApiError(error);
            }
        });
    }

    private ItemTypeFilter selectedType() {
        int id = typeFilter.getCheckedRadioButtonId();
        if (id == R.id.filter_images) {
            return ItemTypeFilter.IMAGES;
        }
        if (id == R.id.filter_videos) {
            return ItemTypeFilter.VIDEOS;
        }
        if (id == R.id.filter_files) {
            return ItemTypeFilter.FILES;
        }
        return ItemTypeFilter.ALL;
    }

    private RecentFilter selectedRecent() {
        int index = recent.getSelectedItemPosition();
        RecentFilter[] values = RecentFilter.values();
        return index >= 0 && index < values.length ? values[index] : RecentFilter.ANY_TIME;
    }

    private void configureDateInput(EditText input) {
        input.setFocusable(false);
        input.setOnClickListener(v -> showDatePicker(input));
    }

    private void configureRecentSpinner() {
        CharSequence[] labels = view.getResources().getTextArray(R.array.recent_filter_labels);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(
                view.getContext(), android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return styleRecentSpinnerText(super.getView(position, convertView, parent));
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styleRecentSpinnerText(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        recent.setAdapter(adapter);
        recent.post(() -> {
            int width = recent.getWidth();
            if (width > 0) {
                recent.setDropDownWidth(width);
                recent.setDropDownHorizontalOffset(0);
            }
        });
    }

    private View styleRecentSpinnerText(View itemView) {
        if (itemView instanceof TextView) {
            TextView textView = (TextView) itemView;
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    view.getResources().getDimension(R.dimen.recent_spinner_text));
            textView.setSingleLine(true);
        }
        return itemView;
    }

    private void showDatePicker(EditText target) {
        hideKeyboard();
        Calendar calendar = calendarFrom(target.getText().toString());
        DatePickerDialog dialog = new DatePickerDialog(view.getContext(), (picker, year, month, dayOfMonth) ->
                target.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)),
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private Calendar calendarFrom(String value) {
        Calendar calendar = Calendar.getInstance();
        if (value == null || value.trim().isEmpty()) {
            return calendar;
        }
        String[] parts = value.trim().split("-");
        if (parts.length != 3) {
            return calendar;
        }
        try {
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]) - 1;
            int day = Integer.parseInt(parts[2]);
            calendar.setLenient(false);
            calendar.set(year, month, day);
            calendar.getTime();
        } catch (IllegalArgumentException e) {
            calendar = Calendar.getInstance();
        }
        return calendar;
    }

    private void openMedia(Item selected) {
        List<Item> media = new ArrayList<>();
        int start = 0;
        for (Item item : items) {
            if (item.isMedia()) {
                if (item.getId() == selected.getId()) {
                    start = media.size();
                }
                media.add(item);
            }
        }
        host.openMediaViewer(media, start);
    }

    private void removeItem(long itemId) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).getId() == itemId) {
                items.remove(i);
            }
        }
        render();
    }

    private void render() {
        adapter.submit(items);
        empty.setVisibility(items.isEmpty() && !requestInFlight ? View.VISIBLE : View.GONE);
    }

    private void handleApiError(ApiError error) {
        if (error.isAuthenticationFailure()) {
            host.onSessionExpired();
        } else {
            host.showMessage(error.getMessage());
        }
    }

    private void hideKeyboard() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        View focused = view.findFocus();
        if (focused != null) {
            focused.clearFocus();
        }
    }
}
