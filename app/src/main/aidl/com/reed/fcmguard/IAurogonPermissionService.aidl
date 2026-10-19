package com.reed.fcmguard;

/** Runs a single, user-requested permission grant through the Shizuku user service. */
interface IAurogonPermissionService {
    String grantSecureSettings(String packageName) = 1;
    void destroy() = 16777114;
}
