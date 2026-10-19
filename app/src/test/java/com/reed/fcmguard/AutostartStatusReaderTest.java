package com.reed.fcmguard;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutostartStatusReaderTest {
    @Test public void userInstalledAppsAreIncluded() {
        assertTrue(AutostartStatusReader.isUserInstalledApp(0));
    }

    @Test public void systemAndUpdatedSystemAppsAreExcluded() {
        assertFalse(AutostartStatusReader.isUserInstalledApp(ApplicationInfo.FLAG_SYSTEM));
        assertFalse(AutostartStatusReader.isUserInstalledApp(
                ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
    }
}
