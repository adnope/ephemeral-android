package com.ephemeral.android.ui.common;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.ListView;

import com.ephemeral.android.R;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class PopupMenus {
    private PopupMenus() {
    }

    public static PopupMenu create(View anchor) {
        Context themed = new ContextThemeWrapper(anchor.getContext(), R.style.Widget_PopupMenu_Ephemeral);
        return new StyledPopupMenu(themed, anchor);
    }

    private static class StyledPopupMenu extends PopupMenu {
        public StyledPopupMenu(Context context, View anchor) {
            super(context, anchor);
        }

        @Override
        public void show() {
            super.show();
            styleMenu(this);
        }
    }

    private static void styleMenu(PopupMenu menu) {
        try {
            Field mPopupField = PopupMenu.class.getDeclaredField("mPopup");
            mPopupField.setAccessible(true);
            Object menuPopupHelper = mPopupField.get(menu);
            Method getPopupMethod = menuPopupHelper.getClass().getDeclaredMethod("getPopup");
            getPopupMethod.setAccessible(true);
            Object menuPopup = getPopupMethod.invoke(menuPopupHelper);
            Method getListViewMethod = menuPopup.getClass().getMethod("getListView");
            ListView listView = (ListView) getListViewMethod.invoke(menuPopup);
            if (listView != null) {
                listView.setPadding(0, 0, 0, 0);
                listView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

                listView.post(() -> {
                    int count = listView.getChildCount();
                    for (int i = 0; i < count; i++) {
                        View child = listView.getChildAt(i);
                        if (child != null) {
                            child.setBackground(createItemRipple(child.getContext(), i, count));
                        }
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static android.graphics.drawable.Drawable createItemRipple(Context context, int position, int total) {
        int color = 0x14000000;
        android.content.res.ColorStateList colorStateList = android.content.res.ColorStateList.valueOf(color);

        android.graphics.drawable.shapes.RoundRectShape roundRectShape;
        float radius = dpToPx(context, 22);

        if (total == 1) {
            roundRectShape = new android.graphics.drawable.shapes.RoundRectShape(
                    new float[] { radius, radius, radius, radius, radius, radius, radius, radius },
                    null, null);
        } else if (position == 0) {
            roundRectShape = new android.graphics.drawable.shapes.RoundRectShape(
                    new float[] { radius, radius, radius, radius, 0, 0, 0, 0 },
                    null, null);
        } else if (position == total - 1) {
            roundRectShape = new android.graphics.drawable.shapes.RoundRectShape(
                    new float[] { 0, 0, 0, 0, radius, radius, radius, radius },
                    null, null);
        } else {
            roundRectShape = new android.graphics.drawable.shapes.RoundRectShape(
                    new float[] { 0, 0, 0, 0, 0, 0, 0, 0 },
                    null, null);
        }

        android.graphics.drawable.ShapeDrawable mask = new android.graphics.drawable.ShapeDrawable(roundRectShape);
        mask.getPaint().setColor(android.graphics.Color.BLACK);

        return new android.graphics.drawable.RippleDrawable(colorStateList, null, mask);
    }

    private static float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }
}
