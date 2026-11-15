# R8 still shrinks and optimizes; class names are kept so stack traces in user-provided
# logcat stay readable without a mapping file.
-dontobfuscate

# ShizukuAutostart calls Shizuku.newProcess by reflection.
-keepclassmembers class rikka.shizuku.Shizuku {
    *** newProcess(...);
}
