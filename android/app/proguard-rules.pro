# ProGuard / R8 rules for OptoBot release builds.

# ---------- Kotlin ----------
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.** { *; }
-dontwarn kotlinx.**

# ---------- React Native ----------
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.swmansion.** { *; }
-keepclassmembers class * implements com.facebook.react.bridge.NativeModule { *; }
-keepclassmembers class com.facebook.react.bridge.JavaScriptModule { *; }
-keepclassmembers class * @com.facebook.react.uimanager.annotations.ReactProp { *; }
-dontwarn com.facebook.react.**

# ---------- OptoBot native bridge ----------
-keep class com.optobot.native.** { *; }

# We strip OptoBuildSecret's secret string at the source level by inlining it
# into a CharArray. R8 will inline `char[]` constants and obfuscate the class
# name, so the literal won't survive as a readable string in the .dex.
# Additional defense: never log or print BUILD_SECRET anywhere in code.
-keep,allowobfuscation class com.optobot.native.OptoBuildSecret {
    <fields>;
}

# ---------- ONNX Runtime ----------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keepclassmembers class ai.onnxruntime.** { *; }

# ---------- Asset obfuscation: strip obvious model-related strings ----------
# We strip any LITERAL string that names a known model vendor / architecture
# from the final .dex. This blocks casual `apktool d + grep` analysis.
-assumenosideeffects class com.optobot.native.OptoConfig {
    public static *** ASSET_MODEL;
    public static *** ASSET_TOKENIZER;
}

# Aggressively remove unused classes (R8 default for minify).
# Re-evaluate if any reflection breaks.
-renamesourcefileattribute SourceFile

# Disable logging in release
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ---------- Reflection used by ORT ----------
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# Keep enums used in switch statements
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
