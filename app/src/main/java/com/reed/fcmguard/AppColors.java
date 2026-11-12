package com.reed.fcmguard;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

/**
 * Resolves the UI colours from the current theme. Colours are theme attributes
 * ({@code R.attr.pal_*}) so the selected palette (see {@link Palettes}) applies everywhere.
 */
final class AppColors {
    private AppColors() {}

    static int get(Context context, int attr) {
        return get(context.getTheme(), attr);
    }

    @SuppressWarnings("deprecation") // getColor(int, Theme) needs API 23; minSdk is 21.
    static int get(Resources.Theme theme, int attr) {
        TypedValue value = new TypedValue();
        if (!theme.resolveAttribute(attr, value, true)) return 0xFFFF00FF; // Loud on purpose.
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return theme.getResources().getColor(value.resourceId); // A colour state list.
    }
}
