package com.ephemeral.android.ui.common;

import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

public final class ViewUi {
    private ViewUi() {
    }

    public static void stripButtonShadow(Button button) {
        preparePressable(button);
    }

    public static void prepareTextButton(TextView view) {
        preparePressable(view);
    }

    public static void prepareImageButton(ImageButton button) {
        preparePressable(button);
        button.setScaleType(ImageButton.ScaleType.CENTER);
    }

    public static void prepareHistoryCompoundButton(CompoundButton button) {
        preparePressable(button);
    }

    public static void prepareHistoryCompoundButtons(RadioGroup group, CompoundButton... extras) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof CompoundButton) {
                prepareHistoryCompoundButton((CompoundButton) child);
            }
        }
        if (extras != null) {
            for (CompoundButton extra : extras) {
                if (extra != null) {
                    prepareHistoryCompoundButton(extra);
                }
            }
        }
    }

    private static void preparePressable(View view) {
        view.setStateListAnimator(null);
        view.setElevation(0f);
        view.setTranslationZ(0f);
        view.setForeground(null);
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setBackgroundTintList(null);
        }
        if (view instanceof Button) {
            ((Button) view).setBackgroundTintList(null);
        }
        if (view instanceof ImageButton) {
            ((ImageButton) view).setBackgroundTintList(null);
        }
    }

    public static void prepareSpinner(Spinner spinner) {
        preparePressable(spinner);
        spinner.setBackgroundResource(com.ephemeral.android.R.drawable.bg_text_field);
    }

    public static void applyInstantDropdownAnimation(Spinner spinner) {
        int animationStyle = com.ephemeral.android.R.style.PopupAnimationInstant;
        try {
            java.lang.reflect.Field popupField = Spinner.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popup = popupField.get(spinner);
            if (popup != null) {
                popup.getClass().getMethod("setAnimationStyle", int.class).invoke(popup, animationStyle);
            }
        } catch (ReflectiveOperationException ignored) {
            // Theme popupAnimationStyle still applies on most devices.
        }
    }

    public static void configureFilterSpinner(android.content.Context context, Spinner spinner, int labelsArrayId) {
        android.widget.ArrayAdapter<CharSequence> adapter = android.widget.ArrayAdapter.createFromResource(
                context, labelsArrayId, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        applyInstantDropdownAnimation(spinner);
    }

    public static void syncSpinnerDropDownWidth(Spinner spinner) {
        spinner.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = spinner.getWidth();
                if (width > 0) {
                    spinner.setDropDownWidth(width);
                    spinner.setDropDownHorizontalOffset(0);
                    spinner.removeOnLayoutChangeListener(this);
                }
            }
        });
    }
}
