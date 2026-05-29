package com.ephemeral.android.ui.common;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class StyledSpinner extends Spinner {

    public StyledSpinner(Context context) {
        super(context);
    }

    public StyledSpinner(Context context, int mode) {
        super(context, mode);
    }

    public StyledSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StyledSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StyledSpinner(Context context, AttributeSet attrs, int defStyleAttr, int mode) {
        super(context, attrs, defStyleAttr, mode);
    }

    @Override
    public void setAdapter(SpinnerAdapter adapter) {
        if (adapter != null) {
            super.setAdapter(new StyledSpinnerAdapter(adapter));
        } else {
            super.setAdapter(null);
        }
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        try {
            Object popup = getPopupObject();
            if (popup != null) {
                configureListView(popup);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return handled;
    }

    private void configureListView(Object popup) {
        ListView listView = getPopupListView(popup);
        if (listView != null) {
            listView.setPadding(0, 0, 0, 0);
            listView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        } else {
            post(() -> {
                try {
                    Object p = getPopupObject();
                    if (p != null) {
                        ListView lv = getPopupListView(p);
                        if (lv != null) {
                            lv.setPadding(0, 0, 0, 0);
                            lv.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private Object getPopupObject() {
        Class<?> clazz = getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField("mPopup");
                field.setAccessible(true);
                Object popup = field.get(this);
                if (popup != null) {
                    return popup;
                }
            } catch (NoSuchFieldException e) {
                // Ignore and try superclass
            } catch (IllegalAccessException e) {
                // Ignore
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private ListView getPopupListView(Object popup) {
        if (popup == null) return null;
        try {
            Method method = popup.getClass().getMethod("getListView");
            return (ListView) method.invoke(popup);
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static class StyledSpinnerAdapter implements SpinnerAdapter, android.widget.ListAdapter {
        private final SpinnerAdapter delegate;

        public StyledSpinnerAdapter(SpinnerAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return delegate instanceof android.widget.ListAdapter
                    ? ((android.widget.ListAdapter) delegate).areAllItemsEnabled()
                    : true;
        }

        @Override
        public boolean isEnabled(int position) {
            return delegate instanceof android.widget.ListAdapter
                    ? ((android.widget.ListAdapter) delegate).isEnabled(position)
                    : true;
        }

        @Override
        public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
            View view = delegate.getDropDownView(position, convertView, parent);
            if (view != null) {
                view.setBackground(PopupMenus.createItemRipple(view.getContext(), position, getCount()));
            }
            return view;
        }

        @Override
        public void registerDataSetObserver(android.database.DataSetObserver observer) {
            delegate.registerDataSetObserver(observer);
        }

        @Override
        public void unregisterDataSetObserver(android.database.DataSetObserver observer) {
            delegate.unregisterDataSetObserver(observer);
        }

        @Override
        public int getCount() {
            return delegate.getCount();
        }

        @Override
        public Object getItem(int position) {
            return delegate.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            return delegate.getItemId(position);
        }

        @Override
        public boolean hasStableIds() {
            return delegate.hasStableIds();
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            return delegate.getView(position, convertView, parent);
        }

        @Override
        public int getItemViewType(int position) {
            return delegate.getItemViewType(position);
        }

        @Override
        public int getViewTypeCount() {
            return delegate.getViewTypeCount();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }
    }
}
