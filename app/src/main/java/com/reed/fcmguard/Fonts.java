package com.reed.fcmguard;

import android.graphics.Typeface;
import android.os.Build;

/** Typefaces beyond what XML text styles can express. */
final class Fonts {
    private static Typeface title;

    private Fonts() {}

    /**
     * Title weight (800). The system fonts are variable (MiSans on HyperOS, Roboto on
     * stock Android), so this renders heavier than bold; before Android 9 it is bold.
     */
    static Typeface title() {
        if (title == null) {
            title = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? Typeface.create(Typeface.DEFAULT, 800, false)
                    : Typeface.DEFAULT_BOLD;
        }
        return title;
    }
}
