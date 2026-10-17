package com.reed.fcmguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercise the original MILLET path independently of the night feature. */
public class SettingsGuardRegressionTest {
    private Context context;
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    @Before public void setup() {
        context=mock(Context.class); prefs=mock(SharedPreferences.class); editor=mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(eq(SettingsGuard.PREFS),anyInt())).thenReturn(prefs);
        when(context.getString(R.string.default_key)).thenReturn("MILLET_NO_RESTRICT_APP");
        when(context.getString(R.string.default_required_item)).thenReturn("com.google.android.gms");
        when(prefs.getString(anyString(),any())).thenAnswer(i->i.getArgument(1));
        when(prefs.edit()).thenReturn(editor); when(editor.putString(anyString(),anyString())).thenReturn(editor);
    }
    @Test public void commaMembershipStaysExactAndTrimmed() {
        assertTrue(SettingsGuard.hasRequiredItem(context,"a, com.google.android.gms ,b"));
        assertFalse(SettingsGuard.hasRequiredItem(context,"com.google.android.gms.other"));
        assertFalse(SettingsGuard.hasRequiredItem(context,"a_com.google.android.gms"));
    }
    @Test public void healthyMilletDoesNotWriteSettingsOrPreferences() {
        String value="a,com.google.android.gms,b";
        when(prefs.getString("last_good_MILLET_NO_RESTRICT_APP",null)).thenReturn(value);
        try(MockedStatic<Settings.System> system=mockStatic(Settings.System.class)) {
            system.when(()->Settings.System.canWrite(context)).thenReturn(true);
            system.when(()->Settings.System.getString(null,"MILLET_NO_RESTRICT_APP")).thenReturn(value);
            SettingsGuard.Result result=SettingsGuard.repair(context);
            assertTrue(result.success); assertFalse(result.changed);
            system.verify(()->Settings.System.putString(any(),anyString(),anyString()),never());
            verify(prefs,never()).edit();
        }
    }
    @Test public void milletRepairPreservesPackagesAndNeverTouchesGameKey() {
        try(MockedStatic<Settings.System> system=mockStatic(Settings.System.class)) {
            system.when(()->Settings.System.canWrite(context)).thenReturn(true);
            system.when(()->Settings.System.getString(null,"MILLET_NO_RESTRICT_APP")).thenReturn("a,b,a");
            system.when(()->Settings.System.putString(null,"MILLET_NO_RESTRICT_APP","a,b,com.google.android.gms")).thenReturn(true);
            SettingsGuard.Result result=SettingsGuard.repair(context);
            assertTrue(result.success); assertTrue(result.changed);
            system.verify(()->Settings.System.putString(null,"MILLET_NO_RESTRICT_APP","a,b,com.google.android.gms"));
            system.verify(()->Settings.System.putString(any(),eq(NightProtectionEngine.KEY),anyString()),never());
        }
    }
    @Test public void permissionDenialNeverWritesMillet() {
        try(MockedStatic<Settings.System> system=mockStatic(Settings.System.class)) {
            system.when(()->Settings.System.canWrite(context)).thenReturn(false);
            assertFalse(SettingsGuard.repair(context).success);
            system.verify(()->Settings.System.putString(any(),anyString(),anyString()),never());
        }
    }
}
