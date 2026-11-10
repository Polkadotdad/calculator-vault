package com.dhillon.calculatorvault;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

final class UiUtil {

    private UiUtil() {
    }

    /**
     * Android 15+ draws apps edge-to-edge. Pads {@code root} so content stays clear of the
     * status bar, navigation bar and camera cut-out. Older versions already do this themselves.
     */
    static void padForSystemBars(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        final int l = root.getPaddingLeft();
        final int t = root.getPaddingTop();
        final int r = root.getPaddingRight();
        final int b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets s = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(l + s.left, t + s.top, r + s.right, b + s.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
