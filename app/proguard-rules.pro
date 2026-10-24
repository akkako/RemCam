# Project-specific ProGuard rules can be added here if needed.

# Strip verbose/debug/info logging from release builds. Warnings and errors
# are kept so field failures remain diagnosable via logcat.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
