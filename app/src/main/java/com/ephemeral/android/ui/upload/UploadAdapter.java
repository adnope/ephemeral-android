package com.ephemeral.android.ui.upload;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ephemeral.android.R;
import com.ephemeral.android.data.model.UploadStatus;
import com.ephemeral.android.data.model.UploadTask;
import com.ephemeral.android.util.ByteFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UploadAdapter extends RecyclerView.Adapter<UploadAdapter.UploadViewHolder> {
    interface Callback {
        void retry(long localId);

        void cancel(long localId);
    }

    private final Callback callback;
    private final List<UploadTask> tasks = new ArrayList<>();

    UploadAdapter(Callback callback) {
        this.callback = callback;
        setHasStableIds(true);
    }

    void submit(List<UploadTask> nextTasks) {
        tasks.clear();
        tasks.addAll(nextTasks);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return tasks.get(position).getLocalId();
    }

    @NonNull
    @Override
    public UploadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_upload, parent, false);
        return new UploadViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UploadViewHolder holder, int position) {
        UploadTask task = tasks.get(position);
        holder.name.setText(task.getDisplayName());
        holder.progress.setProgress(task.getProgressPercent());
        holder.status.setText(statusText(task));
        boolean retryable = task.getStatus() == UploadStatus.FAILED || task.getStatus() == UploadStatus.CANCELED;
        holder.retry.setVisibility(retryable ? View.VISIBLE : View.GONE);
        holder.cancel.setVisibility(task.isActive() ? View.VISIBLE : View.GONE);
        holder.retry.setOnClickListener(v -> callback.retry(task.getLocalId()));
        holder.cancel.setOnClickListener(v -> callback.cancel(task.getLocalId()));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    private String statusText(UploadTask task) {
        String size = ByteFormatter.format(task.getSizeBytes());
        if (task.getStatus() == UploadStatus.FAILED && !task.getErrorMessage().isEmpty()) {
            return "failed: " + task.getErrorMessage();
        }
        return String.format(Locale.US, "%s - %s - %d%%",
                task.getStatus().name().toLowerCase(Locale.US), size, task.getProgressPercent());
    }

    static final class UploadViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView status;
        final ProgressBar progress;
        final Button retry;
        final ImageButton cancel;

        UploadViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_upload_name);
            status = itemView.findViewById(R.id.text_upload_status);
            progress = itemView.findViewById(R.id.progress_upload);
            retry = itemView.findViewById(R.id.button_upload_retry);
            cancel = itemView.findViewById(R.id.button_upload_cancel);
        }
    }
}
